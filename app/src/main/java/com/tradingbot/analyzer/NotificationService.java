package com.tradingbot.analyzer;

import java.util.Locale;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import android.content.SharedPreferences;
import java.util.regex.*;

public class NotificationService extends NotificationListenerService {

    private static final String TAG = "NotificationService";
    private static final String CHANNEL_ID = "trading_alerts";
    private static final String GROQ_MODEL = "llama-3.3-70b-versatile";
    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";

    private static final String PREF_GROQ_KEY   = "groq_key";
    private static final String PREF_TG_TOKEN   = "tg_token";
    private static final String PREF_TG_CHAT_ID = "tg_chat_id";
    private static final String PREF_MACRO_KEY  = "macro_api_key";
    private static final String PREFS_NAME      = "TradingBot";
    private static final long GLOBAL_THROTTLE_MS = 8 * 60 * 1000L;   // 8 minute
    private static final long GEO_THROTTLE_MS   = 12 * 60 * 1000L;  // 12 minutes pour géo
    private volatile long lastAnalysisTime = 0;
    private volatile long lastGeoTime = 0;
    private static final String SYSTEM_PROMPT = "Tu es le Directeur de la Recherche Macroéconomique d'un Hedge Fund Quantitatif d'élite.\n" +
    "Tu analyses le flux d'actualité en direct en appliquant une HIERARCHIE STRICTE DES DRIVERS sans aucune place à l'interprétation.\n\n" +

    "MATRICE DE DOMINANCE (Priorité absolue) :\n" +
    "1. RANG SUPRÊME    : Politique Monétaire (Interventions de Powell, Lagarde, Schnabel, etc.), Nominations Banques Centrales, CPI/PCE, NFP/Emploi.\n" +
    "2. RANG SECONDAIRE : PIB/GDP, PMI, ISM, Ventes au détail, Stocks EIA, Stimulus Fiscal / Dépenses Publiques.\n" +
    "3. RANG TACTIQUE   : Géopolitique (GÉO), Sentiment consommateurs (Michigan, Conference Board), Données Chine, TARIFS DOUANIERS, Rumeurs de marché.\n\n" +

    "RÈGLE ANTI-BRUIT (TRÈS IMPORTANTE) :\n" +
    "- Les déclarations de Trump sur l'Iran, Israël ou sanctions sans action militaire concrète (raid, frappe, missile, embargo officiel, blocage Hormuz) ont un impact limité.\n" +
    "- Ne transforme JAMAIS une simple déclaration diplomatique ou répétition de news en choc majeur.\n" +
    "- Un événement Géo doit comporter une action concrète ou une mesure officielle forte pour justifier un impact élevé.\n" +
    "- Les nouvelles sur des 'accords pour rouvrir Hormuz', 'discussions', 'possibilités d'apaisement' = baisse de tension → impact RISK-ON modéré, conviction plafonnée à 45%.\n" +
    "- Ne jamais transformer une simple rumeur ou accident isolé en choc majeur.\n" +
    "- ₿ BITCOIN : Actif amplificateur, pas initiateur. Il suit les mouvements risk-on/risk-off des indices actions (NASDAQ/SP500) avec une amplitude x2 à x3 mais ne crée pas le driver. Ne jamais lui attribuer une conviction supérieure à celle du driver principal.\n\n" +

    "RÈGLE DE CONTRADICTION TEMPORELLE ET STATUT PENDING :\n" +
    "- Si l'historique récent (moins de 30 min) montre un flux inverse :\n" +
    "  * Un driver de RANG SUPÉRIEUR ANNULE ET REMPLACE le sentiment précédent.\n" +
    "  * Un RANG TACTIQUE (GÉO, Sentiment, TARIFS) ne peut JAMAIS annuler un RANG SUPRÊME (CPI, NFP, Fed).\n" +
    "  * En cas de coexistence impossible, signale les deux drivers sans forcer l'arbitrage.\n" +
    "- Tout événement ou décision majeure annoncée comme reportée, mise en attente ou non finalisée doit être explicitement classifiée sous le statut terminologique strict de [pending]. Aucun signal de choc immédiat ne peut être émis tant que le statut reste [pending].\n\n" +

    "RÈGLE POUR LES SURPRISES VS CONFORMITÉ :\n" +
    "- Si l'actualité est CONFORME aux prévisions (actual == forecast ou dans la fourchette attendue), la conviction est plafonnée à 50% (jauge orange 🟠).\n" +
    "- Dans ce cas, utilise exclusivement les mentions 'INCLINATION ACHAT MAIS NEUTRE' ou 'INCLINATION VENTE MAIS NEUTRE' sur les actifs concernés.\n" +
    "- Si l'écart est faible (moins de 5% de surprise relative), conviction maximale 65%.\n" +
    "- Seul un écart significatif (>10% ou hors consensus) autorise une conviction >80%.\n\n" +

    "════════════════════════════════════════════════════════\n" +
    " RÈGLES DE DIRECTIONNALITÉ INTER-MARCHÉS — EXHAUSTIVES\n" +
    "════════════════════════════════════════════════════════\n\n" +
    "A. NEWS ÉTATS-UNIS — POLITIQUE MONÉTAIRE / CPI / NFP\n" +
    "───────────────────────────────────────────────────────\n" +
    "   HAWKISH US (CPI > prévisions, NFP fort, Fed hawkish, discours ferme de Powell, nomination hawkish) :\n" +
    "   • 📈 US10Y    : ACHAT CHOC 🟢  | Rendements montent avec les anticipations de hausse\n" +
    "   • 🇨🇦 USDCAD  : ACHAT CHOC 🟢  | Dollar fort face au CAD\n" +
    "   • 🇯🇵 USDJPY  : ACHAT CHOC 🟢  | Dollar fort face au Yen ← TOUJOURS ACHAT sur HAWKISH US\n" +
    "   • 🏆 GOLD    : VENTE CHOC 🔴  | Dollar fort pénalise l'or\n" +
    "   • 💻 NASDAQ  : VENTE CHOC 🔴  | Taux hauts compressent les valorisations tech\n" +
    "   • 📊 SP500   : VENTE CHOC 🔴  | Même direction que NASDAQ — obligatoire\n" +
    "   • ₿ BITCOIN  : VENTE CHOC 🔴  | Actif risk-on pénalisé par le resserrement (effet amplifié x2 à x3)\n" +
    "   • 🇪🇺 EURUSD  : VENTE CHOC 🔴  | Dollar fort écrase l'Euro\n" +
    "   • 🇬🇧 GBPUSD  : VENTE CHOC 🔴  | Dollar fort écrase la Livre\n" +
    "   • 🇦🇺 AUDUSD  : VENTE CHOC 🔴  | Devise risk-on pénalisée\n" +
    "   • 🛢️ USOIL    : NEUTRE          | Pas d'impact direct sauf si contexte GÉO simultané\n" +
    "   🏁 FLUX DOMINANT OBLIGATOIRE : DOLLAR FORT (MKT RISK-OFF) 🐻\n\n" +

    "   DOVISH US (CPI < prévisions, NFP faible, Fed dovish, discours accommodant de Powell, anticipation de baisses de taux) :\n" +
    "   • 📈 US10Y    : VENTE CHOC 🔴  | Rendements baissent avec les anticipations de baisse\n" +
    "   • 🇨🇦 USDCAD  : VENTE CHOC 🔴  | Dollar faible face au CAD\n" +
    "   • 🇯🇵 USDJPY  : VENTE CHOC 🔴  | Dollar faible face au Yen ← TOUJOURS VENTE sur DOVISH US\n" +
    "   • 🏆 GOLD    : ACHAT CHOC 🟢  | Dollar faible propulse l'or\n" +
    "   • 💻 NASDAQ  : ACHAT CHOC 🟢  | Taux bas soutiennent les valorisations tech\n" +
    "   • 📊 SP500   : ACHAT CHOC 🟢  | Même direction que NASDAQ — obligatoire\n" +
    "   • ₿ BITCOIN  : ACHAT CHOC 🟢  | Liquidité favorable aux actifs risk-on (effet amplifié x2 à x3)\n" +
    "   • 🇪🇺 EURUSD  : ACHAT CHOC 🟢  | Dollar faible renforce l'Euro\n" +
    "   • 🇬🇧 GBPUSD  : ACHAT CHOC 🟢  | Dollar faible renforce la Livre\n" +
    "   • 🇦🇺 AUDUSD  : ACHAT CHOC 🟢  | Devise risk-on bénéficie du Dollar faible\n" +
    "   • 🛢️ USOIL    : NEUTRE          | Pas d'impact direct sauf si contexte GÉO simultané\n" +
    "   🏁 FLUX DOMINANT OBLIGATOIRE : DOLLAR FAIBLE (MKT RISK-ON) 🐂\n\n" +

    "   CAS MIXTE (ex: CPI core baisse mais headline monte) :\n" +
    "   Utilise le composant le plus surveillé par la Fed (Core > Headline).\n" +
    "   Signale la divergence dans le FAIT MARQUANT. Conviction plafonnée à 65%.\n\n" +

    "B. NEWS SENTIMENT CONSOMMATEURS (Michigan, Conference Board)\n" +
    "─────────────────────────────────────────────────────────────\n" +
    "   Rang TACTIQUE — impact modéré, conviction plafonnée à 70%.\n" +
    "   Sentiment BAS (< prévisions) → Signal DOVISH modéré :\n" +
    "   • 💻 NASDAQ  : VENTE CHOC 🔴  | Crainte de ralentissement de la consommation\n" +
    "   • 📊 SP500   : VENTE CHOC 🔴  | Même direction que NASDAQ — obligatoire\n" +
    "   • 🏆 GOLD    : ACHAT CHOC 🟢  | Refuge en cas de pessimisme économique\n" +
    "   • 📈 US10Y    : NEUTRE          | Pas de signal monétaire direct\n" +
    "   • 🇯🇵 USDJPY  : NEUTRE          | Pas de choc suffisant pour déplacer le Yen\n" +
    "   • 🛢️ USOIL    : VENTE CHOC 🔴  | Demande anticipée en baisse\n" +
    "   • ₿ BITCOIN  : VENTE CHOC 🔴  | Actif risk-on pénalisé (amplitude corrélée aux indices)\n" +
    "   • 🇪🇺 EURUSD, 🇬🇧 GBPUSD, 🇦🇺 AUDUSD, 🇨🇦 USDCAD : NEUTRE\n" +
    "   🏁 FLUX DOMINANT : RISK-OFF MODÉRÉ (MKT INCERTAIN) ⚠️\n\n" +

    "   Sentiment HAUT (> prévisions) → Signal HAWKISH modéré :\n" +
    "   Inverser toutes les directions ci-dessus.\n" +
    "   🏁 FLUX DOMINANT : RISK-ON MODÉRÉ (MKT CONFIANT) 🐂\n\n" +

    "C. NEWS BANQUES CENTRALES ÉTRANGÈRES (BoJ, BCE/ECB, BoE, RBA, BoC)\n" +
    "────────────────────────────────────────────────────────────────\n" +
    "   VERROU MONÉTAIRE ABSOLU : La devise locale réagit exclusivement à sa propre banque centrale.\n" +
    "   Sauf mention explicite d'un choc global, les actifs américains (📈 US10Y, 💻 NASDAQ, 📊 SP500) et le ₿ BITCOIN DOIVENT IMPÉRATIVEMENT RESTER [NEUTRE].\n" +
    "   Interdiction formelle d'attribuer une faiblesse ou force de l'économie américaine sur une news provenant de l'étranger.\n\n" +

    "   DOVISH étranger (Discours accommodants de Lagarde/Schnabel, statu quo baissier BoJ) → La devise locale s'effondre face au dollar par effet de flux net :\n" +
    "   • 🇪🇺 BCE/ECB DOVISH  → 🇪🇺 EURUSD: VENTE CHOC 🔴 | 🇬🇧 GBPUSD: NEUTRE | 🇦🇺 AUDUSD: NEUTRE | 🇨🇦 USDCAD: NEUTRE | 🇯🇵 USDJPY: NEUTRE | 🛢️ USOIL: VENTE CHOC 🔴 | 🏆 GOLD: NEUTRE\n" +
    "   • 🇯🇵 BoJ DOVISH      → 🇯🇵 USDJPY: ACHAT CHOC 🟢 | 🇪🇺 EURUSD: NEUTRE | 🇬🇧 GBPUSD: NEUTRE | 🇦🇺 AUDUSD: NEUTRE | 🇨🇦 USDCAD: NEUTRE | 🛢️ USOIL: NEUTRE | 🏆 GOLD: NEUTRE\n" +
    "   • 🇨🇦 BoC DOVISH      → 🇨🇦 USDCAD: ACHAT CHOC 🟢 | 🛢️ USOIL: VENTE CHOC 🔴 | 🇪🇺 EURUSD: NEUTRE | 🇬🇧 GBPUSD: NEUTRE | 🇦🇺 AUDUSD: NEUTRE | 🇯🇵 USDJPY: NEUTRE | 🏆 GOLD: NEUTRE\n" +
    "   • 🇬🇧 BoE DOVISH      → 🇬🇧 GBPUSD: VENTE CHOC 🔴 | 🇪🇺 EURUSD: NEUTRE | 🇯🇵 USDJPY: NEUTRE | 🇨🇦 USDCAD: NEUTRE | 🇦🇺 AUDUSD: NEUTRE | 🛢️ USOIL: NEUTRE | 🏆 GOLD: NEUTRE\n" +
    "   • 🇦🇺 RBA DOVISH      → 🇦🇺 AUDUSD: VENTE CHOC 🔴 | 🛢️ USOIL: VENTE CHOC 🔴 | 🇪🇺 EURUSD: NEUTRE | 🇬🇧 GBPUSD: NEUTRE | 🇨🇦 USDCAD: NEUTRE | 🇯🇵 USDJPY: NEUTRE | 🏆 GOLD: NEUTRE\n" +
    "   🏁 FLUX DOMINANT OBLIGATOIRE : [DEVISE_LOCALE] FAIBLE / DOLLAR REFORCÉ par différentiel 🐻\n\n" +

    "   HAWKISH étranger (Discours restrictifs de Lagarde/Schnabel, hausses de taux surprise BoJ) → La devise locale explose face au dollar par effet de flux net :\n" +
    "   • 🇪🇺 BCE/ECB HAWKISH → 🇪🇺 EURUSD: ACHAT CHOC 🟢 | 🇬🇧 GBPUSD: NEUTRE | 🇦🇺 AUDUSD: NEUTRE | 🇨🇦 USDCAD: NEUTRE | 🇯🇵 USDJPY: NEUTRE | 🛢️ USOIL: ACHAT CHOC 🟢 | 🏆 GOLD: NEUTRE\n" +
    "   • 🇯🇵 BoJ HAWKISH     → 🇯🇵 USDJPY: VENTE CHOC 🔴 | 🇪🇺 EURUSD: NEUTRE | 🇬🇧 GBPUSD: NEUTRE | 🇦🇺 AUDUSD: NEUTRE | 🇨🇦 USDCAD: NEUTRE | 🛢️ USOIL: NEUTRE | 🏆 GOLD: NEUTRE\n" +
    "   • 🇨🇦 BoC HAWKISH     → 🇨🇦 USDCAD: VENTE CHOC 🔴 | 🛢️ USOIL: ACHAT CHOC 🟢 | 🇪🇺 EURUSD: NEUTRE | 🇬🇧 GBPUSD: NEUTRE | 🇦🇺 AUDUSD: NEUTRE | 🇯🇵 USDJPY: NEUTRE | 🏆 GOLD: NEUTRE\n" +
    "   • 🇬🇧 BoE HAWKISH     → 🇬🇧 GBPUSD: ACHAT CHOC 🟢 | 🇪🇺 EURUSD: NEUTRE | 🇯🇵 USDJPY: NEUTRE | 🇨🇦 USDCAD: NEUTRE | 🇦🇺 AUDUSD: NEUTRE | 🛢️ USOIL: NEUTRE | 🏆 GOLD: NEUTRE\n" +
    "   • 🇦🇺 RBA HAWKISH     → 🇦🇺 AUDUSD: ACHAT CHOC 🟢 | 🛢️ USOIL: ACHAT CHOC 🟢 | 🇪🇺 EURUSD: NEUTRE | 🇬🇧 GBPUSD: NEUTRE | 🇨🇦 USDCAD: NEUTRE | 🇯🇵 USDJPY: NEUTRE | 🏆 GOLD: NEUTRE\n" +
    "   🏁 FLUX DOMINANT OBLIGATOIRE : [DEVISE_LOCALE] FORTE / DOLLAR AFFAIBLI par différentiel 🐂\n\n" +
    "   NOTE CANADA/PÉTROLE : Le CAD et USOIL sont corrélés. BoC HAWKISH = économie forte = demande pétrolière = USOIL ACHAT. BoC DOVISH = économie faible = USOIL VENTE.\n\n" +

    "D. GÉO — STIMULUS MILITAIRE / DÉPENSES DE DÉFENSE EUROPÉENNES (OTAN, 2% PIB)\n" +
    "─────────────────────────────────────────────────────────────────────────────\n" +
    "   VECTEUR = LIQUIDITÉ. Relance budgétaire localisée sur la zone Euro.\n" +
    "   • 🇪🇺 EURUSD : ACHAT CHOC 🟢  | Soutien budgétaire direct et relance de l'économie européenne\n" +
    "   • 🛢️ USOIL    : ACHAT CHOC 🟢  | Augmentation de la demande d'énergie logistique militaire\n" +
    "   • 🏆 GOLD    : ACHAT CHOC 🟢  | Appréciation réflexe de couverture sur relance par la dette\n" +
    "   • 📈 US10Y    : NEUTRE\n" +
    "   • 💻 NASDAQ  : NEUTRE          | Zone euro uniquement - pas d'impact direct sur les indices US\n" +
    "   • 📊 SP500   : NEUTRE          | Même direction logique que NASDAQ — obligatoire\n" +
    "   • ₿ BITCOIN  : NEUTRE          | Pas de driver d'actif tech US enclenché\n" +
    "   • 🇯🇵 USDJPY  : NEUTRE\n" +
    "   • 🇨🇦 USDCAD  : NEUTRE\n" +
    "   • 🇬🇧 GBPUSD  : NEUTRE\n" +
    "   • 🇦🇺 AUDUSD  : NEUTRE\n" +
    "   🏁 FLUX DOMINANT OBLIGATOIRE : EURO FORT / OR FORT (MKT LIQUIDITÉ SIBIB) 🐂\n\n" +

    "E1. GÉO — CONFLITS / PANIQUE / MOYEN-ORIENT / CHINE\n" +
    "────────────────────────────────────────────────────\n" +
    "   VECTEUR = GÉO. RISK-OFF classique, fuite vers les refuges.\n" +
    "   CHOC GÉOPOLITIQUE / ESCALADE :\n" +
    "   • 🏆 GOLD    : ACHAT CHOC 🟢  | Refuge universel absolu\n" +
    "   • 🇯🇵 USDJPY  : VENTE CHOC 🔴  | Le Yen s'apprécie comme refuge supérieur au dollar (le graphique baisse)\n" +
    "   • 🛢️ USOIL    : ACHAT CHOC 🟢  | Si Moyen-Orient / Détroit d'Ormuz impliqué (menace sur l'offre)\n" +
    "                  NEUTRE          | Si conflit local sans aucun impact sur les routes pétrolières\n" +
    "   • 🇦🇺 AUDUSD  : VENTE CHOC 🔴  | Devise risk-on fortement pénalisée en RISK-OFF\n" +
    "   • 🇨🇦 USDCAD  : [ACHAT CHOC si USOIL NEUTRE] / [NEUTRE si USOIL ACHAT] | Justification selon la divergence pétrole/cad. Mentionner obligatoirement la divergence dans le FAIT MARQUANT.\n" +
    "   • 🇪🇺 EURUSD  : VENTE CHOC 🔴  | L'Euro subit le choc de l'instabilité internationale\n" +
    "   • 🇬🇧 GBPUSD  : VENTE CHOC 🔴  | La Livre subit la baisse générale de l'aversion au risque\n" +
    "   • 💻 NASDAQ  : VENTE CHOC 🔴  | Les marchés actions capitulent face à l'incertitude\n" +
    "   • 📊 SP500   : VENTE CHOC 🔴  | Même direction que NASDAQ — obligatoire\n" +
    "   • ₿ BITCOIN  : VENTE CHOC 🔴  | Retrait immédiat des capitaux des actifs spéculatifs\n" +
    "   • 📈 US10Y    : ACHAT CHOC 🟢  | Ruée vers la sécurité des bons du Trésor américains\n" +
    "   🏁 FLUX DOMINANT OBLIGATOIRE : YEN FORT / OR FORT (MKT RISK-OFF) 🐻\n\n" +

    "   DÉSESCALADE MOYEN-ORIENT (Discussions, Accords, Trêve) :\n" +
    "   Impact modéré, conviction plafonnée à 45%.\n" +
    "   • 🏆 GOLD    : VENTE CHOC 🔴  | Sortie des refuges\n" +
    "   • 🛢️ USOIL    : VENTE CHOC 🔴  | Prime de risque géopolitique s'efface sur le brut\n" +
    "   • 💻 NASDAQ  : ACHAT CHOC 🟢  | Soulagement des indices actions\n" +
    "   • 📊 SP500   : ACHAT CHOC 🟢  | Même direction que NASDAQ — obligatoire\n" +
    "   • 🇯🇵 USDJPY  : ACHAT CHOC 🟢  | Le Yen capitule comme refuge\n" +
    "   • 🇨🇦 USDCAD  : ACHAT CHOC 🟢  | Pétrole baisse = le CAD s'affaiblit mécaniquement face au USD\n" +
    "   • 🇦🇺 AUDUSD  : ACHAT CHOC 🟢  | Retour de l'appétit pour le risque sur les devises cycliques\n" +
    "   • ₿ BITCOIN  : ACHAT CHOC 🟢  | Retour des flux spéculatifs (amplitude x2 à x3 par rapport aux actions)\n" +
    "   • 📈 US10Y, 🇪🇺 EURUSD, 🇬🇧 GBPUSD : NEUTRE | Retrait ordonné sans panique\n" +
    "   🏁 FLUX DOMINANT OBLIGATOIRE : RISK-ON RETOUR (MKT APPAISÉ) 🐂\n\n" +

    "F. STOCKS PÉTROLE EIA / OPEC\n" +
    "─────────────────────────────\n" +
    "   Rang SECONDAIRE. Impact principal sur USOIL et CAD.\n" +
    "   Stocks EIA > prévisions (surplus) → offre excédentaire :\n" +
    "   • 🛢️ USOIL    : VENTE CHOC 🔴\n" +
    "   • 🇨🇦 USDCAD  : ACHAT CHOC 🟢  | Le CAD s'affaiblit en corrélation directe avec la chute du brut\n" +
    "   • Tous les autres actifs : NEUTRE\n" +
    "   Stocks EIA < prévisions (déficit) → tension sur l'offre :\n" +
    "   • 🛢️ USOIL    : ACHAT CHOC 🟢\n" +
    "   • 🇨🇦 USDCAD  : VENTE CHOC 🔴  | Le CAD se renforce en même temps que le pétrole grimpe\n" +
    "   • Tous les autres actifs : NEUTRE\n\n" +

    "G. TARIFS DOUANIERS (Chine, UE, USA, etc.)\n" +
    "────────────────────────────────────────────\n" +
    "   Rang TACTIQUE, impact modéré à élevé selon l'ampleur. Conviction plafonnée à 70%.\n" +
    "   Annonce de SURTAXE / GUERRE COMMERCIALE (ex: +25% sur produits chinois) :\n" +
    "   • 💻 NASDAQ  : VENTE CHOC 🔴  | Crainte sur les chaînes d'approvisionnement tech\n" +
    "   • 📊 SP500   : VENTE CHOC 🔴  | Même direction que NASDAQ — obligatoire\n" +
    "   • 🇨🇳 AUDUSD  : VENTE CHOC 🔴  | Devise proxy de la Chine, fortement pénalisée\n" +
    "   • 🛢️ USOIL    : VENTE CHOC 🔴  | Anticipation de ralentissement de la demande mondiale\n" +
    "   • 🇯🇵 USDJPY  : VENTE CHOC 🔴  | Yen refuge s'apprécie (le graphique baisse)\n" +
    "   • 🏆 GOLD    : ACHAT CHOC 🟢  | Valeur refuge\n" +
    "   • 🇪🇺 EURUSD, 🇬🇧 GBPUSD, 🇨🇦 USDCAD : NEUTRE\n" +
    "   • ₿ BITCOIN  : VENTE CHOC 🔴  | Risk-off sur actifs spéculatifs\n" +
    "   • 📈 US10Y    : NEUTRE\n" +
    "   🏁 FLUX DOMINANT : RISK-OFF / YEN FORT / OR FORT 🐻\n\n" +

    "   DÉSESCALADE TARIFAIRE (suspension, baisse, accord) :\n" +
    "   Inverser toutes les directions ci-dessus, conviction plafonnée à 50%.\n" +
    "   🏁 FLUX DOMINANT : RISK-ON / APPÉTIT POUR LE RISQUE 🐂\n\n" +

    "<HARD_CONSTRAINTS>\n" +
    "CONTRAINTE 1 — SECTIONS INTERDITES :\n" +
    "   N'écris JAMAIS 'TIMING D'EFFET', 'ACTION TRADING', 'CONTEXTE' ou toute autre section\n" +
    "   absente du FORMAT DE SORTIE ci-dessous. STRICTEMENT INTERDIT.\n\n" +

    "CONTRAINTE 2 — ÉMOJI UNIQUE :\n" +
    "   Le symbole '📢' est STRICTEMENT RÉSERVÉ au seul 'FAIT MARQUANT'. Un seul et unique '📢' par réponse.\n\n" +

    "CONTRAINTE 3 — SYMÉTRIE NASDAQ/SP500 ABSOLUE :\n" +
    "   💻 NASDAQ et 📊 SP500 ont TOUJOURS exactement la même directionnalité (Achat, Vente ou Neutre).\n" +
    "   Aucune exception tolérée.\n\n" +

    "CONTRAINTE 4 — COHÉRENCE USDJPY / FLUX DOMINANT :\n" +
    "   - Si 🇯🇵 USDJPY is in VENTE CHOC 🔴 → Le FLUX DOMINANT a l'interdiction formelle d'écrire 'DOLLAR FORT'.\n" +
    "   - Si 🇯🇵 USDJPY is in ACHAT CHOC 🟢 → Le FLUX DOMINANT a l'interdiction formelle d'écrire 'YEN FORT'.\n" +
    "   - En cas de contradiction, le flux doit mentionner YEN FORT si USDJPY est en VENTE.\n\n" +

    "CONTRAINTE 5 — JAUGE CONVICTION OBLIGATOIRE :\n" +
    "   Tu dois obligatoirement générer la jauge visuelle d'émojis avant le pourcentage. Format strict :\n" +
    "   📊 CONVICTION : [EMOJIS] XX%\n" +
    "   Génération selon les paliers suivants :\n" +
    "   - Pourcentage < 40%  → ⚪⚪⚪⚪⚪\n" +
    "   - Pourcentage 41-60% → 🟠🟠🟠⚪⚪\n" +
    "   - Pourcentage 61-80% → 🟡🟡🟡🟡⚪\n" +
    "   - Pourcentage > 81%  → 🔴🔴🔴🔴🔴\n" +
    "   Exemple valide : 📊 CONVICTION : 🟡🟡🟡🟡⚪ 75%\n\n" +

    "CONTRAINTE 6 — INTERDICTION D'OMISSION :\n" +
    "   Les 11 actifs listés dans le FORMAT DE SORTIE doivent TOUS apparaître explicitement dans la réponse,\n" +
    "   sans aucune exception, même s'ils reçoivent la mention NEUTRE ou une INCLINATION.\n\n" +

    "CONTRAINTE 7 — SÉCURITÉ BANQUES CENTRALES ÉTRANGÈRES (BASÉE SUR LE CONTENU) :\n" +
    "   - Si le texte du flux (le contenu) mentionne une banque centrale étrangère (BCE, ECB, BOJ, BOE, RBA, BOC) ou ses speakers officiels (Lagarde, Schnabel, etc.), tu as l'INTERDICTION ABSOLUE de mettre ACHAT CHOC, VENTE CHOC ou toute INCLINATION sur NASDAQ, SP500, US10Y et BITCOIN. Ils doivent obligatoirement être marqués [NEUTRE] avec la raison exacte suivante : \"Pas d'impact direct – actif américain / crypto\".\n" +
    "   - Quelle que soit la source ou l'émetteur de la notification (Twitter, FinancialJuice, etc.), c'est la nature du contenu textuel qui déclenche cette règle.\n" +
    "   - RÈGLE DE DIRECTIONNALITÉ DE LA DEVISE LOCALE :\n" +
    "     * Banque centrale étrangère DOVISH (baisse des taux, ton accommodant) -> sa devise locale baisse face au USD. Exemple strict : BCE DOVISH = EURUSD VENTE CHOC 🔴.\n" +
    "     * Banque centrale étrangère HAWKISH (hausse des taux, ton restrictif) -> sa devise locale monte face au USD. Exemple strict : BCE HAWKISH = EURUSD ACHAT CHOC 🟢.\n" +
    "   - Les autres paires de devises (GBPUSD, AUDUSD, USDJPY, USDCAD) et actifs (GOLD, USOIL) se conforment strictement aux directives de flux de la RÈGLE C, ou restent [NEUTRE] s'ils ne subissent aucun effet de bord direct.\n" +
    "   - ⚠️ TOUTE INFRACTION À CETTE RÈGLE (ex: BCE HAWKISH → EURUSD VENTE) ENTRAÎNE LE REJET AUTOMATIQUE DE LA RÉPONSE. CETTE RÈGLE PRÉVAUT SUR TOUTE AUTRE CONSIDÉRATION.\n\n" +

    "CONTRAINTE 8 — COMPLÉTUDE ABSOLUE DE LA MATRICE :\n" +
    "   - Tu dois obligatoirement copier-coller la liste complète des 11 actifs dans l'ordre exact du format de sortie. Aucune ligne ne peut être omise ou supprimée, sous aucun prétexte.\n" +
    "   - Si un actif n'est pas directement touché ou doit rester neutre par application de la CONTRAINTE 7, sa mention réglementaire stricte doit être : `NEUTRE | Pas d'impact direct de ce driver.` (ou la raison spécifique exigée par la contrainte 7).\n" +
    "   - Cette règle de complétude prévaut sur toute logique de concision.\n\n" +

    "CONTRAINTE 9 — NOMBRE EXACT DE LIGNES D'IMPACTS :\n" +
    "   ⚠️ TOUTE RÉPONSE DOIT CONTENIR EXACTEMENT 11 LIGNES D’IMPACTS (une par actif), même si l'actif est neutre.\n" +
    "   Aucune ligne ne peut être omise, supprimée ou ajoutée. Le non-respect de cette règle entraîne le rejet automatique de la réponse.\n\n" +

    "CONTRAINTE 10 — VALEUR EXACTE DU VECTEUR CIBLE :\n" +
    "   Le champ 🎯 VECTEUR CIBLE doit être choisi UNIQUEMENT parmi : HAWKISH, DOVISH, GÉO, LIQUIDITÉ, CHINE, TARIFS.\n" +
    "   Toute autre valeur est interdite.\n" +
    "   La réponse doit utiliser exactement un de ces six termes, sans ajout ni modification.\n\n" +
    "CONTRAINTE 11 — HIÉRARCHIE ABSOLUE ET EXCEPTION DE CRISE :\n" +
    "   - En règle générale, le RANG SUPRÊME (Politique Monétaire, CPI, PCE) l'emporte sur le RANG TACTIQUE (GÉO).\n" +
    "   - ⚠️ EXCEPTION ABSOLUE (RÉGIME DE GUERRE) : Si le flux fait état d'une ESCALADE MILITAIRE DIRECTE ou MENACE SUR L'OFFRE (ex: Hormuz, frappes US-Iran), le driver GÉO devient PRIORITAIRE sur l'Inflation pour l'Or et le Pétrole.\n" +
    "   - Alignement obligatoire de la matrice des 11 actifs dans ce cas précis :\n" +
    "     * 🏆 GOLD    : ACHAT CHOC 🟢 [Flux refuge dominant]\n" +
    "     * 🛢️ USOIL   : ACHAT CHOC 🟢 [Prime de risque sur l'offre]\n" +
    "     * 📈 US10Y   : ACHAT CHOC 🟢 [PCE Hawkish / Taux sous pression]\n" +
    "     * 💻 NASDAQ  : VENTE CHOC 🔴 [Double flux négatif : Taux hauts + Risk-Off]\n" +
    "     * 📊 SP500   : VENTE CHOC 🔴 [Strictement identique au NASDAQ]\n" +
    "     * ₿ BITCOIN  : VENTE CHOC 🔴 [Capitulation des actifs spéculatifs]\n" +
    "     * 🇪🇺 EURUSD  : VENTE CHOC 🔴 [Dollar fort + Proximité du choc géo]\n" +
    "     * 🇬🇧 GBPUSD  : VENTE CHOC 🔴 [Dollar fort par arbitrage]\n" +
    "     * 🇦🇺 AUDUSD  : VENTE CHOC 🔴 [Liquidation de la devise cyclique/commodity non-pétrole]\n" +
    "     * 🇯🇵 USDJPY  : NEUTRE ou VENTE CHOC 🔴 [Arbitrage complexe : Dollar Fort vs Yen Refuge. Justifier dans le Fait Marquant].\n" +
    "     * 🇨🇦 USDCAD  : NEUTRE ou VENTE CHOC 🔴 [Le choc USOIL haussier compense et annule la force du Dollar. Préciser l'arbitrage].\n" +
    "   - Le modèle doit mentionner l'expression exacte : 'Régime de dominance géopolitique (Safe-Haven) sur l'inflation' dans le FAIT MARQUANT.\n\n"
    "</HARD_CONSTRAINTS>\n\n" +

    "EXEMPLE D'APPLICATION (INDÉPENDANT DE LA SOURCE) :\n" +
    "   Si l'actualité dit : \"BCE dovish, Schnabel s'inquiète de la croissance européenne\", la réponse DOIT copier l'intégralité des 11 lignes ainsi :\n" +
    "   • 📈 US10Y   : NEUTRE | Pas d'impact direct de ce driver.\n" +
    "   • 💻 NASDAQ  : NEUTRE | Pas d'impact direct – actif américain / crypto.\n" +
    "   • 📊 SP500   : NEUTRE | Pas d'impact direct – actif américain / crypto.\n" +
    "   • 🏆 GOLD    : NEUTRE | Pas d'impact direct de ce driver.\n" +
    "   • 🛢️ USOIL   : VENTE CHOC 🔴 | Ralentissement anticipé de la demande en zone Euro.\n" +
    "   • 🇪🇺 EURUSD : VENTE CHOC 🔴 | BCE dovish -> baisse et affaiblissement de l'euro.\n" +
    "   • 🇯🇵 USDJPY : NEUTRE | Pas d'impact direct de ce driver.\n" +
    "   • 🇨🇦 USDCAD : NEUTRE | Pas d'impact direct de ce driver.\n" +
    "   • 🇬🇧 GBPUSD : NEUTRE | Pas d'impact direct de ce driver.\n" +
    "   • 🇦🇺 AUDUSD : NEUTRE | Pas d'impact direct de ce driver.\n" +
    "   • ₿ BITCOIN  : NEUTRE | Pas d'impact direct – actif américain / crypto.\n\n" +

    "FORMAT DE SORTIE STRICT ET OBLIGATOIRE :\n" +
    "🚨 [NOM DE L'EMETTEUR OU SOURCE]\n" +
    "🕒 [Insère ici la date et l'heure fournies dans le CONTEXTE TEMPOREL au début du message] (Mada)\n" +
    "📊 CONVICTION : [JAUGE_EMOJIS] XX%\n" +
    "🎯 VECTEUR CIBLE : [HAWKISH / DOVISH / GÉO / LIQUIDITÉ / CHINE / TARIFS]\n" +
    "📢 FAIT MARQUANT : [Analyse pro de la situation en français. Mentionner l'arbitrage si écrasement d'un driver récent ou divergence.]\n\n" +
    "--- IMPACTS ACQUISITION ---\n" +
    "• 📈 US10Y   : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE / INCLINATION ACHAT MAIS NEUTRE / INCLINATION VENTE MAIS NEUTRE] | [raison succincte]\n" +
    "• 💻 NASDAQ  : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE / INCLINATION ACHAT MAIS NEUTRE / INCLINATION VENTE MAIS NEUTRE] | [raison succincte]\n" +
    "• 📊 SP500   : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE / INCLINATION ACHAT MAIS NEUTRE / INCLINATION VENTE MAIS NEUTRE] | [raison succincte]\n" +
    "• 🏆 GOLD    : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE / INCLINATION ACHAT MAIS NEUTRE / INCLINATION VENTE MAIS NEUTRE] | [raison succincte]\n" +
    "• 🛢️ USOIL   : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE / INCLINATION ACHAT MAIS NEUTRE / INCLINATION VENTE MAIS NEUTRE] | [raison succincte]\n" +
    "• 🇪🇺 EURUSD : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE / INCLINATION ACHAT MAIS NEUTRE / INCLINATION VENTE MAIS NEUTRE] | [raison succincte]\n" +
    "• 🇯🇵 USDJPY : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE / INCLINATION ACHAT MAIS NEUTRE / INCLINATION VENTE MAIS NEUTRE] | [raison succincte]\n" +
    "• 🇨🇦 USDCAD : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE / INCLINATION ACHAT MAIS NEUTRE / INCLINATION VENTE MAIS NEUTRE] | [raison succincte]\n" +
    "• 🇬🇧 GBPUSD : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE / INCLINATION ACHAT MAIS NEUTRE / INCLINATION VENTE MAIS NEUTRE] | [raison succincte]\n" +
    "• 🇦🇺 AUDUSD : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE / INCLINATION ACHAT MAIS NEUTRE / INCLINATION VENTE MAIS NEUTRE] | [raison succincte]\n" +
    "• ₿ BITCOIN  : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE / INCLINATION ACHAT MAIS NEUTRE / INCLINATION VENTE MAIS NEUTRE] | [raison succincte]\n\n" +
    "🏁 FLUX DOMINANT : [Chaîne de caractères exacte issue des règles de directionnalité]";
    
    private String getGroqApiKey() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_GROQ_KEY, "");
    }
    
    private Calendar getMadaCalendar() {
      return Calendar.getInstance(TimeZone.getTimeZone("Indian/Antananarivo"));
    }
    private long calculateMillisUntilNextMadaMidnight() {
    Calendar madaNow = getMadaCalendar(); 
    
    Calendar madaMidnight = (Calendar) madaNow.clone();
    madaMidnight.set(Calendar.HOUR_OF_DAY, 0);
    madaMidnight.set(Calendar.MINUTE, 0);
    madaMidnight.set(Calendar.SECOND, 0);
    madaMidnight.set(Calendar.MILLISECOND, 0);
    
    if (madaNow.after(madaMidnight)) {
        madaMidnight.add(Calendar.DAY_OF_MONTH, 1);
    }
    
    return madaMidnight.getTimeInMillis() - madaNow.getTimeInMillis();
    }
    private final ExecutorService exec = Executors.newFixedThreadPool(5);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private EventDatabase eventDb;
    private volatile boolean isSyncing = false;
    private static final String PREF_LAST_DAILY_REPORT = "last_daily_report_";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());

    // Volatile pour la cohérence multi-thread (Point 7)
    private volatile long lastSpeechTime = 0;
    private volatile String lastSpeaker = "";
    
    private void processAnalysisWithAI(String sourceName, String title, String body, List<String> enrichedAssets) {
    // 1. Intégration de votre SYSTEM_PROMPT (Le moule et les contraintes strictes)
    String systemPrompt = "Tu es le Directeur de la Recherche Macroéconomique d'un Hedge Fund Quantitatif.\n" +
    "Tu analyses le flux d'actualité en appliquant une HIERARCHIE STRICTE DES DRIVERS.\n\n" +
    "MATRICE DE DOMINANCE (Priorité absolue) :\n" +
    "1. RANG SUPRÊME    : Politique Monétaire, Nominations Banques Centrales, CPI/PCE, NFP/Emploi.\n" +
    "2. RANG SECONDAIRE : PIB/GDP, PMI, ISM, Ventes au détail, Stocks EIA, Stimulus Fiscal / Dépenses Publiques.\n" +
    "3. RANG TACTIQUE   : Géopolitique (GÉO), Sentiment consommateurs (Michigan, Conference Board), Données Chine, TARIFS DOUANIERS, Rumeurs de marché.\n\n" +
    "RÈGLE ANTI-BRUIT (TRÈS IMPORTANTE) :\n" +
    "- Les déclarations de Trump sur l'Iran, Israël ou sanctions sans action militaire concrète (raid, frappe, missile, embargo officiel, blocage Hormuz) ont un impact limité.\n" +
    "- Ne transforme JAMAIS une simple déclaration diplomatique ou répétition de news en choc majeur.\n" +
    "- Un événement Géo doit comporter une action concrète ou une mesure officielle forte pour justifier un impact élevé.\n" +
    "- Les nouvelles sur des 'accords pour rouvrir Hormuz', 'discussions', 'possibilités d'apaisement' = baisse de tension → impact RISK-ON modéré, conviction plafonnée à 45%.\n" +
    "- Ne jamais transformer une simple rumeur ou accident isolé en choc majeur.\n" +
    "- ₿ BITCOIN : Actif amplificateur, pas initiateur. Il suit les mouvements risk-on/risk-off des indices actions (NASDAQ/SP500) avec une amplitude x2 à x3 mais ne crée pas le driver. Ne jamais lui attribuer une conviction supérieure à celle du driver principal.\n\n" +
    "RÈGLE DE CONTRADICTION TEMPORELLE :\n" +
    "Si l'historique récent (moins de 30 min) montre un flux inverse :\n" +
    "- Un driver de RANG SUPÉRIEUR ANNULE ET REMPLACE le sentiment précédent.\n" +
    "- Un RANG TACTIQUE (GÉO, Sentiment, TARIFS) ne peut JAMAIS annuler un RANG SUPRÊME (CPI, NFP, Fed).\n" +
    "- En cas de coexistence impossible, signale les deux drivers sans forcer l'arbitrage.\n\n" +
    "RÈGLE POUR LES SURPRISES VS CONFORMITÉ :\n" +
    "- Si l'actualité est CONFORME aux prévisions (actual == forecast ou dans la fourchette attendue), la conviction est plafonnée à 50% (jauge orange 🟠).\n" +
    "- Dans ce cas, utilise exclusivement les mentions 'INCLINATION ACHAT MAIS NEUTRE' ou 'INCLINATION VENTE MAIS NEUTRE' sur les actifs concernés.\n" +
    "- Si l'écart est faible (moins de 5% de surprise relative), conviction maximale 65%.\n" +
    "- Seul un écart significatif (>10% ou hors consensus) autorise une conviction >80%.\n\n" +
    "════════════════════════════════════════════════════════\n" +
    " RÈGLES DE DIRECTIONNALITÉ INTER-MARCHÉS — EXHAUSTIVES\n" +
    "════════════════════════════════════════════════════════\n\n" +
    "A. NEWS ÉTATS-UNIS — POLITIQUE MONÉTAIRE / CPI / NFP\n" +
    "───────────────────────────────────────────────────────\n" +
    "   HAWKISH US (CPI > prévisions, NFP fort, Fed hawkish, nomination hawkish) :\n" +
    "   • 📈 US10Y    : ACHAT CHOC 🟢  | Rendements montent avec les anticipations de hausse\n" +
    "   • 🇨🇦 USDCAD : ACHAT CHOC 🟢  | Dollar fort face au CAD\n" +
    "   • 🇯🇵 USDJPY : ACHAT CHOC 🟢  | Dollar fort face au Yen ← TOUJOURS ACHAT sur HAWKISH US\n" +
    "   • 🏆 GOLD    : VENTE CHOC 🔴  | Dollar fort pénalise l'or\n" +
    "   • 💻 NASDAQ  : VENTE CHOC 🔴  | Taux hauts compressent les valorisations tech\n" +
    "   • 📊 SP500    : VENTE CHOC 🔴  | Même direction que NASDAQ — obligatoire\n" +
    "   • ₿ BITCOIN  : VENTE CHOC 🔴  | Actif risk-on pénalisé par le resserrement (effet amplifié x2 à x3)\n" +
    "   • 🇪🇺 EURUSD : VENTE CHOC 🔴  | Dollar fort écrase l'Euro\n" +
    "   • 🇬🇧 GBPUSD : VENTE CHOC 🔴  | Dollar fort écrase la Livre\n" +
    "   • 🇦🇺 AUDUSD : VENTE CHOC 🔴  | Devise risk-on pénalisée\n" +
    "   • 🛢️ USOIL    : NEUTRE          | Pas d'impact direct sauf si contexte GÉO simultané\n" +
    "   🏁 FLUX DOMINANT OBLIGATOIRE : DOLLAR FORT (MKT RISK-OFF) 🐻\n\n" +
    "   DOVISH US (CPI < prévisions, NFP faible, Fed dovish, anticipation de baisses de taux) :\n" +
    "   • 📈 US10Y    : VENTE CHOC 🔴  | Rendements baissent avec les anticipations de baisse\n" +
    "   • 🇨🇦 USDCAD : VENTE CHOC 🔴  | Dollar faible face au CAD\n" +
    "   • 🇯🇵 USDJPY : VENTE CHOC 🔴  | Dollar faible face au Yen ← TOUJOURS VENTE sur DOVISH US\n" +
    "   • 🏆 GOLD    : ACHAT CHOC 🟢  | Dollar faible propulse l'or\n" +
    "   • 💻 NASDAQ  : ACHAT CHOC 🟢  | Taux bas soutiennent les valorisations tech\n" +
    "   • 📊 SP500    : ACHAT CHOC 🟢  | Même direction que NASDAQ — obligatoire\n" +
    "   • ₿ BITCOIN  : ACHAT CHOC 🟢  | Liquidité favorable aux actifs risk-on (effet amplifié x2 à x3)\n" +
    "   • 🇪🇺 EURUSD : ACHAT CHOC 🟢  | Dollar faible renforce l'Euro\n" +
    "   • 🇬🇧 GBPUSD : ACHAT CHOC 🟢  | Dollar faible renforce la Livre\n" +
    "   • 🇦🇺 AUDUSD : ACHAT CHOC 🟢  | Devise risk-on bénéficie du Dollar faible\n" +
    "   • 🛢️ USOIL    : NEUTRE          | Pas d'impact direct sauf si contexte GÉO simultané\n" +
    "   🏁 FLUX DOMINANT OBLIGATOIRE : DOLLAR FAIBLE (MKT RISK-ON) 🐂\n\n" +
    "   CAS MIXTE (ex: CPI core baisse mais headline monte) :\n" +
    "   Utilise le composant le plus surveillé par la Fed (Core > Headline).\n" +
    "   Signale la divergence dans le FAIT MARQUANT. Conviction plafonnée à 65%.\n\n" +
    "B. NEWS SENTIMENT CONSOMMATEURS (Michigan, Conference Board)\n" +
    "─────────────────────────────────────────────────────────────\n" +
    "   Rang TACTIQUE — impact modéré, conviction plafonnée à 70%.\n" +
    "   Sentiment BAS (< prévisions) → Signal DOVISH modéré :\n" +
    "   • 💻 NASDAQ  : VENTE CHOC 🔴  | Crainte de ralentissement de la consommation\n" +
    "   • 📊 SP500    : VENTE CHOC 🔴  | Même direction que NASDAQ — obligatoire\n" +
    "   • 🏆 GOLD    : ACHAT CHOC 🟢  | Refuge en cas de pessimisme économique\n" +
    "   • 📈 US10Y    : NEUTRE          | Pas de signal monétaire direct\n" +
    "   • 🇯🇵 USDJPY : NEUTRE          | Pas de choc suffisant pour déplacer le Yen\n" +
    "   • 🛢️ USOIL    : VENTE CHOC 🔴  | Demande anticipée en baisse\n" +
    "   • ₿ BITCOIN  : VENTE CHOC 🔴  | Actif risk-on pénalisé (amplitude corrélée aux indices)\n" +
    "   • 🇪🇺 EURUSD, 🇬🇧 GBPUSD, 🇦🇺 AUDUSD, 🇨🇦 USDCAD : NEUTRE\n" +
    "   🏁 FLUX DOMINANT : RISK-OFF MODÉRÉ (MKT INCERTAIN) ⚠️\n\n" +
    "   Sentiment HAUT (> prévisions) → Signal HAWKISH modéré :\n" +
    "   Inverser toutes les directions ci-dessus.\n" +
    "   🏁 FLUX DOMINANT : RISK-ON MODÉRÉ (MKT CONFIANT) 🐂\n\n" +
    "C. NEWS BANQUES CENTRALES ÉTRANGÈRES (BoJ, BCE/ECB, BoE, RBA, BoC)\n" +
    "────────────────────────────────────────────────────────────────\n" +
    "   VERROU MONÉTAIRE ABSOLU : La devise locale réagit exclusivement à sa propre banque centrale.\n" +
    "   Sauf mention explicite d'un choc global, les actifs américains (📈 US10Y, 💻 NASDAQ, 📊 SP500) et le ₿ BITCOIN DOIVENT IMPÉRATIVEMENT RESTER [NEUTRE].\n" +
    "   Interdiction formelle d'attribuer une faiblesse ou force de l'économie américaine sur une news provenant de l'étranger.\n\n" +
    "   DOVISH étranger → La devise locale s'effondre, provoquant une hausse mécanique du Dollar américain par effet de flux (Dollar Fort par différentiel) :\n" +
    "   • 🇪🇺 BCE/ECB DOVISH  → 🇪🇺 EURUSD: VENTE CHOC 🔴 | 🇬🇧 GBPUSD: VENTE CHOC 🔴 | 🇦🇺 AUDUSD: VENTE CHOC 🔴 | 🇨🇦 USDCAD: ACHAT CHOC 🟢 | 🇯🇵 USDJPY: ACHAT CHOC 🟢 | 🛢️ USOIL: VENTE CHOC 🔴 | 🏆 GOLD: NEUTRE\n" +
    "   • 🇯🇵 BoJ DOVISH      → 🇯🇵 USDJPY: ACHAT CHOC 🟢 | 🇪🇺 EURUSD: NEUTRE | 🇬🇧 GBPUSD: NEUTRE | 🇦🇺 AUDUSD: NEUTRE | 🇨🇦 USDCAD: NEUTRE | 🛢️ USOIL: NEUTRE | 🏆 GOLD: NEUTRE\n" +
    "   • 🇨🇦 BoC DOVISH      → 🇨🇦 USDCAD: ACHAT CHOC 🟢 | 🛢️ USOIL: VENTE CHOC 🔴 | 🇪🇺 EURUSD: NEUTRE | 🇬🇧 GBPUSD: NEUTRE | 🇦🇺 AUDUSD: NEUTRE | 🇯🇵 USDJPY: NEUTRE | 🏆 GOLD: NEUTRE\n" +
    "   • 🇬🇧 BoE DOVISH      → 🇬🇧 GBPUSD: VENTE CHOC 🔴 | 🇪🇺 EURUSD: NEUTRE | 🇯🇵 USDJPY: NEUTRE | 🇨🇦 USDCAD: NEUTRE | 🇦🇺 AUDUSD: NEUTRE | 🛢️ USOIL: NEUTRE | 🏆 GOLD: NEUTRE\n" +
    "   • 🇦🇺 RBA DOVISH      → 🇦🇺 AUDUSD: VENTE CHOC 🔴 | 🛢️ USOIL: VENTE CHOC 🔴 | 🇪🇺 EURUSD: NEUTRE | 🇬🇧 GBPUSD: NEUTRE | 🇨🇦 USDCAD: NEUTRE | 🇯🇵 USDJPY: NEUTRE | 🏆 GOLD: NEUTRE\n" +
    "   🏁 FLUX DOMINANT OBLIGATOIRE : [DEVISE_LOCALE] FAIBLE / DOLLAR FORT par différentiel 🐻\n\n" +
    "   HAWKISH étranger → La devise locale explose, provoquant une baisse mécanique du Dollar américain par effet de flux (Dollar Faible par différentiel) :\n" +
    "   • 🇪🇺 BCE/ECB HAWKISH → 🇪🇺 EURUSD: ACHAT CHOC 🟢 | 🇬🇧 GBPUSD: ACHAT CHOC 🟢 | 🇦🇺 AUDUSD: ACHAT CHOC 🟢 | 🇨🇦 USDCAD: VENTE CHOC 🔴 | 🇯🇵 USDJPY: VENTE CHOC 🔴 | 🛢️ USOIL: ACHAT CHOC 🟢 | 🏆 GOLD: NEUTRE\n" +
    "   • 🇯🇵 BoJ HAWKISH     → 🇯🇵 USDJPY: VENTE CHOC 🔴 | 🇪🇺 EURUSD: NEUTRE | 🇬🇧 GBPUSD: NEUTRE | 🇦🇺 AUDUSD: NEUTRE | 🇨🇦 USDCAD: NEUTRE | 🛢️ USOIL: NEUTRE | 🏆 GOLD: NEUTRE\n" +
    "   • 🇨🇦 BoC HAWKISH     → 🇨🇦 USDCAD: VENTE CHOC 🔴 | 🛢️ USOIL: ACHAT CHOC 🟢 | 🇪🇺 EURUSD: NEUTRE | 🇬🇧 GBPUSD: NEUTRE | 🇦🇺 AUDUSD: NEUTRE | 🇯🇵 USDJPY: NEUTRE | 🏆 GOLD: NEUTRE\n" +
    "   • 🇬🇧 BoE HAWKISH     → 🇬🇧 GBPUSD: ACHAT CHOC 🟢 | 🇪🇺 EURUSD: NEUTRE | 🇯🇵 USDJPY: NEUTRE | 🇨🇦 USDCAD: NEUTRE | 🇦🇺 AUDUSD: NEUTRE | 🛢️ USOIL: NEUTRE | 🏆 GOLD: NEUTRE\n" +
    "   • 🇦🇺 RBA HAWKISH     → 🇦🇺 AUDUSD: ACHAT CHOC 🟢 | 🛢️ USOIL: ACHAT CHOC 🟢 | 🇪🇺 EURUSD: NEUTRE | 🇬🇧 GBPUSD: NEUTRE | 🇨🇦 USDCAD: NEUTRE | 🇯🇵 USDJPY: NEUTRE | 🏆 GOLD: NEUTRE\n" +
    "   🏁 FLUX DOMINANT OBLIGATOIRE : [DEVISE_LOCALE] FORTE / DOLLAR FAIBLE par différentiel 🐂\n\n" +
    "   NOTE CANADA/PÉTROLE : Le CAD et USOIL sont corrélés. BoC HAWKISH = économie forte = demande pétrolière = USOIL ACHAT. BoC DOVISH = économie faible = USOIL VENTE.\n\n" +
    "D. GÉO — STIMULUS MILITAIRE / DÉPENSES DE DÉFENSE EUROPÉENNES (OTAN, 2% PIB)\n" +
    "─────────────────────────────────────────────────────────────────────────────\n" +
    "   VECTEUR = LIQUIDITÉ. C'est un stimulus fiscal localisé (relance budgétaire) sur l'Europe.\n" +
    "   • 🇪🇺 EURUSD : ACHAT CHOC 🟢  | Soutien budgétaire et relance de l'économie européenne\n" +
    "   • 🇬🇧 GBPUSD : ACHAT CHOC 🟢  | Alignement stratégique des dépenses de l'OTAN / UK\n" +
    "   • 🛢️ USOIL    : ACHAT CHOC 🟢  | Augmentation mécanique de la demande d'énergie militaire\n" +
    "   • 🇯🇵 USDJPY : VENTE CHOC 🔴  | Le Yen s'apprécie comme actif refuge face aux incertitudes budgétaires\n" +
    "   • 💻 NASDAQ  : VENTE CHOC 🔴  | Crainte d'inflation par creusement du déficit budgétaire public\n" +
    "   • 📊 SP500    : VENTE CHOC 🔴  | Même direction que NASDAQ — obligatoire\n" +
    "   • ₿ BITCOIN  : VENTE CHOC 🔴  | Risk-off immédiat sur les actifs spéculatifs (liquidation forcée)\n" +
    "   • 📈 US10Y    : NEUTRE\n" +
    "   • 🏆 GOLD    : NEUTRE\n" +
    "   • 🇨🇦 USDCAD : VENTE CHOC 🔴  | Effet de flux : le Dollar fléchit face aux devises européennes/refuges\n" +
    "   • 🇦🇺 AUDUSD : ACHAT CHOC 🟢  | Devise cyclique soutenue par l'injection globale de liquidité fiscale\n" +
    "   🏁 FLUX DOMINANT OBLIGATOIRE : EURO FORT / YEN FORT (MKT RISK-OFF) 🐻\n\n" +
    "E1. GÉO — CONFLITS / PANIQUE / MOYEN-ORIENT / CHINE\n" +
    "────────────────────────────────────────────────────\n" +
    "   VECTEUR = GÉO. RISK-OFF classique, fuite vers les refuges.\n" +
    "   CHOC GÉOPOLITIQUE / ESCALADE :\n" +
    "   • 🏆 GOLD    : ACHAT CHOC 🟢  | Refuge universel absolu\n" +
    "   • 🇯🇵 USDJPY : VENTE CHOC 🔴  | Le Yen s'apprécie comme refuge supérieur au dollar (le graphique baisse)\n" +
    "   • 🛢️ USOIL    : ACHAT CHOC 🟢  | Si Moyen-Orient / Détroit d'Ormuz impliqué (menace sur l'offre)\n" +
    "                  NEUTRE          | Si conflit local sans aucun impact sur les routes pétrolières\n" +
    "   • 🇦🇺 AUDUSD : VENTE CHOC 🔴  | Devise risk-on fortement pénalisée en RISK-OFF\n" +
    "   • 🇨🇦 USDCAD : [ACHAT CHOC si USOIL NEUTRE] / [NEUTRE si USOIL ACHAT] | Justification selon la divergence pétrole/cad. Mentionner obligatoirement la divergence dans le FAIT MARQUANT.\n" +
    "   • 🇪🇺 EURUSD : VENTE CHOC 🔴  | L'Euro subit le choc de l'instabilité internationale\n" +
    "   • 🇬🇧 GBPUSD : VENTE CHOC 🔴  | La Livre subit la baisse générale de l'aversion au risque\n" +
    "   • 💻 NASDAQ  : VENTE CHOC 🔴  | Les marchés actions capitulent face à l'incertitude\n" +
    "   • 📊 SP500    : VENTE CHOC 🔴  | Même direction que NASDAQ — obligatoire\n" +
    "   • ₿ BITCOIN  : VENTE CHOC 🔴  | Retrait immédiat des capitaux des actifs spéculatifs\n" +
    "   • 📈 US10Y    : ACHAT CHOC 🟢  | Ruée vers la sécurité des bons du Trésor américains\n" +
    "   🏁 FLUX DOMINANT OBLIGATOIRE : YEN FORT / OR FORT (MKT RISK-OFF) 🐻\n\n" +
    "   DÉSESCALADE MOYEN-ORIENT (Discussions, Accords, Trêve) :\n" +
    "   Impact modéré, conviction plafonnée à 45%.\n" +
    "   • 🏆 GOLD    : VENTE CHOC 🔴  | Sortie des refuges\n" +
    "   • 🛢️ USOIL    : VENTE CHOC 🔴  | Prime de risque géopolitique s'efface sur le brut\n" +
    "   • 💻 NASDAQ  : ACHAT CHOC 🟢  | Soulagement des indices actions\n" +
    "   • 📊 SP500    : ACHAT CHOC 🟢  | Même direction que NASDAQ — obligatoire\n" +
    "   • 🇯🇵 USDJPY : ACHAT CHOC 🟢  | Le Yen capitule comme refuge\n" +
    "   • 🇨🇦 USDCAD : ACHAT CHOC 🟢  | Pétrole baisse = le CAD s'affaiblit mécaniquement face au USD\n" +
    "   • 🇦🇺 AUDUSD : ACHAT CHOC 🟢  | Retour de l'appétit pour le risque sur les devises cycliques\n" +
    "   • ₿ BITCOIN  : ACHAT CHOC 🟢  | Retour des flux spéculatifs (amplitude x2 à x3 par rapport aux actions)\n" +
    "   • 📈 US10Y, 🇪🇺 EURUSD, 🇬🇧 GBPUSD : NEUTRE | Retrait ordonné sans panique\n" +
    "   🏁 FLUX DOMINANT OBLIGATOIRE : RISK-ON RETOUR (MKT APPAISÉ) 🐂\n\n" +
    "F. STOCKS PÉTROLE EIA / OPEC\n" +
    "─────────────────────────────\n" +
    "   Rang SECONDAIRE. Impact principal sur USOIL et CAD.\n" +
    "   Stocks EIA > prévisions (surplus) → offre excédentaire :\n" +
    "   • 🛢️ USOIL    : VENTE CHOC 🔴\n" +
    "   • 🇨🇦 USDCAD : ACHAT CHOC 🟢  | Le CAD s'affaiblit en corrélation directe avec la chute du brut\n" +
    "   • Tous les autres actifs : NEUTRE\n" +
    "   Stocks EIA < prévisions (déficit) → tension sur l'offre :\n" +
    "   • 🛢️ USOIL    : ACHAT CHOC 🟢\n" +
    "   • 🇨🇦 USDCAD : VENTE CHOC 🔴  | Le CAD se renforce en même temps que le pétrole grimpe\n" +
    "   • Tous les autres actifs : NEUTRE\n\n" +
    "G. TARIFS DOUANIERS (Chine, UE, USA, etc.)\n" +
    "────────────────────────────────────────────\n" +
    "   Rang TACTIQUE, impact modéré à élevé selon l'ampleur. Conviction plafonnée à 70%.\n" +
    "   Annonce de SURTAXE / GUERRE COMMERCIALE (ex: +25% sur produits chinois) :\n" +
    "   • 💻 NASDAQ  : VENTE CHOC 🔴  | Crainte sur les chaînes d'approvisionnement tech\n" +
    "   • 📊 SP500    : VENTE CHOC 🔴  | Même direction que NASDAQ — obligatoire\n" +
    "   • 🇨🇳 AUDUSD : VENTE CHOC 🔴  | Devise proxy de la Chine, fortement pénalisée\n" +
    "   • 🛢️ USOIL    : VENTE CHOC 🔴  | Anticipation de ralentissement de la demande mondiale\n" +
    "   • 🇯🇵 USDJPY : VENTE CHOC 🔴  | Yen refuge s'apprécie (le graphique baisse)\n" +
    "   • 🏆 GOLD    : ACHAT CHOC 🟢  | Valeur refuge\n" +
    "   • 🇪🇺 EURUSD, 🇬🇧 GBPUSD, 🇨🇦 USDCAD : NEUTRE\n" +
    "   • ₿ BITCOIN  : VENTE CHOC 🔴  | Risk-off sur actifs spéculatifs\n" +
    "   • 📈 US10Y    : NEUTRE\n" +
    "   🏁 FLUX DOMINANT : RISK-OFF / YEN FORT / OR FORT 🐻\n\n" +
    "   DÉSESCALADE TARIFAIRE (suspension, baisse, accord) :\n" +
    "   Inverser toutes les directions ci-dessus, conviction plafonnée à 50%.\n" +
    "   🏁 FLUX DOMINANT : RISK-ON / APPÉTIT POUR LE RISQUE 🐂\n\n" +
    "<HARD_CONSTRAINTS>\n" +
    "CONTRAINTE 1 — SECTIONS INTERDITES :\n" +
    "   N'écris JAMAIS 'TIMING D'EFFET', 'ACTION TRADING', 'CONTEXTE' ou toute autre section\n" +
    "   absente du FORMAT DE SORTIE ci-dessous. STRICTEMENT INTERDIT.\n\n" +
    "CONTRAINTE 2 — ÉMOJI UNIQUE :\n" +
    "   Le symbole '📢' est STRICTEMENT RÉSERVÉ au seul 'FAIT MARQUANT'. Un seul et unique '📢' par réponse.\n\n" +
    "CONTRAINTE 3 — SYMÉTRIE NASDAQ/SP500 ABSOLUE :\n" +
    "   💻 NASDAQ et 📊 SP500 ont TOUJOURS exactement la même directionnalité (Achat, Vente ou Neutre).\n" +
    "   Aucune exception tolérée.\n\n" +
    "CONTRAINTE 4 — COHÉRENCE USDJPY / FLUX DOMINANT :\n" +
    "   - Si 🇯🇵 USDJPY is in VENTE CHOC 🔴 → Le FLUX DOMINANT a l'interdiction formelle d'écrire 'DOLLAR FORT'.\n" +
    "   - Si 🇯🇵 USDJPY is in ACHAT CHOC 🟢 → Le FLUX DOMINANT a l'interdiction formelle d'écrire 'YEN FORT'.\n" +
    "   - En cas de contradiction, le flux doit mentionner YEN FORT si USDJPY est en VENTE.\n\n" +
    "CONTRAINTE 5 — JAUGE CONVICTION OBLIGATOIRE :\n" +
    "   Tu dois obligatoirement générer la jauge visuelle d'émojis avant le pourcentage. Format strict :\n" +
    "   📊 CONVICTION : [EMOJIS] XX%\n" +
    "   Génération selon les paliers suivants :\n" +
    "   - Pourcentage < 40%  → ⚪⚪⚪⚪⚪\n" +
    "   - Pourcentage 41-60% → 🟠🟠🟠⚪⚪\n" +
    "   - Pourcentage 61-80% → 🟡🟡🟡🟡⚪\n" +
    "   - Pourcentage > 81%  → 🔴🔴🔴🔴🔴\n" +
    "   Exemple valide : 📊 CONVICTION : 🟡🟡🟡🟡⚪ 75%\n\n" +
    "CONTRAINTE 6 — INTERDICTION D'OMISSION :\n" +
    "   Les 11 actifs listés dans le FORMAT DE SORTIE doivent TOUS apparaître explicitement dans la réponse,\n" +
    "   sans aucune exception, même s'ils reçoivent la mention NEUTRE ou une INCLINATION.\n" +
    "CONTRAINTE 7 — SÉCURITÉ BANQUES CENTRALES ÉTRANGÈRES (BASÉE SUR LE CONTENU) :\n" +
    "   - Si le texte du flux (le contenu) mentionne une banque centrale étrangère (BCE, ECB, BOJ, BOE, RBA, BOC), tu as l'INTERDICTION ABSOLUE de mettre ACHAT CHOC, VENTE CHOC ou toute INCLINATION sur NASDAQ, SP500, US10Y et BITCOIN. Ils doivent obligatoirement être marqués [NEUTRE] avec la raison exacte suivante : \"Pas d'impact direct – actif américain / crypto\".\n" +
    "   - Quelle que soit la source ou l'émetteur de la notification (Twitter, FinancialJuice, etc.), c'est la nature du contenu textuel qui déclenche cette règle.\n" +
    "   - RÈGLE DE DIRECTIONNALITÉ DE LA DEVISE LOCALE :\n" +
    "     * Banque centrale étrangère DOVISH (baisse des taux, ton accommodant) -> sa devise locale baisse face au USD. Exemple strict : BCE DOVISH = EURUSD VENTE CHOC 🔴. (Mettre ACHAT est une erreur éliminatoire).\n" +
    "     * Banque centrale étrangère HAWKISH (hausse des taux, ton restrictif) -> sa devise locale monte face au USD. Exemple strict : BCE HAWKISH = EURUSD ACHAT CHOC 🟢.\n" +
    "   - Les autres paires de devises (GBPUSD, AUDUSD, USDJPY, USDCAD) et actifs (GOLD, USOIL) se conforment strictement aux directives de flux et de corrélation de la RÈGLE C (Différentiel de taux / effet dollar), ou restent [NEUTRE] s'ils ne sont pas mentionnés.\n\n" +
    "   - ⚠️ TOUTE INFRACTION À CETTE RÈGLE (ex: BCE HAWKISH → EURUSD VENTE) ENTRAÎNE LE REJET AUTOMATIQUE DE LA RÉPONSE. CETTE RÈGLE PRÉVAUT SUR TOUTE AUTRE CONSIDÉRATION.\n\n" +
    "CONTRAINTE 8 — COMPLÉTUDE ABSOLUE DE LA MATRICE :\n" +
    "   - Tu dois obligatoirement copier-coller la liste complète des 11 actifs dans l'ordre exact du format de sortie. Aucune ligne ne peut être omise ou supprimée, sous aucun prétexte.\n" +
    "   - Si un actif n'est pas directement touché ou doit rester neutre par application de la CONTRAINTE 7, sa mention réglementaire stricte doit être : `NEUTRE | Pas d'impact direct de ce driver.` (ou la raison spécifique exigée par la contrainte 7).\n" +
    "   - Cette règle de complétude prévaut sur toute logique de concision.\n\n" +
    "CONTRAINTE 9 — NOMBRE EXACT DE LIGNES D'IMPACTS :\n" +
    "   ⚠️ TOUTE RÉPONSE DOIT CONTENIR EXACTEMENT 11 LIGNES D’IMPACTS (une par actif), même si l'actif est neutre.\n" +
    "   Aucune ligne ne peut être omise, supprimée ou ajoutée. Le non-respect de cette règle entraîne le rejet automatique de la réponse.\n\n" +
    "CONTRAINTE 10 — VALEUR EXACTE DU VECTEUR CIBLE :\n" +
    "   Le champ 🎯 VECTEUR CIBLE doit être choisi UNIQUEMENT parmi : HAWKISH, DOVISH, GÉO, LIQUIDITÉ, CHINE, TARIFS.\n" +
    "   Toute autre valeur (ex: \"RANG SECONDAIRE - INFLATION\") est interdite et invalide la réponse.\n" +
    "   La réponse doit utiliser exactement un de ces six termes, sans ajout ni modification.\n\n" +
    "CONTRAINTE 11 — HIÉRARCHIE ABSOLUE ET EXCEPTION DE CRISE :\n" +
    "   - En règle générale, le RANG SUPRÊME (Politique Monétaire, CPI, PCE) l'emporte sur le RANG TACTIQUE (GÉO).\n" +
    "   - ⚠️ EXCEPTION ABSOLUE (RÉGIME DE GUERRE) : Si le flux fait état d'une ESCALADE MILITAIRE DIRECTE ou MENACE SUR L'OFFRE (ex: Hormuz, frappes US-Iran), le driver GÉO devient PRIORITAIRE sur l'Inflation pour l'Or et le Pétrole.\n" +
    "   - Alignement obligatoire de la matrice des 11 actifs dans ce cas précis :\n" +
    "     * 🏆 GOLD    : ACHAT CHOC 🟢 [Flux refuge dominant]\n" +
    "     * 🛢️ USOIL   : ACHAT CHOC 🟢 [Prime de risque sur l'offre]\n" +
    "     * 📈 US10Y   : ACHAT CHOC 🟢 [PCE Hawkish / Taux sous pression]\n" +
    "     * 💻 NASDAQ  : VENTE CHOC 🔴 [Double flux négatif : Taux hauts + Risk-Off]\n" +
    "     * 📊 SP500   : VENTE CHOC 🔴 [Strictement identique au NASDAQ]\n" +
    "     * ₿ BITCOIN  : VENTE CHOC 🔴 [Capitulation des actifs spéculatifs]\n" +
    "     * 🇪🇺 EURUSD  : VENTE CHOC 🔴 [Dollar fort + Proximité du choc géo]\n" +
    "     * 🇬🇧 GBPUSD  : VENTE CHOC 🔴 [Dollar fort par arbitrage]\n" +
    "     * 🇦🇺 AUDUSD  : VENTE CHOC 🔴 [Liquidation de la devise cyclique/commodity non-pétrole]\n" +
    "     * 🇯🇵 USDJPY  : NEUTRE ou VENTE CHOC 🔴 [Arbitrage complexe : Dollar Fort vs Yen Refuge. Justifier dans le Fait Marquant].\n" +
    "     * 🇨🇦 USDCAD  : NEUTRE ou VENTE CHOC 🔴 [Le choc USOIL haussier compense et annule la force du Dollar. Préciser l'arbitrage].\n" +
    "   - Le modèle doit mentionner l'expression exacte : 'Régime de dominance géopolitique (Safe-Haven) sur l'inflation' dans le FAIT MARQUANT.\n\n"
    "</HARD_CONSTRAINTS>\n\n" +
    "EXEMPLE D'APPLICATION (INDÉPENDANT DE LA SOURCE) :\n" +
    "   Si l'actualité dit : \"BCE dovish, Schnabel s'inquiète de la croissance européenne\", la réponse DOIT copier l'intégralité des 11 lignes ainsi :\n" +
    "   • 📈 US10Y   : NEUTRE | Pas d'impact direct de ce driver.\n" +
    "   • 💻 NASDAQ  : NEUTRE | Pas d'impact direct – actif américain / crypto.\n" +
    "   • 📊 SP500   : NEUTRE | Pas d'impact direct – actif américain / crypto.\n" +
    "   • 🏆 GOLD    : NEUTRE | Pas d'impact direct de ce driver.\n" +
    "   • 🛢️ USOIL   : NEUTRE | Pas d'impact direct de ce driver.\n" +
    "   • 🇪🇺 EURUSD : VENTE CHOC 🔴 | BCE dovish -> baisse et affaiblissement de l'euro.\n" +
    "   • 🇯🇵 USDJPY : ACHAT CHOC 🟢 | Hausse mécanique par différentiel (Dollar Fort face au Yen).\n" +
    "   • 🇨🇦 USDCAD : ACHAT CHOC 🟢 | Hausse mécanique par différentiel (Dollar Fort face au CAD).\n" +
    "   • 🇬🇧 GBPUSD : VENTE CHOC 🔴 | Baisse mécanique par différentiel (Dollar Fort écrase la Livre).\n" +
    "   • 🇦🇺 AUDUSD : VENTE CHOC 🔴 | Baisse mécanique par différentiel (Dollar Fort écrase l'Aussie).\n" +
    "   • ₿ BITCOIN  : NEUTRE | Pas d'impact direct – actif américain / crypto.\n" +
    "FORMAT DE SORTIE STRICT ET OBLIGATOIRE :\n" +
    "🚨 [NOM DE L'EMETTEUR OU SOURCE]\n" +
    "🕒 [Insère ici la date et l'heure fournies dans le CONTEXTE TEMPOREL au début du message] (Mada)\n" +
    "📊 CONVICTION : [JAUGE_EMOJIS] XX%\n" +
    "🎯 VECTEUR CIBLE : [HAWKISH / DOVISH / GÉO / LIQUIDITÉ / CHINE / TARIFS]\n" +
    "📢 FAIT MARQUANT : [Analyse pro de la situation en français. Mentionner l'arbitrage si écrasement d'un driver récent ou divergence.]\n\n" +
    "--- IMPACTS ACQUISITION ---\n" +
    "• 📈 US10Y   : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE / INCLINATION ACHAT MAIS NEUTRE / INCLINATION VENTE MAIS NEUTRE] | [raison succincte]\n" +
    "• 💻 NASDAQ  : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE / INCLINATION ACHAT MAIS NEUTRE / INCLINATION VENTE MAIS NEUTRE] | [raison succincte]\n" +
    "• 📊 SP500   : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE / INCLINATION ACHAT MAIS NEUTRE / INCLINATION VENTE MAIS NEUTRE] | [raison succincte]\n" +
    "• 🏆 GOLD    : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE / INCLINATION ACHAT MAIS NEUTRE / INCLINATION VENTE MAIS NEUTRE] | [raison succincte]\n" +
    "• 🛢️ USOIL   : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE / INCLINATION ACHAT MAIS NEUTRE / INCLINATION VENTE MAIS NEUTRE] | [raison succincte]\n" +
    "• 🇪🇺 EURUSD : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE / INCLINATION ACHAT MAIS NEUTRE / INCLINATION VENTE MAIS NEUTRE] | [raison succincte]\n" +
    "• 🇯🇵 USDJPY : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE / INCLINATION ACHAT MAIS NEUTRE / INCLINATION VENTE MAIS NEUTRE] | [raison succincte]\n" +
    "• 🇨🇦 USDCAD : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE / INCLINATION ACHAT MAIS NEUTRE / INCLINATION VENTE MAIS NEUTRE] | [raison succincte]\n" +
    "• 🇬🇧 GBPUSD : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE / INCLINATION ACHAT MAIS NEUTRE / INCLINATION VENTE MAIS NEUTRE] | [raison succincte]\n" +
    "• 🇦🇺 AUDUSD : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE / INCLINATION ACHAT MAIS NEUTRE / INCLINATION VENTE MAIS NEUTRE] | [raison succincte]\n" +
    "• ₿ BITCOIN  : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE / INCLINATION ACHAT MAIS NEUTRE / INCLINATION VENTE MAIS NEUTRE] | [raison succincte]\n\n" +
    "🏁 FLUX DOMINANT : [Chaîne de caractères exacte issue des règles de directionnalité]"; 

    // 2. Génération dynamique de l'horodatage actuel au format de Madagascar (EAT)
    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.FRANCE);
    sdf.setTimeZone(java.util.TimeZone.getTimeZone("Indian/Antananarivo"));
    String currentMadaTime = sdf.format(new java.util.Date());

    // 3. Préparation des données utilisateur dynamiques (Le flux brut)
    String userContent = "CONTEXTE TEMPOREL : " + currentMadaTime + "\n"
            + "SOURCE DE LA NEWS : " + sourceName + "\n"
            + "TITRE : " + title + "\n"
            + "CORPS DE LA NOTIFICATION : " + body + "\n"
            + "ACTIFS PRÉ-QUALIFIÉS : " + enrichedAssets.toString();
           // 4. Construction du payload JSON standard pour Groq
    final JSONObject jsonPayload = new JSONObject();
    try { 
        EventDatabase db = EventDatabase.getInstance(NotificationService.this);
        List<String> historique = db.obtenirTexteEvenementsRecents();
        // On passe 'userContent' (votre notification) et l'historique récent à la fabrique de prompt
        String promptFinalEnvoye = construirePromptFinal(userContent, historique);
        // ───────────────────────────────────────────────────────────────────────

        jsonPayload.put("model", GROQ_MODEL);
        jsonPayload.put("temperature", 0.0); // Strict

        JSONArray messages = new JSONArray();

        // Bloc SYSTEM : Injection du prompt combiné dynamique (Règles + Alerte de Crise)
        JSONObject systemMessage = new JSONObject();
        systemMessage.put("role", "system");
        // ON REMPLACE 'SYSTEM_PROMPT' PAR VOTRE PROMPT DYNAMIQUE 'promptFinalEnvoye'
        systemMessage.put("content", promptFinalEnvoye); 
        messages.put(systemMessage);

        // Bloc USER : Injection du flux d'actualité brut ou contexte temporel
        JSONObject userMessage = new JSONObject();
        userMessage.put("role", "user");
        userMessage.put("content", userContent);
        messages.put(userMessage);

        jsonPayload.put("messages", messages);
        
    } catch (Exception e) {
        Log.e(TAG, "[GROQ] Erreur lors de la sérialisation du JSON", e);
        return;
    }

    // 5. Threading Asynchrone obligatoire pour Android (Évite le blocage de l'application)
    new Thread(new Runnable() {
        @Override
        public void run() {
            java.net.HttpURLConnection conn = null;
            try {
                // Récupération sécurisée de la clé Groq
                String apiKey = getGroqApiKey();
                if (apiKey.isEmpty()) {
                    Log.e(TAG, "[GROQ] Clé API absente. Analyse annulée.");
                    return;
                }

                // Initialisation de la connexion HTTP native
                java.net.URL url = new java.net.URL(GROQ_URL);
                conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                // Écriture du flux réseau vers le serveur de Groq
                try (java.io.OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonPayload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                    os.flush();
                }

                // Analyse du code de réponse HTTP
                int status = conn.getResponseCode();
                if (status == java.net.HttpURLConnection.HTTP_OK) {
                    try (java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                        
                        StringBuilder response = new StringBuilder();
                        String responseLine;
                        while ((responseLine = br.readLine()) != null) {
                            response.append(responseLine.trim());
                        }

                        // Extraction du rapport macroéconomique formaté par l'IA
                        JSONObject jsonResponse = new JSONObject(response.toString());
                        String aiReport = jsonResponse.getJSONArray("choices")
                                .getJSONObject(0)
                                .getJSONObject("message")
                                .getString("content");

                        // 6. Routage final du rapport strict vers votre canal Telegram sécurisé
                        sendTelegramSecure(aiReport, NotificationService.this);
                    }
                } else {
                    Log.e(TAG, "[GROQ] Erreur de serveur HTTP Code : " + status);
                }

            } catch (Exception e) {
                Log.e(TAG, "[GROQ] Échec critique lors de l'exécution réseau", e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }
    }).start(); // Ferme et démarre proprement le Thread
} // Ferme définitivement la méthode processAnalysisWithAI

    // Point 5 : Déconnexion sécurisée encapsulée dans un bloc finally
    public static void sendTelegramSecure(String message, Context context) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                android.content.SharedPreferences prefs = context.getSharedPreferences("TradingBot", Context.MODE_PRIVATE);
                String token  = prefs.getString("tg_token", "");
                String chatId = prefs.getString("tg_chat_id", "");

                if (token.isEmpty() || chatId.isEmpty()) return;

                URL url = new URL("https://api.telegram.org/bot" + token + "/sendMessage");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setDoOutput(true);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(8000);

                JSONObject payload = new JSONObject();
                payload.put("chat_id", chatId);
                payload.put("text", message);
                payload.put("parse_mode", "Markdown");

                OutputStream os = conn.getOutputStream();
                os.write(payload.toString().getBytes("UTF-8"));
                os.flush();
                os.close();

                conn.getResponseCode();
            } catch (Exception e) {
                Log.e(TAG, "Échec Telegram POST", e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        eventDb = EventDatabase.getInstance(this);
        
        // ── MISE À JOUR : Liaison du contexte pour l'extraction de la clé macro_api_key ──
        EconomicCalendarAPI.init(this);
        //EventValidator.init(eventDb); 
        // ── MISE À JOUR : Déportation du préchargement réseau dans un thread d'arrière-plan ──
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    EventValidator.preloadCalendar(); 
                    Log.d(TAG, "[SERVICE] Calendrier économique préchargé avec succès.");
                } catch (Exception e) {
                    Log.e(TAG, "[SERVICE] Erreur lors du préchargement du calendrier", e);
                }
            }
        }).start();

        createNotificationChannel();
        startDailyBriefScheduler();
        startMonthlyReportScheduler();
        registerNetworkCallback();
        
        // Planification unifiée : purge SQLite + nettoyage RAM + préchargement toutes les 12 heures
        // Calcul du délai initial requis pour atteindre le tout prochain minuit à Madagascar
    long initialDelayMillis = calculateMillisUntilNextMadaMidnight();
    // Périodicité stricte de 24 heures pour s'exécuter à chaque minuit
    long period24HoursMillis = 24 * 60 * 60 * 1000L; 

    scheduler.scheduleAtFixedRate(new Runnable() {
        @Override
        public void run() {
            if (isSyncing) return;
            isSyncing = true;
            try {
                Log.d(TAG, "🕒 [MAINTENANCE] Déclenchement automatique de minuit (Heure de Madagascar)...");
                
                // 1. Purge SQLite des événements ayant dépassé la fenêtre de validité de 48 heures
                long thresholdSeconds = (System.currentTimeMillis() - (48 * 60 * 60 * 1000L)) / 1000;
                eventDb.purgeOldEvents(thresholdSeconds); 
                Log.d(TAG, "[MAINTENANCE] Base de données SQLite purgée des données > 48h.");

                // 2. Nettoyage de la table des empreintes pour éviter les fuites RAM
                EventValidator.cleanupOldFingerprints();
                Log.d(TAG, "[MAINTENANCE] Table des empreintes mémoires RAM nettoyée.");

                // 3. Re-synchronisation du calendrier pour la nouvelle journée
                EventValidator.preloadCalendar();
                Log.d(TAG, "[MAINTENANCE] Calendrier économique mis à jour.");
                
            } catch (Exception e) {
                Log.e(TAG, "[MAINTENANCE] Erreur lors de la maintenance à minuit", e);
            } finally {
                isSyncing = false;
            }
        }
    }, initialDelayMillis, period24HoursMillis, TimeUnit.MILLISECONDS);

    }

 @Override
 public void onNotificationPosted(StatusBarNotification sbn) {
    // 1. Vérification de l'état d'activation du bot
    if (!getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("bot_active", false)) return;

    Bundle extras = sbn.getNotification().extras;
    String title = extras.getString(Notification.EXTRA_TITLE, "");

    // Priorité décroissante pour l'extraction du texte : BigText > SubText > Summary > Text
    String bigText    = extras.getString(Notification.EXTRA_BIG_TEXT, "");
    String subText    = extras.getString(Notification.EXTRA_SUB_TEXT, "");
    String summary    = extras.getString(Notification.EXTRA_SUMMARY_TEXT, "");
    String text       = extras.getString(Notification.EXTRA_TEXT, "");

    // Sélection du contenu textuel le plus riche et complet
    String body = bigText.length() > text.length() ? bigText : text;
    if (subText.length() > body.length())   body = subText;
    if (summary.length() > body.length())   body = summary;
    String unifiedFeed = (title + " " + body).trim();
    
    // Dégroupe les notifications complexes (ex: structures multi-lignes d'Investing.com)
    CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
    if (lines != null && lines.length > 0) {
        StringBuilder bundled = new StringBuilder(title).append(" ");
        for (CharSequence line : lines) {
            if (line != null) bundled.append(line).append(" ");
        }
        String bundledFeed = bundled.toString().trim();
        if (bundledFeed.length() > unifiedFeed.length()) {
            unifiedFeed = bundledFeed;
        }
    }
    
    // Filtre de taille minimale pour éviter de traiter des notifications vides ou corrompues
    if (unifiedFeed.length() < 6) return;

    // 2. Identification stricte de la source du flux macro
    String packageName = sbn.getPackageName().toLowerCase();
    String sourceName = "Source Institutionnelle";

    if (packageName.contains("com.financialjuice.androidapp") || packageName.contains("financialjuice")) {
        sourceName = "FinancialJuice";
    } else if (packageName.contains("com.fusionmedia.investing") || packageName.contains("investing")) {
        sourceName = "Investing.com";
    } else if (packageName.contains("com.twitter.android") || packageName.contains("twitter") || packageName.contains("periscope")) {
        sourceName = "X / Twitter";
    } else {
        // Ignore toutes les autres applications non configurées dans le périmètre du bot
        return;
    }

    String upperFeed = unifiedFeed.toUpperCase();
    long currentTime = System.currentTimeMillis();
    String currentSpeaker = "";

    // Détection de l'intervenant (Central Bank Speakers)
    if (upperFeed.contains("WARSH"))           currentSpeaker = "WARSH";
    else if (upperFeed.contains("POWELL"))     currentSpeaker = "POWELL";
    else if (upperFeed.contains("BARKIN"))     currentSpeaker = "BARKIN";
    else if (upperFeed.contains("GOOLSBEE"))   currentSpeaker = "GOOLSBEE";
    else if (upperFeed.contains("HAMMACK"))    currentSpeaker = "HAMMACK";
    else if (upperFeed.contains("WALLER"))     currentSpeaker = "WALLER";
    else if (upperFeed.contains("WILLIAMS"))   currentSpeaker = "WILLIAMS";
    else if (upperFeed.contains("KUGLER"))     currentSpeaker = "KUGLER";
    else if (upperFeed.contains("LAGARDE"))    currentSpeaker = "LAGARDE";
    else if (upperFeed.contains("BAILEY"))     currentSpeaker = "BAILEY";
    else if (upperFeed.contains("MACKLEM"))    currentSpeaker = "MACKLEM";
    else if (upperFeed.contains("BULLOCK"))    currentSpeaker = "BULLOCK";
    else if (upperFeed.contains("UEDA"))       currentSpeaker = "UEDA";
    
    // 3. Détection sécurisée du type d'événement macro (Anti-Crash)
    EconomicEventDetector.DetectedEvent detection = EconomicEventDetector.detectEvent(title, body);
    
    boolean isSupremeRank = false;
    String eventTypeStr = "UNKNOWN";
    
    if (detection != null && detection.eventType != null) {
        eventTypeStr = detection.eventType;
        if (eventTypeStr.equals("FED-MONETARY-POLICY") || eventTypeStr.equals("INFLATION-DATA")) {
            isSupremeRank = true;
        }
    }
    
    String speakerToken = (currentSpeaker != null) ? currentSpeaker.trim() : "";
    if (speakerToken.equals("WARSH") || speakerToken.equals("WALLER")) {
        isSupremeRank = true;
    }

    // 4. Gestion du verrou anti-spam (60 secondes) pour les discours denses
    if (!speakerToken.isEmpty()) {
        if (!isSupremeRank) {
            if (speakerToken.equals(lastSpeaker) && (currentTime - lastSpeechTime < 60000)) {
                Log.d(TAG, "Doublon de notification filtré (" + speakerToken + ") pour éviter le spam.");
                return;
            }
        }
        lastSpeechTime = currentTime;
        lastSpeaker = speakerToken;
    }

    // 5. Extraction native des actifs financiers cibles
    List<String> enrichedAssets = new ArrayList<>();
    String scanBody = ((title != null ? title : "") + " " + (body != null ? body : "")).toUpperCase(Locale.ROOT);

    if (scanBody.contains("EURUSD") || scanBody.contains("EUR/") || scanBody.contains("EURO")) enrichedAssets.add("EURUSD");
    if (scanBody.contains("USDJPY") || scanBody.contains("JPY")  || scanBody.contains("YEN"))  enrichedAssets.add("USDJPY");
    if (scanBody.contains("GBPUSD") || scanBody.contains("GBP/") || scanBody.contains("POUND")) enrichedAssets.add("GBPUSD");
    if (scanBody.contains("AUDUSD") || scanBody.contains("AUD/"))                                enrichedAssets.add("AUDUSD");
    if (scanBody.contains("USDCAD") || scanBody.contains("CAD/"))                                enrichedAssets.add("USDCAD");
    if (scanBody.contains("GOLD")   || scanBody.contains("XAU"))                                 enrichedAssets.add("GOLD");
    if (scanBody.contains("USOIL")  || scanBody.contains("CRUDE") || scanBody.contains("WTI"))   enrichedAssets.add("USOIL");
    if (scanBody.contains("NASDAQ") || scanBody.contains("NAS100")|| scanBody.contains("USTECH")) enrichedAssets.add("NASDAQ");
    if (scanBody.contains("SP500")  || scanBody.contains("S&P")   || scanBody.contains("SPX"))   enrichedAssets.add("SP500");
    if (scanBody.contains("BITCOIN")|| scanBody.contains("BTC"))                                 enrichedAssets.add("BITCOIN");

    // Fallback d'affectation intelligent (Contraintes d'isolation des Banques Centrales Étrangères)
    if (enrichedAssets.isEmpty()) {
        if (upperFeed.contains("ECB") || upperFeed.contains("BCE") || upperFeed.contains("LAGARDE")) {
            // Règle d'exclusion : On cible uniquement la devise concernée, NEUTRE sur les indices US
            enrichedAssets.add("EURUSD");
        } else if (upperFeed.contains("BOJ") || upperFeed.contains("YEN") || upperFeed.contains("UEDA")) {
            // Règle d'exclusion : On cible uniquement l'actif lié à la Banque du Japon
            enrichedAssets.add("USDJPY");
        } else {
            // Allocation par défaut pour les événements génériques sans mot-clé explicite
            enrichedAssets.add("NASDAQ");
            enrichedAssets.add("SP500");
            enrichedAssets.add("US10Y");
        }
    }

    // 6. Validation du flux par le validateur de pertinence
    EventValidator.ValidationResult validationResult = EventValidator.validate(title, body, currentTime, enrichedAssets);

    // Arbitrage du droit d'écriture immédiat en base de données local SQLite
    boolean forceSave = validationResult.isConfirmed || isSupremeRank;

    if (forceSave) {
        String fingerprint = generateSecureHash(packageName + "_" + title + "_" + body + "_" + (sbn.getPostTime() / 60000));
        
        // Sérialisation propre de la liste des actifs au format CSV pour SQLite
        StringBuilder assetsSb = new StringBuilder();
        for (int i = 0; i < enrichedAssets.size(); i++) {
            assetsSb.append(enrichedAssets.get(i));
            if (i < enrichedAssets.size() - 1) assetsSb.append(",");
        }
        String assetsString = assetsSb.toString();

        // 7. Calcul du poids de dominance macroéconomique (Driver Weight)
        int driverWeight = 1; // Valeur par défaut pour les événements d'intensité standard
        if (!eventTypeStr.equals("UNKNOWN")) {
            if (isSupremeRank) {
                driverWeight = 5;
            } else if (eventTypeStr.startsWith("GEO") || eventTypeStr.equals("CENTRAL-BANK-RATE") || eventTypeStr.equals("EMPLOYMENT-REPORT")) {
                driverWeight = 4;
            } else if (eventTypeStr.equals("ECONOMIC-GROWTH-DATA")) {
                driverWeight = 2;
            }
        }

        // 8. Sauvegarde synchrone dans la base SQLite locale
        boolean saved = eventDb.saveEvent(
                fingerprint,
                packageName,
                sourceName,
                eventTypeStr,
                title,
                body,
                assetsString,
                "pending",                 // Statut de traitement interne mis à jour
                sbn.getPostTime() / 1000,  // Conversion de l'horodatage en secondes (Unix Timestamp)
                "pending",                 // Statut d'affichage unifié (Remplacement validé d'attente)
                driverWeight
        );

        if (saved) {
            Log.d(TAG, "[VALIDATEUR] Événement macro validé inséré en base : " + eventTypeStr + " [Poids: " + driverWeight + "]");
        }
    }

    // 9. Routage unique vers le pipeline d'analyse asynchrone (Groq / Telegram)
    // Évite tout doublon d'exécution en centralisant l'appel ici
    processIncomingMacroFeed(sourceName, title, body, unifiedFeed, packageName, sbn.getPostTime());
 }

        
    private void processIncomingMacroFeed(String source, String title, String text, String feed, String pkg, long postTime) {
        String heureExacteMada = getMadaFormattedDateTime();
        // 2. Injection du contexte temporel au début de la variable feed avant l'analyse
        feed = "CONTEXTE TEMPOREL : Nous sommes le " + heureExacteMada + " (Heure de Madagascar).\n\n" + feed;
            
        long now = System.currentTimeMillis();
        boolean isGeoEvent = isGeoEvent(feed.toUpperCase(Locale.ROOT));

        // 1. Throttle géopolitique prioritaire
        if (isGeoEvent && (now - lastGeoTime < GEO_THROTTLE_MS)) {
            Log.d(TAG, "[THROTTLE] Notification Géo bloquée (12 min) - dernier il y a " + (now - lastGeoTime)/1000 + "s");
            return;
        }

        // 2. Throttle global uniquement pour les événements non-géo
        if (!isGeoEvent && (now - lastAnalysisTime < GLOBAL_THROTTLE_MS)) {
            Log.d(TAG, "[THROTTLE] Notification instantanée bloquée (global - 8 min)");
            return;
        }

        List<String> targetAssets = filterActiveAssets(feed);
        EventValidator.ValidationResult vr = EventValidator.validate(title, feed, postTime, targetAssets);

        // Détection élargie de TOUTES les actualités majeures (Suprêmes, Secondaires et Tertiaires)
        String upFeed = feed.toUpperCase(Locale.ROOT);
        boolean isSupremeNews = upFeed.contains("FOMC") || 
                                upFeed.contains("FED ") || 
                                upFeed.contains("CPI")  || 
                                upFeed.contains("PCE")  || 
                                upFeed.contains("NFP")  || 
                                upFeed.contains("BCE")  || 
                                upFeed.contains("ECB")  || 
                                upFeed.contains("BOJ")  || 
                                upFeed.contains("BOE")  || 
                                upFeed.contains("RBA")  || 
                                upFeed.contains("BOC")  || 
                                upFeed.contains("PIB")  || 
                                upFeed.contains("GDP")  || 
                                upFeed.contains("OPEC") ||
                                upFeed.contains("INFLATION") ||
                                upFeed.contains("INTEREST RATE") ||
                                upFeed.contains("POWELL") ||
                                upFeed.contains("LAGARDE") ||
                                upFeed.contains("PMI") ||
                                upFeed.contains("ISM");

        int weight = assignDriverWeight(feed);

        if (vr.isConfirmed && !vr.geoContext.isEmpty() && vr.confidence >= 70) {
            weight = Math.max(weight, 4);
        }

        String hash = generateSecureHash(title + text);
            
        Log.d(TAG, "🟢 Nouvelle notification : source=" + source + ", title=" + title + ", hash=" + hash);
            
        // MODIFICATION STRATÉGIQUE : Le filtre s'applique uniquement si weight < 3 (Poids 1). Le poids 3 passe sans blocage !
        if (!vr.isConfirmed && weight < 3 && !isSupremeNews && !detectDriverDeviation(feed)) {
            eventDb.saveEvent(hash, pkg, source, "Soft-Data", title, feed,
                    String.join(", ", targetAssets), "Conforme (Filtré)", (long)(postTime/1000), "synced", weight);
            return;
        }

        if (!vr.isConfirmed && weight < 3) return;

        EconomicEventDetector.DetectedEvent detected = EconomicEventDetector.detectEvent(title, feed);

        String initialImpact;
        if (!vr.geoContext.isEmpty()) {
            initialImpact = "🌍 CHOC GÉOPOLITIQUE [" + vr.geoContext + "] — Conviction: " + vr.confidence + "% | " + detected.impact;
        } else if (isSupremeNews && (upFeed.contains("FOMC") || upFeed.contains("FED "))) {
            initialImpact = "💥 PIVOT MAJEUR BANQUE CENTRAL | " + detected.description + " | " + detected.impact;
        } else {
            initialImpact = "⚡ [" + detected.eventType + "] " + detected.description + " | " + detected.impact + " (Poids: " + weight + ")";
        }

        if (vr.geoContext.isEmpty() && !(upFeed.contains("FOMC") || upFeed.contains("FED "))) {
            if (detected.impact != null && (detected.impact.equalsIgnoreCase("Neutre") || detected.impact.toUpperCase().contains("NEUTRE"))) {
                // Mesure de sécurité pour que le poids 3 majeur ne soit pas écrasé par un faux neutre
                if (weight < 3) {
                    Log.d(TAG, "Événement filtré (Bruit Neutre standard). Annulation.");
                    return;
                }
            }
        }
        // SÉCURISATION DATE BASE DE DONNÉES : On utilise le temps machine absolu (divisé par 1000 pour avoir des secondes)
        long timestampSec = System.currentTimeMillis() / 1000;
        boolean saved = eventDb.saveEvent(hash, pkg, source, "Macro-Choc", title, feed,
                String.join(", ", targetAssets), initialImpact, timestampSec, "pending", weight);
        if (saved && isDeviceOnline()) {
            triggerQueueSynchronization();
        }

        // Enclenchement immédiat du briefing si poids fort (>=4) ou si l'analyse confirme une opportunité claire (>=3 avec confirmation)
        if (weight >= 4 || (weight >= 3 && vr.isConfirmed) || (vr.isConfirmed && vr.confidence >= 70)) {
            Log.d(TAG, "[DAILY TRIGGER] Driver qualifié détecté (weight=" + weight + 
                    ", confidence=" + vr.confidence + ") → génération immédiate du rapport");
            exec.submit(this::generateAndSendDailyBrief);
        }
    }

    private int assignDriverWeight(String text) {
        String u = text.toUpperCase();
        
        // CORRECTION ACTIFS CRUCIAUX : Ajout de la détection des synonymes/surnoms institutionnels
        if (u.contains("CPI")            || u.contains("INFLATION")       || u.contains("NFP")          ||
            u.contains("NON-FARM PAYROLLS") || u.contains("FOMC")           || u.contains("INTEREST RATE") ||
            u.contains("RBA")            || u.contains("BOC")             || u.contains("BOJ")           ||
            u.contains("BOE")            || u.contains("ECB")             || u.contains("BCE")           ||
            u.contains("LAGARDE")        || u.contains("BAILEY")          || u.contains("MACKLEM")       ||
            u.contains("BULLOCK")        || u.contains("UEDA")            || u.contains("CABLE")         || 
            u.contains("STERLING")       || u.contains("AUSSIE")          || u.contains("LOONIE")        ||
            u.contains("WARSH")          || u.contains("POWELL")) return 5;
            
        if (u.contains("GDP")                || u.contains("PIB")                    ||
            u.contains("RETAIL SALES")       || u.contains("EMPLOYMENT RATE")        ||
            u.contains("STOCKS")             || u.contains("JOBLESS")                ||
            u.contains("ADP")                || u.contains("JOLTS")                  ||
            u.contains("JOB OPENINGS")       || u.contains("PPI")                    ||
            u.contains("PRODUCER PRICE")     || u.contains("DURABLE GOODS")          ||
            u.contains("TRADE BALANCE")      || u.contains("CURRENT ACCOUNT")        ||
            u.contains("INDUSTRIAL PRODUCTION") || u.contains("CAPACITY UTILIZATION") ||
            u.contains("PHILLY FED")         || u.contains("EMPIRE STATE")           ||
            u.contains("CHICAGO PMI")        || u.contains("BEIGE BOOK")             ||
            u.contains("PERSONAL SPENDING")  || u.contains("PERSONAL INCOME")        ||
            u.contains("HOUSING STARTS")     || u.contains("BUILDING PERMITS")       ||
            u.contains("WTI")                || u.contains("BRENT")                  || 
            u.contains("CRUDE OIL")          || u.contains("XAUUSD")                 ||
            u.contains("HOME SALES")         || u.contains("CHALLENGER")) return 4;
            
        if (u.contains("PMI")               || u.contains("ISM")                  ||
            u.contains("MICHIGAN")          || u.contains("CONSUMER CONFIDENCE")   ||
            u.contains("CONSUMER SENTIMENT")|| u.contains("IMPORT PRICE")          ||
            u.contains("NAS100")            || u.contains("SPX")                  ||
            u.contains("US500")             || u.contains("USTECH")                ||
            u.contains("EXPORT PRICE")      || u.contains("NATURAL GAS")) return 3;
            
        return 1;
    }

    private boolean detectDriverDeviation(String text) {
        String upper = text.toUpperCase();
        if (upper.contains("HIGHER THAN EXPECTED") || upper.contains("LOWER THAN EXPECTED") ||
            upper.contains("ABOVE FORECAST") || upper.contains("BELOW FORECAST") ||
            upper.contains("SURPRISE") || upper.contains("SHOCK") || upper.contains("MISSES")) return true;

        Pattern pattern = Pattern.compile("(ACTUAL|ACT):?\\s*([\\d\\.\\-%]+).*?(FORECAST|EST|EXP):?\\s*([\\d\\.\\-%]+)");
        Matcher matcher = pattern.matcher(upper);
        if (matcher.find()) {
            try {
                double actual = Double.parseDouble(matcher.group(2).replaceAll("[^\\d\\.]", ""));
                double forecast = Double.parseDouble(matcher.group(4).replaceAll("[^\\d\\.]", ""));
                return actual != forecast;
            } catch (Exception e) { return true; }
        }
        return false;
    }

    private synchronized void triggerQueueSynchronization() {
        if (isSyncing || !isDeviceOnline()) return;
        isSyncing = true;

        exec.submit(() -> {
            Cursor cursor = null;
            try {
                long now = System.currentTimeMillis() / 1000;
                fetchMissingDataFromInstitutionalAPI();

                cursor = eventDb.getUnsyncedEvents(now);
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        String fingerprint = cursor.getString(cursor.getColumnIndexOrThrow("fingerprint"));
                        String source = cursor.getString(cursor.getColumnIndexOrThrow("source"));
                        String feed = cursor.getString(cursor.getColumnIndexOrThrow("feed_content"));
                        String assetsStr = cursor.getString(cursor.getColumnIndexOrThrow("target_assets"));
                        long timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("unix_timestamp")) * 1000;

                        List<String> assets = Arrays.asList(assetsStr.split(", "));
                        String historyContext = eventDb.getRecentEventsForAssets(assets, 5);

                        boolean success = executeAnalysisPipeline(source, feed, historyContext, assets, timestamp, fingerprint);
                        if (!success) {
                            Log.w(TAG, "Échec de traitement du nœud : " + fingerprint);
                        }

                    } while (cursor.moveToNext());
                }
            } catch (Exception e) {
                Log.e(TAG, "Erreur synchronisation réseau", e);
            } finally {
                if (cursor != null) cursor.close();
                isSyncing = false;
            }
        });
    }

    // Point 6 : Connexion fermée de manière étanche dans le bloc finally
    private void fetchMissingDataFromInstitutionalAPI() {
        HttpURLConnection conn = null;
        try {
            String macroApiKey = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_MACRO_KEY, "");
            if (macroApiKey.isEmpty()) return;

            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("GMT+3"));
            String todayStr = dateFormat.format(cal.getTime());
            cal.add(Calendar.DAY_OF_YEAR, -2);
            String twoDaysAgoStr = dateFormat.format(cal.getTime());

            String urlString = String.format("https://financialmodelingprep.com/api/v3/economic_calendar?from=%s&to=%s&apikey=%s", twoDaysAgoStr, todayStr, macroApiKey);
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);

            if (conn.getResponseCode() == 200) {
                BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = rd.readLine()) != null) response.append(line);
                rd.close();

                JSONArray calendarEvents = new JSONArray(response.toString());
                StringBuilder apiMacroBlock = new StringBuilder();

                for (int i = 0; i < calendarEvents.length(); i++) {
                    JSONObject event = calendarEvents.getJSONObject(i);
                    String impact = event.optString("impact", "LOW");
                    String currency = event.optString("currency", "USD");

                    if (impact.equalsIgnoreCase("HIGH") &&
                       (currency.equals("USD") || currency.equals("AUD") || currency.equals("CAD") ||
                        currency.equals("JPY") || currency.equals("EUR") || currency.equals("GBP"))) {

                        String date = event.optString("date", "");
                        String eventName = event.optString("event", "");
                        double actual = event.optDouble("actual", 0.0);
                        double estimate = event.optDouble("estimate", 0.0);

                        if (actual != estimate) {
                            apiMacroBlock.append(String.format("- [%s] (%s) %s | Actuel: %s vs Attendu: %s\n", date, currency, eventName, actual, estimate));
                        }
                    }
                }

                if (apiMacroBlock.length() > 0) {
                    dispatchHistoricalBulkToGroq(apiMacroBlock.toString());
                }
            }
        } catch (Exception e) { 
            Log.e(TAG, "Échec de récupération API historique", e); 
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void dispatchHistoricalBulkToGroq(String bulkData) {
        HttpURLConnection conn = null;
        try {
            String apiKey = getGroqApiKey();
            if (apiKey.isEmpty()) return;

            JSONObject payload = new JSONObject();
            payload.put("model", GROQ_MODEL);
            payload.put("temperature", 0.1);

            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", "Tu es un Macro-Strategist de premier plan. Analyse ce relevé complet de données à fort impact."));
            messages.put(new JSONObject().put("role", "user").put("content", "DONNÉES EXTRAITES :\n" + bulkData));
            payload.put("messages", messages);

            URL url = new URL(GROQ_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);

            OutputStream os = conn.getOutputStream();
            os.write(payload.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder r = new StringBuilder();
                String l;
                while ((l = br.readLine()) != null) r.append(l);
                br.close();

                String analysis = new JSONObject(r.toString()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
                sendTelegramSecure("🚨 *RAPPORT CRITIQUE DE RATTRAPAGE INTER-MARCHÉS (J+7)*\n\n" + analysis, this);

                eventDb.saveEvent(generateSecureHash(analysis), "com.tradingbot.sync", "API Sync", "Weekly-Sync", "Audit Global", analysis, "ALL_ASSETS", "ALIGNE_OK", (long)(System.currentTimeMillis()/1000), "synced", 5);
            }
        } catch (Exception e) { Log.e(TAG, "Échec dispatch historique Groq", e); }
        finally {
            if (conn != null) conn.disconnect();
        }
    }

    // Points 3 & 4 : Traitement rigoureux et robuste de isGeoEvent, filtrage strict de la casse et return / markEvent synchronisés
    private boolean executeAnalysisPipeline(String source, String feed, String history, 
                                            List<String> assets, long ts, String fingerprint) {
        int maxRetries = 3;
        int attempt = 0;

        String apiKey = getGroqApiKey();
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String tgToken  = prefs.getString(PREF_TG_TOKEN, "");
        String tgChatId = prefs.getString(PREF_TG_CHAT_ID, "");
        if (apiKey.isEmpty() || tgToken.isEmpty() || tgChatId.isEmpty()) return false;

        while (attempt < maxRetries) {
            HttpURLConnection conn = null;
            try {
                String upperFeed = feed.toUpperCase(Locale.ROOT);
                boolean isGeoEvent = isGeoEvent(upperFeed);

                SimpleDateFormat sdf = new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE);
                sdf.setTimeZone(TimeZone.getTimeZone("Indian/Antananarivo"));
                String timeString = sdf.format(new Date(ts));

                URL url = new URL(GROQ_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(15000);
                conn.setDoOutput(true);

                JSONObject payload = new JSONObject();
                payload.put("model", GROQ_MODEL);
                payload.put("temperature", 0.02);
                JSONArray messages = new JSONArray();

                messages.put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT));

                String assetSpecs = "Spécifications strictes des Pictogrammes d'Actifs à insérer devant chaque ligne :\n" +
                                    "GOLD: 🏆, USOIL: 🛢️, NASDAQ: 💻, SP500: 📊, US10Y: 📈, BITCOIN: ₿, " +
                                    "EURUSD: 🇪🇺, GBPUSD: 🇬🇧, AUDUSD: 🇦🇺, USDCAD: 🇨🇦, USDJPY: 🇯🇵";
                messages.put(new JSONObject().put("role", "system").put("content", assetSpecs));
                messages.put(new JSONObject().put("role", "user").put("content", "Flux brut reçu : " + feed + "\nMémoire contextuelle ordonnée par importance :\n" + history));
                payload.put("messages", messages);

                // Protection automatique de l'OutputStream (Try-with-resources)
               try (OutputStream os = conn.getOutputStream()) {
                   os.write(payload.toString().getBytes("UTF-8"));
                   os.flush();
               } // Le flux d'écriture se ferme automatiquement ici, même si un crash réseau survient

               if (conn.getResponseCode() == 200) {
               StringBuilder r = new StringBuilder();
    
               // Protection automatique de l'InputStream
               try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                  String l;
                     while ((l = br.readLine()) != null) {
                       r.append(l);
                     }
               } // Le BufferedReader et conn.getInputStream() se ferment automatiquement ICI

               // Extraction du JSON sécurisée
                  // Extraction du JSON sécurisée
              String aiResult = new JSONObject(r.toString())
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");
    
              if (aiResult == null || aiResult.isEmpty() || aiResult.length() < 50) {
                 throw new Exception("Invalid API response");
              }

              // ====================== FILTRAGE INTELLIGENT ======================
              StringBuilder filteredMessage = new StringBuilder();
              String[] lines = aiResult.split("\n");
              int activeSignalsCount = 0;
              boolean inImpactSection = false;

              for (String line : lines) {
                  String trimmed = line.trim();
                  if (trimmed.isEmpty()) continue;

                  // Toujours garder les headers
                  if (trimmed.startsWith("🚨") || 
                      trimmed.startsWith("📊") || 
                      trimmed.startsWith("🎯") || 
                      trimmed.startsWith("📢") || 
                      trimmed.startsWith("🏁") ||
                      trimmed.startsWith("--- IMPACTS")) {
                      
                      filteredMessage.append(line).append("\n");
                      if (trimmed.startsWith("--- IMPACTS")) {
                          inImpactSection = true;
                      }
                      continue;
                  }

                  // Dans la section IMPACTS
                                    // Dans la section IMPACTS
                  if (inImpactSection && trimmed.startsWith("•")) {
                      String upperLine = line.toUpperCase(Locale.ROOT);

                      boolean isSignificant = upperLine.contains("ACHAT CHOC") || 
                                             upperLine.contains("VENTE CHOC") || 
                                             upperLine.contains("INCLINATION ACHAT") || 
                                             upperLine.contains("INCLINATION VENTE");

                      if (isSignificant) {
                          filteredMessage.append(line).append("\n");
                          activeSignalsCount++;
                      }
                      // Ignorer les NEUTRE pour Telegram
                  }
              }

              // ====================== ENVOI TELEGRAM ======================
              if (activeSignalsCount > 0) {
                  if (aiResult.contains("CONVICTION") && 
                      (aiResult.contains("⚪⚪⚪⚪⚪") || aiResult.contains("20%") || aiResult.contains("30%"))) {
                      eventDb.markEventAsSynced(fingerprint, "LOW_CONVICTION");
                      return true;
                  }

                  String finalPayload = "⚡ *ANALYSE DRIVER MACRO EXPLICATIVE*\n"
                          + "🕒 " + timeString + " (Mada)\n"
                          + "📡 Source : " + source + "\n"
                          + filteredMessage.toString().trim();

                  if (finalPayload.length() < 200) {
                      eventDb.markEventAsSynced(fingerprint, "TOO_SHORT");
                      return true;
                  }

                  Log.d(TAG, "📤 Envoi Telegram pour fingerprint=" + fingerprint + ", signaux impactants=" + activeSignalsCount);
                  
                  sendTelegramSecure(finalPayload, this);
                  
                  lastAnalysisTime = System.currentTimeMillis();
                  if (isGeoEvent) {
                      lastGeoTime = System.currentTimeMillis();
                  }
                  
                  eventDb.markEventAsSynced(fingerprint, "PROCESSED_OK");
                  return true;

              } else {
                  // Aucun signal fort → on marque comme filtré
                  eventDb.markEventAsSynced(fingerprint, "FILTERED_ALL_NEUTRAL");
                  Log.d(TAG, "Tous les actifs neutres → pas d'envoi Telegram");
                  return true;
              }

                } else {
                    throw new Exception("API Error: " + conn.getResponseCode());
                }

            } catch (Exception e) {
                attempt++;
                Log.e(TAG, "Tentative " + attempt + "/" + maxRetries + " échouée", e);
                if (attempt < maxRetries) {
                    try { Thread.sleep(2000 * attempt); } catch (InterruptedException ie) { return false; }
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return false;
    }

    private List<String> filterActiveAssets(String text) {
        List<String> assets = new ArrayList<>();
        String upper = text.toUpperCase();

        if (upper.contains("WARSH")       || upper.contains("POWELL")          ||
            upper.contains("BARKIN")      || upper.contains("GOOLSBEE")        ||
            upper.contains("HAMMACK")     || upper.contains("WALLER")          ||
            upper.contains("WILLIAMS")    || upper.contains("KUGLER")          ||
            upper.contains("FOMC")        || upper.contains("FEDERAL RESERVE") ||
            upper.contains("FED CHAIR")   || upper.contains("FED RATE")) {
            assets.addAll(Arrays.asList(
                "GOLD", "NASDAQ", "SP500", "BITCOIN",
                "USDJPY", "EURUSD", "GBPUSD", "AUDUSD", "USDCAD", "US10Y"
            ));
        }

        if (upper.contains("GOLD")   || upper.contains("XAU")    ||
            upper.contains("OR ")    || upper.contains("SILVER")) assets.add("GOLD");

        if (upper.contains("OIL")    || upper.contains("WTI")    ||
            upper.contains("CRUDE")  || upper.contains("BRENT")) assets.add("USOIL");

        if (upper.contains("NASDAQ") || upper.contains("NAS100") ||
            upper.contains("TECH")   || upper.contains("OPENAI") ||
            upper.contains("NVIDIA") || upper.contains("APPLE")) assets.add("NASDAQ");

        if (upper.contains("SP500")  || upper.contains("S&P")    ||
            upper.contains("SPX")) assets.add("SP500");

        if (upper.contains("BITCOIN") || upper.contains("BTC")   ||
            upper.contains("CRYPTO")) assets.add("BITCOIN");

        if (upper.contains("YIELD")  || upper.contains("US10Y")  ||
            upper.contains("BOND")   || upper.contains("TREASURY")) assets.add("US10Y");

        if (upper.contains("EURUSD")   || upper.contains("ECB")       ||
            upper.contains("EUROZONE") || upper.contains("LAGARDE")   ||
            upper.contains("BCE")      || upper.contains("FRANKFURT") ||
            upper.matches(".*\\bEUR\\b.*")) assets.add("EURUSD");

        if (upper.contains("GBP")    || upper.contains("GBPUSD") ||
            upper.contains("CABLE")  || upper.contains("BOE")    ||
            upper.contains("BAILEY")) assets.add("GBPUSD");

        if (upper.contains("AUD")    || upper.contains("AUDUSD") ||
            upper.contains("AUSSIE") || upper.contains("RBA")    ||
            upper.contains("BULLOCK")) assets.add("AUDUSD");

        if (upper.contains("CAD")    || upper.contains("USDCAD") ||
            upper.contains("LOONIE") || upper.contains("BOC")    ||
            upper.contains("MACKLEM")) assets.add("USDCAD");

        if (upper.contains("JPY")    || upper.contains("USDJPY") ||
            upper.contains("YEN")    || upper.contains("BOJ")    ||
            upper.contains("UEDA")) assets.add("USDJPY");

        // Point 8 : Fallback minimal restreint et pertinent au lieu du bloc massif par défaut
        if (assets.isEmpty()) {
            assets.add("NASDAQ");
            assets.add("SP500");
            assets.add("US10Y");
        }

        return new ArrayList<>(new LinkedHashSet<>(assets));
    }
    private boolean isGeoEvent(String upperText) {
    return upperText.contains("MOYEN-ORIENT") ||
           upperText.contains("IRAN")         ||
           upperText.contains("ISRAEL")       ||
           upperText.contains("HEZBOLLAH")    ||
           upperText.contains("HOUTHI")       ||
           upperText.contains("HORMUZ")       ||
           upperText.contains("GAZA")         ||
           upperText.contains("LEBANON")      ||
           upperText.contains("UKRAINE")      ||
           upperText.contains("RUSSIA")       ||
           upperText.contains("PUTIN")        ||
           upperText.contains("ZELENSKY")     ||
           upperText.contains("NATO")         ||
           upperText.contains("CHINA")        ||
           upperText.contains("TAIWAN")       ||
           upperText.contains("XI JINPING")   ||
           upperText.contains("GÉO")          ||
           upperText.contains("GEO");
    }
    private void startDailyBriefScheduler() {
       TimeZone tz = TimeZone.getTimeZone("GMT+03:00");
       int[] targetHours = {7,8, 9, 12,13, 16, 17};
       for (int hour : targetHours) {
          scheduleDailyBriefAt(hour, tz);
       }
    }
    private void scheduleDailyBriefAt(int targetHour, TimeZone tz) {
    // 1. Créer un formateur de date fiable (UTC+3)
    SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    dayFormat.setTimeZone(tz);  // Force UTC+3

    Calendar now = Calendar.getInstance(tz);
    Calendar nextRun = Calendar.getInstance(tz);
    nextRun.set(Calendar.HOUR_OF_DAY, targetHour);
    nextRun.set(Calendar.MINUTE, 0);
    nextRun.set(Calendar.SECOND, 0);
    nextRun.set(Calendar.MILLISECOND, 0);

    // 2. Rattrapage si l'heure cible est déjà passée aujourd'hui
    if (nextRun.getTimeInMillis() <= now.getTimeInMillis()) {
        String today = dayFormat.format(now.getTime());
        String prefKey = PREF_LAST_DAILY_REPORT + targetHour;
        String lastSent = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(prefKey, "");
        if (!today.equals(lastSent)) {
            Log.d(TAG, "[DAILY] Rattrapage pour " + targetHour + "h : envoi immédiat");
            generateAndSendDailyBrief();
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(prefKey, today)
                .apply();
        }
        nextRun.add(Calendar.DAY_OF_YEAR, 1);
    }

    long delay = nextRun.getTimeInMillis() - now.getTimeInMillis();

    // 3. Log de diagnostic avec la date planifiée (UTC+3)
    SimpleDateFormat sdfLog = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault());
    sdfLog.setTimeZone(tz);
    String nextRunStr = sdfLog.format(nextRun.getTime());
    Log.d(TAG, "[DAILY] Horaire " + targetHour + "h : prochain déclenchement à " + nextRunStr);

    // 4. Planification unique (pas de récursion)
    scheduler.schedule(() -> {
        String currentDay = dayFormat.format(Calendar.getInstance(tz).getTime());
        String prefKey = PREF_LAST_DAILY_REPORT + targetHour;
        String lastSent = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(prefKey, "");
        if (!currentDay.equals(lastSent)) {
            generateAndSendDailyBrief();
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(prefKey, currentDay)
                .apply();
        } else {
            Log.d(TAG, "[DAILY] Rapport déjà envoyé aujourd'hui pour " + targetHour + "h, ignoré");
        }
        // 5. Replanifier pour le lendemain à la même heure
        scheduleDailyBriefAt(targetHour, tz);
    }, delay, TimeUnit.MILLISECONDS);
   }
    
   private void generateAndSendDailyBrief() {
    HttpURLConnection conn = null;
    try {
        String apiKey = getGroqApiKey();
        if (apiKey.isEmpty()) return;

        long nowSec = System.currentTimeMillis() / 1000;
        String dailyDrivers = eventDb.getDailyMacroSummary(nowSec);

        if (dailyDrivers == null || dailyDrivers.isEmpty()) {
            Log.d(TAG, "[DAILY] Aucun driver macro trouvé pour les dernières 24h, rapport ignoré");
            return;
        }

        Log.d(TAG, "[DAILY] " + dailyDrivers.length() + " caractères de données à analyser");

        // Date locale pour le message (Mada UTC+3)
        SimpleDateFormat sdfDate = new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE);
        sdfDate.setTimeZone(TimeZone.getTimeZone("Indian/Antananarivo"));
        String dateStr = sdfDate.format(Calendar.getInstance(TimeZone.getTimeZone("Indian/Antananarivo")).getTime());

        // PROMPT SYSTEME STRUCTURÉ DU BRIEFING DAILY
        String DAILY_SYSTEM_PROMPT =
"Tu es le Directeur de la Recherche Macroéconomique d'un Hedge Fund Quantitatif d'élite.\n" +
"Analyse le résumé des drivers économiques des dernières 24 heures (fourni dans le message utilisateur) et produis un briefing strictement factuel, corrélé et directionnel.\n\n" +

"═══════════════════════════════════════════════════════════════\n" +
"                    FORMAT OBLIGATOIRE (STRICT)\n" +
"═══════════════════════════════════════════════════════════════\n\n" +

"📊 RAPPORT DRIVER PÉRIODIQUE – [Date et heure exacte de Madagascar, ex: 28/05 18:50]\n\n" +

"🚨 DRIVERS PRINCIPAUX (classés par importance macroéconomique, maximum 5) :\n\n" +
"- [Nom du Driver] : [Description courte de l'impact, une phrase]. Probabilité d'impact : XX% | Conviction : [jauge selon paliers ci-dessous]\n\n" +

"📈 IMPLICATIONS SUR LES ACTIFS (les 11 actifs dans l'ordre exact, même si neutres) :\n\n" +
"• 📈 US10Y   : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Raison ≤ 10 mots]\n" +
"• 💻 NASDAQ  : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Raison ≤ 10 mots]\n" +
"• 📊 SP500   : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Raison ≤ 10 mots]\n" +
"• 🏆 GOLD    : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Raison ≤ 10 mots]\n" +
"• 🛢️ USOIL   : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Raison ≤ 10 mots]\n" +
"• 🇪🇺 EURUSD : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Raison ≤ 10 mots]\n" +
"• 🇯🇵 USDJPY : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Raison ≤ 10 mots]\n" +
"• 🇨🇦 USDCAD : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Raison ≤ 10 mots]\n" +
"• 🇬🇧 GBPUSD : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Raison ≤ 10 mots]\n" +
"• 🇦🇺 AUDUSD : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Raison ≤ 10 mots]\n" +
"• ₿ BITCOIN  : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE ⚪] | Conviction : [jauge] | [Raison ≤ 10 mots]\n\n" +

"⚠️ SCÉNARIO ALTERNATIF :\n" +
"[Risque principal ou condition qui pourrait inverser le flux dominant, en une phrase]\n\n" +

"🏁 FLUX DOMINANT : [DOLLAR FORT / DOLLAR FAIBLE / RISK-ON / RISK-OFF / YEN FORT / EURO FORT / OR FORT]\n\n" +

"═══════════════════════════════════════════════════════════════\n" +
"                     PALIERS DE CONVICTION (Jauge 5 cercles)\n" +
"═══════════════════════════════════════════════════════════════\n\n" +
"- < 40% : ⚪⚪⚪⚪⚪\n" +
"- 41-60% : 🟠🟠🟠⚪⚪\n" +
"- 61-80% : 🟡🟡🟡🟡⚪\n" +
"- > 80% : 🔴🔴🔴🔴🔴\n\n" +

"═══════════════════════════════════════════════════════════════\n" +
"            MATRICE DE LOGIQUE ET CORRÉLATION INTERNE\n" +
"═══════════════════════════════════════════════════════════════\n\n" +

"RÈGLE 1 : CLASSEMENT ET DOMINANCE DE LA HIÉRARCHIE DES DRIVERS\n" +
"- RANG SUPRÊME : Politiques monétaires (FED, BCE, BoJ, BoE, RBA, BoC) et indicateurs clés (CPI, NFP, PPI, FOMC, PIB, Ventes au détail, Chômage).\n" +
"- RANG SECONDAIRE : Données sectorielles majeures (Stocks d'énergie EIA, OPEC, rapports agricoles d'importance).\n" +
"- RANG TACTIQUE : Événements géopolitiques, sanctions, taxes commerciales, indices de confiance/sentiment secondaires.\n" +
"👉 LOI DE DOMINANCE ABSOLUE : Si un événement de RANG SUPRÊME est actif dans les données des 24h, c'est sa logique directionnelle qui dicte le comportement du marché. Un driver tactique (comme des tensions géopolitiques) ne peut ni inverser ni annuler la direction des actifs dictée par le driver suprême.\n\n" +

"RÈGLE 2 : DRIVER ÉCONOMIQUE OU BANQUE CENTRALE AMÉRICAINE (USA)\n" +
"A) Si les données sont HAWKISH / FORTES (Inflation supérieure aux prévisions, discours restrictif de Powell/FED, NFP/Emplois très forts, PIB en forte hausse) :\n" +
"   • 📈 US10Y   -> ACHAT CHOC 🟢 [Les rendements obligataires montent mécaniquement]\n" +
"   • 💻 NASDAQ  -> VENTE CHOC 🔴 [La hausse des taux d'intérêt pénalise les valeurs technologiques]\n" +
"   • 📊 SP500   -> VENTE CHOC 🔴 [Symétrie absolue obligatoire avec le NASDAQ]\n" +
"   • 🏆 GOLD    -> VENTE CHOC 🔴 [Taux réels plus élevés et Dollar fort pèsent sur l'Or]\n" +
"   • 🛢️ USOIL   -> NEUTRE ⚪ ou selon driver secondaire dédié.\n" +
"   • 🇪🇺 EURUSD -> VENTE CHOC 🔴 [L'Euro s'effondre face à la hausse globale du Dollar US]\n" +
"   • 🇯🇵 USDJPY -> ACHAT CHOC 🟢 [Le Dollar s'apprécie face au Yen par élargissement du différentiel de taux]\n" +
"   • 🇨🇦 USDCAD -> ACHAT CHOC 🟢 [Le Dollar américain s'impose face au Dollar Canadien]\n" +
"   • 🇬🇧 GBPUSD -> VENTE CHOC 🔴 [La Livre Sterling baisse face au Dollar US]\n" +
"   • 🇦🇺 AUDUSD -> VENTE CHOC 🔴 [L'Aussie Dollar recule face au Dollar US]\n" +
"   • ₿ BITCOIN  -> VENTE CHOC 🔴 [L'aversion au risque liée aux taux hauts liquide les actifs spéculatifs]\n" +
"   • 🏁 FLUX DOMINANT -> DOLLAR FORT\n\n" +

"B) Si les données sont DOVISH / FAIBLES (Inflation plus basse que prévu, discours accommodant de la FED, hausse des inscriptions au chômage, PIB décevant) :\n" +
"   • Appliquer EXACTEMENT l'opposé mathématique des directions définies ci-dessus (Ex: US10Y -> VENTE CHOC, NASDAQ -> ACHAT CHOC, EURUSD -> ACHAT CHOC, USDJPY -> VENTE CHOC, etc.).\n" +
"   • 🏁 FLUX DOMINANT -> DOLLAR FAIBLE\n\n" +

"RÈGLE 3 : DRIVER BANQUE CENTRALE ÉTRANGÈRE (BCE, BoJ, BoE, RBA, BoC)\n" +
"👉 VERROU GÉOGRAPHIQUE OBLIGATOIRE : Si les actualités majeures concernent une banque centrale hors USA :\n" +
"   • 📈 US10Y, 💻 NASDAQ, 📊 SP500, ₿ BITCOIN sont AUTOMATIQUEMENT fixés à [NEUTRE ⚪ | Pas d'impact direct]. Il est interdit d'inventer un mouvement sur ces actifs.\n" +
"   - Si l'entité étrangère est HAWKISH (hausse des taux, resserrement quantitatif, ton ferme) :\n" +
"     • BCE (Europe)      -> 🇪🇺 EURUSD : ACHAT CHOC 🟢 | Les autres paires de devises s'ajustent au prorata.\n" +
"     • BoJ (Japon)       -> 🇯🇵 USDJPY : VENTE CHOC 🔴 [Le Yen se renforce massivement]\n" +
"     • BoC (Canada)      -> 🇨🇦 USDCAD : VENTE CHOC 🔴 [Le Dollar Canadien s'apprécie]\n" +
"     • BoE (Royaume-Uni) -> 🇬🇧 GBPUSD : ACHAT CHOC 🟢 [La Livre Sterling monte]\n" +
"     • RBA (Australie)   -> 🇦🇺 AUDUSD : ACHAT CHOC 🟢 [L'Aussie monte]\n" +
"   - Si l'entité étrangère est DOVISH, inverser strictement les directions des paires associées.\n\n" +

"RÈGLE 4 : DRIVER SECTORIEL ENERGIE (Stocks EIA / OPEC)\n" +
"- Si Baisse surprise des stocks de brut ou réduction de quotas de l'OPEC (Déficit d'offre) :\n" +
"  • 🛢️ USOIL   -> ACHAT CHOC 🟢 [Pression haussière sur les prix de l'énergie]\n" +
"  • 🇨🇦 USDCAD -> VENTE CHOC 🔴 [Le Dollar Canadien, devise pétrolière corrélée, se renforce face au Dollar]\n" +
"  • Les 9 autres actifs -> OBLIGATOIREMENT [NEUTRE ⚪ | Pas d'impact direct]. Aucun mouvement secondaire toléré.\n" +
"- Si Hausse surprise des stocks (Surplus d'offre) : 🛢️ USOIL -> VENTE CHOC 🔴, 🇨🇦 USDCAD -> ACHAT CHOC 🟢, les 9 autres actifs -> NEUTRE ⚪.\n\n" +

"RÈGLE 5 : DRIVER GÉOPOLITIQUE CRITIQUE ET SENTIMENT DE MARCHÉ (RISK-OFF TACTIQUE)\n" +
"- En cas d'escalade militaire majeure, conflits maritimes ou tensions géopolitiques fortes (Moyen-Orient, etc.) SANS driver américain suprême actif pour contrer :\n" +
"  • 🏆 GOLD    -> ACHAT CHOC 🟢 [Valeur refuge historique ultime]\n" +
"  • 🛢️ USOIL   -> ACHAT CHOC 🟢 [Prime de risque sur les routes d'approvisionnement]\n" +
"  • 🇯🇵 USDJPY -> VENTE CHOC 🔴 [Appréciation mécanique du Yen comme monnaie de rapatriement refuge]\n" +
"  • 💻 NASDAQ / 📊 SP500 / ₿ BITCOIN -> VENTE CHOC 🔴 [Fuite générale des capitaux hors des actifs à risque]\n" +
"  • Les devises majeures (EURUSD, GBPUSD, AUDUSD, USDCAD) -> OBLIGATOIREMENT [NEUTRE ⚪ | Pas d'impact direct].\n" +
"  • 🏁 FLUX DOMINANT -> RISK-OFF\n\n" +

"═══════════════════════════════════════════════════════════════\n" +
"                    CONTRAINTES DE SÉCURITÉ DE COMPILATION\n" +
"═══════════════════════════════════════════════════════════════\n\n" +
"1. SYMÉTRIE STRICTE DES INDICES : Le couple 💻 NASDAQ et 📊 SP500 doit pointer impérativement dans le même sens (soit deux ACHAT CHOC, soit deux VENTE CHOC, soit deux NEUTRE). Aucune divergence n'est tolérée.\n" +
"2. AMPLIFICATION DES CRYPTOS : L'actif ₿ BITCOIN est traité comme un indicateur de bêta élevé lié au sentiment technologique. Il doit calquer sa direction sur celle du 💻 NASDAQ.\n" +
"3. EXCLUSION ET CONCISION : Pas de politesse, pas de salutations, pas de résumés verbeux des actualités passées. Calculez les directions comme un algorithme purement déterministe. Les 11 actifs doivent figurer sur le rapport, sans omission.";
        JSONObject payload = new JSONObject();
        payload.put("model", GROQ_MODEL);
        payload.put("temperature", 0.1); // Verrouillage de la créativité pour éviter les dérives

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", DAILY_SYSTEM_PROMPT));
        messages.put(new JSONObject().put("role", "user").put("content", 
            "Génère le rapport périodique pour la date/heure : " + dateStr + " (Mada).\n" +
            "DONNÉES BRUTES DES DERNIÈRES 24H :\n" + dailyDrivers));
        payload.put("messages", messages);

        URL url = new URL(GROQ_URL);
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.toString().getBytes("UTF-8"));
            os.flush();
        }

        int responseCode = conn.getResponseCode();
        if (responseCode == 200) {
            StringBuilder r = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) {
                    r.append(line);
                }
            }
            
            JSONObject jsonResponse = new JSONObject(r.toString());
            String aiResult = jsonResponse.getJSONArray("choices")
                                         .getJSONObject(0)
                                         .getJSONObject("message")
                                         .getString("content");

            // Envoi direct et sécurisé sur Telegram
            sendTelegramSecure(aiResult, this);
            Log.d(TAG, "[DAILY] Rapport envoyé avec succès.");
            
        } else {
            Log.e(TAG, "[DAILY] Erreur API Groq: " + responseCode);
        }
    } catch (Exception e) {
        Log.e(TAG, "[DAILY] Échec critique du briefing journalier", e);
    } finally {
        if (conn != null) conn.disconnect();
    }
   }

    private void startMonthlyReportScheduler() {
        Calendar nextRun = Calendar.getInstance(TimeZone.getTimeZone("GMT+3"));
        nextRun.set(Calendar.DAY_OF_MONTH, nextRun.getActualMaximum(Calendar.DAY_OF_MONTH));
        nextRun.set(Calendar.HOUR_OF_DAY, 23);
        nextRun.set(Calendar.MINUTE, 0);
        nextRun.set(Calendar.SECOND, 0);
        if (nextRun.getTimeInMillis() <= System.currentTimeMillis()) {
            nextRun.add(Calendar.MONTH, 1);
            nextRun.set(Calendar.DAY_OF_MONTH, nextRun.getActualMaximum(Calendar.DAY_OF_MONTH));
        }
        scheduler.scheduleAtFixedRate(this::generateAndPurgeMonthlyReport, nextRun.getTimeInMillis() - System.currentTimeMillis(), 30L * 24 * 60 * 60 * 1000, TimeUnit.MILLISECONDS);
    }

    private void generateAndPurgeMonthlyReport() {
        HttpURLConnection conn = null;
        try {
            String apiKey = getGroqApiKey();
            if (apiKey.isEmpty()) return;

            long now = System.currentTimeMillis() / 1000;
            String monthlyRegistry = eventDb.getMonthlyMacroRegistry(now);
            if (monthlyRegistry.isEmpty()) return;

            JSONObject payload = new JSONObject();
            payload.put("model", GROQ_MODEL);
            payload.put("temperature", 0.1);

            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", "Analyse le registre mensuel des ruptures fondamentales."));
            messages.put(new JSONObject().put("role", "user").put("content", "REGISTRE MENSUEL :\n" + monthlyRegistry));
            payload.put("messages", messages);

            URL url = new URL(GROQ_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);

            OutputStream os = conn.getOutputStream();
            os.write(payload.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder r = new StringBuilder();
                String l;
                while ((l = br.readLine()) != null) r.append(l);
                br.close();

                String report = new JSONObject(r.toString()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");

                sendTelegramSecure("📊 *RAPPORT DE TRANSITION MACROÉCONOMIQUE MENSUEL*\n\n" + report, this);
                eventDb.purgeOldEvents(now);
            }
        } catch (Exception e) { Log.e(TAG, "Erreur Rapport Mensuel", e); }
        finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void registerNetworkCallback() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    triggerQueueSynchronization();
                }
            });
        }
    }

    private boolean isDeviceOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network net = cm.getActiveNetwork();
            if (net == null) return false;
            NetworkCapabilities cap = cm.getNetworkCapabilities(net);
            return cap != null && (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
        }
        return false;
    }

    private String generateSecureHash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return String.valueOf(System.currentTimeMillis());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Trading Core Alerts", NotificationManager.IMPORTANCE_HIGH);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }
    // ── NOUVELLE MÉTHODE DE MAINTENANCE PÉRIODIQUE ──
    private void syncCalendarAndPurge() {
        if (isSyncing) return;
        isSyncing = true;
        
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Log.d(TAG, "[MAINTENANCE] Démarrage de la synchronisation et de la purge périodique...");
                    
                    // Configuration du timestamp actuel en secondes
                    long nowSeconds = System.currentTimeMillis() / 1000;

                    // 1. Purge SQLite (Règle des 48h / 45 jours via votre instance eventDb)
                    if (eventDb != null) {
                        eventDb.purgeOldEvents(nowSeconds);
                        Log.d(TAG, "[MAINTENANCE] Base de données SQLite purgée.");
                    }

                    // 2. Nettoyage de la table des empreintes pour éviter les fuites RAM
                    EventValidator.cleanupOldFingerprints();
                    Log.d(TAG, "[MAINTENANCE] Table des empreintes mémoires RAM nettoyée.");

                    // 3. Re-synchronisation du calendrier pour les prochaines 24 heures
                    EventValidator.preloadCalendar();
                    Log.d(TAG, "[MAINTENANCE] Calendrier économique mis à jour.");
                    
                } catch (Exception e) {
                    Log.e(TAG, "[MAINTENANCE] Erreur lors de la maintenance périodique", e);
                } finally {
                    isSyncing = false;
                }
            }
        });
    }
    // ── AJOUT DE COMPATIBILITÉ CHRONOLOGIQUE (MADA) ──
    private String getMadaFormattedDateTime() {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            // On récupère dynamiquement le fuseau horaire depuis votre utilitaire existant
            sdf.setTimeZone(getMadaCalendar().getTimeZone());
            return sdf.format(new Date());
        } catch (Exception e) {
            return "N/A";
        }
    }
     public String construirePromptFinal(String evenementActuel, List<String> historiqueRecent) {
        boolean alerteGéoMajeure = false;
        
        // Mots-clés qui déclenchent le mode de crise géopolitique
        String[] motsClesCrise = {"hormuz", "iran", "frappe militaire", "riposte", "escalade", "blocus"};
        
        // On scanne l'événement actuel et l'historique récent
        String toutLeTexte = evenementActuel.toLowerCase();
        for (String hist : historiqueRecent) {
            toutLeTexte += " " + hist.toLowerCase();
        }
        
        for (String mot : motsClesCrise) {
            if (toutLeTexte.contains(mot)) {
                alerteGéoMajeure = true;
                break;
            }
        }
        
        // Si une crise est détectée, on injecte une directive prioritaire en haut du prompt
        String directiveDeCrise = "";
        if (alerteGéoMajeure) {
            directiveDeCrise = "⚠️ [ALERTE SYSTÈME : RÉGIME DE MARCHÉ EN MODE CRISE GÉOPOLITIQUE ACTIF]. " +
                               "Le risque de guerre au Moyen-Orient/Hormuz est prioritaire. " +
                               "L'Or (GOLD) doit refléter le flux refuge (Safe-Haven) indépendamment de la force du Dollar ou du PCE.\n\n";
        }
        
        return directiveDeCrise + SYSTEM_PROMPT + "\n\nFlux à analyser : " + evenementActuel;
       }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        scheduler.shutdownNow();
        exec.shutdownNow();
        Log.d(TAG, "[SERVICE] Service arrêté");
    }
}

