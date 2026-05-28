package com.tradingbot.analyzer;

import java.util.Locale;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
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

    private String getGroqApiKey() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_GROQ_KEY, "");
    }

    private Calendar getMadaCalendar() {
      return Calendar.getInstance(TimeZone.getTimeZone("Indian/Antananarivo"));
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
    
    private static final String SYSTEM_PROMPT =
    "Tu es le Directeur de la Recherche Macroéconomique d'un Hedge Fund Quantitatif.\n" +
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
    "CONTRAINTE 11 — HIÉRARCHIE ABSOLUE DES DRIVERS :\n" +
    "   En cas de coexistence d'un driver de RANG SUPRÊME (politique monétaire, décision de banque centrale, CPI, NFP, FOMC, BCE, BoE, BoJ, RBA, BoC) et d'un driver de RANG TACTIQUE (géopolitique, tarifs douaniers, sentiment consommateur, rumeur), le driver de RANG SUPRÊME prévaut.\n" +
    "   Le driver tactique ne peut annuler, réduire ou remplacer la directionnalité imposée par le driver suprême.\n" +
    "   Exemple : une annonce BCE HAWKISH (hausse des taux) l'emporte sur un contexte géopolitique de paix avec l'Iran. Dans ce cas, EURUSD reste ACHAT CHOC, et les impacts sur les devises (GBPUSD, AUDUSD, etc.) suivent la règle C (différentiel de taux).\n" +
    "   ⚠️ Cette règle prévaut sur toute interprétation contraire.\n\n" +
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
        scheduler.scheduleAtFixedRate(this::syncCalendarAndPurge, 15, 12 * 60, TimeUnit.MINUTES);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("bot_active", false)) return;

        Bundle extras = sbn.getNotification().extras;
        String title = extras.getString(Notification.EXTRA_TITLE, "");

        // Priorité décroissante : BigText > SubText > Summary > Text
        String bigText    = extras.getString(Notification.EXTRA_BIG_TEXT, "");
        String subText    = extras.getString(Notification.EXTRA_SUB_TEXT, "");
        String summary    = extras.getString(Notification.EXTRA_SUMMARY_TEXT, "");
        String text       = extras.getString(Notification.EXTRA_TEXT, "");

        // On prend le plus long contenu disponible
        String body = bigText.length() > text.length() ? bigText : text;
        if (subText.length() > body.length())   body = subText;
        if (summary.length() > body.length())   body = summary;
        String unifiedFeed = (title + " " + body).trim();
        
        // Dégroupe les notifications groupées (bundles Investing.com)
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
        
        if (unifiedFeed.length() < 10) return;

        String packageName = sbn.getPackageName().toLowerCase();
        String sourceName = "Source Institutionnelle";

        if (packageName.equals("com.financialjuice.androidapp") || packageName.contains("financialjuice")) {
            sourceName = "FinancialJuice";
        } else if (packageName.equals("com.fusionmedia.investing") || packageName.contains("investing")) {
            sourceName = "Investing.com";
        } else if (packageName.equals("com.twitter.android") || packageName.contains("twitter") || packageName.contains("periscope")) {
            sourceName = "X / Twitter";
        } else {
            return;
        }

        String upperFeed = unifiedFeed.toUpperCase();
        long currentTime = System.currentTimeMillis();
        String currentSpeaker = "";

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

        if (!currentSpeaker.isEmpty()) {
            if (currentSpeaker.equals(lastSpeaker) && (currentTime - lastSpeechTime < 60000)) {
                Log.d(TAG, "Doublon de notification filtré (" + currentSpeaker + ") pour éviter le spam.");
                return;
            }
            lastSpeechTime = currentTime;
            lastSpeaker = currentSpeaker;
        }

        // ── PIPELINE DE FILTRAGE ET VALIDATION MACRO ──
        
        // 1. Soumission et validation temporelle face au calendrier via EventValidator
        List<String> enrichedAssets = new ArrayList<>();
        EventValidator.ValidationResult validationResult = EventValidator.validate(title, body, currentTime, enrichedAssets);

        // 2. Catégorisation et extraction du type de driver via EconomicEventDetector
        EconomicEventDetector.DetectedEvent detection = EconomicEventDetector.detectEvent(title, body);

        // Si le validateur confirme l'urgence ou la légitimité de la news, on persiste en DB
        if (validationResult.isConfirmed) {
            String fingerprint = generateSecureHash(packageName + "_" + title + "_" + body + "_" + (sbn.getPostTime() / 60000));
            
            // Conversion propre de la matrice d'actifs
            StringBuilder assetsSb = new StringBuilder();
            for (int i = 0; i < enrichedAssets.size(); i++) {
                assetsSb.append(enrichedAssets.get(i));
                if (i < enrichedAssets.size() - 1) assetsSb.append(",");
            }
            String assetsString = assetsSb.toString();

            // Calcul du poids de dominance (driver_weight) pour le filtrage SQLite
            int driverWeight = 1;
            if (detection.eventType != null) {
                if (detection.eventType.equals("FED-MONETARY-POLICY") || detection.eventType.equals("INFLATION-DATA")) {
                    driverWeight = 5;
                } else if (detection.eventType.startsWith("GEO") || detection.eventType.equals("CENTRAL-BANK-RATE") || detection.eventType.equals("EMPLOYMENT-REPORT")) {
                    driverWeight = 4;
                } else if (detection.eventType.equals("ECONOMIC-GROWTH-DATA")) {
                    driverWeight = 2;
                }
            }

            // Insertion synchronisée en base de données avec statut "pending"
            boolean saved = eventDb.saveEvent(
                    fingerprint,
                    packageName,
                    sourceName,
                    detection.eventType,
                    title,
                    body,
                    assetsString,
                    "PENDING",
                    sbn.getPostTime() / 1000,
                    "pending", // Remplacement définitif de "attente"
                    driverWeight
            );

            if (saved) {
                Log.d(TAG, "[VALIDATEUR] Événement macro validé inséré en base : " + detection.eventType + " [Poids: " + driverWeight + "]");
            }
        }

        // Envoi vers votre logique de traitement IA (Ici 'body' remplace intelligemment 'text')
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
       int[] targetHours = {8, 9, 12, 16, 17};
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
"                     FORMAT OBLIGATOIRE (STRICT)\n" +
"═══════════════════════════════════════════════════════════════\n\n" +

"📊 RAPPORT MACRO PÉRIODIQUE – [Date et heure exacte de Madagascar, ex: 27/05 16:30]\n\n" +

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
"                     RÈGLES INVIOABLES À APPLIQUER\n" +
"═══════════════════════════════════════════════════════════════\n\n" +
"1. HIÉRARCHIE DES DRIVERS :\n" +
"   - RANG SUPRÊME (politique monétaire, CPI, NFP, FOMC, BCE, BoJ, BoE, RBA, BoC) prévaut toujours sur un RANG TACTIQUE (géopolitique, tarifs, sentiment).\n" +
"   - En cas de coexistence, seul le driver suprême détermine la directionnalité des actifs ; le driver tactique ne peut annuler ou réduire cet impact.\n\n" +
"2. POUR UNE ANNONCE DE BANQUE CENTRALE ÉTRANGÈRE (BCE, BoJ, BoE, RBA, BoC) :\n" +
"   - NASDAQ, SP500, US10Y et BITCOIN sont OBLIGATOIREMENT NEUTRES (pas d'impact direct).\n" +
"   - La devise locale suit la règle : DOVISH → devise baisse (VENTE CHOC), HAWKISH → devise monte (ACHAT CHOC).\n" +
"   - Les autres devises (EUR, GBP, AUD, CAD, JPY) réagissent selon le différentiel de taux (effet dollar).\n\n" +
"3. SYMÉTRIE NASDAQ/SP500 : Les deux indices doivent avoir exactement la même direction (ACHAT CHOC ou VENTE CHOC).\n\n" +
"4. BITCOIN suit NASDAQ/SP500 avec une direction identique (amplifié).\n\n" +
"5. POUR UN ACTIF SANS IMPACT DIRECT, écrire : NEUTRE ⚪ | Pas d'impact direct (ou raison courte équivalente).\n\n" +
"6. SOYEZ ULTRA-CONCIS : aucune introduction, aucune phrase en dehors du format. Ne répétez pas le contenu du résumé.\n\n" +
"7. Le champ 🏁 FLUX DOMINANT doit être choisi UNIQUEMENT parmi : DOLLAR FORT, DOLLAR FAIBLE, RISK-ON, RISK-OFF, YEN FORT, EURO FORT, OR FORT.\n\n" +
"8. Pour extraire les drivers principaux, classez-les par ordre d'importance (Suprême > Secondaire > Tactique). Maximum 5 drivers. N'incluez pas les événements neutres.\n\n" +
"9. La probabilité d'impact doit refléter la vigueur du signal (ex: 85% pour un discours hawkish clair, 65% pour une escalade géopolitique modérée).\n\n" +
"10. Respectez impérativement les pictogrammes : 📈 US10Y, 💻 NASDAQ, 📊 SP500, 🏆 GOLD, 🛢️ USOIL, 🇪🇺 EURUSD, 🇯🇵 USDJPY, 🇨🇦 USDCAD, 🇬🇧 GBPUSD, 🇦🇺 AUDUSD, ₿ BITCOIN.\n\n" +
"11. Ne déviez jamais de ce format. Aucun blabla, aucune salutation, aucun commentaire supplémentaire.";
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

    @Override
    public void onDestroy() {
        super.onDestroy();
        scheduler.shutdownNow();
        exec.shutdownNow();
        Log.d(TAG, "[SERVICE] Service arrêté");
    }
}

