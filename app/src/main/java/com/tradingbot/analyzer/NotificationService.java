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

    // Cache pour daily report par actif
    private static final Map<String, List<DailyReportEntry>> dailyReportByAsset = 
        new ConcurrentHashMap<>();
    
    // Horaires des rapports (format 24h)
    private static final int[] REPORT_HOURS = {8, 12, 16, 17, 21};
    private static final int[] REPORT_MINUTES = {55, 55, 30, 0, 0};
    
    // Tracker des rapports envoyés aujourd'hui
    private static final Set<String> sentReportsToday = new HashSet<>();

    // Applications autorisées - RÉDUITES aux meilleures sources
    private static final List<String> ALLOWED_APPS = Arrays.asList(
        "com.twitter.android",              // X/Twitter
        "com.financialjuice.androidapp",    // FinancialJuice
        "com.investing.app",                // Investing.com
        "com.reuters.news"                  // Reuters
    );

    // Mots-clés trading
    private static final List<String> KEYWORDS = Arrays.asList(
        // Géopolitique
        "war","attack","missile","sanction","conflict","crisis","invasion",
        "nuclear","terror","breaking","urgent","alert","flash",
        "guerre","attaque","conflit","crise",
        
        // Économie
        "fed","rate","inflation","cpi","nfp","gdp","fomc","powell","recession","taux",
        "ecb","boe","boj","bank of england","bank of japan",
        
        // Actifs
        "gold","xauusd","silver","oil","bitcoin","btc","crypto","etf",
        "dollar","usd","gbp","jpy","eur","nasdaq","sp500","dow",
        
        // Pétrole
        "crude","wti","brent","opec","petroleum","barrel","eia","api",
        
        // Calendrier économique
        "forecast","expected","actual","previous","release","consensus",
        "pmi","ppi","retail","unemployment","jobless","housing"
    );

    // Actifs détectables
    private static final String[][] ASSETS = {
        {"GOLD",   "gold,xauusd,or,fed,rate,war,conflict,sanction,nuclear,inflation,powell,safe haven"},
        {"BTCUSD", "bitcoin,btc,crypto,etf,binance,coinbase,blockchain,ethereum"},
        {"GBPUSD", "gbp,pound,boe,uk,britain,sterling,cable,bank of england,bailey"},
        {"USDJPY", "jpy,yen,boj,japan,ueda,kuroda,intervention,yield curve"},
        {"EURUSD", "eur,euro,ecb,europe,lagarde,draghi"},
        {"SP500",  "sp500,s&p 500,s&p500,spx,spy,stock market,wall street,earnings"},
        {"NASDAQ", "nasdaq,ndx,qqq,tech stocks,tech,vix,apple,tesla,nvidia,microsoft"},
        {"OIL",    "oil,crude,wti,brent,opec,petroleum,barrel,eia,api,energy,saudi,russia oil"},
        {"USDCAD", "cad,loonie,canada,boc"},
    };

    // Priorités des comptes X/Twitter
    private static final Map<String, Integer> PRIORITY_ACCOUNTS = new HashMap<>();

    static {
        // PRIORITÉ CRITIQUE 5/5
        PRIORITY_ACCOUNTS.put("fxhedgers", 5);
        PRIORITY_ACCOUNTS.put("deltaone", 5);
        PRIORITY_ACCOUNTS.put("firstsquawk", 5);
        PRIORITY_ACCOUNTS.put("livesquawk", 5);
        PRIORITY_ACCOUNTS.put("financialjuice", 5);
        PRIORITY_ACCOUNTS.put("kobeissiletter", 5);
        PRIORITY_ACCOUNTS.put("nick_timiraos", 5);
        
        // PRIORITÉ HAUTE 4/5
        PRIORITY_ACCOUNTS.put("federalreserve", 4);
        PRIORITY_ACCOUNTS.put("bankofengland", 4);
        PRIORITY_ACCOUNTS.put("boj_en", 4);
        PRIORITY_ACCOUNTS.put("eiagov", 4);
        PRIORITY_ACCOUNTS.put("javierblas", 4);
        PRIORITY_ACCOUNTS.put("watcherguru", 4);
        PRIORITY_ACCOUNTS.put("coinglass", 4);
        PRIORITY_ACCOUNTS.put("zerohedge", 4);
        PRIORITY_ACCOUNTS.put("markets", 4);
        PRIORITY_ACCOUNTS.put("schuldensuehner", 4);
        PRIORITY_ACCOUNTS.put("reutersuk", 4);
        PRIORITY_ACCOUNTS.put("reutersjapan", 4);
    }

    // Mots-clés spécifiques par actif
    private static final Map<String, String[]> ASSET_SPECIFIC_KEYWORDS = new HashMap<>();

    static {
        ASSET_SPECIFIC_KEYWORDS.put("GBPUSD", new String[]{
            "bank of england", "boe", "bailey", "uk inflation", "uk cpi",
            "uk pmi", "uk gdp", "sterling", "cable"
        });
        
        ASSET_SPECIFIC_KEYWORDS.put("USDJPY", new String[]{
            "bank of japan", "boj", "ueda", "yen", "jpy",
            "japan cpi", "japan gdp", "intervention"
        });
        
        ASSET_SPECIFIC_KEYWORDS.put("SP500", new String[]{
            "sp500", "s&p 500", "s&p", "spx", "spy", "earnings", 
            "fomc", "stock market", "wall street", "market open", "market close"
        });
        
        ASSET_SPECIFIC_KEYWORDS.put("NASDAQ", new String[]{
            "nasdaq", "ndx", "qqq", "tech stocks", "vix", 
            "apple", "microsoft", "nvidia", "tesla"
        });
        
        ASSET_SPECIFIC_KEYWORDS.put("OIL", new String[]{
            "eia", "api", "opec", "crude", "wti", "brent", "barrel"
        });
        
        ASSET_SPECIFIC_KEYWORDS.put("GOLD", new String[]{
            "gold", "xauusd", "safe haven", "fed", "powell"
        });
        
        ASSET_SPECIFIC_KEYWORDS.put("BTCUSD", new String[]{
            "bitcoin", "btc", "crypto", "ethereum", "sec crypto"
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        eventDb = new EventDatabase(this);
        
        // Initialiser le cache par actif
        for (String[] asset : ASSETS) {
            dailyReportByAsset.put(asset[0], 
                Collections.synchronizedList(new ArrayList<>()));
        }
        
        exec.submit(() -> processMissedEvents());
        
        // Nettoyer vieux événements quotidiennement
        scheduler.scheduleAtFixedRate(
            () -> eventDb.cleanOldEvents(),
            1, 24, TimeUnit.HOURS
        );
        
        // Vérifier toutes les minutes si un rapport doit être envoyé
        scheduler.scheduleAtFixedRate(
            () -> checkAndSendScheduledReports(),
            0, 1, TimeUnit.MINUTES
        );
        
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[SERVICE] Démarré - Rapports: 8h55, 12h55, 16h30, 17h, 21h");
    }

    // Classe pour stocker les entrées du rapport
    private static class DailyReportEntry {
        String timestamp;
        String impact;
        String eventType;
        String description;
        String summary;
        String signal; // BUY/SELL/WAIT
        
        DailyReportEntry(String timestamp, String impact, String eventType, 
                         String description, String summary, String signal) {
            this.timestamp = timestamp;
            this.impact = impact;
            this.eventType = eventType;
            this.description = description;
            this.summary = summary;
            this.signal = signal;
        }
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
        
        String appName = getAppName(packageName);
        List<String> assets = detectAssets(combined);
        
        // FILTRAGE STRICT POUR X/TWITTER
        if (appName.equals("X/Twitter")) {
            int accountPriority = getAccountPriority(combined);
            
            if (accountPriority < 4) {
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("[FILTRE] Compte X non prioritaire ignoré");
                return;
            }
            
            if (accountPriority == 4 && !hasAssetSpecificKeywords(combined, assets)) {
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("[FILTRE] Pas de keywords pertinents");
                return;
            }
            
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[X] Priorité " + accountPriority + "/5 ✓");
        }
        
        if (!isTradingRelevant(combined)) return;

        // DÉTECTION ET FILTRAGE STRICT PAR IMPACT
        EconomicEventDetector.DetectedEvent detectedEvent = 
            EconomicEventDetector.detectEvent(title, full);
        
        if (!detectedEvent.shouldNotify()) {
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[FILTRE] Impact neutre - rejeté: " + 
                    combined.substring(0, Math.min(50, combined.length())));
            return;
        }
        
        String eventId = generateEventId(appName, title, combined);
        String assetsStr = String.join(", ", assets);
        
        boolean saved = eventDb.saveEvent(
            eventId, packageName, appName, detectedEvent.eventType, 
            title, combined, assetsStr, detectedEvent.impact
        );
        
        if (!saved) return;
        
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[" + detectedEvent.impact.toUpperCase() + "] " + 
                appName + " [" + detectedEvent.eventType + "]: " + 
                detectedEvent.getDescription());

        final String ft = combined;
        final String fa = appName;
        final EconomicEventDetector.DetectedEvent fde = detectedEvent;
        final List<String> fAssets = assets;
        
        exec.submit(() -> processNotificationWithContext(
            this, eventId, fa, ft, fde, assetsStr, fAssets
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
        EconomicEventDetector.DetectedEvent detectedEvent, String assetsStr,
        List<String> assetsList) {
        
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
        tgMsg.append("Impact Général: ").append(detectedEvent.impact).append("\n");
        
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
        
        tgMsg.append("*ANALYSE PAR ACTIF:*\n").append(analysis);

        sendTelegram(tgMsg.toString());
        showLocalNotif(ctx, assetsStr, analysis, detectedEvent.impact);
        
        // Sauvegarder pour daily report par actif
        saveToDailyReport(detectedEvent, text, analysis, assetsList);
        
        EventDatabase eventDb = new EventDatabase(ctx);
        try {
            int dbId = Integer.parseInt(eventId.substring(eventId.length() - 8), 16);
            eventDb.markProcessed(dbId, analysis);
        } catch (Exception e) {
            // Ignore
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
            
            prompt.append("\nIMPORTANT: Impact général détecté = ").append(event.impact);
            prompt.append("\nCeci est l'impact MACRO. Analyse maintenant l'impact SPÉCIFIQUE par actif.\n");
            prompt.append("Exemple: Si impact général = Baissier (risk-off), alors:\n");
            prompt.append("- GOLD: HAUSSIER (valeur refuge)\n");
            prompt.append("- NASDAQ: BAISSIER (aversion au risque)\n");
            prompt.append("- USD: peut être HAUSSIER (dollar fort)\n\n");
            
            prompt.append("News: \"").append(text).append("\"\n");
            prompt.append("Actifs à analyser: ").append(assets).append("\n\n");
            
            prompt.append("Donne une analyse TRÈS COURTE pour CHAQUE actif listé:\n");
            prompt.append("Format strict par actif (max 2 lignes):\n");
            prompt.append("[ACTIF]: IMPACT (Haussier/Baissier) → SIGNAL (BUY/SELL/WAIT) - Raison en 1 phrase\n\n");
            prompt.append("Exemple:\n");
            prompt.append("GOLD: Haussier → BUY - Valeur refuge en période de tensions\n");
            prompt.append("NASDAQ: Baissier → SELL - Risk-off favorise sortie des tech\n\n");
            prompt.append("RÉSUMÉ FINAL: Liste compacte [actif→signal]");

            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", "Analyste financier expert. Réponds en français, " +
                "ultra concis (max 2 lignes par actif). Différencie impact MACRO vs SPÉCIFIQUE.");

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
        String title = emoji + " Impact " + impact + " - " + assets;
        
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

    // === DAILY REPORT SYSTÈME PAR ACTIF ===
    
    private static void saveToDailyReport(EconomicEventDetector.DetectedEvent event, 
                                         String text, String analysis, List<String> assets) {
        String timestamp = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        String summary = text.substring(0, Math.min(120, text.length()));
        
        // Extraire le signal de l'analyse
        String signal = extractSignalFromAnalysis(analysis);
        
        // Sauvegarder pour chaque actif concerné
        for (String asset : assets) {
            List<DailyReportEntry> assetCache = dailyReportByAsset.get(asset);
            if (assetCache != null) {
                DailyReportEntry entry = new DailyReportEntry(
                    timestamp,
                    event.impact,
                    event.eventType,
                    event.getDescription(),
                    summary,
                    signal
                );
                
                assetCache.add(entry);
                
                // Garder max 30 événements par actif
                if (assetCache.size() > 30) {
                    assetCache.remove(0);
                }
            }
        }
    }
    
    private static String extractSignalFromAnalysis(String analysis) {
        if (analysis.contains("BUY")) return "BUY";
        if (analysis.contains("SELL")) return "SELL";
        return "WAIT";
    }
    
    // Vérifier si un rapport doit être envoyé
    private void checkAndSendScheduledReports() {
        Calendar cal = Calendar.getInstance();
        int currentHour = cal.get(Calendar.HOUR_OF_DAY);
        int currentMinute = cal.get(Calendar.MINUTE);
        int currentDay = cal.get(Calendar.DAY_OF_YEAR);
        
        // Réinitialiser à minuit
        Calendar todayStart = Calendar.getInstance();
        todayStart.set(Calendar.HOUR_OF_DAY, 0);
        todayStart.set(Calendar.MINUTE, 0);
        
        if (cal.getTimeInMillis() - todayStart.getTimeInMillis() < 60000) {
            sentReportsToday.clear();
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[REPORT] Nouveau jour - reset");
        }
        
        // Vérifier chaque horaire programmé
        for (int i = 0; i < REPORT_HOURS.length; i++) {
            int reportHour = REPORT_HOURS[i];
            int reportMinute = REPORT_MINUTES[i];
            
            String reportKey = currentDay + "_" + reportHour + "_" + reportMinute;
            
            if (currentHour == reportHour && 
                currentMinute == reportMinute && 
                !sentReportsToday.contains(reportKey)) {
                
                generateScheduledReport(reportHour, reportMinute);
                sentReportsToday.add(reportKey);
                
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("[REPORT] Rapport " + reportHour + "h" + 
                        String.format("%02d", reportMinute) + " envoyé");
            }
        }
    }
    
    // Générer un rapport programmé avec résumé par actif
    private void generateScheduledReport(int hour, int minute) {
        // Vérifier s'il y a au moins un événement
        boolean hasEvents = false;
        for (List<DailyReportEntry> cache : dailyReportByAsset.values()) {
            if (!cache.isEmpty()) {
                hasEvents = true;
                break;
            }
        }
        
        if (!hasEvents) {
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[REPORT] Aucun événement - rapport ignoré");
            return;
        }
        
        StringBuilder report = new StringBuilder();
        
        // En-tête
        String reportTitle = getReportTitle(hour, minute);
        report.append("📊 *").append(reportTitle).append("*\n");
        report.append(new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            .format(new Date()));
        report.append("\n\n");
        
        // Statistiques globales
        int totalEvents = 0;
        int haussier = 0, baissier = 0;
        
        for (List<DailyReportEntry> cache : dailyReportByAsset.values()) {
            totalEvents += cache.size();
            for (DailyReportEntry entry : cache) {
                if ("Haussier".equals(entry.impact)) haussier++;
                if ("Baissier".equals(entry.impact)) baissier++;
            }
        }
        
        report.append("*Vue d'ensemble: ").append(totalEvents).append(" événements*\n");
        report.append("📈 Haussiers: ").append(haussier).append(" | ");
        report.append("📉 Baissiers: ").append(baissier).append("\n\n");
        
        // Rapport par actif (seulement ceux avec des événements)
        report.append("*📌 RÉSUMÉ PAR ACTIF:*\n\n");
        
        for (String[] assetInfo : ASSETS) {
            String assetName = assetInfo[0];
            List<DailyReportEntry> assetCache = dailyReportByAsset.get(assetName);
            
            if (assetCache == null || assetCache.isEmpty()) continue;
            
            // Compter les signaux pour cet actif
            int buyCount = 0, sellCount = 0, waitCount = 0;
            String dominantImpact = "";
            int haussierCount = 0, baissierCount = 0;
            
            for (DailyReportEntry entry : assetCache) {
                if ("BUY".equals(entry.signal)) buyCount++;
                if ("SELL".equals(entry.signal)) sellCount++;
                if ("WAIT".equals(entry.signal)) waitCount++;
                if ("Haussier".equals(entry.impact)) haussierCount++;
                if ("Baissier".equals(entry.impact)) baissierCount++;
            }
            
            dominantImpact = haussierCount > baissierCount ? "📈 Haussier" : "📉 Baissier";
            
            // Emoji selon l'actif
            String assetEmoji = getAssetEmoji(assetName);
            
            report.append(assetEmoji).append(" *").append(assetName).append("* - ");
            report.append(assetCache.size()).append(" evt - ");
            report.append(dominantImpact).append("\n");
            
            // Signaux dominants
            String dominantSignal = "";
            if (buyCount > sellCount && buyCount > waitCount) {
                dominantSignal = "🟢 BUY dominant (" + buyCount + ")";
            } else if (sellCount > buyCount && sellCount > waitCount) {
                dominantSignal = "🔴 SELL dominant (" + sellCount + ")";
            } else {
                dominantSignal = "⚪ Mixte (B:" + buyCount + " S:" + sellCount + ")";
            }
            
            report.append("   Signal: ").append(dominantSignal).append("\n");
            
            // Derniers événements (max 3 les plus récents)
            int eventCount = Math.min(3, assetCache.size());
            for (int i = assetCache.size() - eventCount; i < assetCache.size(); i++) {
                DailyReportEntry entry = assetCache.get(i);
                report.append("   • ").append(entry.timestamp).append(" - ");
                report.append(entry.description.substring(0, Math.min(40, entry.description.length())));
                report.append("\n");
            }
            
            report.append("\n");
        }
        
        report.append("_Prochain rapport: ").append(getNextReportTime(hour, minute)).append("_");
        
        sendTelegram(report.toString());
        
        // Nettoyer le cache après le rapport de 21h
        if (hour == 21) {
            for (List<DailyReportEntry> cache : dailyReportByAsset.values()) {
                cache.clear();
            }
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[REPORT] Cache nettoyé (fin de journée)");
        }
    }
    
    private String getAssetEmoji(String asset) {
        switch (asset) {
            case "GOLD": return "🥇";
            case "BTCUSD": return "₿";
            case "GBPUSD": return "🇬🇧";
            case "USDJPY": return "🇯🇵";
            case "EURUSD": return "🇪🇺";
            case "SP500": return "📊";
            case "NASDAQ": return "💻";
            case "OIL": return "🛢️";
            case "USDCAD": return "🇨🇦";
            default: return "📈";
        }
    }
    
    private String getReportTitle(int hour, int minute) {
        if (hour == 8 && minute == 55) {
            return "RAPPORT PRÉ-OUVERTURE EUROPÉENNE";
        } else if (hour == 12 && minute == 55) {
            return "RAPPORT MI-JOURNÉE";
        } else if (hour == 16 && minute == 30) {
            return "RAPPORT PRÉ-CLÔTURE EUROPÉENNE";
        } else if (hour == 17 && minute == 0) {
            return "RAPPORT CLÔTURE EUROPÉENNE";
        } else if (hour == 21 && minute == 0) {
            return "RAPPORT FIN DE JOURNÉE";
        }
        return "RAPPORT TRADING";
    }
    
    private String getNextReportTime(int currentHour, int currentMinute) {
        for (int i = 0; i < REPORT_HOURS.length; i++) {
            if (REPORT_HOURS[i] > currentHour || 
                (REPORT_HOURS[i] == currentHour && REPORT_MINUTES[i] > currentMinute)) {
                return REPORT_HOURS[i] + "h" + String.format("%02d", REPORT_MINUTES[i]);
            }
        }
        return "8h55 (demain)";
    }

    // === HELPERS ===

    private static int getAccountPriority(String text) {
        String lower = text.toLowerCase();
        
        for (Map.Entry<String, Integer> entry : PRIORITY_ACCOUNTS.entrySet()) {
            String account = entry.getKey();
            if (lower.contains(account) || lower.contains("@" + account)) {
                return entry.getValue();
            }
        }
        
        return 1;
    }

    private static boolean hasAssetSpecificKeywords(String text, List<String> assets) {
        String lower = text.toLowerCase();
        
        for (String asset : assets) {
            String[] keywords = ASSET_SPECIFIC_KEYWORDS.get(asset);
            if (keywords != null) {
                for (String kw : keywords) {
                    if (lower.contains(kw)) {
                        return true;
                    }
                }
            }
        }
        
        return false;
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
        if (events.isEmpty()) return;
        EventDatabase.StoredEvent first = events.get(0);
        processNotification(ctx, first.appName, first.content);
    }

    public static void processNotification(Context ctx, String appName, String text) {
        List<String> assets = detectAssets(text);
        String assetsStr = String.join(", ", assets);

        EconomicEventDetector.DetectedEvent event = 
            EconomicEventDetector.detectEvent("", text);
        
        if (!event.shouldNotify()) return;

        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[BOT] Analyse " + assetsStr + "...");

        String analysis = analyzeWithGroq(text, assetsStr, event, null);

        String ts = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            .format(new Date());

        String emoji = event.impact.equals("Haussier") ? "📈" : "📉";
        String tgMsg = "*" + emoji + " ALERTE TRADING* - " + ts + "\n" +
            "Source: " + appName + "\n\n" +
            "Impact Général: " + event.impact + "\n\n" +
            "NEWS:\n" + text.substring(0, Math.min(300, text.length())) + "\n\n" +
            "*ANALYSE PAR ACTIF:*\n" + analysis;

        sendTelegram(tgMsg);
        showLocalNotif(ctx, assetsStr, analysis, event.impact);
        
        saveToDailyReport(event, text, analysis, assets);

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
