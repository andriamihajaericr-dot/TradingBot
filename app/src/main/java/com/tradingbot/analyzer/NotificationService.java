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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class NotificationService extends NotificationListenerService {

    private static final String TAG = "NotificationService";
    // ✅ Singleton
    private static NotificationService serviceInstance;
    public static NotificationService getInstance() {
        return serviceInstance;
    }
    // ✅ CORRECTIF : Variable de verrouillage globale pour l'import SQLite
    public static volatile boolean isDatabaseImportInProgress = false;
    public EventDatabase getEventDb() {
        return this.eventDb;
    }
    //private static final Map<String, Long> recentFingerprints = new ConcurrentHashMap<>();
    private static final String CHANNEL_ID = "trading_alerts"; 
    // 🛡️ CORRECTIF SURVIVANCE PROCESSUS : canal séparé, silencieux, pour la notification
    // persistante du foreground service. Sans foreground, Android tuait le processus en
    // arrière-plan et réinitialisait tout l'état en RAM (cooldowns, maps, compteurs) entre
    // deux notifications — ce qui annulait le correctif anti-spam Inertie Macro.
    private static final String FOREGROUND_CHANNEL_ID = "trading_bot_service";
    private static final int FOREGROUND_NOTIFICATION_ID = 1001;
    private static final String GROQ_MODEL = "llama-3.3-70b-versatile";
    private static final String GROQ_MODEL_FALLBACK = "llama-3.1-8b-instant";
    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    private static final String PREF_GROQ_KEY   = "groq_key";
    private static final String PREF_TG_TOKEN   = "tg_token";
    private static final String PREF_TG_CHAT_ID = "tg_chat_id";
    private static final String PREF_MACRO_KEY  = "macro_api_key";
    private static final String PREFS_NAME      = "TradingBot";
    private static final long GEO_THROTTLE_MS   = 12 * 60 * 1000L;  // 12 minutes pour géo
    private volatile long lastAnalysisTime = 0;
    private volatile long lastGeoTime = 0;
    // 🛡️ Compteur tokens Groq — protection quota TPD 100k/jour
    private static final int TOKEN_BUDGET_DAILY = 90000; // marge 10k de sécurité
    private static final int TOKEN_ESTIMATE_PER_CALL = 1500; // estimation moyenne input+output
    private static final AtomicInteger dailyTokensUsed = new AtomicInteger(0);
    private static long tokenResetTime = 0L; // minuit UTC du jour courant
    // Seuil minimal de prix pour considérer la donnée comme valide
    private static final double MIN_VALID_PRICE = 0.0;
    // Seuil de divergence (0.5% est plus sûr pour éviter le bruit sur le Forex)
    private static final double DIVERGENCE_THRESHOLD = 0.5;
    private final ConcurrentHashMap<String, PrevailingDirection> lastForecast = new ConcurrentHashMap<>();
    // Protection anti-spam : évite de scanner en boucle si le marché est instable
    private final ConcurrentHashMap<String, Long> lastAlertsSent = new ConcurrentHashMap<>();
    private static final long ALERT_COOLDOWN_MS = 60 * 60 * 1000L; // 1 heure de cooldown par actif
    private static final long INERTIA_REMINDER_COOLDOWN_MS = 60 * 60 * 1000L; // 1 rappel max par heure par type de driver
    
    private final ConcurrentHashMap<String, Long> lastInertiaReminderSentMemory = new ConcurrentHashMap<>();
        private static class PrevailingDirection {
            final String direction; // "BULLISH", "BEARISH" ou "NEUTRE"
            final double referencePrice;
            final long timestamp;
            PrevailingDirection(String dir, double price, long ts) {
                this.direction = dir;
                this.referencePrice = price;
                this.timestamp = ts;
            }
        }

    private void startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel fgChannel = new NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "TradingBot — Service actif",
                NotificationManager.IMPORTANCE_MIN // silencieux, pas d'icône clignotante
            );
            fgChannel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(fgChannel);
        }

        Notification fgNotification = new Notification.Builder(this, FOREGROUND_CHANNEL_ID)
            .setContentTitle("TradingBot actif")
            .setContentText("Surveillance macro en cours…")
            .setSmallIcon(android.R.drawable.stat_notify_sync) // ⚠️ remplace par ta propre icône si tu en as une (ex: R.drawable.ic_launcher)
            .setOngoing(true)
            .build();

        try {
           if (Build.VERSION.SDK_INT >= 34) {
                startForeground(FOREGROUND_NOTIFICATION_ID, fgNotification, 1 << 30);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(FOREGROUND_NOTIFICATION_ID, fgNotification);
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, fgNotification);
            }
            Log.d(TAG, "[SERVICE] Foreground démarré — processus protégé contre le kill système.");
        } catch (Exception e) {
            Log.e(TAG, "[SERVICE] Échec démarrage foreground — le processus reste vulnérable au kill système.", e);
        }
    }

    
    private void checkAndSendMissedWeeklyReport() {
        SharedPreferences prefs = getSharedPreferences("TradingBotPrefs", MODE_PRIVATE);
        long lastWeeklySentMs = prefs.getLong("last_weekly_sent_ms", 0L);
    
        // Trouver le dernier vendredi à 22h00 (Mada) 
        Calendar lastFriday = Calendar.getInstance(TimeZone.getTimeZone("Indian/Antananarivo"));
        lastFriday.set(Calendar.HOUR_OF_DAY, 22);
        lastFriday.set(Calendar.MINUTE, 0);
        lastFriday.set(Calendar.SECOND, 0);
        lastFriday.set(Calendar.MILLISECOND, 0);
        // Reculer jusqu'au dernier vendredi
        while (lastFriday.get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY) {
            lastFriday.add(Calendar.DAY_OF_MONTH, -1);
        }
        // Si ce vendredi est dans le futur, reculer d'une semaine
        if (lastFriday.getTimeInMillis() > System.currentTimeMillis()) {
            lastFriday.add(Calendar.DAY_OF_MONTH, -7);
        }
    
        long lastFridayMs = lastFriday.getTimeInMillis();
        boolean alreadySent = lastWeeklySentMs >= lastFridayMs;
    
        if (!alreadySent) {
            Log.d(TAG, "[WEEKLY] Rapport manqué détecté — envoi immédiat du rattrapage");
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("📅 [WEEKLY] Rapport manqué → envoi du rattrapage");
            }
            // Délai 30s pour laisser le service se stabiliser avant l'appel Groq
            scheduler.schedule(this::generateAndSendWeeklyReport, 30, TimeUnit.SECONDS);
        } else {
            Log.d(TAG, "[WEEKLY] Rapport déjà envoyé cette semaine — pas de rattrapage nécessaire");
        }
    }

    private void checkAndSendMissedMonthlyReport() {
        SharedPreferences prefs = getSharedPreferences("TradingBotPrefs", MODE_PRIVATE);
        long lastMonthlySentMs = prefs.getLong("last_monthly_sent_ms", 0L);
    
        Calendar lastEndOfMonth = Calendar.getInstance(
        TimeZone.getTimeZone("Indian/Antananarivo"));
        lastEndOfMonth.set(Calendar.HOUR_OF_DAY, 23);
        lastEndOfMonth.set(Calendar.MINUTE, 0);
        lastEndOfMonth.set(Calendar.SECOND, 0);
        lastEndOfMonth.set(Calendar.MILLISECOND, 0);
        lastEndOfMonth.set(Calendar.DAY_OF_MONTH, 1);
        lastEndOfMonth.add(Calendar.DAY_OF_MONTH, -1);
    
        long lastEndOfMonthMs = lastEndOfMonth.getTimeInMillis();
    
        if (lastMonthlySentMs < lastEndOfMonthMs
                && lastEndOfMonthMs < System.currentTimeMillis()) {
            Log.d(TAG, "[MONTHLY] Rapport mensuel manqué → rattrapage dans 60s");
            if (MainActivity.instance != null)
                MainActivity.instance.addLog(
                    "📊 [MONTHLY] Rapport manqué → rattrapage en cours");
            scheduler.schedule((Runnable) this::generateAndPurgeMonthlyReport, 60, TimeUnit.SECONDS);
        } else {
            Log.d(TAG, "[MONTHLY] Rapport mensuel déjà envoyé — pas de rattrapage");
        }
    }
    
    // Actifs dont les prix sont disponibles via WebSocket TradingView
    // (anciennement TwelveData — remplacé par TradingViewFetcher)
    public static final List<String> MARKET_PRICE_ASSETS = Arrays.asList(
    "NASDAQ", "GOLD", "USOIL", "USDJPY", "GBPUSD", "SP500");
    
    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.i(TAG, "✅ [LISTENER] Connecté — le bot reçoit bien le flux système.");
        if (MainActivity.instance != null) {
            MainActivity.instance.addLog("✅ [SYSTÈME] Accès notifications confirmé par Android.");
        }
    }
    
    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.e(TAG, "🔴 [LISTENER] DÉCONNECTÉ — plus aucune notification ne sera captée !");
        if (MainActivity.instance != null) {
            MainActivity.instance.addLog("🔴 [SYSTÈME] Accès notifications COUPÉ (vérifie Paramètres > Accès aux notifications).");
        }
    }
    // Ajoutez cette méthode à la fin de votre classe NotificationService.java
    public static String formatEventForDisplay(EconomicCalendarAPI.CalendarEvent e) {
        if (e.actual == null || e.actual.equals("N/A") || e.actual.isEmpty()) {
            return e.indicator + " | Prévu: " + e.forecast;
        }
        
        try {
            // Nettoyage des chaînes pour obtenir uniquement des nombres
            double a = Double.parseDouble(e.actual.replaceAll("[^\\d.\\-]", ""));
            double f = Double.parseDouble(e.forecast.replaceAll("[^\\d.\\-]", ""));
            
            // Logique de flèche
            String fleche = (a > f) ? "↑" : "↓";
            if (a == f) fleche = "="; // Optionnel : cas d'égalité
            
            return String.format("%s | Prévu: %s | Réel: %s %s", e.indicator, e.forecast, e.actual, fleche);
        } catch (Exception ex) {
            // En cas d'erreur de conversion, on retourne le format de base
            return e.indicator + " | Prévu: " + e.forecast + " | Réel: " + e.actual;
        }
    }
    
    private static final Map<String, String> EMOJI_ASSET_MAP = new HashMap<>();
    static {
        EMOJI_ASSET_MAP.put("💻", "NASDAQ"); EMOJI_ASSET_MAP.put("📊", "SP500");
        EMOJI_ASSET_MAP.put("🏆", "GOLD");   EMOJI_ASSET_MAP.put("🛢️", "USOIL");
        EMOJI_ASSET_MAP.put("🇯🇵", "USDJPY"); EMOJI_ASSET_MAP.put("🇬🇧", "GBPUSD");
    }

    private void captureForecastFromReport(String report) {
    if (report == null || report.isEmpty()) return;
    // 🛡️ Guard : si aucune ligne bullet dans le rapport, 
    //            aucune prévision à capturer → 0 appel réseau
    if (!report.contains("•")) return;
    try {
        // Prix depuis WebSocket TradingView — temps réel, gratuit, sans quota
Map<String, Double> currentPrices = new HashMap<>();
for (Map.Entry<String, TradingViewFetcher.TVMarketData> e :
        TradingViewFetcher.getCache().entrySet()) {
    currentPrices.put(e.getKey(), e.getValue().price);
}
    long now = System.currentTimeMillis();
    
            for (String line : report.split("\n")) {
                if (!line.startsWith("•")) continue;
                String[] parts = line.split(":");
                if (parts.length < 2) continue;
    
                // Robustesse : trim pour ignorer les espaces avant/après
                String leftPart = parts[0].trim();
                String rightPart = parts[1].trim();
    
                String asset = null;
                for (Map.Entry<String, String> entry : EMOJI_ASSET_MAP.entrySet()) {
                    if (leftPart.contains(entry.getKey())) {
                        asset = entry.getValue();
                        break;
                    }
                }
                if (asset == null) continue;
    
                String direction = "NEUTRE";
                // Vérification sur le segment nettoyé
                if (rightPart.contains("BULLISH") || rightPart.contains("ACHAT")) direction = "BULLISH";
                else if (rightPart.contains("BEARISH") || rightPart.contains("VENTE")) direction = "BEARISH";
    
                double price = currentPrices.getOrDefault(asset, 0.0);
                
                // Utilisation de la constante de validation
                if (price > MIN_VALID_PRICE) {
                    lastForecast.put(asset, new PrevailingDirection(direction, price, now));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur parsing prévisions", e);
        }
    }
    private void checkForecastDivergence() {
    if (lastForecast.isEmpty()) return;
    long now = System.currentTimeMillis();

    // Récupération instantanée du cache temps réel TradingView
    java.util.concurrent.ConcurrentHashMap<String, TradingViewFetcher.TVMarketData> tvCache = TradingViewFetcher.getCache();
    if (tvCache.isEmpty()) return;

    // Utilisation d'un itérateur pour pouvoir supprimer l'élément en toute sécurité pendant le parcours
    java.util.Iterator<Map.Entry<String, PrevailingDirection>> iterator = lastForecast.entrySet().iterator();

    while (iterator.hasNext()) {
        Map.Entry<String, PrevailingDirection> entry = iterator.next();
        String asset = entry.getKey();
        PrevailingDirection forecast = entry.getValue();

        // Extraction de la structure de données TV pour cet actif
        TradingViewFetcher.TVMarketData tvData = tvCache.get(asset);
        if (tvData == null || tvData.price <= 0) continue;

        // 1. Vérification atomique du cooldown (Inchangé, optimal)
        final boolean[] canAlert = {false};
        lastAlertsSent.compute(asset, (k, lastTime) -> {
            if (lastTime == null || (now - lastTime) >= ALERT_COOLDOWN_MS) {
                canAlert[0] = true;
                return now; // Met à jour le timestamp de l'alerte
            }
            return lastTime; // Trop tôt
        });

        if (!canAlert[0]) continue;

        // 2. Calcul de la variation par rapport au prix de référence du forecast
        double changePercent = (tvData.price - forecast.referencePrice) / forecast.referencePrice * 100.0;
        
        boolean contradiction = (forecast.direction.equals("BULLISH") && changePercent < -DIVERGENCE_THRESHOLD) ||
                                (forecast.direction.equals("BEARISH") && changePercent > DIVERGENCE_THRESHOLD);

        if (contradiction) {
            Log.w(TAG, "⚡ [DIVERGENCE] Contradiction détectée pour " + asset + " : " + changePercent + "%");

            // 3. Génération instantanée du rapport Global Macro (sans aucune requête HTTP / sans slot)
            String rapportGlobalRealTime = TradingViewFetcher.buildContexteMacroGlobal(NotificationService.this);

            String alerteDiv = "🔄 *ALERTE DIVERGENCE MARCHÉ*\n" +
                               "Actif : *" + asset + "* (" + String.format(java.util.Locale.US, "%+.2f", changePercent) + "%)\n" +
                               "Flux directionnel initial : *" + forecast.direction + "*\n\n" +
                               "📊 *Nouvelle cartographie temps réel :*\n" + rapportGlobalRealTime;

            sendTelegramSecure(alerteDiv, NotificationService.this);

            // 4. Suppression de la prévision via l'itérateur pour stopper le monitoring de cet actif
            // jusqu'à ce que le pipeline IA génère un nouveau forecast rafraîchi
            iterator.remove();
        }
    }
   }
    
    private static final String SYSTEM_PROMPT =
    "══════════════════════════════════════════════════════\n" +
    "IDENTITÉ\n" +
    "══════════════════════════════════════════════════════\n" +
    "Tu es le Directeur de la Recherche Macroéconomique d'un Hedge Fund Quantitatif.\n" +
    "Objectif : Identifier le DRIVER DOMINANT d'une actualité, appliquer la hiérarchie macroéconomique, puis projeter son impact sur les 6 actifs obligatoires.\n\n" +

    "══════════════════════════════════════════════════════\n" +
    "HIÉRARCHIE DES DRIVERS\n" +
    "══════════════════════════════════════════════════════\n" +
    "RANG SUPRÊME : FED, FOMC, CPI, Core CPI, PCE, Core PCE, NFP, Chômage, GDP, Powell, Warsh\n" +
    "RANG SECONDAIRE : PMI, ISM, Retail Sales, EIA, OPEC, Stocks pétrole, Stimulus budgétaire\n" +
    "RANG TACTIQUE : Géopolitique, Tarifs douaniers, Sentiment consommateurs, Chine, IPO, M&A, Rumeurs\n" +
    "RÈGLE : Un driver de rang supérieur écrase toujours un driver inférieur.\n" +
    "Exception : Escalade militaire directe (Hormuz, frappes, guerre, embargo énergétique) : le régime GÉO devient prioritaire pour GOLD et USOIL.\n\n" +

    "══════════════════════════════════════════════════════\n" +
    "ANTI-BRUIT & SOURCES\n" +
    "══════════════════════════════════════════════════════\n" +
    "• Déclarations diplomatiques seules = impact faible.\n" +
    "• Trump, Iran, Israël, sanctions verbales = impact limité.\n" +
    "• Discussions, négociations, trêves potentielles = impact limité.\n" +
    "• Une géopolitique majeure exige : frappe, missile, embargo, blocage d'Hormuz, opération militaire réelle.\n" +
    "• Pondération des sources : Bloomberg, Reuters, FT, FJ = forte. Twitter, ZeroHedge, rumeurs = faible (conviction ≤40%).\n\n" +
    "══════════════════════════════════════════════════════\n" +
    "RÈGLES DE FIABILITÉ\n" +
    "══════════════════════════════════════════════════════\n" +
    "1. Interdiction d'inventer un événement absent du registre.\n" +
    "2. Toute conclusion doit être reliée à un événement observé.\n" +
    "3. Contradiction temporelle : un driver SUPRÊME annule un driver inférieur dans les 30 min.\n" +
    "4. Si deux drivers majeurs se contredisent, régime NEUTRE et confiance FAIBLE.\n" +
    "5. Le FLUX DOMINANT doit être dérivé du driver de rang le plus élevé.\n" +
    "6. Si données insuffisantes : ⚠️ DONNÉES INSUFFISANTES POUR ANALYSE DIRECTIONNELLE.\n\n" +

    "══════════════════════════════════════════════════════\n" +
    "CONVICTION & NIVEAU DE CONFIANCE\n" +
    "══════════════════════════════════════════════════════\n" +
    "Actual = Forecast → max 50% | Surprise <5% → max 65% | Surprise >10% → 80%+\n" +
    "Jauge : <40% ⚪⚪⚪⚪⚪ | 41-60% 🟠🟠🟠⚪⚪ | 61-80% 🟡🟡🟡🟡⚪ | >80% 🔴🔴🔴🔴🔴\n" +
    "NIVEAU DE CONFIANCE : SUPRÊME dominant → ÉLEVÉ | SECONDAIRE → MODÉRÉ | TACTIQUE → FAIBLE | Conflit → FAIBLE\n\n" +

    "══════════════════════════════════════════════════════\n" +
    "MATRICES DIRECTIONNELLES (↑=BULLISH, ↓=BEARISH)\n" +
    "══════════════════════════════════════════════════════\n" +
    "HAWKISH US : USDJPY↑ GOLD↓ NASDAQ↓ SP500↓ GBPUSD↓ USOIL= | FLUX : DOLLAR FORT\n" +
    "DOVISH US : inverse | FLUX : DOLLAR FAIBLE\n" +
    "GÉO escalade : USOIL↑ USDJPY↓ NASDAQ↓ SP500↓ GBPUSD↓ | FLUX : CRISE GÉOPOLITIQUE\n" +
    "GOLD en crise GÉO Iran/Hormuz :\n" +
    "- Attaque tanker/drone/tension SANS riposte USA confirmée → GOLD🟢 immédiat (historique : +0.8% à +1.5%)\n" +
    "- Riposte militaire USA confirmée (frappe/bombardement/Pentagon/airstrike) → GOLD🔴 30-60min puis rebond\n" +
    "- RÈGLE PAR DÉFAUT : si texte ne contient pas 'riposte/strike/Pentagon/airstrike/bombardement' → GOLD🟢 obligatoirement\n" +
    "GÉO désescalade : GOLD↓ USOIL↓ NASDAQ↑ SP500↑ USDJPY↑ | FLUX : RISK-ON\n" +
    "EIA déficit : USOIL↑ | EIA surplus : USOIL↓\n" +
    "TARIFS escalade : NASDAQ↓ SP500↓ USOIL↓ USDJPY↓ GOLD↑ GBPUSD↓ | FLUX : RISK-OFF\n" +
    "CHINE forte : USOIL↑ NASDAQ↑ SP500↑ | FLUX : RISK-ON\n" +
    "SENTIMENT faible : NASDAQ↓ SP500↓ GOLD↑ USOIL↓ | FLUX : RISK-OFF MODÉRÉ\n" +
    "IPO majeure : NASDAQ↑ SP500↑ GOLD↓ USDJPY↓ | FLUX : RISK-ON\n" +
    "RÈGLE JUSTIFICATION : la matrice donne la DIRECTION, jamais le TEXTE. Pour chaque actif, déduis et écris le mécanisme causal exact (ex: taux/devise/refuge/corrélation) reliant le driver détecté à cet actif précis — jamais une formule générique répétée.\n\n" +
    "══════════════════════════════════════════════════════\n" +
    "BANQUES CENTRALES ÉTRANGÈRES\n" +
    "══════════════════════════════════════════════════════\n" +
    "BoJ dovish → USDJPY↑ ; BoJ hawkish → USDJPY↓\n" +
    "BoE dovish → GBPUSD↓ ; BoE hawkish → GBPUSD↑\n" +
    "EURUSD (CONTEXTE UNIQUEMENT) : EURUSD n'est pas un actif suivi. Si une donnée BCE/zone euro est fournie en contexte, utilise-la uniquement pour calibrer la cohérence directionnelle de GBPUSD (corrélation EUR/GBP), sans jamais l'afficher comme ligne séparée dans le rapport.\n" +
    "RÈGLE ABSOLUE : NASDAQ, SP500, GOLD, USOIL = NEUTRE pour toute news étrangère (sauf choc global explicite ou driver énergie/géo simultané).\n\n" +
    "══════════════════════════════════════════════════════\n" +
    "CONTRAINTES ABSOLUES\n" +
    "══════════════════════════════════════════════════════\n" +
    "1. Analyser uniquement les actifs impactés par le driver. Omettre les actifs NEUTRE — ne pas les lister.\n" +
    "2. NASDAQ = SP500 (même direction).\n" +
    "3. Un seul 📢 dans toute la réponse.\n" +
    "4. USDJPY BEARISH → flux ne dit pas DOLLAR FORT.\n" +
    "5. USDJPY BULLISH → flux ne dit pas YEN FORT.\n" +
    "6. CORRÉLATION USDJPY/GBPUSD :\n" +
    "   - Régime DOLLAR (HAWKISH/DOVISH Fed) → directions INVERSES obligatoires : USDJPY↑ = GBPUSD↓ et inversement.\n" +
    "   - Régime RISK (GÉO/risk-off/risk-on) → même direction obligatoire : les deux baissent en risk-off, les deux montent en risk-on.\n" +
    "   - Divergence autorisée UNIQUEMENT si BoJ seul (GBPUSD neutre) ou BoE seul (USDJPY neutre).\n" +
    "7. Chaque actif : direction + mécanisme causal précis ≤8 mots. INTERDIT : 'pas de lien direct', 'même raisonnement', 'comme pour'.\n" +
    "DIRECTION OBLIGATOIRE : utiliser exclusivement 🟢 pour BULLISH, 🔴 pour BEARISH, NEUTRE pour neutre. Interdit d'écrire 'BULLISH', 'BEARISH', '↑', '↓', '='.\n" +
    "Lister uniquement les actifs impactés — omettre les NEUTRE.\n" +
    "8. Pas de doubles astérisques (**) – utiliser *simple*.\n" +
    "9. VECTEUR CIBLE autorisé : HAWKISH, DOVISH, GÉO, LIQUIDITÉ, CHINE, TARIFS, IPO.\n" +
    "10. En cas de crise géopolitique, appliquer l'exception et mentionner \"Régime Safe-Haven\".\n\n" +
    "══════════════════════════════════════════════════════\n" +
    "FORMAT DE SORTIE OBLIGATOIRE\n" +
    "══════════════════════════════════════════════════════\n" +
    "🚨 [SOURCE]\n" +
    "🕒 [DATE/HEURE MADA]\n" +
    "📊 CONVICTION : [JAUGE] XX% | CONFIANCE : [FAIBLE/MODÉRÉ/ÉLEVÉ]\n" +
    "🎯 VECTEUR CIBLE : [VALEUR AUTORISÉE]\n" +
    "📢 FAIT MARQUANT : [Analyse synthétique du driver dominant]\n" +
    "--- IMPACTS ACQUISITION ---\n" +
    "• 💻 NASDAQ : [direction] | [justification ≤10 mots]\n" +
    "• 📊 SP500 : [direction] | [justification ≤10 mots]\n" +
    "• 🏆 GOLD : [direction] | [justification ≤10 mots]\n" +
    "• 🛢️ USOIL : [direction] | [justification ≤10 mots]\n" +
    "• 🇯🇵 USDJPY : [direction] | [justification ≤10 mots]\n" +
    "• 🇬🇧 GBPUSD : [direction] | [justification ≤10 mots]\n" +
    "🏁 FLUX DOMINANT : [FLUX EXACT ISSUE DES MATRICES]";

    private static final String DAILY_SYSTEM_PROMPT =
    "Tu es Directeur de la Recherche Macroéconomique d'un Hedge Fund Quantitatif.\n" +
    
    "HIERARCHIE DES DRIVERS : SUPRÊME(100) > SECONDAIRE(60) > TACTIQUE(30).\n" +
    "SUPRÊME : FED,FOMC,Powell,Futur Chair FED,CPI,Core CPI,PCE,Core PCE,NFP,Chômage,GDP,ISM,PMI Flash,Retail Sales.\n" +
    "SECONDAIRE : EIA,OPEC,Résultats majeurs,Big Tech,PMI hors US,PIB hors US.\n" +
    "TACTIQUE : Géopolitique,Tarifs,Chine,Michigan,Conference Board,Rumeurs.\n" +
    "DOMINANCE : le rang supérieur gagne toujours. Un driver tactique ne peut jamais annuler un driver suprême. Si conflit >40 points : suivre uniquement le driver dominant. Si conflit <20 points : FLUX MIXTE et conviction max 55%.\n" +
    "RÈGLE FED ABSOLUE : Powell,FOMC,Minutes FOMC,Dot Plot,Futur Chair FED sont toujours des drivers SUPRÊMES. Aucune news tactique ne peut les invalider.\n" +  
    "MOTEUR : 1-Identifier le driver principal. 2-Identifier le régime dominant. 3-Appliquer la matrice d'actifs. 4-Calculer la conviction. 5-Valider la cohérence finale.\n" +
    "RÈGLE USD MAÎTRE : pour FED,CPI,PCE,NFP,GDP,ISM déterminer d'abord DOLLAR FORT ou DOLLAR FAIBLE avant tout autre actif.\n" +
    "MONÉTAIRE US :\n" +
    "HAWKISH = USDJPY↑ GOLD↓ NASDAQ↓ SP500↓ GBPUSD↓ USOIL=\n" +
    "DOVISH = USDJPY↓ GOLD↑ NASDAQ↑ SP500↑ GBPUSD↑ USOIL=\n" +

    "BANQUES CENTRALES ÉTRANGÈRES :\n" +
    "BoJ HAWKISH=USDJPY↓ ; BoJ DOVISH=USDJPY↑.\n" +
    "BoE HAWKISH=GBPUSD↑ ; BoE DOVISH=GBPUSD↓.\n" +
    "EURUSD (CONTEXTE UNIQUEMENT, non listé) : sert uniquement à calibrer la cohérence de GBPUSD via la corrélation EUR/GBP, jamais affiché seul.\n" +
    "NASDAQ,SP500,GOLD,USOIL neutres sauf choc global explicite ou driver énergie/géo simultané.\n" +
    
    "GÉOPOLITIQUE :\n" +
    "Escalade réelle (frappe,missile,raid,embargo,Hormuz) = GOLD↑ USOIL↑ USDJPY↓ NASDAQ↓ SP500↓ GBPUSD↓.\n" +
    "Désescalade = inverse.\n" +
    
    "EIA déficit=USOIL↑.\n" +
    "EIA surplus=USOIL↓.\n" +
    
    "TARIFS DOUANIERS :\n" +
    "Escalade=NASDAQ↓ SP500↓ USOIL↓ USDJPY↓ GOLD↑ GBPUSD↓.\n" +
    
    "CHINE :\n" +
    "Chine forte=USOIL↑ NASDAQ↑ SP500↑.\n" +
    
    "Michigan faible=NASDAQ↓ SP500↓ GOLD↑ USOIL↓.\n" +
    
    "CONVICTION :\n" +
    "Base 50 + Bonus Rang + Bonus Surprise - Malus Conflit.\n" +
    "Surprise 0-5%=+0 ; 5-10%=+10 ; 10-20%=+20 ; >20%=+30.\n" +
    "Conforme aux attentes=max 50%.\n" +
    "Flux mixte=-30%.\n" +
    "Bornes 25%-95%.\n" +
    "⚪⚪⚪⚪⚪<40% | 🟠🟠🟠⚪⚪=41-60% | 🟡🟡🟡🟡⚪=61-80% | 🔴🔴🔴🔴🔴>80%.\n" +
    
    "SOURCES : Bloomberg,Reuters,Financial Times,Wall Street Journal=fortes. Twitter/X,ZeroHedge,rumeurs=max 40%.\n" +
    
    "VALIDATION FINALE :\n" +
    "1-NASDAQ=SP500 obligatoirement.\n" +
    "2-USDJPY cohérent avec le flux dominant.\n" +
    "3-Un seul 📢.\n" +
    "4. Lister uniquement les actifs impactés. Omettre les NEUTRE — ne pas les afficher.\n" +
    "5-Aucune direction contradictoire.\n" +
    "6-Identifier le driver dominant avant les impacts.\n" +
    "7-Pas de doubles astérisques.\n" +
    
    "DIRECTIONS AUTORISÉES : 🟢 (bullish) | 🔴 (bearish) | NEUTRE. Interdit d'écrire le mot BULLISH ou BEARISH en toutes lettres, interdit d'utiliser ↑ ↓ =.\n" +
    "DICTIONNAIRE MÉCANISMES PAR ACTIF (utiliser exclusivement ces termes) :\n" +
   "NASDAQ : re-pricing multiple croissance | compression valorisation tech | risk appetite dégradé\n" +
    "SP500 : prime de risque equity élargie | flux risk-off vers obligations | révision bénéfices à la baisse\n" +
    "GOLD : défaut GÉO Iran/Hormuz = GOLD🟢 refuge | riposte USA confirmée = GOLD🔴 court terme | taux réels négatifs soutiennent\n" +
    "USOIL : prime offre Hormuz activée | stocks EIA inférieurs attentes | demande Chine révisée\n" +
    "USDJPY : désengagement carry trade JPY | flux refuge compressent le cross | différentiel BoJ-Fed déterminant\n" +
    "CORRÉLATION USDJPY/GBPUSD : " +
    "En régime DOLLAR (HAWKISH/DOVISH Fed) → directions INVERSES obligatoires (USDJPY↑ = GBPUSD↓). " +
    "En régime RISK (GÉO/risk-off/risk-on) → même direction obligatoire (les deux baissent en risk-off, les deux montent en risk-on). " +
    "Divergence possible UNIQUEMENT si BoJ seul (neutre GBPUSD) ou BoE seul (neutre USDJPY).\n" +
    "GBPUSD : contexte macro UK détériore GBP | BoE diverge de la Fed | risk-off comprime les paires risquées | corrélation EURUSD confirme la direction\n" +
    "FORMAT STRICT :\n" +
    "📊 RAPPORT JOURNALIER – [Date/Heure Madagascar]\n" +
    "🚨 [SOURCE]\n" +
    "🕒 [Date/Heure Madagascar]\n" +
    "📊 CONVICTION : [JAUGE] XX%\n" +
    "🎯 VECTEUR CIBLE : [HAWKISH/DOVISH/GÉO/TARIFS/CHINE/LIQUIDITÉ]\n" +
    "📢 [FAIT MARQUANT : identifier clairement le driver dominant et l'arbitrage éventuel]\n" +
    "--- IMPACTS ACQUISITION ---\n" +
    "• 💻 NASDAQ : [🟢/🔴/NEUTRE] | [mécanisme ≤8 mots]\n" +
    "• 📊 SP500 : [🟢/🔴/NEUTRE] | [mécanisme ≤8 mots]\n" +
    "• 🏆 GOLD : [🟢/🔴/NEUTRE] | [mécanisme ≤8 mots]\n" +
    "• 🛢️ USOIL : [🟢/🔴/NEUTRE] | [mécanisme ≤8 mots]\n" +
    "• 🇯🇵 USDJPY : [🟢/🔴/NEUTRE] | [mécanisme ≤8 mots]\n" +
    "• 🇬🇧 GBPUSD : [🟢/🔴/NEUTRE] | [mécanisme ≤8 mots]\n" +
    "🏁 FLUX DOMINANT : [DOLLAR FORT/DOLLAR FAIBLE/RISK-ON/RISK-OFF/YEN FORT/OR FORT/CRISE GÉOPOLITIQUE]\n" +
    "INTERDIT ABSOLU : tout texte après 🏁 FLUX DOMINANT. Aucun paragraphe de synthèse, aucun comptage d'événements, aucune justification supplémentaire. Le rapport s'arrête obligatoirement à 🏁 FLUX DOMINANT.";
    
    private String getGroqApiKey() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(PREF_GROQ_KEY, "");
    }

    // 🛡️ Tronque les réponses d'erreur API longues avant affichage dans le panneau de logs,
    // pour garder le panneau lisible (les corps d'erreur JSON peuvent être très longs).
    private String truncateForLog(String s) {
        if (s == null) return "null";
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
    
    private boolean estEvenementSuprême(String text) {
        String upper = text.toUpperCase(Locale.ROOT);
        return upper.contains("FOMC") || upper.contains("FED ") ||
               upper.contains("CPI")  || upper.contains("PCE")  ||
               upper.contains("NFP")  || upper.contains("BCE")  ||
               upper.contains("ECB")  || upper.contains("BOJ")  ||
               upper.contains("BOE")  || upper.contains("RBA")  ||
               upper.contains("BOC")  || upper.contains("PIB")  ||
               upper.contains("GDP")  || upper.contains("OPEC") ||
               upper.contains("INFLATION") || upper.contains("INTEREST RATE") ||
               upper.contains("POWELL") || upper.contains("LAGARDE") ||
               upper.contains("PMI") || upper.contains("ISM");
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
    // === AJOUT À FAIRE ===
    private final ExecutorService tradingPipelineExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService exec = Executors.newFixedThreadPool(5);
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private EventDatabase eventDb;
    private volatile boolean isSyncing = false;
    private static final String PREF_LAST_DAILY_REPORT = "last_daily_report_";
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
    // Volatile pour la cohérence multi-thread (Point 7)
    private volatile long lastSpeechTime = 0;
    private volatile String lastSpeaker = "";

    private int extrairePourcentageConviction(String aiReport) {
        Pattern p = Pattern.compile("CONVICTION\\s*:\\s*.*?(\\d{1,3})%");
        Matcher m = p.matcher(aiReport);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) { return 0; }
        }
        return 0;
    }

    private static String injectLivePrices(String groqReport, List<String> assets) {
    if (groqReport == null || groqReport.isEmpty() || assets == null || assets.isEmpty()) 
    return groqReport;

    try {

    // ✅ Source unique : cache WebSocket TradingView (temps réel, gratuit, sans quota).
    // Alimenté en continu par TradingViewFetcher.start() — plus d'appel réseau Twelve Data ici.
    Map<String, TradingViewFetcher.TVMarketData> liveDataMap = TradingViewFetcher.getCache();
    if (liveDataMap == null || liveDataMap.isEmpty()) return groqReport;

    String[] lignes = groqReport.split("\n");
    StringBuilder reportAjuste = new StringBuilder();

    for (String ligne : lignes) {
        String ligneModifiee = ligne;
        
        for (String asset : assets) {
            String patternStr = "^\\s*[•\\-*]?\\s*\\S*\\s*" + Pattern.quote(asset) + "\\s*:.*";
            
            if (ligne.matches(patternStr)) {
                TradingViewFetcher.TVMarketData data = liveDataMap.get(asset);
                if (data != null && data.price > 0) {
                    String sign = (data.changePercent >= 0) ? "+" : "";
                    String emojiVariation = (data.changePercent > 0) ? "🟢"
                        : (data.changePercent < 0) ? "🔴" : "⚪";
                    StringBuilder badgeMarche = new StringBuilder(String.format(Locale.US,
                        " (%.4f | %s%.2f%% %s", data.price, sign, data.changePercent, emojiVariation));
                    // 🏛️ Niveaux pivots natifs TradingView, affichés seulement s'ils sont chargés
                    if (data.pdh > 0 || data.pdl > 0) {
                        badgeMarche.append(" | PDH=").append(String.format(Locale.US, "%.4f", data.pdh))
                                   .append(" PDL=").append(String.format(Locale.US, "%.4f", data.pdl));
                        if (data.brokeAbovePDH) badgeMarche.append(" 🔺PDH");
                        else if (data.brokeBelowPDL) badgeMarche.append(" 🔻PDL");
                    }
                    if (data.pwh > 0 || data.pwl > 0) {
                        badgeMarche.append(" | PWH=").append(String.format(Locale.US, "%.4f", data.pwh))
                                   .append(" PWL=").append(String.format(Locale.US, "%.4f", data.pwl));
                        if (data.brokeAbovePWH) badgeMarche.append(" 🚀PWH");
                        else if (data.brokeBelowPWL) badgeMarche.append(" 🔥PWL");
                    }
                    badgeMarche.append(")");
                    int indexColon = ligne.indexOf(":");
                    if (indexColon != -1) {
                        ligneModifiee = ligne.substring(0, indexColon) + badgeMarche + " :" + ligne.substring(indexColon + 1);
                    } else {
                        ligneModifiee = ligne + badgeMarche;
                    }
                }
                break;
            }
        }
        reportAjuste.append(ligneModifiee).append("\n");
    }
    return reportAjuste.toString().trim();
} catch (Exception e) {
    Log.e(TAG, "Erreur lors de l'injection des prix live", e);
    return groqReport;
}
}
private void processAnalysisWithAI(String sourceName, String title, String body, List<String> enrichedAssets, String fingerprint, String customSystemPrompt, boolean isSupremeRank,
    Map<String, TradingViewFetcher.TVMarketData> cachedMarketData) { // ✅ Alignement sur le cache WebSocket TradingView (Twelve Data n'est plus utilisé)
    
    final String systemPrompt = (customSystemPrompt != null && !customSystemPrompt.isEmpty())
       ? customSystemPrompt
       : SYSTEM_PROMPT;
       
    // Génération dynamique de l'horodatage actuel au format de Madagascar (EAT)
    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss", java.util.Locale.FRANCE);
    sdf.setTimeZone(java.util.TimeZone.getTimeZone("Indian/Antananarivo"));
    String currentMadaTime = sdf.format(new java.util.Date());

    // Sécurisation anti-NullPointerException de la liste des actifs
    String assetsString = (enrichedAssets != null) ? enrichedAssets.toString() : "[]";

    final String userContent = "CONTEXTE TEMPOREL : " + currentMadaTime + "\n"
            + "SOURCE DE LA NEWS : " + sourceName + "\n"
            + "TITRE : " + title + "\n"
            + "CORPS DE LA NOTIFICATION : " + body + "\n"
            + "ACTIFS PRÉ-QUALIFIÉS : " + assetsString;

    Executors.newSingleThreadExecutor().execute(new Runnable() {
        @Override
        public void run() {
            java.net.HttpURLConnection conn = null;
            EventDatabase db = EventDatabase.getInstance(NotificationService.this);
            if (db == null || fingerprint == null) {
                Log.e(TAG, "Instance SQLite ou empreinte manquante. Avortement du pipeline.");
                return;
            }
    
            try {
                List<String> historique = db.obtenirTexteEvenementsRecents();
                String promptFinal = construirePromptFinalAvecPrompt(body, historique, systemPrompt);
                
                // Vérifier et réinitialiser le compteur à minuit UTC
                long nowUtc = System.currentTimeMillis();
                long midnightUtc = (nowUtc / 86400000L + 1) * 86400000L;
                if (nowUtc >= tokenResetTime) {
                    dailyTokensUsed.set(0);
                    tokenResetTime = midnightUtc;
                    if (MainActivity.instance != null)
                        MainActivity.instance.addLog("🔄 [TOKEN] Compteur TPD réinitialisé (minuit UTC).");
                }
                
                // Vérifier budget restant
                int used = dailyTokensUsed.addAndGet(TOKEN_ESTIMATE_PER_CALL);
                getSharedPreferences("TradingBotPrefs", MODE_PRIVATE).edit()
                    .putInt("daily_tokens_used", used)
                    .putLong("token_reset_time", tokenResetTime)
                    .apply();
                
                JSONObject jsonPayload = new JSONObject();
                
                if (used > TOKEN_BUDGET_DAILY) {
                    Log.w(TAG, "[TOKEN] Budget TPD épuisé (" + used + ") — bascule directe fallback.");
                    if (MainActivity.instance != null)
                        MainActivity.instance.addLog("⚠️ [TOKEN] Budget 90k atteint — fallback préventif.");
                    
                    jsonPayload.put("model", GROQ_MODEL_FALLBACK);
                    jsonPayload.put("temperature", 0.0);
                    jsonPayload.put("max_tokens", 600);
                    
                    // ✅ CORRECTION DU CRASH : On injecte le rappel de format directement dans la variable String du prompt
                    promptFinal += "\n\nRAPPEL FORMAT STRICT FALLBACK :\n" +
                                   "- Justification : INTERDIT ce mot. Format obligatoire : '• emoji ACTIF : 🟢/🔴 | mécanisme ≤8 mots'\n" +
                                   "- Jamais de phrase complète. Jamais de 'entraînent', 'pourrait', 'impact potentiel'.\n" +
                                   "- Exemples : '| Prime géopolitique activée Hormuz' | '| Flight-to-quality comprime rendements'";
                } else {
                    jsonPayload.put("model", GROQ_MODEL);
                    jsonPayload.put("temperature", 0.02);
                    jsonPayload.put("max_tokens", 600);
                }
    
                // Construction propre et sécurisée des messages JSON
                JSONArray messages = new JSONArray();
                messages.put(new JSONObject().put("role", "system").put("content", promptFinal));
                messages.put(new JSONObject().put("role", "user").put("content", userContent));
                jsonPayload.put("messages", messages);
    
                String apiKey = getGroqApiKey();
                if (apiKey.isEmpty()) {
                    Log.e(TAG, "[GROQ] Clé API absente. Analyse annulée.");
                    db.markEventAsSynced(fingerprint, "FAILED_MISSING_API_KEY");
                    return;
                }
    
                java.net.URL url = new java.net.URL(GROQ_URL);
                conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
    
                try (java.io.OutputStream os = conn.getOutputStream()) {
                    byte[] input = jsonPayload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                    os.flush();
                }
                
                // ... Suite de ton code (lecture de la réponse, etc.)

    
                int status = conn.getResponseCode();
                if (status == java.net.HttpURLConnection.HTTP_OK) {
                    StringBuilder response = new StringBuilder();
                    try (java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            response.append(line);
                        }
                    }
    
                    JSONObject jsonResponse = new JSONObject(response.toString());
                    String aiReport = jsonResponse.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content");
    
                    if (aiReport == null || aiReport.length() < 50) {
                        Log.w(TAG, "[GROQ] Rapport reçu trop court ou vide.");
                        db.markEventAsSynced(fingerprint, "FAILED_EMPTY_LLM_REPORT");
                        return;
                    }
    
                    // Filtrage intelligent des signaux d'impacts macroéconomiques
                    StringBuilder filteredMessage = new StringBuilder();
                    String[] lines = aiReport.split("\n");
                    int activeSignalsCount = 0;
                    boolean inImpactSection = false;
    
                    java.util.Set<String> actifsDejaSeen = new java.util.HashSet<>();
                    for (String line : lines) {
                        // Masquer les actifs NEUTRE — inutiles pour le trading
                        if (line.contains("NEUTRE") || line.matches(".*•.*:.*= \\|.*")) continue;
                        // Masquer les doublons d'actifs (ex: EURUSD affiché 2 fois)
                        if (line.trim().startsWith("•")) {
                            String actifKey = line.trim().length() > 15 ? line.trim().substring(0, 15) : line.trim();
                            if (!actifsDejaSeen.add(actifKey)) continue;
                        }
                        String trimmed = line.trim();
                        if (trimmed.isEmpty()) continue;
                        if (trimmed.startsWith("🚨") || trimmed.startsWith("📊") || trimmed.startsWith("🎯") ||
                            trimmed.startsWith("📢") || trimmed.startsWith("🏁") || trimmed.startsWith("--- IMPACTS")) {
                            filteredMessage.append(line).append("\n");
                            if (trimmed.startsWith("--- IMPACTS")) inImpactSection = true;
                            continue;
                        }
                        if (inImpactSection && trimmed.startsWith("•")) {
                            String upperLine = line.toUpperCase(Locale.ROOT);
                            boolean isInclinationNeutral = upperLine.contains("MAIS NEUTRE");
                            boolean isSignificant = !isInclinationNeutral &&
                                (upperLine.contains("BULLISH") || upperLine.contains("BEARISH"));
                            if (isSignificant) {
                                filteredMessage.append(line).append("\n");
                                activeSignalsCount++;
                            }
                        }
                    }
    
                    // ✅ Application du filtre conviction
                    // APRÈS
                    if (activeSignalsCount > 0) {
                    int convictionPercent = extrairePourcentageConviction(aiReport);
                      if (convictionPercent >= 40 || isSupremeRank) {
                            // ✅ Injection des prix live inline sur chaque ligne d'actif
                              String enrichedReport = injectLivePrices(
                                filteredMessage.toString().trim(),
                                enrichedAssets
                            );
                            String finalPayload = "⚡ *ANALYSE MACRO ÉCONOMIQUE*\n" + enrichedReport;
                            sendTelegramSecure(finalPayload, NotificationService.this);
                            // Extraire résumé directionnel pour affichage rappel inertie
                            StringBuilder impactResume = new StringBuilder();
                            for (String l : aiReport.split("\n")) {
                                if (l.matches(".*•.*:.*[🟢🔴].*")) {
                                    String[] parts = l.split("\\|");
                                    if (parts.length > 0) impactResume.append(parts[0].trim()).append(" ");
                                }
                            }
                            String impactFinal = impactResume.length() > 0
                                ? impactResume.toString().trim()
                                : aiReport.contains("FLUX DOMINANT") ?
                                  "Flux: " + aiReport.split("FLUX DOMINANT")[1].replaceAll("[:\\n]","").trim() : "N/A";
                                  db.markEventAsSynced(fingerprint, impactFinal.length() > 200
                                ? impactFinal.substring(0, 200) : impactFinal);
                                                        } else {
                                                            Log.d(TAG, "Conviction trop faible (" + convictionPercent + "%) et non suprême → message ignoré");
                                                        db.markEventAsSynced(fingerprint, "LOW_CONVICTION_FILTERED");
                                                    }
                                                } else {
                                                    db.markEventAsSynced(fingerprint, "FILTERED_ALL_NEUTRAL");
                                                }
                                            } else {
                                                Log.e(TAG, "[GROQ] Erreur de serveur HTTP Code : " + status);
                                            if (status == 429) {
                                Log.w(TAG, "[GROQ] 429 TPD — Bascule automatique vers " + GROQ_MODEL_FALLBACK);
                                if (MainActivity.instance != null)
                                    MainActivity.instance.addLog("⚠️ [GROQ] Quota épuisé — fallback sur modèle léger.");
                                conn.disconnect();
                                try {
                                    jsonPayload.put("model", GROQ_MODEL_FALLBACK);
                                    jsonPayload.put("temperature", 0.0);
                                    jsonPayload.put("max_tokens", 600);
                                    String contexteFallback = "";
                                    try {
                                List<String> historiqueDb = db.obtenirTexteEvenementsRecents();
                                if (historiqueDb != null && !historiqueDb.isEmpty()) {
                                    contexteFallback += "CONTEXTE RÉCENT (derniers événements) :\n"
                                        + String.join("\n", historiqueDb.subList(0, Math.min(3, historiqueDb.size())))
                                        + "\n\n";
                                }
                                // 2. Prix LKV cache
                                   if (cachedMarketData != null && !cachedMarketData.isEmpty()) {
                                   StringBuilder prixLkv = new StringBuilder("PRIX LKV (cache) :\n");
                                   // ✅ Type aligné sur le nouveau cache de TradingViewFetcher :
                                   for (Map.Entry<String, TradingViewFetcher.MarketData> e : cachedMarketData.entrySet()) {
                                   prixLkv.append(e.getKey()).append(" : ")
                                   .append(String.format(java.util.Locale.US, "%.4f", e.getValue().price))
                                   .append("\n");
                                    }
                                   contexteFallback += prixLkv.toString() + "\n";
                                   }
                             // 3. Flux dominant sauvegardé
                                String dernierFlux = getSharedPreferences("TradingBotPrefs", MODE_PRIVATE)
                                    .getString("last_dominant_flow", null);
                                if (dernierFlux != null && !dernierFlux.isEmpty()) {
                                    contexteFallback += "FLUX DOMINANT PRÉCÉDENT : " + dernierFlux + "\n"
                                        + "RÈGLE : si le nouveau driver est cohérent, maintiens ce flux. "
                                        + "Si contradictoire, justifie explicitement le changement de régime.\n\n";
                                }
            } catch (Exception eCtx) {
                Log.w(TAG, "[FALLBACK] Enrichissement contexte échoué : " + eCtx.getMessage());
            }
            // Injecter dans le message user existant
            JSONArray msgsFallback = jsonPayload.getJSONArray("messages");
            JSONObject userMsg = msgsFallback.getJSONObject(msgsFallback.length() - 1);
            String bodyEnrichi = contexteFallback + userMsg.getString("content");
            userMsg.put("content", bodyEnrichi);
                    java.net.URL urlFallback = new java.net.URL(GROQ_URL);
                    java.net.HttpURLConnection connFallback = (java.net.HttpURLConnection) urlFallback.openConnection();
                    connFallback.setRequestMethod("POST");
                    connFallback.setDoOutput(true);
                    connFallback.setRequestProperty("Authorization", "Bearer " + apiKey);
                    connFallback.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    connFallback.setConnectTimeout(15000);
                    connFallback.setReadTimeout(15000);
                    try (java.io.OutputStream osFb = connFallback.getOutputStream()) {
                        byte[] inputFb = jsonPayload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        osFb.write(inputFb, 0, inputFb.length);
                        osFb.flush();
                    }
                    int statusFb = connFallback.getResponseCode();
                    if (statusFb == java.net.HttpURLConnection.HTTP_OK) {
                        StringBuilder fbResp = new StringBuilder();
                        try (java.io.BufferedReader brFb = new java.io.BufferedReader(
                                new java.io.InputStreamReader(connFallback.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                            String lineFb;
                            while ((lineFb = brFb.readLine()) != null) fbResp.append(lineFb);
                        }
                        JSONObject jsonFb = new JSONObject(fbResp.toString());
                        String fallbackReport = jsonFb.getJSONArray("choices")
                                .getJSONObject(0).getJSONObject("message").getString("content");
                        if (fallbackReport != null && fallbackReport.length() >= 50) {
                // Filtrer NEUTRE avant envoi — même logique que modèle principal
                StringBuilder filteredFb = new StringBuilder();
                boolean inImpactFb = false;
                for (String lFb : fallbackReport.split("\n")) {
                    if (lFb.contains("NEUTRE") || lFb.matches(".*•.*:.*= \\|.*")) continue;
                    String trimFb = lFb.trim();
                    if (trimFb.isEmpty()) continue;
                    if (trimFb.startsWith("🚨") || trimFb.startsWith("🕒") || trimFb.startsWith("📊") ||
                        trimFb.startsWith("🎯") || trimFb.startsWith("📢") || trimFb.startsWith("🏁") ||
                        trimFb.startsWith("--- IMPACTS")) {
                        filteredFb.append(lFb).append("\n");
                        if (trimFb.startsWith("--- IMPACTS")) inImpactFb = true;
                        continue;
                    }
                    if (inImpactFb && trimFb.startsWith("•")) {
                        String upperFb = lFb.toUpperCase(Locale.ROOT);
                        if (!upperFb.contains("MAIS NEUTRE") &&
                            (upperFb.contains("BULLISH") || upperFb.contains("BEARISH") ||
                             lFb.contains("🟢") || lFb.contains("🔴"))) {
                            filteredFb.append(lFb).append("\n");
                        }
                    }
    }
    // Seuil conviction plus élevé sur fallback — modèle léger moins fiable
    
boolean fluxGeo = fallbackReport.contains("FLUX DOMINANT : CRISE GÉOPOLITIQUE");
if (fluxGeo) {
    String fb = filteredFb.toString();
    String fbLower = fallbackReport.toLowerCase();
    // Détection riposte militaire USA — bilingue
    boolean ripposteUSA =
        fbLower.contains("riposte")
        || fbLower.contains("frappe américaine")
        || fbLower.contains("frappe militaire")
        || fbLower.contains("trump ordonne")
        || fbLower.contains("offensive américaine")
        || fbLower.contains("bombardement")
        || fbLower.contains("us strike")
        || fbLower.contains("us attack")
        || fbLower.contains("military response")
        || fbLower.contains("pentagon")
        || fbLower.contains("airstrike")
        || fbLower.contains("air strike")
        || fbLower.contains("missile strike")
        || fbLower.contains("trump orders")
        || fbLower.contains("retaliati")
        || fbLower.contains("u.s. military")
        || fbLower.contains("us military")
        || fbLower.contains("american forces")
        || fbLower.contains("armed response");
    // GOLD : refuge sauf si riposte USA active (dollar domine)
    if (!ripposteUSA)
        fb = fb.replaceAll("(• 🏆 GOLD\\s*:\\s*)🔴", "$1🟢");
    // USOIL/USDCAD/AUDUSD : toujours haussiers en crise GÉO
    // USOIL : toujours haussier en crise GÉO
    if (fb.contains("USOIL : 🔴"))  fb = fb.replace("• 🛢️ USOIL : 🔴",  "• 🛢️ USOIL : 🟢");
    filteredFb = new StringBuilder(fb);
     }
    int convFb = extrairePourcentageConviction(fallbackReport);
    // Vérifier cohérence vecteur/flux — rejeter si contradiction
    boolean vecteurGeo = fallbackReport.contains("VECTEUR CIBLE : GÉO") || fallbackReport.contains("VECTEUR CIBLE : GÉOPOLITIQUE");
    //boolean fluxGeo = fallbackReport.contains("FLUX DOMINANT : CRISE GÉOPOLITIQUE");
    boolean fluxHawkish = fallbackReport.contains("FLUX DOMINANT : DOLLAR FORT") || fallbackReport.contains("VECTEUR CIBLE : HAWKISH");
    boolean contradiction = (vecteurGeo && fluxHawkish) || (fluxGeo && fluxHawkish);
    if (contradiction) {
        Log.d(TAG, "[FALLBACK] Contradiction vecteur/flux détectée — rapport rejeté.");
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("⚪ [FALLBACK] Contradiction régime — ignoré.");
    } else if (convFb >= 55 || (isSupremeRank && convFb >= 45)) {
        sendTelegramSecure("⚡ *[ANALYSE FONDAMENTALE]* " + filteredFb.toString().trim(), NotificationService.this);
    } else {
        Log.d(TAG, "[FALLBACK] Conviction trop faible (" + convFb + "%) — ignoré.");
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("⚪ [FALLBACK] Conviction " + convFb + "% insuffisante — ignoré.");
    }
        StringBuilder impactFb = new StringBuilder();
    for (String l : fallbackReport.split("\n")) {
        if (l.matches(".*•.*:.*[🟢🔴].*")) {
            String[] parts = l.split("\\|");
            if (parts.length > 0) impactFb.append(parts[0].trim()).append(" ");
        }
    }
    String impactFinalFb = impactFb.length() > 0
        ? impactFb.toString().trim()
        : "Flux: " + (fallbackReport.contains("FLUX DOMINANT") ?
          fallbackReport.split("FLUX DOMINANT")[1].replaceAll("[:\\n]","").trim() : "N/A");
    if (db != null) db.markEventAsSynced(fingerprint, impactFinalFb.length() > 200
        ? impactFinalFb.substring(0, 200) : impactFinalFb);
        // Sauvegarder le flux dominant pour contexte fallback suivant
        try {
            Pattern fluxPattern = Pattern.compile("FLUX DOMINANT\\s*:\\s*(.+)");
            Matcher fluxMatcher = fluxPattern.matcher(fallbackReport);
            if (fluxMatcher.find()) {
                String nouveauFlux = fluxMatcher.group(1).trim();
    String ancienFlux = getSharedPreferences("TradingBotPrefs", MODE_PRIVATE)
        .getString("last_dominant_flow", null);
    getSharedPreferences("TradingBotPrefs", MODE_PRIVATE)
        .edit()
        .putString("last_dominant_flow", nouveauFlux)
        .apply();
    // 🚨 Alerte changement de régime
     String ancienFluxNorm = ancienFlux.split("\\(")[0].trim().toUpperCase(Locale.ROOT);
    String nouveauFluxNorm = nouveauFlux.split("\\(")[0].trim().toUpperCase(Locale.ROOT);
    // Envoyer UNIQUEMENT si le régime change réellement
    if (ancienFlux != null && !ancienFlux.isEmpty()
            && !ancienFluxNorm.equals(nouveauFluxNorm)
            && !nouveauFluxNorm.isEmpty()) {
        String alerteChangement =
            "🔄 *CHANGEMENT DE RÉGIME DÉTECTÉ*\n" +
            "━━━━━━━━━━━━━━━━━━━━\n" +
            "📤 Ancien flux : *" + ancienFlux + "*\n" +
            "📥 Nouveau flux : *" + nouveauFlux + "*\n" +
            "⚡ Source : " + sourceName + "\n" +
            "🕒 " + new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss",
                java.util.Locale.FRANCE)
                .format(new java.util.Date());
        sendTelegramSecure(alerteChangement, NotificationService.this);
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("🔄 [RÉGIME] " + ancienFlux + " → " + nouveauFlux);
    }
        }
    } catch (Exception eFlux) {
        Log.w(TAG, "[FALLBACK] Sauvegarde flux dominant échouée : " + eFlux.getMessage());
    }
            } else {
                db.markEventAsSynced(fingerprint, "FAILED_FALLBACK_EMPTY");
            }
        } else {
            db.markEventAsSynced(fingerprint, "FAILED_FALLBACK_HTTP_" + statusFb);
        }
        connFallback.disconnect();
    } catch (Exception eFb) {
        Log.e(TAG, "[GROQ] Erreur lors du fallback modèle léger", eFb);
        db.markEventAsSynced(fingerprint, "FAILED_FALLBACK_EXCEPTION");
    }
    } else if (status >= 500) {
        Log.w(TAG, "[GROQ] Statut " + status + " serveur transitoire — événement laissé en attente.");
    } else {
        db.markEventAsSynced(fingerprint, "FAILED_SERVER_HTTP_" + status);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[GROQ] Échec lors de l'exécution réseau / SQLite", e);
                    if (db != null) {
                        try {
                            db.markEventAsSynced(fingerprint, "FAILED_CRITICAL_EXCEPTION");
                        } catch (Exception ex) {
                            Log.e(TAG, "Impossible de forcer la mise à jour du verrou SQLite", ex);
                        }
                    }
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        });    
    }
    // Point 5 : Déconnexion sécurisée encapsulée dans un bloc finally
    public static void sendTelegramSecure(String message, Context context) {
        new Thread(() -> {
            try {
                android.content.SharedPreferences prefs =
                    context.getSharedPreferences("TradingBot", Context.MODE_PRIVATE);
                String token  = prefs.getString("tg_token", "");
                String chatId = prefs.getString("tg_chat_id", "");
                if (token.isEmpty() || chatId.isEmpty()) {
                    Log.w(TAG, "[TELEGRAM] Token ou Chat ID manquant — envoi annulé.");
                    return;
                }
    
                // ✅ Découpage automatique si message > 4000 chars (limite Telegram = 4096)
                int MAX = 4000;
                List<String> chunks = new ArrayList<>();
                if (message.length() <= MAX) {
                    chunks.add(message);
                } else {
                    // Découpe proprement sur les sauts de ligne pour ne pas couper une ligne en deux
                    String[] lines = message.split("\n");
                    StringBuilder current = new StringBuilder();
                    for (String line : lines) {
                        if (current.length() + line.length() + 1 > MAX) {
                            chunks.add(current.toString().trim());
                            current = new StringBuilder();
                        }
                        current.append(line).append("\n");
                    }
                    if (current.length() > 0) chunks.add(current.toString().trim());
                }
    
                for (String chunk : chunks) {
                    sendChunkToTelegram(chunk, token, chatId, "Markdown");
                    if (chunks.size() > 1) Thread.sleep(500); // anti-flood entre morceaux
                }
    
            } catch (Exception e) {
                Log.e(TAG, "[TELEGRAM] Erreur envoi", e);
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("❌ [TELEGRAM] Erreur : " + e.getMessage());
            }
        }).start();
    }

    // ✅ NOUVELLE méthode helper — envoie un seul chunk, retente en texte brut si Markdown rejeté
    private static void sendChunkToTelegram(String text, String token,
                                             String chatId, String parseMode) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://api.telegram.org/bot" + token + "/sendMessage");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
    
            JSONObject payload = new JSONObject();
            payload.put("chat_id", chatId);
            payload.put("text", text);
            if (parseMode != null && !parseMode.isEmpty())
                payload.put("parse_mode", parseMode);
    
            byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(input, 0, input.length);
            }
    
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_OK) {
                Log.d(TAG, "[TELEGRAM] ✅ Chunk envoyé (" + text.length() + " chars)");
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("✅ [TELEGRAM] Message envoyé.");
            } else if (code == 400 && "Markdown".equals(parseMode)) {
                // ✅ Markdown rejeté (astérisque non fermé, etc.) → retente en texte brut
                Log.w(TAG, "[TELEGRAM] 400 Markdown rejeté → retry en texte brut");
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("⚠️ [TELEGRAM] Markdown invalide → retry texte brut");
                conn.disconnect();
                sendChunkToTelegram(text, token, chatId, "");
            } else {
                // Lit le corps d'erreur pour avoir le message exact de Telegram
                StringBuilder errBody = new StringBuilder();
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getErrorStream(),
                            StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = br.readLine()) != null) errBody.append(l);
                } catch (Exception ignored) {}
                String errMsg = "❌ [TELEGRAM] HTTP " + code + " : " + errBody;
                Log.e(TAG, errMsg);
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog(errMsg);
            }
        } catch (Exception e) {
            Log.e(TAG, "[TELEGRAM] Échec chunk", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // APRÈS
    if (!getSharedPreferences("TradingBot", MODE_PRIVATE).getBoolean("bot_active", false)) {
        String pkg = sbn.getPackageName();
        if (pkg != null && (pkg.contains("financialjuice") || pkg.contains("nikkei") || pkg.contains("forex.portal"))) {
            Log.w(TAG, "⏸️ [BOT INACTIF] Notif de '" + pkg + "' ignorée — bot_active=false.");
        }
        return;
    }
        // ✅ CORRECTIF BUG 9 : ignore toute notification pendant un import/restauration de base
        // (MainActivity ferme et recrée le fichier .db -> tout traitement ici lirait/écrirait
        // sur une connexion fermée ou un fichier en cours de remplacement).
        if (isDatabaseImportInProgress) {
            Log.w(TAG, "[SERVICE] Notification ignorée : import de base de données en cours.");
            return;
        }
        
            // Détection et filtrage immédiat des packages sources autorisés
            String packageName = sbn.getPackageName().toLowerCase(Locale.ROOT);
            String sourceName = "Source Institutionnelle";
            if (packageName.contains("financialjuice")) {
            sourceName = "FinancialJuice";
            } else if (packageName.contains("nikkei")) {
                sourceName = "TradingEconomics";
            } else if (packageName.contains("forex.portal")) {
                sourceName = "Myfxbook";
            } else if (packageName.contains("bloomberg")) {
                sourceName = "Bloomberg";
            } else if (packageName.contains("twitter") || packageName.contains("periscope")) {
                sourceName = "X /Twitter";
            } else if (packageName.contains("chrome") || packageName.equals("com.android.chrome")) {
                sourceName = "Chrome";
            // APRÈS
            } else {
                Log.v(TAG, "[FILTRE PACKAGE] Ignoré (source non suivie) : " + packageName);
                return;
            }
        
            // Extraction sécurisée des chaînes de caractères brutes fournies par Android
            Bundle extras = sbn.getNotification().extras;
            final String title = extras.getString(Notification.EXTRA_TITLE, "");
            String bigText = extras.getString(Notification.EXTRA_BIG_TEXT, "");
            String text = extras.getString(Notification.EXTRA_TEXT, "");
        
            // 🔴 SÉCURITÉ REGEX : Conservation du texte brut le plus complet pour l'extraction mathématique d'EconomicAnalyzer
            final String bodyTextRaw = bigText.length() > text.length() ? bigText : text;
        
            // Reconstruction standard du flux texte unifié pour le reste des modules
            String subText = extras.getString(Notification.EXTRA_SUB_TEXT, "");
            String summary = extras.getString(Notification.EXTRA_SUMMARY_TEXT, "");
            String tempBody = bodyTextRaw;
            if (subText.length() > tempBody.length()) tempBody = subText;
            if (summary.length() > tempBody.length()) tempBody = summary;
        
            String tempUnifiedFeed = (title + " " + tempBody).trim();
            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null && lines.length > 0) {
                StringBuilder bundled = new StringBuilder(title).append(" ");
                for (CharSequence line : lines) {
                    if (line != null) bundled.append(line).append(" ");
                }
                String bundledFeed = bundled.toString().trim();
                if (bundledFeed.length() > tempUnifiedFeed.length()) {
                    tempUnifiedFeed = bundledFeed;
                }
            }
        
            // APRÈS
            if (tempUnifiedFeed.length() < 6) {
               Log.w(TAG, "⚠️ [EXTRACTION VIDE] '" + sourceName + "' rejeté : texte trop court (titre='" + title 
               + "', len=" + tempUnifiedFeed.length() + "). Possible notif groupée/RemoteViews non standard.");
               return;
             }
            final String finalUnifiedFeed = tempUnifiedFeed;
            final String finalSourceName = sourceName;
            final long postTimeMs = sbn.getPostTime();  
             // ==================== 🔥 INTERCEPTION ET ENRICHISSEMENT FRED IMMÉDIAT ====================
            String upperCheck = finalUnifiedFeed.toUpperCase(Locale.ROOT);
            final String[][] FRED_INDICATORS = {
                // Emploi 
                {"JOBLESS CLAIMS",    "ICSA",     "K"},
                {"INITIAL CLAIMS",    "ICSA",     "K"},
                {"CHÔMAGE US",        "ICSA",     "K"},
                // Inflation
                {"CPI",               "CPIAUCSL", "%"},
                {"CONSUMER PRICE",    "CPIAUCSL", "%"},
                {"PCE",               "PCEPILFE", "%"},
                {"CORE PCE",          "PCEPILFE", "%"},
                {"PPI",               "PPIACO",   "%"},
                // Consommation / Sentiment
                {"RETAIL SALES",      "RSXFS",    "%"},
                {"VENTES AU DÉTAIL",  "RSXFS",    "%"},
                {"MICHIGAN",          "UMCSENT",  ""},
                {"CONSUMER SENTIMENT","UMCSENT",  ""},
                // PMI / ISM
                {"ISM MANUFACTUR",    "NAPM",     ""},
            };
            
            String matchedSeries = null;
            String matchedFormat = null;
            String matchedLabel  = null;
            
            for (String[] indicator : FRED_INDICATORS) {
                if (upperCheck.contains(indicator[0])) {
                    matchedSeries = indicator[1];
                    matchedFormat = indicator[2];
                    matchedLabel  = indicator[0];
                    break; // Premier match suffit
                }
            }
            
            if (matchedSeries != null) {
                final String seriesId = matchedSeries;
                final String format   = matchedFormat;
                final String label    = matchedLabel;
            
                exec.execute(new Runnable() {
                    @Override
                    public void run() {
                        Log.d(TAG, "🔄 [FRED] Requête pour " + label + " (série: " + seriesId + ")...");
                        String actualValue = EconomicCalendarAPI.fetchFredActualValue(seriesId, format);
            
                        if (actualValue != null && !actualValue.isEmpty()) {
                            long currentUnixTimestamp = System.currentTimeMillis() / 1000L;
                            boolean updated = EventDatabase.getInstance(getApplicationContext())
                                         .updateActualIfMissing(title, currentUnixTimestamp, actualValue);
                            if (updated) {
                                Log.d(TAG, "✅ [FRED] Synchronisé : " + label + " = " + actualValue);
                                if (MainActivity.instance != null) {
                                    MainActivity.instance.addLog("✅ [FRED] " + label + " → " + actualValue);
                                }
                            } else {
                                Log.d(TAG, "ℹ️ [FRED] " + label + " = " + actualValue + " — aucun événement N/A à mettre à jour.");
                            }
                        } else {
                            Log.w(TAG, "⚠️ [FRED FAILURE] Impossible d'extraire la donnée pour : " + label);
                            if (MainActivity.instance != null) {
                                MainActivity.instance.addLog("⚠️ [FRED] Échec récupération : " + label);
                            }
                        }
                    }
                });
            }
            // =========================================================================================
         // 2️⃣ BASCOULEMENT IMMÉDIAT ET ISOLÉ DANS LE PIPELINE ASYNCHRONE (THREAD D'ARRIÈRE-PLAN)
            // Utilisation de l'exécuteur existant de la classe pour stabiliser la RAM et le CPU
            tradingPipelineExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        String upperFeed = finalUnifiedFeed.toUpperCase(Locale.ROOT);
                        long currentTime = System.currentTimeMillis();
                        String currentSpeaker = "";
        
                        // Identification des Speakers de Banques Centrales
                        if (upperFeed.contains("POWELL") || upperFeed.contains("WARSH") || upperFeed.contains("WALLER") ||
                            upperFeed.contains("BARKIN") || upperFeed.contains("GOOLSBEE") || upperFeed.contains("WILLIAMS") ||
                            upperFeed.contains("KUGLER") || upperFeed.contains("BOSTIC") || upperFeed.contains("DALY") ||
                            upperFeed.contains("LOGAN") || upperFeed.contains("FED")) {
                            currentSpeaker = "FED";
                        } else if (upperFeed.contains("LAGARDE") || upperFeed.contains("SCHNABEL") || upperFeed.contains("NAGEL")) {
                            currentSpeaker = "ECB";
                        }
        
                        EconomicEventDetector.DetectedEvent detectedEvt = EconomicEventDetector.detectEvent(title, finalUnifiedFeed);
                        String eventTypeStr = detectedEvt.eventType;
                        boolean isSupremeRank = false;
    
                        // 🛡️ Préservation de TOUTE la logique géo en aval (allocation d'actifs, bypass throttle, seuils)
                        // EconomicEventDetector retourne des types granulaires (GEO-MIDDLE-EAST, GEO-EUROPE-EST,
                        // GEO-ASIA-PACIFIC) là où l'ancien code inline utilisait un seul marqueur générique "GEOPOLITICAL".
                        // On normalise ici pour ne rien casser dans le reste de la méthode.
                        boolean isGeoDetected = eventTypeStr.startsWith("GEO-")
                                || upperFeed.contains("HORMUZ") || upperFeed.contains("ORMUZ") || upperFeed.contains("IRAN") || upperFeed.contains("ISRAEL")
                                || upperFeed.contains("HEZBOLLAH") || upperFeed.contains("HOUTHI") || upperFeed.contains("GAZA") || upperFeed.contains("LEBANON")
                                || upperFeed.contains("MOYEN-ORIENT") || upperFeed.contains("MIDDLE EAST") || upperFeed.contains("WAR") || upperFeed.contains("STRIKE")
                                || upperFeed.contains("FRAPPE") || upperFeed.contains("ESCALADE") || upperFeed.contains("CONFLIT") || upperFeed.contains("MILITARY")
                                || upperFeed.contains("TAIWAN") || upperFeed.contains("UKRAINE") || upperFeed.contains("RUSSIA");
    
                        if (isGeoDetected) {
                            eventTypeStr = "GEOPOLITICAL"; // Conserve le marqueur exact attendu par toute la logique en aval
                            isSupremeRank = false;
                        } else if (upperFeed.contains("OIL") || upperFeed.contains("WTI") || upperFeed.contains("BRENT") || upperFeed.contains("CRUDE") ||
                                   upperFeed.contains("EIA") || upperFeed.contains("OPEC") || upperFeed.contains("INVENTORIES") || upperFeed.contains("PETROLE")) {
                            // Cas non couvert nommément par EconomicEventDetector → on garde le comportement historique
                            eventTypeStr = "OIL-INVENTORY";
                        }
                             String enrichedBody = EventValidator.enrichWithCalendar(title, bodyTextRaw, postTimeMs);
                        EconomicAnalyzer.EvaluationResult ecoResult = EconomicAnalyzer.analyserEvenement(title, enrichedBody);
                        Log.d(TAG, "Devise détectée : " + ecoResult.currency + ", poids : " + ecoResult.weight);
                        // Le poids n'est plus forcé à 5 ou 3 statiquement, il découle de la surprise de l'écart mathématique (1 à 4)
                        int finalCalculatedWeight = ecoResult.weight;
                        String rawImpact = detectedEvt.getRawImpact();
                        if (finalCalculatedWeight >= 3 || currentSpeaker.equals("FED") || eventTypeStr.equals("GEOPOLITICAL")
                                || "HIGH".equals(rawImpact) || "FED-WARSH-SIGNAL".equals(detectedEvt.eventType)) {
                            isSupremeRank = true;
                        }
        
                        // 4️⃣ Anti-spam / Protection contre les flux de paroles répétitifs des speakers
                        String speakerToken = currentSpeaker.trim();
                        if (!speakerToken.isEmpty()) {
                            if (!isSupremeRank && speakerToken.equals(lastSpeaker) && (currentTime - lastSpeechTime < 60000)) {
                                Log.d(TAG, "Doublon de discours filtré (" + speakerToken + ")");
                                return;
                            }
                            lastSpeechTime = currentTime;
                            lastSpeaker = speakerToken;
                        }

                        List<String> enrichedAssets = new ArrayList<>();
                        if (upperFeed.contains("JPY") || upperFeed.contains("YEN") || upperFeed.contains("BOJ")) enrichedAssets.add("USDJPY");
                        if (upperFeed.contains("GBP") || upperFeed.contains("BOE")) enrichedAssets.add("GBPUSD");
                        if (upperFeed.contains("GOLD") || upperFeed.contains("XAU")) enrichedAssets.add("GOLD");
                        if (upperFeed.contains("NASDAQ") || upperFeed.contains("TECH") || upperFeed.contains("AI")) enrichedAssets.add("NASDAQ");
                        if (upperFeed.contains("SP500") || upperFeed.contains("S&P")) enrichedAssets.add("SP500");
                        
                        if (upperFeed.contains("OIL") || upperFeed.contains("WTI") || upperFeed.contains("CRUDE") ||
                            upperFeed.contains("EIA") || upperFeed.contains("HORMUZ") || upperFeed.contains("ORMUZ")) {
                            if (!enrichedAssets.contains("USOIL")) enrichedAssets.add("USOIL");
                            if (!enrichedAssets.contains("GOLD")) enrichedAssets.add("GOLD");
                        }
                        
                        if (eventTypeStr.equals("GEOPOLITICAL")) {
                            String[] geoAssets = {"GOLD", "USOIL", "USDJPY", "NASDAQ", "SP500"};
                            for (String asset : geoAssets) {
                                if (!enrichedAssets.contains(asset)) enrichedAssets.add(asset);
                            }
                        }
                        
                        if (isSupremeRank && !eventTypeStr.equals("GEOPOLITICAL")) {
                            String[] macroAssets = {"NASDAQ", "SP500", "GOLD", "USDJPY", "GBPUSD"};
                            for (String asset : macroAssets) {
                                if (!enrichedAssets.contains(asset)) enrichedAssets.add(asset);
                            }
                        }
                        
                        if (enrichedAssets.isEmpty()) {
                            enrichedAssets.add("NASDAQ");
                            enrichedAssets.add("SP500");
                            enrichedAssets.add("GOLD");
                        }
                        
                        EventValidator.ValidationResult validationResult = EventValidator.validate(NotificationService.this, title, bodyTextRaw + " [" + finalSourceName + "]", currentTime, enrichedAssets);
                        
                        // Log dans l'Ui
                        if (MainActivity.instance != null) {
                        MainActivity.instance.addLog(finalSourceName + ": " + (validationResult.isConfirmed ? "CONFIRMÉ" : "REJETÉ") + " - " + validationResult.reason);
                        }
                        // Coupe-circuit du Validateur : On bloque les doublons temporels, sauf s'il s'agit d'un choc absolu de poids 4
                        if (validationResult != null && !validationResult.isConfirmed) {
                        // Cas particulier : inertie macro (driver déjà actif) → on envoie un rappel Telegram
                        if (validationResult.isInertiaBlock) {
                         // qui est plus stable que celui de EventValidator.
                        String inertiaKeySource;
                        if (eventTypeStr != null && !eventTypeStr.isEmpty()) {
                            inertiaKeySource = eventTypeStr;
                        } else if (validationResult.detectedTypeForInertia != null && !validationResult.detectedTypeForInertia.isEmpty()) {
                            inertiaKeySource = validationResult.detectedTypeForInertia;
                        } else {
                            inertiaKeySource = "UNKNOWN";
                        }
                              String inertiaPrefKey = "inertia_reminder_" + inertiaKeySource;
                            long nowMs = System.currentTimeMillis();
                           Long previousMemory = lastInertiaReminderSentMemory.putIfAbsent(inertiaPrefKey, nowMs);
                            if (previousMemory != null) {
                                if ((nowMs - previousMemory) < INERTIA_REMINDER_COOLDOWN_MS) {
                                    Log.d(TAG, "[RAPPEL] Driver " + inertiaKeySource + " déjà rappelé (verrou mémoire), ignoré.");      
                                    return;
                                }
                                // Cooldown mémoire expiré : on met à jour la réservation
                                lastInertiaReminderSentMemory.put(inertiaPrefKey, nowMs);
                            }

                            // 🛡️ Étape 2 : SharedPreferences — protection de fond contre les
                            // redémarrages de processus Android (la mémoire seule ne survit pas).
                            android.content.SharedPreferences inertiaPrefs =
                                getSharedPreferences("TradingBotPrefs", MODE_PRIVATE);
                            long lastSentForType = inertiaPrefs.getLong(inertiaPrefKey, 0L);
                            if (lastSentForType != 0L && (nowMs - lastSentForType) < INERTIA_REMINDER_COOLDOWN_MS) {
                                Log.d(TAG, "[RAPPEL] Driver " + inertiaKeySource + " déjà rappelé récemment (préférences), ignoré.");
                                return; // On arrête le traitement normal sans renvoyer Telegram
                            }
                            inertiaPrefs.edit().putLong(inertiaPrefKey, nowMs).apply();

                            // Ne pas envoyer le rappel si l'impact précédent est vide, neutre ou insignifiant
                            String lastImpact = validationResult.lastEventSummary;
                            boolean impactInsignifiant = lastImpact == null
                                || lastImpact.isEmpty()
                                || lastImpact.contains("Filtré – tous les actifs neutres")
                                || lastImpact.contains("Filtré – conviction trop faible")
                                || lastImpact.contains("FAILED_FALLBACK")
                                || lastImpact.contains("Historique — impact non disponible");
                            if (impactInsignifiant) {
                                Log.d(TAG, "[RAPPEL] Impact précédent insignifiant — rappel ignoré.");
                                return;
                            }
                            String reminderMsg = "⏳ *RAPPEL : DRIVER DÉJÀ ACTIF*\n" +
                                                 "🔹 " + validationResult.reason + "\n\n" +
                                                 "📋 *Dernier événement similaire :*\n" +
                                                 lastImpact;
                            sendTelegramSecure(reminderMsg, NotificationService.this);
                                                        Log.d(TAG, "[RAPPEL] Driver actif : rappel envoyé.");
                                                        return; // On arrête le traitement normal
                                                    }
                                                    // Pour les autres cas de rejet (doublon, rumeur, faible confiance, etc.)
                                                    if (finalCalculatedWeight < 4) {
                                                        Log.d(TAG, "[COUPE-CIRCUIT TIMING] Événement rejeté : " + validationResult.reason);
                                                        return;
                                                    }
                        }
    
                     // APRÈS (remplace le bloc déjà patché précédemment)
                    boolean isConfirmedCalendarDrop = validationResult != null && validationResult.isCalendarIntercept;
                    if (finalCalculatedWeight < 3 && !eventTypeStr.equals("GEOPOLITICAL") && !isConfirmedCalendarDrop) {
                        String dropMsg = "[COUPE-CIRCUIT MACRO] " + finalSourceName + " — \"" + title + "\" rejeté (poids=" + finalCalculatedWeight + ", type=" + eventTypeStr + ")";
                        Log.d(TAG, dropMsg);
                        if (MainActivity.instance != null) MainActivity.instance.addLog("⚪ " + dropMsg);
                        return;
                    }
                    if (isConfirmedCalendarDrop && finalCalculatedWeight < 3) {
                        Log.d(TAG, "🟢 [BYPASS CALENDRIER] Sauvegarde forcée malgré poids=" + finalCalculatedWeight + " (Interception Calendrier Macro confirmée).");
                    }
                    // 8️⃣ Génération de la signature cryptographique et persistance SQLite en base de données
                    String fingerprint = generateSecureHash(packageName + "_" + title + "_" + bodyTextRaw + "_" + (postTimeMs / 60000));
    
                    StringBuilder assetsSb = new StringBuilder();
                    for (int i = 0; i < enrichedAssets.size(); i++) {
                        assetsSb.append(enrichedAssets.get(i));
                        if (i < enrichedAssets.size() - 1) assetsSb.append(",");
                    }
    
                    // Sauvegarde de l'événement en base de données persistante avec injection du vrai poids macro calculé
                    boolean saved = eventDb.saveEvent(
                            fingerprint, packageName, finalSourceName, eventTypeStr, title, bodyTextRaw,
                            assetsSb.toString(), "pending", postTimeMs / 1000, "pending", finalCalculatedWeight);
    
                     if (saved) {
                     Log.i(TAG, "[DATABASE] Match " + eventTypeStr + " enregistré avec succès. Poids affecté : " + finalCalculatedWeight);
                       if (MainActivity.instance != null) {
                       MainActivity.instance.addLog("💾 [DB] " + finalSourceName + " enregistré (" + eventTypeStr + ", poids " + finalCalculatedWeight + ") — \"" + title + "\"");
                       }
                    }
                      else {
                      Log.w(TAG, "[DATABASE] ⚠️ ÉCHEC saveEvent pour '" + title + "' (fingerprint=" + fingerprint + ") — doublon déjà connu ou erreur SQLite.");
                      }
                        // 9️⃣ Enrichissement dynamique et forcé du Prompt Système IA avec les flèches théoriques de l'analyseur
// 9️⃣ Enrichissement dynamique du Prompt Système IA
// 9️⃣ ENRICHISSEMENT MARKET DATA & PROMPT IA (Pipeline Intégré)
// 📋 IA (Pipeline Intégré) : Préparation du Snapshot Marché Temps Réel
// ✅ CORRECTION : renommer la variable String pour éviter le conflit avec la Map
String marketSnapshotString = "Marché non analysé.";
// ✅ Aligné sur le modèle interne de TradingViewFetcher
java.util.Map<String, TradingViewFetcher.MarketData> batchSnapshot = null;
try {
    // Filtrer uniquement les 6 actifs Twelve Data parmi enrichedAssets
    List<String> twelveFiltered = new ArrayList<>();
    for (String a : enrichedAssets) {
        // Limiter aux 4 actifs core — évite les batches supplémentaires sur événements riches
        if (MARKET_PRICE_ASSETS.contains(a)) twelveFiltered.add(a);
        if (twelveFiltered.size() >= 4) break; // Cap strict à 4 actifs = 1 batch max
    }
    // ✅ tryAcquireBatchSlot via TradingViewFetcher
    if (!twelveFiltered.isEmpty() && TradingViewFetcher.tryAcquireBatchSlot()) {
        batchSnapshot = TradingViewFetcher.getMarketDataBatch(twelveFiltered);
    } else {
        Log.w(TAG, "[BATCH] Slot occupé ou aucun actif Twelve Data — cache LKV utilisé");
    }
    if (batchSnapshot != null && !batchSnapshot.isEmpty()) {
        StringBuilder sb = new StringBuilder("Données de marché (Live Batch) : ");
        boolean premierActif = true;

        // ✅ Utilisation du type de données TradingViewFetcher pour l'itération du snapshot
        for (java.util.Map.Entry<String, TradingViewFetcher.MarketData> entry : batchSnapshot.entrySet()) {
            TradingViewFetcher.MarketData mData = entry.getValue();
            
            // 🛡️ MULTI-PROTECTION : Filtrage préventif des valeurs nulles ou prix aberrants (<= 0)
            if (mData == null || mData.price <= 0) {
                Log.w(TAG, "Snapshot - Actif ignoré car données invalides ou nulles : " + entry.getKey());
                continue; 
            }

            // ✂️ SUPPRESSION DU SÉPARATEUR DE FIN : Ajouté uniquement avant les éléments suivants
            if (!premierActif) {
                sb.append(" | ");
            }
            premierActif = false;

            String sign = (mData.changePercent >= 0) ? "+" : "";
            String emojiVariation = (mData.changePercent > 0) ? "🟢"
                : (mData.changePercent < 0) ? "🔴" : "⚪";
            sb.append(entry.getKey())
              .append(" => ")
              .append(String.format(Locale.US, "%.4f (%s%.2f%% %s)", mData.price, sign, mData.changePercent, emojiVariation));
        }

        // Vérification finale au cas où TOUS les actifs auraient été rejetés par le filtre de sécurité
        if (premierActif) {
            marketSnapshotString = "Données de marché indisponibles (aucun prix valide extrait).";
        } else {
            marketSnapshotString = sb.toString();
        }
    } else {
        marketSnapshotString = "Données de marché indisponibles (Twelve Data hors-ligne ou limite atteinte).";
    }
} catch (Exception e) {
    Log.e(TAG, "Échec de la génération du snapshot marché", e);
    marketSnapshotString = "Erreur technique lors de l'acquisition des données.";
}

// Construction du prompt enrichi (utilise la variable String renommée)
String baseSystemPrompt = SYSTEM_PROMPT;
String promptAI = "📊 [CONTEXTE MARCHÉ ACTUEL] : " + marketSnapshotString + "\n\n" + baseSystemPrompt;

if (ecoResult.isParsed) {
    promptAI = "⚠️ [GUIDAGE MATRICIEL INTERNE] : \n" +
            "L'analyseur mathématique déterministe a détecté un écart type. " +
            "Direction recommandée : " + ecoResult.directionText + "\n\n" + promptAI;
}
// 🛡️ THROTTLE GÉO CIBLÉ (12 min) — seul angle mort non couvert par l'inertie macro 2h,
// qui exclut volontairement les types GEO-* (cf. EventValidator.validate(), ÉTAPE 4).
// Remplace l'ancien GEO_THROTTLE_MS mort de processIncomingMacroFeed(), sans réimporter
// le reste de sa mécanique (rollback, etc.) devenue inutile face aux 3 autres garde-fous actifs.
if (eventTypeStr.equals("GEOPOLITICAL")) {
    if (!isSupremeRank && (currentTime - lastGeoTime < GEO_THROTTLE_MS)) {
        Log.d(TAG, "[THROTTLE GÉO] Notification bloquée (12 min) - dernier choc géo il y a "
                + (currentTime - lastGeoTime) / 1000 + "s");
        return;
    }
    lastGeoTime = currentTime;
}
// 🔟 Exécution finale de l'analyse cognitive LLM
// Réutilise batchSnapshot déjà récupéré ligne 1103 — 0 appel réseau supplémentaire
boolean forceSend = isSupremeRank || (validationResult != null && validationResult.isCalendarIntercept);
processAnalysisWithAI(finalSourceName, title, bodyTextRaw, enrichedAssets, fingerprint, promptAI, forceSend, batchSnapshot);
                   } catch (Exception e) {
                        Log.e(TAG, "Erreur critique au sein de l'exécution asynchrone de la pipeline", e);
                    }
                }
                });
            }
           /**
     * Retourne true uniquement pendant les sessions actives
     * (Londres 08h-17h UTC ou New York 13h30-21h UTC).
     * Évite les appels Twelve Data inutiles la nuit ou le week-end.
     */
    private static boolean isMarketHours() {
        java.util.Calendar utc = java.util.Calendar.getInstance(
            java.util.TimeZone.getTimeZone("UTC"));
        int dow  = utc.get(java.util.Calendar.DAY_OF_WEEK);
        int hour = utc.get(java.util.Calendar.HOUR_OF_DAY);
        int min  = utc.get(java.util.Calendar.MINUTE);
        int totalMin = hour * 60 + min;
    
        // Week-end → fermé
        if (dow == java.util.Calendar.SATURDAY ||
            dow == java.util.Calendar.SUNDAY) return false;
    
        // Session Londres  : 08h00–17h00 UTC (480–1020 min)
        // Session New York : 13h30–21h00 UTC (810–1260 min)
        return (totalMin >= 480 && totalMin <= 1020) ||
               (totalMin >= 810 && totalMin <= 1260);
       }
    
          public static void sendToGroqAndTelegram(String source, String title, String body, List<String> assets, Context context) {
            if (context == null) return;
            String fingerprint = String.valueOf((source + title + body).hashCode());
            NotificationService instance = serviceInstance;
                 List<String> finalAssetsList = (assets != null && !assets.isEmpty()) ? assets : new ArrayList<>(Arrays.asList(
                "GOLD","NASDAQ","SP500",
                "USDJPY","GBPUSD","USOIL"
            ));
            // Conversion de la Liste en String pour correspondre au schéma SQLite
            String assetsStr = String.join(",", finalAssetsList);
                EventDatabase db = (instance != null) ? instance.getEventDb() : null;
            if (db != null) {
                if (!db.isEventAlreadySaved(title, System.currentTimeMillis() / 1000)) {
                    int dynamicWeight = EconomicCalendarAPI.isSupremeCalendarIndicator(title) ? 5 : 3;
                    db.saveEvent(
                        fingerprint, "com.tradingbot.calendar", source,
                        "CALENDAR-RESULT", title, body, assetsStr,
                        "pending", System.currentTimeMillis() / 1000,
                        "pending", dynamicWeight
                    );
                }
            } else {
                Log.w(TAG, "[sendToGroqAndTelegram] Sauvegarde DB ignorée (service/DB indisponible) pour : " + title);
            }
           // 🚀 INJECTION : Déportation réseau simplifiée et ultra-rapide via Batch API
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
        StringBuilder blocPrix = new StringBuilder();
    
        // Déclaré ici — accessible partout dans le try ET le catch
        java.util.Map<String, TradingViewFetcher.MarketData> batchPrices = null;
    
        if (assets != null && !assets.isEmpty()) {
        blocPrix.append("\n\n📊 *COURS INSTANTANÉS AU MOMENT DE L'IMPACT :*");

        // Filtrer uniquement les 6 actifs Twelve Data — assets complet va à Groq
        List<String> twelveAssets = new ArrayList<>();
        for (String asset : assets) {
            if (MARKET_PRICE_ASSETS.contains(asset)) {
                twelveAssets.add(asset);
            }
        }
    
        if (!twelveAssets.isEmpty()) {
        // tryAcquireBatchSlot() est synchronized — un seul thread obtient le slot
        if (TradingViewFetcher.tryAcquireBatchSlot()) {
            batchPrices = TradingViewFetcher.getMarketDataBatch(twelveAssets);
            if (batchPrices == null) batchPrices = new java.util.HashMap<>();
        } else {
            // Slot occupé — utilise le cache LKV existant sans appel réseau
            Log.w(TAG, "[TV DATA] Slot occupé — cache LKV utilisé pour ce flux");
            batchPrices = new java.util.HashMap<>();
        }
            for (String asset : twelveAssets) {
                TradingViewFetcher.MarketData data = batchPrices.get(asset);
                if (data != null && data.price > 0) {
                    // 🛡️ HARMONISATION : 🟢/🔴/⚪ remplace 📈/📉 pour rester cohérent avec
                    // les autres points d'affichage de prix (choc macro, snapshot Daily,
                    // injectLivePrices) — un seul code visuel partout dans le bot.
                    String tendance = (data.changePercent > 0) ? "🟢"
                        : (data.changePercent < 0) ? "🔴" : "⚪";
                    String formatPrix = (data.price > 1000) ? "\n%s %s : *%,.2f* (%+.2f%%)" : "\n%s %s : *%.5f* (%+.2f%%)";
                    blocPrix.append(String.format(Locale.US, formatPrix, tendance, asset, data.price, data.changePercent));
                } else {
                    blocPrix.append("\n🔸 ").append(asset).append(" : (Cours indisponible)");
                }
            }
        }
       }
    
        String bodyEnrichi = body + blocPrix.toString();
    
        if (instance != null) {
            // batchPrices réutilisé — 0 appel réseau supplémentaire
            instance.processAnalysisWithAI(source, title, bodyEnrichi, assets, fingerprint, SYSTEM_PROMPT, true, batchPrices);
        } else {
            String msg = "📅 *RÉSULTAT CALENDAIRE*\n📌 *" + title + "*\n📊 " + bodyEnrichi;
            sendTelegramSecure(msg, context);
        }
    
    } catch (Exception e) {
        Log.e(TAG, "Erreur critique lors de l'enrichissement par Batch API", e);
        if (instance != null) {
            // batchPrices peut être null si exception avant son initialisation — cachedMarketData=null est géré dans processAnalysisWithAI
            instance.processAnalysisWithAI(source, title, body, assets, fingerprint, SYSTEM_PROMPT, true, null);
        } else {
            String msg = "📅 *RÉSULTAT CALENDAIRE*\n📌 *" + title + "*\n📊 " + body;
            sendTelegramSecure(msg, context);
        }
    }
            }
    }).start();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundServiceNotification();
        eventDb = EventDatabase.getInstance(this);
        
        // ── Liaison du contexte pour l'extraction de la clé macro_api_key ──
        EconomicCalendarAPI.init(this);
        EventValidator.setAppContext(this); 
        serviceInstance = this;
        // Ajouter dans onCreate() après ligne 1751 :
        TradingViewFetcher.start(this);
        // 🛡️ Restaurer compteur tokens depuis SharedPreferences
        SharedPreferences tokenPrefs = getSharedPreferences("TradingBotPrefs", MODE_PRIVATE);
        long savedResetTime = tokenPrefs.getLong("token_reset_time", 0L);
        int savedTokens = tokenPrefs.getInt("daily_tokens_used", 0);
        long nowInit = System.currentTimeMillis();
        if (nowInit < savedResetTime) {
            dailyTokensUsed.set(savedTokens);
            tokenResetTime = savedResetTime;
            Log.i(TAG, "[TOKEN] Compteur restauré : " + savedTokens + " tokens utilisés.");
        } else {
            dailyTokensUsed.set(0);
            tokenResetTime = (nowInit / 86400000L + 1) * 86400000L;
            Log.i(TAG, "[TOKEN] Nouveau jour — compteur remis à zéro.");
    }                 // ✅ Assure la survie de l'instance pour l'IA
        
        // ── Déportation du préchargement réseau dans un thread d'arrière-plan ──
    new Thread(new Runnable() {
    @Override
    public void run() {
    try {
                // Guard : évite le double appel avec BACKFILL qui se déclenche
                // quasi simultanément via registerNetworkCallback().onAvailable()
                long now = System.currentTimeMillis();
                if (now - lastCalendarBackfillMillis < CALENDAR_BACKFILL_GUARD_MS) {
                    Log.d(TAG, "[SERVICE] Préchargement initial ignoré — BACKFILL déjà actif.");
                    return;
                }
                lastCalendarBackfillMillis = now;
                EventValidator.preloadCalendar();
            } catch (Exception e) {
                Log.e(TAG, "[SERVICE] Erreur lors du préchargement du calendrier", e);
            }
        }
        }).start();
    
        createNotificationChannel();
        //startDailyBriefScheduler();
        //startMonthlyReportScheduler();
        registerNetworkCallback();
        
        // Planification unifiée : purge SQLite + nettoyage RAM + préchargement toutes les 24h à Minuit (Madagascar)
        long initialDelayMillis = calculateMillisUntilNextMadaMidnight();
        long period24HoursMillis = 24 * 60 * 60 * 1000L; 
    
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if (isSyncing) return;
                isSyncing = true;
                try {
                    Log.d(TAG, "🕒 [MAINTENANCE] Déclenchement automatique de minuit (Heure de Madagascar)...");
                    
                    // 1. Purge SQLite des événements ayant dépassé la fenêtre de validité de 48 heures
                    // 1. Purge SQLite à deux vitesses (bruit 48h / piliers macro 45j) — purgeOldEvents calcule
                    // lui-même les deux seuils en interne à partir du timestamp courant, donc on lui passe "now" brut.
                    long nowSeconds = System.currentTimeMillis() / 1000;
                    eventDb.purgeOldEvents(nowSeconds);
                    Log.d(TAG, "[MAINTENANCE] Base de données SQLite purgée (bruit > 48h, piliers > 45j).");
                    // 2. Nettoyage de la table des empreintes pour éviter les fuites RAM
                    EventValidator.cleanupOldFingerprints();
                    Log.d(TAG, "[MAINTENANCE] Table des empreintes mémoires RAM nettoyée.");
    
                    // 3. Re-synchronisation du calendrier pour la nouvelle journée
                    EventValidator.preloadCalendar();
                    lastAlertsSent.clear();
                    Log.d(TAG, "[MAINTENANCE] Cooldowns d'alertes réinitialisés.");
                    
                } catch (Exception e) {
                    Log.e(TAG, "[MAINTENANCE] Erreur lors de la maintenance à minuit", e);
                } finally {
                    isSyncing = false;
                }
            }
        }, initialDelayMillis, period24HoursMillis, TimeUnit.MILLISECONDS);
         // ✅ NOUVEAU (remplace le 6h)
        long fifteenMinutesMillis = 15 * 60 * 1000L;
       scheduler.scheduleAtFixedRate(new Runnable() {
       @Override
       public void run() {
        try {
            EventValidator.preloadCalendar();
        } catch (Exception e) {
            Log.e(TAG, "[CALENDAR] Erreur refresh 15min", e);
        }
        }
        }, fifteenMinutesMillis, fifteenMinutesMillis, TimeUnit.MILLISECONDS);
        
    scheduler.scheduleAtFixedRate(new Runnable() {
    @Override
    public void run() {
        try {
            // 🛡️ Guard : appel Twelve Data uniquement si lastForecast
            // contient des données ET si on est en heure de marché active
            if (lastForecast.isEmpty()) return; // Rien à surveiller → 0 appel réseau
            if (!isMarketHours()) return;        // Hors session → 0 appel réseau
            
            checkForecastDivergence();
        } catch (Exception e) {
            // 🚨 SÉCURITÉ : Capture impérative pour éviter que le scheduler ne s'arrête à tout jamais
            Log.e(TAG, "⚠️ Échec critique lors du check de divergence de prévisions (Scheduler 15min)", e);
            
            // Optionnel : pousser le log sur l'interface utilisateur si l'instance est disponible
            if (MainActivity.instance != null) {
                try {
                    MainActivity.instance.addLog("❌ Erreur de surveillance macro : " + e.getMessage());
                } catch (Exception ignored) {
                    // Évite un crash en cascade si l'UI est instable à ce moment précis
                }
            }
        }
    }
      }, 15, 15, TimeUnit.MINUTES);
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
        // 🟢 1. Récupération sécurisée des événements historiques (7 jours)
        List<EconomicCalendarAPI.CalendarEvent> historicalEvents = EconomicCalendarAPI.fetchHistoricalEvents(this, 7);
        
        // 🟢 2. Initialisation du StringBuilder qui manquait au compilateur
        StringBuilder apiMacroBlock = new StringBuilder();
        
        for (EconomicCalendarAPI.CalendarEvent e : historicalEvents) {
            if (e == null) continue;
        
            // Vérification des critères d'impact et de publication des chiffres
            if (e.importance != null && e.importance.equalsIgnoreCase("HIGH") && e.actual != null && e.forecast != null) {
                
                String dateStr = e.timestamp; 
                String countryOrCurrency = (e.country != null) ? e.country : "USD";
                
                // 🟢 FIX : Remplacement de logToMain par le Log d'Android standard
                Log.i("NotificationService", String.format("High Impact Event: %s, Region: %s, Event: %s, Act: %s, Fcst: %s",
                        dateStr, countryOrCurrency, e.indicator, e.actual, e.forecast));
        
                // Formatage de la ligne pour le bloc macro envoyé à Groq
                apiMacroBlock.append("- ")
                            .append(e.indicator)
                            .append(" (").append(countryOrCurrency).append(") | ")
                            .append("Actual: ").append(e.actual).append(" | ")
                            .append("Forecast: ").append(e.forecast).append("\n");
            }
        }
        
        // 🟢 3. Envoi groupé à l'analyse Groq si des données sont présentes
        if (apiMacroBlock.length() > 0) {
            dispatchHistoricalBulkToGroq(apiMacroBlock.toString());
        }
        if (apiMacroBlock.length() > 0) {
            dispatchHistoricalBulkToGroq(apiMacroBlock.toString());
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
                    "GOLD: 🏆, USOIL: 🛢️, NASDAQ: 💻, SP500: 📊, " +
                    "GBPUSD: 🇬🇧, USDJPY: 🇯🇵";
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

                      boolean isInclinationNeutral = upperLine.contains("MAIS NEUTRE");
                      boolean isSignificant = !isInclinationNeutral && (upperLine.contains("BULLISH") || upperLine.contains("BEARISH"));

                      if (isSignificant) {
                          filteredMessage.append(line).append("\n");
                          activeSignalsCount++;
                      }
                      // Ignorer les NEUTRE pour Telegram
                  }
              }
                // ====================== ENVOI TELEGRAM ======================
    if (activeSignalsCount > 0) {
    // Extraction précise du pourcentage de conviction depuis aiResult
    int convictionPercent = extrairePourcentageConviction(aiResult);
    boolean isSupremeRank = estEvenementSuprême(feed); // feed = le texte brut de l'événement

    // Seuil : conviction >= 40% OU événement de Rang Suprême (Fed, CPI, NFP, etc.)
    if (convictionPercent >= 40 || isSupremeRank) {
        String finalPayload = "⚡ *ANALYSE  MACRO ÉCONOMIQUES Pipeline*\n"
                + "🕒 " + timeString + " (Mada)\n"
                + "📡 Source : " + source + "\n"
                + filteredMessage.toString().trim();

        if (finalPayload.length() < 200) {
            eventDb.markEventAsSynced(fingerprint, "TOO_SHORT");
            return true;
        }

        Log.d(TAG, "📤 Envoi Telegram pour fingerprint=" + fingerprint + ", signaux impactants=" + activeSignalsCount);
        
        if (MainActivity.instance != null) {
            MainActivity.instance.addLog(source + ": Envoi Telegram " + fingerprint);
        }
        sendTelegramSecure(finalPayload, this);
        lastAnalysisTime = System.currentTimeMillis();
        if (isGeoEvent) { lastGeoTime = System.currentTimeMillis(); }
        eventDb.markEventAsSynced(fingerprint, "PROCESSED_OK");
        
        // Enregistrement des prévisions pour le moniteur de divergence
        captureForecastFromReport(aiResult); 
        return true;
        } else {
            Log.d(TAG, "Conviction trop faible (" + convictionPercent + "%) et non suprême → message ignoré");
            eventDb.markEventAsSynced(fingerprint, "LOW_CONVICTION_FILTERED");
            return true;
        }
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
                "GOLD", "NASDAQ", "SP500",
                "USDJPY", "GBPUSD"
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

        if (upper.contains("GBP")    || upper.contains("GBPUSD") ||
            upper.contains("CABLE")  || upper.contains("BOE")    ||
            upper.contains("BAILEY")) assets.add("GBPUSD");

         
        if (upper.contains("JPY")    || upper.contains("USDJPY") ||
            upper.contains("YEN")    || upper.contains("BOJ")    ||
            upper.contains("UEDA")) assets.add("USDJPY");

        // Point 8 : Fallback minimal restreint et pertinent au lieu du bloc massif par défaut
        if (assets.isEmpty()) {
            assets.add("NASDAQ");
            assets.add("SP500");
            assets.add("GOLD");
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
    public void startDailyBriefScheduler() {
    TimeZone tz = TimeZone.getTimeZone("Indian/Antananarivo"); // ✅ cohérent partout
    // 🛡️ CORRECTIF DAILY REPORT : les horaires {7,8,9,12,13,16,17} ne correspondaient
    // pas aux horaires prévus pour le bot ({7,12,15,19,22} heure Madagascar), ce qui
    // empêchait l'envoi du rapport aux moments attendus.
    int[] targetHours = {7, 12};
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
                scheduler.execute(() -> {
                boolean sent = generateAndSendDailyBrief();
                if (sent) {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putString(prefKey, today)
                        .apply();
                } else {
                    Log.w(TAG, "[DAILY] Rattrapage " + targetHour + "h non confirmé — nouvelle tentative au prochain cycle");
                }
            });
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
    // 4. Planification unique (pas de récursion)
    scheduler.schedule(() -> {
        String currentDay = dayFormat.format(Calendar.getInstance(tz).getTime());
        String prefKey = PREF_LAST_DAILY_REPORT + targetHour;
        String lastSent = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(prefKey, "");
        if (!currentDay.equals(lastSent)) {
              boolean sent = generateAndSendDailyBrief();
            if (sent) {
                getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(prefKey, currentDay)
                    .apply();
            } else {
                Log.w(TAG, "[DAILY] Envoi " + targetHour + "h non confirmé — le créneau reste disponible");
            }
        } else {
            Log.d(TAG, "[DAILY] Rapport déjà envoyé aujourd'hui pour " + targetHour + "h, ignoré");
        }
        // 5. Replanifier pour le lendemain à la même heure
        scheduleDailyBriefAt(targetHour, tz);
    }, delay, TimeUnit.MILLISECONDS);
   }
    
   public boolean generateAndSendDailyBrief() {
    HttpURLConnection conn = null;
    try {
        String apiKey = getGroqApiKey();
        if (apiKey.isEmpty()) {
            Log.w(TAG, "[DAILY] Clé API Groq absente — rapport annulé.");
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("❌ [DAILY] Clé API Groq absente — vérifie l'onglet Clés API.");
            }
            return false;
        }

        long nowSec = System.currentTimeMillis() / 1000;
        String dailyDrivers = eventDb.getDailyMacroSummary(nowSec);

        // Date locale pour le message (Mada UTC+3)
        SimpleDateFormat sdfDate = new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE);
        sdfDate.setTimeZone(TimeZone.getTimeZone("Indian/Antananarivo"));
        String dateStr = sdfDate.format(Calendar.getInstance(TimeZone.getTimeZone("Indian/Antananarivo")).getTime());

           if (dailyDrivers == null || dailyDrivers.trim().isEmpty()) {
            Log.w(TAG, "[DAILY] Aucun driver macro trouvé pour les dernières 24h.");
    
            // Éviter les envois multiples : utiliser SharedPreferences avec un délai
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            long lastReminderTime = prefs.getLong("last_empty_db_reminder", 0);
            long now = System.currentTimeMillis();
            if (now - lastReminderTime > 60 * 60 * 1000) { // 1 heure
                prefs.edit().putLong("last_empty_db_reminder", now).apply();
        
                String dernierDriverConnu = eventDb.obtenirLeToutDernierDriver();
                if (dernierDriverConnu != null && !dernierDriverConnu.trim().isEmpty()) {
                    String messageRappel = "⚠️ *[RAPPEL : AUCUN NOUVEAU DRIVER DEPUIS 24H]*\n" +
                                           "🕒 Rapport périodique du " + dateStr + " (Mada)\n\n" +
                                           "Le flux de collecte n'a détecté aucun nouveau catalyseur macroéconomique.\n" +
                                           "Voici le dernier état de marché enregistré à titre de rappel pour l'équipe :\n\n" +
                                           dernierDriverConnu;
                    sendTelegramSecure(messageRappel, this);
                } else {
                    sendTelegramSecure("⚪ *RAPPEL SYSTEME :* Base de données entièrement vide. Aucun historique macroéconomique disponible.", this);
                }
            } else {
                Log.d(TAG, "[DAILY] Rappel déjà envoyé récemment, ignoré.");
            }
            return false;
        }
        // =========================================================================

        Log.d(TAG, "[DAILY] " + dailyDrivers.length() + " caractères de données à analyser");

        // PROMPT SYSTEME STRUCTURÉ DU BRIEFING DAILY (S'exécute uniquement si dailyDrivers n'est pas vide)
                // Utilisation du prompt compact V3 pour le Daily Report
        String baseSystemPrompt = DAILY_SYSTEM_PROMPT;
        // Ajout de la directive de crise géopolitique si nécessaire
        baseSystemPrompt = construirePromptQuotidienSystem(dailyDrivers, baseSystemPrompt);

        // Récupération de la mémoire d'inertie du marché (SharedPreferences)
        SharedPreferences prefs = getSharedPreferences("TradingBotPrefs", MODE_PRIVATE);
        String lastDominantFlow = prefs.getString("last_daily_flow", "NEUTRE / PRUDENCE RECOMMANDÉE");
                    String systemPromptFinal = "CONTEXTE HIER (INERTIE DE MARCHÉ) : Le flux dominant de la veille était : " + lastDominantFlow + ".\n" +
                        "Si les événements actuels ne contredisent pas ce flux de manière écrasante (>70% de conviction), conserve-le pour éviter les faux signaux.\n\n" +
                        baseSystemPrompt + "\n\n" +
                        "Tu es un expert en macroéconomie. Tu dois rédiger ton rapport en terminant obligatoirement par la ligne suivante formatée de cette exacte façon :\n" +
                        "🏁 FLUX DOMINANT : [Insère ici le flux sélectionné]";
                            JSONObject payload = new JSONObject();
                    int usedD = dailyTokensUsed.addAndGet(2000);
                    getSharedPreferences("TradingBotPrefs", MODE_PRIVATE).edit()
                        .putInt("daily_tokens_used", usedD)
                        .putLong("token_reset_time", tokenResetTime)
                        .apply();
                    payload.put("model", usedD > TOKEN_BUDGET_DAILY ? GROQ_MODEL_FALLBACK : GROQ_MODEL);
                    if (usedD > TOKEN_BUDGET_DAILY && MainActivity.instance != null)
                        MainActivity.instance.addLog("⚠️ [DAILY] Budget token atteint — modèle léger.");
                    payload.put("temperature", 0.02);
                    
                    JSONArray messages = new JSONArray();
                    messages.put(new JSONObject().put("role", "system").put("content", systemPromptFinal));
                            // ✅ Snapshot marché injecté dans le daily comme dans le pipeline news live
                    
                    String dailyMarketSnapshot = "Données de marché indisponibles.";
                    try {
                             // Prix depuis WebSocket TradingView — remplace MarketDataFetcher
                    StringBuilder sbM = new StringBuilder("📊 COURS AU MOMENT DU RAPPORT :\n");
                    boolean hasData = false;
Map<String, TradingViewFetcher.TVMarketData> tvSnap = TradingViewFetcher.getCache();
if (!tvSnap.isEmpty()) {
    for (String asset : MARKET_PRICE_ASSETS) {
        TradingViewFetcher.TVMarketData d = tvSnap.get(asset);
        if (d != null && d.price > 0) {
            String sign = d.changePercent >= 0 ? "+" : "";
            String emojiVariation = (d.changePercent > 0) ? "🟢"
                : (d.changePercent < 0) ? "🔴" : "⚪";
            sbM.append(asset).append(" => ")
               .append(String.format(Locale.US, "%.4f (%s%.2f%% %s)",
                   d.price, sign, d.changePercent, emojiVariation));
            // 🏛️ Niveaux pivots natifs TradingView (Daily/Weekly), affichés seulement s'ils sont chargés
            if (d.pdh > 0 || d.pdl > 0) {
                sbM.append(" | PDH=").append(String.format(Locale.US, "%.4f", d.pdh))
                   .append(" PDL=").append(String.format(Locale.US, "%.4f", d.pdl));
                if (d.brokeAbovePDH) sbM.append(" 🔺PDH");
                else if (d.brokeBelowPDL) sbM.append(" 🔻PDL");
            }
            if (d.pwh > 0 || d.pwl > 0) {
                sbM.append(" | PWH=").append(String.format(Locale.US, "%.4f", d.pwh))
                   .append(" PWL=").append(String.format(Locale.US, "%.4f", d.pwl));
                if (d.brokeAbovePWH) sbM.append(" 🚀PWH");
                else if (d.brokeBelowPWL) sbM.append(" 🔥PWL");
            }
            sbM.append("\n");
            hasData = true;
                                }
                            }
                        }
                    
                        if (hasData) {
                            dailyMarketSnapshot = sbM.toString();
                        }
                    
                    } catch (Exception e) {
                        Log.w(TAG, "[DAILY] Snapshot marché indisponible : " + e.getMessage());
                    }
                    String currentFlow = getSharedPreferences("TradingBotPrefs", MODE_PRIVATE)
                        .getString("last_dominant_flow", "INDÉTERMINÉ");
                    messages.put(new JSONObject().put("role", "user").put("content",
                        "Génère le rapport périodique pour la date/heure : " + dateStr + " (Mada).\n\n" +
                        "⚡ FLUX DOMINANT ACTUEL (dernière analyse live) : " + currentFlow + "\n" +
                        "─────────────────────────────\n" +
                        dailyMarketSnapshot + "\n" +
                        "─────────────────────────────\n" +
                        "DONNÉES BRUTES DES DERNIÈRES 24H :\n" + dailyDrivers + "\n" +
                        "─────────────────────────────\n\n" +
                        "⚠️ INSTRUCTION SPÉCIALE : Identifie dans le texte la source de chaque événement (Bloomberg, FinancialJuice, etc.) " +
                        "et applique la RÈGLE 12 (Pondération des sources et comptage des signaux).\n" +
                        "Tu dois impérativement fournir dans le rapport le nombre d'événements en faveur de RISK-OFF et RISK-ON.\n" +
                        "Si plus de 60% du poids penche vers RISK-OFF, le FLUX DOMINANT doit refléter cette majorité écrasante."));
                            payload.put("messages", messages);
                            payload.put("temperature", 0.02);
                            payload.put("max_tokens", 1500);
                            URL url = new URL(GROQ_URL);
                            conn = (HttpURLConnection) url.openConnection();
                            conn.setRequestMethod("POST");
                            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                            conn.setConnectTimeout(15000); 
                            conn.setReadTimeout(20000);
                            conn.setDoOutput(true);
                    
                            // ✅ Gestion sécurisée de l'OutputStream
                            try (OutputStream os = conn.getOutputStream()) {
                                byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
                                os.write(input, 0, input.length);
                                os.flush();
                            }
                    
                            int responseCode = conn.getResponseCode();
                            if (responseCode == HttpURLConnection.HTTP_OK) {
                                StringBuilder r = new StringBuilder();
                                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
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
                    
                        if (aiResult != null && aiResult.trim().length() > 50) {
                        sendTelegramSecure(aiResult.trim(), this);
                        Log.d(TAG, "[DAILY] Rapport IA standard généré et envoyé avec succès.");
                    
                        // Parsing et persistance du flux dominant pour le lendemain
                        Pattern flowPattern = Pattern.compile("(?i)🏁\\s*FLUX\\s*DOMINANT\\s*:\\s*(.{3,60})(?:\\n|$)");
                        Matcher flowMatcher = flowPattern.matcher(aiResult);
                        if (flowMatcher.find()) {
                            String newFlow = flowMatcher.group(1).trim();
                            getSharedPreferences("TradingBotPrefs", MODE_PRIVATE)
                                .edit()
                                .putString("last_daily_flow", newFlow)
                                .apply();
                            Log.d(TAG, "💾 Inertie mise à jour. Flux enregistré pour demain : " + newFlow);
                        } else {
                            Log.w(TAG, "[DAILY] Balise '🏁 FLUX DOMINANT :' introuvable. Préférences inchangées.");
                        }
                            return true ;
                                } else {
                                   Log.w(TAG, "[DAILY] Groq réponse vide — contenu : "
                                   + (aiResult != null ? aiResult.trim() : "null"));
                                  if (MainActivity.instance != null) {
                                  MainActivity.instance.addLog("⚠️ [DAILY] Groq réponse vide ou insuffisante");
                                  }
                                }
                            } else {
                                try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                                    StringBuilder errorResponse = new StringBuilder();
                                    String line;
                                    while ((line = errorReader.readLine()) != null) {
                                        errorResponse.append(line);
                                    }
                                    Log.e(TAG, "[DAILY] Erreur HTTP " + responseCode + " de l'API Groq : " + errorResponse.toString());
                    if (responseCode == 429) {
                        if (MainActivity.instance != null)
                            MainActivity.instance.addLog("⚠️ [DAILY] 429 TPD — bascule sur modèle léger.");
                        try {
                            payload.put("model", GROQ_MODEL_FALLBACK);
                            payload.put("max_tokens", 1500);
                            conn.disconnect();
                            URL urlFbD = new URL(GROQ_URL);
                            HttpURLConnection connFbD = (HttpURLConnection) urlFbD.openConnection();
                            connFbD.setRequestMethod("POST");
                            connFbD.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                            connFbD.setRequestProperty("Authorization", "Bearer " + apiKey);
                            connFbD.setConnectTimeout(15000);
                            connFbD.setReadTimeout(20000);
                            connFbD.setDoOutput(true);
                            try (OutputStream osFbD = connFbD.getOutputStream()) {
                                osFbD.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                            }
                            if (connFbD.getResponseCode() == HttpURLConnection.HTTP_OK) {
                                StringBuilder rFbD = new StringBuilder();
                                try (BufferedReader brFbD = new BufferedReader(new InputStreamReader(connFbD.getInputStream(), StandardCharsets.UTF_8))) {
                                    String lineFbD;
                                    while ((lineFbD = brFbD.readLine()) != null) rFbD.append(lineFbD);
                                }
                                String reportFbD = new JSONObject(rFbD.toString())
                                    .getJSONArray("choices").getJSONObject(0)
                                    .getJSONObject("message").getString("content");
                                if (reportFbD != null && reportFbD.length() > 50) {
                                    sendTelegramSecure("📅 *[DAILY - FALLBACK]* " + reportFbD, NotificationService.this);
                                    if (MainActivity.instance != null)
                                        MainActivity.instance.addLog("✅ [DAILY] Rapport envoyé via modèle léger.");
                                }
                            }
                            connFbD.disconnect();
                        } catch (Exception eFbD) {
                            Log.e(TAG, "[DAILY] Fallback échoué", eFbD);
                        }
                    } else if (MainActivity.instance != null) {
                        MainActivity.instance.addLog("❌ [DAILY] Erreur HTTP " + responseCode + " de Groq : "
                            + truncateForLog(errorResponse.toString()));
                    }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "[DAILY] Échec critique lors du traitement du briefing journalier", e);
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog("❌ [DAILY] Échec : " + e.getClass().getSimpleName() + " — " + e.getMessage());
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
       return false ; 
    }

    public void startMonthlyReportScheduler() {
    // ── Rapport MENSUEL — dernier jour du mois à 23h00 (Mada) ──
    Calendar nextMonthly = Calendar.getInstance(
        TimeZone.getTimeZone("Indian/Antananarivo"));
    nextMonthly.set(Calendar.DAY_OF_MONTH,
        nextMonthly.getActualMaximum(Calendar.DAY_OF_MONTH));
    nextMonthly.set(Calendar.HOUR_OF_DAY, 23);
    nextMonthly.set(Calendar.MINUTE, 0);
    nextMonthly.set(Calendar.SECOND, 0);
    if (nextMonthly.getTimeInMillis() <= System.currentTimeMillis()) {
        nextMonthly.add(Calendar.MONTH, 1);
        nextMonthly.set(Calendar.DAY_OF_MONTH,
            nextMonthly.getActualMaximum(Calendar.DAY_OF_MONTH));
    }
   // APRÈS — on remplace scheduleAtFixedRate par un schedule one-shot
// qui se reprogramme lui-même à chaque exécution
    scheduler.schedule(new Runnable() {
        @Override
        public void run() {
            generateAndPurgeMonthlyReport();
            // Recalcule la vraie fin du mois suivant après exécution
            Calendar next = Calendar.getInstance(
                TimeZone.getTimeZone("Indian/Antananarivo"));
            next.add(Calendar.MONTH, 1);
            next.set(Calendar.DAY_OF_MONTH,
                next.getActualMaximum(Calendar.DAY_OF_MONTH));
            next.set(Calendar.HOUR_OF_DAY, 23);
            next.set(Calendar.MINUTE, 0);
            next.set(Calendar.SECOND, 0);
            long delayMs = next.getTimeInMillis() - System.currentTimeMillis();
            if (delayMs > 0) scheduler.schedule(this, delayMs, TimeUnit.MILLISECONDS);
        }
    }, nextMonthly.getTimeInMillis() - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
    // ── Rapport HEBDOMADAIRE — chaque vendredi à 22h00 (Mada) ──
    Calendar nextWeekly = Calendar.getInstance(
        TimeZone.getTimeZone("Indian/Antananarivo"));
    nextWeekly.set(Calendar.HOUR_OF_DAY, 22);
    nextWeekly.set(Calendar.MINUTE, 0);
    nextWeekly.set(Calendar.SECOND, 0);
    while (nextWeekly.get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY
           || nextWeekly.getTimeInMillis() <= System.currentTimeMillis()) {
        nextWeekly.add(Calendar.DAY_OF_MONTH, 1);
    }
    scheduler.scheduleAtFixedRate(this::generateAndSendWeeklyReport,
        nextWeekly.getTimeInMillis() - System.currentTimeMillis(),
        7L * 24 * 60 * 60 * 1000, TimeUnit.MILLISECONDS);

    Log.d(TAG, "[SCHEDULER] Rapport mensuel → " + nextMonthly.getTime());
    Log.d(TAG, "[SCHEDULER] Rapport hebdo → " + nextWeekly.getTime());

    // ── Rattrapares au démarrage ──
    checkAndSendMissedWeeklyReport();
    checkAndSendMissedMonthlyReport();
    }

    private static volatile long lastCalendarBackfillMillis = 0L;
    private static final long CALENDAR_BACKFILL_GUARD_MS = 30 * 60 * 1000L;

   // 🛡️ Surcharge : la version sans paramètre garde le comportement actuel (purge active),
// pour ne rien casser chez les appelants existants (scheduler automatique).
        public boolean generateAndPurgeMonthlyReport() {
            return generateAndPurgeMonthlyReport(true);
        }
        
        // 🛡️ Nouvelle version avec contrôle de la purge — utilisée par le bouton manuel
        // (purgeAfterSend = false) pour préserver le registre avant le vrai rapport de fin de mois.
        public boolean generateAndPurgeMonthlyReport(boolean purgeAfterSend) {
            HttpURLConnection conn = null;
            try {
                String apiKey = getGroqApiKey();
        if (apiKey.isEmpty()) {
            Log.w(TAG, "[MONTHLY] Clé API Groq absente — rapport annulé.");
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("❌ [MONTHLY] Clé API Groq absente — vérifie l'onglet Clés API.");
            }
            return false;
        }
        long now = System.currentTimeMillis() / 1000;
        String monthlyRegistry = eventDb.getMonthlyMacroRegistry(now);
        if (monthlyRegistry == null || monthlyRegistry.isEmpty()) {
            Log.w(TAG, "[MONTHLY] Registre mensuel vide — rapport annulé");
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("⚠️ [MONTHLY] Aucune donnée disponible pour le rapport mensuel");
            }
            return false; // ✅ Type de retour fixé
        }

        // Mémoire d'inertie mensuelle — contexte du mois précédent
        String lastMonthlyFlow = getSharedPreferences("TradingBotPrefs", MODE_PRIVATE)
            .getString("last_monthly_flow", "NEUTRE / DONNÉES INSUFFISANTES");
        String currentFlowM = getSharedPreferences("TradingBotPrefs", MODE_PRIVATE)
            .getString("last_dominant_flow", "INDÉTERMINÉ");
                JSONObject payload = new JSONObject();
        int usedM = dailyTokensUsed.addAndGet(2000);
        getSharedPreferences("TradingBotPrefs", MODE_PRIVATE).edit()
            .putInt("daily_tokens_used", usedM)
            .putLong("token_reset_time", tokenResetTime)
            .apply();
        payload.put("model", usedM > TOKEN_BUDGET_DAILY ? GROQ_MODEL_FALLBACK : GROQ_MODEL);
        if (usedM > TOKEN_BUDGET_DAILY && MainActivity.instance != null)
            MainActivity.instance.addLog("⚠️ [MONTHLY] Budget token atteint — modèle léger.");
        payload.put("temperature", 0.05);
        JSONArray messages = new JSONArray();
                messages.put(new JSONObject().put("role", "system").put("content",
    "Tu es un analyste macroéconomique et stratège de marché quant senior de niveau institutionnel.\n" +
    "Produis un rapport de transition macroéconomique mensuel extrêmement rigoureux analysant les ruptures fondamentales du mois écoulé.\n\n" +

    "RÈGLES DE FIABILITÉ (OBLIGATOIRES) :\n" +
    "1. Interdiction d'inventer un choc, un risque ou une tendance absente du registre.\n" +
    "2. Toute conclusion doit être reliée à au moins un événement observé.\n" +
    "3. Hiérarchie absolue : SUPRÊME > SECONDAIRE > TACTIQUE.\n" +
    "4. Les événements de rang supérieur dominent toujours les conclusions.\n" +
    "5. Un événement tactique ne peut jamais annuler un événement suprême.\n" +
    "6. Si deux événements suprêmes se contredisent, signaler explicitement la divergence.\n" +
    "7. Les risques résiduels doivent provenir d'un thème déjà présent dans le registre.\n" +
    "8. Aucun actif hors liste des 6 actifs autorisés.\n" +
    "9. NASDAQ et SP500 doivent rester cohérents dans les conclusions.\n" +
    "10. Si les données sont insuffisantes, écrire explicitement : CONCLUSION LIMITÉE PAR LE MANQUE DE DONNÉES.\n\n" +
    "HIÉRARCHIE DES ÉVÉNEMENTS :\n" +
    "SUPRÊME : FED, FOMC, Powell, CPI, Core CPI, PCE, Core PCE, NFP, Chômage, GDP, ISM.\n" +
    "SECONDAIRE : EIA, OPEC, PMI, résultats majeurs, données sectorielles.\n" +
    "TACTIQUE : Géopolitique, Tarifs, Sentiment, Rumeurs.\n\n" +

    "RÈGLE DE JUSTIFICATION :\n" +
    "- Chaque justification doit être factuelle : chiffre observé (ex: 'CPI 3.8% vs 3.5% attendu') ou événement nommé (ex: 'Powell hawkish FOMC mai').\n" +
    "- Longueur maximale : 10 mots.\n" +
    "- Style institutionnel obligatoire : 'prime de risque élargie', 'flight-to-quality activé', 'différentiel de taux déterminant', 'révision bénéfices à la baisse'.\n" +
    "- NUANCE GÉO OBLIGATOIRE : défaut crise GÉO Iran/Hormuz = GOLD🟢 refuge (historique +0.8% à +1.5%). " +
    "Riposte militaire USA confirmée uniquement = GOLD🔴 court terme. " +
    "RÈGLE PAR DÉFAUT : sans 'riposte/strike/Pentagon' dans le texte → GOLD🟢.\n" +
    "- INTERDIT : 'les investisseurs sont prudents', 'incertitudes économiques', 'contexte difficile', toute phrase sans ancrage factuel.\n\n" +                                                                    
    "Tu dois impérativement analyser la dynamique globale et l'impact uniquement parmi cette liste fermée de 6 actifs :\n" +
    "NASDAQ, SP500, GOLD, USOIL, USDJPY, GBPUSD.\n" +
    "EURUSD (CONTEXTE UNIQUEMENT, non listé) : s'il apparaît dans le registre, utilise-le uniquement pour calibrer la cohérence directionnelle de GBPUSD (corrélation EUR/GBP) — ne jamais l'afficher comme ligne séparée.\n" +
    "CORRÉLATION USDJPY/GBPUSD : " +
    "En régime DOLLAR (HAWKISH/DOVISH Fed) → directions INVERSES obligatoires (USDJPY↑ = GBPUSD↓). " +
    "En régime RISK (GÉO/risk-off/risk-on) → même direction obligatoire (les deux baissent en risk-off, les deux montent en risk-on). " +
    "Divergence possible UNIQUEMENT si BoJ seul (neutre GBPUSD) ou BoE seul (neutre USDJPY).\n" +
    "Lister uniquement les actifs avec impact réel — omettre les NEUTRE.\n\n" +
    "Format OBLIGATOIRE et STRICT :\n\n" +

    "1. 🔥 LES CHOCS MACRO MAJEURS DU MOIS (1 à 3 selon l'importance réelle) :\n" +
    "   • [Nom du Choc] => Impact direct sur [Actif concerné] | Source/Type : [CPI, FOMC, EIA, discours, etc.]\n" +
    "   (Répéter pour chaque choc identifié, maximum 3. Si moins, ne pas inventer.)\n\n" +

    "2. 🏛️ POSITIONNEMENT MONÉTAIRE & ANTICIPATIONS :\n" +
    "   • Posture de la Réserve Fédérale : [HAWKISH / DOVISH / DATA-DEPENDENT]\n\n" +
    
    "3. 📉 MATRICE DE PERFORMANCE & DÉVIATION DE NOS ACTIFS :\n" +
    "   • Actifs Leaders : [Actifs parmi les 6] => [HAUSSE / BAISSE]\n" +
    "   • Actifs Sous Tension : [Actifs parmi les 6 montrant volatilité ou retournement]\n\n" +

    "4. 🛡️ RISQUES RÉSIDUELS ET INERTIE (MOIS SUIVANT) :\n" +
    "   • Risque Majeur Détecté : [Uniquement issu d'un thème observé]\n" +
    "   • Niveau d'Alerte : [MODÉRÉ / ÉLEVÉ / CRITIQUE]\n\n" +

    "5. 🏁 FLUX MENSUEL DOMINANT :\n" +
    "   Format obligatoire :\n" +
    "   [REGIME STRUCTUREL] => [CONSÉQUENCE PRINCIPALE SUR LES ACTIFS]\n\n" +

    "   Exemples :\n" +
    "   DOLLAR FORT STRUCTUREL => pression durable sur GOLD.\n" +
    "   RISK-ON STRUCTUREL => soutien durable NASDAQ et SP500.\n" +
    "   RISK-OFF STRUCTUREL => préférence pour GOLD et USD.\n\n" +
    "CONTRAINTES DE RÉDACTION :\n" +
    "• Utiliser uniquement *italique simple*.\n" +
    "• Interdiction des doubles astérisques (**).\n" +
    "• Style technique, institutionnel, concis et quantitatif.\n" +
    "• Aucune formule de politesse.\n" +
    "• Aucun texte hors du format demandé."
));
         
        messages.put(new JSONObject().put("role", "user").put("content",
            "⚡ FLUX DOMINANT ACTUEL (dernière analyse live) : " + currentFlowM + "\n" +
            "📊 FLUX MENSUEL PRÉCÉDENT : " + lastMonthlyFlow + "\n" +
            "─────────────────────────────\n" +
            "REGISTRE MACRO DU MOIS :\n" + monthlyRegistry));
        payload.put("messages", messages);
        payload.put("temperature", 0.05);
        payload.put("max_tokens", 1500);
        URL url = new URL(GROQ_URL);
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(25000);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
            os.flush();
        }

        int responseCode = conn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            StringBuilder r = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) r.append(line);
            }

            String report = new JSONObject(r.toString())
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");

            if (report != null && report.trim().length() > 300) {
                String monthlyFlowLine = "📊 *RAPPORT DE TRANSITION MACROÉCONOMIQUE MENSUEL*\n\n" + report.trim();
                sendTelegramSecure(monthlyFlowLine, this);
                
                // Mémoriser le timestamp pour le rattrapage
                getSharedPreferences("TradingBotPrefs", MODE_PRIVATE)
                    .edit()
                    .putLong("last_monthly_sent_ms", System.currentTimeMillis())
                    .apply();
                Log.d(TAG, "[MONTHLY] Rapport mensuel envoyé avec succès.");

                // Persistance du flux mensuel dominant
                Pattern monthlyFlowPattern = Pattern.compile("(?i)🏁\\s*FLUX\\s*MENSUEL\\s*DOMINANT\\s*:\\s*(.{3,60})(?:\\n|$)");
                Matcher monthlyFlowMatcher = monthlyFlowPattern.matcher(report);
                if (monthlyFlowMatcher.find()) {
                    String newMonthlyFlow = monthlyFlowMatcher.group(1).trim();
                    getSharedPreferences("TradingBotPrefs", MODE_PRIVATE)
                        .edit()
                        .putString("last_monthly_flow", newMonthlyFlow)
                        .apply();
                    Log.d(TAG, "💾 [MONTHLY] Flux mensuel enregistré : " + newMonthlyFlow);
                }
                
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog("✅ [MONTHLY] Rapport mensuel envoyé");
                }
                // ✅ Purge sécurisée uniquement si le rapport a été généré et transmis avec succès
                // 🛡️ Sautée si appelée manuellement avec purgeAfterSend=false (bouton de test)
                if (purgeAfterSend) {
                    eventDb.purgeOldEvents(now);
                } else {
                    Log.d(TAG, "[MONTHLY] Purge ignorée (déclenchement manuel/test) — registre préservé.");
                    if (MainActivity.instance != null) {
                        MainActivity.instance.addLog("ℹ️ [MONTHLY] Purge ignorée (mode test) — registre préservé.");
                    }
                }
                return true; // ✅ Succès total : On retourne true au planificateur !
            } else {
                Log.w(TAG, "[MONTHLY] Groq réponse vide ou insuffisante — purge annulée");
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog("⚠️ [MONTHLY] Rapport mensuel vide — non envoyé");
                }
            }
        } else {
            try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder errorResponse = new StringBuilder();
                String line;
                while ((line = errorReader.readLine()) != null) {
                    errorResponse.append(line);
                }
                Log.e(TAG, "[MONTHLY] Erreur HTTP " + responseCode + " de l'API Groq : " + errorResponse.toString());
if (responseCode == 429) {
    if (MainActivity.instance != null)
        MainActivity.instance.addLog("⚠️ [MONTHLY] 429 TPD — bascule sur modèle léger.");
    try {
        payload.put("model", GROQ_MODEL_FALLBACK);
        payload.put("max_tokens", 1500);
        conn.disconnect();
        URL urlFbM = new URL(GROQ_URL);
        HttpURLConnection connFbM = (HttpURLConnection) urlFbM.openConnection();
        connFbM.setRequestMethod("POST");
        connFbM.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connFbM.setRequestProperty("Authorization", "Bearer " + apiKey);
        connFbM.setConnectTimeout(15000);
        connFbM.setReadTimeout(20000);
        connFbM.setDoOutput(true);
        try (OutputStream osFbM = connFbM.getOutputStream()) {
            osFbM.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (connFbM.getResponseCode() == HttpURLConnection.HTTP_OK) {
            StringBuilder rFbM = new StringBuilder();
            try (BufferedReader brFbM = new BufferedReader(new InputStreamReader(connFbM.getInputStream(), StandardCharsets.UTF_8))) {
                String lineFbM;
                while ((lineFbM = brFbM.readLine()) != null) rFbM.append(lineFbM);
            }
            String reportFbM = new JSONObject(rFbM.toString())
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content");
            if (reportFbM != null && reportFbM.length() > 50) {
                sendTelegramSecure("📊 *[MONTHLY - FALLBACK]* " + reportFbM, NotificationService.this);
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("✅ [MONTHLY] Rapport envoyé via modèle léger.");
            }
        }
        connFbM.disconnect();
    } catch (Exception eFbM) {
        Log.e(TAG, "[MONTHLY] Fallback échoué", eFbM);
    }
} else if (MainActivity.instance != null) {
    MainActivity.instance.addLog("❌ [MONTHLY] Erreur HTTP " + responseCode + " de Groq : "
        + truncateForLog(errorResponse.toString()));
}
            }
        }
    } catch (Exception e) { 
        Log.e(TAG, "Erreur Rapport Mensuel", e); 
        if (MainActivity.instance != null) {
            MainActivity.instance.addLog("❌ [MONTHLY] Échec : " + e.getClass().getSimpleName() + " — " + e.getMessage());
        }
    } finally {
        if (conn != null) conn.disconnect();
    }
    return false; // ❌ Échec : Renvoie false pour signaler une anomalie et permettre un rattrapage
}

public boolean generateAndSendWeeklyReport() {
    HttpURLConnection conn = null;
    try {
        String apiKey = getGroqApiKey();
        if (apiKey.isEmpty()) {
            Log.w(TAG, "[WEEKLY] Clé API Groq absente — rapport annulé.");
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("❌ [WEEKLY] Clé API Groq absente — vérifie l'onglet Clés API.");
            }
            return false;
        }
        long now = System.currentTimeMillis() / 1000;
        // Récupère les événements des 7 derniers jours (poids >= 2)
        String weeklyRegistry = eventDb.getWeeklyMacroSummary(now);
        if (weeklyRegistry == null || weeklyRegistry.isEmpty()) {
            Log.w(TAG, "[WEEKLY] Registre hebdo vide — rapport annulé");
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("⚠️ [WEEKLY] Aucune donnée pour le rapport hebdomadaire");
            return false; // ✅ Type de retour fixé
        }

        String lastWeeklyFlow = getSharedPreferences("TradingBotPrefs", MODE_PRIVATE)
    .getString("last_weekly_flow", "NEUTRE / DONNÉES INSUFFISANTES");
String currentFlowW = getSharedPreferences("TradingBotPrefs", MODE_PRIVATE)
    .getString("last_dominant_flow", "INDÉTERMINÉ");
        JSONObject payload = new JSONObject();
int usedW = dailyTokensUsed.addAndGet(2000);
getSharedPreferences("TradingBotPrefs", MODE_PRIVATE).edit()
    .putInt("daily_tokens_used", usedW)
    .putLong("token_reset_time", tokenResetTime)
    .apply();
payload.put("model", usedW > TOKEN_BUDGET_DAILY ? GROQ_MODEL_FALLBACK : GROQ_MODEL);
if (usedW > TOKEN_BUDGET_DAILY && MainActivity.instance != null)
    MainActivity.instance.addLog("⚠️ [WEEKLY] Budget token atteint — modèle léger.");
payload.put("temperature", 0.05);

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content",
    "Tu es un analyste macroéconomique et stratège de marché quant senior.\n" +
    "Produis un rapport de marché hebdomadaire institutionnel, rigoureux et précis.\n\n" +

    "RÈGLES DE FIABILITÉ (OBLIGATOIRES) :\n" +
    "1. Interdiction d'inventer un événement, un impact ou une tendance absente du registre.\n" +
    "2. Toute conclusion doit être reliée à au moins un événement observé.\n" +
    "3. Hiérarchie absolue : SUPRÊME > SECONDAIRE > TACTIQUE.\n" +
    "4. Les événements de rang supérieur dominent toujours les conclusions.\n" +
    "5. Un événement tactique ne peut normalement pas annuler un événement suprême, sauf exception géopolitique majeure (voir règle de crise).\n" +
    "6. Si deux événements suprêmes se contredisent, signaler explicitement la divergence.\n" +
    "7. Les risques résiduels et l'agenda stratégique doivent s'appuyer sur des thèmes observés.\n" +
    "8. Aucun actif hors liste des 6 actifs autorisés.\n" +
    "9. NASDAQ et SP500 doivent rester cohérents dans les conclusions.\n" +
    "10. Si les données sont insuffisantes, écrire explicitement : CONCLUSION LIMITÉE PAR LE MANQUE DE DONNÉES.\n" +
    "11. Si aucun événement de rang SUPRÊME n'est présent dans la semaine, le rapport doit indiquer :\n" +
    "    \"SEMAINE DOMINÉE PAR DES DRIVERS SECONDAIRES OU TACTIQUES.\"\n" +
    "12. Le FLUX HEBDO DOMINANT doit toujours être dérivé de l'événement ayant le rang le plus élevé observé, sauf activation explicite de la règle CRISE GÉOPOLITIQUE (voir règle de crise).\n" +
    "13. Si le régime est DOLLAR FORT ou DOLLAR FAIBLE, la justification doit obligatoirement mentionner FED ou données macro américaines.\n" +
    "14. En cas d'absence de consensus clair entre plusieurs drivers majeurs, conserver un régime NEUTRE et attribuer un niveau de confiance FAIBLE.\n\n" +

    "HIÉRARCHIE DES ÉVÉNEMENTS :\n" +
    "SUPRÊME : FED, FOMC, Powell, CPI, Core CPI, PCE, Core PCE, NFP, Chômage, GDP, ISM.\n" +
    "SECONDAIRE : EIA, OPEC, PMI, résultats majeurs, données sectorielles.\n" +
    "TACTIQUE : Géopolitique, Tarifs, Sentiment, Rumeurs.\n\n" +

    "RÈGLE DE CRISE GÉOPOLITIQUE :\n" +
    "Un événement tactique ne peut normalement pas annuler un événement suprême.\n" +
    "Exception : en cas de choc géopolitique majeur confirmé (guerre ouverte, attaque militaire directe, fermeture d'une route énergétique stratégique, mobilisation militaire massive ou crise systémique), le régime géopolitique devient temporairement dominant et peut supplanter un driver suprême jusqu'à normalisation du marché.\n" +
    "Si un événement géopolitique provoque un mouvement global de fuite vers les actifs refuges (GOLD, USD, JPY) et une baisse simultanée des actifs risqués (NASDAQ, SP500), alors le régime CRISE GÉOPOLITIQUE devient prioritaire.\n" +
    "Flux dominant autorisé : CRISE GÉOPOLITIQUE => priorité sur tous les autres régimes (et supplante la règle 15).\n\n" +
    "RÈGLE DE JUSTIFICATION :\n" +
    "- Chaque justification doit être factuelle : chiffre observé (ex: 'NFP +250k vs +185k attendu') ou événement nommé (ex: 'minutes FOMC hawkish').\n" +
    "- Longueur maximale : 10 mots.\n" +
    "- Style institutionnel obligatoire : 'surprise haussière NFP renforce dollar', 'flight-to-quality vers JPY et GOLD', 'prime géopolitique activée sur USOIL'.\n" +
    "- NUANCE GÉO : défaut crise GÉO Iran/Hormuz → GOLD🟢 refuge immédiat. " +
    "Exception riposte USA confirmée → GOLD🔴 court terme. " +
    "Sans mot-clé militaire explicite dans le texte → GOLD🟢 par défaut.\n" +
    "- INTERDIT : 'les investisseurs sont prudents', 'incertitudes économiques', 'sentiment dégradé', toute généralité sans chiffre ni source.\n\n" +                        

    "Tu dois impérativement analyser la dynamique globale et l'impact uniquement parmi cette liste fermée de 6 actifs :\n" +
    "NASDAQ, SP500, GOLD, USOIL, USDJPY, GBPUSD.\n" +
    "EURUSD (CONTEXTE UNIQUEMENT, non listé) : s'il apparaît dans le registre, utilise-le uniquement pour calibrer la cohérence directionnelle de GBPUSD (corrélation EUR/GBP) — ne jamais l'afficher comme ligne séparée.\n" +
    "CORRÉLATION USDJPY/GBPUSD : " +
    "En régime DOLLAR (HAWKISH/DOVISH Fed) → directions INVERSES obligatoires (USDJPY↑ = GBPUSD↓). " +
    "En régime RISK (GÉO/risk-off/risk-on) → même direction obligatoire (les deux baissent en risk-off, les deux montent en risk-on). " +
    "Divergence possible UNIQUEMENT si BoJ seul (neutre GBPUSD) ou BoE seul (neutre USDJPY).\n" +
    "Lister uniquement les actifs avec impact réel — omettre les NEUTRE.\n\n" +
    "Format OBLIGATOIRE et STRICT :\n\n" +

    "1. 🏆 ÉVÉNEMENTS CLÉS ET IMPACTS (1 à 3 événements retenus, classés par importance décroissante) :\n" +
    "   • [Nom de l'événement] | Statut: [Confirmé / Surprise] | Impact: [Majeur / Modéré] | Rang: [SUPRÊME / SECONDAIRE / TACTIQUE] | Source/Type: [CPI, FOMC, EIA, discours, etc.]\n" +
    "     └ Synthèse: [Lien logique et concis avec l'actif touché, ≤10 mots]\n" +
    "     └ Si SURPRISE: [Explication de l'écart par rapport au consensus, ≤10 mots]\n\n" +

    "2. 📊 BILAN DIRECTIONNEL GLOBAL :\n" +
    "   ⚖️ RÉGIME : [RISK-ON / RISK-OFF / DOLLAR FORT / DOLLAR FAIBLE / YEN FORT / EURO FORT / CRISE GÉOPOLITIQUE / NEUTRE]\n" +
    "   └ Moteur macro: [Une phrase concise, ≤15 mots]\n\n" +

    "📊 NIVEAU DE CONFIANCE :\n" +
    "[FAIBLE / MODÉRÉ / ÉLEVÉ]\n" +
    "Méthode :\n" +
    "• Driver SUPRÊME dominant => ÉLEVÉ\n" +
    "• Driver SECONDAIRE dominant => MODÉRÉ\n" +
    "• Driver TACTIQUE dominant => FAIBLE\n" +
    "• CRISE GÉOPOLITIQUE confirmée => ÉLEVÉ\n" +
    "• Drivers contradictoires => FAIBLE\n" +
    "• Données insuffisantes => FAIBLE\n\n" +

    "3. 🎯 IMPACTS DIRECTS SUR NOS ACTIFS SPÉCIFIQUES :\n" +
    "   • 🇺🇸 INDICES (SP500, NASDAQ) : [HAUSSE / BAISSE / NEUTRE] => [Justification macro, ≤10 mots]\n" +
    "   • 🪙 REFUGES & MATIÈRES (GOLD, USOIL) : [HAUSSE / BAISSE / NEUTRE] => [Justification macro, ≤10 mots]\n" +
    "   • 💵 FOREX (OBLIGATOIRE : les 2 paires doivent apparaître explicitement) :\n" +
    "     └ USDJPY : [HAUSSE / BAISSE / NEUTRE] => [Justification, ≤10 mots]\n" +
    "     └ GBPUSD : [HAUSSE / BAISSE / NEUTRE] => [Justification, ≤10 mots]\n\n" +

    "4. 📅 AGENDA STRATÉGIQUE (Semaine Prochaine) :\n" +
    "   IMPORTANT : Utiliser UNIQUEMENT les événements futurs explicitement présents dans les données brutes fournies.\n" +
    "   • [Jour] - [Actif Spécifique Cible] : [Event macro précis] | Impact: [Élevé / Critique]\n" +
    "   Si aucun agenda futur n'est disponible dans les données, écrire :\n" +
    "   \"Agenda indisponible dans les données fournies.\"\n\n" +

    "5. 🏁 FLUX HEBDO DOMINANT :\n" +
    "   Format obligatoire :\n" +
    "   [REGIME STRUCTUREL] => [CONSÉQUENCE PRINCIPALE SUR LES ACTIFS]\n" +
    "   Dérivé exclusivement de l'événement de rang le plus élevé, SAUF si la règle CRISE GÉOPOLITIQUE est activée (dans ce cas, utiliser CRISE GÉOPOLITIQUE).\n\n" +
    "   Exemples :\n" +
    "   DOLLAR FORT => pression sur GOLD.\n" +
    "   RISK-ON => soutien NASDAQ et SP500.\n" +
    "   RISK-OFF => préférence pour GOLD et USD.\n" +
    "   CRISE GÉOPOLITIQUE => fuite vers GOLD, USD, JPY ; baisse des actifs risqués.\n\n" +

    "CONTRAINTES DE RÉDACTION :\n" +
    "• Utiliser uniquement *italique simple*.\n" +
    "• Interdiction des doubles astérisques (**).\n" +
    "• Style technique, institutionnel, concis et quantitatif.\n" +
    "• Aucune formule de politesse.\n" +
    "• Aucun texte hors du format demandé."
));
        messages.put(new JSONObject().put("role", "user").put("content",
            "⚡ FLUX DOMINANT ACTUEL (dernière analyse live) : " + currentFlowW + "\n" +
            "📅 FLUX HEBDO PRÉCÉDENT : " + lastWeeklyFlow + "\n" +
            "─────────────────────────────\n" +
            "REGISTRE MACRO DE LA SEMAINE :\n" + weeklyRegistry));
        payload.put("messages", messages);
        payload.put("temperature", 0.05);
        payload.put("max_tokens", 1500);
        URL url = new URL(GROQ_URL);
        conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(25000);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
            os.flush();
        }

        int responseCode = conn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            StringBuilder r = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) r.append(line);
            }

            String report = new JSONObject(r.toString())
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");

            if (report != null && report.trim().length() > 300) {
                sendTelegramSecure("📅 *BILAN MACRO HEBDOMADAIRE — VENDREDI*\n\n" + report.trim(), this);
                
                getSharedPreferences("TradingBotPrefs", MODE_PRIVATE)
                    .edit()
                    .putLong("last_weekly_sent_ms", System.currentTimeMillis())
                    .apply();

                Log.d(TAG, "[WEEKLY] Rapport hebdomadaire envoyé.");

                // Persistance flux hebdo
                Pattern weeklyPattern = Pattern.compile(
                    "(?i)🏁\\s*FLUX\\s*HEBDO\\s*DOMINANT\\s*:\\s*(.{3,60})(?:\\n|$)");
                Matcher weeklyMatcher = weeklyPattern.matcher(report);
                if (weeklyMatcher.find()) {
                    getSharedPreferences("TradingBotPrefs", MODE_PRIVATE)
                        .edit()
                        .putString("last_weekly_flow", weeklyMatcher.group(1).trim())
                        .apply();
                }
                
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("✅ [WEEKLY] Rapport hebdomadaire envoyé");

                return true; // ✅ Succès : On retourne true au planificateur !
            } else {
                Log.w(TAG, "[WEEKLY] Réponse Groq insuffisante");
            }
        } else {
            try (BufferedReader errorReader = new BufferedReader(new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                StringBuilder errorResponse = new StringBuilder();
                String line;
                while ((line = errorReader.readLine()) != null) {
                    errorResponse.append(line);
                }
                Log.e(TAG, "[WEEKLY] Erreur HTTP " + responseCode + " de l'API Groq : " + errorResponse.toString());
if (responseCode == 429) {
    if (MainActivity.instance != null)
        MainActivity.instance.addLog("⚠️ [WEEKLY] 429 TPD — bascule sur modèle léger.");
    try {
        payload.put("model", GROQ_MODEL_FALLBACK);
        payload.put("max_tokens", 1500);
        conn.disconnect();
        URL urlFbW = new URL(GROQ_URL);
        HttpURLConnection connFbW = (HttpURLConnection) urlFbW.openConnection();
        connFbW.setRequestMethod("POST");
        connFbW.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connFbW.setRequestProperty("Authorization", "Bearer " + apiKey);
        connFbW.setConnectTimeout(15000);
        connFbW.setReadTimeout(20000);
        connFbW.setDoOutput(true);
        try (OutputStream osFbW = connFbW.getOutputStream()) {
            osFbW.write(payload.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (connFbW.getResponseCode() == HttpURLConnection.HTTP_OK) {
            StringBuilder rFbW = new StringBuilder();
            try (BufferedReader brFbW = new BufferedReader(new InputStreamReader(connFbW.getInputStream(), StandardCharsets.UTF_8))) {
                String lineFbW;
                while ((lineFbW = brFbW.readLine()) != null) rFbW.append(lineFbW);
            }
            String reportFbW = new JSONObject(rFbW.toString())
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content");
            if (reportFbW != null && reportFbW.length() > 50) {
                sendTelegramSecure("📆 *[WEEKLY - FALLBACK]* " + reportFbW, NotificationService.this);
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("✅ [WEEKLY] Rapport envoyé via modèle léger.");
            }
        }
        connFbW.disconnect();
    } catch (Exception eFbW) {
        Log.e(TAG, "[WEEKLY] Fallback échoué", eFbW);
    }
} else if (MainActivity.instance != null) {
    MainActivity.instance.addLog("❌ [WEEKLY] Erreur HTTP " + responseCode + " de Groq : "
        + truncateForLog(errorResponse.toString()));
                                 }
            }
        }
    } catch (Exception e) {
        Log.e(TAG, "[WEEKLY] Erreur rapport hebdomadaire", e);
        if (MainActivity.instance != null) {
            MainActivity.instance.addLog("❌ [WEEKLY] Échec : " + e.getClass().getSimpleName() + " — " + e.getMessage());
        }
    } finally {
        if (conn != null) conn.disconnect();
    }

    return false; // ❌ Échec ou anomalie : Le planificateur pourra retenter l'envoi
}

private void registerNetworkCallback() {
    ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
    if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                triggerQueueSynchronization();

                long now = System.currentTimeMillis();
                if (now - lastCalendarBackfillMillis >= CALENDAR_BACKFILL_GUARD_MS) {
                    lastCalendarBackfillMillis = now;
                    Log.d(TAG, "[NETWORK] Reconnexion détectée → backfill calendrier (guard 30min respecté).");
                    syncCalendarAndPurge();
                } else {
                    Log.d(TAG, "[NETWORK] Reconnexion détectée → backfill ignoré (guard 30min actif).");
                }
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
    // Méthode 1 — version simple sans paramètre prompt
    public String construirePromptFinal(String evenementActuel, List<String> historiqueRecent) {
            boolean alerteGéoMajeure = false;
            String[] motsClesCrise = {
                "hormuz", "ormuz", "détroit d'hormuz", "strait of hormuz",
                "iran", "israel", "hezbollah", "houthi", "frappe militaire",
                "airstrike", "missile", "drone attack", "riposte", "escalade",
                "blocus", "blockade", "raid", "invasion"
            };
            String toutLeTexte = (evenementActuel != null ? evenementActuel.toLowerCase() : "");
            if (historiqueRecent != null) {
                for (String hist : historiqueRecent) {
                    if (hist != null) toutLeTexte += " " + hist.toLowerCase();
                }
            }
            for (String mot : motsClesCrise) {
                if (toutLeTexte.contains(mot)) { alerteGéoMajeure = true; break; }
            }
            String directiveDeCrise = "";
            if (alerteGéoMajeure) {
                directiveDeCrise =
                    "⚠️ [ALERTE SYSTÈME : RÉGIME DE MARCHÉ EN MODE CRISE GÉOPOLITIQUE ACTIF]. " +
                    "Le risque de guerre au Moyen-Orient ou une menace sur le Détroit d'Hormuz est prioritaire. " +
                    "L'Or (GOLD) doit refléter le flux refuge (Safe-Haven). " +
                    "Appliquer immédiatement la CONTRAINTE 10.\n\n";
        }
        return directiveDeCrise + SYSTEM_PROMPT;
    }
        
    // Méthode 2 — version avec prompt personnalisé (séparée, au même niveau)
    public String construirePromptFinalAvecPrompt(String evenementActuel,
                List<String> historiqueRecent, String basePrompt) {
            boolean alerteGéoMajeure = false;
            String[] motsClesCrise = {
                "hormuz", "ormuz", "détroit d'hormuz", "strait of hormuz",
                "iran", "israel", "hezbollah", "houthi", "frappe militaire",
                "airstrike", "missile", "drone attack", "riposte", "escalade",
                "blocus", "blockade", "raid", "invasion"
            };
            String toutLeTexte = (evenementActuel != null ? evenementActuel.toLowerCase() : "");
            if (historiqueRecent != null) {
                for (String hist : historiqueRecent) {
                    if (hist != null) toutLeTexte += " " + hist.toLowerCase();
                }
            }
            for (String mot : motsClesCrise) {
                if (toutLeTexte.contains(mot)) { alerteGéoMajeure = true; break; }
            }
            String directiveDeCrise = "";
            if (alerteGéoMajeure) {
                directiveDeCrise =
                    "⚠️ [ALERTE SYSTÈME : RÉGIME DE MARCHÉ EN MODE CRISE GÉOPOLITIQUE ACTIF]. " +
                    "Appliquer immédiatement la CONTRAINTE 10.\n\n";
            }
            // ✅ Utilise basePrompt (contient le guidage mathématique si présent)
            return directiveDeCrise + basePrompt;
    }

     // Méthode de vérification géographique des mots-clés
     public String construirePromptQuotidienSystem(String registreDeLaJournee, String promptDeBase) {
        boolean alerteGéoMajeure = false;
        
        // Liste enrichie des mots-clés de crise
        String[] motsClesCrise = {
            "hormuz", "ormuz", "détroit d'hormuz", "strait of hormuz",
            "iran", "israel", "hezbollah", "houthi", "frappe militaire", 
            "airstrike", "missile", "drone attack", "riposte", "escalade", 
            "blocus", "blockade", "raid", "invasion"
        };
    
        String toutLeTexte = (registreDeLaJournee != null) ? registreDeLaJournee.toLowerCase() : "";
        for (String mot : motsClesCrise) {
            if (toutLeTexte.contains(mot)) {
                alerteGéoMajeure = true;
                break;
            }
        }
    
        // Si un mot-clé de crise est détecté, on injecte une directive d'activation en tête de prompt
        if (alerteGéoMajeure) {
            String alerteFlash = 
                "⚠️ [ALERTE SYSTÈME CRITIQUE : EXCEPTION DE CRISE ACTIVE].\n" +
                "Le registre des dernières 24h fait état d'une ESCALADE MILITAIRE DIRECTE ou d'une MENACE SUR L'OFFRE (notamment Hormuz).\n" +
                "CONSIGNE : Tu te trouves dans le cas d'exception absolue décrit à la section GÉOPOLITIQUE. Active immédiatement la matrice géopolitique prioritaire (Régime de dominance géopolitique sur l'inflation) pour l'alignement des 6 actifs et le fait marquant.\n\n";
            return alerteFlash + promptDeBase;
        }
    
        // Sinon, on renvoie le prompt standard (la hiérarchie normale s'applique)
        return promptDeBase;
    }

  @Override
  public void onDestroy() {
    super.onDestroy();
     // Ajouter dans onDestroy() ligne 3326 :
    TradingViewFetcher.stop();
    serviceInstance = null; // ✅ Libération immédiate du singleton

    // 1. Demande d'arrêt simultanée et non bloquante
    scheduler.shutdown();
    exec.shutdown();
    tradingPipelineExecutor.shutdown();

    // 2. Attente de la terminaison propre (500ms max pour éviter les ANR sous Android)
    try {
        if (!scheduler.awaitTermination(500, TimeUnit.MILLISECONDS)) {
            scheduler.shutdownNow();
        }
        if (!exec.awaitTermination(500, TimeUnit.MILLISECONDS)) {
            exec.shutdownNow();
        }
        if (!tradingPipelineExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
            tradingPipelineExecutor.shutdownNow();
        }
    } catch (InterruptedException e) {
        // En cas d'interruption, on force uniquement les exécuteurs encore actifs
        if (!scheduler.isTerminated()) scheduler.shutdownNow();
        if (!exec.isTerminated()) exec.shutdownNow();
        if (!tradingPipelineExecutor.isTerminated()) tradingPipelineExecutor.shutdownNow();
        Thread.currentThread().interrupt();
    }

    // 3. Arrêt simplifié de MarketDataFetcher (gestion des erreurs interne)
    TradingViewFetcher.shutdownExecutor();

    // 4. Fermeture sécurisée de la base de données
    if (eventDb != null) {
        eventDb.close(); 
    }

    Log.d(TAG, "[SERVICE] Service arrêté proprement");
}
        
}
