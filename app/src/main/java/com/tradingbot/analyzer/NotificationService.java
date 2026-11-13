package com.tradingbot.analyzer;

import java.util.Locale;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;

import java.nio.charset.StandardCharsets;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import org.json.JSONArray;
import org.json.JSONObject;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import android.content.SharedPreferences;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;
import android.util.Log;

public class NotificationService extends NotificationListenerService {

    private static final String TAG = "NotificationService";
    //private ScheduledExecutorService scheduler;
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
    private static final String GROQ_MODEL = "openai/gpt-oss-120b";
    private static final String GROQ_MODEL_FALLBACK = "openai/gpt-oss-20b";
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
private static final int TOKEN_BUDGET_DAILY = 90000;      // llama-3.3-70b-versatile : 100k réel, marge 10k
private static final int TOKEN_BUDGET_FALLBACK = 420000;  // llama-3.1-8b-instant : 500k réel, marge 50k
private static final int TOKEN_ESTIMATE_PER_CALL = 4000;  // estimation moyenne input+output (par-événement)
private static final AtomicInteger dailyTokensUsed = new AtomicInteger(0);    // ✅ dédié au modèle principal
private static final AtomicInteger fallbackTokensUsed = new AtomicInteger(0); // ✅ dédié au modèle fallback, pool séparé
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
            .setContentTitle("Analyse Fondamentale")
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
            MainActivity.instance.addLog("🔴 [SYSTÈME] Accès notifications COUPÉ — tentative de reconnexion automatique...");
        }
        // Demande explicite à Android de rebrancher le listener sans intervention manuelle
        requestRebind(new android.content.ComponentName(this, NotificationService.class));
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

        /**
     * Chemin live basé sur MatriceMacro. Le LLM ne fournit que le vecteur et des faits ;
     * la direction, la conviction et la validité sont calculées en Java.
     */
    private void analyserAvecMatrice(String sourceName, String title, String body,
                                     String fingerprint, boolean isSupremeRank,
                                     Map<String, TradingViewFetcher.TVMarketData> cachedMarketData) {
        EventDatabase db = EventDatabase.getInstance(this);
        try {
            String userContent = "TITRE : " + title + "\nCORPS : " + body;
            ReponseGroq rep = appelerAvecBudget(getGroqApiKey(), PROMPT_EXTRACTION, userContent, 800, 0.0, 800);

            if (!rep.ok()) {
                db.markEventAsSynced(fingerprint, "FAILED_LLM_" + rep.status);
                if (MainActivity.instance != null) MainActivity.instance.addLog("⛔ [MATRICE] LLM KO (" + rep.status + ") — " + title);
                return;
            }

            JSONObject extrait = new JSONObject(extraireJsonObjet(rep.contenu));
            MatriceMacro.Vecteur vecteur = MatriceMacro.vecteurDepuisTag(extrait.optString("vecteur", "AUCUN"));
            if (vecteur == null) {
                db.markEventAsSynced(fingerprint, "FILTERED_VECTEUR_INCONNU");
                return;
            }

            boolean confirme = extrait.optBoolean("confirme", false);
            double surprise = extrait.optDouble("surprise_signee", 0.0);
            boolean surpriseChiffree = Math.abs(surprise) > 0.0;
            boolean speculatif = MatriceMacro.detecterSpeculatif(body);
            boolean sourceForte = sourceForteParNom(sourceName);

            // ECB : jamais seul. Un autre driver du même lot doit être présent (vérification à brancher en partie 3).
            boolean confirmeParAutre = false;

            MatriceMacro.Resultat res = MatriceMacro.evaluer(
                    vecteur, confirme, speculatif, surpriseChiffree, sourceForte, confirmeParAutre);

            if (!res.emissible()) {
                String raison = res.violations.isEmpty() ? "AUCUN_ACTIF" : "INVARIANT : " + res.violations.get(0);
                db.markEventAsSynced(fingerprint, "FILTERED_" + raison);
                Log.w(TAG, "[MATRICE] Non émis (" + title + ") : " + raison);
                return;
            }

            String heure = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE) {{
                setTimeZone(TimeZone.getTimeZone("Indian/Antananarivo"));
            }}.format(new Date());

            String message = MatriceMacro.construireMessage(res, sourceName, heure, extrait.optString("fait_marquant", title));
            sendTelegramSecure(message, this);
            db.markEventAsSynced(fingerprint, "PROCESSED_MATRICE_" + vecteur.name());
            Log.i(TAG, "[MATRICE] Émis : " + vecteur.name() + " conviction=" + res.conviction);

       } catch (Exception e) {
            Log.e(TAG, "[MATRICE] Échec analyse", e);
            db.markEventAsSynced(fingerprint, "FAILED_MATRICE_EXCEPTION");
            if (MainActivity.instance != null) MainActivity.instance.addLog("⛔ [MATRICE] Exception — " + e.getMessage() + " — " + title);
        }
    }

    private static boolean sourceForteParNom(String source) {
        if (source == null) return false;
        String s = source.toLowerCase(Locale.ROOT);
        return s.contains("bloomberg") || s.contains("reuters") || s.contains("financial")
            || s.contains("financialjuice") || s.contains("wall street");
    }

    /** Extrait le premier objet JSON d'une réponse, au cas où le modèle ajoute du texte autour. */
    private static String extraireJsonObjet(String texte) {
        int debut = texte.indexOf('{');
        int fin = texte.lastIndexOf('}');
        if (debut < 0 || fin <= debut) throw new IllegalArgumentException("pas de JSON dans la réponse");
        return texte.substring(debut, fin + 1);
    }
        // Prompt d'extraction : le LLM ne décide plus de la direction, il décrit l'événement.
    private static final String PROMPT_EXTRACTION =
        "Tu extrais des faits d'une dépêche macro-économique. Tu ne donnes AUCUNE direction de marché.\n" +
        "Réponds UNIQUEMENT par un objet JSON valide, sans texte autour, avec exactement ces clés :\n" +
        "{\n" +
        "  \"vecteur\": one of [\"HAWKISH_US\",\"DOVISH_US\",\"EIA_DEFICIT\",\"EIA_SURPLUS\",\"TARIFS\",\"CHINE\",\"SENTIMENT_FAIBLE\",\"IPO\",\"HAWKISH_BOJ\",\"DOVISH_BOJ\",\"HAWKISH_BOE\",\"DOVISH_BOE\",\"HAWKISH_ECB\",\"DOVISH_ECB\",\"GEO_CAS_A\",\"GEO_CAS_B\",\"DESESCALADE\",\"AUCUN\"],\n" +
        "  \"confirme\": true ou false (true seulement si l'événement a eu lieu, pas une annonce, menace ou rumeur),\n" +
        "  \"surprise_signee\": nombre (actual - forecast, ou 0 si indisponible),\n" +
        "  \"fait_marquant\": une phrase de 25 mots maximum, factuelle, sans jugement de marché\n" +
        "}\n" +
        "Choisis \"AUCUN\" si l'événement ne correspond à aucun vecteur.\n" +
        "Pour GEO_CAS_A : frappe ou blocage physique confirmé sur l'énergie. GEO_CAS_B : tension ou menace sans impact physique confirmé.\n";
    
    private static final String MACRO_CORE_RULES =
    "HIÉRARCHIE : SUPRÊME(FED,FOMC,CPI,Core CPI,PCE,Core PCE,NFP,Chômage,GDP,Powell,Warsh) > SECONDAIRE(PMI,ISM,Retail Sales,EIA,OPEC,Stocks pétrole,Stimulus) > TACTIQUE(Géopolitique,Tarifs,Sentiment conso,Chine,IPO,M&A,Rumeurs). Un rang supérieur écrase toujours un rang inférieur.\n" +
    "EXCEPTION UNIQUE (prioritaire sur rang et règle des 30min) : choc géo avec IMPACT PHYSIQUE RÉEL CONFIRMÉ sur l'offre énergie (Hormuz bloqué effectif, frappe confirmée infra pétrole/gaz, embargo appliqué) prime sur TOUS les drivers, pour les 6 actifs. Escalade verbale/menace/frappe non confirmée = PAS d'exception, hiérarchie normale.\n\n" +

    "FIABILITÉ : 1-Interdit d'inventer un événement absent du registre. 2-Toute conclusion reliée à un événement observé. 3-Driver SUPRÊME annule un driver inférieur dans les 30min (contradiction temporelle). 4-Deux drivers majeurs contradictoires → régime NEUTRE, confiance FAIBLE. 5-Le flux dominant dérive du driver de rang le plus élevé, sauf EXCEPTION UNIQUE active. 6-Données insuffisantes → ⚠️ DONNÉES INSUFFISANTES POUR ANALYSE DIRECTIONNELLE.\n\n" +

    "GÉO-ÉNERGIE (Iran/Hormuz/Golfe) — Étape1 PIVOT (sens du dollar) : (a) choc d'offre RÉEL confirmé→pétrole s'envole→demande USD pour transactions énergie + anticipation Fed hawkish (inflation importée)→USD SE RENFORCE. (b) escalade verbale/diplomatique SANS choc réel→pas de choc dollar→refuge classique (JPY/or)→USD stable/légèrement plus faible.\n" +
    "Étape2 GOLD (déduit du dollar, jamais d'un mot-clé de riposte) : cas(a) USD renforcé→GOLD🔴 ou NEUTRE (canal dollar-fort domine refuge ; justif type : achats pétrole en USD renforcent le dollar, pèsent sur l'or). cas(b) dollar stable/faible→GOLD🟢 (refuge classique). Riposte militaire US confirmée EN PLUS d'un choc d'offre→USD très fort (flight-to-cash)→GOLD🔴 net, rebond possible seulement après stabilisation (30-60min).\n" +
    "Étape3 les 5 autres actifs suivent le MÊME sens du dollar : USOIL↑ dans les deux cas. USDJPY cas(a)→↑/neutre, cas(b)→↓. GBPUSD miroir de USDJPY sur l'axe dollar (USD fort→↓ ; dollar stable/faible→↓ si risk-off pur, neutre/↑ seulement si dollar faiblit franchement). NASDAQ/SP500↓ dans les deux cas (risk-off + coût énergie) ; si choc transitoire sans impact réel sur l'offre→impact limité, conviction FAIBLE.\n" +
    "Désescalade : dollar revient au régime pré-choc→GOLD↓ USOIL↓ NASDAQ↑ SP500↑ USDJPY↑ GBPUSD↑ (même sens que USDJPY, régime RISK-ON) | FLUX : RISK-ON\n\n" +

    "CORRÉLATION USDJPY/GBPUSD : régime DOLLAR (Fed HAWKISH/DOVISH, ou choc d'offre géo RÉEL confirmé traité COMME régime dollar)→directions INVERSES obligatoires (USDJPY↑=GBPUSD↓). Régime RISK pur (géo sans choc confirmé, risk-off/risk-on général)→MÊME direction obligatoire. Divergence permise UNIQUEMENT si BoJ seul (GBPUSD neutre) ou BoE seul (USDJPY neutre).\n\n" +
     
    "CORRÉLATION GOLD/USDJPY : GOLD (inverse dollar) et USDJPY (direct dollar) sont TOUJOURS de sens opposé, sans aucune exception, quel que soit le régime (Fed, GÉO cas(a) ou cas(b), désescalade, tarifs). Si les deux ont le même emoji, au moins un des deux est faux — à corriger avant émission.\n\n" +
    "BANQUES CENTRALES ÉTRANGÈRES : HAWKISH_BOJ→USDJPY↓(yen renforce) | DOVISH_BOJ→USDJPY↑. HAWKISH_BOE→GBPUSD↑(livre renforce) | DOVISH_BOE→GBPUSD↓. HAWKISH_ECB→EURUSD contextuel↑→calibrer GBPUSD légèrement↑ (corrélation EUR/GBP) SEULEMENT si aucun driver BoE propre ce jour | DOVISH_ECB→inverse. EURUSD = CONTEXTE UNIQUEMENT (jamais affiché comme ligne séparée), sert uniquement à calibrer la cohérence directionnelle de GBPUSD. RÈGLE ABSOLUE : NASDAQ, SP500, GOLD, USOIL = NEUTRE pour toute news de banque centrale étrangère (sauf choc global explicite ou driver énergie/géo simultané). NE JAMAIS appliquer la matrice Fed (HAWKISH_US/DOVISH_US) à une banque centrale étrangère.\n\n" +

    "ROUTAGE OBLIGATOIRE : le vecteur GÉO est RÉSERVÉ aux événements géopolitiques réels (conflits armés, tensions inter-États, sanctions internationales, terrorisme, ruptures diplomatiques majeures). NE JAMAIS classer en GÉO une décision interne d'entreprise (compliance, RH, gouvernance) même si son nom évoque un contexte géopolitique — classer selon le mécanisme réel ou ignorer si aucun lien matériel avec les 6 actifs.\n\n" +

    "CONTRAINTES : NASDAQ et SP500 doivent toujours rester cohérents (même direction). Analyser uniquement parmi les 6 actifs autorisés (NASDAQ, SP500, GOLD, USOIL, USDJPY, GBPUSD) ; omettre les NEUTRE. Style *italique simple* uniquement, jamais de doubles astérisques (**).\n\n" +

    "INTERDIT (RÈGLE ANTI-INERTIE) : justifier le FLUX DOMINANT ou une conclusion par le régime précédent lui-même (phrases interdites : 'cohérent avec le flux précédent', 'maintenu, car', 'cohérent avec le driver dominant'). Chaque conclusion se justifie UNIQUEMENT par le contenu des événements analysés dans CE rapport, même si la conclusion finale est identique à la période précédente.\n";

    private static final String LIVE_SPECIFIC =
    "IDENTITÉ : Tu es le Directeur de la Recherche Macroéconomique d'un Hedge Fund Quantitatif, en mode analyse temps réel. Objectif : identifier le DRIVER DOMINANT de CETTE news, projeter l'impact sur les 6 actifs obligatoires (NASDAQ, SP500, GOLD, USOIL, USDJPY, GBPUSD).\n\n" +

    "ANTI-BRUIT & SOURCES : déclarations diplomatiques seules, Trump/Iran/Israël verbal, discussions/négociations/trêves potentielles = impact limité. Géopolitique majeure exige frappe/missile/embargo/blocage Hormuz/opération militaire réelle. Sources fortes : Bloomberg, Reuters, FT, FinancialJuice. Sources faibles (conviction ≤40%) : Twitter, ZeroHedge, rumeurs.\n\n" +

    "CONVICTION : Actual=Forecast→max50% | Surprise<5%→max65% | Surprise>10%→80%+. Jauge : <40%⚪⚪⚪⚪⚪ | 41-60%🟠🟠🟠⚪⚪ | 61-80%🟡🟡🟡🟡⚪ | >80%🔴🔴🔴🔴🔴. CONFIANCE : SUPRÊME dominant→ÉLEVÉ | SECONDAIRE→MODÉRÉ | TACTIQUE→FAIBLE | Conflit→FAIBLE.\n\n" +

    "MATRICES (↑=BULLISH,↓=BEARISH) — OBLIGATION : jamais sauter du driver à la direction ; toujours passer par une variable pivot explicite (sens DOLLAR/DXY, taux réels US, appétit pour le risque, offre physique énergie), puis dériver CHAQUE actif de cette même variable — jamais un narratif indépendant par actif pour un même événement.\n\n" +
    "HAWKISH_US : USD↑(taux réels)→USDJPY↑ GOLD↓ NASDAQ↓ SP500↓ GBPUSD↓ USOIL= | FLUX : DOLLAR FORT\n" +
    "DOVISH_US : USD↓→inverse | FLUX : DOLLAR FAIBLE\n" +
    "EIA déficit→USOIL↑ | EIA surplus→USOIL↓\n" +
    "TARIFS escalade : risk-off pur, PAS de choc dollar net→NASDAQ↓ SP500↓ USOIL↓ USDJPY↓(yen refuge) GOLD↑(canal refuge domine) GBPUSD↓(même sens que USDJPY) | FLUX : RISK-OFF\n" +
    "CHINE forte : USOIL↑ NASDAQ↑ SP500↑ | FLUX : RISK-ON\n" +
    "SENTIMENT faible : NASDAQ↓ SP500↓ GOLD↑ USOIL↓ | FLUX : RISK-OFF MODÉRÉ\n" +
    "IPO majeure : NASDAQ↑ SP500↑ GOLD↓ USDJPY↓ | FLUX : RISK-ON\n" +
    "RÈGLE JUSTIFICATION : la matrice donne le POINT DE DÉPART (variable pivot), jamais la conclusion automatique. Pour chaque actif, écris la chaîne causale réelle (driver→dollar/taux/risque→actif), jamais une formule générique répétée.\n\n" +

    "CONTRAINTES SPÉCIFIQUES TEMPS RÉEL :\n" +
    "1-Analyser uniquement les actifs impactés ; omettre les NEUTRE.\n" +
    "1bis-Si AUCUN actif significativement impacté : la section IMPACTS ACQUISITION ne doit JAMAIS rester vide → écrire une seule ligne '• ⚪ AUCUN IMPACT SIGNIFICATIF | [raison ≤10 mots]' à la place des 6 lignes normales.\n" +
    "2-Un seul 📢 dans toute la réponse.\n" +
    "3-USDJPY BEARISH → flux ne dit pas DOLLAR FORT. USDJPY BULLISH → flux ne dit pas YEN FORT.\n" +
    "4-Chaque actif : direction + mécanisme causal précis ≤8 mots. INTERDIT : 'pas de lien direct', 'même raisonnement', 'comme pour'.\n" +
    "DIRECTION OBLIGATOIRE : exclusivement 🟢(BULLISH) 🔴(BEARISH) NEUTRE. Interdit d'écrire 'BULLISH','BEARISH','↑','↓','='.\n" +
    "5-VECTEUR CIBLE autorisé : HAWKISH_US, DOVISH_US, HAWKISH_ECB, DOVISH_ECB, HAWKISH_BOJ, DOVISH_BOJ, HAWKISH_BOE, DOVISH_BOE, GÉO, LIQUIDITÉ, CHINE, TARIFS, IPO.\n" +
    "6-ROUTAGE VECTEUR : Fed/US→HAWKISH_US/DOVISH_US. BCE/ECB/zone euro/PMI européen→HAWKISH_ECB/DOVISH_ECB. BoJ/Japon→HAWKISH_BOJ/DOVISH_BOJ. BoE/UK→HAWKISH_BOE/DOVISH_BOE. Pour ces 6 derniers tags (banque centrale NON-Fed) : appliquer OBLIGATOIREMENT BANQUES CENTRALES ÉTRANGÈRES + RÈGLE ABSOLUE (NASDAQ,SP500,GOLD,USOIL=NEUTRE non listés sauf choc global explicite) — NE JAMAIS appliquer HAWKISH_US/DOVISH_US à une banque centrale étrangère.\n" +
    "7-Choc géopolitique énergétique : AVANT de rédiger un seul impact, écrire explicitement 'PIVOT : cas(a) choc réel confirmé' OU 'PIVOT : cas(b) escalade non confirmée' — UNE SEULE FOIS. Dériver ENSUITE les 6 actifs de ce pivot unique. INTERDIT d'utiliser un libellé de cas(a) ('choc d'offre confirmé') pour un actif et un libellé de cas(b) ('refuge classique') pour un autre, dans le même rapport.\n\n" +
    
    "AUTO-VÉRIFICATION FINALE (relire AVANT d'émettre, corriger silencieusement si besoin) :\n" +
    "A-EMOJI=TEXTE : si la justification contient 'hausse','refuge','renforce','soutient','flight-to-quality' → emoji 🟢 obligatoire. Si elle contient 'baisse','pèse sur','comprime','risk-off' pour CET actif → 🔴 obligatoire. Un emoji qui contredit son propre texte est INTERDIT.\n" +
    "B-CORRÉLATION USDJPY/GBPUSD : relire la règle du tronc commun et vérifier explicitement que la paire respecte le régime (inverse si DOLLAR, identique si RISK pur) avant d'émettre.\n" +
    "C-JUSTIFICATIONS DISTINCTES : aucune justification ne peut être copiée mot pour mot (ou quasi identique) sur plus de 2 actifs. Si deux actifs non corrélés (ex: GOLD et USDJPY) ont la même phrase, réécrire l'un des deux avec son propre canal de transmission (actions: appétit au risque/résultats ; or: taux réels/refuge ; pétrole: offre/demande ; forex: différentiel de taux/carry).\n" +
    "D-VECTEUR VALIDE : si le pays/institution concerné n'est PAS Fed/US, BCE/zone euro, BoJ/Japon ou BoE/UK, alors HAWKISH_US/DOVISH_US et les autres tags de banque centrale sont INTERDITS — traiter comme TACTIQUE générique (impact limité, conviction ≤50%) ou ignorer si aucun lien matériel avec les 6 actifs.\n" +
    "E-CONDITIONNEL ≠ CONFIRMÉ : si le texte source contient 'pourrait', 'menace de', 'envisage', 'selon des sources non confirmées', 'risque de' → l'événement est SPÉCULATIF, jamais un choc confirmé. Ne jamais appliquer l'EXCEPTION UNIQUE ni l'Étape 1a (choc réel) à un conditionnel — rang TACTIQUE, conviction ≤50%.\n" +
    "F-COMPLÉTUDE : vérifier qu'aucun des 6 actifs n'est omis sans la ligne AUCUN IMPACT (règle 1bis).\n\n" +
    "FORMAT DE SORTIE OBLIGATOIRE :\n" +
    "🚨 [SOURCE]\n🕒 [DATE/HEURE MADA]\n📊 CONVICTION : [JAUGE] XX% | CONFIANCE : [FAIBLE/MODÉRÉ/ÉLEVÉ]\n🎯 VECTEUR CIBLE : [VALEUR AUTORISÉE]\n📢 FAIT MARQUANT : [analyse synthétique du driver dominant]\n--- IMPACTS ACQUISITION ---\n• 💻 NASDAQ : [direction] | [justification ≤10 mots]\n• 📊 SP500 : [direction] | [justification ≤10 mots]\n• 🏆 GOLD : [direction] | [justification ≤10 mots]\n• 🛢️ USOIL : [direction] | [justification ≤10 mots]\n• 🇯🇵 USDJPY : [direction] | [justification ≤10 mots]\n• 🇬🇧 GBPUSD : [direction] | [justification ≤10 mots]\n🏁 FLUX DOMINANT : [FLUX EXACT ISSU DES MATRICES]\n\n" +

    "CORRÉLATION TECHNIQUE/FONDAMENTAL : le contexte marché fourni contient un [VERDICT TECHNIQUE] par actif, à intégrer ainsi :\n" +
    "1-CONFLUENCE✅ : [VERDICT]✅ + driver fondamental même direction → mentionner la confluence dans le FAIT MARQUANT, élever la conviction de 10 à 15 points.\n" +
    "2-DIVERGENCE⚠️ : [VERDICT]✅ + driver fondamental direction opposée → signaler la divergence explicitement dans le FAIT MARQUANT, réduire la conviction de 15 à 20 points.\n" +
    "3-TECHNIQUE IGNORÉ : [VERDICT]⚠️ signal suspect (flux gelé) → ignorer le verdict technique, appliquer uniquement la matrice fondamentale.\n" +
    "4-NEUTRE CONFIRMÉ : [FAIR VALUE ZONE] + fondamental neutre/ambigu → actif en attente de catalyseur, ne pas forcer de direction.\n";
    private static final String SYSTEM_PROMPT = MACRO_CORE_RULES + "\n" + LIVE_SPECIFIC;

    private static final String DAILY_SPECIFIC =
    "Tu es Directeur de la Recherche Macroéconomique d'un Hedge Fund Quantitatif, en mode synthèse journalière.\n" +
    "PÉRIMÈTRE D'ANALYSE : tu reçois l'agrégation de tous les événements macroéconomiques et géopolitiques majeurs survenus au cours des dernières 24 heures. " +
    "Tu dois identifier le DRIVER DOMINANT qui a le plus fort impact sur les marchés sur cette période, en appliquant la hiérarchie quotidienne ci-dessous. " +
    "Si plusieurs drivers de rang égal coexistent, retiens celui dont la surprise est la plus élevée ou dont l'impact marché est le plus net.\n\n" +
    
    "HIÉRARCHIE QUOTIDIENNE (remplace celle du tronc commun pour ce rapport) :\n" +
    "SUPRÊME(100) : FED,FOMC,Powell,Futur Chair FED,CPI,Core CPI,PCE,Core PCE,NFP,Chômage,GDP,ISM,PMI Flash,Retail Sales.\n" +
    "SECONDAIRE(60) : EIA,OPEC,Résultats majeurs,Big Tech,PMI hors US,PIB hors US.\n" +
    "TACTIQUE(30) : Géopolitique,Tarifs,Chine,Michigan,Conference Board,Rumeurs.\n\n" +
    
    "DOMINANCE : Le rang supérieur gagne toujours. Un driver tactique ne peut jamais annuler un driver suprême.\n" +
    "NUANCE CONFLIT : si l'écart entre deux drivers est >40 points (ex. 100 vs 30), suivre UNIQUEMENT le driver dominant. Si l'écart est <20 points, régime FLUX MIXTE et conviction max 55%.\n" +
    "Pour un écart entre 20 et 40 points inclus, appliquer une pondération 50/50 et abaisser la conviction de 10 points supplémentaires.\n\n" +
    
    "RÈGLE FED ABSOLUE : Powell,FOMC,Minutes FOMC,Dot Plot,Futur Chair FED sont toujours SUPRÊMES. Aucune news tactique ne peut les invalider.\n\n" +
    
    "MOTEUR D'ANALYSE : 1-Identifier le driver principal. 2-Déterminer le régime dominant (USD, RISK, etc.). 3-Appliquer la matrice d'actifs correspondante. 4-Calculer la conviction. 5-Vérifier la cohérence finale.\n\n" +
    
    "RÈGLE USD MAÎTRE : pour FED,CPI,PCE,NFP,GDP,ISM,GÉO-choc-confirmé, détermine d'abord DOLLAR FORT ou DOLLAR FAIBLE avant de déduire les autres actifs.\n\n" +
    
    "MATRICES QUOTIDIENNES (elles complètent celles du tronc commun) :\n" +
    "MONÉTAIRE US → HAWKISH_US = USDJPY↑ GOLD↓ NASDAQ↓ SP500↓ GBPUSD↓ USOIL= ; DOVISH_US = inverse.\n" +
    "EIA déficit = USOIL↑ ; EIA surplus = USOIL↓.\n" +
    "TARIFS escalade = NASDAQ↓ SP500↓ USOIL↓ USDJPY↓ GOLD↑ GBPUSD↓.\n" +
    "CHINE forte = USOIL↑ NASDAQ↑ SP500↑.\n" +
    "MICHIGAN FAIBLE (Sentiment conso) = NASDAQ↓ SP500↓ GOLD↑ USOIL↓.\n\n" +
    
    "CONVICTION JOURNALIÈRE (spécifique à la synthèse quotidienne) :\n" +
    "Base 50 + Bonus Rang (SUPRÊME +20, SECONDAIRE +10, TACTIQUE +0) + Bonus Surprise (0-5% +0, 5-10% +10, 10-20% +20, >20% +30) - Malus Conflit (si MIXTE -30, si pondération 50/50 -10).\n" +
    "Conforme aux attentes → max 50%. Flux mixte → -30%.\n" +
    "Borne le score final entre 25% et 95%.\n" +
    "Jauge : ⚪⚪⚪⚪⚪<40% | 🟠🟠🟠⚪⚪=41-60% | 🟡🟡🟡🟡⚪=61-80% | 🔴🔴🔴🔴🔴>80%.\n\n" +
    
    "SOURCES : Bloomberg,Reuters,Financial Times,Wall Street Journal = fortes (conviction nominale). Twitter/X, ZeroHedge, rumeurs = max 40%.\n\n" +
    
    "VALIDATION FINALE (après avoir listé les impacts) :\n" +
    "1. NASDAQ et SP500 doivent avoir la même direction (obligatoire).\n" +
    "2. USDJPY doit être cohérent avec le FLUX DOMINANT (pas de contradiction).\n" +
    "3. Un seul 📢 dans tout le rapport.\n" +
    "4. Lister uniquement les actifs impactés. Omettre les NEUTRE — ne pas les afficher.\n" +
    "5. Aucune direction contradictoire entre actifs corrélés.\n" +
    "6. Identifier le driver dominant avant de lister les impacts.\n" +
    "7. Pas de doubles astérisques (**).\n\n" +
    
    "DIRECTIONS AUTORISÉES : 🟢 (bullish) | 🔴 (bearish) | NEUTRE. Interdit d'écrire BULLISH/BEARISH en toutes lettres, interdit d'utiliser ↑ ↓ =.\n\n" +
    
    "DICTIONNAIRE MÉCANISMES PAR ACTIF (utilise exclusivement ces termes, en ≤8 mots) :\n" +
    "NASDAQ : re-pricing multiple croissance | compression valorisation tech | risk appetite dégradé\n" +
    "SP500 : prime de risque equity élargie | flux risk-off vers obligations | révision bénéfices à la baisse\n" +
    "GOLD : choc offre confirmé = dollar fort pèse sur l'or | escalade verbale seule = refuge classique soutient | taux réels négatifs soutiennent\n" +
    "USOIL : prime offre Hormuz activée | stocks EIA inférieurs attentes | demande Chine révisée\n" +
    "USDJPY : désengagement carry trade JPY | flux refuge compressent le cross | différentiel BoJ-Fed déterminant\n" +
    "GBPUSD : contexte macro UK détériore GBP | BoE diverge de la Fed | risk-off comprime les paires risquées | corrélation EURUSD confirme la direction\n\n" +
    
    "FORMAT STRICT (respecte impérativement cette structure) :\n" +
    "📊 RAPPORT JOURNALIER – [Date/Heure Madagascar]\n" +
    "🚨 [SOURCE]\n" +
    "🕒 [Date/Heure Madagascar]\n" +
    "📊 CONVICTION : [JAUGE] XX%\n" +
    "🎯 VECTEUR CIBLE : [HAWKISH_US/DOVISH_US/HAWKISH_ECB/DOVISH_ECB/HAWKISH_BOJ/DOVISH_BOJ/HAWKISH_BOE/DOVISH_BOE/GÉO/TARIFS/CHINE/LIQUIDITÉ]\n" +
    "📢 [FAIT MARQUANT : identifier le driver dominant et l'arbitrage éventuel]\n" +
    "--- IMPACTS ACQUISITION ---\n" +
    "• 💻 NASDAQ : [🟢/🔴/NEUTRE] | [mécanisme ≤8 mots]\n" +
    "• 📊 SP500 : [🟢/🔴/NEUTRE] | [mécanisme ≤8 mots]\n" +
    "• 🏆 GOLD : [🟢/🔴/NEUTRE] | [mécanisme ≤8 mots]\n" +
    "• 🛢️ USOIL : [🟢/🔴/NEUTRE] | [mécanisme ≤8 mots]\n" +
    "• 🇯🇵 USDJPY : [🟢/🔴/NEUTRE] | [mécanisme ≤8 mots]\n" +
    "• 🇬🇧 GBPUSD : [🟢/🔴/NEUTRE] | [mécanisme ≤8 mots]\n" +
    "🏁 FLUX DOMINANT : [DOLLAR FORT/DOLLAR FAIBLE/RISK-ON/RISK-OFF/YEN FORT/OR FORT/CRISE GÉOPOLITIQUE]\n" +
    "INTERDIT ABSOLU : tout texte après 🏁 FLUX DOMINANT. Aucun paragraphe de synthèse, aucun comptage d'événements. Le rapport s'arrête là.";
    private static final String DAILY_SYSTEM_PROMPT = MACRO_CORE_RULES + "\n" + DAILY_SPECIFIC;

    private static final String WEEKLY_SPECIFIC =
    "Tu es un analyste macroéconomique et stratège de marché quant senior.\n" +
    "Produis un rapport de marché hebdomadaire institutionnel, rigoureux et précis.\n\n" +

    "HIÉRARCHIE HEBDOMADAIRE (remplace celle du tronc commun pour ce rapport) :\n" +
    "SUPRÊME : FED, FOMC, Powell, CPI, Core CPI, PCE, Core PCE, NFP, Chômage, GDP, ISM.\n" +
    "SECONDAIRE : EIA, OPEC, PMI, résultats majeurs, données sectorielles.\n" +
    "TACTIQUE : Géopolitique, Tarifs, Sentiment, Rumeurs.\n\n" +

    "RÈGLES DE FIABILITÉ (OBLIGATOIRES) :\n" +
    "1. Interdiction d'inventer un événement, un impact ou une tendance absente du registre.\n" +
    "2. Toute conclusion doit être reliée à au moins un événement observé.\n" +
    "3. Hiérarchie absolue : SUPRÊME > SECONDAIRE > TACTIQUE.\n" +
    "4. Les événements de rang supérieur dominent toujours les conclusions.\n" +
    "5. Un événement tactique ne peut normalement pas annuler un événement suprême, sauf exception géopolitique majeure (EXCEPTION UNIQUE du tronc commun).\n" +
    "6. Si deux événements suprêmes se contredisent, signaler explicitement la divergence.\n" +
    "7. Les risques résiduels et l'agenda stratégique doivent s'appuyer sur des thèmes observés.\n" +
    "8. Aucun actif hors liste des 6 actifs autorisés (NASDAQ, SP500, GOLD, USOIL, USDJPY, GBPUSD).\n" +
    "9. NASDAQ et SP500 doivent rester cohérents dans les conclusions.\n" +
    "10. Si les données sont insuffisantes, écrire explicitement : CONCLUSION LIMITÉE PAR LE MANQUE DE DONNÉES.\n" +
    "11. Si aucun événement de rang SUPRÊME n'est présent dans la semaine, le rapport doit indiquer : \"SEMAINE DOMINÉE PAR DES DRIVERS SECONDAIRES OU TACTIQUES.\"\n" +
    "12. Le FLUX HEBDO DOMINANT doit toujours être dérivé de l'événement ayant le rang le plus élevé observé, sauf activation explicite de l'EXCEPTION UNIQUE (dans ce cas utiliser CRISE GÉOPOLITIQUE).\n" +
    "13. Si le régime est DOLLAR FORT ou DOLLAR FAIBLE, la justification doit obligatoirement mentionner FED ou données macro américaines.\n" +
    "14. En cas d'absence de consensus clair entre plusieurs drivers majeurs, conserver un régime NEUTRE et attribuer un niveau de confiance FAIBLE.\n\n" +

    "RAPPEL CONTEXTUEL (issu du tronc commun) :\n" +
    "• EURUSD : jamais affiché comme ligne séparée. Utilisé uniquement pour calibrer la direction de GBPUSD (corrélation EUR/GBP).\n" +
    "• NUANCE GÉOPOLITIQUE SUR GOLD : avant de conclure, évaluer le sens du dollar selon l'EXCEPTION UNIQUE.\n" +
    "  - Choc d'offre RÉEL confirmé (Hormuz bloqué, frappe infra) → USD fort → GOLD baisse ou neutre.\n" +
    "  - Escalade verbale SANS choc réel → USD stable/faible → GOLD hausse refuge classique.\n" +
    "  - Riposte militaire US confirmée → flight-to-cash, GOLD baisse nette avant stabilisation possible.\n\n" +

    "CORRÉLATION USDJPY/GBPUSD (rappel depuis tronc commun) :\n" +
    "Régime DOLLAR (Fed HAWKISH/DOVISH, OU choc d'offre géo confirmé) → directions INVERSES obligatoires (USDJPY↑ = GBPUSD↓).\n" +
    "Régime RISK pur (GÉO sans choc confirmé, risk-off/risk-on général) → même direction obligatoire.\n" +
    "Divergence possible UNIQUEMENT si BoJ seul (GBPUSD neutre) ou BoE seul (USDJPY neutre).\n\n" +

    "RÈGLE DE JUSTIFICATION :\n" +
    "- Chaque justification doit être factuelle : chiffre observé (ex: 'NFP +250k vs +185k attendu') ou événement nommé (ex: 'minutes FOMC hawkish').\n" +
    "- Longueur maximale : 10 mots.\n" +
    "- Style institutionnel obligatoire : 'surprise haussière NFP renforce dollar', 'flight-to-quality vers JPY et GOLD', 'prime géopolitique activée sur USOIL'.\n" +
    "- INTERDIT : 'les investisseurs sont prudents', 'incertitudes économiques', 'sentiment dégradé', toute généralité sans chiffre ni source.\n\n" +

    "Format OBLIGATOIRE et STRICT :\n\n" +
    "📊 RAPPORT HEBDOMADAIRE – [Date/Heure Madagascar]\n\n" +
    "1. 🏆 ÉVÉNEMENTS CLÉS ET IMPACTS (1 à 3 événements retenus, classés par importance décroissante) :\n" +
    "   • [Nom de l'événement] | Statut: [Confirmé / Surprise] | Impact: [Majeur / Modéré] | Rang: [SUPRÊME / SECONDAIRE / TACTIQUE] | Source/Type: [CPI, FOMC, EIA, discours, etc.]\n" +
    "     - Synthèse: [Lien logique et concis avec l'actif touché, ≤10 mots]\n" +
    "     - Si SURPRISE: [Explication de l'écart par rapport au consensus, ≤10 mots]\n\n" +
    "2. 📊 BILAN DIRECTIONNEL GLOBAL :\n" +
    "   ⚖️ RÉGIME : [RISK-ON / RISK-OFF / DOLLAR FORT / DOLLAR FAIBLE / YEN FORT / EURO FORT / CRISE GÉOPOLITIQUE / NEUTRE]\n" +
    "   - Moteur macro: [Une phrase concise, ≤15 mots]\n\n" +
    "📊 NIVEAU DE CONFIANCE :\n" +
    "[FAIBLE / MODÉRÉ / ÉLEVÉ]\n" +
    "Méthode : Driver SUPRÊME dominant => ÉLEVÉ | Driver SECONDAIRE dominant => MODÉRÉ | Driver TACTIQUE dominant => FAIBLE | CRISE GÉOPOLITIQUE confirmée => ÉLEVÉ | Drivers contradictoires => FAIBLE | Données insuffisantes => FAIBLE\n\n" +
    "3. 🎯 IMPACTS DIRECTS SUR NOS ACTIFS SPÉCIFIQUES :\n" +
    "   • 🇺🇸 INDICES (SP500, NASDAQ) : [HAUSSE / BAISSE / NEUTRE] => [Justification macro, ≤10 mots]\n" +
    "   • 🪙 REFUGES & MATIÈRES (GOLD, USOIL) : [HAUSSE / BAISSE / NEUTRE] => [Justification macro, ≤10 mots]\n" +
    "   • 💵 FOREX (OBLIGATOIRE : les 2 paires doivent apparaître explicitement) :\n" +
    "     - USDJPY : [HAUSSE / BAISSE / NEUTRE] => [Justification, ≤10 mots]\n" +
    "     - GBPUSD : [HAUSSE / BAISSE / NEUTRE] => [Justification, ≤10 mots]\n\n" +
    "4. 📅 AGENDA STRATÉGIQUE (Semaine Prochaine) :\n" +
    "   IMPORTANT : Utiliser UNIQUEMENT les événements futurs explicitement présents dans les données brutes fournies.\n" +
    "   • [Jour] - [Actif Spécifique Cible] : [Event macro précis] | Impact: [Élevé / Critique]\n" +
    "   Si aucun agenda futur n'est disponible dans les données, écrire : \"Agenda indisponible dans les données fournies.\"\n\n" +
    "5. 🏁 FLUX HEBDO DOMINANT :\n" +
    "   Format obligatoire : [REGIME STRUCTUREL] => [CONSÉQUENCE PRINCIPALE SUR LES ACTIFS]\n" +
    "   Exemples : DOLLAR FORT => pression sur GOLD. RISK-ON => soutien NASDAQ et SP500. RISK-OFF => préférence pour GOLD et USD. CRISE GÉOPOLITIQUE => fuite vers GOLD, USD, JPY ; baisse des actifs risqués.\n\n" +

    "CONTRAINTES DE RÉDACTION :\n" +
    "• Utiliser uniquement *italique simple*.\n" +
    "• Interdiction des doubles astérisques (**).\n" +
    "• Style technique, institutionnel, concis et quantitatif.\n" +
    "• Aucune formule de politesse.\n" +
    "• Aucun texte hors du format demandé.";
    private static final String WEEKLY_SYSTEM_PROMPT = MACRO_CORE_RULES + "\n" + WEEKLY_SPECIFIC;

    private static final String MONTHLY_SPECIFIC =
    "Tu es un analyste macroéconomique et stratège de marché quant senior de niveau institutionnel.\n" +
    "Produis un rapport de transition macroéconomique mensuel extrêmement rigoureux analysant les ruptures fondamentales du mois écoulé.\n\n" +

    "HIÉRARCHIE MENSUELLE (remplace celle du tronc commun pour ce rapport) :\n" +
    "SUPRÊME : FED, FOMC, Powell, CPI, Core CPI, PCE, Core PCE, NFP, Chômage, GDP, ISM.\n" +
    "SECONDAIRE : EIA, OPEC, PMI, résultats majeurs, données sectorielles.\n" +
    "TACTIQUE : Géopolitique, Tarifs, Sentiment, Rumeurs.\n\n" +

    "RÈGLES DE FIABILITÉ SPÉCIFIQUES AU MENSUEL :\n" +
    "1. Interdiction d'inventer un choc, un risque ou une tendance absente du registre.\n" +
    "2. Toute conclusion doit être reliée à au moins un événement observé.\n" +
    "3. Hiérarchie absolue : SUPRÊME > SECONDAIRE > TACTIQUE.\n" +
    "4. Les événements de rang supérieur dominent toujours les conclusions.\n" +
    "5. Un événement tactique ne peut jamais annuler un événement suprême. Seule l'EXCEPTION UNIQUE du tronc commun (choc géo avec impact physique réel confirmé sur l'offre énergétique) peut temporairement dominer. Applique dans ce cas les étapes GÉO-ÉNERGIE du tronc commun pour déterminer le sens du dollar et l'impact sur GOLD.\n" +
    "6. Si deux événements suprêmes se contredisent, signaler explicitement la divergence.\n" +
    "7. Les risques résiduels doivent provenir d'un thème déjà présent dans le registre.\n" +
    "8. Aucun actif hors liste des 6 actifs autorisés.\n" +
    "9. NASDAQ et SP500 doivent rester cohérents dans les conclusions.\n" +
    "10. Si les données sont insuffisantes, écrire explicitement : CONCLUSION LIMITÉE PAR LE MANQUE DE DONNÉES.\n\n" +

    "RÈGLE USD MAÎTRE (spécifique mensuel) : pour tout driver monétaire américain (FED, CPI, PCE, NFP, GDP, ISM) ou choc géopolitique confirmé impactant l'offre énergétique, détermine d'abord la direction du DOLLAR (FORT / FAIBLE) avant de projeter les impacts sur les autres actifs.\n\n" +

    "RÈGLE DE JUSTIFICATION :\n" +
    "- Chaque justification doit être factuelle : chiffre observé (ex: 'CPI 3.8% vs 3.5% attendu') ou événement nommé (ex: 'Powell hawkish FOMC mai').\n" +
    "- Longueur maximale : 10 mots.\n" +
    "- Style institutionnel obligatoire : 'prime de risque élargie', 'flight-to-quality activé', 'différentiel de taux déterminant', 'révision bénéfices à la baisse'.\n" +
    "- NUANCE GÉO OBLIGATOIRE : GOLD dépend du sens du dollar, jamais un réflexe automatique. " +
    "Choc d'offre CONFIRMÉ (frappe/blocage réel) → dollar renforcé (achats énergie en USD + Fed hawkish anticipée) → GOLD baisse. " +
    "Simple tension/escalade verbale sans choc réel → refuge classique → GOLD hausse.\n" +
    "- INTERDIT : 'les investisseurs sont prudents', 'incertitudes économiques', 'contexte difficile', toute phrase sans ancrage factuel.\n\n" +

    "RAPPEL CONTEXTUEL (issu du tronc commun) :\n" +
    "• EURUSD : jamais affiché comme ligne séparée. Utilisé uniquement pour calibrer la direction de GBPUSD (corrélation EUR/GBP).\n" +
    "• CORRÉLATION USDJPY/GBPUSD : " +
    "Régime DOLLAR (Fed HAWKISH/DOVISH, OU choc d'offre géopolitique confirmé) → directions INVERSES obligatoires (USDJPY↑ = GBPUSD↓). " +
    "Régime RISK pur (GÉO sans choc confirmé, risk-off/risk-on général) → même direction obligatoire. " +
    "Divergence possible UNIQUEMENT si BoJ seul (GBPUSD neutre) ou BoE seul (USDJPY neutre).\n" +
    "• Pour déduire les directions des actifs à partir du régime monétaire US, utilise les matrices HAWKISH_US / DOVISH_US du tronc commun.\n" +
    "• Lister uniquement les actifs avec impact réel — omettre les NEUTRE.\n\n" +

    "Format OBLIGATOIRE et STRICT :\n\n" +
    "📊 RAPPORT MENSUEL – [Date/Heure Madagascar]\n\n" +
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
    "   Format obligatoire : [REGIME STRUCTUREL] => [CONSÉQUENCE PRINCIPALE SUR LES ACTIFS]\n" +
    "   Exemples : DOLLAR FORT STRUCTUREL => pression durable sur GOLD. RISK-ON STRUCTUREL => soutien durable NASDAQ et SP500. RISK-OFF STRUCTUREL => préférence pour GOLD et USD.\n\n" +

    "CONTRAINTES DE RÉDACTION :\n" +
    "• Utiliser uniquement *italique simple*.\n" +
    "• Interdiction des doubles astérisques (**).\n" +
    "• Style technique, institutionnel, concis et quantitatif.\n" +
    "• Aucune formule de politesse.\n" +
    "• Aucun texte hors du format demandé.";
     private static final boolean UTILISER_MATRICE = true;
    private static final String MONTHLY_SYSTEM_PROMPT = MACRO_CORE_RULES + "\n" + MONTHLY_SPECIFIC;

        // ================= LOT 2 : appel Groq unique et budget centralisé =================

    private static final class ReponseGroq {
        final int status;
        final String contenu;     // null si erreur
        final int tokensReels;
        final String erreur;      // corps tronqué si erreur
        final String modele;

        ReponseGroq(int status, String contenu, int tokensReels, String erreur, String modele) {
            this.status = status;
            this.contenu = contenu;
            this.tokensReels = tokensReels;
            this.erreur = erreur;
            this.modele = modele;
        }

        boolean ok() { return status == HttpURLConnection.HTTP_OK && contenu != null; }
    }

    private static String lire(InputStream is) throws IOException {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String l;
            while ((l = br.readLine()) != null) sb.append(l).append('\n');
        }
        return sb.toString();
    }

    /** Appel HTTP unique vers Groq. Aucune logique de budget ici. */
    private ReponseGroq appelerGroq(String apiKey, String modele, String systemPrompt,
                                    String userContent, int maxTokens, double temperature,
                                    int tokensEstimes) throws Exception {
        JSONObject payload = new JSONObject()
                .put("model", modele)
                .put("temperature", temperature)
                .put("max_tokens", maxTokens)
                .put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "system").put("content", systemPrompt))
                        .put(new JSONObject().put("role", "user").put("content", userContent)));

        attendreSiNecessairePourTPM(tokensEstimes);
        HttpURLConnection conn = (HttpURLConnection) new URL(GROQ_URL).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                return new ReponseGroq(status, null, 0, truncateForLog(lire(conn.getErrorStream())), modele);
            }
            JSONObject json = new JSONObject(lire(conn.getInputStream()));
            String contenu = json.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content");
            JSONObject usage = json.optJSONObject("usage");
            int reels = (usage != null) ? usage.optInt("total_tokens", tokensEstimes) : tokensEstimes;
            return new ReponseGroq(status, contenu, reels, null, modele);
        } finally {
            conn.disconnect();
        }
    }

    /** Réserve des tokens sur le budget du jour. Retourne le modèle à utiliser, ou null si épuisé. */
    private synchronized String reserverModele(int estimation) {
        verifierResetQuotidien();
        if (dailyTokensUsed.get() + estimation <= TOKEN_BUDGET_DAILY) {
            dailyTokensUsed.addAndGet(estimation);
            return GROQ_MODEL;
        }
        if (fallbackTokensUsed.get() + estimation <= TOKEN_BUDGET_FALLBACK) {
            fallbackTokensUsed.addAndGet(estimation);
            return GROQ_MODEL_FALLBACK;
        }
        return null;
    }

    /** Remplace l'estimation par la consommation réelle (reel = 0 si l'appel a échoué). */
    private synchronized void corrigerReservation(String modele, int estimation, int reel) {
        AtomicInteger compteur = GROQ_MODEL.equals(modele) ? dailyTokensUsed : fallbackTokensUsed;
        compteur.addAndGet(reel - estimation);
        persisterCompteurs();
    }

    private synchronized void verifierResetQuotidien() {
        long now = System.currentTimeMillis();
        if (now < tokenResetTime) return;
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Indian/Antananarivo"));
        c.setTimeInMillis(now);
        c.add(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        dailyTokensUsed.set(0);
        fallbackTokensUsed.set(0);
        tokenResetTime = c.getTimeInMillis();
        persisterCompteurs();
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("🔄 [TOKEN] Compteurs réinitialisés (minuit Madagascar).");
    }

    private void persisterCompteurs() {
        getSharedPreferences("TradingBotPrefs", MODE_PRIVATE).edit()
                .putInt("daily_tokens_used", dailyTokensUsed.get())
                .putInt("fallback_tokens_used", fallbackTokensUsed.get())
                .putLong("token_reset_time", tokenResetTime)
                .apply();
    }

    /**
     * Point d'entrée unique pour tout appel Groq : budget, appel, bascule 429 vers le modèle
     * léger (une seule fois), comptabilisation réelle.
     */
    private ReponseGroq appelerAvecBudget(String apiKey, String systemPrompt, String userContent,
                                          int maxTokens, double temperature, int estimation) throws Exception {
        String modele = reserverModele(estimation);
        if (modele == null) {
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("🛑 [TOKEN] Budget principal ET fallback épuisés.");
            return new ReponseGroq(-1, null, 0, "budget épuisé", null);
        }

        ReponseGroq r = appelerGroq(apiKey, modele, systemPrompt, userContent, maxTokens, temperature, estimation);

        if (r.status == 429) {
            // Quota du modèle principal épuisé : un seul essai sur le modèle léger
            corrigerReservation(modele, estimation, 0);
            if (GROQ_MODEL_FALLBACK.equals(modele)) return r;  // déjà sur le léger : pas de bascule
            String modeleLeger = reserverModele(estimation);
            if (modeleLeger == null) return r;
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("⚠️ [GROQ] 429 — bascule sur " + GROQ_MODEL_FALLBACK);
            r = appelerGroq(apiKey, modeleLeger, systemPrompt, userContent, maxTokens, temperature, estimation);
            corrigerReservation(modeleLeger, estimation, r.ok() ? r.tokensReels : 0);
            return r;
        }

        corrigerReservation(modele, estimation, r.ok() ? r.tokensReels : 0);
        return r;
    }

    // ================= FIN LOT 2 =================
    
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
    // APRÈS
    private final ExecutorService exec = Executors.newFixedThreadPool(2);
    // ↓ réduit de 5 à 2 : avec ~4000 tokens/appel réels, 5 appels simultanés (20 000 tokens)
    // pulvérisaient le plafond Groq de 6000 tokens/minute à eux seuls, peu importe le budget journalier restant.

    // 🎯 LIMITEUR TPM (tokens par minute) — absent jusqu'ici, cause principale de l'épuisement
    // avec peu d'événements : le compteur dailyTokensUsed ne surveille QUE le total journalier,
    // jamais le débit par minute, qui est la vraie limite Groq (6000 TPM confirmé par le fallback).
    private static final int TPM_LIMITE_SURE = 5000; // marge sous le plafond Groq (6000 sur le modèle léger)
    private final java.util.ArrayDeque<long[]> fenetreTPM = new java.util.ArrayDeque<>(); // [timestampMs, tokens]

   private void attendreSiNecessairePourTPM(int tokensEstimes) {
        int demande = Math.min(tokensEstimes, TPM_LIMITE_SURE);
        while (true) {
            long attente;
            synchronized (fenetreTPM) {
                long maintenant = System.currentTimeMillis();
                while (!fenetreTPM.isEmpty() && maintenant - fenetreTPM.peekFirst()[0] > 60_000)
                    fenetreTPM.pollFirst();
                int utilise = fenetreTPM.stream().mapToInt(e -> (int) e[1]).sum();
                if (utilise + demande <= TPM_LIMITE_SURE || fenetreTPM.isEmpty()) {
                    fenetreTPM.addLast(new long[]{maintenant, demande});
                    return;
                }
                long[] plusVieux = fenetreTPM.peekFirst();
                attente = Math.max(200, 60_000 - (maintenant - plusVieux[0]) + 100);
            }
            try { Thread.sleep(attente); } catch (InterruptedException ignored) {}
        }
    }
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
        String verdictTechnique = null;
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

                    // 🧠 Verdict quantitatif institutionnel — même moteur que lors des chocs macro,
                    // garantit une lecture technique cohérente entre rapports périodiques et alertes temps réel.
                    verdictTechnique = TradingViewFetcher.buildTechnicalInterpretation(asset, data);
                }
                break;
            }
        }
        reportAjuste.append(ligneModifiee).append("\n");
        if (verdictTechnique != null) {
            reportAjuste.append("   _").append(verdictTechnique).append("_\n");
        }
    }
    return reportAjuste.toString().trim();
} catch (Exception e) {
    Log.e(TAG, "Erreur lors de l'injection des prix live", e);
    return groqReport;
}
}
    // 🛡️ Anti-contamination : ne conserve que la valeur matricielle (ex: "DOLLAR FORT"),
// jamais une justification ou une parenthèse que le modèle aurait ajoutée en violation du format.
// Empêche la propagation d'une phrase interdite (ex: "maintenu, car...") dans les futurs
// appels fallback/DAILY/WEEKLY/MONTHLY qui réinjectent ce flux comme contexte.
private static String sanitiserFluxDominant(String flux) {
    if (flux == null) return null;
    String f = flux.trim();
    int idxParen = f.indexOf('(');
    if (idxParen >= 0) f = f.substring(0, idxParen).trim();
    if (f.length() > 40) f = f.substring(0, 40).trim();
    return f;
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
 
    // APRÈS (version sûre, avec garde-fou isSupremeRank/calendrier)
    if (!isSupremeRank && !EventValidator.estMaterielPourMarche(title, body)) {
        // ⚠️ Garde-fou : isSupremeRank couvre aussi les événements de calendrier déjà validés
        // (isCalendarIntercept) — on ne filtre JAMAIS un événement déjà pré-qualifié en amont,
        // uniquement les news brutes non classées, pour éviter de perdre un vrai signal SUPRÊME.
        Log.w(TAG, "🚫 [FILTRE PERTINENCE] News jugée sans lien matériel avec les 6 actifs — appel IA annulé : " + title);
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("🚫 [FILTRE] News hors-sujet ignorée : " + title);
        return;
    }
    
     if (UTILISER_MATRICE) {
                 if (MainActivity.instance != null)
                            MainActivity.instance.addLog("[MATRICE] Entrée : " + sourceName + " | " + title);
                        analyserAvecMatrice(sourceName, title, body, fingerprint, isSupremeRank, cachedMarketData);
                        return;
    }
    
    final String userContent = "CONTEXTE TEMPOREL : " + currentMadaTime + "\n"
            + "SOURCE DE LA NEWS : " + sourceName + "\n"
            + "TITRE : " + title + "\n"
            + "CORPS DE LA NOTIFICATION : " + body + "\n"
            + "ACTIFS PRÉ-QUALIFIÉS : " + assetsString;

    exec.execute(new Runnable() {
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
                if (historique != null && historique.size() > 3) historique = historique.subList(0, 3);
                if (historique != null) {
                    List<String> tronques = new ArrayList<>();
                    for (String h : historique) tronques.add(h.length() > 300 ? h.substring(0, 300) + "…" : h);
                    historique = tronques;
                }
                String promptFinal = construirePromptFinalAvecPrompt(body, historique, systemPrompt);
                JSONObject jsonPayload = new JSONObject(); // ✅ déclaration restaurée (perdue lors du dernier collage)
                // Vérifier et réinitialiser le compteur à minuit UTC
                long nowUtc = System.currentTimeMillis();
                if (nowUtc >= tokenResetTime) {
                    java.util.Calendar madaMidnightCal = java.util.Calendar.getInstance(
                        java.util.TimeZone.getTimeZone("Indian/Antananarivo"));
                    madaMidnightCal.setTimeInMillis(nowUtc);
                    madaMidnightCal.add(java.util.Calendar.DAY_OF_MONTH, 1);
                    madaMidnightCal.set(java.util.Calendar.HOUR_OF_DAY, 0);
                    madaMidnightCal.set(java.util.Calendar.MINUTE, 0);
                    madaMidnightCal.set(java.util.Calendar.SECOND, 0);
                    madaMidnightCal.set(java.util.Calendar.MILLISECOND, 0);
                    dailyTokensUsed.set(0);
                    tokenResetTime = madaMidnightCal.getTimeInMillis();
                    if (MainActivity.instance != null)
                        MainActivity.instance.addLog("🔄 [TOKEN] Compteur TPD réinitialisé (minuit Madagascar).");
                }
                
                // Vérifier budget restant
                boolean dejaSurFallback = dailyTokensUsed.get() > TOKEN_BUDGET_DAILY;
                int usedPrincipal = dejaSurFallback ? dailyTokensUsed.get() : dailyTokensUsed.addAndGet(TOKEN_ESTIMATE_PER_CALL);
                int usedFallback  = dejaSurFallback ? fallbackTokensUsed.addAndGet(TOKEN_ESTIMATE_PER_CALL) : 0;

                boolean utiliserFallback = usedPrincipal > TOKEN_BUDGET_DAILY;
                boolean fallbackAussiSature = usedFallback > TOKEN_BUDGET_FALLBACK;

                if (utiliserFallback && fallbackAussiSature) {
                    // ✅ Nouveau cas à gérer : même le fallback (500k) est épuisé — vraiment plus de marge Groq aujourd'hui
                    Log.e(TAG, "🛑 [TOKEN] Fallback ÉGALEMENT épuisé (" + usedFallback + "/" + TOKEN_BUDGET_FALLBACK + ") — aucun appel Groq possible aujourd'hui.");
                    if (MainActivity.instance != null)
                        MainActivity.instance.addLog("🛑 [TOKEN] Budget principal ET fallback épuisés — en attente de minuit.");
                    return; // ⛔ n'appelle plus Groq du tout pour cet événement
                }
                if (utiliserFallback) {
                    // bascule fallback (utilise usedFallback pour ses propres logs, pas usedPrincipal)
                    Log.w(TAG, "[TOKEN] Budget TPD épuisé (" + usedFallback + "/" + TOKEN_BUDGET_FALLBACK + ") — bascule directe fallback.");
                    if (MainActivity.instance != null)
                        MainActivity.instance.addLog("⚠️ [TOKEN] Budget 90k atteint — fallback préventif.");
                    
                    jsonPayload.put("model", GROQ_MODEL_FALLBACK);
                    jsonPayload.put("temperature", 0.0);
                    jsonPayload.put("max_tokens", 1400);
                    
                    // ✅ CORRECTION DU CRASH : On injecte le rappel de format directement dans la variable String du prompt
                    promptFinal += "\n\nRAPPEL FORMAT STRICT FALLBACK :\n" +
                                   "- Justification : INTERDIT ce mot. Format obligatoire : '• emoji ACTIF : 🟢/🔴 | mécanisme ≤8 mots'\n" +
                                   "- Jamais de phrase complète. Jamais de 'entraînent', 'pourrait', 'impact potentiel'.\n" +
                                   "- Exemples : '| Prime géopolitique activée Hormuz' | '| Flight-to-quality comprime rendements'\n\n" +
                                   "RAPPEL GOLD/GÉO (ne pas recalculer) :\n" +
                                   "Si un contexte géopolitique est présent, les directions GOLD/USDJPY/GBPUSD/USOIL sont DÉJÀ IMPOSÉES " +
                                   "par la DIRECTIVE OBLIGATOIRE en tête de ce prompt (calculée en amont, non négociable). " +
                                   "NE PAS les redéduire toi-même à partir de mots-clés dans le texte de la news — copie EXACTEMENT " +
                                   "les directions données par cette directive, et rédige uniquement le mécanisme causal ≤8 mots pour chacune.\n" +
                                   "INTERDIT : ignorer la directive du haut et déduire GOLD toi-même à partir du texte.";
                } else {
                    jsonPayload.put("model", GROQ_MODEL);
                    jsonPayload.put("temperature", 0.02);
                    jsonPayload.put("max_tokens", 1600);
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
                attendreSiNecessairePourTPM(TOKEN_ESTIMATE_PER_CALL); 
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

                    // 🎯 CORRECTION BUDGET RÉEL : remplace l'estimation forfaitaire par la
                    // consommation RÉELLE renvoyée par Groq — élimine la dérive du compteur.
                    JSONObject usage = jsonResponse.optJSONObject("usage");
                    if (usage != null) {
                        int reelTokens = usage.optInt("total_tokens", TOKEN_ESTIMATE_PER_CALL);
                        int delta = reelTokens - TOKEN_ESTIMATE_PER_CALL;
                        if (delta != 0) {
                            int nouveauTotal = dailyTokensUsed.addAndGet(delta);
                            if (MainActivity.instance != null && Math.abs(delta) > 500)
                                MainActivity.instance.addLog("📐 [TOKEN] Estimation corrigée : " + reelTokens
                                    + " réels vs " + TOKEN_ESTIMATE_PER_CALL + " estimés (compteur=" + nouveauTotal + ").");
                        }
                    }
    
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
                            boolean isSignificant = !isInclinationNeutral &&(line.contains("🟢") || line.contains("🔴"));
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
                           // APRÈS
                        if (convictionPercent >= 40 || isSupremeRank) {
                              String enrichedReport = injectLivePrices(
                                filteredMessage.toString().trim(),
                                enrichedAssets
                            );
                        
                            // 🎯 Validation croisée : prédiction du rapport vs mouvement RÉEL du marché
                            EventValidator.MarketValidationResult marketCheck =
                                EventValidator.validateAgainstRealMarket(
                                    NotificationService.this, sourceName,
                                    filteredMessage.toString(), cachedMarketData);
                        
                            int fiabilite = EventValidator.getSourceReliability(sourceName);
                            StringBuilder footer = new StringBuilder();
                            if (!marketCheck.contradictions.isEmpty()) {
                                footer.append("\n\n").append(marketCheck.warningLine());
                            }
                            if (fiabilite >= 0) {
                                footer.append("\n📊 Fiabilité historique source \"").append(sourceName)
                                      .append("\" : ").append(fiabilite).append("%");
                            }
                        
                            // 🎯 Validation de cohérence interne : 6 actifs, texte/emoji, corrélation GOLD/USDJPY/GBPUSD
                            EventValidator.CoherenceRapportResult coherence =
                                EventValidator.validerCoherenceRapport(filteredMessage.toString());
                            if (!coherence.estValide()) {
                                Log.w(TAG, "⚠️ [COHÉRENCE] Rapport incohérent détecté (" + sourceName + ") : " + coherence.resume());
                                footer.append("\n\n🔎 *Contrôle qualité* : ").append(coherence.resume());
                            }
                        
                            List<String> anomaliesChiffres = EventValidator.verifierActualVsForecast(body + " " + filteredMessage.toString());
                            if (!anomaliesChiffres.isEmpty()) {
                                Log.w(TAG, "⚠️ [ACTUAL/FORECAST] " + String.join(" | ", anomaliesChiffres));
                                footer.append("\n\n🔢 *Alerte lecture chiffrée* : ").append(String.join(" | ", anomaliesChiffres));
                            }
                            String anomaliePhase = EventValidator.verifierPhaseChocGeo(NotificationService.this, filteredMessage.toString());
                            if (anomaliePhase != null) {
                                Log.w(TAG, "⚠️ [PHASE CHOC GÉO] " + anomaliePhase);
                                footer.append("\n\n⏱️ *Alerte phase temporelle* : ").append(anomaliePhase);
                            }
                        
                            String anomalieVecteurGeo = EventValidator.verifierVecteurGeoPertinent(filteredMessage.toString());
                            if (anomalieVecteurGeo != null) {
                                Log.w(TAG, "⚠️ [VECTEUR GÉO] " + anomalieVecteurGeo);
                                footer.append("\n\n🏷️ *Alerte classification* : ").append(anomalieVecteurGeo);
                            }
                        
                            List<String> violationsNeutralite = EventValidator.verifierNeutraliteActifsUSSurBanqueEtrangere(filteredMessage.toString());
                            if (!violationsNeutralite.isEmpty()) {
                                Log.w(TAG, "⚠️ [NEUTRALITÉ US] " + String.join(" | ", violationsNeutralite));
                                footer.append("\n\n🌐 *Alerte neutralité* : ").append(String.join(" | ", violationsNeutralite));
                            }
                        
                           // APRÈS
                            String contaminationFed = EventValidator.verifierContaminationCausaleFed(filteredMessage.toString());
                            if (contaminationFed != null) {
                                Log.w(TAG, "⚠️ [CONTAMINATION CAUSALE] " + contaminationFed);
                                footer.append("\n\n🔗 *Alerte mécanisme causal* : ").append(contaminationFed);
                            }
                        
                            List<String> duplications = EventValidator.verifierJustificationsDupliquees(filteredMessage.toString());
                            if (!duplications.isEmpty()) {
                                Log.w(TAG, "⚠️ [JUSTIFICATIONS DUPLIQUÉES] " + String.join(" | ", duplications));
                                footer.append("\n\n📋 *Alerte justification* : ").append(String.join(" | ", duplications));
                            }

                            EventValidator.CroisementTechniqueResult croisementTech = EventValidator.verifierCroisementTechnique(filteredMessage.toString(), cachedMarketData);
                            if (!croisementTech.estValide()) {
                                Log.w(TAG, "⚠️ [CROISEMENT TECHNIQUE] " + croisementTech.resume());
                                footer.append("\n\n📉 *Alerte marché réel* : ").append(croisementTech.resume());
                            }

                            String chocSurNonConfirme = EventValidator.verifierChocDollarSurNonConfirme(filteredMessage.toString());
                            if (chocSurNonConfirme != null) {
                                Log.w(TAG, "⚠️ [DOLLAR/NON-CONFIRMÉ] " + chocSurNonConfirme);
                                footer.append("\n\n🎯 *Alerte logique* : ").append(chocSurNonConfirme);
                            }

                            String paysHorsTaxonomie = EventValidator.verifierPaysHorsTaxonomieBanqueCentrale(filteredMessage.toString());
                            if (paysHorsTaxonomie != null) {
                                Log.w(TAG, "⚠️ [PAYS HORS TAXONOMIE] " + paysHorsTaxonomie);
                                footer.append("\n\n🌍 *Alerte taxonomie* : ").append(paysHorsTaxonomie);
                            }
                            String finalPayload = "⚡ *ANALYSE MACRO ÉCONOMIQUE*\n" + enrichedReport + footer;
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
                                 : extraireFlux(aiReport);
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
                                    jsonPayload.put("max_tokens", 1400);
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
                                   for (Map.Entry<String, TradingViewFetcher.TVMarketData> e : cachedMarketData.entrySet()) {
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
                                    contexteFallback += "FLUX DOMINANT PRÉCÉDENT (INFORMATION SEULE, PAS UNE JUSTIFICATION) : " + dernierFlux + "\n"
                                        + "RÈGLE 11 (RAPPEL) : le FLUX DOMINANT de CE rapport doit se justifier UNIQUEMENT par le contenu de la news actuelle. "
                                        + "INTERDIT d'écrire 'maintenu, car', 'cohérent avec le flux précédent' ou toute référence au régime précédent comme justification — "
                                        + "même si la conclusion finale est identique.\n\n";
                                }
                                
            } catch (Exception eCtx) {
                Log.w(TAG, "[FALLBACK] Enrichissement contexte échoué : " + eCtx.getMessage());
            }
            // Injecter dans le message user existant
            JSONArray msgsFallback = jsonPayload.getJSONArray("messages");
            JSONObject userMsg = msgsFallback.getJSONObject(msgsFallback.length() - 1);
            String bodyEnrichi = contexteFallback + userMsg.getString("content");
             // AJOUT — le chemin réactif (429 en plein appel) ne recevait jamais ce rappel,
             // contrairement au chemin préventif (budget atteint avant l'appel)
             JSONObject sysMsgFallback = msgsFallback.getJSONObject(0);
             String sysContentFb = sysMsgFallback.getString("content")
             + "\n\nRAPPEL FORMAT STRICT FALLBACK :\n"
             + "- Justification : INTERDIT ce mot. Format obligatoire : '• emoji ACTIF : 🟢/🔴 | mécanisme ≤8 mots'\n"
             + "- Jamais de phrase complète. Jamais de 'entraînent', 'pourrait', 'impact potentiel'.\n";
            sysMsgFallback.put("content", sysContentFb);
            userMsg.put("content", bodyEnrichi);
                    java.net.URL urlFallback = new java.net.URL(GROQ_URL);
                    attendreSiNecessairePourTPM(TOKEN_ESTIMATE_PER_CALL);
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

                        // 🎯 CORRECTION BUDGET RÉEL : cet appel avait été réservé par erreur dans le pool
                        // PRINCIPAL (dailyTokensUsed) lors du 1er essai, alors qu'il utilise en réalité
                        // GROQ_MODEL_FALLBACK. On retire la réservation mal attribuée et on comptabilise
                        // la consommation RÉELLE dans le bon pool (fallbackTokensUsed).
                        JSONObject usageFb = jsonFb.optJSONObject("usage");
                        dailyTokensUsed.addAndGet(-TOKEN_ESTIMATE_PER_CALL);
                        int reelTokensFb = (usageFb != null) ? usageFb.optInt("total_tokens", TOKEN_ESTIMATE_PER_CALL) : TOKEN_ESTIMATE_PER_CALL;
                        fallbackTokensUsed.addAndGet(reelTokensFb);

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
        // ✅ Le prompt fallback applique désormais lui-même la chaîne dollar (Étape 1/2/3).
        // On ne force plus GOLD/USOIL en 🟢 — on laisse la sortie du modèle telle quelle
        // pour respecter le raisonnement dollar-fort vs refuge.
        if (fallbackReport.contains("USOIL : 🔴") && !fallbackReport.toLowerCase().contains("surplus")) {
            // Garde uniquement un garde-fou soft : log si USOIL baisse malgré un choc d'offre confirmé, sans écraser
            Log.w(TAG, "[FALLBACK] USOIL 🔴 en régime GÉO — à vérifier manuellement (pas de correction automatique).");
        }
    }

           // APRÈS
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
        // 🎯 Contrôle qualité fallback — même batterie de 8 checks que le chemin principal,
        // appliquée sur filteredFb (variable propre au fallback) au lieu de filteredMessage.
        StringBuilder footerFb = new StringBuilder();

        EventValidator.MarketValidationResult marketCheckFb =
            EventValidator.validateAgainstRealMarket(NotificationService.this, sourceName, filteredFb.toString(), cachedMarketData);
        int fiabiliteFb = EventValidator.getSourceReliability(sourceName);
        if (!marketCheckFb.contradictions.isEmpty()) footerFb.append("\n\n").append(marketCheckFb.warningLine());
        if (fiabiliteFb >= 0) footerFb.append("\n📊 Fiabilité source \"").append(sourceName).append("\" : ").append(fiabiliteFb).append("%");

        EventValidator.CoherenceRapportResult coherenceFb = EventValidator.validerCoherenceRapport(filteredFb.toString());
        if (!coherenceFb.estValide()) {
            Log.w(TAG, "⚠️ [FALLBACK COHÉRENCE] " + coherenceFb.resume());
            footerFb.append("\n\n🔎 *Contrôle qualité* : ").append(coherenceFb.resume());
        }

        List<String> anomaliesChiffresFb = EventValidator.verifierActualVsForecast(filteredFb.toString());
        if (!anomaliesChiffresFb.isEmpty()) footerFb.append("\n\n🔢 *Alerte lecture chiffrée* : ").append(String.join(" | ", anomaliesChiffresFb));

        String anomaliePhaseFb = EventValidator.verifierPhaseChocGeo(NotificationService.this, filteredFb.toString());
        if (anomaliePhaseFb != null) footerFb.append("\n\n⏱️ *Alerte phase temporelle* : ").append(anomaliePhaseFb);

        String anomalieVecteurGeoFb = EventValidator.verifierVecteurGeoPertinent(filteredFb.toString());
        if (anomalieVecteurGeoFb != null) footerFb.append("\n\n🏷️ *Alerte classification* : ").append(anomalieVecteurGeoFb);

        List<String> violationsNeutraliteFb = EventValidator.verifierNeutraliteActifsUSSurBanqueEtrangere(filteredFb.toString());
        if (!violationsNeutraliteFb.isEmpty()) footerFb.append("\n\n🌐 *Alerte neutralité* : ").append(String.join(" | ", violationsNeutraliteFb));

        String contaminationFedFb = EventValidator.verifierContaminationCausaleFed(filteredFb.toString());
        if (contaminationFedFb != null) footerFb.append("\n\n🔗 *Alerte mécanisme causal* : ").append(contaminationFedFb);

        List<String> duplicationsFb = EventValidator.verifierJustificationsDupliquees(filteredFb.toString());
        if (!duplicationsFb.isEmpty()) footerFb.append("\n\n📋 *Alerte justification* : ").append(String.join(" | ", duplicationsFb));

        EventValidator.CroisementTechniqueResult croisementTechFb =
            EventValidator.verifierCroisementTechnique(filteredFb.toString(), cachedMarketData);
        if (!croisementTechFb.estValide()) footerFb.append("\n\n📉 *Alerte marché réel* : ").append(croisementTechFb.resume());

        String chocSurNonConfirmeFb = EventValidator.verifierChocDollarSurNonConfirme(filteredFb.toString());
        if (chocSurNonConfirmeFb != null) footerFb.append("\n\n🎯 *Alerte logique* : ").append(chocSurNonConfirmeFb);

        String paysHorsTaxonomieFb = EventValidator.verifierPaysHorsTaxonomieBanqueCentrale(filteredFb.toString());
        if (paysHorsTaxonomieFb != null) footerFb.append("\n\n🌍 *Alerte taxonomie* : ").append(paysHorsTaxonomieFb);
        sendTelegramSecure("⚡ *[ANALYSE FONDAMENTALE]* " + filteredFb.toString().trim() + footerFb, NotificationService.this);
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
                String nouveauFlux = sanitiserFluxDominant(fluxMatcher.group(1).trim());
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
            "🕒 " + new java.util.function.Supplier<String>() {
                public String get() {
                    java.text.SimpleDateFormat sdfAlerte = new java.text.SimpleDateFormat(
                        "dd/MM/yyyy HH:mm:ss", java.util.Locale.FRANCE);
                    sdfAlerte.setTimeZone(java.util.TimeZone.getTimeZone("Indian/Antananarivo"));
                    return sdfAlerte.format(new java.util.Date());
                }
            }.get();
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
    private static String extraireFlux(String rapport) {
                                    if (rapport == null) return "N/A";
                                    Matcher m = Pattern.compile("FLUX DOMINANT\\s*:\\s*(.+)").matcher(rapport);
                                    return m.find() ? "Flux: " + m.group(1).trim() : "N/A";
                                }
    // Point 5 : Déconnexion sécurisée encapsulée dans un bloc finally
    private static final Object TG_LOCK = new Object();
    private static long tgDernierEnvoiMs = 0L;
    private static void espacerTelegram() {
        synchronized (TG_LOCK) {
            long attente = 2500 - (System.currentTimeMillis() - tgDernierEnvoiMs);
            if (attente > 0) { try { Thread.sleep(attente); } catch (InterruptedException ignored) {} }
            tgDernierEnvoiMs = System.currentTimeMillis();
        }
    }
    public static void sendTelegramSecure(String message, Context context) {
          espacerTelegram();
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

        private static void ajouterAlerte(StringBuilder f, String libelle, String message) {
        if (message != null && !message.isEmpty()) f.append("\n\n").append(libelle).append(" : ").append(message);
    }

    private static void ajouterAlerte(StringBuilder f, String libelle, List<String> messages) {
        if (messages != null && !messages.isEmpty()) ajouterAlerte(f, libelle, String.join(" | ", messages));
    }

    /** Tous les contrôles post-génération. marche peut être null (contrôles marché ignorés). */
    private String construireFooterControles(String rapport, String source,
                                             Map<String, TradingViewFetcher.TVMarketData> marche) {
        StringBuilder f = new StringBuilder();

        if (marche != null) {
            EventValidator.MarketValidationResult mv =
                    EventValidator.validateAgainstRealMarket(this, source, rapport, marche);
            if (!mv.contradictions.isEmpty()) f.append("\n\n").append(mv.warningLine());
        }

        int fiab = EventValidator.getSourceReliability(source);
        if (fiab >= 0) f.append("\n📊 Fiabilité historique source \"").append(source).append("\" : ").append(fiab).append("%");

        EventValidator.CoherenceRapportResult coh = EventValidator.validerCoherenceRapport(rapport);
        if (!coh.estValide()) ajouterAlerte(f, "🔎 *Contrôle qualité*", coh.resume());

        ajouterAlerte(f, "🔢 *Alerte lecture chiffrée*", EventValidator.verifierActualVsForecast(rapport));
        ajouterAlerte(f, "⏱️ *Alerte phase temporelle*", EventValidator.verifierPhaseChocGeo(this, rapport));
        ajouterAlerte(f, "🏷️ *Alerte classification*", EventValidator.verifierVecteurGeoPertinent(rapport));
        ajouterAlerte(f, "🔀 *Alerte vecteur*", EventValidator.verifierCoherenceVecteurSurprise(rapport));
        ajouterAlerte(f, "🌐 *Alerte neutralité*", EventValidator.verifierNeutraliteActifsUSSurBanqueEtrangere(rapport));
        ajouterAlerte(f, "🔗 *Alerte mécanisme causal*", EventValidator.verifierContaminationCausaleFed(rapport));
        ajouterAlerte(f, "📋 *Alerte justification*", EventValidator.verifierJustificationsDupliquees(rapport));

        if (marche != null) {
            EventValidator.CroisementTechniqueResult cross = EventValidator.verifierCroisementTechnique(rapport, marche);
            if (!cross.estValide()) ajouterAlerte(f, "📉 *Alerte marché réel*", cross.resume());
        }

        ajouterAlerte(f, "🎯 *Alerte logique*", EventValidator.verifierChocDollarSurNonConfirme(rapport));
        ajouterAlerte(f, "🌍 *Alerte taxonomie*", EventValidator.verifierPaysHorsTaxonomieBanqueCentrale(rapport));
        return f.toString();
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
            String titreBrut = extras.getString(Notification.EXTRA_TITLE, "");
            String bigText = extras.getString(Notification.EXTRA_BIG_TEXT, "");
            String text = extras.getString(Notification.EXTRA_TEXT, "");

            // Titre de secours : FinancialJuice et d'autres sources n'envoient souvent
            // aucun titre, seulement le texte. On prend les premiers caractères du corps.
            String corpsPourTitre = (bigText.length() > text.length()) ? bigText : text;
            final String title = (titreBrut != null && !titreBrut.trim().isEmpty())
                    ? titreBrut
                    : corpsPourTitre.substring(0, Math.min(80, corpsPourTitre.length())).trim();
        
            // 🔴 SÉCURITÉ REGEX : Conservation du texte brut le plus complet pour l'extraction mathématique d'EconomicAnalyzer
            final String bodyTextRaw = bigText.length() > text.length() ? bigText : text;
            // Correction du libellé : le package X/Twitter relaie souvent FinancialJuice
            if ("X /Twitter".equals(sourceName)
                    && (titreBrut + " " + bodyTextRaw).toLowerCase(Locale.ROOT).contains("financialjuice")) {
                sourceName = "FinancialJuice";
            }
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
                            if (MainActivity.instance != null)
                                MainActivity.instance.addLog("[REJET] " + finalSourceName + " | " + title + " → " + validationResult.reason);
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
          // ✅ Source unique : cache WebSocket TradingView (temps réel, gratuit, sans quota).
java.util.Map<String, TradingViewFetcher.TVMarketData> batchSnapshot = null;
try {
    // Filtrer uniquement les actifs suivis par TradingView parmi enrichedAssets
    List<String> twelveFiltered = new ArrayList<>();
    for (String a : enrichedAssets) {
        // Limiter aux 4 actifs core — conserve le même plafond qu'avant
        if (MARKET_PRICE_ASSETS.contains(a)) twelveFiltered.add(a);
        if (twelveFiltered.size() >= 4) break; // Cap strict à 4 actifs
    }
    if (!twelveFiltered.isEmpty()) {
        java.util.Map<String, TradingViewFetcher.TVMarketData> liveCache = TradingViewFetcher.getCache();
        batchSnapshot = new java.util.HashMap<>();
        for (String a : twelveFiltered) {
            TradingViewFetcher.TVMarketData d = liveCache.get(a);
            if (d != null) batchSnapshot.put(a, d);
        }
    } else {
        Log.w(TAG, "[SNAPSHOT] Aucun actif suivi par TradingView parmi les actifs enrichis — snapshot ignoré");
    }
    if (batchSnapshot != null && !batchSnapshot.isEmpty()) {
        StringBuilder sb = new StringBuilder("Données de marché (Live Batch) : ");
        boolean premierActif = true;
             
        // ✅ Utilisation du type de données TradingViewFetcher pour l'itération du snapshot
        for (java.util.Map.Entry<String, TradingViewFetcher.TVMarketData> entry : batchSnapshot.entrySet()) {
            TradingViewFetcher.TVMarketData mData = entry.getValue();
            
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
        marketSnapshotString = "Données de marché indisponibles (cache WebSocket TradingView vide).";
    }
} catch (Exception e) {
    Log.e(TAG, "Échec de la génération du snapshot marché", e);
    marketSnapshotString = "Erreur technique lors de l'acquisition des données.";
}

// Construction du prompt enrichi (utilise la variable String renommée)
String baseSystemPrompt = SYSTEM_PROMPT;
String promptAI = baseSystemPrompt + "\n\n📊 [CONTEXTE MARCHÉ ACTUEL] : " + marketSnapshotString;

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
        long nowMs = System.currentTimeMillis();

        // ── Session Londres — heure locale Europe/London (DST-safe : GMT en hiver, BST+1 en été)
        java.util.Calendar lon = java.util.Calendar.getInstance(
            java.util.TimeZone.getTimeZone("Europe/London"));
        lon.setTimeInMillis(nowMs);
        int dowLon = lon.get(java.util.Calendar.DAY_OF_WEEK);
        if (dowLon == java.util.Calendar.SATURDAY ||
            dowLon == java.util.Calendar.SUNDAY) return false;
        int lonMin = lon.get(java.util.Calendar.HOUR_OF_DAY) * 60
                   + lon.get(java.util.Calendar.MINUTE);
        // 08h00–17h00 heure locale Londres (couvre GMT hiver et BST été automatiquement)
        boolean londonOpen = (lonMin >= 480 && lonMin <= 1020);

        // ── Session New York — heure locale America/New_York (DST-safe : EST hiver, EDT+1 été)
        java.util.Calendar ny = java.util.Calendar.getInstance(
            java.util.TimeZone.getTimeZone("America/New_York"));
        ny.setTimeInMillis(nowMs);
        int nyMin = ny.get(java.util.Calendar.HOUR_OF_DAY) * 60
                  + ny.get(java.util.Calendar.MINUTE);
        // 09h30–21h00 heure locale New York (couvre EST hiver et EDT été automatiquement)
        boolean newYorkOpen = (nyMin >= 570 && nyMin <= 1260);

        return londonOpen || newYorkOpen;
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
        NotificationService svc = serviceInstance;
        ExecutorService pool = (svc != null) ? svc.exec : Executors.newSingleThreadExecutor();
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
        StringBuilder blocPrix = new StringBuilder();
    
        // Déclaré ici — accessible partout dans le try ET le catch
java.util.Map<String, TradingViewFetcher.TVMarketData> batchPrices = null;

if (assets != null && !assets.isEmpty()) {
blocPrix.append("\n\n📊 *COURS INSTANTANÉS AU MOMENT DE L'IMPACT :*");

// Filtrer uniquement les actifs suivis par TradingView — assets complet va à Groq
List<String> twelveAssets = new ArrayList<>();
for (String asset : assets) {
    if (MARKET_PRICE_ASSETS.contains(asset)) {
        twelveAssets.add(asset);
    }
}

if (!twelveAssets.isEmpty()) {
// ✅ Lecture directe du cache WebSocket TradingView — plus de slot/quota à gérer
java.util.Map<String, TradingViewFetcher.TVMarketData> liveCache = TradingViewFetcher.getCache();
batchPrices = new java.util.HashMap<>();
for (String asset : twelveAssets) {
    TradingViewFetcher.TVMarketData d = liveCache.get(asset);
    if (d != null) batchPrices.put(asset, d);
}
        for (String asset : twelveAssets) {
        TradingViewFetcher.TVMarketData data = batchPrices.get(asset);
        if (data != null && data.price > 0) {
                    // 🛡️ HARMONISATION : 🟢/🔴/⚪ remplace 📈/📉 pour rester cohérent avec
                    // les autres points d'affichage de prix (choc macro, snapshot Daily,
                    // injectLivePrices) — un seul code visuel partout dans le bot.
                    String tendance = (data.changePercent > 0) ? "🟢"
                        : (data.changePercent < 0) ? "🔴" : "⚪";
                    String formatPrix = (data.price > 1000) ? "\n%s %s : *%,.2f* (%+.2f%%)" : "\n%s %s : *%.5f* (%+.2f%%)";
                    blocPrix.append(String.format(Locale.US, formatPrix, tendance, asset, data.price, data.changePercent));

                    // 🧠 Verdict quantitatif institutionnel — structure, liquidité, Order Flow
                    String verdict = TradingViewFetcher.buildTechnicalInterpretation(asset, data);
                    blocPrix.append("\n   _").append(verdict).append("_");
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
    });
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
        // 🛡️ Restaurer compteur tokens depuis SharedPreferenc
        SharedPreferences tokenPrefs = getSharedPreferences("TradingBotPrefs", MODE_PRIVATE);
        long savedResetTime = tokenPrefs.getLong("token_reset_time", 0L);
        int savedTokens = tokenPrefs.getInt("daily_tokens_used", 0);
        long nowInit = System.currentTimeMillis();

        // Minuit RÉEL à Madagascar (UTC+3), pas minuit UTC
        java.util.Calendar calMada = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Indian/Antananarivo"));
        calMada.set(java.util.Calendar.HOUR_OF_DAY, 0);
        calMada.set(java.util.Calendar.MINUTE, 0);
        calMada.set(java.util.Calendar.SECOND, 0);
        calMada.set(java.util.Calendar.MILLISECOND, 0);
        calMada.add(java.util.Calendar.DAY_OF_YEAR, 1);
        long prochainMinuitMada = calMada.getTimeInMillis();

        if (nowInit < savedResetTime) {
            dailyTokensUsed.set(savedTokens);
            tokenResetTime = savedResetTime;
            Log.i(TAG, "[TOKEN] Compteur restauré : " + savedTokens + " tokens utilisés.");
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("🔄 [TOKEN] Compteur restauré au démarrage : " + savedTokens + " tokens déjà utilisés (reset prévu à "
                    + new java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRANCE).format(new java.util.Date(tokenResetTime)) + ").");
            }
        } else {
            dailyTokensUsed.set(0);
            tokenResetTime = prochainMinuitMada;
            Log.i(TAG, "[TOKEN] Nouveau jour — compteur remis à zéro.");
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("🔄 [TOKEN] Nouveau jour détecté — compteur remis à zéro au démarrage.");
            }
        }

        // ── Heartbeat toutes les 5 minutes (hors du if/else : doit tourner à chaque démarrage) ──
        scheduler.scheduleAtFixedRate(() -> {
            getSharedPreferences("TradingBotPrefs", MODE_PRIVATE)
                .edit()
                .putLong("last_heartbeat", System.currentTimeMillis())
                .apply();
        }, 0, 5, TimeUnit.MINUTES);
        Log.d(TAG, "💓 Heartbeat programmé toutes les 5 minutes");
    
        
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

                    // Réinitialisation des verrous d'alertes pivots pour la nouvelle journée
                    TradingViewFetcher.rolloverDailyLevels();
                    Log.d(TAG, "[MAINTENANCE] Rollover pivots déclenché — alertFiredPDH/PDL/P4H/PWH/PWL/PMH/PML réarmés.");

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
    }

    private void dispatchHistoricalBulkToGroq(String bulkData) {
        HttpURLConnection conn = null;
        try {
            String apiKey = getGroqApiKey();
            if (apiKey.isEmpty()) return;

            JSONObject payload = new JSONObject();
            payload.put("model", GROQ_MODEL);
            payload.put("temperature", 0.1);
            payload.put("max_tokens", 800);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", "Tu es un Macro-Strategist de premier plan. Analyse ce relevé complet de données à fort impact."));
            messages.put(new JSONObject().put("role", "user").put("content", "DONNÉES EXTRAITES :\n" + bulkData));
            payload.put("messages", messages);

            URL url = new URL(GROQ_URL);
            attendreSiNecessairePourTPM(TOKEN_ESTIMATE_PER_CALL);
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
                attendreSiNecessairePourTPM(TOKEN_ESTIMATE_PER_CALL);
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
                payload.put("max_tokens", 1600);
                JSONArray messages = new JSONArray();

                messages.put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT));

                //String assetSpecs = "Spécifications strictes des Pictogrammes d'Actifs à insérer devant chaque ligne :\n" +
                  //  "GOLD: 🏆, USOIL: 🛢️, NASDAQ: 💻, SP500: 📊, " +
                    //"GBPUSD: 🇬🇧, USDJPY: 🇯🇵";
                //messages.put(new JSONObject().put("role", "system").put("content", assetSpecs));
                messages.put(new JSONObject().put("role", "user").put("content", "Flux brut reçu : " + feed + "\nMémoire contextuelle ordonnée par importance :\n" + history));
                payload.put("messages", messages);
                if (history != null && history.length() > 1000) {
                    history = history.substring(0, 1000) + "\n...[tronqué]";
                }
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
                     boolean isSignificant = !isInclinationNeutral &&(line.contains("🟢") || line.contains("🔴"));

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

    private static volatile long lastCalendarBackfillMillis = 0L;
    private static final long CALENDAR_BACKFILL_GUARD_MS = 30 * 60 * 1000L;
        public void startMonthlyReportScheduler() {
        // ── Rapport MENSUEL — dernier jour du mois à 23h00 (Mada) ──
        Calendar nextMonthly = Calendar.getInstance(TimeZone.getTimeZone("Indian/Antananarivo"));
        nextMonthly.set(Calendar.DAY_OF_MONTH, nextMonthly.getActualMaximum(Calendar.DAY_OF_MONTH));
        nextMonthly.set(Calendar.HOUR_OF_DAY, 23);
        nextMonthly.set(Calendar.MINUTE, 0);
        nextMonthly.set(Calendar.SECOND, 0);
        if (nextMonthly.getTimeInMillis() <= System.currentTimeMillis()) {
            nextMonthly.add(Calendar.MONTH, 1);
            nextMonthly.set(Calendar.DAY_OF_MONTH, nextMonthly.getActualMaximum(Calendar.DAY_OF_MONTH));
        }
        scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                generateAndPurgeMonthlyReport();
                Calendar next = Calendar.getInstance(TimeZone.getTimeZone("Indian/Antananarivo"));
                next.add(Calendar.MONTH, 1);
                next.set(Calendar.DAY_OF_MONTH, next.getActualMaximum(Calendar.DAY_OF_MONTH));
                next.set(Calendar.HOUR_OF_DAY, 23);
                next.set(Calendar.MINUTE, 0);
                next.set(Calendar.SECOND, 0);
                long delayMs = next.getTimeInMillis() - System.currentTimeMillis();
                if (delayMs > 0) scheduler.schedule(this, delayMs, TimeUnit.MILLISECONDS);
            }
        }, nextMonthly.getTimeInMillis() - System.currentTimeMillis(), TimeUnit.MILLISECONDS);

        // ── Rapport HEBDOMADAIRE — chaque vendredi à 22h00 (Mada) ──
        Calendar nextWeekly = Calendar.getInstance(TimeZone.getTimeZone("Indian/Antananarivo"));
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

        checkAndSendMissedWeeklyReport();
        checkAndSendMissedMonthlyReport();
    }
    public boolean generateAndSendDailyBrief() {
    try {
        String apiKey = getGroqApiKey();
        if (apiKey.isEmpty()) {
            Log.w(TAG, "[DAILY] Clé API Groq absente — rapport annulé.");
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("❌ [DAILY] Clé API Groq absente — vérifie l'onglet Clés API.");
            return false;
        }

        long nowSec = System.currentTimeMillis() / 1000;
        String dailyDrivers = eventDb.getDailyMacroSummary(nowSec);

        SimpleDateFormat sdfDate = new SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE);
        sdfDate.setTimeZone(TimeZone.getTimeZone("Indian/Antananarivo"));
        String dateStr = sdfDate.format(new Date());

        if (dailyDrivers == null || dailyDrivers.trim().isEmpty()) {
            Log.w(TAG, "[DAILY] Aucun driver macro trouvé pour les dernières 24h.");
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            long lastReminderTime = prefs.getLong("last_empty_db_reminder", 0);
            long now = System.currentTimeMillis();
            if (now - lastReminderTime > 60 * 60 * 1000) {
                prefs.edit().putLong("last_empty_db_reminder", now).apply();
                String dernierDriverConnu = eventDb.obtenirLeToutDernierDriver();
                if (dernierDriverConnu != null && !dernierDriverConnu.trim().isEmpty()) {
                    sendTelegramSecure("⚠️ *[RAPPEL : AUCUN NOUVEAU DRIVER DEPUIS 24H]*\n" +
                            "🕒 Rapport périodique du " + dateStr + " (Mada)\n\n" +
                            "Le flux de collecte n'a détecté aucun nouveau catalyseur macroéconomique.\n" +
                            "Voici le dernier état de marché enregistré à titre de rappel pour l'équipe :\n\n" +
                            dernierDriverConnu, this);
                } else {
                    sendTelegramSecure("⚪ *RAPPEL SYSTEME :* Base de données entièrement vide. Aucun historique macroéconomique disponible.", this);
                }
            } else {
                Log.d(TAG, "[DAILY] Rappel déjà envoyé récemment, ignoré.");
            }
            return false;
        }

        final int MAX_DAILY_DRIVERS_CHARS = 1500;
        if (dailyDrivers.length() > MAX_DAILY_DRIVERS_CHARS) {
            Log.w(TAG, "[DAILY] dailyDrivers tronqué : " + dailyDrivers.length() + " → " + MAX_DAILY_DRIVERS_CHARS);
            dailyDrivers = dailyDrivers.substring(0, MAX_DAILY_DRIVERS_CHARS) + "\n[...tronqué...]";
        }

        String baseSystemPrompt = construirePromptQuotidienSystem(dailyDrivers, DAILY_SYSTEM_PROMPT);

        SharedPreferences prefs = getSharedPreferences("TradingBotPrefs", MODE_PRIVATE);
        String lastDailyFlow = prefs.getString("last_daily_flow", "NEUTRE / PRUDENCE RECOMMANDÉE");
        String systemPromptFinal = "CONTEXTE HIER (INERTIE DE MARCHÉ) : Le flux dominant de la veille était : " + lastDailyFlow + ".\n" +
                "Si les événements actuels ne contredisent pas ce flux de manière écrasante (>70% de conviction), " +
                "tu peux conserver la MÊME conclusion, mais tu dois quand même justifier le FAIT MARQUANT par les " +
                "événements RÉELS du jour analysés — jamais par la simple continuité du flux de la veille.\n\n" +
                baseSystemPrompt + "\n\n" +
                "Tu es un expert en macroéconomie. Tu dois rédiger ton rapport en terminant obligatoirement par la ligne suivante formatée de cette exacte façon :\n" +
                "🏁 FLUX DOMINANT : [Insère ici le flux sélectionné]";

        String dailyMarketSnapshot = construireSnapshotDaily();

        String currentFlow = getSharedPreferences("TradingBotPrefs", MODE_PRIVATE)
                .getString("last_dominant_flow", "INDÉTERMINÉ");

        String userDaily =
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
                "Si plus de 60% du poids penche vers RISK-OFF, le FLUX DOMINANT doit refléter cette majorité écrasante.";

        ReponseGroq rep = appelerAvecBudget(apiKey, systemPromptFinal, userDaily, 1500, 0.02, 8000);
        if (!rep.ok()) {
            Log.w(TAG, "[DAILY] Échec Groq (HTTP " + rep.status + ") : " + rep.erreur);
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("❌ [DAILY] Échec Groq HTTP " + rep.status + " — " + rep.erreur);
            return false;
        }

        String aiResult = rep.contenu;
        if (aiResult == null || aiResult.trim().length() <= 50) {
            Log.w(TAG, "[DAILY] Groq réponse vide ou insuffisante.");
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("⚠️ [DAILY] Groq réponse vide ou insuffisante");
            return false;
        }

        String footerDaily = construireFooterControles(aiResult, "Daily", null);
        sendTelegramSecure(aiResult.trim() + footerDaily, this);
        Log.d(TAG, "[DAILY] Rapport IA généré et envoyé avec succès.");

        Matcher flowMatcher = Pattern.compile("(?i)🏁\\s*FLUX\\s*DOMINANT\\s*:\\s*(.{3,60})(?:\\n|$)").matcher(aiResult);
        if (flowMatcher.find()) {
            getSharedPreferences("TradingBotPrefs", MODE_PRIVATE).edit()
                    .putString("last_daily_flow", flowMatcher.group(1).trim())
                    .apply();
        } else {
            Log.w(TAG, "[DAILY] Balise '🏁 FLUX DOMINANT :' introuvable. Préférences inchangées.");
        }
        return true;

       } catch (Exception e) {
        Log.e(TAG, "[DAILY] Échec critique lors du traitement du briefing journalier", e);
        StackTraceElement[] pile = e.getStackTrace();
        String ligne = (pile.length > 0) ? pile[0].getFileName() + ":" + pile[0].getLineNumber() : "?";
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("❌ [DAILY] Échec : " + e.getClass().getSimpleName()
                + " — " + e.getMessage() + " @ " + ligne);
        return false;
    }
}
public boolean generateAndPurgeMonthlyReport(boolean purgeAfterSend) {
    try {
        String apiKey = getGroqApiKey();
        if (apiKey.isEmpty()) {
            Log.w(TAG, "[MONTHLY] Clé API Groq absente — rapport annulé.");
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("❌ [MONTHLY] Clé API Groq absente — vérifie l'onglet Clés API.");
            return false;
        }

        long now = System.currentTimeMillis() / 1000;
        String monthlyRegistry = eventDb.getMonthlyMacroRegistry(now);
        final int MAX_MONTHLY_CHARS = 8500;
        if (monthlyRegistry != null && monthlyRegistry.length() > MAX_MONTHLY_CHARS) {
            Log.w(TAG, "[MONTHLY] Registre tronqué : " + monthlyRegistry.length() + " → " + MAX_MONTHLY_CHARS);
            monthlyRegistry = monthlyRegistry.substring(0, MAX_MONTHLY_CHARS) + "\n[...tronqué...]";
        }
        if (monthlyRegistry == null || monthlyRegistry.isEmpty()) {
            Log.w(TAG, "[MONTHLY] Registre mensuel vide — rapport annulé");
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("⚠️ [MONTHLY] Aucune donnée disponible pour le rapport mensuel");
            return false;
        }

        String lastMonthlyFlow = getSharedPreferences("TradingBotPrefs", MODE_PRIVATE)
                .getString("last_monthly_flow", "NEUTRE / DONNÉES INSUFFISANTES");
        String currentFlowM = getSharedPreferences("TradingBotPrefs", MODE_PRIVATE)
                .getString("last_dominant_flow", "INDÉTERMINÉ");

        String userMonthly =
                "⚡ FLUX DOMINANT ACTUEL (dernière analyse live) : " + currentFlowM + "\n" +
                "📊 FLUX MENSUEL PRÉCÉDENT : " + lastMonthlyFlow + "\n" +
                "─────────────────────────────\n" +
                "REGISTRE MACRO DU MOIS :\n" + monthlyRegistry;

        ReponseGroq rep = appelerAvecBudget(apiKey, MONTHLY_SYSTEM_PROMPT, userMonthly, 1500, 0.05, 9000);
        if (!rep.ok()) {
            Log.w(TAG, "[MONTHLY] Échec Groq (HTTP " + rep.status + ") : " + rep.erreur);
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("❌ [MONTHLY] Échec Groq HTTP " + rep.status + " — " + rep.erreur);
            return false;
        }

        String report = rep.contenu;
        if (report == null || report.trim().length() <= 300) {
            Log.w(TAG, "[MONTHLY] Groq réponse vide ou insuffisante — purge annulée");
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("⚠️ [MONTHLY] Rapport mensuel vide — non envoyé");
            return false;
        }

        String footerMonthly = construireFooterControles(report, "Monthly", null);
        sendTelegramSecure("📊 *RAPPORT DE TRANSITION MACROÉCONOMIQUE MENSUEL*\n\n" + report.trim() + footerMonthly, this);

        getSharedPreferences("TradingBotPrefs", MODE_PRIVATE).edit()
                .putLong("last_monthly_sent_ms", System.currentTimeMillis())
                .apply();
        Log.d(TAG, "[MONTHLY] Rapport mensuel envoyé avec succès.");

        Matcher monthlyFlowMatcher = Pattern.compile(
                "(?i)🏁\\s*FLUX\\s*MENSUEL\\s*DOMINANT\\s*:\\s*(.{3,60})(?:\\n|$)").matcher(report);
        if (monthlyFlowMatcher.find()) {
            getSharedPreferences("TradingBotPrefs", MODE_PRIVATE).edit()
                    .putString("last_monthly_flow", monthlyFlowMatcher.group(1).trim())
                    .apply();
        }

        if (MainActivity.instance != null)
            MainActivity.instance.addLog("✅ [MONTHLY] Rapport mensuel envoyé");

        if (purgeAfterSend) {
            eventDb.purgeOldEvents(now);
        } else {
            Log.d(TAG, "[MONTHLY] Purge ignorée (mode test) — registre préservé.");
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("ℹ️ [MONTHLY] Purge ignorée (mode test) — registre préservé.");
        }
        return true;

    } catch (Exception e) {
        Log.e(TAG, "Erreur Rapport Mensuel", e);
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("❌ [MONTHLY] Échec : " + e.getClass().getSimpleName() + " — " + e.getMessage());
        return false;
    }
}

public boolean generateAndPurgeMonthlyReport() {
    return generateAndPurgeMonthlyReport(true);
}
public boolean generateAndSendWeeklyReport() {
    try {
        String apiKey = getGroqApiKey();
        if (apiKey.isEmpty()) {
            Log.w(TAG, "[WEEKLY] Clé API Groq absente — rapport annulé.");
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("❌ [WEEKLY] Clé API Groq absente — vérifie l'onglet Clés API.");
            return false;
        }

        long now = System.currentTimeMillis() / 1000;
        String weeklyRegistry = eventDb.getWeeklyMacroSummary(now);
        final int MAX_WEEKLY_CHARS = 16000;
        if (weeklyRegistry != null && weeklyRegistry.length() > MAX_WEEKLY_CHARS) {
            Log.w(TAG, "[WEEKLY] Registre tronqué : " + weeklyRegistry.length() + " → " + MAX_WEEKLY_CHARS);
            weeklyRegistry = weeklyRegistry.substring(0, MAX_WEEKLY_CHARS) + "\n[...tronqué...]";
        }
        if (weeklyRegistry == null || weeklyRegistry.isEmpty()) {
            Log.w(TAG, "[WEEKLY] Registre hebdo vide — rapport annulé");
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("⚠️ [WEEKLY] Aucune donnée pour le rapport hebdomadaire");
            return false;
        }

        String lastWeeklyFlow = getSharedPreferences("TradingBotPrefs", MODE_PRIVATE)
                .getString("last_weekly_flow", "NEUTRE / DONNÉES INSUFFISANTES");
        String currentFlowW = getSharedPreferences("TradingBotPrefs", MODE_PRIVATE)
                .getString("last_dominant_flow", "INDÉTERMINÉ");

        String userWeekly =
                "⚡ FLUX DOMINANT ACTUEL (dernière analyse live) : " + currentFlowW + "\n" +
                "📅 FLUX HEBDO PRÉCÉDENT : " + lastWeeklyFlow + "\n" +
                "─────────────────────────────\n" +
                "REGISTRE MACRO DE LA SEMAINE :\n" + weeklyRegistry;

        ReponseGroq rep = appelerAvecBudget(apiKey, WEEKLY_SYSTEM_PROMPT, userWeekly, 1600, 0.05, 9000);
        if (!rep.ok()) {
            Log.w(TAG, "[WEEKLY] Échec Groq (HTTP " + rep.status + ") : " + rep.erreur);
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("❌ [WEEKLY] Échec Groq HTTP " + rep.status + " — " + rep.erreur);
            return false;
        }

        String report = rep.contenu;
        if (report == null || report.trim().length() <= 300) {
            Log.w(TAG, "[WEEKLY] Réponse Groq insuffisante");
            return false;
        }

        String footerWeekly = construireFooterControles(report, "Weekly", null);
        sendTelegramSecure("📅 *BILAN MACRO HEBDOMADAIRE — VENDREDI*\n\n" + report.trim() + footerWeekly, this);

        getSharedPreferences("TradingBotPrefs", MODE_PRIVATE).edit()
                .putLong("last_weekly_sent_ms", System.currentTimeMillis())
                .apply();
        Log.d(TAG, "[WEEKLY] Rapport hebdomadaire envoyé.");

        Matcher weeklyMatcher = Pattern.compile(
                "(?i)🏁\\s*FLUX\\s*HEBDO\\s*DOMINANT\\s*:\\s*(.{3,60})(?:\\n|$)").matcher(report);
        if (weeklyMatcher.find()) {
            getSharedPreferences("TradingBotPrefs", MODE_PRIVATE).edit()
                    .putString("last_weekly_flow", weeklyMatcher.group(1).trim())
                    .apply();
        }

        if (MainActivity.instance != null)
            MainActivity.instance.addLog("✅ [WEEKLY] Rapport hebdomadaire envoyé");
        return true;

    } catch (Exception e) {
        Log.e(TAG, "[WEEKLY] Erreur rapport hebdomadaire", e);
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("❌ [WEEKLY] Échec : " + e.getClass().getSimpleName() + " — " + e.getMessage());
        return false;
    }
}
    
/** Snapshot des prix TradingView pour le rapport daily. */
private String construireSnapshotDaily() {
    StringBuilder sb = new StringBuilder("📊 COURS AU MOMENT DU RAPPORT :\n");
    boolean hasData = false;
    try {
        Map<String, TradingViewFetcher.TVMarketData> tvSnap = TradingViewFetcher.getCache();
        for (String asset : MARKET_PRICE_ASSETS) {
            TradingViewFetcher.TVMarketData d = tvSnap.get(asset);
            if (d == null || d.price <= 0) continue;
            String sign = d.changePercent >= 0 ? "+" : "";
            String emoji = d.changePercent > 0 ? "🟢" : (d.changePercent < 0 ? "🔴" : "⚪");
            sb.append(asset).append(" => ")
              .append(String.format(Locale.US, "%.4f (%s%.2f%% %s)", d.price, sign, d.changePercent, emoji));
            if (d.pdh > 0 || d.pdl > 0) {
                sb.append(" | PDH=").append(String.format(Locale.US, "%.4f", d.pdh))
                  .append(" PDL=").append(String.format(Locale.US, "%.4f", d.pdl));
                if (d.brokeAbovePDH) sb.append(" 🔺PDH");
                else if (d.brokeBelowPDL) sb.append(" 🔻PDL");
            }
            if (d.pwh > 0 || d.pwl > 0) {
                sb.append(" | PWH=").append(String.format(Locale.US, "%.4f", d.pwh))
                  .append(" PWL=").append(String.format(Locale.US, "%.4f", d.pwl));
                if (d.brokeAbovePWH) sb.append(" 🚀PWH");
                else if (d.brokeBelowPWL) sb.append(" 🔥PWL");
            }
            sb.append("\n");
            hasData = true;
        }
    } catch (Exception e) {
        Log.w(TAG, "[DAILY] Snapshot marché indisponible : " + e.getMessage());
    }
    return hasData ? sb.toString() : "Données de marché indisponibles.";
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
            boolean chocReelConfirme = false;
            boolean simpleMentionGeo = false;
    
            // ⚠️ Mots qui confirment un IMPACT PHYSIQUE RÉEL sur l'offre d'énergie ou une action militaire actée
            String[] motsClesConfirmation = {
                "hormuz bloqué", "détroit d'hormuz bloqué", "strait of hormuz blocked",
                "embargo appliqué", "coupure des exportations", "arrêt de la production",
                "raffinerie touchée", "raffinerie frappée", "frappe confirmée",
                "riposte militaire", "riposte confirmée", "frappe américaine",
                "bombardement confirmé", "airstrike confirmed", "blocus effectif"
            };
            // ℹ️ Mentions géopolitiques SANS confirmation d'impact réel (contexte seulement, ne force rien)
            String[] motsClesMentionSimple = {
                "hormuz", "ormuz", "iran", "israel", "hezbollah", "houthi",
                "escalade", "tension", "menace", "pourrait", "risque de", "invasion"
            };
    
            String toutLeTexte = (evenementActuel != null ? evenementActuel.toLowerCase() : "");
            if (historiqueRecent != null) {
                for (String hist : historiqueRecent) {
                    if (hist != null) toutLeTexte += " " + hist.toLowerCase();
                }
            }
            for (String mot : motsClesConfirmation) {
                if (toutLeTexte.contains(mot)) { chocReelConfirme = true; break; }
            }
            for (String mot : motsClesMentionSimple) {
                if (toutLeTexte.contains(mot)) { simpleMentionGeo = true; break; }
            }
        
        String directiveDeCrise = "";
        if (chocReelConfirme) {
            directiveDeCrise =
                "⚠️ [ALERTE SYSTÈME : CHOC D'OFFRE RÉEL CONFIRMÉ DÉTECTÉ]. " +
                "DIRECTIVE OBLIGATOIRE ET NON NÉGOCIABLE (calculée en amont, ne pas recalculer) : " +
                "GOLD = 🔴 ou NEUTRE (jamais 🟢, jamais justifié par 'refuge classique') | " +
                "USDJPY = 🟢 (dollar renforcé par la demande pétrole, PAS un refuge ici) | " +
                "GBPUSD = 🔴 | USOIL = 🟢 (choc d'offre = prix qui monte). " +
                "Ta seule tâche : rédiger le mécanisme causal ≤8 mots pour chaque actif AVEC ces directions imposées, " +
                "PAS recalculer les directions toi-même.\n";

            String phaseTexte = EventValidator.getPhaseChocGeoTexte(NotificationService.this);
            if (!phaseTexte.isEmpty()) {
                directiveDeCrise += phaseTexte + "\n";
            }
            directiveDeCrise += "\n";
        } else if (simpleMentionGeo) {
            directiveDeCrise =
                "ℹ️ [CONTEXTE : mention géopolitique détectée SANS confirmation d'impact physique réel]. " +
                "DIRECTIVE OBLIGATOIRE (calculée en amont) : GOLD = 🟢 (refuge classique, pas de contrepoids dollar) | " +
                "USDJPY = 🔴 (yen refuge) | GBPUSD = 🔴 (même sens, régime RISK pur) | " +
                "USOIL = 🟢 (prime de tension). Rédige uniquement le mécanisme causal ≤8 mots pour chaque actif, " +
                "PAS recalculer les directions.\n\n";
        }
            // ✅ Utilise basePrompt (contient le guidage mathématique si présent)
            return directiveDeCrise + basePrompt;
    }

     // Méthode de vérification géographique des mots-clés
    public String construirePromptQuotidienSystem(String registreDeLaJournee, String promptDeBase) {
        boolean chocReelConfirme = false;
    
        // ⚠️ Uniquement des mots qui confirment un impact physique réel sur 24h, pas une simple mention de pays
        String[] motsClesConfirmation = {
            "hormuz bloqué", "détroit d'hormuz bloqué", "strait of hormuz blocked",
            "embargo appliqué", "coupure des exportations", "arrêt de la production",
            "raffinerie touchée", "raffinerie frappée", "frappe confirmée",
            "riposte militaire", "riposte confirmée", "frappe américaine",
            "bombardement confirmé", "airstrike confirmed", "blocus effectif"
        };
    
        String toutLeTexte = (registreDeLaJournee != null) ? registreDeLaJournee.toLowerCase() : "";
        for (String mot : motsClesConfirmation) {
            if (toutLeTexte.contains(mot)) {
                chocReelConfirme = true;
                break;
            }
        }
    
        if (chocReelConfirme) {
            String alerteFlash = 
                "⚠️ [ALERTE SYSTÈME CRITIQUE : CHOC D'OFFRE RÉEL CONFIRMÉ SUR 24H].\n" +
                "Le registre des dernières 24h fait état d'un IMPACT PHYSIQUE CONFIRMÉ sur l'offre d'énergie ou d'une action militaire actée.\n" +
                "CONSIGNE : Applique l'EXCEPTION UNIQUE (section HIÉRARCHIE) pour les 6 actifs, en évaluant d'abord le sens du dollar " +
                "(Étape 1 de la matrice GÉO) avant de conclure sur GOLD — jamais de refuge automatique.\n\n";
            return alerteFlash + promptDeBase;
        }
        // Sinon, on renvoie le prompt standard (la hiérarchie normale s'applique)
        return promptDeBase;
    }

  @Override
  public void onDestroy() {
    super.onDestroy();
    // 🎯 Alerte visible : sans ça, la disparition de la notification est le seul signal,
    // et personne ne la surveille activement.
    sendTelegramSecure("🔴 *TradingBot ARRÊTÉ* — surveillance macro interrompue.\n"
        + "Heure : " + new java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.FRANCE).format(new java.util.Date()),
        getApplicationContext());
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
    
    // 4. Fermeture sécurisée de la base de données
    if (eventDb != null) {
        eventDb.close(); 
    }

    Log.d(TAG, "[SERVICE] Service arrêté proprement");
}
        
}
