package com.tradingbot.analyzer;

import android.util.Log;
import java.util.*;
import java.util.regex.*;

public class EconomicEventDetector {

    private static final String TAG = "EventDetector";
    
    private EventDatabase eventDb;
    
    private static final long CORRELATION_WINDOW_MS = 30 * 60 * 1000;
    
    public EconomicEventDetector(EventDatabase db) {
        this.eventDb = db;
    }
    
    // =====================================================
    // DÉTECTION PROACTIVE DES ÉVÉNEMENTS À VENIR
    // =====================================================
    
    public void checkUpcomingEvents() {
        try {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[DETECTOR] Vérification événements à venir...");
            }
            
            List<EconomicCalendarAPI.CalendarEvent> upcomingEvents = 
                EconomicCalendarAPI.fetchUpcomingEvents(24);
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog(
                    "[DETECTOR] " + upcomingEvents.size() + " événements trouvés"
                );
            }
            
            for (EconomicCalendarAPI.CalendarEvent event : upcomingEvents) {
                processCalendarEvent(event);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Erreur checkUpcomingEvents", e);
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[DETECTOR] Erreur: " + e.getMessage());
            }
        }
    }
    
    // =====================================================
    // DÉTECTION DES ÉVÉNEMENTS RÉCENTS (déjà passés)
    // =====================================================
    
    public void checkRecentEvents() {
        try {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[DETECTOR] Vérification événements récents...");
            }
            
            List<EconomicCalendarAPI.CalendarEvent> recentEvents = 
                EconomicCalendarAPI.fetchRecentEvents(30);
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog(
                    "[DETECTOR] " + recentEvents.size() + " événements récents"
                );
            }
            
            for (EconomicCalendarAPI.CalendarEvent event : recentEvents) {
                correlateWithNotifications(event);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Erreur checkRecentEvents", e);
        }
    }
    
    // =====================================================
    // TRAITER UN ÉVÉNEMENT DU CALENDRIER
    // =====================================================
    
    private void processCalendarEvent(EconomicCalendarAPI.CalendarEvent event) {
        try {
            long eventTime = Long.parseLong(event.timestamp) * 1000;
            long now = System.currentTimeMillis();
            long timeUntilEvent = eventTime - now;
            
            if (timeUntilEvent < 0) {
                return;
            }
            
            String eventId = "calendar_" + event.timestamp + "_" + 
                           event.country.hashCode() + "_" + 
                           event.indicator.hashCode();
            
            if (eventDb.eventExists(eventId)) {
                return;
            }
            
            String assetsStr = String.join(", ", event.affectedAssets);
            
            boolean saved = eventDb.saveEvent(
                eventId,
                "economic.calendar",
                "Economic Calendar API",
                event.importance,
                event.indicator,
                buildEventContent(event),
                assetsStr,
                "Neutre"
            );
            
            if (saved && MainActivity.instance != null) {
                MainActivity.instance.addLog(
                    "[CALENDAR] Sauvegardé: " + event.country + " - " + 
                    event.indicator + " → " + event.affectedAssets.get(0)
                );
            }
            
            if ("High".equals(event.importance) && timeUntilEvent < 60 * 60 * 1000) {
                sendUpcomingEventAlert(event, timeUntilEvent);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Erreur processCalendarEvent", e);
        }
    }
    
    // =====================================================
    // CORRÉLER ÉVÉNEMENT CALENDRIER AVEC NOTIFICATIONS
    // =====================================================
    
    private void correlateWithNotifications(EconomicCalendarAPI.CalendarEvent calendarEvent) {
        try {
            long eventTime = Long.parseLong(calendarEvent.timestamp) * 1000;
            
            List<EventDatabase.StoredEvent> notifications = 
                eventDb.getEventsInTimeWindow(eventTime, CORRELATION_WINDOW_MS);
            
            List<EventDatabase.StoredEvent> relevantNotifs = new ArrayList<>();
            for (EventDatabase.StoredEvent notif : notifications) {
                if ("notification".equals(notif.sourceType)) {
                    relevantNotifs.add(notif);
                }
            }
            
            if (relevantNotifs.isEmpty()) {
                return;
            }
            
            boolean hasMatch = false;
            for (EventDatabase.StoredEvent notif : relevantNotifs) {
                String[] notifAssets = notif.assets.split(",");
                
                for (String notifAsset : notifAssets) {
                    String trimmedAsset = notifAsset.trim();
                    
                    if (calendarEvent.affectedAssets.contains(trimmedAsset)) {
                        hasMatch = true;
                        
                        if (MainActivity.instance != null) {
                            MainActivity.instance.addLog(
                                "[CORRELATION] ✓ Match: " + trimmedAsset + 
                                " | Calendar: " + calendarEvent.indicator + 
                                " ↔ Notif: " + notif.title
                            );
                        }
                        
                        int newConfidence = Math.min(100, notif.confidence + 15);
                        eventDb.updateConfidence(notif.eventId, newConfidence);
                        
                        break;
                    }
                }
                
                if (hasMatch) break;
            }
            
            if (hasMatch) {
                sendCorrelatedAlert(calendarEvent, relevantNotifs.get(0));
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Erreur correlateWithNotifications", e);
        }
    }
    
    // =====================================================
    // ANALYSE PAR ACTIF
    // =====================================================
    
    public void analyzeAssetEvents(String asset) {
        try {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[DETECTOR] Analyse actif: " + asset);
            }
            
            long since = System.currentTimeMillis() - (24 * 60 * 60 * 1000);
            
            List<EventDatabase.StoredEvent> assetEvents = 
                eventDb.getEventsByAsset(asset, since);
            
            if (assetEvents.isEmpty()) {
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog("[DETECTOR] Aucun événement pour " + asset);
                }
                return;
            }
            
            int highCount = 0;
            int mediumCount = 0;
            int totalConfidence = 0;
            
            for (EventDatabase.StoredEvent event : assetEvents) {
                if ("HIGH".equals(event.impact)) highCount++;
                if ("MEDIUM".equals(event.impact)) mediumCount++;
                totalConfidence += event.confidence;
            }
            
            int avgConfidence = totalConfidence / assetEvents.size();
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog(
                    "[DETECTOR] " + asset + " : " + assetEvents.size() + " événements | " +
                    "HIGH: " + highCount + " | MEDIUM: " + mediumCount + 
                    " | Conf. moy: " + avgConfidence + "%"
                );
            }
            
            if (highCount >= 3) {
                sendAssetHighActivityAlert(asset, highCount, assetEvents);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Erreur analyzeAssetEvents", e);
        }
    }
    
    // =====================================================
    // GÉNÉRATION DE RAPPORTS
    // =====================================================
    
    public String generateDailyReport() {
        StringBuilder report = new StringBuilder();
        
        try {
            EventDatabase.DatabaseStats stats = eventDb.getStats();
            
            report.append("📊 RAPPORT QUOTIDIEN\n\n");
            report.append("Total événements: ").append(stats.totalEvents).append("\n");
            report.append("Traités: ").append(stats.processedEvents).append("\n");
            report.append("Aujourd'hui: ").append(stats.eventsToday).append("\n");
            report.append("Confiance moyenne: ").append(stats.avgConfidence).append("%\n\n");
            
            report.append("Par source:\n");
            for (Map.Entry<String, Integer> entry : stats.bySource.entrySet()) {
                report.append("  - ").append(entry.getKey()).append(": ")
                      .append(entry.getValue()).append("\n");
            }
            
            List<EconomicCalendarAPI.CalendarEvent> upcoming = 
                EconomicCalendarAPI.fetchUpcomingEvents(4);
            
            if (!upcoming.isEmpty()) {
                report.append("\n🔜 PROCHAINS ÉVÉNEMENTS (4h):\n");
                for (int i = 0; i < Math.min(5, upcoming.size()); i++) {
                    EconomicCalendarAPI.CalendarEvent evt = upcoming.get(i);
                    report.append("  • ").append(evt.country).append(" - ")
                          .append(evt.indicator).append(" (")
                          .append(evt.importance).append(")\n");
                    report.append("    → ").append(evt.affectedAssets.get(0)).append("\n");
                }
            }
            
            report.append("\n📈 ACTIFS LES PLUS ACTIFS:\n");
            String[] topAssets = {"SP500", "GOLD", "EURUSD", "BTCUSD"};
            long since = System.currentTimeMillis() - (24 * 60 * 60 * 1000);
            
            for (String asset : topAssets) {
                int count = eventDb.getEventCountByAsset(asset, since);
                if (count > 0) {
                    report.append("  • ").append(asset).append(": ")
                          .append(count).append(" événements\n");
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Erreur generateDailyReport", e);
            report.append("\nErreur génération rapport: ").append(e.getMessage());
        }
        
        return report.toString();
    }
    
    // =====================================================
    // ALERTES
    // =====================================================
    
    private void sendUpcomingEventAlert(EconomicCalendarAPI.CalendarEvent event, 
                                        long timeUntilEvent) {
        try {
            int minutesUntil = (int) (timeUntilEvent / (60 * 1000));
            
            StringBuilder message = new StringBuilder();
            message.append("⏰ **ÉVÉNEMENT IMMINENT**\n\n");
            message.append("**Dans ").append(minutesUntil).append(" minutes**\n\n");
            message.append("**Pays:** ").append(event.country).append("\n");
            message.append("**Événement:** ").append(event.indicator).append("\n");
            message.append("**Importance:** ").append(event.importance).append("\n");
            message.append("**Forecast:** ").append(event.forecast).append("\n");
            message.append("**Previous:** ").append(event.previous).append("\n\n");
            message.append("**Actifs impactés:**\n");
            
            for (int i = 0; i < Math.min(4, event.affectedAssets.size()); i++) {
                message.append("  ").append(i + 1).append(". ")
                       .append(event.affectedAssets.get(i)).append("\n");
            }
            
            NotificationService.sendTelegram(message.toString());
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog(
                    "[ALERT] Événement imminent envoyé: " + event.indicator
                );
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Erreur sendUpcomingEventAlert", e);
        }
    }
    
    private void sendCorrelatedAlert(EconomicCalendarAPI.CalendarEvent calendarEvent, 
                                     EventDatabase.StoredEvent notification) {
        try {
            StringBuilder message = new StringBuilder();
            message.append("🔗 **CORRÉLATION DÉTECTÉE**\n\n");
            message.append("**Calendrier économique:**\n");
            message.append(calendarEvent.country).append(" - ")
                   .append(calendarEvent.indicator).append("\n\n");
            message.append("**Notification reçue:**\n");
            message.append(notification.appName).append(" - ")
                   .append(notification.title).append("\n\n");
            message.append("**Actifs communs:**\n");
            message.append(notification.assets).append("\n\n");
            message.append("**Confiance augmentée:** ")
                   .append(notification.confidence).append("% → ")
                   .append(Math.min(100, notification.confidence + 15)).append("%");
            
            NotificationService.sendTelegram(message.toString());
            
        } catch (Exception e) {
            Log.e(TAG, "Erreur sendCorrelatedAlert", e);
        }
    }
    
    private void sendAssetHighActivityAlert(String asset, int highCount, 
                                            List<EventDatabase.StoredEvent> events) {
        try {
            StringBuilder message = new StringBuilder();
            message.append("⚠️ **FORTE ACTIVITÉ DÉTECTÉE**\n\n");
            message.append("**Actif:** ").append(asset).append("\n");
            message.append("**Événements HIGH:** ").append(highCount).append("\n\n");
            message.append("**Derniers événements:**\n");
            
            for (int i = 0; i < Math.min(3, events.size()); i++) {
                EventDatabase.StoredEvent evt = events.get(i);
                message.append("  • ").append(evt.title).append("\n");
            }
            
            NotificationService.sendTelegram(message.toString());
            
        } catch (Exception e) {
            Log.e(TAG, "Erreur sendAssetHighActivityAlert", e);
        }
    }
    
    // =====================================================
    // UTILITAIRES
    // =====================================================
    
    private String buildEventContent(EconomicCalendarAPI.CalendarEvent event) {
        StringBuilder content = new StringBuilder();
        content.append(event.country).append(" - ").append(event.indicator).append("\n");
        content.append("Forecast: ").append(event.forecast).append("\n");
        content.append("Previous: ").append(event.previous);
        
        if (!"N/A".equals(event.actual)) {
            content.append("\nActual: ").append(event.actual);
        }
        
        return content.toString();
    }

    // =====================================================
    // ✨ DÉTECTION D'ÉVÉNEMENT (MÉTHODE PRINCIPALE)
    // =====================================================
    
    public static DetectedEvent detectEvent(String title, String content) {
        String combined = (title + " " + content).toLowerCase();
        
        String eventType = detectEventType(combined);
        String impact = detectImpact(combined);
        String description = title.isEmpty() ? 
            content.substring(0, Math.min(100, content.length())) : title;
        
        DetectedEvent event = new DetectedEvent(eventType, impact, description);
        
        event.country = detectCountry(combined);
        event.indicator = detectIndicator(combined);
        event.forecast = extractDataPoint(combined, "forecast", "expected", "estimate");
        event.previous = extractDataPoint(combined, "previous", "prior", "last");
        event.actual = extractDataPoint(combined, "actual", "came in at", "reported");
        
        return event;
    }
    
    private static String detectEventType(String text) {
        if (text.contains("nfp") || text.contains("payroll") || text.contains("employment")) {
            return "EMPLOYMENT";
        }
        if (text.contains("cpi") || text.contains("inflation") || text.contains("pce")) {
            return "INFLATION";
        }
        if (text.contains("fed") || text.contains("fomc") || text.contains("ecb") || 
            text.contains("boe") || text.contains("rate decision")) {
            return "CENTRAL_BANK";
        }
        if (text.contains("gdp") || text.contains("pmi") || text.contains("retail")) {
            return "GROWTH";
        }
        if (text.contains("oil") || text.contains("eia") || text.contains("crude")) {
            return "COMMODITY";
        }
        return "ECONOMIC";
    }
    
    private static String detectImpact(String text) {
        int bullishScore = 0;
        int bearishScore = 0;
        
        String[] bullishWords = {"rally", "surge", "gain", "up", "rise", "increase", 
                                "better than expected", "beat", "positive", "strong"};
        String[] bearishWords = {"fall", "drop", "decline", "down", "decrease", 
                                "worse than expected", "miss", "negative", "weak"};
        
        for (String word : bullishWords) {
            if (text.contains(word)) bullishScore++;
        }
        for (String word : bearishWords) {
            if (text.contains(word)) bearishScore++;
        }
        
        if (bullishScore > bearishScore) return "Haussier";
        if (bearishScore > bullishScore) return "Baissier";
        return "Neutre";
    }
    
    private static String detectCountry(String text) {
        if (text.contains("us ") || text.contains("usa") || text.contains("united states")) {
            return "United States";
        }
        if (text.contains("uk ") || text.contains("britain") || text.contains("united kingdom")) {
            return "United Kingdom";
        }
        if (text.contains("eurozone") || text.contains("euro area") || text.contains("germany")) {
            return "Eurozone";
        }
        if (text.contains("japan")) return "Japan";
        if (text.contains("australia")) return "Australia";
        if (text.contains("canada")) return "Canada";
        return null;
    }
    
    private static String detectIndicator(String text) {
        if (text.contains("nfp") || text.contains("non-farm payroll")) return "Non-Farm Payrolls";
        if (text.contains("cpi")) return "Consumer Price Index";
        if (text.contains("gdp")) return "Gross Domestic Product";
        if (text.contains("pmi")) return "Purchasing Managers Index";
        if (text.contains("fomc")) return "FOMC Meeting";
        if (text.contains("retail sales")) return "Retail Sales";
        return null;
    }
    
    private static String extractDataPoint(String text, String... keywords) {
        for (String keyword : keywords) {
            int index = text.indexOf(keyword);
            if (index != -1) {
                String substr = text.substring(index);
                Pattern pattern = Pattern.compile("[-+]?\\d+\\.?\\d*%?");
                Matcher matcher = pattern.matcher(substr);
                if (matcher.find()) {
                    return matcher.group();
                }
            }
        }
        return null;
    }
    
    // =====================================================
    // ✨ CLASSE DÉTECTÉE EVENT
    // =====================================================
    
    public static class DetectedEvent {
        public String eventType;
        public String impact;
        public String description;
        public String country;
        public String indicator;
        public String forecast;
        public String previous;
        public String actual;
        
        public DetectedEvent(String eventType, String impact, String description) {
            this.eventType = eventType;
            this.impact = impact;
            this.description = description;
            this.country = null;
            this.indicator = null;
            this.forecast = null;
            this.previous = null;
            this.actual = null;
        }
        
        public String getDescription() {
            if (indicator != null && country != null) {
                return country + " " + indicator;
            }
            return description;
        }
        
        public boolean shouldNotify() {
            return "Haussier".equals(impact) || "Baissier".equals(impact);
        }
    }
}
