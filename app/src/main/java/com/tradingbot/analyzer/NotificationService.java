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
        
        {
