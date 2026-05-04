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
    
    private static final long TIME_WINDOW_MS = 30 * 60 * 1000;
    
    private final ExecutorService exec = Executors.newFixedThreadPool(5);
    private EventDatabase eventDb;
    
    private final ScheduledExecutorService scheduler = 
        Executors.newScheduledThreadPool(2);

    private static final Map<String, List<DailyReportEntry>> dailyReportByAsset = 
        new ConcurrentHashMap<>();
    
    private static final Map<String, Long> knownDrivers = 
        new ConcurrentHashMap<>();
    
    private static String lastSummaryDriverSignature = "";
    private static boolean dailySummaryAlreadySent = false;
    
    private static final int[] REPORT_HOURS = {8, 12, 16, 17, 21};
    private static final int[] REPORT_MINUTES = {55, 55, 30, 0, 0};
    
    private static final Set<String> sentReportsToday = 
        Collections.synchronizedSet(new HashSet<>());

    private static final List<String> ALLOWED_APPS = Arrays.asList(
        "com.twitter.android",
        "com.financialjuice.androidapp",
        "com.investing.app",
        "com.reuters.news"
    );

    // === MOTS-CLÉS ENRICHIS ===
    private static final List<String> KEYWORDS = Arrays.asList(
        // Géopolitique
        "war","attack","missile","sanction","conflict","crisis","invasion",
        "nuclear","terror","breaking","urgent","alert","flash","strike",
        "guerre","attaque","conflit","crise","iran","israel","ukraine","russia",
        "china","taiwan","north korea","middle east",
        
        // Banques centrales
        "fed","fomc","powell","yellen","federal reserve","rate hike","rate cut",
        "boe","bailey","bank of england","mpc",
        "boj","ueda","kuroda","bank of japan","yen intervention",
        "ecb","lagarde","draghi","european central bank",
        "rba","reserve bank australia","lowe",
        "boc","bank of canada","macklem",
        
        // Données macro US
        "nfp","non-farm payroll","payroll","jobs report","employment",
        "cpi","inflation","core inflation","pce","price index",
        "gdp","gross domestic product","growth",
        "fomc minutes","fed minutes","beige book",
        "retail sales","consumer spending","personal spending",
        "ism","pmi","manufacturing","services","purchasing managers",
        "housing starts","building permits","home sales","existing home",
        "jobless claims","unemployment","initial claims",
        "consumer confidence","sentiment","university of michigan",
        "ppi","producer price","wholesale",
        "trade balance","exports","imports","deficit",
        "durable goods","factory orders","industrial production",
        
        // Données macro UK
        "uk cpi","uk inflation","uk gdp","uk employment","uk retail",
        "uk pmi","uk manufacturing","uk services",
        
        // Données macro Japan
        "japan cpi","japan gdp","japan pmi","tankan","boj minutes",
        "japan trade","japan machinery orders",
        
        // Données macro Europe
        "eurozone","euro area","eu cpi","eu gdp","eu pmi",
        "german","germany","ifo","zew","bundesbank",
        "french","france","italy","spain",
        
        // Données macro Australia
        "australia","aussie","rba minutes","aus employment","aus cpi",
        
        // Or
        "gold","xauusd","precious metal","bullion","safe haven",
        "gold price","gold rally","gold sell-off",
        
        // Crypto
        "bitcoin","btc","crypto","cryptocurrency","ethereum","eth",
        "binance","coinbase","sec crypto","crypto etf","spot etf",
        "blockchain","defi","altcoin","satoshi","mining",
        "crypto regulation","crypto crash","crypto rally",
        
        // Forex général
        "dollar","usd","dxy","dollar index","greenback",
        "forex","fx","currency","exchange rate","cross",
        
        // GBP
        "pound","sterling","cable","gbp","brexit",
        
        // JPY
        "yen","jpy","carry trade","yen weakness","yen strength",
        
        // EUR
        "euro","eur","single currency",
        
        // AUD
        "aussie dollar","aud","commodity currency",
        
        // CAD
        "loonie","cad","canadian dollar",
        
        // Indices
        "sp500","s&p 500","spx","spy","wall street","dow jones","dow",
        "nasdaq","ndx","qqq","tech stocks","faang","magnificent 7",
        "russell","small cap","vix","fear index","volatility",
        "futures","stock futures","equity futures",
        
        // Pétrole
        "oil","crude","wti","brent","petroleum","barrel","energy",
        "opec","opec+","saudi","russia oil","iran oil","shale",
        "eia","api","oil inventory","oil stockpile","crude inventory",
        "oil production","oil demand","oil supply","refinery",
        "gasoline","diesel","natural gas","lng",
        
        // Big Tech / Earnings
        "earnings","quarterly results","revenue","guidance","eps",
        "apple","aapl","microsoft","msft","alphabet","googl",
        "amazon","amzn","meta","nvidia","nvda","tesla","tsla",
        "netflix","nflx","facebook","instagram",
        
        // Finance / Banking
        "treasury","bonds","yields","10-year","30-year","2-year",
        "debt ceiling","government shutdown","default",
        "bank","banking","credit","jpmorgan","goldman","morgan stanley",
        "fed funds","interest rate","basis points","dovish","hawkish",
        
        // Calendrier économique
        "forecast","expected","actual","previous","consensus","release",
        "preliminary","revised","final reading","flash estimate",
        "better than expected","worse than expected","miss","beat"
    );

    // === ACTIFS ENRICHIS ===
    private static final String[][] ASSETS = {
        {"GOLD", 
         "gold,xauusd,xau,bullion,precious metal,safe haven," +
         "gold price,gold rally,gold futures,gold etf,gld,spot gold," +
         "gold miners,barrick,newmont,fed gold,gold reserve"},
        
        {"BTCUSD", 
         "bitcoin,btc,crypto,cryptocurrency,satoshi,blockchain," +
         "coinbase,binance,ethereum,eth,altcoin,defi,nft," +
         "spot etf,grayscale,microstrategy,saylor,halving," +
         "crypto regulation,sec crypto,gbtc,btc etf"},
        
        {"GBPUSD", 
         "gbp,pound,sterling,cable,bank of england,boe,bailey," +
         "uk inflation,uk cpi,uk gdp,uk pmi,uk employment," +
         "uk retail,brexit,northern ireland,scotland," +
         "gilt,uk bonds,ftse,london,mpc meeting"},
        
        {"USDJPY", 
         "jpy,yen,usdjpy,bank of japan,boj,ueda,kuroda," +
         "yen intervention,carry trade,japan cpi,japan gdp," +
         "tankan,nikkei,topix,japanese yen,jgb,japan bonds," +
         "weak yen,strong yen,yen depreciation"},
        
        {"EURUSD", 
         "eur,euro,eurusd,ecb,lagarde,draghi,eurozone," +
         "euro area,eu cpi,eu gdp,eu pmi,single currency," +
         "germany,france,italy,spain,ifo,zew," +
         "euro strength,euro weakness,peripheral bonds"},
        
        {"SP500", 
         "sp500,s&p 500,s&p500,spx,spy,wall street," +
         "stock market,us stocks,equity,american stocks," +
         "500 index,large cap,blue chip,dow jones,dow," +
         "market rally,market sell-off,stock futures,equity index"},
        
        {"NASDAQ", 
         "nasdaq,ndx,qqq,tech stocks,technology,faang," +
         "magnificent 7,apple,microsoft,nvidia,tesla,amazon,meta," +
         "alphabet,google,netflix,semiconductor,chip stocks," +
         "tech rally,tech sell-off,nasdaq 100,nasdaq futures"},
        
        {"OIL", 
         "oil,crude,wti,brent,petroleum,barrel,energy," +
         "opec,opec+,saudi,russia oil,iran oil,iraq oil," +
         "eia,api,oil inventory,crude inventory,stockpile," +
         "oil production,oil demand,shale,fracking,drilling," +
         "gasoline,diesel,refinery,natural gas,lng," +
         "oil price,crude price,energy sector,exxon,chevron"},
        
        {"USDCAD", 
         "cad,loonie,canadian dollar,usdcad,boc,bank of canada," +
         "canada,canadian,macklem,oil canada,wcs,western canadian," +
         "tsx,toronto,canada employment,canada cpi,canada gdp"},
        
        {"AUDUSD", 
         "aud,aussie,australian dollar,audusd,rba,reserve bank australia," +
         "australia,lowe,iron ore,china australia,commodity currency," +
         "asx,sydney,australia employment,australia cpi,aus gdp," +
         "mining,bhp,rio tinto,coal australia"}
    };

    // === COMPTES X/TWITTER PRIORITAIRES ===
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
        PRIORITY_ACCOUNTS.put("ecb", 4);
        PRIORITY_ACCOUNTS.put("eiagov", 4);
        PRIORITY_ACCOUNTS.put("forexfactory", 4);
        PRIORITY_ACCOUNTS.put("investingcom", 4);
        PRIORITY_ACCOUNTS.put("teconomics", 4);
        PRIORITY_ACCOUNTS.put("tradingeconomics", 4);
        PRIORITY_ACCOUNTS.put("javierblas", 4);
        PRIORITY_ACCOUNTS.put("oott_energy", 4);
        PRIORITY_ACCOUNTS.put("amena__bakr", 4);
        PRIORITY_ACCOUNTS.put("watcherguru", 4);
        PRIORITY_ACCOUNTS.put("coinglass", 4);
        PRIORITY_ACCOUNTS.put("glassnode", 4);
        PRIORITY_ACCOUNTS.put("zerohedge", 4);
        PRIORITY_ACCOUNTS.put("markets", 4);
        PRIORITY_ACCOUNTS.put("schuldensuehner", 4);
        PRIORITY_ACCOUNTS.put("reutersuk", 4);
        PRIORITY_ACCOUNTS.put("reutersjapan", 4);
        PRIORITY_ACCOUNTS.put("business", 4);
        PRIORITY_ACCOUNTS.put("sino_market", 4);
        PRIORITY_ACCOUNTS.put("carlquintanilla", 4);
        PRIORITY_ACCOUNTS.put("ole_s_hansen", 4);
        PRIORITY_ACCOUNTS.put("robinbrooksiif", 4);
        PRIORITY_ACCOUNTS.put("hkuppy", 4);
        
        // PRIORITÉ MOYENNE 3/5
        PRIORITY_ACCOUNTS.put("economics", 3);
        PRIORITY_ACCOUNTS.put("ft", 3);
        PRIORITY_ACCOUNTS.put("wsj", 3);
        PRIORITY_ACCOUNTS.put("sentimentrader", 3);
        PRIORITY_ACCOUNTS.put("vandaresearch", 3);
    }

    // === MOTS-CLÉS SPÉCIFIQUES PAR ACTIF ===
    private static final Map<String, String[]> ASSET_SPECIFIC_KEYWORDS = new HashMap<>();

    static {
        ASSET_SPECIFIC_KEYWORDS.put("GBPUSD", new String[]{
            "bank of england", "boe", "bailey", "mpc meeting",
            "uk inflation", "uk cpi", "uk gdp", "uk pmi", "uk employment",
            "uk retail", "uk manufacturing", "uk services",
            "sterling", "cable", "pound", "brexit", "gilt", "ftse"
        });
        
        ASSET_SPECIFIC_KEYWORDS.put("USDJPY", new String[]{
            "bank of japan", "boj", "ueda", "kuroda", "yen", "jpy",
            "japan cpi", "japan gdp", "japan pmi", "tankan",
            "intervention", "carry trade", "jgb", "nikkei",
            "weak yen", "strong yen", "yen depreciation"
        });
        
        ASSET_SPECIFIC_KEYWORDS.put("EURUSD", new String[]{
            "ecb", "lagarde", "draghi", "euro", "eur", "eurozone",
            "euro area", "single currency", "eu cpi", "eu gdp", "eu pmi",
            "germany", "ifo", "zew", "france", "italy", "spain",
            "peripheral", "german bund"
        });
        
        ASSET_SPECIFIC_KEYWORDS.put("SP500", new String[]{
            "sp500", "s&p 500", "s&p", "spx", "spy",
            "stock market", "wall street", "dow", "dow jones",
            "earnings", "quarterly results", "guidance",
            "stock futures", "equity futures", "market open",
            "market close", "vix", "volatility"
        });
        
        ASSET_SPECIFIC_KEYWORDS.put("NASDAQ", new String[]{
            "nasdaq", "ndx", "qqq", "tech stocks", "technology",
            "apple", "microsoft", "nvidia", "tesla", "amazon", "meta",
            "alphabet", "google", "faang", "magnificent 7",
            "semiconductor", "chip stocks", "ai stocks"
        });
        
        ASSET_SPECIFIC_KEYWORDS.put("OIL", new String[]{
            "eia", "api", "opec", "opec+", "crude", "wti", "brent",
            "barrel", "oil inventory", "crude inventory", "stockpile",
            "oil production", "oil demand", "saudi", "russia oil",
            "iran oil", "shale", "refinery", "gasoline", "natural gas"
        });
        
        ASSET_SPECIFIC_KEYWORDS.put("GOLD", new String[]{
            "gold", "xauusd", "bullion", "safe haven", "precious metal",
            "gold price", "gold rally", "gold miners",
            "fed", "powell", "inflation", "real yields"
        });
        
        ASSET_SPECIFIC_KEYWORDS.put("BTCUSD", new String[]{
            "bitcoin", "btc", "crypto", "cryptocurrency", "ethereum",
            "sec crypto", "spot etf", "coinbase", "binance",
            "halving", "microstrategy", "grayscale", "blockchain"
        });
        
        ASSET_SPECIFIC_KEYWORDS.put("USDCAD", new String[]{
            "cad", "loonie", "canada", "canadian", "boc",
            "bank of canada", "oil canada", "wcs", "tsx",
            "canada employment", "canada cpi"
        });
        
        ASSET_SPECIFIC_KEYWORDS.put("AUDUSD", new String[]{
            "aud", "aussie", "australia", "australian", "rba",
            "reserve bank australia", "iron ore", "china australia",
            "asx", "mining", "coal", "bhp"
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        eventDb = new EventDatabase(this);
        
        for (String[] asset : ASSETS) {
            dailyReportByAsset.put(asset[0], 
                Collections.synchronizedList(new ArrayList<>()));
        }
        
        exec.submit(() -> processMissedEvents());
        
        // Nettoyage quotidien
        scheduler.scheduleAtFixedRate(
            () -> eventDb.cleanOldEvents(),
            1, 24, TimeUnit.HOURS
        );
        
        // Rapports programmés
        scheduler.scheduleAtFixedRate(
            () -> {
                checkAndSendScheduledReports();
                checkAndSendDailySummary();
            },
            0, 1, TimeUnit.MINUTES
        );
        
        // NOUVEAU: Polling calendrier économique + vérification événements manqués
        scheduler.scheduleAtFixedRate(
            () -> {
                EventValidator.preloadCalendar();
                checkMissedCalendarEvents();
            },
            1, 15, TimeUnit.MINUTES
        );
        
        // NOUVEAU: Monitoring système
        scheduler.scheduleAtFixedRate(
            () -> SystemMonitor.checkSystemHealth(),
            1, 60, TimeUnit.MINUTES
        );
        
        // Charger calendrier au démarrage
        exec.submit(() -> EventValidator.preloadCalendar());
        
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[SERVICE] Démarré - Multi-sources + Validation croisée ACTIVÉ");
    }

    private static class DailyReportEntry {
        String timestamp;
        String impact;
        String eventType;
        String description;
        String summary;
        String signal;
        
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
        
        // NOUVEAU: Détection actifs avec scoring intelligent
        List<String> assets = detectAssetsWithScoring(combined);
        
        if (appName.equals("X/Twitter")) {
            int accountPriority = getAccountPriority(combined);
            
            // ASSOUPLISSEMENT: Priorité 3+ acceptée (au lieu de 4+)
            if (accountPriority < 3) {
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("[FILTRE] Compte X priorité " + 
                        accountPriority + " - ignoré");
                return;
            }
            
            if (accountPriority == 3 && !hasAssetSpecificKeywords(combined, assets)) {
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("[FILTRE] Priorité 3 sans keywords pertinents");
                return;
            }
            
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[X] Priorité " + accountPriority + "/5 ✓");
        }
        
        if (!isTradingRelevant(combined)) return;

        EconomicEventDetector.DetectedEvent detectedEvent = 
            EconomicEventDetector.detectEvent(title, full);
        
        // NOUVEAU: Validation croisée avec calendrier
        EventValidator.ValidationResult validation = 
            EventValidator.validate(title, combined, System.currentTimeMillis(), assets);
        
        // Enrichir données avec calendrier
        if (validation.isConfirmed) {
            if (validation.forecast != null) detectedEvent.forecast = validation.forecast;
            if (validation.previous != null) detectedEvent.previous = validation.previous;
            if (validation.actual != null) detectedEvent.actual = validation.actual;
            
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[VALIDATION] ✓ Confirmé par calendrier (confiance: " + 
                    validation.confidence + "%)");
        }
        
        // ASSOUPLISSEMENT: Accepter impact Neutre si confiance > 70%
        if (!detectedEvent.shouldNotify() && validation.confidence < 70) {
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[FILTRE] Impact neutre + confiance faible - rejeté");
            return;
        }
        
        String eventId = generateEventId(appName, title, combined);
        String assetsStr = String.join(", ", assets);
        
        boolean saved = eventDb.saveEvent(
            eventId, packageName, appName, detectedEvent.eventType, 
            title, combined, assetsStr, detectedEvent.impact
        );
        
        if (!saved) return;
        
        // NOUVEAU: Enregistrer événement dans monitoring
        for (String asset : assets) {
            SystemMonitor.recordEvent(asset);
        }
        
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[" + detectedEvent.impact.toUpperCase() + "] " + 
                appName + " [" + detectedEvent.eventType + "]: " + 
                detectedEvent.getDescription() + " (Confiance: " + validation.confidence + "%)");

        final String ft = combined;
        final String fa = appName;
        final EconomicEventDetector.DetectedEvent fde = detectedEvent;
        final List<String> fAssets = assets;
        
        if (isNewMajorDriver(detectedEvent, combined)) {
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[DRIVER] ⚡ NOUVEAU DRIVER DÉTECTÉ !");
            
            Calendar cal = Calendar.getInstance();
            int currentHour = cal.get(Calendar.HOUR_OF_DAY);
            int currentMinute = cal.get(Calendar.MINUTE);
            
            boolean isAfter755 = (currentHour > 7) || (currentHour == 7 && currentMinute >= 55);
            
            String newDriverSignature = createDriverSignature(detectedEvent, combined);
            boolean isDifferentFromLastSummary = !newDriverSignature.equals(lastSummaryDriverSignature);
            
            if (isAfter755 && isDifferentFromLastSummary) {
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("[AUTO-SUMMARY] Déclenchement résumé auto");
                
                exec.submit(() -> {
                    try {
                        Thread.sleep(30000);
                        generateAndSendMarketSummary();
                        lastSummaryDriverSignature = newDriverSignature;
                    } catch (Exception e) {
                        if (MainActivity.instance != null)
                            MainActivity.instance.addLog("[AUTO-SUMMARY] Erreur: " + e.getMessage());
                    }
                });
            }
        }
        
        exec.submit(() -> processNotificationWithContext(
            this, eventId, fa, ft, fde, assetsStr, fAssets
        ));
    }

    // NOUVEAU: Vérifier événements manqués du calendrier
    private void checkMissedCalendarEvents() {
        long now = System.currentTimeMillis();
        long window = 15 * 60 * 1000; // 15 minutes
        
        List<EconomicCalendarAPI.CalendarEvent> recent = 
            EconomicCalendarAPI.fetchRecentEvents(15);
        
        for (EconomicCalendarAPI.CalendarEvent event : recent) {
            long eventTime = parseTimestamp(event.timestamp);
            
            // Événement dans les 15 dernières minutes
            if (eventTime < now && eventTime > (now - window)) {
                
                String eventId = event.indicator + "_" + event.timestamp;
                
                if (!eventDb.eventExists(eventId)) {
                    if (MainActivity.instance != null) {
                        MainActivity.instance.addLog("[MISSED] ⚠️ Événement calendrier détecté: " + 
                            event.indicator + " (" + event.country + ")");
                    }
                    
                    // Créer événement synthétique
                    createSyntheticEvent(event);
                }
            }
        }
    }
    
    private void createSyntheticEvent(EconomicCalendarAPI.CalendarEvent event) {
        String title = event.country + " " + event.indicator;
        StringBuilder content = new StringBuilder();
        
        content.append(event.indicator);
        
        if (event.forecast != null && !event.forecast.isEmpty()) {
            content.append(" - Prévision: ").append(event.forecast);
        }
        if (event.previous != null && !event.previous.isEmpty()) {
            content.append(", Précédent: ").append(event.previous);
        }
        if (event.actual != null && !event.actual.isEmpty()) {
            content.append(", Actuel: ").append(event.actual);
        }
        
        // Traiter comme notification normale
        processNotification(this, "Calendrier Économique", content.toString());
        
        if (MainActivity.instance != null) {
            MainActivity.instance.addLog("[RECOVERY] Événement calendrier traité: " + title);
        }
    }
    
    private long parseTimestamp(String timestamp) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            return sdf.parse(timestamp).getTime();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    // NOUVEAU: Détection actifs avec scoring intelligent
    private static List<String> detectAssetsWithScoring(String text) {
        String lower = text.toLowerCase();
        Map<String, Integer> assetScores = new HashMap<>();
        
        // Initialiser tous les actifs à 0
        for (String[] asset : ASSETS) {
            assetScores.put(asset[0], 0);
        }
        
        // Scoring par keywords généraux
        for (String[] asset : ASSETS) {
            String assetName = asset[0];
            String[] keywords = asset[1].split(",");
            
            for (String kw : keywords) {
                if (lower.contains(kw.trim())) {
                    assetScores.put(assetName, assetScores.get(assetName) + 10);
                }
            }
            
            // Keywords spécifiques (bonus)
            String[] specificKw = ASSET_SPECIFIC_KEYWORDS.get(assetName);
            if (specificKw != null) {
                for (String kw : specificKw) {
                    if (lower.contains(kw)) {
                        assetScores.put(assetName, assetScores.get(assetName) + 20);
                    }
                }
            }
        }
        
        // Règles contextuelles
        applyContextualRules(lower, assetScores);
        
        // Retourner actifs avec score >= 15
        List<String> detected = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : assetScores.entrySet()) {
            if (entry.getValue() >= 15) {
                detected.add(entry.getKey());
            }
        }
        
        // Si aucun actif détecté, appliquer règles par défaut
        if (detected.isEmpty()) {
            detected.add("GOLD");
            detected.add("BTCUSD");
        }
        
        return detected;
    }
    
    private static void applyContextualRules(String text, Map<String, Integer> scores) {
        // Événement macro US → impacte tous les USD pairs
        if ((text.contains("fed") || text.contains("nfp") || text.contains("cpi")) && 
            (text.contains("us") || text.contains("united states"))) {
            scores.put("EURUSD", scores.get("EURUSD") + 15);
            scores.put("GBPUSD", scores.get("GBPUSD") + 15);
            scores.put("USDJPY", scores.get("USDJPY") + 15);
            scores.put("GOLD", scores.get("GOLD") + 20);
            scores.put("BTCUSD", scores.get("BTCUSD") + 15);
        }
        
        // Risk-off → Gold + JPY up, Stocks down
        if (text.contains("war") || text.contains("crisis") || text.contains("nuclear") ||
            text.contains("attack") || text.contains("conflict")) {
            scores.put("GOLD", scores.get("GOLD") + 30);
            scores.put("USDJPY", scores.get("USDJPY") + 20); // JPY refuge
            scores.put("NASDAQ", scores.get("NASDAQ") + 25);
            scores.put("SP500", scores.get("SP500") + 25);
        }
        
        // Pétrole → CAD corrélation
        if (text.contains("oil") || text.contains("opec") || text.contains("eia") ||
            text.contains("crude") || text.contains("brent") || text.contains("wti")) {
            scores.put("USDCAD", scores.get("USDCAD") + 25);
        }
        
        // China news → AUD impact
        if (text.contains("china") || text.contains("pboc") || text.contains("xi jinping")) {
            scores.put("AUDUSD", scores.get("AUDUSD") + 25);
        }
        
        // Tech earnings → NASDAQ
        if ((text.contains("apple") || text.contains("microsoft") || text.contains("nvidia") ||
             text.contains("tesla") || text.contains("amazon") || text.contains("meta")) &&
            text.contains("earnings")) {
            scores.put("NASDAQ", scores.get("NASDAQ") + 30);
            scores.put("SP500", scores.get("SP500") + 20);
        }
        
        // Brexit → GBP
        if (text.contains("brexit") || text.contains("uk eu")) {
            scores.put("GBPUSD", scores.get("GBPUSD") + 25);
        }
        
        // Intervention → JPY
        if (text.contains("intervention") && (text.contains("yen") || text.contains("boj"))) {
            scores.put("USDJPY", scores.get("USDJPY") + 35);
        }
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

    private static void saveToDailyReport(EconomicEventDetector.DetectedEvent event, 
                                         String text, String analysis, List<String> assets) {
        String timestamp = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        String summary = text.substring(0, Math.min(120, text.length()));
        
        String signal = extractSignalFromAnalysis(analysis);
        
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
    
    private static boolean isNewMajorDriver(EconomicEventDetector.DetectedEvent event, String text) {
        String lower = text.toLowerCase();
        
        boolean isMajorEvent = 
            (lower.contains("fed") && (lower.contains("rate") || lower.contains("powell") || lower.contains("fomc"))) ||
            (lower.contains("boe") && (lower.contains("rate") || lower.contains("bailey"))) ||
            (lower.contains("boj") && (lower.contains("intervention") || lower.contains("ueda"))) ||
            (lower.contains("ecb") && (lower.contains("rate") || lower.contains("lagarde"))) ||
            (lower.contains("rba") && lower.contains("rate")) ||
            (lower.contains("boc") && lower.contains("rate")) ||
            lower.contains("nfp") ||
            lower.contains("non-farm") ||
            (lower.contains("cpi") && event.actual != null) ||
            (lower.contains("gdp") && event.actual != null) ||
            (lower.contains("pmi") && event.actual != null) ||
            lower.contains("fomc minutes") ||
            lower.contains("beige book") ||
            (lower.contains("opec") && (lower.contains("cut") || lower.contains("increase"))) ||
            (lower.contains("eia") && event.actual != null) ||
            (lower.contains("api") && lower.contains("inventory")) ||
            ((lower.contains("war") || lower.contains("attack") || lower.contains("nuclear") || 
              lower.contains("strike") || lower.contains("invasion")) &&
             (lower.contains("breaking") || lower.contains("urgent"))) ||
            ((lower.contains("apple") || lower.contains("aapl") ||
              lower.contains("microsoft") || lower.contains("msft") ||
              lower.contains("nvidia") || lower.contains("nvda") ||
              lower.contains("tesla") || lower.contains("tsla") ||
              lower.contains("amazon") || lower.contains("amzn") ||
              lower.contains("meta") || lower.contains("alphabet") ||
              lower.contains("google") || lower.contains("googl") ||
              lower.contains("netflix") || lower.contains("nflx") ||
              lower.contains("berkshire") || lower.contains("jpmorgan")) && 
             lower.contains("earnings"));
        
        if (!isMajorEvent) {
            return false;
        }
        
        String driverSignature = createDriverSignature(event, text);
        Long lastSeen = knownDrivers.get(driverSignature);
        long now = System.currentTimeMillis();
        
        if (lastSeen != null && (now - lastSeen < 8 * 60 * 60 * 1000)) {
            return false;
        }
        
        knownDrivers.put(driverSignature, now);
        cleanOldDrivers();
        
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[DRIVER] ⚡ NOUVEAU: " + 
                driverSignature.substring(0, Math.min(50, driverSignature.length())));
        
        return true;
    }
    
    private static String createDriverSignature(EconomicEventDetector.DetectedEvent event, String text) {
        String lower = text.toLowerCase();
        StringBuilder sig = new StringBuilder();
        
        if (event.indicator != null) {
            sig.append(event.indicator).append("_");
        }
        
        if (event.country != null) {
            sig.append(event.country).append("_");
        }
        
        if (lower.contains("fed")) sig.append("fed_");
        if (lower.contains("boe")) sig.append("boe_");
        if (lower.contains("boj")) sig.append("boj_");
        if (lower.contains("ecb")) sig.append("ecb_");
        if (lower.contains("rba")) sig.append("rba_");
        if (lower.contains("boc")) sig.append("boc_");
        if (lower.contains("opec")) sig.append("opec_");
        if (lower.contains("nfp")) sig.append("nfp_");
        if (lower.contains("cpi")) sig.append("cpi_");
        if (lower.contains("gdp")) sig.append("gdp_");
        if (lower.contains("war")) sig.append("war_");
        if (lower.contains("nuclear")) sig.append("nuclear_");
        if (lower.contains("apple")) sig.append("aapl_");
        if (lower.contains("microsoft")) sig.append("msft_");
        if (lower.contains("nvidia")) sig.append("nvda_");
        if (lower.contains("tesla")) sig.append("tsla_");
        
        return sig.toString();
    }
    
    private static void cleanOldDrivers() {
        long cutoff = System.currentTimeMillis() - (8 * 60 * 60 * 1000);
        knownDrivers.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }
    
    private void checkAndSendScheduledReports() {
        Calendar cal = Calendar.getInstance();
        int currentHour = cal.get(Calendar.HOUR_OF_DAY);
        int currentMinute = cal.get(Calendar.MINUTE);
        int currentDay = cal.get(Calendar.DAY_OF_YEAR);
        
        Calendar todayStart = Calendar.getInstance();
        todayStart.set(Calendar.HOUR_OF_DAY, 0);
        todayStart.set(Calendar.MINUTE, 0);
        
        if (cal.getTimeInMillis() - todayStart.getTimeInMillis() < 60000) {
            sentReportsToday.clear();
            dailySummaryAlreadySent = false;
            lastSummaryDriverSignature = "";
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[REPORT] Nouveau jour - reset complet");
        }
        
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
    
    private void generateScheduledReport(int hour, int minute) {
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
        
        String reportTitle = getReportTitle(hour, minute);
        report.append("📊 *").append(reportTitle).append("*\n");
        report.append(new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            .format(new Date()));
        report.append("\n\n");
        
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
        
        report.append("*📌 RÉSUMÉ PAR ACTIF:*\n\n");
        
        for (String[] assetInfo : ASSETS) {
            String assetName = assetInfo[0];
            List<DailyReportEntry> assetCache = dailyReportByAsset.get(assetName);
            
            if (assetCache == null || assetCache.isEmpty()) continue;
            
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
            
            String assetEmoji = getAssetEmoji(assetName);
            
            report.append(assetEmoji).append(" *").append(assetName).append("* - ");
            report.append(assetCache.size()).append(" evt - ");
            report.append(dominantImpact).append("\n");
            
            String dominantSignal = "";
            if (buyCount > sellCount && buyCount > waitCount) {
                dominantSignal = "🟢 BUY dominant (" + buyCount + ")";
            } else if (sellCount > buyCount && sellCount > waitCount) {
                dominantSignal = "🔴 SELL dominant (" + sellCount + ")";
            } else {
                dominantSignal = "⚪ Mixte (B:" + buyCount + " S:" + sellCount + ")";
            }
            
            report.append("   Signal: ").append(dominantSignal).append("\n");
            
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
        
        if (hour == 21) {
            for (List<DailyReportEntry> cache : dailyReportByAsset.values()) {
                cache.clear();
            }
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[REPORT] Cache nettoyé (fin de journée)");
        }
    }
    
    private void checkAndSendDailySummary() {
        Calendar cal = Calendar.getInstance();
        int currentHour = cal.get(Calendar.HOUR_OF_DAY);
        int currentMinute = cal.get(Calendar.MINUTE);
        
        if (currentHour == 7 && currentMinute == 55) {
            String today = new SimpleDateFormat("yyyyMMdd", Locale.getDefault())
                .format(new Date());
            
            if (!sentReportsToday.contains("summary_" + today)) {
                generateAndSendMarketSummary();
                sentReportsToday.add("summary_" + today);
                dailySummaryAlreadySent = true;
                
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("[SUMMARY] Résumé quotidien 7h55 envoyé");
            }
        }
    }
    
    private void generateAndSendMarketSummary() {
        StringBuilder context = new StringBuilder();
        
        int totalEvents = 0;
        for (List<DailyReportEntry> cache : dailyReportByAsset.values()) {
            totalEvents += cache.size();
            
            int start = Math.max(0, cache.size() - 5);
            for (int i = start; i < cache.size(); i++) {
                DailyReportEntry entry = cache.get(i);
                context.append("- ").append(entry.timestamp).append(" : ")
                       .append(entry.description).append(" (Impact: ")
                       .append(entry.impact).append(")\n");
            }
        }
        
        if (totalEvents == 0) {
            context.append("Aucun événement majeur capté récemment.");
        }
        
        String basePrompt = generateDailyMarketSummaryPrompt();
        String fullPrompt = basePrompt.replace(
            "[ICI SERA INJECTÉ LE CONTEXTE DES ÉVÉNEMENTS DU JOUR]", 
            context.toString()
        );
        
        try {
            if (MainActivity.CLAUDE_API_KEY == null || 
                MainActivity.CLAUDE_API_KEY.trim().isEmpty()) {
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("[SUMMARY] Clé API manquante");
                return;
            }

            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", 
                "Tu es un analyste de marché expert. Génère un résumé professionnel, " +
                "concis et orienté trading en français. Respecte STRICTEMENT la structure " +
                "et la limite de 340 mots.");

            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", fullPrompt);

            JSONArray messages = new JSONArray();
            messages.put(systemMsg);
            messages.put(userMsg);

            JSONObject body = new JSONObject();
            body.put("model", GROQ_MODEL);
            body.put("messages", messages);
            body.put("max_tokens", 800);
            body.put("temperature", 0.4);

            String summary = callGroqAPI(body.toString());
            
            String telegramMsg = "📰 *RÉSUMÉ MARCHÉ QUOTIDIEN*\n" +
                new SimpleDateFormat("EEEE dd MMMM yyyy", Locale.FRENCH)
                    .format(new Date()) + "\n\n" +
                summary;
            
            sendTelegram(telegramMsg);
            
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[SUMMARY] Résumé marché envoyé avec succès");

        } catch (Exception e) {
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[SUMMARY] Erreur: " + e.getMessage());
        }
    }
    
    private static String generateDailyMarketSummaryPrompt() {
        return 
            "Résumé marché ultra-court en français (max 340 mots).\n\n" +
            "Actifs à analyser : Gold (XAUUSD), S&P500, Nasdaq, GBPUSD, USDJPY, BTCUSD, AUDUSD, Pétrole (Brent/WTI).\n\n" +
            "Date et heure actuelles : " + 
            new SimpleDateFormat("EEEE dd/MM/yyyy 'à' HH:mm 'EAT (UTC+3)'", Locale.FRENCH)
                .format(new Date()) + "\n\n" +
            "Structure OBLIGATOIRE :\n\n" +
            "1. **TOP 3 DRIVERS DU JOUR** + heure de sortie\n" +
            "   → Si un nouveau driver important est apparu dans les dernières 8 heures, commence par \"**NOUVEAU DRIVER :**\" + heure précise (heure US ET).\n\n" +
            "2. **ANALYSE PAR ACTIF** (1 ligne maximum par actif) :\n" +
            "   Format strict : [Actif] : [variation %] | Bias : [Bullish/Bearish/Neutre] | Raison brève\n\n" +
            "3. **THÈME DOMINANT** + sentiment global du marché\n\n" +
            "4. **ÉVÉNEMENTS MAJEURS À VENIR**\n" +
            "   Liste les 4-5 événements les plus importants avec format EXACT :\n" +
            "   \"[Jour] DD/MM/YYYY à HH:MM ET (HH:MM EAT) : [Événement]\"\n\n" +
            "RÈGLES CRITIQUES :\n" +
            "- Concis, clair, professionnel\n" +
            "- Priorise actualités des dernières 12-18 heures\n" +
            "- MAX 340 mots STRICT\n\n" +
            "Contexte actuel :\n" +
            "[ICI SERA INJECTÉ LE CONTEXTE DES ÉVÉNEMENTS DU JOUR]\n\n" +
            "Génère maintenant le résumé marché.";
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
            case "AUDUSD": return "🇦🇺";
            default: return "📈";
        }
    }
    
    private String getReportTitle(int hour, int minute) {
        if (hour == 8 && minute == 55) return "RAPPORT PRÉ-OUVERTURE EUROPÉENNE";
        else if (hour == 12 && minute == 55) return "RAPPORT MI-JOURNÉE";
        else if (hour == 16 && minute == 30) return "RAPPORT PRÉ-CLÔTURE EUROPÉENNE";
        else if (hour == 17 && minute == 0) return "RAPPORT CLÔTURE EUROPÉENNE";
        else if (hour == 21 && minute == 0) return "RAPPORT FIN DE JOURNÉE";
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
        List<String> assets = detectAssetsWithScoring(text);
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
