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

    // Constantes centralisées pour toutes les clés SharedPreferences
    private static final String PREF_GROQ_KEY   = "groq_key";
    private static final String PREF_TG_TOKEN   = "tg_token";
    private static final String PREF_TG_CHAT_ID = "tg_chat_id";
    private static final String PREF_MACRO_KEY  = "macro_api_key";
    private static final String PREFS_NAME      = "TradingBot";
    private static final long GLOBAL_THROTTLE_MS = 8 * 60 * 1000L;   // 8 minutes
    private static final long GEO_THROTTLE_MS   = 12 * 60 * 1000L;  // 12 minutes pour géo
    private volatile long lastAnalysisTime = 0;
    private volatile long lastGeoTime = 0;

    /** Raccourci centralisé pour lire la clé Groq depuis les SharedPreferences. */
    private String getGroqApiKey() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_GROQ_KEY, "");
    }

    private final ExecutorService exec = Executors.newFixedThreadPool(5);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private EventDatabase eventDb;
    private volatile boolean isSyncing = false;

    private long lastSpeechTime = 0;
    private String lastSpeaker = "";
    private static final String SYSTEM_PROMPT =
        "Tu es le Directeur de la Recherche Macroéconomique d'un Hedge Fund Quantitatif.\n" +
        "Tu analyses le flux d'actualité en appliquant une HIERARCHIE STRICTE DES DRIVERS.\n\n" +

        "MATRICE DE DOMINANCE (Priorité absolue) :\n" +
        "1. RANG SUPRÊME    : Politique Monétaire, Nominations Banques Centrales, CPI/PCE, NFP/Emploi.\n" +
        "2. RANG SECONDAIRE : PIB/GDP, PMI, ISM, Ventes au détail, Stocks EIA, Stimulus Fiscal / Dépenses Publiques.\n" +
        "3. RANG TACTIQUE   : Géopolitique (GÉO), Sentiment consommateurs (Michigan, Conference Board), Rumeurs de marché.\n\n" +

        "RÈGLE ANTI-BRUIT (TRÈS IMPORTANTE) :\n" +
        "- Les déclarations de Trump sur l'Iran, Israël ou sanctions sans action militaire concrète (raid, frappe, missile, embargo officiel, blocage Hormuz) ont un impact limité.\n" +
        "- Ne transforme JAMAIS une simple déclaration diplomatique ou répétition de news en choc majeur.\n" +
        "- Un événement Géo doit comporter une action concrète ou une mesure officielle forte pour justifier un impact élevé.\n\n" +

        "RÈGLE DE CONTRADICTION TEMPORELLE :\n" +
        "Si l'historique récent (moins de 30 min) montre un flux inverse :\n" +
        "- Un driver de RANG SUPÉRIEUR ANNULE ET REMPLACE le sentiment précédent.\n" +
        "- Un RANG TACTIQUE (GÉO, Sentiment) ne peut JAMAIS annuler un RANG SUPRÊME (CPI, NFP, Fed).\n" +
        "- En cas de coexistence impossible, signale les deux drivers sans forcer l'arbitrage.\n\n" +

        "════════════════════════════════════════════════════════\n" +
        " RÈGLES DE DIRECTIONNALITÉ INTER-MARCHÉS — EXHAUSTIVES\n" +
        "════════════════════════════════════════════════════════\n\n" +

        "A. NEWS ÉTATS-UNIS — POLITIQUE MONÉTAIRE / CPI / NFP\n" +
        "───────────────────────────────────────────────────────\n" +
        "   HAWKISH US (CPI > prévisions, NFP fort, Fed hawkish, nomination hawkish) :\n" +
        "   • 📈 US10Y   : ACHAT CHOC 🟢  | Rendements montent avec les anticipations de hausse\n" +
        "   • 🇨🇦 USDCAD : ACHAT CHOC 🟢  | Dollar fort face au CAD\n" +
        "   • 🇯🇵 USDJPY : ACHAT CHOC 🟢  | Dollar fort face au Yen ← TOUJOURS ACHAT sur HAWKISH US\n" +
        "   • 🏆 GOLD    : VENTE CHOC 🔴  | Dollar fort pénalise l'or\n" +
        "   • 💻 NASDAQ  : VENTE CHOC 🔴  | Taux hauts compressent les valorisations tech\n" +
        "   • 📊 SP500   : VENTE CHOC 🔴  | Même direction que NASDAQ — obligatoire\n" +
        "   • ₿ BITCOIN  : VENTE CHOC 🔴  | Actif risk-on pénalisé par le resserrement\n" +
        "   • 🇪🇺 EURUSD : VENTE CHOC 🔴  | Dollar fort écrase l'Euro\n" +
        "   • 🇬🇧 GBPUSD : VENTE CHOC 🔴  | Dollar fort écrase la Livre\n" +
        "   • 🇦🇺 AUDUSD : VENTE CHOC 🔴  | Devise risk-on pénalisée\n" +
        "   • 🛢️ USOIL   : NEUTRE          | Pas d'impact direct sauf si contexte GÉO simultané\n" +
        "   🏁 FLUX DOMINANT OBLIGATOIRE : DOLLAR FORT (MKT RISK-OFF) 🐻\n\n" +

        "   DOVISH US (CPI < prévisions, NFP faible, Fed dovish, anticipation de baisses de taux) :\n" +
        "   • 📈 US10Y   : VENTE CHOC 🔴  | Rendements baissent avec les anticipations de baisse\n" +
        "   • 🇨🇦 USDCAD : VENTE CHOC 🔴  | Dollar faible face au CAD\n" +
        "   • 🇯🇵 USDJPY : VENTE CHOC 🔴  | Dollar faible face au Yen ← TOUJOURS VENTE sur DOVISH US\n" +
        "   • 🏆 GOLD    : ACHAT CHOC 🟢  | Dollar faible propulse l'or\n" +
        "   • 💻 NASDAQ  : ACHAT CHOC 🟢  | Taux bas soutiennent les valorisations tech\n" +
        "   • 📊 SP500   : ACHAT CHOC 🟢  | Même direction que NASDAQ — obligatoire\n" +
        "   • ₿ BITCOIN  : ACHAT CHOC 🟢  | Liquidité favorable aux actifs risk-on\n" +
        "   • 🇪🇺 EURUSD : ACHAT CHOC 🟢  | Dollar faible renforce l'Euro\n" +
        "   • 🇬🇧 GBPUSD : ACHAT CHOC 🟢  | Dollar faible renforce la Livre\n" +
        "   • 🇦🇺 AUDUSD : ACHAT CHOC 🟢  | Devise risk-on bénéficie du Dollar faible\n" +
        "   • 🛢️ USOIL   : NEUTRE          | Pas d'impact direct sauf contexte GÉO simultané\n" +
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
        "   • 📈 US10Y   : NEUTRE          | Pas de signal monétaire direct\n" +
        "   • 🇯🇵 USDJPY : NEUTRE          | Pas de choc suffisant pour déplacer le Yen\n" +
        "   • 🛢️ USOIL   : VENTE CHOC 🔴  | Demande anticipée en baisse\n" +
        "   • ₿ BITCOIN  : VENTE CHOC 🔴  | Actif risk-on pénalisé\n" +
        "   • 🇪🇺 EURUSD, 🇬🇧 GBPUSD, 🇦🇺 AUDUSD, 🇨🇦 USDCAD : NEUTRE\n" +
        "   🏁 FLUX DOMINANT : RISK-OFF MODÉRÉ (MKT INCERTAIN) ⚠️\n\n" +
        "   Sentiment HAUT (> prévisions) → Signal HAWKISH modéré :\n" +
        "   Inverser toutes les directions ci-dessus.\n" +
        "   🏁 FLUX DOMINANT : RISK-ON MODÉRÉ (MKT CONFIANT) 🐂\n\n" +

        "C. NEWS BANQUES CENTRALES ÉTRANGÈRES (BoJ, BCE, BoE, RBA, BoC)\n" +
        "────────────────────────────────────────────────────────────────\n" +
        "   Règle fondamentale : la devise locale réagit à SA propre banque centrale.\n" +
        "   💻 NASDAQ, 📊 SP500 et 📈 US10Y restent NEUTRES sauf choc systémique mondial.\n" +
        "   ₿ BITCOIN reste NEUTRE sur les décisions de banques centrales étrangères.\n\n" +
        "   DOVISH local → devise locale s'affaiblit face au Dollar :\n" +
        "   • 🇨🇦 BoC/CPI Canada DOVISH     → 🇨🇦 USDCAD : ACHAT CHOC 🟢 | 🛢️ USOIL : VENTE CHOC 🔴\n" +
        "   • 🇯🇵 BoJ/CPI Japon DOVISH      → 🇯🇵 USDJPY : ACHAT CHOC 🟢 | 🏆 GOLD : NEUTRE\n" +
        "   • 🇪🇺 BCE/CPI Europe DOVISH     → 🇪🇺 EURUSD : VENTE CHOC 🔴\n" +
        "   • 🇬🇧 BoE/CPI UK DOVISH         → 🇬🇧 GBPUSD : VENTE CHOC 🔴\n" +
        "   • 🇦🇺 RBA/CPI Australie DOVISH → 🇦🇺 AUDUSD : VENTE CHOC 🔴 | 🛢️ USOIL : VENTE CHOC 🔴\n\n" +
        "   HAWKISH local → devise locale se renforce face au Dollar :\n" +
        "   • 🇨🇦 BoC/CPI Canada HAWKISH    → 🇨🇦 USDCAD : VENTE CHOC 🔴 | 🛢️ USOIL : ACHAT CHOC 🟢\n" +
        "   • 🇯🇵 BoJ/CPI Japon HAWKISH     → 🇯🇵 USDJPY : VENTE CHOC 🔴 | 🏆 GOLD : NEUTRE\n" +
        "   • 🇪🇺 BCE/CPI Europe HAWKISH    → 🇪🇺 EURUSD : ACHAT CHOC 🟢\n" +
        "   • 🇬🇧 BoE/CPI UK HAWKISH        → 🇬🇧 GBPUSD : ACHAT CHOC 🟢\n" +
        "   • 🇦🇺 RBA/CPI Australie HAWKISH → 🇦🇺 AUDUSD : ACHAT CHOC 🟢 | 🛢️ USOIL : ACHAT CHOC 🟢\n\n" +
        "   NOTE CANADA/PÉTROLE : Le CAD et USOIL sont corrélés. BoC HAWKISH = économie forte\n" +
        "   = demande pétrolière = USOIL ACHAT. BoC DOVISH = économie faible = USOIL VENTE.\n\n" +

        "D. GÉO — STIMULUS MILITAIRE / DÉPENSES DE DÉFENSE EUROPÉENNES (OTAN, 2% PIB)\n" +
        "─────────────────────────────────────────────────────────────────────────────\n" +
        "   VECTEUR = LIQUIDITÉ. C'est un stimulus fiscal localisé (relance budgétaire).\n" +
        "   • 🇪🇺 EURUSD : ACHAT CHOC 🟢  | Soutien budgétaire et relance de l'économie européenne\n" +
        "   • 🇬🇧 GBPUSD : ACHAT CHOC 🟢  | Alignement stratégique des dépenses de l'OTAN / UK\n" +
        "   • 🛢️ USOIL   : ACHAT CHOC 🟢  | Augmentation mécanique de la demande d'énergie militaire\n" +
        "   • 🇯🇵 USDJPY : VENTE CHOC 🔴  | Le Yen s'apprécie comme actif refuge face aux incertitudes\n" +
        "   • 💻 NASDAQ  : VENTE CHOC 🔴  | Crainte d'inflation par creusement du déficit budgétaire public\n" +
        "   • 📊 SP500   : VENTE CHOC 🔴  | Même direction que NASDAQ — obligatoire\n" +
        "   • 📈 US10Y   : NEUTRE\n" +
        "   • 🏆 GOLD    : NEUTRE\n" +
        "   • ₿ BITCOIN  : VENTE CHOC 🔴  | Risk-off immédiat sur les actifs spéculatifs\n" +
        "   • 🇨🇦 USDCAD : NEUTRE\n" +
        "   • 🇦🇺 AUDUSD : NEUTRE\n" +
        "   🏁 FLUX DOMINANT OBLIGATOIRE : EURO FORT (MKT RISK-OFF) 🐻\n\n" +

        "E. GÉO — CONFLITS / PANIQUE / MOYEN-ORIENT / CHINE\n" +
        "────────────────────────────────────────────────────\n" +
        "   VECTEUR = GÉO. RISK-OFF classique, fuite vers les refuges.\n" +
        "   • 🏆 GOLD    : ACHAT CHOC 🟢  | Refuge universel absolu\n" +
        "   • 🇯🇵 USDJPY : VENTE CHOC 🔴  | Le Yen s'apprécie comme refuge supérieur au dollar (le graphique baisse)\n" +
        "   • 🛢️ USOIL   : ACHAT CHOC 🟢  | Si Moyen-Orient / Détroit d'Ormuz impliqué (menace sur l'offre)\n" +
        "                  NEUTRE          | Si conflit local sans aucun impact sur les routes pétrolières\n" +
        "   • 🇦🇺 AUDUSD : VENTE CHOC 🔴  | Devise risk-on fortement pénalisée en RISK-OFF\n" +
        "   • 🇨🇦 USDCAD : RÈGLE DOUBLE ─ Si USOIL est en ACHAT CHOC 🟢 (pétrole monte) → USDCAD = NEUTRE (forces opposées qui s'annulent : USD fort vs CAD soutenu par pétrole). Si USOIL est NEUTRE → USDCAD : ACHAT CHOC 🟢. Mentionner obligatoirement la divergence dans le FAIT MARQUANT.\n" +
        "   • 🇪🇺 EURUSD : VENTE CHOC 🔴  | L'Euro subit le choc de l'instabilité internationale\n" +
        "   • 🇬🇧 GBPUSD : VENTE CHOC 🔴  | La Livre subit la baisse générale de l'aversion au risque\n" +
        "   • 💻 NASDAQ  : VENTE CHOC 🔴  | Les marchés actions capitulent face à l'incertitude\n" +
        "   • 📊 SP500   : VENTE CHOC 🔴  | Même direction que NASDAQ — obligatoire\n" +
        "   • ₿ BITCOIN  : VENTE CHOC 🔴  | Retrait immédiat des capitaux des actifs spéculatifs\n" +
        "   • 📈 US10Y   : ACHAT CHOC 🟢  | Ruée vers la sécurité des bons du Trésor américains\n" +
        "   🏁 FLUX DOMINANT OBLIGATOIRE : YEN FORT / OR FORT (MKT RISK-OFF) 🐻\n\n" +

        "F. STOCKS PÉTROLE EIA / OPEC\n" +
        "─────────────────────────────\n" +
        "   Rang SECONDAIRE. Impact principal sur USOIL et CAD.\n" +
        "   Stocks EIA > prévisions (surplus) → offre excédentaire :\n" +
        "   • 🛢️ USOIL   : VENTE CHOC 🔴\n" +
        "   • 🇨🇦 USDCAD : ACHAT CHOC 🟢  | Le CAD s'affaiblit en corrélation directe avec la chute du brut\n" +
        "   • Tous les autres actifs : NEUTRE\n" +
        "   Stocks EIA < prévisions (déficit) → tension sur l'offre :\n" +
        "   • 🛢️ USOIL   : ACHAT CHOC 🟢\n" +
        "   • 🇨🇦 USDCAD : VENTE CHOC 🔴  | Le CAD se renforce en même temps que le pétrole grimpe\n" +
        "   • Tous les autres actifs : NEUTRE\n\n" +

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
        "   - Si 🇯🇵 USDJPY est en VENTE CHOC 🔴 → Le FLUX DOMINANT a l'interdiction formelle d'écrire 'DOLLAR FORT'.\n" +
        "   - Si 🇯🇵 USDJPY est en ACHAT CHOC 🟢 → Le FLUX DOMINANT a l'interdiction formelle d'écrire 'YEN FORT'.\n\n" +
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
        "   sans aucune exception, même s'ils reçoivent la mention NEUTRE.\n" +
        "</HARD_CONSTRAINTS>\n\n" +

        "FORMAT DE SORTIE STRICT ET OBLIGATOIRE :\n" +
        "🚨 [NOM DE L'EMETTEUR OU SOURCE]\n" +
        "📊 CONVICTION : [JAUGE_EMOJIS] XX%\n" +
        "🎯 VECTEUR CIBLE : [HAWKISH / DOVISH / GÉO / LIQUIDITÉ]\n" +
        "📢 FAIT MARQUANT : [Analyse pro de la situation en français. Mentionner l'arbitrage si écrasement d'un driver récent.]\n\n" +
        "--- IMPACTS ACQUISITION ---\n" +
        "• 📈 US10Y   : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE] | [raison succincte]\n" +
        "• 💻 NASDAQ  : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE] | [raison succincte]\n" +
        "• 📊 SP500   : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE] | [raison succincte]\n" +
        "• 🏆 GOLD    : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE] | [raison succincte]\n" +
        "• 🛢️ USOIL   : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE] | [raison succincte]\n" +
        "• 🇪🇺 EURUSD : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE] | [raison succincte]\n" +
        "• 🇯🇵 USDJPY : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE] | [raison succincte]\n" +
        "• 🇨🇦 USDCAD : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE] | [raison succincte]\n" +
        "• 🇬🇧 GBPUSD : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE] | [raison succincte]\n" +
        "• 🇦🇺 AUDUSD : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE] | [raison succincte]\n" +
        "• ₿ BITCOIN  : [ACHAT CHOC 🟢 / VENTE CHOC 🔴 / NEUTRE] | [raison succincte]\n\n" +
        "🏁 FLUX DOMINANT : [Chaîne de caractères exacte issue des règles de directionnalité]";

    public static void sendTelegramSecure(String message, Context context) {
        new Thread(() -> {
            try {
                android.content.SharedPreferences prefs = context.getSharedPreferences("TradingBot", Context.MODE_PRIVATE);
                String token  = prefs.getString("tg_token", "");
                String chatId = prefs.getString("tg_chat_id", "");

                if (token.isEmpty() || chatId.isEmpty()) return;

                URL url = new URL("https://api.telegram.org/bot" + token + "/sendMessage");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
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
                conn.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Échec Telegram POST", e);
            }
        }).start();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        eventDb = EventDatabase.getInstance(this);
        EventValidator.preloadCalendar(); 
        createNotificationChannel();
        startDailyBriefScheduler();
        startMonthlyReportScheduler();
        registerNetworkCallback();
        // Nettoyage périodique toutes les 30 minutes
        scheduler.scheduleAtFixedRate(EventValidator::cleanupOldFingerprints, 
                                     30, 30, TimeUnit.MINUTES);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean("bot_active", false)) return;

        Bundle extras = sbn.getNotification().extras;
        String title = extras.getString(Notification.EXTRA_TITLE, "");
        String text = extras.getString(Notification.EXTRA_TEXT, "");
        String unifiedFeed = (title + " " + text).trim();

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

        processIncomingMacroFeed(sourceName, title, text, unifiedFeed, packageName, sbn.getPostTime());
    }

    private void processIncomingMacroFeed(String source, String title, String text, String feed, String pkg, long postTime) {
        List<String> targetAssets = filterActiveAssets(feed);

        // ── Validation institutionnelle (calendrier + géopolitique + anti-rumeur) ──────
        // CORRECTION : qualification complète EventValidator.ValidationResult (classe interne)
        // EventValidator enrichit targetAssets si un événement géo ou calendrier est détecté.
        // Si isConfirmed = false → rejet silencieux, rien n'est sauvegardé ni envoyé.
        EventValidator.ValidationResult vr = EventValidator.validate(title, feed, postTime, targetAssets);

        boolean isFomcPivot = feed.toUpperCase().contains("FOMC") || feed.toUpperCase().contains("FED ");
        int weight = assignDriverWeight(feed);

        // Élévation du poids pour les événements géopolitiques confirmés à haute conviction
        if (vr.isConfirmed && !vr.geoContext.isEmpty() && vr.confidence >= 80) {
            weight = Math.max(weight, 4); // Géo fort impact → traité comme driver secondaire minimum
        }

        String hash = generateSecureHash(title + text);

        // Filtre pré-validation : bruit faible non confirmé → sauvegarde silencieuse sans traitement
        if (!vr.isConfirmed && weight < 4 && !isFomcPivot && !detectDriverDeviation(feed)) {
            eventDb.saveEvent(hash, pkg, source, "Soft-Data", title, feed,
                    String.join(", ", targetAssets), "Conforme (Filtré)", (int)(postTime/1000), "synced", weight);
            return;
        }

        // Rejet définitif si le validator a explicitement refusé (rumeur, éditorial)
        if (!vr.isConfirmed && weight < 4) return;
        // ── Détection et classification de l'événement ──────────────
        EconomicEventDetector.DetectedEvent detected = EconomicEventDetector.detectEvent(title, feed);

        // Construction du label d'impact enrichi :
        // Priorité : Géo (EventValidator) > FOMC Pivot > EconomicEventDetector > Générique
        String initialImpact;
        if (!vr.geoContext.isEmpty()) {
            initialImpact = "🌍 CHOC GÉOPOLITIQUE [" + vr.geoContext + "] — Conviction: " + vr.confidence + "% | " + detected.impact;
        } else if (isFomcPivot) {
            initialImpact = "💥 PIVOT MAJEUR BANQUE CENTRALE | " + detected.description + " | " + detected.impact;
        } else {
            initialImpact = "⚡ [" + detected.eventType + "] " + detected.description + " | " + detected.impact + " (Poids: " + weight + ")";
        }

        // =========================================================================
        // SÉCURITÉ : Filtrer le neutre UNIQUEMENT s'il ne s'agit ni de GÉO ni de FOMC
        // =========================================================================
        if (vr.geoContext.isEmpty() && !isFomcPivot) {
            if (detected.impact != null && (detected.impact.equalsIgnoreCase("Neutre") || detected.impact.toUpperCase().contains("NEUTRE"))) {
                Log.d(TAG, "Événement filtré (Bruit Neutre standard). Annulation de la base et de l'envoi Groq/Telegram.");
                return; // Rejet silencieux total
            }
        }
        // =========================================================================

        boolean saved = eventDb.saveEvent(hash, pkg, source, "Macro-Choc", title, feed,
                String.join(", ", targetAssets), initialImpact, (int)(postTime/1000), "en_attente", weight);
        if (saved && isDeviceOnline()) {
            triggerQueueSynchronization();
        }
    }

    private int assignDriverWeight(String text) {
        String u = text.toUpperCase();
        if (u.contains("CPI")            || u.contains("INFLATION")       || u.contains("NFP")          ||
            u.contains("NON-FARM PAYROLLS") || u.contains("FOMC")           || u.contains("INTEREST RATE") ||
            u.contains("RBA")            || u.contains("BOC")             || u.contains("BOJ")           ||
            u.contains("BOE")            || u.contains("ECB")             || u.contains("BCE")           ||
            u.contains("LAGARDE")        || u.contains("BAILEY")          || u.contains("MACKLEM")       ||
            u.contains("BULLOCK")        || u.contains("UEDA")            ||
            u.contains("WARSH")          || u.contains("POWELL")) return 5;
        if (u.contains("GDP")                || u.contains("PIB")                   ||
            u.contains("RETAIL SALES")       || u.contains("EMPLOYMENT RATE")       ||
            u.contains("STOCKS")             || u.contains("JOBLESS")               ||
            u.contains("ADP")                || u.contains("JOLTS")                 ||
            u.contains("JOB OPENINGS")       || u.contains("PPI")                   ||
            u.contains("PRODUCER PRICE")     || u.contains("DURABLE GOODS")         ||
            u.contains("TRADE BALANCE")      || u.contains("CURRENT ACCOUNT")       ||
            u.contains("INDUSTRIAL PRODUCTION") || u.contains("CAPACITY UTILIZATION") ||
            u.contains("PHILLY FED")         || u.contains("EMPIRE STATE")          ||
            u.contains("CHICAGO PMI")        || u.contains("BEIGE BOOK")            ||
            u.contains("PERSONAL SPENDING")  || u.contains("PERSONAL INCOME")       ||
            u.contains("HOUSING STARTS")     || u.contains("BUILDING PERMITS")      ||
            u.contains("HOME SALES")         || u.contains("CHALLENGER")) return 4;
        if (u.contains("PMI")               || u.contains("ISM")                  ||
            u.contains("MICHIGAN")          || u.contains("CONSUMER CONFIDENCE")   ||
            u.contains("CONSUMER SENTIMENT")|| u.contains("IMPORT PRICE")          ||
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

    private void fetchMissingDataFromInstitutionalAPI() {
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
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
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
            // CORRECTION 3 : Fermeture de la connexion dans fetchMissingDataFromInstitutionalAPI
            conn.disconnect();
        } catch (Exception e) { Log.e(TAG, "Échec de récupération API historique", e); }
    }

    // CORRECTION 5 : Renommage de dispatchWeeklyBulkToGroq → dispatchHistoricalBulkToGroq (nom plus fidèle)
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

                eventDb.saveEvent(generateSecureHash(analysis), "com.tradingbot.sync", "API Sync", "Weekly-Sync", "Audit Global", analysis, "ALL_ASSETS", "ALIGNE_OK", (int)(System.currentTimeMillis()/1000), "synced", 5);
            }
        } catch (Exception e) { Log.e(TAG, "Échec dispatch historique Groq", e); }
        finally {
            // CORRECTION 3 : Fermeture garantie dans le bloc finally
            if (conn != null) conn.disconnect();
        }
    }

        private boolean executeAnalysisPipeline(String source, String feed, String history, 
                                            List<String> assets, long ts, String fingerprint) {
        
        int maxRetries = 3;
        int attempt = 0;

        // Lecture des clés une seule fois, avant la boucle retry
        String apiKey = getGroqApiKey();
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String tgToken  = prefs.getString(PREF_TG_TOKEN, "");
        String tgChatId = prefs.getString(PREF_TG_CHAT_ID, "");
        if (apiKey.isEmpty() || tgToken.isEmpty() || tgChatId.isEmpty()) return false;

        long now = System.currentTimeMillis();
        // ── THROTTLE GLOBAL + GÉO ─────────────────────────────
        if (now - lastAnalysisTime < GLOBAL_THROTTLE_MS) {
            eventDb.markEventAsSynced(fingerprint, "THROTTLED_GLOBAL");
            Log.d(TAG, "[THROTTLE] Analyse bloquée (global - 8 min)");
            return true;
        }

        boolean isGeoEvent = feed.toUpperCase(Locale.ROOT).contains("MOYEN-ORIENT") || 
                            feed.toUpperCase(Locale.ROOT).contains("IRAN") || 
                            feed.toUpperCase(Locale.ROOT).contains("ISRAEL") ||
                            feed.toUpperCase(Locale.ROOT).contains("GÉO");

        if (isGeoEvent && (now - lastGeoTime < GEO_THROTTLE_MS)) {
            eventDb.markEventAsSynced(fingerprint, "THROTTLED_GEO");
            Log.d(TAG, "[THROTTLE] Événement Géo bloqué (12 min)");
            return true;
        }

        while (attempt < maxRetries) {
            HttpURLConnection conn = null;
            try {

                SimpleDateFormat sdf = new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE);
                sdf.setTimeZone(TimeZone.getTimeZone("GMT+3"));
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

                    String aiResult = new JSONObject(r.toString()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
                    
                    if (aiResult.isEmpty() || aiResult.length() < 50) {
                        throw new Exception("Invalid API response");
                    }

                    // ─────────────────────────────────────────────────────────────
                    // FILTRAGE : Neutres gardés pour Groq, seulement signaux forts pour Telegram
                    // ─────────────────────────────────────────────────────────────
                    StringBuilder filteredMessage = new StringBuilder();
                    String[] lines = aiResult.split("\n");
                    int activeSignalsCount = 0;

                    for (String line : lines) {
                        String trimmed = line.trim();
                        if (trimmed.isEmpty()) continue;

                        // Métadonnées importantes
                        if (trimmed.startsWith("🚨") || 
                            trimmed.startsWith("📊") || 
                            trimmed.startsWith("🎯") || 
                            trimmed.startsWith("📢") || 
                            trimmed.startsWith("🏁") ||
                            trimmed.startsWith("--- IMPACTS")) {
                            filteredMessage.append(line).append("\n");
                            continue;
                        }

                        // Seulement les lignes avec ACHAT ou VENTE
                        if (trimmed.contains("•") && 
                           (line.contains("ACHAT CHOC") || line.contains("VENTE CHOC"))) {
                            
                            // Corrections de cohérence (USDJPY / USDCAD)
                            String processedLine = line;
                            if (line.contains("🇯🇵 USDJPY") && line.contains("ACHAT CHOC") &&
                               (line.contains("renforcer le yen") || (line.contains("yen") && line.contains("refuge")) || line.contains("s'apprécier"))) {
                                processedLine = line.replace("ACHAT CHOC 🟢", "VENTE CHOC 🔴");
                            }
                            if (line.contains("🇯🇵 USDJPY") && line.contains("VENTE CHOC") &&
                               (line.contains("faiblir le yen") || line.contains("Yen japonais pourrait faiblir"))) {
                                processedLine = line.replace("VENTE CHOC 🔴", "ACHAT CHOC 🟢");
                            }
                            if (line.contains("🇨🇦 USDCAD") && line.contains("VENTE CHOC") &&
                               (line.contains("renforcer le CAD") || line.contains("s'apprécier"))) {
                                processedLine = line.replace("VENTE CHOC 🔴", "ACHAT CHOC 🟢");
                            }

                            filteredMessage.append(processedLine).append("\n");
                            activeSignalsCount++;
                        }
                    }

                    if (activeSignalsCount > 0) {
                        String finalPayload = "⚡ *ANALYSE DRIVER MACRO EXPLICATIVE*\n"
                                + "🕒 " + timeString + " (Mada)\n"
                                + "📡 Source : " + source + "\n"
                                + filteredMessage.toString().trim();

                        // Protection anti-spam : message trop court
                        if (finalPayload.length() < 200) {
                            eventDb.markEventAsSynced(fingerprint, "TOO_SHORT");
                            return true;
                        }

                        sendTelegramSecure(finalPayload, this);

                        // Mise à jour des throttles
                        lastAnalysisTime = now;
                        if (isGeoEvent) {
                            lastGeoTime = now;
                        }
                    } else {
                        eventDb.markEventAsSynced(fingerprint, "FILTERED_ALL_NEUTRAL");
                    }

                    eventDb.markEventAsSynced(fingerprint, "PROCESSED_OK");
                    return true;

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

        // ── USD / Fed — impact global systémique ─────────────────────
        // Warsh, Powell et membres Fed → tous les actifs majeurs impactés
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

        // ── Or / Métaux précieux ──────────────────────────────────────
        if (upper.contains("GOLD")   || upper.contains("XAU")    ||
            upper.contains("OR ")    || upper.contains("SILVER")) assets.add("GOLD");

        // ── Pétrole ───────────────────────────────────────────────────
        if (upper.contains("OIL")    || upper.contains("WTI")    ||
            upper.contains("CRUDE")  || upper.contains("BRENT")) assets.add("USOIL");

        // ── Indices US ────────────────────────────────────────────────
        if (upper.contains("NASDAQ") || upper.contains("NAS100") ||
            upper.contains("TECH")   || upper.contains("OPENAI") ||
            upper.contains("NVIDIA") || upper.contains("APPLE")) assets.add("NASDAQ");

        if (upper.contains("SP500")  || upper.contains("S&P")    ||
            upper.contains("SPX")) assets.add("SP500");

        // ── Crypto ────────────────────────────────────────────────────
        if (upper.contains("BITCOIN") || upper.contains("BTC")   ||
            upper.contains("CRYPTO")) assets.add("BITCOIN");

        // ── Obligations US ────────────────────────────────────────────
        if (upper.contains("YIELD")  || upper.contains("US10Y")  ||
            upper.contains("BOND")   || upper.contains("TREASURY")) assets.add("US10Y");

        // ── EURUSD ────────────────────────────────────────────────────
        if (upper.contains("EURUSD")   || upper.contains("ECB")       ||
            upper.contains("EUROZONE") || upper.contains("LAGARDE")   ||
            upper.contains("BCE")      || upper.contains("FRANKFURT") ||
            upper.matches(".*\\bEUR\\b.*")) assets.add("EURUSD");

        // ── GBPUSD ────────────────────────────────────────────────────
        if (upper.contains("GBP")    || upper.contains("GBPUSD") ||
            upper.contains("CABLE")  || upper.contains("BOE")    ||
            upper.contains("BAILEY")) assets.add("GBPUSD");

        // ── AUDUSD ────────────────────────────────────────────────────
        if (upper.contains("AUD")    || upper.contains("AUDUSD") ||
            upper.contains("AUSSIE") || upper.contains("RBA")    ||
            upper.contains("BULLOCK")) assets.add("AUDUSD");

        // ── USDCAD ────────────────────────────────────────────────────
        if (upper.contains("CAD")    || upper.contains("USDCAD") ||
            upper.contains("LOONIE") || upper.contains("BOC")    ||
            upper.contains("MACKLEM")) assets.add("USDCAD");

        // ── USDJPY ────────────────────────────────────────────────────
        if (upper.contains("JPY")    || upper.contains("USDJPY") ||
            upper.contains("YEN")    || upper.contains("BOJ")    ||
            upper.contains("UEDA")) assets.add("USDJPY");

        // ── Fallback : si aucun actif détecté → actifs majeurs par défaut ──
        if (assets.isEmpty()) {
            assets.addAll(Arrays.asList(
                "GOLD", "NASDAQ", "USOIL", "EURUSD", "AUDUSD", "USDCAD", "USDJPY"
            ));
        }

        // Dédoublonnage en conservant l'ordre
        return new ArrayList<>(new LinkedHashSet<>(assets));
    }

    private void startDailyBriefScheduler() {
        Calendar nextRun = Calendar.getInstance(TimeZone.getTimeZone("GMT+3"));
        nextRun.set(Calendar.HOUR_OF_DAY, 7);
        nextRun.set(Calendar.MINUTE, 0);
        nextRun.set(Calendar.SECOND, 0);
        if (nextRun.getTimeInMillis() <= System.currentTimeMillis()) nextRun.add(Calendar.DAY_OF_YEAR, 1);
        scheduler.scheduleAtFixedRate(this::generateAndSendDailyBrief, nextRun.getTimeInMillis() - System.currentTimeMillis(), 24L * 60 * 60 * 1000, TimeUnit.MILLISECONDS);
    }

    private void generateAndSendDailyBrief() {
        HttpURLConnection conn = null;
        try {
            String apiKey = getGroqApiKey();
            if (apiKey.isEmpty()) return;

            long now = System.currentTimeMillis() / 1000;
            String dailyDrivers = eventDb.getDailyMacroDrivers(now);
            if (dailyDrivers.isEmpty()) return;

            JSONObject payload = new JSONObject();
            payload.put("model", GROQ_MODEL);
            payload.put("temperature", 0.1);

            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", "Rédige un briefing matinal synthétique des chocs macroéconomiques enregistrés la veille."));
            messages.put(new JSONObject().put("role", "user").put("content", "DONNÉES HIER :\n" + dailyDrivers));
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

                String summary = new JSONObject(r.toString()).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
                sendTelegramSecure("🌅 *DAILY BRIEF STRATÉGIQUE*\n\n" + summary, this);
            }
        } catch (Exception e) { Log.e(TAG, "Erreur Daily Brief", e); }
        finally {
            // CORRECTION 3 : Fermeture garantie dans generateAndSendDailyBrief
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
            // CORRECTION 3 : Fermeture garantie dans generateAndPurgeMonthlyReport
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
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        scheduler.shutdownNow();
        exec.shutdownNow();
        Log.d(TAG, "[SERVICE] Service arrêté");
    }
}
