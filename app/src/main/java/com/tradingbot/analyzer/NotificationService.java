package com.tradingbot.analyzer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;

public class NotificationService extends NotificationListenerService {

    private static final String TAG = "NotificationService";
    private static final String CHANNEL_ID = "trading_alerts";
    
    private EventDatabase eventDb;
    private EconomicEventDetector eventDetector;
    
    // Apps financières à surveiller
    private static final Set<String> FINANCE_APPS = new HashSet<>(Arrays.asList(
        "com.investing.app",
        "com.mql5.mobile",
        "net.metaquotes.metatrader5",
        "net.metaquotes.metatrader4",
        "com.tradingview.tradingviewapp",
        "org.telegram.messenger",
        "com.whatsapp",
        "com.fxcm.tradingstation.mobile",
        "com.ig.android",
        "com.etoro.openbook",
        "com.plus500",
        "com.avatrade",
        "com.forextime",
        "com.oanda.mobile.mobiletraderfx",
        "com.fxpro.direct",
        "com.xm.trading"
    ));
    
    // NOS ACTIFS SUPPORTÉS
    private static final List<String> SUPPORTED_ASSETS = Arrays.asList(
        "SP500", "S&P500", "S&P 500", "SPX",
        "NASDAQ", "NDX", "NQ",
        "EURUSD", "EUR/USD", "EUR USD",
        "GBPUSD", "GBP/USD", "GBP USD", "CABLE",
        "USDJPY", "USD/JPY", "USD JPY",
        "AUDUSD", "AUD/USD", "AUD USD", "AUSSIE",
        "GOLD", "XAU", "XAUUSD", "XAU/USD", "OR",
        "OIL", "CRUDE", "WTI", "BRENT", "CL", "PETROL", "PÉTROLE",
        "BITCOIN", "BTC", "BTCUSD", "BTC/USD"
    );
    
    // Mots-clés de détection d'événements macro
    private static final Map<String, String> EVENT_KEYWORDS = new HashMap<String, String>() {{
        put("nfp", "HIGH");
        put("non-farm", "HIGH");
        put("payroll", "HIGH");
        put("employment", "MEDIUM");
        put("unemployment", "MEDIUM");
        put("jobless", "MEDIUM");
        put("cpi", "HIGH");
        put("inflation", "HIGH");
        put("pce", "HIGH");
        put("ppi", "MEDIUM");
        put("fed", "HIGH");
        put("fomc", "HIGH");
        put("ecb", "HIGH");
        put("boe", "HIGH");
        put("boj", "HIGH");
        put("rba", "MEDIUM");
        put("boc", "MEDIUM");
        put("rate decision", "HIGH");
        put("interest rate", "HIGH");
        put("gdp", "HIGH");
        put("retail sales", "MEDIUM");
        put("pmi", "MEDIUM");
        put("ism", "MEDIUM");
        put("eia", "MEDIUM");
        put("opec", "MEDIUM");
        put("crude oil", "MEDIUM");
        put("oil inventory", "MEDIUM");
    }};
    
    // Pays → Actif principal
    private static final Map<String, String> COUNTRY_TO_PRIMARY_ASSET = new HashMap<String, String>() {{
        put("us", "SP500");
        put("usa", "SP500");
        put("united states", "SP500");
        put("u.s", "SP500");
        put("america", "SP500");
        put("american", "SP500");
        put("euro", "EURUSD");
        put("eurozone", "EURUSD");
        put("germany", "EURUSD");
        put("german", "EURUSD");
        put("france", "EURUSD");
        put("french", "EURUSD");
        put("italy", "EURUSD");
        put("italian", "EURUSD");
        put("spain", "EURUSD");
        put("spanish", "EURUSD");
        put("uk", "GBPUSD");
        put("britain", "GBPUSD");
        put("british", "GBPUSD");
        put("england", "GBPUSD");
        put("united kingdom", "GBPUSD");
        put("japan", "USDJPY");
        put("japanese", "USDJPY");
        put("australia", "AUDUSD");
        put("australian", "AUDUSD");
        put("aussie", "AUDUSD");
        put("canada", "OIL");
        put("china", "GOLD");
        put("chinese", "GOLD");
    }};
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        // Initialiser DB et Detector
        eventDb = new EventDatabase(this);
        eventDetector = new EconomicEventDetector(eventDb);
        
        createNotificationChannel();
        
        if (MainActivity.instance != null) {
            MainActivity.instance.addLog("[SERVICE] NotificationService démarré avec détecteur");
        }
    }
    
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            String packageName = sbn.getPackageName();
            
            // Filtrer apps financières
            if (!FINANCE_APPS.contains(packageName)) {
                return;
            }
            
            Notification notification = sbn.getNotification();
            if (notification == null) {
                return;
            }
            
            String title = "";
            String text = "";
            
            try {
                if (notification.extras != null) {
                    CharSequence titleSeq = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
                    CharSequence textSeq = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
                    
                    if (titleSeq != null) title = titleSeq.toString();
                    if (textSeq != null) text = textSeq.toString();
                }
            } catch (Exception e) {
                Log.e(TAG, "Erreur extraction notification", e);
                return;
            }
            
            if (title.isEmpty() && text.isEmpty()) {
                return;
            }
            
            // Analyser la notification
            analyzeNotification(packageName, title, text);
            
        } catch (Exception e) {
            Log.e(TAG, "Erreur onNotificationPosted", e);
        }
    }
    
    // =====================================================
    // ANALYSE DE NOTIFICATION (INTÉGRÉE AVEC DÉTECTEUR)
    // =====================================================
    
    private void analyzeNotification(String packageName, String title, String content) {
        try {
            String fullContent = (title + " " + content).toLowerCase();
            
            // 1. DÉTECTER LE PAYS
            String country = detectCountry(fullContent);
            
            // 2. DÉTECTER LES ACTIFS (priorisés)
            List<String> detectedAssets = detectAssetsPrioritized(fullContent, country);
            
            if (detectedAssets.isEmpty()) {
                return; // Pas d'actif pertinent
            }
            
            // 3. DÉTECTER L'IMPORTANCE
            String importance = detectImportance(fullContent);
            
            // 4. DÉTECTER LE TYPE D'ÉVÉNEMENT
            String eventType = detectEventType(fullContent);
            
            // 5. CALCULER LA CONFIANCE
            int confidence = calculateConfidence(
                detectedAssets.size(),
                importance,
                country,
                eventType
            );
            
            // Filtrer confiance trop faible
            if (confidence < 40) {
                return;
            }
            
            // 6. SAUVEGARDER EN DB
            String eventId = generateEventId(packageName, title, content);
            
            if (eventDb.eventExists(eventId)) {
                return; // Déjà traité
            }
            
            String appName = getAppName(packageName);
            String assetsStr = String.join(", ", detectedAssets);
            
            boolean saved = eventDb.saveEvent(
                eventId,
                packageName,
                appName,
                eventType,
                title,
                content,
                assetsStr,
                importance,
                confidence,
                "notification"
            );
            
            if (saved && MainActivity.instance != null) {
                MainActivity.instance.addLog(
                    "[NOTIF] " + country + " → " + detectedAssets.get(0) + 
                    " (" + importance + ", " + confidence + "%) | " + title
                );
            }
            
            // 7. ✨ NOUVELLE ÉTAPE : CORRÉLATION AVEC CALENDRIER ✨
            correlateWithCalendar(eventId, country, detectedAssets, importance, confidence);
            
            // 8. ENVOYER ALERTE SI HIGH
            if ("HIGH".equals(importance) && confidence >= 70) {
                sendTelegramAlert(
                    country,
                    detectedAssets,
                    title,
                    content,
                    importance,
                    confidence
                );
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Erreur analyzeNotification", e);
        }
    }
    
    // =====================================================
    // ✨ NOUVELLE MÉTHODE : CORRÉLATION AVEC CALENDRIER ✨
    // =====================================================
    
    private void correlateWithCalendar(String notificationEventId, String country, 
                                       List<String> assets, String importance, 
                                       int confidence) {
        try {
            // Récupérer événements du calendrier dans les 30 prochaines minutes
            List<EconomicCalendarAPI.CalendarEvent> upcomingEvents = 
                EconomicCalendarAPI.fetchUpcomingEvents(1); // 1 heure à venir
            
            if (upcomingEvents.isEmpty()) {
                return;
            }
            
            long now = System.currentTimeMillis();
            long correlationWindow = 30 * 60 * 1000; // 30 minutes
            
            boolean matchFound = false;
            EconomicCalendarAPI.CalendarEvent matchedEvent = null;
            
            // Chercher correspondance
            for (EconomicCalendarAPI.CalendarEvent calEvent : upcomingEvents) {
                long eventTime = Long.parseLong(calEvent.timestamp) * 1000;
                long timeDiff = Math.abs(eventTime - now);
                
                // Vérifier si dans fenêtre de temps
                if (timeDiff > correlationWindow) {
                    continue;
                }
                
                // Vérifier correspondance pays
                boolean countryMatch = calEvent.country.toLowerCase().contains(country.toLowerCase()) ||
                                      country.toLowerCase().contains(calEvent.country.toLowerCase());
                
                if (!countryMatch && !country.equals("Unknown")) {
                    continue;
                }
                
                // Vérifier correspondance actifs
                for (String asset : assets) {
                    if (calEvent.affectedAssets.contains(asset)) {
                        matchFound = true;
                        matchedEvent = calEvent;
                        break;
                    }
                }
                
                if (matchFound) break;
            }
            
            // Si match trouvé
            if (matchFound && matchedEvent != null) {
                // Augmenter la confiance
                int newConfidence = Math.min(100, confidence + 20);
                eventDb.updateConfidence(notificationEventId, newConfidence);
                
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog(
                        "[CORRELATION] ✓ Notification ↔ Calendrier: " + 
                        matchedEvent.indicator + " | Confiance: " + 
                        confidence + "% → " + newConfidence + "%"
                    );
                }
                
                // Envoyer alerte de corrélation
                sendCorrelationAlert(notificationEventId, matchedEvent, assets, newConfidence);
                
                // Informer le détecteur
                if (eventDetector != null) {
                    // Le détecteur peut faire des analyses supplémentaires
                    eventDetector.checkRecentEvents();
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Erreur correlateWithCalendar", e);
        }
    }
    
    // =====================================================
    // ALERTE DE CORRÉLATION
    // =====================================================
    
    private void sendCorrelationAlert(String notifId, 
                                      EconomicCalendarAPI.CalendarEvent calEvent,
                                      List<String> assets, int confidence) {
        try {
            long eventTime = Long.parseLong(calEvent.timestamp) * 1000;
            long now = System.currentTimeMillis();
            long minutesUntil = (eventTime - now) / (60 * 1000);
            
            StringBuilder message = new StringBuilder();
            message.append("🔗 **CORRÉLATION CONFIRMÉE**\n\n");
            message.append("Une notification a été corrélée avec le calendrier économique!\n\n");
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
            
            sendTelegramAlert(
                calEvent.country,
                assets,
                "🔗 Corrélation: " + calEvent.indicator,
                message.toString(),
                "HIGH",
                confidence
            );
            
        } catch (Exception e) {
            Log.e(TAG, "Erreur sendCorrelationAlert", e);
        }
    }
    
    // =====================================================
    // DÉTECTION INTELLIGENTE DU PAYS
    // =====================================================
    
    private String detectCountry(String contentLower) {
        for (Map.Entry<String, String> entry : COUNTRY_TO_PRIMARY_ASSET.entrySet()) {
            String countryKeyword = entry.getKey();
            
            if (contentLower.contains(countryKeyword)) {
                if (countryKeyword.contains("us") || countryKeyword.contains("america")) {
                    return "United States";
                } else if (countryKeyword.contains("euro") || countryKeyword.contains("german")) {
                    return "Eurozone";
                } else if (countryKeyword.contains("uk") || countryKeyword.contains("britain")) {
                    return "United Kingdom";
                } else if (countryKeyword.contains("japan")) {
                    return "Japan";
                } else if (countryKeyword.contains("australia")) {
                    return "Australia";
                } else if (countryKeyword.contains("canada")) {
                    return "Canada";
                } else if (countryKeyword.contains("china")) {
                    return "China";
                }
            }
        }
        
        return "Unknown";
    }
    
    // =====================================================
    // DÉTECTION PRIORISÉE DES ACTIFS
    // =====================================================
    
    private List<String> detectAssetsPrioritized(String contentLower, String country) {
        List<String> assets = new ArrayList<>();
        Set<String> uniqueAssets = new HashSet<>();
        
        // 1. PRIORISER L'ACTIF PRINCIPAL DU PAYS
        String primaryAsset = getPrimaryAssetForCountry(country, contentLower);
        if (primaryAsset != null) {
            assets.add(primaryAsset);
            uniqueAssets.add(normalizeAsset(primaryAsset));
        }
        
        // 2. DÉTECTER TOUS LES ACTIFS MENTIONNÉS
        for (String assetKeyword : SUPPORTED_ASSETS) {
            if (contentLower.contains(assetKeyword.toLowerCase())) {
                String normalized = normalizeAsset(assetKeyword);
                
                if (!uniqueAssets.contains(normalized)) {
                    assets.add(normalized);
                    uniqueAssets.add(normalized);
                }
            }
        }
        
        // 3. COMPLÉTER SELON L'INDICATEUR
        String[] oilKeywords = {"oil", "crude", "eia", "opec", "petroleum", "petrol", "pétrole"};
        for (String keyword : oilKeywords) {
            if (contentLower.contains(keyword) && !uniqueAssets.contains("OIL")) {
                assets.add(0, "OIL");
                uniqueAssets.add("OIL");
                break;
            }
        }
        
        // 4. AJOUT GOLD POUR ÉVÉNEMENTS MACRO MAJEURS
        String[] majorEvents = {"nfp", "fomc", "fed", "cpi", "gdp", "ecb", "boe"};
        for (String event : majorEvents) {
            if (contentLower.contains(event) && !uniqueAssets.contains("GOLD")) {
                assets.add("GOLD");
                uniqueAssets.add("GOLD");
                break;
            }
        }
        
        return assets;
    }
    
    private String getPrimaryAssetForCountry(String country, String contentLower) {
        String countryLower = country.toLowerCase();
        
        if (countryLower.contains("united states") || countryLower.contains("us")) {
            if (contentLower.contains("eia") || contentLower.contains("crude") || 
                contentLower.contains("oil inventory")) {
                return "OIL";
            }
            return "SP500";
        }
        
        if (countryLower.contains("euro") || countryLower.contains("germany") || 
            countryLower.contains("france")) {
            return "EURUSD";
        }
        
        if (countryLower.contains("kingdom") || countryLower.contains("britain")) {
            return "GBPUSD";
        }
        
        if (countryLower.contains("japan")) {
            return "USDJPY";
        }
        
        if (countryLower.contains("australia")) {
            return "AUDUSD";
        }
        
        if (countryLower.contains("canada")) {
            return "OIL";
        }
        
        if (countryLower.contains("china")) {
            return "GOLD";
        }
        
        return "GOLD";
    }
    
    private String normalizeAsset(String asset) {
        String upper = asset.toUpperCase();
        
        if (upper.contains("S&P") || upper.equals("SPX")) return "SP500";
        if (upper.contains("NASDAQ") || upper.equals("NDX") || upper.equals("NQ")) return "NASDAQ";
        if (upper.contains("EUR") && upper.contains("USD")) return "EURUSD";
        if (upper.contains("GBP") && upper.contains("USD")) return "GBPUSD";
        if (upper.contains("USD") && upper.contains("JPY")) return "USDJPY";
        if (upper.contains("AUD") && upper.contains("USD")) return "AUDUSD";
        if (upper.contains("GOLD") || upper.contains("XAU") || upper.equals("OR")) return "GOLD";
        if (upper.contains("OIL") || upper.contains("CRUDE") || upper.contains("WTI") || 
            upper.contains("BRENT") || upper.contains("PETROL")) return "OIL";
        if (upper.contains("BTC") || upper.contains("BITCOIN")) return "BTCUSD";
        
        return upper;
    }
    
    // =====================================================
    // DÉTECTION DE L'IMPORTANCE
    // =====================================================
    
    private String detectImportance(String contentLower) {
        int highScore = 0;
        int mediumScore = 0;
        
        for (Map.Entry<String, String> entry : EVENT_KEYWORDS.entrySet()) {
            String keyword = entry.getKey();
            String importance = entry.getValue();
            
            if (contentLower.contains(keyword)) {
                if ("HIGH".equals(importance)) {
                    highScore++;
                } else if ("MEDIUM".equals(importance)) {
                    mediumScore++;
                }
            }
        }
        
        String[] urgentKeywords = {
            "breaking", "alert", "urgent", "flash", "now", "live",
            "just in", "developing", "emergency"
        };
        
        for (String keyword : urgentKeywords) {
            if (contentLower.contains(keyword)) {
                highScore += 2;
            }
        }
        
        if (highScore >= 1) {
            return "HIGH";
        } else if (mediumScore >= 1 || highScore > 0) {
            return "MEDIUM";
        } else {
            return "LOW";
        }
    }
    
    // =====================================================
    // DÉTECTION DU TYPE D'ÉVÉNEMENT
    // =====================================================
    
    private String detectEventType(String contentLower) {
        if (contentLower.contains("nfp") || contentLower.contains("payroll") || 
            contentLower.contains("employment")) {
            return "EMPLOYMENT";
        }
        
        if (contentLower.contains("cpi") || contentLower.contains("inflation") || 
            contentLower.contains("pce")) {
            return "INFLATION";
        }
        
        if (contentLower.contains("fed") || contentLower.contains("fomc") || 
            contentLower.contains("ecb") || contentLower.contains("boe") || 
            contentLower.contains("rate decision")) {
            return "CENTRAL_BANK";
        }
        
        if (contentLower.contains("gdp") || contentLower.contains("pmi") || 
            contentLower.contains("retail")) {
            return "GROWTH";
        }
        
        if (contentLower.contains("oil") || contentLower.contains("eia") || 
            contentLower.contains("crude")) {
            return "COMMODITY";
        }
        
        return "ECONOMIC";
    }
    
    // =====================================================
    // CALCUL DE LA CONFIANCE
    // =====================================================
    
    private int calculateConfidence(int assetCount, String importance, 
                                     String country, String eventType) {
        int confidence = 50;
        
        if (assetCount >= 1) confidence += 10;
        if (assetCount >= 2) confidence += 5;
        
        if ("HIGH".equals(importance)) {
            confidence += 20;
        } else if ("MEDIUM".equals(importance)) {
            confidence += 10;
        }
        
        if (!country.equals("Unknown")) {
            confidence += 10;
        }
        
        if (!eventType.equals("ECONOMIC")) {
            confidence += 5;
        }
        
        return Math.min(100, confidence);
    }
    
    // =====================================================
    // ENVOI ALERTE TELEGRAM
    // =====================================================
    
    public static void sendTelegramAlert(String country, List<String> assets, 
                                          String title, String content, 
                                          String importance, int confidence) {
        new Thread(() -> {
            try {
                String botToken = "7922022330:AAFlkd8Hy4BSCYC6vjy4z_DmEbA8J2RWySs";
                String chatId = "1166473965";
                
                String icon = "HIGH".equals(importance) ? "🔴" : 
                             "MEDIUM".equals(importance) ? "🟡" : "🟢";
                
                StringBuilder message = new StringBuilder();
                message.append(icon).append(" **ALERTE TRADING**\n\n");
                message.append("**Pays:** ").append(country).append("\n");
                message.append("**Actifs:** ").append(String.join(", ", assets)).append("\n");
                message.append("**Importance:** ").append(importance).append("\n");
                message.append("**Confiance:** ").append(confidence).append("%\n\n");
                message.append("**Événement:**\n").append(title).append("\n\n");
                
                if (!content.equals(title)) {
                    message.append("**Détails:**\n").append(content.substring(0, 
                        Math.min(200, content.length()))).append("...");
                }
                
                String urlString = "https://api.telegram.org/bot" + botToken + 
                                  "/sendMessage?chat_id=" + chatId + 
                                  "&text=" + URLEncoder.encode(message.toString(), "UTF-8") +
                                  "&parse_mode=Markdown";
                
                URL url = new URL(urlString);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(5000);
                
                int responseCode = conn.getResponseCode();
                
                if (MainActivity.instance != null) {
                    if (responseCode == 200) {
                        MainActivity.instance.addLog("[TELEGRAM] ✓ Alerte envoyée");
                    } else {
                        MainActivity.instance.addLog("[TELEGRAM] ✗ Erreur " + responseCode);
                    }
                }
                
                conn.disconnect();
                
            } catch (Exception e) {
                Log.e(TAG, "Erreur Telegram", e);
            }
        }).start();
    }
    
    // =====================================================
    // UTILITAIRES
    // =====================================================
    
    private String generateEventId(String pkg, String title, String content) {
        String base = pkg + "_" + title + "_" + System.currentTimeMillis();
        return String.valueOf(base.hashCode());
    }
    
    private String getAppName(String packageName) {
        switch (packageName) {
            case "com.investing.app": return "Investing.com";
            case "com.tradingview.tradingviewapp": return "TradingView";
            case "org.telegram.messenger": return "Telegram";
            case "net.metaquotes.metatrader5": return "MetaTrader 5";
            case "net.metaquotes.metatrader4": return "MetaTrader 4";
            default: return packageName;
        }
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
            manager.createNotificationChannel(channel);
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (MainActivity.instance != null) {
            MainActivity.instance.addLog("[SERVICE] NotificationService arrêté");
        }
    }
}
