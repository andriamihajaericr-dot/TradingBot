package com.tradingbot.analyzer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import androidx.core.app.NotificationCompat;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public class NotificationService extends NotificationListenerService {

    private static final String CHANNEL_ID = "trading_alerts";
    private static final int NOTIF_ID = 2001;
    private static final String GROQ_MODEL = "llama-3.3-70b-versatile";
    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    
    // Fenêtre de temps pour combiner les événements (30 minutes)
    private static final long TIME_WINDOW_MS = 30 * 60 * 1000;
    
    private final ExecutorService exec = Executors.newFixedThreadPool(5);
    private EventDatabase eventDb;
    
    // File d'attente pour traiter les événements manqués
    private final ScheduledExecutorService scheduler = 
        Executors.newScheduledThreadPool(1);

    private static final List<String> ALLOWED_APPS = Arrays.asList(
        "com.twitter.android",
        "com.brave.browser",
        "com.android.chrome",
        "com.chrome.beta",
        "org.mozilla.firefox",
        "org.mozilla.fenix",
        "com.coinglass.android",
        "com.financialjuice.androidapp",
        "com.investing.app",
        "com.reuters.news"
    );

    private static final List<String> KEYWORDS = Arrays.asList(
        "war","attack","missile","sanction","conflict","crisis","invasion",
        "nuclear","terror","breaking","urgent","alert","flash",
        "guerre","attaque","conflit","crise",
        "fed","rate","inflation","cpi","nfp","gdp","fomc","powell","recession","taux",
        "gold","xauusd","silver","oil","bitcoin","btc","crypto","etf",
        "dollar","usd","gbp","jpy","eur","nasdaq","sp500","dow",
        "reuters","bloomberg","breaking news",
        // Ajout pour événements économiques
        "forecast","expected","actual","previous","release","consensus",
        "pmi","ppi","retail","unemployment","jobless","housing","trade"
    );

    private static final String[][] ASSETS = {
        {"GOLD",   "gold,xauusd,or,fed,rate,war,conflict,sanction,nuclear,inflation,powell"},
        {"BTCUSD", "bitcoin,btc,crypto,etf,binance,coinbase,hack,sec"},
        {"GBPUSD", "gbp,pound,boe,uk,britain,sterling"},
        {"USDJPY", "jpy,yen,boj,japan"},
        {"NASDAQ", "nasdaq,sp500,dow,wall street,stock,tech"},
    };

    @Override
    public void onCreate() {
        super.onCreate();
        eventDb = new EventDatabase(this);
        
        // Traiter les événements manqués au démarrage
        exec.submit(() -> processMissedEvents());
        
        // Nettoyer les vieux événements périodiquement
        scheduler.scheduleAtFixedRate(
            () -> eventDb.cleanOldEvents(),
            1, 24, TimeUnit.HOURS
        );
        
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[SERVICE] Démarrage avec persistance");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        boolean botActive = getSharedPreferences("TradingBot", MODE_PRIVATE)
            .getBoolean("bot_active", false);
        if (!botActive) return;

        String packageName = sbn.getPackageName();
        if (!isAllowedApp(packageName)) return;

        Bundle extras = sbn.getNotification().extras;
        String title = extras.getString(Notification.EXTRA_TITLE, "");
        String text = extras.getString(Notification.EXTRA_TEXT, "");
        String bigText = extras.getString(Notification.EXTRA_BIG_TEXT, "");
        String full = bigText.isEmpty() ? text : bigText;
        String combined = (title + " " + full).trim();

        if (combined.isEmpty() || !isTradingRelevant(combined)) return;

        String appName = getAppName(packageName);
        
        // Générer un ID unique pour l'événement
        String eventId = generateEventId(appName, title, combined);
        
        // Déterminer le type d'événement
        String eventType = "news"; // Par défaut
        EconomicEventDetector.EconomicEvent economicEvent = null;
        
        // Détecter si c'est un événement économique
        if (appName.equals("FinancialJuice") || appName.equals("Investing.com")) {
            economicEvent = EconomicEventDetector.parseEconomicEvent(title, full);
            if (economicEvent != null && economicEvent.isComplete()) {
                eventType = "economic";
            }
        }
        
        List<String> assets = detectAssets(combined);
        String assetsStr = String.join(", ", assets);
        
        // Sauvegarder dans la DB
        boolean saved = eventDb.saveEvent(
            eventId, packageName, appName, eventType, 
            title, combined, assetsStr
        );
        
        if (!saved) {
            // Événement déjà traité (doublon)
            return;
        }
        
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[NOTIF] " + appName + " [" + eventType + "]: " 
                + combined.substring(0, Math.min(60, combined.length())) + "...");

        final String ft = combined;
        final String fa = appName;
        final String fet = eventType;
        final EconomicEventDetector.EconomicEvent fee = economicEvent;
        
        exec.submit(() -> processNotificationWithContext(
            this, eventId, fa, ft, fet, fee, assetsStr
        ));
    }

    // =========================================================
    //  TRAITER LES ÉVÉNEMENTS MANQUÉS
    // =========================================================
    private void processMissedEvents() {
        List<EventDatabase.StoredEvent> missed = eventDb.getUnprocessedEvents();
        
        if (missed.isEmpty()) return;
        
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[RECOVERY] " + missed.size() 
                + " événements non traités trouvés");
        
        for (EventDatabase.StoredEvent event : missed) {
            try {
                // Rechercher des événements liés dans la fenêtre de temps
                List<EventDatabase.StoredEvent> related = 
                    eventDb.getEventsInTimeWindow(event.timestamp, TIME_WINDOW_MS);
                
                if (related.size() > 1) {
                    // Événement combiné
                    processCombinedEvents(this, related);
                } else {
                    // Événement simple
                    processStoredEvent(this, event);
                }
                
            } catch (Exception e) {
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("[RECOVERY] Erreur: " + e.getMessage());
            }
        }
    }

    // =========================================================
    //  TRAITER AVEC CONTEXTE (NOUVEAU PIPELINE)
    // =========================================================
    private static void processNotificationWithContext(
        Context ctx, String eventId, String appName, String text, 
        String eventType, EconomicEventDetector.EconomicEvent economicData,
        String assetsStr) {
        
        // Rechercher des événements dans la fenêtre de temps
        EventDatabase db = new EventDatabase(ctx);
        List<EventDatabase.StoredEvent> relatedEvents = 
            db.getEventsInTimeWindow(System.currentTimeMillis(), TIME_WINDOW_MS);
        
        boolean hasCombination = false;
        StringBuilder contextBuilder = new StringBuilder();
        
        // Vérifier s'il y a des événements économiques ET des news
        boolean hasEconomic = eventType.equals("economic");
        boolean hasNews = false;
        
        for (EventDatabase.StoredEvent event : relatedEvents) {
            if (event.eventType.equals("economic")) hasEconomic = true;
            if (event.eventType.equals("news")) hasNews = true;
        }
        
        hasCombination = hasEconomic && hasNews && relatedEvents.size() > 1;
        
        if (hasCombination) {
            // Construire le contexte combiné
            contextBuilder.append("CONTEXTE COMBINÉ:\n\n");
            
            for (EventDatabase.StoredEvent event : relatedEvents) {
                contextBuilder.append("[").append(event.eventType.toUpperCase())
                    .append("] ").append(event.title).append("\n");
                contextBuilder.append(event.content.substring(
                    0, Math.min(200, event.content.length()))).append("\n\n");
            }
        }
        
        if (MainActivity.instance != null) {
            if (hasCombination) {
                MainActivity.instance.addLog("[BOT] Analyse COMBINÉE " + assetsStr 
                    + " (" + relatedEvents.size() + " événements)...");
            } else {
                MainActivity.instance.addLog("[BOT] Analyse " + assetsStr + "...");
            }
        }

        // Analyse avec Groq
        String analysis = analyzeWithGroq(
            text, assetsStr, eventType, economicData, 
            hasCombination ? contextBuilder.toString() : null
        );

        String ts = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            .format(new Date());

        // Message Telegram enrichi
        StringBuilder tgMsg = new StringBuilder();
        tgMsg.append("*").append(hasCombination ? "🔄 ALERTE COMBINÉE" : "⚡ ALERTE TRADING")
            .append("* - ").append(ts).append("\n");
        tgMsg.append("Source: ").append(appName);
        
        if (eventType.equals("economic") && economicData != null) {
            tgMsg.append(" [📊 ÉCONOMIQUE]\n\n");
            tgMsg.append("*").append(economicData.indicator).append("* - ")
                .append(economicData.country).append("\n");
            tgMsg.append("Impact: ").append(economicData.impact).append("\n");
            if (!economicData.forecast.equals("N/A"))
                tgMsg.append("Prévision: ").append(economicData.forecast).append("\n");
            if (!economicData.previous.equals("N/A"))
                tgMsg.append("Précédent: ").append(economicData.previous).append("\n");
            if (!economicData.actual.equals("N/A"))
                tgMsg.append("Actuel: ").append(economicData.actual).append("\n");
        } else {
            tgMsg.append(" [📰 NEWS]\n\n");
        }
        
        tgMsg.append("\nDÉTAILS:\n").append(text.substring(0, Math.min(300, text.length())))
            .append("\n\n");
        
        if (hasCombination) {
            tgMsg.append("📌 ÉVÉNEMENTS LIÉS:\n");
            for (EventDatabase.StoredEvent event : relatedEvents) {
                if (!event.eventId.equals(eventId)) {
                    tgMsg.append("• [").append(event.eventType).append("] ")
                        .append(event.title.substring(0, Math.min(50, event.title.length())))
                        .append("\n");
                }
            }
            tgMsg.append("\n");
        }
        
        tgMsg.append("ANALYSE:\n").append(analysis);

        sendTelegram(tgMsg.toString());
        showLocalNotif(ctx, assetsStr, analysis, hasCombination);
        
        // Marquer comme traité
        EventDatabase eventDb = new EventDatabase(ctx);
        eventDb.markProcessed(
            Integer.parseInt(eventId.split("_")[eventId.split("_").length - 1]), 
            analysis
        );

        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[OK] Envoyé - " + assetsStr);
    }

    // =========================================================
    //  ANALYSE GROQ AMÉLIORÉE
    // =========================================================
    private static String analyzeWithGroq(String text, String assets, 
                                         String eventType, 
                                         EconomicEventDetector.EconomicEvent economicData,
                                         String combinedContext) {
        try {
            if (MainActivity.CLAUDE_API_KEY == null || 
                MainActivity.CLAUDE_API_KEY.trim().isEmpty()) {
                return "Clé Groq API non configurée";
            }

            StringBuilder prompt = new StringBuilder();
            prompt.append("Tu es analyste financier expert en trading.\n\n");
            
            if (combinedContext != null) {
                prompt.append(combinedContext).append("\n");
                prompt.append("CONSIGNE: Analyse l'impact COMBINÉ de ces événements.\n\n");
            }
            
            if (eventType.equals("economic") && economicData != null) {
                prompt.append("ÉVÉNEMENT ÉCONOMIQUE:\n");
                prompt.append("Indicateur: ").append(economicData.indicator).append("\n");
                prompt.append("Pays: ").append(economicData.country).append("\n");
                prompt.append("Prévision: ").append(economicData.forecast).append("\n");
                prompt.append("Précédent: ").append(economicData.previous).append("\n");
                prompt.append("Actuel: ").append(economicData.actual).append("\n");
                prompt.append("Impact: ").append(economicData.impact).append("\n\n");
            }
            
            prompt.append("News: \"").append(text).append("\"\n");
            prompt.append("Actifs concernés: ").append(assets).append("\n\n");
            prompt.append("Analyse détaillée par actif:\n");
            prompt.append("IMPACT: Haussier/Baissier/Neutre\n");
            prompt.append("SIGNAL: BUY/SELL/WAIT\n");
            prompt.append("RAISON: 1 phrase claire\n");
            prompt.append("CONVICTION: Faible/Moyenne/Forte\n");
            if (economicData != null) {
                prompt.append("ÉCART PRÉVISION: Analyse si actuel vs forecast significatif\n");
            }
            prompt.append("RÉSUMÉ: [actif] -> [BUY/SELL/WAIT]");

            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", "Tu es un analyste financier expert. " +
                "Réponds toujours en français. Sois précis et actionnable.");

            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", prompt.toString());

            JSONArray messages = new JSONArray();
            messages.put(systemMsg);
            messages.put(userMsg);

            JSONObject body = new JSONObject();
            body.put("model", GROQ_MODEL);
            body.put("messages", messages);
            body.put("max_tokens", 1536);
            body.put("temperature", 0.3);

            return callGroqAPI(body.toString());

        } catch (Exception e) {
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[GROQ] Exception: " + e.getMessage());
            return "Erreur: " + e.getMessage();
        }
    }

    private static String callGroqAPI(String bodyStr) throws Exception {
        URL url = new URL(GROQ_URL);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + 
            MainActivity.CLAUDE_API_KEY.trim());
        c.setDoOutput(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);

        OutputStream os = c.getOutputStream();
        os.write(bodyStr.getBytes("UTF-8"));
        os.flush();
        os.close();

        int responseCode = c.getResponseCode();

        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[GROQ] HTTP: " + responseCode);

        InputStream is = (responseCode == 200) 
            ? c.getInputStream() : c.getErrorStream();

        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        c.disconnect();

        String responseBody = sb.toString();

        if (responseCode == 200) {
            JSONObject resp = new JSONObject(responseBody);
            return resp.getJSONArray("choices")
                       .getJSONObject(0)
                       .getJSONObject("message")
                       .getString("content");
        } else {
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[GROQ] Erreur: " + 
                    responseBody.substring(0, Math.min(200, responseBody.length())));
            return "Erreur Groq " + responseCode;
        }
    }

    // =========================================================
    //  TELEGRAM
    // =========================================================
    private static void sendTelegram(String message) {
        try {
            String enc = URLEncoder.encode(message, "UTF-8");
            URL url = new URL("https://api.telegram.org/bot" + 
                MainActivity.TELEGRAM_TOKEN + "/sendMessage?chat_id=" + 
                MainActivity.TELEGRAM_CHAT_ID + "&text=" + enc + 
                "&parse_mode=Markdown");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10000);
            c.getResponseCode();
            c.disconnect();
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[TG] Envoyé OK");
        } catch (Exception e) {
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[TG] Erreur: " + e.getMessage());
        }
    }

    // =========================================================
    //  NOTIFICATION LOCALE
    // =========================================================
    private static void showLocalNotif(Context ctx, String assets, 
                                      String analysis, boolean isCombined) {
        NotificationManager nm = (NotificationManager)
            ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Trading Alerts", NotificationManager.IMPORTANCE_HIGH));
        
        String title = isCombined 
            ? "🔄 Signal Combiné - " + assets 
            : "⚡ Signal Trading - " + assets;
        
        String summary = analysis.length() > 150
            ? analysis.substring(0, 150) + "..." : analysis;
        
        nm.notify(NOTIF_ID, new NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(analysis))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setVibrate(new long[]{0, 300, 100, 300})
            .build());
    }

    // =========================================================
    //  HELPERS
    // =========================================================
    private boolean isAllowedApp(String packageName) {
        for (String allowed : ALLOWED_APPS)
            if (packageName.toLowerCase().contains(allowed.toLowerCase()))
                return true;
        return false;
    }

    private boolean isTradingRelevant(String text) {
        String lower = text.toLowerCase();
        for (String kw : KEYWORDS) 
            if (lower.contains(kw)) return true;
        return false;
    }

    private static List<String> detectAssets(String text) {
        String lower = text.toLowerCase();
        List<String> found = new ArrayList<>();
        for (String[] a : ASSETS)
            for (String kw : a[1].split(","))
                if (lower.contains(kw.trim()) && !found.contains(a[0])) {
                    found.add(a[0]); 
                    break;
                }
        if (found.isEmpty()) { 
            found.add("GOLD"); 
            found.add("BTCUSD"); 
        }
        return found;
    }

    private String getAppName(String pkg) {
        if (pkg.contains("twitter") || pkg.contains(".x")) return "X/Twitter";
        if (pkg.contains("brave")) return "Brave";
        if (pkg.contains("chrome")) return "Chrome";
        if (pkg.contains("firefox")) return "Firefox";
        if (pkg.contains("coinglass")) return "Coinglass";
        if (pkg.contains("financial")) return "FinancialJuice";
        if (pkg.contains("investing")) return "Investing.com";
        if (pkg.contains("reuters")) return "Reuters";
        return pkg;
    }

    private static String generateEventId(String app, String title, String content) {
        try {
            String data = app + title + content.substring(0, Math.min(100, content.length()));
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString().substring(0, 16);
        } catch (Exception e) {
            return UUID.randomUUID().toString().substring(0, 16);
        }
    }

    private static void processStoredEvent(Context ctx, EventDatabase.StoredEvent event) {
        // Traiter un événement stocké simple
        processNotification(ctx, event.appName, event.content);
    }

    private static void processCombinedEvents(Context ctx, 
                                             List<EventDatabase.StoredEvent> events) {
        // Traiter un groupe d'événements combinés
        // (implémentation similaire à processNotificationWithContext)
    }

    public static void processNotification(Context ctx, String appName, String text) {
        List<String> assets = detectAssets(text);
        String assetsStr = String.join(", ", assets);

        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[BOT] Analyse " + assetsStr + "...");

        String analysis = analyzeWithGroq(text, assetsStr, "news", null, null);

        String ts = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            .format(new Date());

        String tgMsg = "*ALERTE TRADING* - " + ts + "\n" +
            "Source: " + appName + "\n\n" +
            "NEWS:\n" + text.substring(0, Math.min(300, text.length())) + "\n\n" +
            "ANALYSE:\n" + analysis;

        sendTelegram(tgMsg);
        showLocalNotif(ctx, assetsStr, analysis, false);

        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[OK] Envoyé - " + assetsStr);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        exec.shutdown();
        scheduler.shutdown();
        if (eventDb != null) eventDb.close();
    }
}
