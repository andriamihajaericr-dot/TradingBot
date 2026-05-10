package com.tradingbot.analyzer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import androidx.core.app.NotificationCompat;
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
    private static final int NOTIF_ID = 2001;
    private static final String GROQ_MODEL = "llama-3.3-70b-versatile";
    private static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    
    private static final long TIME_WINDOW_MS = 30 * 60 * 1000;
    
    private final ExecutorService exec = Executors.newFixedThreadPool(5);
    private EventDatabase eventDb;
    private EconomicEventDetector eventDetector;
    
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
        "war","attack","missile","sanction","conflict","crisis","invasion",
        "nuclear","terror","breaking","urgent","alert","flash","strike",
        "guerre","attaque","conflit","crise","iran","israel","ukraine","russia",
        "china","taiwan","north korea","middle east",
        "fed","fomc","powell","yellen","federal reserve","rate hike","rate cut",
        "boe","bailey","bank of england","mpc",
        "boj","ueda","kuroda","bank of japan","yen intervention",
        "ecb","lagarde","draghi","european central bank",
        "rba","reserve bank australia","lowe",
        "boc","bank of canada","macklem",
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
        "uk cpi","uk inflation","uk gdp","uk employment","uk retail",
        "uk pmi","uk manufacturing","uk services",
        "japan cpi","japan gdp","japan pmi","tankan","boj minutes",
        "japan trade","japan machinery orders",
        "eurozone","euro area","eu cpi","eu gdp","eu pmi",
        "german","germany","ifo","zew","bundesbank",
        "french","france","italy","spain",
        "australia","aussie","rba minutes","aus employment","aus cpi",
        "gold","xauusd","precious metal","bullion","safe haven",
        "gold price","gold rally","gold sell-off",
        "bitcoin","btc","crypto","cryptocurrency","ethereum","eth",
        "binance","coinbase","sec crypto","crypto etf","spot etf",
        "blockchain","defi","altcoin","satoshi","mining",
        "crypto regulation","crypto crash","crypto rally",
        "dollar","usd","dxy","dollar index","greenback",
        "forex","fx","currency","exchange rate","cross",
        "pound","sterling","cable","gbp","brexit",
        "yen","jpy","carry trade","yen weakness","yen strength",
        "euro","eur","single currency",
        "aussie dollar","aud","commodity currency",
        "loonie","cad","canadian dollar",
        "sp500","s&p 500","spx","spy","wall street","dow jones","dow",
        "nasdaq","ndx","qqq","tech stocks","faang","magnificent 7",
        "russell","small cap","vix","fear index","volatility",
        "futures","stock futures","equity futures",
        "oil","crude","wti","brent","petroleum","barrel","energy",
        "opec","opec+","saudi","russia oil","iran oil","shale",
        "eia","api","oil inventory","oil stockpile","crude inventory",
        "oil production","oil demand","oil supply","refinery",
        "gasoline","diesel","natural gas","lng",
        "earnings","quarterly results","revenue","guidance","eps",
        "apple","aapl","microsoft","msft","alphabet","googl",
        "amazon","amzn","meta","nvidia","nvda","tesla","tsla",
        "netflix","nflx","facebook","instagram",
        "treasury","bonds","yields","10-year","30-year","2-year",
        "debt ceiling","government shutdown","default",
        "bank","banking","credit","jpmorgan","goldman","morgan stanley",
        "fed funds","interest rate","basis points","dovish","hawkish",
        "forecast","expected","actual","previous","consensus","release",
        "preliminary","revised","final reading","flash estimate",
        "better than expected","worse than expected","miss","beat"
    );

    // === ACTIFS ENRICHIS ===
    private static final String[][] ASSETS = {
        {"GOLD", "gold,xauusd,xau,bullion,precious metal,safe haven,gold price,gold rally,gold futures,gold etf,gld,spot gold,gold miners,barrick,newmont,fed gold,gold reserve"},
        {"BTCUSD", "bitcoin,btc,crypto,cryptocurrency,satoshi,blockchain,coinbase,binance,ethereum,eth,altcoin,defi,nft,spot etf,grayscale,microstrategy,saylor,halving,crypto regulation,sec crypto,gbtc,btc etf"},
        {"GBPUSD", "gbp,pound,sterling,cable,bank of england,boe,bailey,uk inflation,uk cpi,uk gdp,uk pmi,uk employment,uk retail,brexit,northern ireland,scotland,gilt,uk bonds,ftse,london,mpc meeting"},
        {"USDJPY", "jpy,yen,usdjpy,bank of japan,boj,ueda,kuroda,yen intervention,carry trade,japan cpi,japan gdp,tankan,nikkei,topix,japanese yen,jgb,japan bonds,weak yen,strong yen,yen depreciation"},
        {"EURUSD", "eur,euro,eurusd,ecb,lagarde,draghi,eurozone,euro area,eu cpi,eu gdp,eu pmi,single currency,germany,france,italy,spain,ifo,zew,euro strength,euro weakness,peripheral bonds"},
        {"SP500", "sp500,s&p 500,s&p500,spx,spy,wall street,stock market,us stocks,equity,american stocks,500 index,large cap,blue chip,dow jones,dow,market rally,market sell-off,stock futures,equity index"},
        {"NASDAQ", "nasdaq,ndx,qqq,tech stocks,technology,faang,magnificent 7,apple,microsoft,nvidia,tesla,amazon,meta,alphabet,google,netflix,semiconductor,chip stocks,tech rally,tech sell-off,nasdaq 100,nasdaq futures"},
        {"OIL", "oil,crude,wti,brent,petroleum,barrel,energy,opec,opec+,saudi,russia oil,iran oil,iraq oil,eia,api,oil inventory,crude inventory,stockpile,oil production,oil demand,shale,fracking,drilling,gasoline,diesel,refinery,natural gas,lng,oil price,crude price,energy sector,exxon,chevron"},
        {"USDCAD", "cad,loonie,canadian dollar,usdcad,boc,bank of canada,canada,canadian,macklem,oil canada,wcs,western canadian,tsx,toronto,canada employment,canada cpi,canada gdp"},
        {"AUDUSD", "aud,aussie,australian dollar,audusd,rba,reserve bank australia,australia,lowe,iron ore,china australia,commodity currency,asx,sydney,australia employment,australia cpi,aus gdp,mining,bhp,rio tinto,coal australia"}
    };

    // === COMPTES X/TWITTER PRIORITAIRES ===
    private static final Map<String, Integer> PRIORITY_ACCOUNTS = new HashMap<>();
    static {
        PRIORITY_ACCOUNTS.put("fxhedgers", 5);
        PRIORITY_ACCOUNTS.put("deltaone", 5);
        PRIORITY_ACCOUNTS.put("firstsquawk", 5);
        PRIORITY_ACCOUNTS.put("livesquawk", 5);
        PRIORITY_ACCOUNTS.put("financialjuice", 5);
        PRIORITY_ACCOUNTS.put("kobeissiletter", 5);
        PRIORITY_ACCOUNTS.put("nick_timiraos", 5);
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
        PRIORITY_ACCOUNTS.put("economics", 3);
        PRIORITY_ACCOUNTS.put("ft", 3);
        PRIORITY_ACCOUNTS.put("wsj", 3);
        PRIORITY_ACCOUNTS.put("sentimentrader", 3);
        PRIORITY_ACCOUNTS.put("vandaresearch", 3);
    }

    // === MOTS-CLÉS SPÉCIFIQUES PAR ACTIF ===
    private static final Map<String, String[]> ASSET_SPECIFIC_KEYWORDS = new HashMap<>();
    static {
        ASSET_SPECIFIC_KEYWORDS.put("GBPUSD", new String[]{"bank of england","boe","bailey","mpc meeting","uk inflation","uk cpi","uk gdp","uk pmi","uk employment","uk retail","uk manufacturing","uk services","sterling","cable","pound","brexit","gilt","ftse"});
        ASSET_SPECIFIC_KEYWORDS.put("USDJPY", new String[]{"bank of japan","boj","ueda","kuroda","yen","jpy","japan cpi","japan gdp","japan pmi","tankan","intervention","carry trade","jgb","nikkei","weak yen","strong yen","yen depreciation"});
        ASSET_SPECIFIC_KEYWORDS.put("EURUSD", new String[]{"ecb","lagarde","draghi","euro","eur","eurozone","euro area","single currency","eu cpi","eu gdp","eu pmi","germany","ifo","zew","france","italy","spain","peripheral","german bund"});
        ASSET_SPECIFIC_KEYWORDS.put("SP500", new String[]{"sp500","s&p 500","s&p","spx","spy","stock market","wall street","dow","dow jones","earnings","quarterly results","guidance","stock futures","equity futures","market open","market close","vix","volatility"});
        ASSET_SPECIFIC_KEYWORDS.put("NASDAQ", new String[]{"nasdaq","ndx","qqq","tech stocks","technology","apple","microsoft","nvidia","tesla","amazon","meta","alphabet","google","faang","magnificent 7","semiconductor","chip stocks","ai stocks"});
        ASSET_SPECIFIC_KEYWORDS.put("OIL", new String[]{"eia","api","opec","opec+","crude","wti","brent","barrel","oil inventory","crude inventory","stockpile","oil production","oil demand","saudi","russia oil","iran oil","shale","refinery","gasoline","natural gas"});
        ASSET_SPECIFIC_KEYWORDS.put("GOLD", new String[]{"gold","xauusd","bullion","safe haven","precious metal","gold price","gold rally","gold miners","fed","powell","inflation","real yields"});
        ASSET_SPECIFIC_KEYWORDS.put("BTCUSD", new String[]{"bitcoin","btc","crypto","cryptocurrency","ethereum","sec crypto","spot etf","coinbase","binance","halving","microstrategy","grayscale","blockchain"});
        ASSET_SPECIFIC_KEYWORDS.put("USDCAD", new String[]{"cad","loonie","canada","canadian","boc","bank of canada","oil canada","wcs","tsx","canada employment","canada cpi"});
        ASSET_SPECIFIC_KEYWORDS.put("AUDUSD", new String[]{"aud","aussie","australia","australian","rba","reserve bank australia","iron ore","china australia","asx","mining","coal","bhp"});
    }

    @Override
    public void onCreate() {
        super.onCreate();
        
        eventDb = new EventDatabase(this);
        eventDetector = new EconomicEventDetector(eventDb);
        
        for (String[] asset : ASSETS) {
            dailyReportByAsset.put(asset[0], Collections.synchronizedList(new ArrayList<>()));
        }
        
        exec.submit(() -> processMissedEvents());
        
        scheduler.scheduleAtFixedRate(() -> eventDb.cleanOldEvents(), 1, 24, TimeUnit.HOURS);
        
        scheduler.scheduleAtFixedRate(() -> {
            checkAndSendScheduledReports();
            checkAndSendDailySummary();
        }, 0, 1, TimeUnit.MINUTES);
        
        scheduler.scheduleAtFixedRate(() -> {
            try {
                EventValidator.preloadCalendar();
            } catch (Exception e) {
                Log.e(TAG, "Erreur preloadCalendar", e);
            }
            checkMissedCalendarEvents();
        }, 1, 15, TimeUnit.MINUTES);
        
        scheduler.scheduleAtFixedRate(() -> {
            try {
                SystemMonitor.checkSystemHealth();
            } catch (Exception e) {
                Log.e(TAG, "Erreur SystemMonitor", e);
            }
        }, 1, 60, TimeUnit.MINUTES);
        
        exec.submit(() -> {
            try {
                EventValidator.preloadCalendar();
            } catch (Exception e) {
                Log.e(TAG, "Erreur preloadCalendar initial", e);
            }
        });
        
        createNotificationChannel();
        
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[SERVICE] Démarré - Multi-sources + Validation + Corrélation ACTIVÉ");
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
        boolean botActive = getSharedPreferences("TradingBot", MODE_PRIVATE).getBoolean("bot_active", false);
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
        List<String> assets = detectAssetsWithScoring(combined);
        
        if (appName.equals("X/Twitter")) {
            int accountPriority = getAccountPriority(combined);
            if (accountPriority < 3) {
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("[FILTRE] Compte X priorité " + accountPriority + " - ignoré");
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

        EconomicEventDetector.DetectedEvent detectedEvent = EconomicEventDetector.detectEvent(title, full);
        
        EventValidator.ValidationResult validation = EventValidator.validate(title, combined, System.currentTimeMillis(), assets);
        
        if (validation.isConfirmed) {
            if (validation.forecast != null) detectedEvent.forecast = validation.forecast;
            if (validation.previous != null) detectedEvent.previous = validation.previous;
            if (validation.actual != null) detectedEvent.actual = validation.actual;
            
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[VALIDATION] ✓ Confirmé par calendrier (confiance: " + validation.confidence + "%)");
        }
        
        if (!detectedEvent.shouldNotify() && validation.confidence < 70) {
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[FILTRE] Impact neutre + confiance faible - rejeté");
            return;
        }
        
        String eventId = generateEventId(appName, title, combined);
        String assetsStr = String.join(", ", assets);
        
        boolean saved = eventDb.saveEvent(eventId, packageName, appName, detectedEvent.eventType, title, combined, assetsStr, detectedEvent.impact);
        if (!saved) return;
        
        try {
            for (String asset : assets) {
                SystemMonitor.recordEvent(asset);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur SystemMonitor.recordEvent", e);
        }
        
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[" + detectedEvent.impact.toUpperCase() + "] " + appName + " [" + detectedEvent.eventType + "]: " + detectedEvent.getDescription() + " (Confiance: " + validation.confidence + "%)");

        correlateWithCalendar(eventId, detectCountry(combined), assets, detectedEvent.impact, validation.confidence);

        final String ft = combined;
        final String fa = appName;
        final EconomicEventDetector.DetectedEvent fde = detectedEvent;
        final List<String> fAssets = assets;
        
        boolean isNewDriver = isNewMajorDriver(detectedEvent, combined);
        
        if (isNewDriver) {
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
        
        final boolean fIsDriver = isNewDriver;
        exec.submit(() -> processNotificationWithContextEnhanced(this, eventId, fa, ft, fde, assetsStr, fAssets, fIsDriver));
    }
    
