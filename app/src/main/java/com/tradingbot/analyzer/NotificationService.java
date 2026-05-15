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
    // === ACTIFS ENRICHIS (AVEC AUDUSD) ===
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
        
        {"AUDUSD", "aud,aussie,australian dollar,audusd,rba,reserve bank australia,australia,australian,lowe,bullock,iron ore,china australia,commodity currency,asx,sydney,australia employment,australia cpi,aus gdp,aus pmi,mining,bhp,rio tinto,coal australia,china trade,china demand"}
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
        ASSET_SPECIFIC_KEYWORDS.put("AUDUSD", new String[]{"aud","aussie","australia","australian","rba","reserve bank australia","lowe","bullock","iron ore","china australia","asx","mining","coal","bhp","rio tinto","china trade","china pmi","china gdp","commodity currency","aus employment","aus cpi","aus gdp","aus pmi","aus retail"});
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
    // =====================================================
    // ✨ CALCUL DU SIGNAL DOMINANT PONDÉRÉ PAR TEMPS
    // =====================================================
    
    /**
     * Calculer le signal dominant pour un actif avec pondération temporelle
     * Les événements récents comptent plus que les anciens
     */
    private static String calculateDominantSignalWeighted(List<EventDatabase.StoredEvent> events) {
        if (events.isEmpty()) {
            return "⚪ NEUTRE (aucun événement)";
        }
        
        long now = System.currentTimeMillis();
        double buyScore = 0;
        double sellScore = 0;
        double waitScore = 0;
        
        for (EventDatabase.StoredEvent event : events) {
            // ✅ PONDÉRATION TEMPORELLE
            long age = now - event.timestamp;
            double weight = 1.0;
            
            if (age < 1 * 60 * 60 * 1000) {
                // < 1h : poids 3x (très récent)
                weight = 3.0;
            } else if (age < 3 * 60 * 60 * 1000) {
                // 1-3h : poids 2x (récent)
                weight = 2.0;
            } else if (age < 6 * 60 * 60 * 1000) {
                // 3-6h : poids 1.5x (moyen)
                weight = 1.5;
            }
            // > 6h : poids 1x (ancien)
            
            // ✅ PONDÉRATION PAR CONFIANCE
            double confidenceWeight = event.confidence / 100.0;
            
            // ✅ PONDÉRATION PAR IMPORTANCE
            double importanceWeight = 1.0;
            if (event.eventType.contains("CENTRAL_BANK") || 
                event.eventType.contains("EMPLOYMENT") ||
                event.eventType.contains("INFLATION")) {
                importanceWeight = 1.5;
            }
            
            // ✅ SCORE FINAL
            double finalWeight = weight * confidenceWeight * importanceWeight;
            
            // Extraire le signal
            String signal = extractSignalFromAnalysis(event.analysis);
            
            if ("BUY".equals(signal)) {
                buyScore += finalWeight;
            } else if ("SELL".equals(signal)) {
                sellScore += finalWeight;
            } else {
                waitScore += finalWeight;
            }
        }
        
        // ✅ DÉTERMINER LE SIGNAL DOMINANT
        double total = buyScore + sellScore + waitScore;
        
        if (total == 0) {
            return "⚪ NEUTRE (aucun signal)";
        }
        
        double buyPct = (buyScore / total) * 100;
        double sellPct = (sellScore / total) * 100;
        double waitPct = (waitScore / total) * 100;
        
        // ✅ SEUIL DE DOMINANCE : 50%
        if (buyPct > 50) {
            return String.format("🟢 BUY DOMINANT (%.0f%%)", buyPct);
        } else if (sellPct > 50) {
            return String.format("🔴 SELL DOMINANT (%.0f%%)", sellPct);
        } else if (Math.abs(buyPct - sellPct) < 15) {
            return String.format("⚠️ SIGNAUX CONTRADICTOIRES (B:%.0f%% S:%.0f%%)", buyPct, sellPct);
        } else if (buyPct > sellPct) {
            return String.format("🟡 TENDANCE BUY (%.0f%% vs %.0f%%)", buyPct, sellPct);
        } else {
            return String.format("🟠 TENDANCE SELL (%.0f%% vs %.0f%%)", sellPct, buyPct);
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
        // ✨ PRIORITÉ ABSOLUE: FINANCIALJUICE CALENDRIER
        if (isEconomicCalendarNotification(appName, title, full)) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog(
                    "[PRIORITY] 🔴 CALENDRIER ÉCONOMIQUE - " + appName
                );
            }
            
            CalendarData calData = extractCalendarData(title, full);
            
            // ✅ NOUVEAU: Traiter IMMÉDIATEMENT (avec ou sans actual)
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog(
                    "[PRIORITY] 📅 Événement détecté: " + calData.indicator
                );
            }
            
            // ✅ Déterminer le type de traitement
            if (calData.hasActual()) {
                // Cas 1: ACTUAL PUBLIÉ (ex: NFP = 250K)
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog(
                        "[PRIORITY] ✅ ACTUAL publié: " + calData.indicator + 
                        " = " + calData.actual
                    );
                }
                processCalendarEventWithPriority(appName, title, full, calData);
                return; // ✅ Traité avec priorité absolue
            } else {
                // Cas 2: FORECAST/PREVIOUS SEULEMENT (ex: Initial Jobless Claims)
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog(
                        "[PRIORITY] 📊 Données forecast/previous détectées"
                    );
                }
                
                // ✅ NOUVEAU: Traiter quand même si c'est FinancialJuice HIGH
                if (appName.equals("FinancialJuice")) {
                    processCalendarEventWithPriority(appName, title, full, calData);
                    return; // ✅ Traité avec priorité
                } else {
                    // Pour autres apps, continuer traitement normal
                    if (MainActivity.instance != null) {
                        MainActivity.instance.addLog(
                            "[CALENDAR] 📅 Événement à venir: " + calData.indicator + 
                            " (" + calData.releaseTime + ")"
                        );
                    }
                }
            }
        }
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
    // =====================================================
    // ✨ VALIDATION COMPLÈTE DES DRIVERS ÉCONOMIQUES
    // =====================================================
    
    /**
     * Vérifier si un événement est un VRAI driver économique
     * Couvre TOUS les événements macro pour nos 10 actifs
     */
    private static boolean isValidEconomicDriver(EventDatabase.StoredEvent event) {
        String lower = event.title.toLowerCase();
        String content = event.content != null ? event.content.toLowerCase() : "";
        String combined = lower + " " + content;
        
        // ========================================
        // ❌ BLACKLIST : REJETS ABSOLUS
        // ========================================
        
        // Sources sans contenu économique concret
        if (combined.contains("walter bloomberg") && 
            !containsAnyMacroKeyword(combined)) {
            return false; // Walter Bloomberg sans contexte macro = pas un driver
        }
        
        if (combined.contains("zerohedge") && 
            !containsAnyMacroKeyword(combined)) {
            return false; // ZeroHedge sans contexte = pas un driver
        }
        
        // Opinions/éditoriaux
        if (combined.contains("opinion") || combined.contains("editorial") ||
            combined.contains("commentary") || combined.contains("op-ed")) {
            return false;
        }
        
        // Politique domestique sans impact macro
        if ((combined.contains("democrat") || combined.contains("republican") ||
             combined.contains("election campaign")) && 
            !combined.contains("fed") && !combined.contains("fiscal policy")) {
            return false;
        }
        
        // ========================================
        // ✅ WHITELIST : VRAIS DRIVERS PAR ACTIF
        // ========================================
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 🇺🇸 ÉTATS-UNIS (Impact: USD pairs, Gold, Stocks)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        // EMPLOI
        String[] employmentIndicators = {
            "nfp", "non-farm payroll", "payrolls", "non farm",
            "unemployment rate", "jobless claims", "initial claims",
            "continuing claims", "adp employment", "employment change",
            "job openings", "jolts", "average hourly earnings"
        };
        if (containsAny(combined, employmentIndicators) && 
            (combined.contains("us") || combined.contains("united states"))) {
            return true; // ✅ EURUSD, GBPUSD, USDJPY, AUDUSD, USDCAD, GOLD, SP500, NASDAQ
        }
        
        // INFLATION
        String[] inflationIndicators = {
            "cpi", "consumer price index", "core cpi",
            "pce", "core pce", "personal consumption",
            "ppi", "producer price", "import prices",
            "export prices", "inflation"
        };
        if (containsAny(combined, inflationIndicators) && 
            (combined.contains("us") || combined.contains("united states") || 
             combined.contains("america"))) {
            return true; // ✅ GOLD, BTCUSD, EURUSD, USDJPY, SP500, NASDAQ
        }
        
        // FED / FOMC
        String[] fedIndicators = {
            "fomc", "federal reserve", "fed rate", "interest rate decision",
            "fed minutes", "fomc minutes", "beige book",
            "powell", "jerome powell", "fed chair",
            "fed funds", "fed dot plot", "fed statement",
            "federal open market committee"
        };
        if (containsAny(combined, fedIndicators)) {
            return true; // ✅ TOUS LES ACTIFS
        }
        
        // CROISSANCE / ACTIVITÉ
        String[] growthIndicators = {
            "gdp", "gross domestic product", "gdp preliminary", "gdp final",
            "retail sales", "advance retail", "core retail",
            "personal spending", "consumer spending",
            "durable goods", "factory orders", "core orders",
            "industrial production", "capacity utilization",
            "business inventories", "wholesale inventories"
        };
        if (containsAny(combined, growthIndicators) && 
            (combined.contains("us") || combined.contains("united states"))) {
            return true; // ✅ SP500, NASDAQ, EURUSD, GOLD
        }
        
        // SENTIMENT / CONFIANCE
        String[] sentimentIndicators = {
            "pmi", "ism manufacturing", "ism services", "ism non-manufacturing",
            "purchasing managers", "markit pmi",
            "consumer confidence", "cb consumer confidence",
            "michigan sentiment", "university of michigan",
            "business confidence", "small business optimism", "nfib"
        };
        if (containsAny(combined, sentimentIndicators) && 
            (combined.contains("us") || combined.contains("united states"))) {
            return true; // ✅ SP500, NASDAQ, GOLD
        }
        
        // IMMOBILIER
        String[] housingIndicators = {
            "housing starts", "building permits",
            "existing home sales", "new home sales", "pending home sales",
            "s&p case-shiller", "case shiller", "home price index",
            "mortgage applications", "refinance index"
        };
        if (containsAny(combined, housingIndicators) && 
            combined.contains("us")) {
            return true; // ✅ EURUSD, GOLD, SP500
        }
        
        // BALANCE COMMERCIALE
        String[] tradeIndicators = {
            "trade balance", "trade deficit", "trade surplus",
            "exports", "imports", "goods trade balance"
        };
        if (containsAny(combined, tradeIndicators) && 
            combined.contains("us")) {
            return true; // ✅ EURUSD, USDJPY, USDCAD
        }
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 🇬🇧 ROYAUME-UNI (Impact: GBPUSD, GOLD)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        String[] ukIndicators = {
            "boe", "bank of england", "boe rate", "mpc", "mpc meeting",
            "bailey", "andrew bailey", "boe minutes",
            "uk cpi", "uk inflation", "uk core cpi",
            "uk gdp", "uk employment", "uk unemployment", "claimant count",
            "uk retail sales", "uk pmi", "uk manufacturing pmi", "uk services pmi",
            "uk wages", "average earnings", "uk trade balance"
        };
        if (containsAny(combined, ukIndicators)) {
            return true; // ✅ GBPUSD, GOLD, EURUSD
        }
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 🇯🇵 JAPON (Impact: USDJPY, GOLD)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        String[] japanIndicators = {
            "boj", "bank of japan", "boj rate", "boj policy",
            "ueda", "kazuo ueda", "kuroda", "boj governor",
            "yen intervention", "currency intervention", "mof intervention",
            "japan cpi", "japan core cpi", "tokyo cpi",
            "japan gdp", "japan pmi", "tankan survey", "tankan",
            "japan trade balance", "japan exports", "japan imports",
            "japan industrial production", "japan retail sales",
            "japan employment", "japan unemployment"
        };
        if (containsAny(combined, japanIndicators)) {
            return true; // ✅ USDJPY, GOLD, EURUSD
        }
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 🇪🇺 ZONE EURO (Impact: EURUSD, GOLD)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        String[] euroIndicators = {
            "ecb", "european central bank", "ecb rate", "ecb policy",
            "lagarde", "christine lagarde", "ecb president", "ecb minutes",
            "eurozone cpi", "euro area cpi", "eurozone inflation",
            "eurozone gdp", "euro area gdp",
            "eurozone pmi", "euro area pmi", "eurozone manufacturing",
            "eurozone unemployment", "eurozone trade balance",
            // Allemagne (locomotive européenne)
            "german", "germany", "ifo", "ifo business climate",
            "zew", "zew sentiment", "german cpi", "german gdp",
            "german pmi", "german factory orders", "german industrial production",
            "bundesbank",
            // France
            "french cpi", "france gdp", "france pmi",
            // Italie / Espagne
            "italy cpi", "italy gdp", "spain cpi", "spain gdp"
        };
        if (containsAny(combined, euroIndicators)) {
            return true; // ✅ EURUSD, GOLD, GBPUSD
        }
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 🇦🇺 AUSTRALIE (Impact: AUDUSD, GOLD)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        String[] australiaIndicators = {
            "rba", "reserve bank australia", "rba rate", "rba minutes",
            "lowe", "philip lowe", "bullock", "michele bullock",
            "australia cpi", "aus cpi", "australia inflation",
            "australia gdp", "aus gdp",
            "australia employment", "aus employment", "australia unemployment",
            "australia retail sales", "aus retail",
            "australia trade balance", "australia pmi",
            "westpac consumer confidence", "nab business confidence",
            // Commodités impactant AUD
            "iron ore", "iron ore price", "mining output",
            "china pmi", "china gdp", "china trade" // Impact indirect via Chine
        };
        if (containsAny(combined, australiaIndicators)) {
            return true; // ✅ AUDUSD, GOLD
        }
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 🇨🇦 CANADA (Impact: USDCAD, OIL)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        String[] canadaIndicators = {
            "boc", "bank of canada", "boc rate", "boc minutes",
            "macklem", "tiff macklem",
            "canada cpi", "canada inflation", "canada core cpi",
            "canada gdp", "canada employment", "canada unemployment",
            "canada retail sales", "canada trade balance",
            "canada pmi", "ivey pmi", "canada housing starts"
        };
        if (containsAny(combined, canadaIndicators)) {
            return true; // ✅ USDCAD, OIL, GOLD
        }
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 🛢️ PÉTROLE (Impact: OIL, USDCAD)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        String[] oilIndicators = {
            "eia", "eia report", "crude oil inventories", "eia crude",
            "api", "api inventories", "api crude",
            "opec", "opec+", "opec meeting", "opec production",
            "saudi", "saudi arabia", "saudi aramco",
            "russia oil", "russia production", "russian oil",
            "iran oil", "iran sanctions", "iran exports",
            "oil production", "crude production", "shale production",
            "baker hughes", "rig count", "oil rigs",
            "iea", "international energy agency", "iea report",
            "spr", "strategic petroleum reserve"
        };
        if (containsAny(combined, oilIndicators)) {
            return true; // ✅ OIL, USDCAD
        }
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 🥇 GOLD (Événements spécifiques or)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        String[] goldIndicators = {
            "gold", "xauusd", "gold price", "gold demand",
            "central bank gold", "gold reserves",
            "real yields", "tips", "breakeven inflation"
        };
        if (containsAny(combined, goldIndicators) && 
            (combined.contains("demand") || combined.contains("reserve") || 
             combined.contains("central bank"))) {
            return true; // ✅ GOLD
        }
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // ₿ CRYPTO (Impact: BTCUSD)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        String[] cryptoIndicators = {
            "sec crypto", "sec bitcoin", "sec ethereum",
            "spot etf", "bitcoin etf", "crypto etf",
            "gbtc", "grayscale", "blackrock bitcoin",
            "coinbase", "binance", "crypto exchange",
            "bitcoin halving", "btc halving",
            "crypto regulation", "crypto ban", "crypto law",
            "el salvador bitcoin", "microstrategy bitcoin",
            "mining difficulty", "hash rate"
        };
        if (containsAny(combined, cryptoIndicators)) {
            return true; // ✅ BTCUSD
        }
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 📈 ACTIONS US (Impact: SP500, NASDAQ)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        String[] stockIndicators = {
            "earnings", "quarterly earnings", "q1 earnings", "q2 earnings",
            "revenue", "eps", "guidance",
            // Big Tech (impact NASDAQ)
            "apple earnings", "aapl", "microsoft earnings", "msft",
            "nvidia earnings", "nvda", "tesla earnings", "tsla",
            "amazon earnings", "amzn", "meta earnings", "meta",
            "alphabet earnings", "googl", "google earnings",
            "netflix earnings", "nflx",
            // Indices
            "s&p rebalance", "nasdaq rebalance", "index addition",
            "vix", "vix spike", "volatility index"
        };
        if (containsAny(combined, stockIndicators)) {
            return true; // ✅ SP500, NASDAQ
        }
        
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        // 🌍 GÉOPOLITIQUE (Impact: tous actifs)
        // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
        
        String[] geopoliticalIndicators = {
            "war", "military action", "invasion", "attack",
            "missile strike", "drone attack", "bombing",
            "sanctions", "economic sanctions", "trade sanctions",
            "nuclear", "nuclear threat", "nuclear test",
            "conflict", "military conflict", "armed conflict",
            "middle east crisis", "taiwan strait", "ukraine war",
            "iran israel", "north korea missile",
            "trade war", "tariffs", "import duties"
        };
        if (containsAny(combined, geopoliticalIndicators) && 
            (combined.contains("breaking") || combined.contains("urgent") || 
             combined.contains("alert"))) {
            return true; // ✅ GOLD, USDJPY, OIL, tous actifs en risk-off
        }
        
        // ========================================
        // ✅ VALIDATION PAR CONFIANCE
        // ========================================
        
        // Si aucun keyword macro mais confiance très haute (>90%) et impact fort
        if (event.confidence >= 90 && 
            ("Haussier".equals(event.impact) || "Baissier".equals(event.impact))) {
            return true;
        }
        
        // ========================================
        // ❌ REJET PAR DÉFAUT
        // ========================================
        
        return false;
    }
    
    /**
     * Vérifier si le texte contient au moins un keyword d'une liste
     */
    private static boolean containsAny(String text, String[] keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Vérifier si le texte contient au moins un mot-clé macro
     */
    private static boolean containsAnyMacroKeyword(String text) {
        String[] macroKeywords = {
            "fed", "fomc", "powell", "boe", "boj", "ecb", "rba", "boc",
            "nfp", "cpi", "gdp", "pmi", "inflation", "employment",
            "retail sales", "trade balance", "eia", "opec"
        };
        return containsAny(text, macroKeywords);
    }
    // =====================================================
    // ✨ DÉTECTION CALENDRIER FINANCIALJUICE - TOUS ÉVÉNEMENTS HIGH
    // =====================================================
    
    /**
     * Détecter si c'est une notification de calendrier économique HIGH importance
     */
    private boolean isEconomicCalendarNotification(String appName, String title, String content) {
        String combined = (title + " " + content).toLowerCase();
        
        // ✅ FINANCIALJUICE - CAPTURER TOUS LES ÉVÉNEMENTS HIGH
        if (appName.equals("FinancialJuice")) {
            
            // Pattern 1: Notification avec "High" importance
            if (combined.contains("high") || combined.contains("🔴") || 
                combined.contains("red dot") || combined.contains("high impact")) {
                
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog("[FJ] 🔴 HIGH importance détecté");
                }
                return true;
            }
            
            // Pattern 2: Indicateurs macro majeurs (toujours HIGH)
            String[] highIndicators = {
                "nfp", "non-farm payroll", "payrolls",
                "cpi", "consumer price", "inflation",
                "gdp", "gross domestic product",
                "fomc", "fed rate", "federal reserve", "interest rate decision",
                "boe rate", "bank of england", "mpc",
                "boj", "bank of japan",
                "ecb rate", "european central bank",
                "rba rate", "reserve bank australia",
                "boc rate", "bank of canada",
                "eia", "crude oil inventory",
                "retail sales",
                "unemployment rate",
                "pmi", "ism",
                "trade balance"
            };
            
            for (String indicator : highIndicators) {
                if (combined.contains(indicator)) {
                    if (MainActivity.instance != null) {
                        MainActivity.instance.addLog(
                            "[FJ] 📅 Indicateur HIGH: " + indicator
                        );
                    }
                    return true;
                }
            }
            
            // Pattern 3: Contient des données économiques (Forecast/Previous/Actual)
            if ((combined.contains("forecast") || combined.contains("expected")) &&
                (combined.contains("previous") || combined.contains("prior"))) {
                
                // Vérifier qu'il y a des chiffres
                if (combined.matches(".*\\d+[.,]?\\d*[%KMB]?.*")) {
                    if (MainActivity.instance != null) {
                        MainActivity.instance.addLog("[FJ] 📊 Données économiques détectées");
                    }
                    return true;
                }
            }
            
            // Pattern 4: Format calendrier avec timing
            if (combined.matches(".*\\d{1,2}:\\d{2}\\s*(am|pm|et|gmt).*") &&
                (combined.contains("releasing") || combined.contains("scheduled") || 
                 combined.contains("expected at"))) {
                
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog("[FJ] ⏰ Publication programmée");
                }
                return true;
            }
        }
        
        // ✅ Patterns génériques pour TOUS les apps
        
        // Liste des pays pour nos actifs
        String[] targetCountries = {
            "united states", "us ", "usa", "u.s.",
            "united kingdom", "uk ", "britain",
            "japan", "japanese",
            "eurozone", "euro area", "germany", "german",
            "australia", "australian",
            "canada", "canadian"
        };
        
        boolean hasTargetCountry = false;
        for (String country : targetCountries) {
            if (combined.contains(country)) {
                hasTargetCountry = true;
                break;
            }
        }
        
        if (hasTargetCountry) {
            // Vérifier si contient un indicateur macro majeur
            String[] macroIndicators = {
                "cpi", "inflation", "nfp", "payroll", "employment",
                "gdp", "pmi", "retail sales", "interest rate",
                "central bank", "fed", "boe", "boj", "ecb", "rba", "boc",
                "eia", "oil inventory"
            };
            
            for (String indicator : macroIndicators) {
                if (combined.contains(indicator)) {
                    // Vérifier présence de données chiffrées
                    if (combined.matches(".*forecast.*\\d+.*") ||
                        combined.matches(".*expected.*\\d+.*") ||
                        combined.matches(".*previous.*\\d+.*") ||
                        combined.matches(".*actual.*\\d+.*")) {
                        
                        if (MainActivity.instance != null) {
                            MainActivity.instance.addLog(
                                "[CALENDAR] 📅 " + indicator + " avec données - " + appName
                            );
                        }
                        return true;
                    }
                }
            }
        }
        
        return false;
    }
    
    // =====================================================
    // CLASSE POUR STOCKER LES DONNÉES DU CALENDRIER
    // =====================================================
    
    private static class CalendarData {
        String indicator;
        String forecast;
        String previous;
        String actual;
        String country;
        String releaseTime;
        
        CalendarData() {
            this.indicator = "Unknown";
            this.forecast = "N/A";
            this.previous = "N/A";
            this.actual = "N/A";
            this.country = "Unknown";
            this.releaseTime = "Now";
        }
        
        boolean hasActual() {
            return actual != null && !actual.equals("N/A") && !actual.isEmpty();
        }
    }
    
    // =====================================================
    // EXTRACTION DES DONNÉES DU CALENDRIER
    // =====================================================
    
    /**
     * ✨ EXTRACTION AMÉLIORÉE - Tous formats FinancialJuice
     */
    private CalendarData extractCalendarData(String title, String content) {
        CalendarData data = new CalendarData();
        String combined = title + " " + content;
        String lower = combined.toLowerCase();
        
        // ========================================
        // 1. EXTRAIRE L'INDICATEUR
        // ========================================
        
        // Indicateurs US
        if (lower.contains("nfp") || lower.contains("non-farm")) {
            data.indicator = "Non-Farm Payrolls (NFP)";
            data.country = "United States";
        } else if (lower.contains("cpi") && !lower.contains("core")) {
            data.indicator = "Consumer Price Index (CPI)";
            data.country = detectCountry(combined);
        } else if (lower.contains("core cpi")) {
            data.indicator = "Core CPI";
            data.country = detectCountry(combined);
        } else if (lower.contains("pce")) {
            data.indicator = "Personal Consumption Expenditures (PCE)";
            data.country = "United States";
        } else if (lower.contains("gdp") && lower.contains("prelim")) {
            data.indicator = "GDP Preliminary";
            data.country = detectCountry(combined);
        } else if (lower.contains("gdp")) {
            data.indicator = "Gross Domestic Product (GDP)";
            data.country = detectCountry(combined);
        } else if (lower.contains("retail sales")) {
            data.indicator = "Retail Sales";
            data.country = detectCountry(combined);
        } else if (lower.contains("unemployment")) {
            data.indicator = "Unemployment Rate";
            data.country = detectCountry(combined);
        } else if (lower.contains("jobless claims") || lower.contains("initial claims")) {
            data.indicator = "Initial Jobless Claims";
            data.country = "United States";
        } else if (lower.contains("pmi") && lower.contains("manufactur")) {
            data.indicator = "Manufacturing PMI";
            data.country = detectCountry(combined);
        } else if (lower.contains("pmi") && lower.contains("service")) {
            data.indicator = "Services PMI";
            data.country = detectCountry(combined);
        } else if (lower.contains("pmi")) {
            data.indicator = "PMI";
            data.country = detectCountry(combined);
        } else if (lower.contains("ism")) {
            data.indicator = "ISM Manufacturing";
            data.country = "United States";
        }
        
        // Banques centrales
        else if (lower.contains("fomc") || (lower.contains("fed") && lower.contains("rate"))) {
            data.indicator = "FOMC Rate Decision";
            data.country = "United States";
        } else if (lower.contains("fed") && lower.contains("minute")) {
            data.indicator = "FOMC Minutes";
            data.country = "United States";
        } else if (lower.contains("boe") || (lower.contains("bank of england") && lower.contains("rate"))) {
            data.indicator = "BoE Rate Decision";
            data.country = "United Kingdom";
        } else if (lower.contains("boj")) {
            data.indicator = "BoJ Policy Decision";
            data.country = "Japan";
        } else if (lower.contains("ecb") && lower.contains("rate")) {
            data.indicator = "ECB Rate Decision";
            data.country = "Eurozone";
        } else if (lower.contains("rba") && lower.contains("rate")) {
            data.indicator = "RBA Rate Decision";
            data.country = "Australia";
        } else if (lower.contains("boc") && lower.contains("rate")) {
            data.indicator = "BoC Rate Decision";
            data.country = "Canada";
        }
        
        // Pétrole
        else if (lower.contains("eia") || lower.contains("crude oil inventor")) {
            data.indicator = "EIA Crude Oil Inventories";
            data.country = "United States";
        } else if (lower.contains("api") && lower.contains("inventor")) {
            data.indicator = "API Crude Oil Inventories";
            data.country = "United States";
        } else if (lower.contains("opec")) {
            data.indicator = "OPEC Meeting";
            data.country = "Global";
        }
        
        // UK
        else if (lower.contains("uk") && lower.contains("cpi")) {
            data.indicator = "UK Consumer Price Index";
            data.country = "United Kingdom";
        } else if (lower.contains("uk") && lower.contains("gdp")) {
            data.indicator = "UK GDP";
            data.country = "United Kingdom";
        } else if (lower.contains("uk") && lower.contains("employment")) {
            data.indicator = "UK Employment";
            data.country = "United Kingdom";
        }
        
        // Australie
        else if (lower.contains("australia") && lower.contains("employment")) {
            data.indicator = "Australia Employment Change";
            data.country = "Australia";
        } else if (lower.contains("australia") && lower.contains("cpi")) {
            data.indicator = "Australia CPI";
            data.country = "Australia";
        } else if (lower.contains("australia") && lower.contains("gdp")) {
            data.indicator = "Australia GDP";
            data.country = "Australia";
        } else if (lower.contains("australia") && lower.contains("retail")) {
            data.indicator = "Australia Retail Sales";
            data.country = "Australia";
        }
        
        // Canada
        else if (lower.contains("canada") && lower.contains("employment")) {
            data.indicator = "Canada Employment Change";
            data.country = "Canada";
        } else if (lower.contains("canada") && lower.contains("cpi")) {
            data.indicator = "Canada CPI";
            data.country = "Canada";
        } else if (lower.contains("canada") && lower.contains("gdp")) {
            data.indicator = "Canada GDP";
            data.country = "Canada";
        }
        
        // Fallback: extraire du titre
        else {
            // Nettoyer le titre des emojis et caractères spéciaux
            String cleanTitle = title.replaceAll("[🔴⏰📅📊]", "").trim();
            data.indicator = cleanTitle.isEmpty() ? "Economic Event" : cleanTitle;
            data.country = detectCountry(combined);
        }
        
        // ========================================
        // 2. EXTRAIRE LES DONNÉES NUMÉRIQUES
        // ========================================
        
        data.forecast = extractValue(combined, "forecast", "expected", "exp", "f:", "fcst");
        data.previous = extractValue(combined, "previous", "prev", "prior", "last", "p:");
        data.actual = extractValue(combined, "actual", "a:", "released", "came in", "reported");
        
        // ========================================
        // 3. EXTRAIRE LE TIMING
        // ========================================
        
        data.releaseTime = extractReleaseTime(combined);
        
        // Si pas de pays détecté, essayer de déduire de l'indicateur
        if (data.country.equals("Unknown")) {
            if (data.indicator.contains("US") || data.indicator.contains("Fed") || 
                data.indicator.contains("NFP") || data.indicator.contains("EIA")) {
                data.country = "United States";
            } else if (data.indicator.contains("UK") || data.indicator.contains("BoE")) {
                data.country = "United Kingdom";
            } else if (data.indicator.contains("Japan") || data.indicator.contains("BoJ")) {
                data.country = "Japan";
            } else if (data.indicator.contains("Australia") || data.indicator.contains("RBA")) {
                data.country = "Australia";
            } else if (data.indicator.contains("Canada") || data.indicator.contains("BoC")) {
                data.country = "Canada";
            } else if (data.indicator.contains("Euro") || data.indicator.contains("ECB")) {
                data.country = "Eurozone";
            }
        }
        
        return data;
    }
    
    /**
     * Extraire une valeur numérique depuis le texte
     */
    private String extractValue(String text, String... keywords) {
        String lower = text.toLowerCase();
        
        for (String keyword : keywords) {
            int index = lower.indexOf(keyword);
            if (index != -1) {
                // Chercher un nombre après le keyword (dans les 30 chars)
                String substr = text.substring(index, Math.min(text.length(), index + 30));
                
                // Pattern pour capturer les nombres
                Pattern pattern = Pattern.compile(
                    "([-+]?\\d+[.,]?\\d*\\s*[%KMB]?)",
                    Pattern.CASE_INSENSITIVE
                );
                Matcher matcher = pattern.matcher(substr);
                
                if (matcher.find()) {
                    return matcher.group(1).trim();
                }
            }
        }
        
        return "N/A";
    }
    
    /**
     * Extraire l'heure de publication
     */
    private String extractReleaseTime(String text) {
        // Pattern pour capturer l'heure (ex: 8:30 AM, 14:30, 2:30pm ET)
        Pattern timePattern = Pattern.compile(
            "(\\d{1,2}:\\d{2}\\s*(?:AM|PM|am|pm)?(?:\\s*(?:ET|EST|GMT|UTC))?)",
            Pattern.CASE_INSENSITIVE
        );
        Matcher matcher = timePattern.matcher(text);
        
        if (matcher.find()) {
            return matcher.group(1);
        }
        
        // Chercher "in X minutes"
        Pattern minutesPattern = Pattern.compile("(\\d+)\\s*minutes?\\s*(?:until|till)", Pattern.CASE_INSENSITIVE);
        Matcher minutesMatcher = minutesPattern.matcher(text);
        
        if (minutesMatcher.find()) {
            return "Dans " + minutesMatcher.group(1) + " minutes";
        }
        
        return "Maintenant";
    }
    
    // =====================================================
    // ✨ TRAITEMENT PRIORITAIRE CALENDRIER ÉCONOMIQUE
    // =====================================================
    
    // =====================================================
    // ✨ TRAITEMENT PRIORITAIRE CALENDRIER ÉCONOMIQUE
    // =====================================================
    
    private void processCalendarEventWithPriority(String appName, String title, 
                                                   String content, CalendarData calData) {
        try {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog(
                    "[PRIORITY] 🚨 TRAITEMENT IMMÉDIAT: " + calData.indicator
                );
            }
            
            // Créer un DetectedEvent enrichi avec les données du calendrier
            EconomicEventDetector.DetectedEvent detectedEvent = 
                EconomicEventDetector.detectEvent(title, content);
            
            // ✅ Enrichir avec les données extraites
            detectedEvent.indicator = calData.indicator;
            detectedEvent.forecast = calData.forecast;
            detectedEvent.previous = calData.previous;
            detectedEvent.actual = calData.actual;
            detectedEvent.country = calData.country;
            
            // Forcer l'importance à HIGH si Actual publié
            if (calData.hasActual()) {
                detectedEvent.eventType = "ECONOMIC_RELEASE";
            } else {
                detectedEvent.eventType = "ECONOMIC_CALENDAR";
            }
            
            // Détecter les actifs impactés
            List<String> assets = detectAssetsWithScoring(title + " " + content);
            
            // Si peu d'actifs détectés, enrichir selon l'indicateur
            if (assets.size() < 2) {
                assets = enrichAssetsFromIndicator(calData.indicator, calData.country);
            }
            
            String assetsStr = String.join(", ", assets);
            String eventId = generateEventId(appName, title, content);
            
            // Sauvegarder en DB avec confiance maximale
            int confidence = calData.hasActual() ? 95 : 85;
            
            boolean saved = eventDb.saveEvent(
                eventId, 
                appName, 
                appName, 
                detectedEvent.eventType, 
                calData.indicator, 
                content, 
                assetsStr, 
                detectedEvent.impact
            );
            
            if (!saved) {
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog("[PRIORITY] ⚠️ Déjà traité - ignoré");
                }
                return;
            }
            
            // ✅ ANALYSE IMMÉDIATE AVEC FLAG PRIORITAIRE
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog(
                    "[PRIORITY] 🔥 ANALYSE EN COURS: " + assetsStr
                );
            }
            
            // =====================================================
            // Construire un contexte enrichi
            // =====================================================
            StringBuilder enrichedContent = new StringBuilder();
            
            // ✅ Adapter le header selon type de données
            if (calData.hasActual()) {
                enrichedContent.append("📅 PUBLICATION CALENDRIER ÉCONOMIQUE - ACTUAL PUBLIÉ\n\n");
            } else {
                enrichedContent.append("📅 CALENDRIER ÉCONOMIQUE HIGH IMPORTANCE\n\n");
            }
            
            enrichedContent.append("Indicateur: ").append(calData.indicator).append("\n");
            enrichedContent.append("Pays: ").append(calData.country).append("\n");
            enrichedContent.append("Heure: ").append(calData.releaseTime).append("\n\n");
            
            // ✅ Afficher TOUTES les données disponibles
            boolean hasData = false;
            if (!calData.forecast.equals("N/A")) {
                enrichedContent.append("🎯 Prévision: ").append(calData.forecast).append("\n");
                hasData = true;
            }
            if (!calData.previous.equals("N/A")) {
                enrichedContent.append("📋 Précédent: ").append(calData.previous).append("\n");
                hasData = true;
            }
            if (calData.hasActual()) {
                enrichedContent.append("✅ ACTUEL: ").append(calData.actual).append("\n");
                hasData = true;
            }
            
            if (hasData) {
                enrichedContent.append("\n");
            }
            
            // ✅ Calculer la surprise SEULEMENT si possible
            if (calData.hasActual() && !calData.forecast.equals("N/A")) {
                try {
                    double actualVal = parseNumericValue(calData.actual);
                    double forecastVal = parseNumericValue(calData.forecast);
                    double diff = actualVal - forecastVal;
                    double diffPct = (diff / Math.abs(forecastVal)) * 100;
                    
                    String surpriseLevel;
                    if (Math.abs(diffPct) > 1.0) {
                        surpriseLevel = "⚠️ SURPRISE MAJEURE";
                    } else if (Math.abs(diffPct) > 0.5) {
                        surpriseLevel = "⚡ Surprise significative";
                    } else if (Math.abs(diffPct) > 0.2) {
                        surpriseLevel = "📊 Léger écart";
                    } else {
                        surpriseLevel = "✓ Conforme aux attentes";
                    }
                    
                    enrichedContent.append("Écart: ").append(String.format("%.2f%%", diffPct))
                                  .append(" - ").append(surpriseLevel).append("\n\n");
                } catch (Exception e) {
                    // Ignore si parsing impossible
                }
            } else if (!calData.hasActual()) {
                // ✅ Instruction spéciale si pas d'actual
                enrichedContent.append("⚠️ NOTE: Actual pas encore publié. ");
                enrichedContent.append("Analyser l'impact POTENTIEL basé sur forecast vs previous.\n\n");
            }
            
            enrichedContent.append("Détails:\n").append(content);
            
            // =====================================================
            // ✅ ANALYSE GROQ AVEC PRIORITÉ MAX
            // =====================================================
            String analysis = analyzeWithGroqEnhanced(
                enrichedContent.toString(),
                assetsStr,
                detectedEvent,
                null,
                true  // ✅ Flag DRIVER = true pour priorité max
            );
            
            // =====================================================
            // ✅ ENVOI TELEGRAM IMMÉDIAT
            // =====================================================
            String ts = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                .format(new Date());
            
            StringBuilder tgMsg = new StringBuilder();
            
            // ✅ ADAPTER LE TITRE selon les données disponibles
            if (calData.hasActual()) {
                tgMsg.append("🔴 **PUBLICATION OFFICIELLE** 🔴\n");
            } else {
                tgMsg.append("📅 **ÉVÉNEMENT ÉCONOMIQUE HIGH** 📅\n");
            }
            
            tgMsg.append("Source: ").append(appName).append(" | ").append(ts).append("\n\n");
            tgMsg.append("**").append(calData.indicator).append("**\n");
            tgMsg.append(calData.country).append(" - ").append(calData.releaseTime).append("\n\n");
            
            // ✅ Afficher les données disponibles
            boolean hasTelegramData = false;
            
            if (!calData.forecast.equals("N/A")) {
                tgMsg.append("🎯 Prévision: ").append(calData.forecast).append("\n");
                hasTelegramData = true;
            }
            if (!calData.previous.equals("N/A")) {
                tgMsg.append("📋 Précédent: ").append(calData.previous).append("\n");
                hasTelegramData = true;
            }
            if (calData.hasActual()) {
                tgMsg.append("✅ **ACTUEL: ").append(calData.actual).append("**\n");
                hasTelegramData = true;
            }
            
            // ✅ Calculer surprise SEULEMENT si actual présent
            if (calData.hasActual() && !calData.forecast.equals("N/A")) {
                try {
                    double actualVal = parseNumericValue(calData.actual);
                    double forecastVal = parseNumericValue(calData.forecast);
                    double diff = actualVal - forecastVal;
                    double diffPct = (diff / Math.abs(forecastVal)) * 100;
                    
                    if (Math.abs(diffPct) > 0.2) {
                        String emoji = Math.abs(diffPct) > 1.0 ? "⚠️" : 
                                      Math.abs(diffPct) > 0.5 ? "⚡" : "📊";
                        tgMsg.append(emoji).append(" Écart: ")
                             .append(String.format("%.2f%%", diffPct)).append("\n");
                    }
                } catch (Exception e) {
                    // Ignore si parsing impossible
                }
            }
            
            if (hasTelegramData) {
                tgMsg.append("\n");
            }
            
            // ✅ Détails de la notification (seulement si contenu utile)
            if (content.length() > 50) {
                tgMsg.append("**Détails:**\n");
                String shortContent = content.length() > 200 ? 
                    content.substring(0, 200) + "..." : content;
                tgMsg.append(shortContent);
                tgMsg.append("\n\n");
            }
            
            // ✅ Analyse PAR ACTIF (toujours présente)
            tgMsg.append("**ANALYSE PAR ACTIF:**\n");
            tgMsg.append(analysis);
            
            // =====================================================
            // Envoyer sur Telegram
            // =====================================================
            sendTelegram(tgMsg.toString());
            
            // =====================================================
            // Notification locale Android
            // =====================================================
            showLocalNotif(this, assetsStr, analysis, detectedEvent.impact);
            
            // =====================================================
            // Sauvegarder dans rapport quotidien
            // =====================================================
            saveToDailyReport(detectedEvent, content, analysis, assets);
            
            // =====================================================
            // Marquer comme traité dans la DB
            // =====================================================
            EventDatabase eventDatabase = new EventDatabase(this);
            try {
                int dbId = Integer.parseInt(eventId.substring(Math.max(0, eventId.length() - 8)), 16);
                eventDatabase.markProcessed(dbId, analysis);
            } catch (Exception e) {
                // Ignore si impossible de parser l'ID
            }
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog(
                    "[PRIORITY] ✅ ENVOYÉ AVEC SUCCÈS - " + assetsStr
                );
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Erreur processCalendarEventWithPriority", e);
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog(
                    "[PRIORITY] ❌ Erreur: " + e.getMessage()
                );
            }
        }
    }
    /**
     * Enrichir les actifs selon l'indicateur
     */
    private List<String> enrichAssetsFromIndicator(String indicator, String country) {
        List<String> assets = new ArrayList<>();
        String lower = indicator.toLowerCase();
        String countryLower = country.toLowerCase();
        
        // ÉTATS-UNIS
        if (countryLower.contains("united states") || countryLower.contains("us")) {
            if (lower.contains("nfp") || lower.contains("non-farm") || lower.contains("payroll")) {
                assets.add("EURUSD");
                assets.add("GBPUSD");
                assets.add("USDJPY");
                assets.add("AUDUSD");
                assets.add("USDCAD");
                assets.add("GOLD");
                assets.add("BTCUSD");
                assets.add("SP500");
                assets.add("NASDAQ");
            } else if (lower.contains("cpi") || lower.contains("inflation")) {
                assets.add("GOLD");
                assets.add("BTCUSD");
                assets.add("EURUSD");
                assets.add("GBPUSD");
                assets.add("USDJPY");
                assets.add("AUDUSD");
                assets.add("SP500");
                assets.add("NASDAQ");
            } else if (lower.contains("fed") || lower.contains("fomc")) {
                assets.add("EURUSD");
                assets.add("GBPUSD");
                assets.add("USDJPY");
                assets.add("AUDUSD");
                assets.add("USDCAD");
                assets.add("GOLD");
                assets.add("BTCUSD");
                assets.add("SP500");
                assets.add("NASDAQ");
            } else if (lower.contains("eia") || lower.contains("oil")) {
                assets.add("OIL");
                assets.add("USDCAD");
            } else {
                assets.add("EURUSD");
                assets.add("GOLD");
                assets.add("SP500");
            }
        }
        // ROYAUME-UNI
        else if (countryLower.contains("uk") || countryLower.contains("britain")) {
            assets.add("GBPUSD");
            if (lower.contains("boe") || lower.contains("rate") || lower.contains("cpi")) {
                assets.add("GOLD");
                assets.add("EURUSD");
            }
        }
        // JAPON
        else if (countryLower.contains("japan")) {
            assets.add("USDJPY");
            if (lower.contains("boj") || lower.contains("rate")) {
                assets.add("GOLD");
            }
        }
        // EUROZONE
        else if (countryLower.contains("euro") || countryLower.contains("germany")) {
            assets.add("EURUSD");
            if (lower.contains("ecb") || lower.contains("rate")) {
                assets.add("GOLD");
            }
        }
        // AUSTRALIE
        else if (countryLower.contains("australia")) {
            assets.add("AUDUSD");
            if (lower.contains("rba") || lower.contains("rate") || lower.contains("cpi")) {
                assets.add("GOLD");
            }
        }
        // CANADA
        else if (countryLower.contains("canada")) {
            assets.add("USDCAD");
            if (lower.contains("boc") || lower.contains("rate")) {
                assets.add("OIL");
                assets.add("GOLD");
            }
        }
        
        // Fallback
        if (assets.isEmpty()) {
            assets.add("GOLD");
            assets.add("EURUSD");
        }
        
        return assets;
    }
    // =====================================================
    // CORRÉLATION AVEC CALENDRIER ÉCONOMIQUE
    // =====================================================
    
    private void correlateWithCalendar(String notificationEventId, String country, 
                                       List<String> assets, String importance, 
                                       int confidence) {
        try {
            List<EconomicCalendarAPI.CalendarEvent> upcomingEvents = EconomicCalendarAPI.fetchUpcomingEvents(1);
            
            if (upcomingEvents.isEmpty()) {
                return;
            }
            
            long now = System.currentTimeMillis();
            long correlationWindow = 30 * 60 * 1000;
            
            boolean matchFound = false;
            EconomicCalendarAPI.CalendarEvent matchedEvent = null;
            
            for (EconomicCalendarAPI.CalendarEvent calEvent : upcomingEvents) {
                long eventTime = Long.parseLong(calEvent.timestamp) * 1000;
                long timeDiff = Math.abs(eventTime - now);
                
                if (timeDiff > correlationWindow) {
                    continue;
                }
                
                boolean countryMatch = calEvent.country.toLowerCase().contains(country.toLowerCase()) ||
                                      country.toLowerCase().contains(calEvent.country.toLowerCase());
                
                if (!countryMatch && !country.equals("Unknown")) {
                    continue;
                }
                
                for (String asset : assets) {
                    if (calEvent.affectedAssets.contains(asset)) {
                        matchFound = true;
                        matchedEvent = calEvent;
                        break;
                    }
                }
                
                if (matchFound) break;
            }
            
            if (matchFound && matchedEvent != null) {
                int newConfidence = Math.min(100, confidence + 20);
                eventDb.updateConfidence(notificationEventId, newConfidence);
                
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog(
                        "[CORRELATION] ✓ Notification ↔ Calendrier: " + 
                        matchedEvent.indicator + " | Confiance: " + 
                        confidence + "% → " + newConfidence + "%"
                    );
                }
                
                sendCorrelationAlert(notificationEventId, matchedEvent, assets, newConfidence);
                
                if (eventDetector != null) {
                    eventDetector.checkRecentEvents();
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Erreur correlateWithCalendar", e);
        }
    }
    
    private void sendCorrelationAlert(String notifId, 
                                      EconomicCalendarAPI.CalendarEvent calEvent,
                                      List<String> assets, int confidence) {
        try {
            long eventTime = Long.parseLong(calEvent.timestamp) * 1000;
            long now = System.currentTimeMillis();
            long minutesUntil = (eventTime - now) / (60 * 1000);
            
            StringBuilder message = new StringBuilder();
            message.append("🔗 **CORRÉLATION CONFIRMÉE**\n\n");
            message.append("Notification ↔ Calendrier économique!\n\n");
            message.append("**Événement programmé:**\n");
            message.append(calEvent.country).append(" - ").append(calEvent.indicator).append("\n");
            message.append("Dans ").append(minutesUntil).append(" minutes\n\n");
            message.append("**Forecast:** ").append(calEvent.forecast).append("\n");
            message.append("**Previous:** ").append(calEvent.previous).append("\n\n");
            message.append("**Actifs impactés:**\n");
            
            for (String asset : assets) {
                message.append("  • ").append(asset).append("\n");
            }
            
            message.append("\n**Confiance:** ").append(confidence).append("%");
            
            sendTelegram(message.toString());
            
        } catch (Exception e) {
            Log.e(TAG, "Erreur sendCorrelationAlert", e);
        }
    }

    // =====================================================
    // ✨ IDENTIFIER LE DRIVER PRINCIPAL DU MARCHÉ
    // =====================================================
    
    /**
     * Identifier l'événement DRIVER qui dirige vraiment le marché
     */
    private EventDatabase.StoredEvent identifyMainMarketDriver(long since) {
        List<EventDatabase.StoredEvent> allEvents = 
            eventDb.getEventsInTimeWindow(since, System.currentTimeMillis() - since);
        
        if (allEvents.isEmpty()) {
            return null;
        }
        
        EventDatabase.StoredEvent mainDriver = null;
        double maxScore = 0;
        
        for (EventDatabase.StoredEvent event : allEvents) {
            double score = 0;
            
            // ✅ CRITÈRE 1: Type d'événement (poids max)
            if (event.eventType.contains("CENTRAL_BANK")) {
                score += 100; // Fed, BOE, BOJ = drivers absolus
            } else if (event.eventType.contains("EMPLOYMENT")) {
                score += 80; // NFP, Jobs
            } else if (event.eventType.contains("INFLATION")) {
                score += 75; // CPI, PCE
            } else if (event.eventType.contains("GROWTH")) {
                score += 60; // GDP, PMI
            } else if (event.eventType.contains("COMMODITY")) {
                score += 50; // EIA, OPEC
            }
            
            // ✅ CRITÈRE 2: Confiance (0-30 points)
            score += (event.confidence / 100.0) * 30;
            
            // ✅ CRITÈRE 3: Nombre d'actifs impactés (0-20 points)
            int assetCount = event.assets.split(",").length;
            score += Math.min(assetCount * 3, 20);
            
            // ✅ CRITÈRE 4: Fraîcheur (0-25 points)
            long age = System.currentTimeMillis() - event.timestamp;
            if (age < 1 * 60 * 60 * 1000) {
                score += 25; // < 1h
            } else if (age < 3 * 60 * 60 * 1000) {
                score += 15; // 1-3h
            } else if (age < 6 * 60 * 60 * 1000) {
                score += 5; // 3-6h
            }
            
            // ✅ CRITÈRE 5: Impact (0-15 points)
            if ("Haussier".equals(event.impact) || "Baissier".equals(event.impact)) {
                score += 15;
            }
            
            // ✅ CRITÈRE 6: Boost si données actual présentes
            if (event.title.toLowerCase().contains("actual") || 
                event.content.toLowerCase().contains("came in at")) {
                score += 10;
            }
            
            if (score > maxScore) {
                maxScore = score;
                mainDriver = event;
            }
        }
        
        return mainDriver;
    }
    
    // =====================================================
    // VÉRIFIER ÉVÉNEMENTS MANQUÉS DU CALENDRIER
    // =====================================================
    
    private void checkMissedCalendarEvents() {
        long now = System.currentTimeMillis();
        long window = 15 * 60 * 1000;
        
        List<EconomicCalendarAPI.CalendarEvent> recent = EconomicCalendarAPI.fetchRecentEvents(15);
        
        for (EconomicCalendarAPI.CalendarEvent event : recent) {
            long eventTime = parseTimestamp(event.timestamp);
            
            if (eventTime < now && eventTime > (now - window)) {
                
                String eventId = "calendar_" + event.indicator + "_" + event.timestamp;
                
                if (!eventDb.eventExists(eventId)) {
                    if (MainActivity.instance != null) {
                        MainActivity.instance.addLog("[MISSED] ⚠️ Événement calendrier détecté: " + 
                            event.indicator + " (" + event.country + ")");
                    }
                    
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

    // =====================================================
    // DÉTECTION DU PAYS
    // =====================================================
    
    private String detectCountry(String contentLower) {
        String lower = contentLower.toLowerCase();
        
        if (lower.contains("us ") || lower.contains("usa") || lower.contains("united states") ||
            lower.contains("america") || lower.contains("american")) {
            return "United States";
        }
        
        if (lower.contains("euro") || lower.contains("germany") || lower.contains("german") ||
            lower.contains("france") || lower.contains("french")) {
            return "Eurozone";
        }
        
        if (lower.contains("uk ") || lower.contains("britain") || lower.contains("british") ||
            lower.contains("england") || lower.contains("united kingdom")) {
            return "United Kingdom";
        }
        
        if (lower.contains("japan") || lower.contains("japanese")) {
            return "Japan";
        }
        
        if (lower.contains("australia") || lower.contains("australian") || lower.contains("aussie")) {
            return "Australia";
        }
        
        if (lower.contains("canada") || lower.contains("canadian")) {
            return "Canada";
        }
        
        if (lower.contains("china") || lower.contains("chinese")) {
            return "China";
        }
        
        return "Unknown";
    }

    // =====================================================
    // DÉTECTION ACTIFS AVEC SCORING INTELLIGENT
    // =====================================================
    
    private static List<String> detectAssetsWithScoring(String text) {
        String lower = text.toLowerCase();
        Map<String, Integer> assetScores = new HashMap<>();
        
        for (String[] asset : ASSETS) {
            assetScores.put(asset[0], 0);
        }
        
        for (String[] asset : ASSETS) {
            String assetName = asset[0];
            String[] keywords = asset[1].split(",");
            
            for (String kw : keywords) {
                if (lower.contains(kw.trim())) {
                    assetScores.put(assetName, assetScores.get(assetName) + 10);
                }
            }
            
            String[] specificKw = ASSET_SPECIFIC_KEYWORDS.get(assetName);
            if (specificKw != null) {
                for (String kw : specificKw) {
                    if (lower.contains(kw)) {
                        assetScores.put(assetName, assetScores.get(assetName) + 20);
                    }
                }
            }
        }
        
        applyContextualRules(lower, assetScores);
        
        List<String> detected = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : assetScores.entrySet()) {
            if (entry.getValue() >= 15) {
                detected.add(entry.getKey());
            }
        }
        
        if (detected.isEmpty()) {
            detected.add("GOLD");
            detected.add("BTCUSD");
        }
        
        return detected;
    }
    
    private static void applyContextualRules(String text, Map<String, Integer> scores) {
        if ((text.contains("fed") || text.contains("nfp") || text.contains("cpi")) && 
            (text.contains("us") || text.contains("united states"))) {
            scores.put("EURUSD", scores.get("EURUSD") + 15);
            scores.put("GBPUSD", scores.get("GBPUSD") + 15);
            scores.put("USDJPY", scores.get("USDJPY") + 15);
            scores.put("GOLD", scores.get("GOLD") + 20);
            scores.put("BTCUSD", scores.get("BTCUSD") + 15);
        }
        
        if (text.contains("war") || text.contains("crisis") || text.contains("nuclear") ||
            text.contains("attack") || text.contains("conflict")) {
            scores.put("GOLD", scores.get("GOLD") + 30);
            scores.put("USDJPY", scores.get("USDJPY") + 20);
            scores.put("NASDAQ", scores.get("NASDAQ") + 25);
            scores.put("SP500", scores.get("SP500") + 25);
        }
        
        if (text.contains("oil") || text.contains("opec") || text.contains("eia") ||
            text.contains("crude") || text.contains("brent") || text.contains("wti")) {
            scores.put("USDCAD", scores.get("USDCAD") + 25);
        }
        
        if (text.contains("china") || text.contains("pboc") || text.contains("xi jinping")) {
            scores.put("AUDUSD", scores.get("AUDUSD") + 25);
        }
        
        if ((text.contains("apple") || text.contains("microsoft") || text.contains("nvidia") ||
             text.contains("tesla") || text.contains("amazon") || text.contains("meta")) &&
            text.contains("earnings")) {
            scores.put("NASDAQ", scores.get("NASDAQ") + 30);
            scores.put("SP500", scores.get("SP500") + 20);
        }
        
        if (text.contains("brexit") || text.contains("uk eu")) {
            scores.put("GBPUSD", scores.get("GBPUSD") + 25);
        }
        
        if (text.contains("intervention") && (text.contains("yen") || text.contains("boj"))) {
            scores.put("USDJPY", scores.get("USDJPY") + 35);
        }
    }

    private void processMissedEvents() {
        List<EventDatabase.StoredEvent> missed = eventDb.getUnprocessedEvents();
        
        if (missed.isEmpty()) return;
        
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[RECOVERY] " + missed.size() + " événements à traiter");
        
        for (EventDatabase.StoredEvent event : missed) {
            try {
                if (!"Haussier".equals(event.impact) && !"Baissier".equals(event.impact)) {
                    eventDb.markProcessed(event.id, "Ignoré (neutre)");
                    continue;
                }
                
                List<EventDatabase.StoredEvent> related = eventDb.getEventsInTimeWindow(event.timestamp, TIME_WINDOW_MS);
                
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

    // =====================================================
    // ✨ PROMPT ULTRA-PRÉCIS - ANALYSES ACTIONNABLES
    // =====================================================
    
    private static String buildEnhancedPrompt(String text, String assets, 
                                              EconomicEventDetector.DetectedEvent event,
                                              String combinedContext,
                                              boolean isDriverChange) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("Tu es analyste quantitatif senior en trading institutionnel.\n\n");
        
        if (isDriverChange) {
            prompt.append("⚠️ DRIVER MAJEUR - IMPACT IMMÉDIAT SUR LES MARCHÉS\n");
            prompt.append("Ce driver change la direction à court terme (0-48h).\n\n");
        }
        
        if (combinedContext != null && !combinedContext.isEmpty()) {
            prompt.append("CONTEXTE COMBINÉ:\n");
            prompt.append(combinedContext).append("\n");
        }
        
        prompt.append("═══════════════════════════════════════\n");
        prompt.append("ÉVÉNEMENT ").append(event.eventType.toUpperCase()).append("\n");
        prompt.append("═══════════════════════════════════════\n\n");
        
        if (event.country != null) {
            prompt.append("🌍 Pays: ").append(event.country).append("\n");
        }
        if (event.indicator != null) {
            prompt.append("📊 Indicateur: ").append(event.indicator).append("\n");
        }
        
        // ✨ DONNÉES MACRO + CALCUL DE SURPRISE
        boolean hasData = false;
        Double actualVal = null;
        Double forecastVal = null;
        Double diffPct = null;
        
        if (event.forecast != null && !event.forecast.isEmpty()) {
            prompt.append("🎯 Prévision: ").append(event.forecast).append("\n");
            hasData = true;
            try {
                forecastVal = parseNumericValue(event.forecast);
            } catch (Exception e) {}
        }
        if (event.previous != null && !event.previous.isEmpty()) {
            prompt.append("📋 Précédent: ").append(event.previous).append("\n");
            hasData = true;
        }
        if (event.actual != null && !event.actual.isEmpty()) {
            prompt.append("✅ Actuel: ").append(event.actual).append("\n");
            hasData = true;
            
            try {
                actualVal = parseNumericValue(event.actual);
                if (forecastVal != null && forecastVal != 0) {
                    double diff = actualVal - forecastVal;
                    diffPct = (diff / Math.abs(forecastVal)) * 100;
                    
                    String surpriseLevel;
                    if (Math.abs(diffPct) > 1.0) {
                        surpriseLevel = "⚠️ SURPRISE MAJEURE";
                    } else if (Math.abs(diffPct) > 0.5) {
                        surpriseLevel = "⚡ Surprise significative";
                    } else if (Math.abs(diffPct) > 0.2) {
                        surpriseLevel = "📊 Léger écart";
                    } else {
                        surpriseLevel = "✓ Conforme";
                    }
                    
                    prompt.append("📈 Écart vs prévision: ")
                          .append(String.format("%.2f%%", diffPct))
                          .append(" - ").append(surpriseLevel).append("\n");
                }
            } catch (Exception e) {}
        }
        
        if (hasData) {
            prompt.append("\n");
        }
        
        prompt.append("📌 Impact Macro Global: ").append(event.impact).append("\n\n");
        
        // ✨ RÈGLES SPÉCIFIQUES PAR ÉVÉNEMENT
        prompt.append("═══════════════════════════════════════\n");
        prompt.append("RÈGLES D'ANALYSE PAR TYPE D'ÉVÉNEMENT:\n");
        prompt.append("═══════════════════════════════════════\n\n");
        
        // RÈGLES NFP
        if (event.indicator != null && 
            (event.indicator.toLowerCase().contains("nfp") || 
             event.indicator.toLowerCase().contains("payroll"))) {
            prompt.append("📋 NFP (Non-Farm Payrolls):\n");
            prompt.append("Si NFP > Prévision (+surprise):\n");
            prompt.append("  → USD: HAUSSIER (économie forte → Fed hawkish probable)\n");
            prompt.append("  → EURUSD/GBPUSD: BAISSIER (USD fort écrase les paires)\n");
            prompt.append("  → GOLD: BAISSIER (moins d'aversion au risque)\n");
            prompt.append("  → SP500/NASDAQ: MIXTE (croissance + mais crainte Fed hawkish)\n");
            prompt.append("  → USDJPY: HAUSSIER (USD fort)\n\n");
            
            prompt.append("Si NFP < Prévision (-surprise):\n");
            prompt.append("  → USD: BAISSIER (économie faible → Fed moins agressive)\n");
            prompt.append("  → GOLD: HAUSSIER (aversion au risque + Fed dovish)\n");
            prompt.append("  → EURUSD/GBPUSD: HAUSSIER (USD faible)\n");
            prompt.append("  → SP500/NASDAQ: MIXTE (économie faible - mais Fed accommodante +)\n\n");
        }
        
        // RÈGLES CPI
        else if (event.indicator != null && event.indicator.toLowerCase().contains("cpi")) {
            prompt.append("📋 CPI (Inflation):\n");
            prompt.append("Si CPI > Prévision (inflation haute):\n");
            prompt.append("  → GOLD: HAUSSIER (couverture inflation + valeur refuge)\n");
            prompt.append("  → USD: HAUSSIER (Fed sera plus hawkish)\n");
            prompt.append("  → EURUSD/GBPUSD: BAISSIER (USD fort)\n");
            prompt.append("  → NASDAQ/SP500: BAISSIER (hausse taux probable → PE compression)\n");
            prompt.append("  → BTCUSD: BAISSIER court terme (risk-off), HAUSSIER moyen terme (hedge inflation)\n\n");
            
            prompt.append("Si CPI < Prévision (inflation basse):\n");
            prompt.append("  → GOLD: BAISSIER (moins de pression inflation)\n");
            prompt.append("  → USD: BAISSIER (Fed moins agressive)\n");
            prompt.append("  → NASDAQ/SP500: HAUSSIER (valorisations tech reprennent)\n");
            prompt.append("  → EURUSD/GBPUSD: HAUSSIER (USD faible)\n\n");
        }
        
        // RÈGLES FED
        else if (event.indicator != null && 
                 (event.indicator.toLowerCase().contains("fed") || 
                  event.indicator.toLowerCase().contains("fomc"))) {
            prompt.append("📋 FED (Décision de taux):\n");
            prompt.append("Si hausse de taux:\n");
            prompt.append("  → USD: HAUSSIER (taux réels plus élevés)\n");
            prompt.append("  → GOLD: BAISSIER court terme (coût d'opportunité), surveiller inflation\n");
            prompt.append("  → NASDAQ/SP500: BAISSIER (valorisations compressées)\n");
            prompt.append("  → EURUSD/GBPUSD: BAISSIER (différentiel de taux)\n\n");
            
            prompt.append("Si pause ou baisse de taux:\n");
            prompt.append("  → USD: BAISSIER (moins attractif)\n");
            prompt.append("  → GOLD: HAUSSIER (coût d'opportunité diminue)\n");
            prompt.append("  → NASDAQ/SP500: HAUSSIER (liquidité + valorisations)\n");
            prompt.append("  → BTCUSD: HAUSSIER (risk-on + liquidité)\n\n");
        }
        
        // RÈGLES OIL/EIA
        else if (text.toLowerCase().contains("oil") || 
                 text.toLowerCase().contains("eia") ||
                 text.toLowerCase().contains("crude")) {
            prompt.append("📋 PÉTROLE (EIA/Inventaires):\n");
            prompt.append("Si inventaires en baisse (demande forte):\n");
            prompt.append("  → OIL: HAUSSIER (demande > offre)\n");
            prompt.append("  → USDCAD: BAISSIER (CAD fort = pétrole cher)\n");
            prompt.append("  → Inflation: risque haussier (coût énergie)\n\n");
            
            prompt.append("Si inventaires en hausse (demande faible):\n");
            prompt.append("  → OIL: BAISSIER (offre > demande)\n");
            prompt.append("  → USDCAD: HAUSSIER (CAD faible)\n\n");
        }
        
        // RÈGLES RISK-OFF
        else if (text.toLowerCase().contains("war") || 
                 text.toLowerCase().contains("attack") ||
                 text.toLowerCase().contains("crisis") ||
                 text.toLowerCase().contains("nuclear")) {
            prompt.append("📋 ÉVÉNEMENT GÉOPOLITIQUE (Risk-Off):\n");
            prompt.append("  → GOLD: HAUSSIER (valeur refuge principale)\n");
            prompt.append("  → USDJPY: HAUSSIER (JPY = refuge)\n");
            prompt.append("  → NASDAQ/SP500: BAISSIER (fuite des actifs risqués)\n");
            prompt.append("  → BTCUSD: BAISSIER court terme (liquidation risk assets)\n");
            prompt.append("  → USD: HAUSSIER (dollar refuge)\n");
            prompt.append("  → EURUSD/GBPUSD: BAISSIER (USD fort)\n\n");
        }
        
        // RÈGLES GÉNÉRALES
        prompt.append("📋 RÈGLES GÉNÉRALES:\n");
        prompt.append("  • USD fort → EURUSD/GBPUSD/AUDUSD BAISSIERS, USDJPY HAUSSIER\n");
        prompt.append("  • Risk-On → Actions HAUSSIÈRES, GOLD BAISSIER, BTCUSD HAUSSIER\n");
        prompt.append("  • Risk-Off → GOLD/JPY HAUSSIERS, Actions BAISSIÈRES\n");
        prompt.append("  • Inflation haute → GOLD HAUSSIER, Bonds BAISSIERS\n\n");
        
        prompt.append("═══════════════════════════════════════\n");
        prompt.append("CONTENU COMPLET:\n");
        prompt.append("═══════════════════════════════════════\n");
        prompt.append(text).append("\n\n");
        
        prompt.append("═══════════════════════════════════════\n");
        prompt.append("ACTIFS À ANALYSER:\n");
        prompt.append("═══════════════════════════════════════\n");
        prompt.append(assets).append("\n\n");
        
        // ✨ FORMAT DE RÉPONSE STRICT
        prompt.append("═══════════════════════════════════════\n");
        prompt.append("FORMAT DE RÉPONSE OBLIGATOIRE:\n");
        prompt.append("═══════════════════════════════════════\n\n");
        
        prompt.append("Pour CHAQUE actif, structure EXACTE:\n\n");
        prompt.append("[ACTIF]: Direction → SIGNAL\n");
        prompt.append("Raison: [Mécanisme économique précis en 1 phrase]\n");
        prompt.append("Force: [Faible/Modérée/Forte] | Timing: [0-24h/24-48h/2-5j]\n");
        prompt.append("Niveau: [prix technique si pertinent]\n\n");
        
        prompt.append("✅ EXEMPLE PARFAIT (NFP +250K vs prév 180K):\n");
        prompt.append("───────────────────────────────────────\n");
        prompt.append("EURUSD: Baissier → SELL\n");
        prompt.append("Raison: NFP +250K (vs 180K prévu) renforce USD via anticipation Fed hawkish, différentiel de taux EUR/USD s'élargit\n");
        prompt.append("Force: Forte | Timing: 0-24h | Niveau: Watch 1.0850 support\n\n");
        
        prompt.append("GOLD: Baissier → SELL\n");
        prompt.append("Raison: Emploi fort réduit probabilité récession, diminue demande valeur refuge + USD fort pèse mécaniquement sur XAU\n");
        prompt.append("Force: Modérée | Timing: 0-24h | Niveau: Support 2320\n\n");
        
        prompt.append("NASDAQ: Mixte → WAIT\n");
        prompt.append("Raison: Croissance économique positive MAIS risque Fed hawkish compresse PE des techs, attendre confirmation Powell\n");
        prompt.append("Force: Faible | Timing: 24-48h | Niveau: Observer 18500\n\n");
        
        prompt.append("───────────────────────────────────────\n\n");
        
        prompt.append("❌ EXEMPLE À ÉVITER (trop vague):\n");
        prompt.append("GOLD: Haussier → BUY\n");
        prompt.append("Raison: L'événement est positif pour l'or\n");
        prompt.append("[TROP VAGUE - Quel mécanisme? Pourquoi?]\n\n");
        
        prompt.append("═══════════════════════════════════════\n");
        prompt.append("EXIGENCES CRITIQUES:\n");
        prompt.append("═══════════════════════════════════════\n\n");
        
        prompt.append("1. MÉCANISME ÉCONOMIQUE EXPLICITE:\n");
        prompt.append("   ✓ 'Fed hawkish → taux réels hausse → USD fort'\n");
        prompt.append("   ✓ 'Inflation haute → couverture or demandée'\n");
        prompt.append("   ✗ 'C'est positif pour l'actif'\n\n");
        
        prompt.append("2. CHAÎNE DE CAUSALITÉ:\n");
        prompt.append("   Événement → Impact intermédiaire → Conséquence actif\n");
        prompt.append("   Ex: CPI +5% → Fed hawkish probable → USD fort → EURUSD baisse\n\n");
        
        prompt.append("3. DIFFÉRENCIATION PAR ACTIF:\n");
        prompt.append("   Même événement = impacts DIFFÉRENTS par actif\n");
        prompt.append("   Ex: Risk-off → GOLD hausse, NASDAQ baisse\n\n");
        
        prompt.append("4. TIMING PRÉCIS:\n");
        prompt.append("   0-24h = Réaction immédiate au driver\n");
        prompt.append("   24-48h = Digestion + confirmation\n");
        prompt.append("   2-5j = Tendance moyen terme\n\n");
        
        prompt.append("5. COHÉRENCE CROSS-ASSET:\n");
        prompt.append("   Si USD HAUSSIER → EURUSD DOIT être BAISSIER\n");
        prompt.append("   Si Risk-Off → Actions BAISSIÈRES + GOLD HAUSSIER\n\n");
        
        // ✨ RÉSUMÉ FINAL COMPACT
        prompt.append("═══════════════════════════════════════\n");
        prompt.append("RÉSUMÉ FINAL (dernière ligne):\n");
        prompt.append("═══════════════════════════════════════\n");
        prompt.append("Signaux: [ACTIF→SIGNAL | ACTIF→SIGNAL | ...]\n");
        prompt.append("Exemple: GOLD→BUY | EURUSD→SELL | NASDAQ→WAIT | BTCUSD→SELL\n\n");
        
        prompt.append("⚠️ VALIDATION AVANT ENVOI:\n");
        prompt.append("□ Chaque raison contient un MÉCANISME économique\n");
        prompt.append("□ Timing précis (0-24h / 24-48h / 2-5j)\n");
        prompt.append("□ Cohérence cross-asset vérifiée\n");
        prompt.append("□ Pas de phrase générique type 'positif pour l'actif'\n");
        
        return prompt.toString();
    }

    private static String analyzeWithGroq(String text, String assets, 
                                         EconomicEventDetector.DetectedEvent event,
                                         String combinedContext) {
        return analyzeWithGroqEnhanced(text, assets, event, combinedContext, false);
    }
    
    private static String analyzeWithGroqEnhanced(String text, String assets, 
                                                  EconomicEventDetector.DetectedEvent event,
                                                  String combinedContext,
                                                  boolean isDriverChange) {
        try {
            if (MainActivity.CLAUDE_API_KEY == null || MainActivity.CLAUDE_API_KEY.trim().isEmpty()) {
                return "Clé Groq API non configurée";
            }

            String enhancedPrompt = buildEnhancedPrompt(text, assets, event, combinedContext, isDriverChange);

            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", 
                "Tu es un analyste quantitatif senior avec 20 ans d'expérience en trading institutionnel. " +
                "Tu DOIS expliquer les MÉCANISMES économiques précis reliant l'événement à chaque actif. " +
                "INTERDIT: phrases génériques comme 'positif pour l'actif', 'impact haussier'. " +
                "OBLIGATOIRE: chaîne causale explicite (événement → mécanisme → impact actif). " +
                "Exemples: 'CPI +5% → Fed hawkish anticipé → taux réels hausse → USD fort → EURUSD baisse' " +
                "ou 'NFP fort → croissance solide → moins aversion risque → or baisse'. " +
                "Différencie TOUJOURS l'impact par actif selon les corrélations (USD fort ≠ impact identique sur tous). " +
                "Réponds en français, structure stricte imposée, timing précis (0-24h/24-48h/2-5j)."
            );

            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", enhancedPrompt);

            JSONArray messages = new JSONArray();
            messages.put(systemMsg);
            messages.put(userMsg);

            JSONObject body = new JSONObject();
            body.put("model", GROQ_MODEL);
            body.put("messages", messages);
            body.put("max_tokens", 2000); // ✨ Augmenté pour analyses détaillées
            body.put("temperature", 0.15); // ✨ Encore plus bas pour précision maximale

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
        c.setRequestProperty("Authorization", "Bearer " + MainActivity.CLAUDE_API_KEY.trim());
        c.setDoOutput(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);

        OutputStream os = c.getOutputStream();
        os.write(bodyStr.getBytes("UTF-8"));
        os.flush();
        os.close();

        int responseCode = c.getResponseCode();

        InputStream is = (responseCode == 200) ? c.getInputStream() : c.getErrorStream();

        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        c.disconnect();

        if (responseCode == 200) {
            JSONObject resp = new JSONObject(sb.toString());
            return resp.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
        } else {
            if (MainActivity.instance != null)
                MainActivity.instance.addLog("[GROQ] Erreur " + responseCode);
            return "Erreur Groq " + responseCode;
        }
    }
    // =====================================================
    // ✨ TRAITEMENT AVEC INDICATION DE DRIVER
    // =====================================================
    
    private static void processNotificationWithContextEnhanced(
        Context ctx, String eventId, String appName, String text, 
        EconomicEventDetector.DetectedEvent detectedEvent, String assetsStr,
        List<String> assetsList, boolean isDriverChange) {
        
        EventDatabase db = new EventDatabase(ctx);
        List<EventDatabase.StoredEvent> relatedEvents = 
            db.getEventsInTimeWindow(System.currentTimeMillis(), TIME_WINDOW_MS);
        
        boolean hasCombination = relatedEvents.size() > 1;
        StringBuilder contextBuilder = new StringBuilder();
        
        if (hasCombination) {
            contextBuilder.append("CONTEXTE COMBINÉ - ÉVÉNEMENTS LIÉS:\n\n");
            
            for (EventDatabase.StoredEvent event : relatedEvents) {
                contextBuilder.append("[").append(event.impact).append(" - ")
                    .append(event.eventType.toUpperCase()).append("] ")
                    .append(event.title).append("\n");
            }
        }
        
        if (MainActivity.instance != null) {
            String combo = hasCombination ? " COMBINÉE" : "";
            String driver = isDriverChange ? " [DRIVER]" : "";
            MainActivity.instance.addLog("[BOT] Analyse" + combo + driver + " " + assetsStr + "...");
        }

        String analysis = analyzeWithGroqEnhanced(
            text, assetsStr, detectedEvent, 
            hasCombination ? contextBuilder.toString() : null,
            isDriverChange
        );

        String ts = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(new Date());

        StringBuilder tgMsg = new StringBuilder();
        
        String emoji;
        if (isDriverChange) {
            emoji = "⚡🔴";
        } else {
            emoji = detectedEvent.impact.equals("Haussier") ? "📈" : "📉";
        }
        
        tgMsg.append("*").append(emoji).append(" ");
        
        if (isDriverChange) {
            tgMsg.append("NOUVEAU DRIVER MAJEUR");
        } else if (hasCombination) {
            tgMsg.append("ALERTE COMBINÉE");
        } else {
            tgMsg.append("ALERTE TRADING");
        }
        
        tgMsg.append("* - ").append(ts).append("\n");
        tgMsg.append("Source: ").append(appName);
        tgMsg.append(" [").append(detectedEvent.eventType.toUpperCase()).append("]\n\n");
        
        tgMsg.append("*").append(detectedEvent.getDescription()).append("*\n");
        tgMsg.append("Impact Général: ").append(detectedEvent.impact).append("\n");
        
        if (detectedEvent.forecast != null && !detectedEvent.forecast.isEmpty()) {
            tgMsg.append("🎯 Prévision: ").append(detectedEvent.forecast).append("\n");
        }
        if (detectedEvent.previous != null && !detectedEvent.previous.isEmpty()) {
            tgMsg.append("📋 Précédent: ").append(detectedEvent.previous).append("\n");
        }
        if (detectedEvent.actual != null && !detectedEvent.actual.isEmpty()) {
            tgMsg.append("✅ Actuel: ").append(detectedEvent.actual).append("\n");
            
            if (detectedEvent.forecast != null && !detectedEvent.forecast.isEmpty()) {
                try {
                    double actualVal = parseNumericValue(detectedEvent.actual);
                    double forecastVal = parseNumericValue(detectedEvent.forecast);
                    double diff = actualVal - forecastVal;
                    double diffPct = (diff / forecastVal) * 100;
                    
                    String surpriseEmoji = Math.abs(diffPct) > 0.5 ? "⚠️ SURPRISE MAJEURE" : 
                                          Math.abs(diffPct) > 0.3 ? "⚡ Surprise" : "";
                    
                    if (!surpriseEmoji.isEmpty()) {
                        tgMsg.append(surpriseEmoji).append(": ")
                             .append(String.format("%.2f%%", diffPct)).append("\n");
                    }
                } catch (Exception e) {
                    // Ignore
                }
            }
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
    
    private static double parseNumericValue(String value) throws NumberFormatException {
        String cleaned = value.replaceAll("[^0-9.-]", "");
        return Double.parseDouble(cleaned);
    }

    // =====================================================
    // TELEGRAM ET NOTIFICATIONS
    // =====================================================

    public static void sendTelegram(String message) {
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
                MainActivity.instance.addLog("[TG] Erreur (retry " + (attempt+1) + "): " + e.getMessage());
            
            try {
                Thread.sleep(1000 * (long)Math.pow(3, attempt));
            } catch (InterruptedException ie) {}
            
            sendTelegramWithRetry(message, attempt + 1);
        }
    }

    private static void showLocalNotif(Context ctx, String assets, String analysis, String impact) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Trading Alerts", NotificationManager.IMPORTANCE_HIGH));
        
        String emoji = impact.equals("Haussier") ? "📈" : "📉";
        String title = emoji + " Impact " + impact + " - " + assets;
        String summary = analysis.length() > 150 ? analysis.substring(0, 150) + "..." : analysis;
        
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
                DailyReportEntry entry = new DailyReportEntry(timestamp, event.impact, event.eventType, event.getDescription(), summary, signal);
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

    // =====================================================
    // DÉTECTION DRIVERS ET GÉNÉRATION RAPPORTS
    // =====================================================
    
    private static boolean isNewMajorDriver(EconomicEventDetector.DetectedEvent event, String text) {
        String lower = text.toLowerCase();
        
        boolean isMajorEvent = 
            (lower.contains("fed") && (lower.contains("rate") || lower.contains("powell") || lower.contains("fomc"))) ||
            (lower.contains("boe") && (lower.contains("rate") || lower.contains("bailey"))) ||
            (lower.contains("boj") && (lower.contains("intervention") || lower.contains("ueda"))) ||
            (lower.contains("ecb") && (lower.contains("rate") || lower.contains("lagarde"))) ||
            (lower.contains("rba") && lower.contains("rate")) ||
            (lower.contains("boc") && lower.contains("rate")) ||
            lower.contains("nfp") || lower.contains("non-farm") ||
            (lower.contains("cpi") && event.actual != null) ||
            (lower.contains("gdp") && event.actual != null) ||
            (lower.contains("pmi") && event.actual != null) ||
            lower.contains("fomc minutes") || lower.contains("beige book") ||
            (lower.contains("opec") && (lower.contains("cut") || lower.contains("increase"))) ||
            (lower.contains("eia") && event.actual != null) ||
            (lower.contains("api") && lower.contains("inventory")) ||
            ((lower.contains("war") || lower.contains("attack") || lower.contains("nuclear") || 
              lower.contains("strike") || lower.contains("invasion")) &&
             (lower.contains("breaking") || lower.contains("urgent"))) ||
            ((lower.contains("apple") || lower.contains("microsoft") || lower.contains("nvidia") ||
              lower.contains("tesla") || lower.contains("amazon") || lower.contains("meta") ||
              lower.contains("alphabet") || lower.contains("google") || lower.contains("netflix")) && 
             lower.contains("earnings"));
        
        if (!isMajorEvent) return false;
        
        String driverSignature = createDriverSignature(event, text);
        Long lastSeen = knownDrivers.get(driverSignature);
        long now = System.currentTimeMillis();
        
        if (lastSeen != null && (now - lastSeen < 8 * 60 * 60 * 1000)) {
            return false;
        }
        
        knownDrivers.put(driverSignature, now);
        cleanOldDrivers();
        
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[DRIVER] ⚡ NOUVEAU: " + driverSignature.substring(0, Math.min(50, driverSignature.length())));
        
        return true;
    }
    
    private static String createDriverSignature(EconomicEventDetector.DetectedEvent event, String text) {
        String lower = text.toLowerCase();
        StringBuilder sig = new StringBuilder();
        
        if (event.indicator != null) sig.append(event.indicator).append("_");
        if (event.country != null) sig.append(event.country).append("_");
        
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
    // =====================================================
    // ✨ GÉNÉRATION RAPPORT AVEC DONNÉES RÉELLES DE LA DB
    // =====================================================
        private void generateScheduledReport(int hour, int minute) {
        // ✅ Calculer le début de la journée
        Calendar todayStart = Calendar.getInstance();
        todayStart.set(Calendar.HOUR_OF_DAY, 0);
        todayStart.set(Calendar.MINUTE, 0);
        todayStart.set(Calendar.SECOND, 0);
        todayStart.set(Calendar.MILLISECOND, 0);
        long todayStartMs = todayStart.getTimeInMillis();
        
        // =============================================
        // CALCUL DES TOTAUX RÉELS
        // =============================================
        int totalEvents = 0;
        int totalHaussier = 0;
        int totalBaissier = 0;
        
        List<EventDatabase.StoredEvent> allTodayEvents = 
            eventDb.getEventsInTimeWindow(todayStartMs, System.currentTimeMillis() - todayStartMs);

        for (EventDatabase.StoredEvent event : allTodayEvents) {
            totalEvents++;
            if ("Haussier".equals(event.impact)) totalHaussier++;
            if ("Baissier".equals(event.impact)) totalBaissier++;
        }

        // =============================================
        // ✅ IDENTIFICATION DU DRIVER PRINCIPAL
        // =============================================
        EventDatabase.StoredEvent mainDriver = identifyMainMarketDriver(todayStartMs);

        // =============================================
        // CONSTRUCTION DU RAPPORT
        // =============================================
        StringBuilder report = new StringBuilder();
        
        String reportTitle = getReportTitle(hour, minute);
        report.append("📊 *").append(reportTitle).append("*\n");
        report.append(new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(new Date()));
        report.append("\n\n");

        report.append("*Vue d'ensemble: ").append(totalEvents).append(" événements*\n");
        report.append("📈 Haussiers: ").append(totalHaussier).append(" | ");
        report.append("📉 Baissiers: ").append(totalBaissier).append("\n\n");

        // ==================== DRIVER PRINCIPAL ====================
        if (mainDriver != null) {
            report.append("*🎯 DRIVER PRINCIPAL DU JOUR:*\n");
            report.append("```\n");
            report.append(mainDriver.title).append("\n");
            report.append("Type: ").append(mainDriver.eventType).append("\n");
            report.append("Impact: ").append(mainDriver.impact).append("\n");
            report.append("Actifs: ").append(mainDriver.assets).append("\n");
            report.append("Confiance: ").append(mainDriver.confidence).append("%\n");
            report.append("```\n\n");
        } else {
            report.append("*🎯 Aucun driver majeur détecté aujourd'hui*\n\n");
        }

        // =============================================
        // ANALYSE PAR ACTIF
        // =============================================
        report.append("*📌 ANALYSE PAR ACTIF:*\n\n");

        // Trier par nombre d'événements (les plus actifs en premier)
        Map<String, Integer> realCountsByAsset = new HashMap<>();
        Map<String, String> dominantSignalByAsset = new HashMap<>();
        Map<String, List<String>> recentEventsByAsset = new HashMap<>();

        for (String[] assetInfo : ASSETS) {
            String assetName = assetInfo[0];
            
            int count = eventDb.getEventCountByAsset(assetName, todayStartMs);
            realCountsByAsset.put(assetName, count);
            
            List<EventDatabase.StoredEvent> assetEvents = 
                eventDb.getEventsByAsset(assetName, todayStartMs);
            
            String dominantSignal = calculateDominantSignalWeighted(assetEvents);
            
            // Derniers événements
            List<String> recentDescs = new ArrayList<>();
            for (EventDatabase.StoredEvent event : assetEvents) {
                if (recentDescs.size() < 3) {
                    String desc = event.title.substring(0, Math.min(40, event.title.length()));
                    String time = new SimpleDateFormat("HH:mm", Locale.getDefault())
                        .format(new Date(event.timestamp));
                    recentDescs.add(time + " - " + desc);
                }
            }
            
            dominantSignalByAsset.put(assetName, dominantSignal);
            recentEventsByAsset.put(assetName, recentDescs);
        }

        // Affichage des actifs triés
        List<Map.Entry<String, Integer>> sortedAssets = 
            new ArrayList<>(realCountsByAsset.entrySet());
        sortedAssets.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        for (Map.Entry<String, Integer> entry : sortedAssets) {
            String assetName = entry.getKey();
            int count = entry.getValue();
            
            if (count == 0) continue;
            
            String emoji = getAssetEmoji(assetName);
            String dominantSignal = dominantSignalByAsset.get(assetName);
            
            report.append(emoji).append(" *").append(assetName).append("* - ");
            report.append(count).append(" evt\n");
            report.append("   Signal: ").append(dominantSignal).append("\n");
            
            if (count < 2) {
                report.append("   ⚠️ COUVERTURE FAIBLE\n");
            }
            
            List<String> recentEvents = recentEventsByAsset.get(assetName);
            if (recentEvents != null && !recentEvents.isEmpty()) {
                for (String eventDesc : recentEvents) {
                    report.append("   • ").append(eventDesc).append("\n");
                }
            }
            report.append("\n");
        }
        
        report.append("_Prochain rapport: ").append(getNextReportTime(hour, minute)).append("_");

        sendTelegram(report.toString());

        // Nettoyage en fin de journée
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
            String today = new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date());
            
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
        String fullPrompt = basePrompt.replace("[ICI SERA INJECTÉ LE CONTEXTE DES ÉVÉNEMENTS DU JOUR]", context.toString());
        
        try {
            if (MainActivity.CLAUDE_API_KEY == null || MainActivity.CLAUDE_API_KEY.trim().isEmpty()) {
                if (MainActivity.instance != null)
                    MainActivity.instance.addLog("[SUMMARY] Clé API manquante");
                return;
            }

            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", 
                "Tu es analyste de marché professionnel avec 15 ans d'expérience. " +
                "Tu génères des résumés FACTUELS basés uniquement sur les événements fournis. " +
                "INTERDICTIONS ABSOLUES: " +
                "- Inventer des événements ou données " +
                "- Utiliser des phrases vagues ('en variation', 'suite aux annonces') " +
                "- Mentionner des sources sans détails précis " +
                "OBLIGATIONS: " +
                "- Mécanismes économiques explicites " +
                "- Chiffres et pourcentages réels " +
                "- Honnêteté si peu d'événements captés " +
                "Réponds en français, max 340 mots, structure stricte imposée."
            );

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
                new SimpleDateFormat("EEEE dd MMMM yyyy", Locale.FRENCH).format(new Date()) + "\n\n" +
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
        // ✅ Obtenir les VRAIS événements du jour depuis la DB
        Calendar todayStart = Calendar.getInstance();
        todayStart.set(Calendar.HOUR_OF_DAY, 0);
        todayStart.set(Calendar.MINUTE, 0);
        todayStart.set(Calendar.SECOND, 0);
        long todayMs = todayStart.getTimeInMillis();
        
        EventDatabase db = new EventDatabase(MainActivity.instance);
        List<EventDatabase.StoredEvent> todayEvents = 
            db.getEventsInTimeWindow(todayMs, System.currentTimeMillis() - todayMs);
        
        // ✅ Construire le contexte RÉEL
        StringBuilder realContext = new StringBuilder();
        if (todayEvents.isEmpty()) {
            realContext.append("Aucun événement majeur capté aujourd'hui.\n");
        } else {
            // ✅ FILTRER les événements valides seulement
            List<EventDatabase.StoredEvent> validEvents = new ArrayList<>();
            for (EventDatabase.StoredEvent event : todayEvents) {
                if (isValidEconomicDriver(event)) {
                    validEvents.add(event);
                }
            }
            
            if (validEvents.isEmpty()) {
                realContext.append("Événements captés mais aucun driver majeur confirmé.\n");
            } else {
                realContext.append("ÉVÉNEMENTS MACRO CONFIRMÉS (" + validEvents.size() + " au total):\n\n");
                
                int count = 0;
                for (EventDatabase.StoredEvent event : validEvents) {
                    if (count >= 10) break;
                }
            
                
                String time = new SimpleDateFormat("HH:mm", Locale.FRENCH)
                    .format(new Date(event.timestamp));
                
                realContext.append(count + 1).append(". [").append(time).append("] ")
                          .append(event.title)
                          .append(" (").append(event.impact).append(")\n");
                realContext.append("   Source: ").append(event.appName)
                          .append(" | Actifs: ").append(event.assets).append("\n");
                
                if (event.analysis != null && !event.analysis.isEmpty()) {
                    String shortAnalysis = event.analysis.length() > 100 ? 
                        event.analysis.substring(0, 100) + "..." : event.analysis;
                    realContext.append("   → ").append(shortAnalysis).append("\n");
                }
                
                realContext.append("\n");
                count++;
            }
        }
        
        return 
            "Tu es analyste de marché senior. Génère un résumé marché professionnel en français (MAX 340 mots).\n\n" +
            "**ACTIFS À ANALYSER:** Gold (XAUUSD), S&P500, Nasdaq, EURUSD, GBPUSD, USDJPY, AUDUSD, USDCAD, BTCUSD, Pétrole.\n\n" +
            "**DATE ACTUELLE:** " + 
            new SimpleDateFormat("EEEE dd MMMM yyyy 'à' HH:mm 'EAT (UTC+3)'", Locale.FRENCH).format(new Date()) + "\n\n" +
            "**STRUCTURE OBLIGATOIRE:**\n\n" +
            "**1. TOP 3 DRIVERS DU JOUR** (avec heure ET détails précis)\n" +
            "   Format: HH:MM - [Événement précis] → Impact: [Mécanisme économique]\n" +
            "   Exemple: 14:30 - NFP US +250K (vs 180K prévu) → USD fort via anticipation Fed hawkish\n\n" +
            "**2. ANALYSE PAR ACTIF** (1 ligne MAX par actif, MÉCANISME obligatoire)\n" +
            "   Format: [ACTIF]: Direction (±X%) → Raison économique\n" +
            "   Exemple: GOLD: Baisse (-0.8%) → USD fort + yields hausse réduisent attrait refuge\n\n" +
            "**3. THÈME DOMINANT** (1 phrase + sentiment général)\n" +
            "   Exemple: Risk-on dominant via données US solides, rotation actions > refuges\n\n" +
            "**4. ÉVÉNEMENTS MAJEURS À VENIR** (concrets avec date/heure)\n" +
            "   Exemple: Demain 8:30 ET - CPI US (prévu 3.2%), Lundi - Décision BOE\n\n" +
            "**RÈGLES CRITIQUES:**\n" +
            "- INTERDIT: phrases vagues ('suite aux annonces', 'en variation')\n" +
            "- OBLIGATOIRE: mécanismes économiques précis\n" +
            "- OBLIGATOIRE: chiffres/pourcentages quand disponibles\n" +
            "- Ne JAMAIS inventer de données si absentes du contexte\n" +
            "- Si peu d'événements: le dire honnêtement\n\n" +
            "**CONTEXTE RÉEL DU JOUR:**\n" +
            realContext.toString() + "\n\n" +
            "Génère le résumé maintenant. Sois FACTUEL, PRÉCIS, et base-toi UNIQUEMENT sur les événements ci-dessus.";
    }
    // =====================================================
    // MÉTHODES UTILITAIRES
    // =====================================================
    
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
                    if (lower.contains(kw)) return true;
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

    private static void processCombinedEvents(Context ctx, List<EventDatabase.StoredEvent> events) {
        if (events.isEmpty()) return;
        EventDatabase.StoredEvent first = events.get(0);
        processNotification(ctx, first.appName, first.content);
    }

    public static void processNotification(Context ctx, String appName, String text) {
        List<String> assets = detectAssetsWithScoring(text);
        String assetsStr = String.join(", ", assets);
        EconomicEventDetector.DetectedEvent event = EconomicEventDetector.detectEvent("", text);
        
        if (!event.shouldNotify()) return;

        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[BOT] Analyse " + assetsStr + "...");

        String analysis = analyzeWithGroq(text, assetsStr, event, null);
        String ts = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(new Date());
        String emoji = event.impact.equals("Haussier") ? "📈" : "📉";
        String tgMsg = "*" + emoji + " ALERTE TRADING* - " + ts + "\n" +
            "Source: " + appName + "\n\nImpact Général: " + event.impact + "\n\n" +
            "NEWS:\n" + text.substring(0, Math.min(300, text.length())) + "\n\n" +
            "*ANALYSE PAR ACTIF:*\n" + analysis;

        sendTelegram(tgMsg);
        showLocalNotif(ctx, assetsStr, analysis, event.impact);
        saveToDailyReport(event, text, analysis, assets);

        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[OK] Envoyé - " + assetsStr);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, 
                "Trading Alerts", 
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Alertes de trading importantes");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        exec.shutdown();
        scheduler.shutdown();
        if (eventDb != null) eventDb.close();
        if (MainActivity.instance != null)
            MainActivity.instance.addLog("[SERVICE] NotificationService arrêté");
    }
}
    
