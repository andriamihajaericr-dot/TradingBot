// ==========================================
// BLOC 1/3 : IMPORTATIONS, CONSTANTES, SYNC STATES ET SYSTEM PROMPT IMMUABLE
// ==========================================
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
    
    private static final long GLOBAL_THROTTLE_MS = 8 * 60 * 1000L;   // 8 minutes
    private static final long GEO_THROTTLE_MS   = 12 * 60 * 1000L;  // 12 minutes pour géo
    
    private volatile long lastAnalysisTime = 0;
    private volatile long lastGeoTime = 0;
    private volatile long lastSpeechTime = 0;
    private volatile String lastSpeaker = "";

    // Objets de base de données et gestionnaires de threads
    private EventDatabase eventDb;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final ExecutorService exec = Executors.newFixedThreadPool(4); // Pool global unifié

    // Mettre ici le nom du pool d'exécuteurs de ton fichier pour garder une compatibilité parfaite
    private final ExecutorService tradingPipelineExecutor = exec; 

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
    
    "CONTRAINTE 11 — EXCEPTION DE REVIREMENT DE CRISE ET ALIGNEMENT AGGRESSIF BRUT :\n" +
    "   - Si un mot-clé de crise géopolitique majeure ou d'escalade directe (ATTACK, MISSILE, STRIKE, WAR, HORMUZ) est détecté dans le registre de données historiques des 24 dernières heures, le statut 'NEUTRE' spéculatif sur USDJPY est révoqué.\n" +
    "   - On force l'alignement de la matrice pour USDJPY sur la règle GÉO prioritaire (YEN refuge → USDJPY: VENTE CHOC 🔴), neutralisant toute interférence avec les données d'inflation classiques.\n\n" +

    "FORMAT DE SORTIE REQUIS (STRICT ET EXCLUSIF) :\n" +
    "🚨 [NOM_DU_DRIVER_REPÉRÉ]\n" +
    "📊 CONVICTION : [EMOJIS] XX%\n" +
    "🎯 VECTEUR CIBLE : [NOM_DU_VECTEUR]\n" +
    "📢 FAIT MARQUANT : [Texte explicatif court]\n" +
    "--- IMPACTS SUR LES ACTIFS ---\n" +
    "• 📈 US10Y : [Statut] | [Raison]\n" +
    "• 💻 NASDAQ : [Statut] | [Raison]\n" +
    "• 📊 SP500 : [Statut] | [Raison]\n" +
    "• 🏆 GOLD : [Statut] | [Raison]\n" +
    "• 🛢️ USOIL : [Statut] | [Raison]\n" +
    "• 🇪🇺 EURUSD : [Statut] | [Raison]\n" +
    "• 🇯🇵 USDJPY : [Statut] | [Raison]\n" +
    "• 🇨🇦 USDCAD : [Statut] | [Raison]\n" +
    "• 🇬🇧 GBPUSD : [Statut] | [Raison]\n" +
    "• 🇦🇺 AUDUSD : [Statut] | [Raison]\n" +
    "• ₿ BITCOIN : [Statut] | [Raison]\n" +
    "🏁 FLUX DOMINANT : [Chaîne de caractères]";

// ==========================================
// BLOC 2/3 : CYCLE DE VIE, CONFIGURATION INITIALE ET PLANIFICATEUR DU RAPPORT MENSUEL (23h55 MADA)
// ==========================================

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "Initialisation de l'infrastructure du NotificationService...");
        
        // Initialisation de la base de données SQLite pour l'historique des drivers
        try {
            eventDb = new EventDatabase(this);
            Log.d(TAG, "Base de données EventDatabase initialisée avec succès.");
        } catch (Exception e) {
            Log.e(TAG, "Erreur critique lors de l'initialisation de la base de données : ", e);
        }

        // Création du canal de notification requis pour le service sous Android O+
        createNotificationChannel();

        // Lancement immédiat du planificateur de rapport mensuel en arrière-plan
        scheduleMonthlyReportTask();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "Fermeture du NotificationService, libération des threads...");
        
        // Arrêt ordonné des pools d'exécution pour éviter les fuites de mémoire
        try {
            scheduler.shutdown();
            exec.shutdown();
            if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
            if (!exec.awaitTermination(3, TimeUnit.SECONDS)) {
                exec.shutdownNow();
            }
            Log.d(TAG, "Pools d'exécuteurs arrêtés proprement.");
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            exec.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.i(TAG, "NotificationListenerService connecté au système Android et opérationnel.");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.w(TAG, "NotificationListenerService déconnecté du système.");
    }

    /**
     * Crée le canal système de notifications pour l'affichage des alertes de trading internes.
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Trading Alerts Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.setDescription("Affichage des chocs macroéconomiques et alertes de trading");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
                Log.d(TAG, "Canal de notification '" + CHANNEL_ID + "' créé.");
            }
        }
    }

    /**
     * Planifie la tâche de vérification du rapport mensuel toutes les heures.
     * Cible précisément 23h55 le dernier jour du mois selon l'heure de Madagascar (GMT+3)[cite: 1].
     */
    private void scheduleMonthlyReportTask() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                // Forçage de la TimeZone sur le fuseau de Madagascar (GMT+3)[cite: 1]
                TimeZone madaZone = TimeZone.getTimeZone("GMT+3");
                Calendar now = Calendar.getInstance(madaZone);
                
                int currentDay = now.get(Calendar.DAY_OF_MONTH);
                int lastDayOfMonth = now.getActualMaximum(Calendar.DAY_OF_MONTH);
                int hour = now.get(Calendar.HOUR_OF_DAY);
                int minute = now.get(Calendar.MINUTE);

                // Vérification stricte : Dernier jour du mois à 23h55[cite: 1]
                if (currentDay == lastDayOfMonth && hour == 23 && minute >= 50) {
                    SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    String currentMonthKey = new SimpleDateFormat("yyyy-MM", Locale.US).format(now.getTime());
                    String lastSentMonth = prefs.getString("last_monthly_report_month", "");

                    // Anti-doublon pour s'assurer que le rapport ne s'exécute qu'une seule fois dans la fenêtre
                    if (!lastSentMonth.equals(currentMonthKey)) {
                        Log.i(TAG, "Déclenchement du Rapport Mensuel Automatique à 23h55 (Mada)...");
                        
                        // Envoi asynchrone pour ne pas impacter le planificateur
                        exec.execute(() -> executeAndSendMonthlyReport(currentMonthKey));
                        
                        prefs.edit().putString("last_monthly_report_month", currentMonthKey).apply();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Erreur dans la boucle de planification du rapport mensuel : ", e);
            }
        }, 0, 5, TimeUnit.MINUTES); // Vérification fine toutes les 5 minutes pour intercepter la fenêtre de 23h55
    }

    /**
     * Compile les données historiques du mois et distribue le Rapport Mensuel.
     */
    private void executeAndSendMonthlyReport(String monthKey) {
        Log.i(TAG, "Compilation du rapport macroéconomique mensuel pour la période : " + monthKey);
        try {
            // Extraction des chocs marquants stockés dans la base de données
            String summaryData = eventDb.getHighImpactEventsForMonth(monthKey);
            
            if (summaryData == null || summaryData.trim().isEmpty()) {
                summaryData = "Aucun choc macroéconomique majeur enregistré ou qualifié ce mois-ci.";
            }

            // Construction du prompt de synthèse destiné à Groq
            String reportPrompt = "Génère un Rapport Stratégique de Performance Macroéconomique pour le mois : " + monthKey + ".\n"
                    + "Voici la base brute des événements qualifiés par notre modèle :\n" + summaryData + "\n\n"
                    + "Instructions de style :\n"
                    + "- Structure de hedge fund d'élite, ton froid, analytique, ultra-quantitatif.\n"
                    + "- Synthétise les grandes tendances monétaires, les surprises d'inflation majeures et l'état des régimes géo.\n"
                    + "- Pas d'introduction polie, commence directement par le titre officiel.";

            // Requête vers Groq
            String response = queryGroqAPI(reportPrompt, "Tu es l'algorithme d'audit macroéconomique du fonds. Tu synthétises le rapport mensuel.");
            
            if (response != null && !response.isEmpty()) {
                // Envoi de la synthèse finale vers le canal Telegram configuré
                sendTelegramMessage(response);
                Log.i(TAG, "Rapport Mensuel envoyé avec succès sur Telegram.");
            } else {
                Log.e(TAG, "Échec de génération du rapport mensuel par l'API Groq (Réponse vide).");
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur lors de l'exécution du rapport mensuel : ", e);
        }
    }

    /**
     * Point d'entrée principal du Listener Android. Capture les notifications système en temps réel.
     */
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        // Extraction asynchrone immédiate pour libérer le thread principal d'Android
        exec.execute(() -> {
            try {
                String packageName = sbn.getPackageName();
                if (packageName == null) return;

                // Verrou de sécurité : Élimination stricte d'Investing.com pour basculer sur FinancialJuice / TradingEconomics[cite: 1]
                if (packageName.contains("investing") || packageName.contains("com.investing")) {
                    return; 
                }

                Bundle extras = sbn.getNotification().extras;
                if (extras == null) return;

                String title = extras.getString(Notification.EXTRA_TITLE, "");
                CharSequence textChar = extras.getCharSequence(Notification.EXTRA_TEXT);
                String text = (textChar != null) ? textChar.toString() : "";

                // Nettoyage rapide des chaînes reçues
                title = title.trim();
                text = text.trim();

                if (title.isEmpty() && text.isEmpty()) return;

                // Routage vers le pipeline d'analyse asynchrone sécurisé
                processIncomingNotification(packageName, title, text);

            } catch (Exception e) {
                Log.e(TAG, "Erreur lors du traitement asynchrone de la notification entrante : ", e);
            }
        });
    }
// ==========================================
// BLOC 3/3 : PIPELINE DE QUALIFICATION, DÉTECTION DES DOUBLONS ET TRANSMISSION RÉSEAU (GROQ/TELEGRAM)
// ==========================================

    /**
     * Filtre et qualifie la source de la notification avant d'en extraire le contenu utile.
     */
    private void processIncomingNotification(String packageName, String title, String text) {
        String sourceLabel = "";
        String cleanContent = "";

        // Identification des applications cibles prioritaires
        if (packageName.contains("financialjuice")) {
            sourceLabel = "FinancialJuice";
            cleanContent = text.isEmpty() ? title : title + " - " + text;
        } else if (packageName.contains("tradingeconomics")) {
            sourceLabel = "TradingEconomics";
            cleanContent = text.isEmpty() ? title : title + " - " + text;
        } else if (packageName.contains("twitter") || packageName.contains("com.twitter.android") || packageName.contains("x.android")) {
            sourceLabel = "X";
            // Pour X, le titre contient souvent l'émetteur et le texte contient le tweet
            cleanContent = title + ": " + text;
        } else {
            // Ignorer silencieusement toutes les autres sources non enregistrées
            return;
        }

        // Nettoyage des bruits textuels récurrents ou des résidus de notifications tronquées
        cleanContent = sanitizeNotificationText(cleanContent);
        if (cleanContent.length() < 10) return; // Trop court pour contenir une info macroéconomique utile

        // Élimination stricte des doublons identiques reçus en rafale (fréquent sur les flux temps réel)
        if (isDuplicateEvent(cleanContent)) {
            Log.d(TAG, "Notification ignorée : Doublon détecté via empreinte MD5.");
            return;
        }

        final String finalSource = sourceLabel;
        final String finalContent = cleanContent;

        // ✅ PIPELINE ASYNCHRONE SÉCURISÉ
        // L'analyse lourde et les appels réseau sont délégués au pool de threads dédié
        tradingPipelineExecutor.execute(() -> {
            try {
                Log.i(TAG, "Analyse en cours via le pipeline pour la source [" + finalSource + "] : " + finalContent);

                // Étape 1 : Construction du prompt d'évaluation de la pertinence macroéconomique
                String evaluationPrompt = "Analyse la notification suivante de manière brute. "
                        + "Détermine si elle contient un événement macroéconomique majeur, une décision de banque centrale, "
                        + "une donnée d'inflation (CPI), d'emploi (NFP), une annonce géopolitique critique ou un tarif douanier.\n\n"
                        + "Notification : \"" + finalContent + "\"\n\n"
                        + "Réponds UNIQUEMENT par un entier de 1 à 5 représentant l'indice d'importance du driver :\n"
                        + "1 = Bruit / Info entreprise / Météo / Faible importance.\n"
                        + "2 = Événement macro mineur sans impact volatilité.\n"
                        + "3 = Événement macro validé avec impact potentiel moyen.\n"
                        + "4 = Choc macroéconomique majeur (CPI, NFP, Décision taux).\n"
                        + "5 = Crise systémique ou géopolitique majeure (Frappes, escalade militaire, blocage de détroit).\n"
                        + "Interdiction d'ajouter du texte, donne uniquement le chiffre.";

                String evaluationResponse = queryGroqAPI(evaluationPrompt, "Tu es un filtre de pertinence. Tu réponds par un seul chiffre.");
                if (evaluationResponse == null) return;

                int priorityScore = 1;
                try {
                    priorityScore = Integer.parseInt(evaluationResponse.replaceAll("[^1-5]", "").trim());
                } catch (NumberFormatException e) {
                    Log.w(TAG, "Impossible de parser le score de pertinence, fallback à 1. Réponse reçue : " + evaluationResponse);
                }

                // Application du seuil révisé : On ne traite que les drivers confirmés de score >= 3
                if (priorityScore < 3) {[cite: 1]
                    Log.d(TAG, "Notification rejetée par le filtre de pertinence (Score: " + priorityScore + " / Seuil requis: 3).");[cite: 1]
                    return;
                }

                Log.i(TAG, "Notification validée par le pipeline (Score: " + priorityScore + "). Génération de la matrice d'impact...");

                // Étape 2 : Soumission au System Prompt immuable pour générer la matrice financière complète
                String matrixResponse = queryGroqAPI(finalContent, SYSTEM_PROMPT);
                
                if (matrixResponse != null && !matrixResponse.isEmpty()) {
                    // Étape 3 : Routage et diffusion de la matrice vers les canaux d'exécution (Telegram)
                    sendTelegramMessage(matrixResponse);
                    
                    // Étape 4 : Archivage persistant de l'événement en base de données pour la compilation du rapport mensuel
                    eventDb.insertEvent(finalSource, finalContent, matrixResponse, priorityScore);
                    Log.i(TAG, "Matrice d'impact diffusée et archivée avec succès.");
                }

            } catch (Exception e) {
                Log.e(TAG, "Erreur critique dans le thread d'exécution du pipeline : ", e);
            }
        });
    }

    /**
     * Nettoie les scories textuelles, les espaces superflus et uniformise les notifications.
     */
    private String sanitizeNotificationText(String rawText) {
        if (rawText == null) return "";
        // Suppression des indicateurs de texte tronqué, des retours à la ligne excessifs et des espaces multiples
        return rawText.replaceAll("\\s+", " ")
                      .replace("...", "")
                      .replace("…", "")
                      .replaceAll("(?i)read more", "")
                      .trim();
    }

    /**
     * Vérifie si un message identique a été traité récemment en comparant son empreinte MD5.
     */
    private boolean isDuplicateEvent(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hashBytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            String currentHash = sb.toString();

            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String lastHash = prefs.getString("last_notification_hash", "");
            long lastTime = prefs.getLong("last_notification_time", 0);
            long now = System.currentTimeMillis();

            // Si le contenu est identique et qu'il s'est écoulé moins de 45 secondes, c'est un doublon
            if (currentHash.equals(lastHash) && (now - lastTime < 45000L)) {
                return true;
            }

            // Mise à jour de l'empreinte de contrôle
            prefs.edit()
                 .putString("last_notification_hash", currentHash)
                 .putLong("last_notification_time", now)
                 .apply();

        } catch (Exception e) {
            Log.e(TAG, "Erreur lors du calcul de l'empreinte anti-doublon : ", e);
        }
        return false;
    }

    /**
     * Gère la communication HTTP POST synchrone avec l'API Groq (exécutée hors du thread principal).
     */
    private String queryGroqAPI(String userPrompt, String systemPrompt) {
        HttpURLConnection conn = null;
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String apiKey = prefs.getString(PREF_GROQ_KEY, "");

            if (apiKey.isEmpty()) {
                Log.e(TAG, "Clé API Groq manquante dans les SharedPreferences. Requête annulée.");
                return null;
            }

            URL url = new URL(GROQ_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setDoOutput(true);

            // Construction du payload JSON conforme à l'API OpenAI/Groq
            JSONObject jsonBody = new JSONObject();
            jsonBody.put("model", GROQ_MODEL);
            jsonBody.put("temperature", 0.1); // Faible température pour garantir le respect strict des contraintes dures

            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
            messages.put(new JSONObject().put("role", "user").put("content", userPrompt));
            jsonBody.put("messages", messages);

            // Envoi des données
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonBody.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }
                    
                    // Extraction du texte de la réponse JSON
                    JSONObject jsonResponse = new JSONObject(response.toString());
                    return jsonResponse.getJSONArray("choices")
                                       .getJSONObject(0)
                                       .getJSONObject("message")
                                       .getString("content")
                                       .trim();
                }
            } else {
                Log.e(TAG, "Erreur API Groq : Code HTTP " + responseCode);
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception lors de la connexion à l'API Groq : ", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    /**
     * Distribue la matrice d'impact générée vers le canal Telegram via l'API Bot.
     */
    private void sendTelegramMessage(String message) {
        HttpURLConnection conn = null;
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String token = prefs.getString(PREF_TG_TOKEN, "");
            String chatId = prefs.getString(PREF_TG_CHAT_ID, "");

            if (token.isEmpty() || chatId.isEmpty()) {
                Log.e(TAG, "Identifiants Telegram manquants dans les SharedPreferences. Envoi impossible.");
                return;
            }

            String tgUrl = "https://api.telegram.org/bot" + token + "/sendMessage";
            URL url = new URL(tgUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);

            JSONObject jsonBody = new JSONObject();
            jsonBody.put("chat_id", chatId);
            jsonBody.put("text", message);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonBody.toString().getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "Erreur API Telegram : Code HTTP " + responseCode);
            } else {
                Log.d(TAG, "Message transmis avec succès sur Telegram.");
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception lors de l'envoi du message Telegram : ", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
