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
    
    private static final long TIME_WINDOW_MS = 30 * 60 * 1000; // 30 minutes
    
    private final ExecutorService exec = Executors.newFixedThreadPool(3);
    private EventDatabase eventDb;
    
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
        // Géopolitique
        "war","attack","missile","sanction","conflict","crisis","invasion",
        "nuclear","terror","breaking","urgent","alert","flash",
        "guerre","attaque","conflit","crise",
        
        // Économie
        "fed","rate","inflation","cpi","nfp","gdp","fomc","powell","recession","taux",
        "ecb","boe","boj",
        
        // Actifs
        "gold","xauusd","silver","oil","bitcoin","btc","crypto","etf",
        "dollar","usd","gbp","jpy","eur","nasdaq","sp500","dow",
        
        // Pétrole
        "crude","wti","brent","opec","petroleum","barrel","eia","api",
        
        // Calendrier économique
        "forecast","expected","actual","previous","release","consensus",
        "pmi","ppi","retail","unemployment","jobless","housing"
    );

    private static final String[][] ASSETS = {
        {"GOLD",   "gold,xauusd,or,fed,rate,war,conflict,sanction,nuclear,inflation,powell"},
        {"BTCUSD", "bitcoin,btc,crypto,etf,binance,coinbase"},
        {"GBPUSD", "gbp,pound,boe,uk,britain,sterling,cable"},
        {"USDJPY", "jpy,yen,boj,japan"},
        {"EURUSD", "eur,euro,ecb,europe"},
        {"NASDAQ", "nasdaq,sp500,dow,wall street,stock,tech"},
        {"OIL",    "oil,crude,wti,brent,opec,petroleum,barrel,eia,api"},
        {"USDCAD", "cad,loonie,canada"},
    };

    @Override
    public void onCreate() {
        super.onCreate();
        eventDb = new EventDatabase(this);
        
        exec.submit(() -> processMissedEvents());
        
        scheduler.scheduleAtFixedRate(
            () -> eventDb.cleanOldEvents(),
            1, 24, TimeUnit.HOURS
        );
        
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[SERVICE] Démarré avec filtrage strict");
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

        if (combined.isEmpty() || combined.length() < 20) return;
        if (!isTradingRelevant(combined)) return;

        String appName = getAppName(packageName);
        
        // DÉTECTION ET FILTRAGE STRICT
        EconomicEventDetector.DetectedEvent detectedEvent = 
            EconomicEventDetector.detectEvent(title, full);
        
        // RÈGLE #1: Rejeter si impact = Neutre
        if (!detectedEvent.shouldNotify()) {
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[FILTRE] Rejeté (impact=" + 
                    detectedEvent.impact + "): " + 
                    combined.substring(0, Math.min(50, combined.length())));
            return;
        }
        
        String eventId = generateEventId(appName, title, combined);
        List<String> assets = detectAssets(combined);
        String assetsStr = String.join(", ", assets);
        
        // Sauvegarder avec l'impact
        boolean saved = eventDb.saveEvent(
            eventId, packageName, appName, detectedEvent.eventType, 
            title, combined, assetsStr, detectedEvent.impact
        );
        
        if (!saved) return; // Doublon
        
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[" + detectedEvent.impact.toUpperCase() + "] " + 
                appName + " [" + detectedEvent.eventType + "]: " + 
                detectedEvent.getDescription());

        final String ft = combined;
        final String fa = appName;
        final EconomicEventDetector.DetectedEvent fde = detectedEvent;
        
        exec.submit(() -> processNotificationWithContext(
            this, eventId, fa, ft, fde, assetsStr
        ));
    }

    private void processMissedEvents() {
        List<EventDatabase.StoredEvent> missed = eventDb.getUnprocessedEvents();
        
        if (missed.isEmpty()) return;
        
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[RECOVERY] " + missed.size() + 
                " événements à traiter");
        
        for (EventDatabase.StoredEvent event : missed) {
            try {
                // Vérifier que l'impact est Haussier ou Baissier
                if (!"Haussier".equals(event.impact) && !"Baissier".equals(event.impact)) {
                    eventDb.markProcessed(event.id, "Ignoré (neutre)");
                    continue;
                }
                
                List<EventDatabase.StoredEvent> related = 
                    eventDb.getEventsInTimeWindow(event.timestamp, TIME_WINDOW_MS);
                
                if (related.size() > 1) {
                    processCombinedEvents(this, related);
                } else {
                    processStoredEvent(this, event);
                }
                
            } catch (Exception e) {
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("[RECOVERY] Erreur: " + e.getMessage());
            }
        }
    }

    private static void processNotificationWithContext(
        Context ctx, String eventId, String appName, String text, 
        EconomicEventDetector.DetectedEvent detectedEvent, String assetsStr) {
        
        EventDatabase db = new EventDatabase(ctx);
        List<EventDatabase.StoredEvent> relatedEvents = 
            db.getEventsInTimeWindow(System.currentTimeMillis(), TIME_WINDOW_MS);
        
        boolean hasCombination = relatedEvents.size() > 1;
        StringBuilder contextBuilder = new StringBuilder();
        
        if (hasCombination) {
            contextBuilder.append("CONTEXTE COMBINÉ:\n\n");
            
            for (EventDatabase.StoredEvent event : relatedEvents) {
                contextBuilder.append("[").append(event.impact).append(" - ")
                    .append(event.eventType.toUpperCase()).append("] ")
                    .append(event.title).append("\n");
            }
        }
        
        if (MainActivity.instance != null) {
            String combo = hasCombination ? " COMBINÉE" : "";
            MainActivity.instance.addLog("[BOT] Analyse" + combo + " " + assetsStr + "...");
        }

        String analysis = analyzeWithGroq(
            text, assetsStr, detectedEvent, 
            hasCombination ? contextBuilder.toString() : null
        );

        String ts = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            .format(new Date());

        StringBuilder tgMsg = new StringBuilder();
        
        String emoji = detectedEvent.impact.equals("Haussier") ? "📈" : "📉";
        
        tgMsg.append("*").append(emoji).append(" ");
        tgMsg.append(hasCombination ? "ALERTE COMBINÉE" : "ALERTE TRADING");
        tgMsg.append("* - ").append(ts).append("\n");
        tgMsg.append("Source: ").append(appName);
        tgMsg.append(" [").append(detectedEvent.eventType.toUpperCase()).append("]\n\n");
        
        tgMsg.append("*").append(detectedEvent.getDescription()).append("*\n");
        tgMsg.append("Impact: ").append(detectedEvent.impact).append("\n");
        
        if (detectedEvent.forecast != null && !detectedEvent.forecast.isEmpty()) {
            tgMsg.append("Prévision: ").append(detectedEvent.forecast).append("\n");
        }
        if (detectedEvent.previous != null && !detectedEvent.previous.isEmpty()) {
            tgMsg.append("Précédent: ").append(detectedEvent.previous).append("\n");
        }
        if (detectedEvent.actual != null && !detectedEvent.actual.isEmpty()) {
            tgMsg.append("Actuel: ").append(detectedEvent.actual).append("\n");
        }
        
        tgMsg.append("\nDÉTAILS:\n")
            .append(text.substring(0, Math.min(300, text.length()))).append("\n\n");
        
        if (hasCombination) {
            tgMsg.append("📌 ÉVÉNEMENTS LIÉS:\n");
            for (EventDatabase.StoredEvent event : relatedEvents) {
                if (!event.eventId.equals(eventId)) {
                    tgMsg.append("• [").append(event.impact).append("] ")
                        .append(event.title.substring(0, Math.min(50, event.title.length())))
                        .append("\n");
                }
            }
            tgMsg.append("\n");
        }
        
        tgMsg.append("*ANALYSE:*\n").append(analysis);

        sendTelegram(tgMsg.toString());
        showLocalNotif(ctx, assetsStr, analysis, detectedEvent.impact);
        
        EventDatabase eventDb = new EventDatabase(ctx);
        try {
            int dbId = Integer.parseInt(eventId.substring(eventId.length() - 8), 16);
            eventDb.markProcessed(dbId, analysis);
        } catch (Exception e) {
            // ID parsing failed, skip
        }

        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[OK] Envoyé - " + assetsStr);
    }

    private static String analyzeWithGroq(String text, String assets, 
                                         EconomicEventDetector.DetectedEvent event,
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
            }
            
            prompt.append("ÉVÉNEMENT ").append(event.eventType.toUpperCase()).append(":\n");
            if (event.indicator != null) {
                prompt.append("Indicateur: ").append(event.indicator).append("\n");
            }
            if (event.country != null) {
                prompt.append("Pays: ").append(event.country).append("\n");
            }
            if (event.forecast != null) {
                prompt.append("Prévision: ").append(event.forecast).append("\n");
            }
            if (event.previous != null) {
                prompt.append("Précédent: ").append(event.previous).append("\n");
            }
            if (event.actual != null) {
                prompt.append("Actuel: ").append(event.actual).append("\n");
            }
            prompt.append("IMPACT DÉTECTÉ: ").append(event.impact).append("\n\n");
            
            prompt.append("News: \"").append(text).append("\"\n");
            prompt.append("Actifs: ").append(assets).append("\n\n");
            prompt.append("Analyse COURTE par actif:\n");
            prompt.append("- SIGNAL: BUY/SELL/WAIT\n");
            prompt.append("- RAISON: 1 phrase concise\n");
            prompt.append("- CONVICTION: Faible/Moyenne/Forte\n");
            prompt.append("RÉSUMÉ: [actif] -> [BUY/SELL] (conviction)");

            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", "Analyste financier expert. Réponds en français, " +
                "sois concis et actionnable.");

            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", prompt.toString());

            JSONArray messages = new JSONArray();
            messages.put(systemMsg);
            messages.put(userMsg);

            JSONObject body = new JSONObject();
            body.put("model", GROQ_MODEL);
            body.put("messages", messages);
            body.put("max_tokens", 1024);
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

        InputStream is = (responseCode == 200) 
            ? c.getInputStream() : c.getErrorStream();

        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        c.disconnect();

        if (responseCode == 200) {
            JSONObject resp = new JSONObject(sb.toString());
            return resp.getJSONArray("choices")
                       .getJSONObject(0)
                       .getJSONObject("message")
                       .getString("content");
        } else {
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[GROQ] Erreur " + responseCode);
            return "Erreur Groq " + responseCode;
        }
    }

    private static void sendTelegram(String message) {
        // Retry avec backoff
        sendTelegramWithRetry(message, 0);
    }
    
    private static void sendTelegramWithRetry(String message, int attempt) {
        if (attempt >= 3) {
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[TG] Abandon après 3 tentatives");
            return;
        }
        
        try {
            String enc = URLEncoder.encode(message, "UTF-8");
            URL url = new URL("https://api.telegram.org/bot" + 
                MainActivity.TELEGRAM_TOKEN + "/sendMessage?chat_id=" + 
                MainActivity.TELEGRAM_CHAT_ID + "&text=" + enc + 
                "&parse_mode=Markdown");
            
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            int code = c.getResponseCode();
            c.disconnect();
            
            if (code == 200) {
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("[TG] Envoyé OK");
            } else {
                throw new IOException("HTTP " + code);
            }
        } catch (Exception e) {
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[TG] Erreur (retry " + (attempt+1) + "): " + 
                    e.getMessage());
            
            // Attendre avant retry (1s, 3s, 9s)
            try {
                Thread.sleep(1000 * (long)Math.pow(3, attempt));
            } catch (InterruptedException ie) {}
            
            sendTelegramWithRetry(message, attempt + 1);
        }
    }

    private static void showLocalNotif(Context ctx, String assets, 
                                      String analysis, String impact) {
        NotificationManager nm = (NotificationManager)
            ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Trading Alerts", NotificationManager.IMPORTANCE_HIGH));
        
        String emoji = impact.equals("Haussier") ? "📈" : "📉";
        String title = emoji + " " + impact + " - " + assets;
        
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
        processNotification(ctx, event.appName, event.content);
    }

    private static void processCombinedEvents(Context ctx, 
                                             List<EventDatabase.StoredEvent> events) {
        // Traiter le groupe d'événements
        if (events.isEmpty()) return;
        
        EventDatabase.StoredEvent first = events.get(0);
        processNotification(ctx, first.appName, first.content);
    }

    public static void processNotification(Context ctx, String appName, String text) {
        List<String> assets = detectAssets(text);
        String assetsStr = String.join(", ", assets);

        EconomicEventDetector.DetectedEvent event = 
            EconomicEventDetector.detectEvent("", text);
        
        if (!event.shouldNotify()) return; // Filtrer si neutre

        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[BOT] Analyse " + assetsStr + "...");

        String analysis = analyzeWithGroq(text, assetsStr, event, null);

        String ts = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            .format(new Date());

        String emoji = event.impact.equals("Haussier") ? "📈" : "📉";
        String tgMsg = "*" + emoji + " ALERTE TRADING* - " + ts + "\n" +
            "Source: " + appName + "\n\n" +
            "NEWS:\n" + text.substring(0, Math.min(300, text.length())) + "\n\n" +
            "ANALYSE:\n" + analysis;

        sendTelegram(tgMsg);
        showLocalNotif(ctx, assetsStr, analysis, event.impact);

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
