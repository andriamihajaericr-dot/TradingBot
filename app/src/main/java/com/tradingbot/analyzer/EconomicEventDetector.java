package com.tradingbot.analyzer;

import android.util.Log;
import java.util.*;

public class EconomicEventDetector {

    private static final String TAG = "EventDetector";
    
    private EventDatabase eventDb;
    
    // Fenêtre de temps pour corréler événements (30 minutes avant/après)
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
            
            // Récupérer événements des prochaines 24h depuis l'API
            List<EconomicCalendarAPI.CalendarEvent> upcomingEvents = 
                EconomicCalendarAPI.fetchUpcomingEvents(24);
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog(
                    "[DETECTOR] " + upcomingEvents.size() + " événements trouvés"
                );
            }
            
            // Sauvegarder dans la DB et envoyer alertes
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
            
            // Récupérer événements des 30 dernières minutes
            List<EconomicCalendarAPI.CalendarEvent> recentEvents = 
                EconomicCalendarAPI.fetchRecentEvents(30);
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog(
                    "[DETECTOR] " + recentEvents.size() + " événements récents"
                );
            }
            
            // Corréler avec les notifications reçues
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
            
            // Ignorer événements passés
            if (timeUntilEvent < 0) {
                return;
            }
            
            // Générer un ID unique
            String eventId = "calendar_" + event.timestamp + "_" + 
                           event.country.hashCode() + "_" + 
                           event.indicator.hashCode();
            
            // Vérifier si déjà en DB
            if (eventDb.eventExists(eventId)) {
                return;
            }
            
            // Sauvegarder dans la DB
            String assetsStr = String.join(", ", event.affectedAssets);
            
            boolean saved = eventDb.saveEvent(
                eventId,
                "economic.calendar",
                "Economic Calendar API",
                event.indicator,
                event.indicator,
                buildEventContent(event),
                assetsStr,
                event.importance,
                calculateCalendarConfidence(event),
                "calendar"
            );
            
            if (saved && MainActivity.instance != null) {
                MainActivity.instance.addLog(
                    "[CALENDAR] Sauvegardé: " + event.country + " - " + 
                    event.indicator + " → " + event.affectedAssets.get(0)
                );
            }
            
            // ALERTE SI ÉVÉNEMENT IMMINENT (< 1h) ET HIGH
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
            
            // Récupérer notifications dans fenêtre de temps
            List<EventDatabase.StoredEvent> notifications = 
                eventDb.getEventsInTimeWindow(eventTime, CORRELATION_WINDOW_MS);
            
            // Filtrer uniquement les notifications (pas calendrier)
            List<EventDatabase.StoredEvent> relevantNotifs = new ArrayList<>();
            for (EventDatabase.StoredEvent notif : notifications) {
                if ("notification".equals(notif.sourceType)) {
                    relevantNotifs.add(notif);
                }
            }
            
            if (relevantNotifs.isEmpty()) {
                return;
            }
            
            // Vérifier si les actifs correspondent
            boolean hasMatch = false;
            for (EventDatabase.StoredEvent notif : relevantNotifs) {
                String[] notifAssets = notif.assets.split(",");
                
                for (String notifAsset : notifAssets) {
                    String trimmedAsset = notifAsset.trim();
                    
                    if (calendarEvent.affectedAssets.contains(trimmedAsset)) {
                        hasMatch = true;
                        
                        if (MainActivity.instance != null) {
                            MainActivity.instance.addLog(
                                "[CORRELATION] ✓ Match trouvé: " + trimmedAsset + 
                                " | Calendar: " + calendarEvent.indicator + 
                                " ↔ Notif: " + notif.title
                            );
                        }
                        
                        // Augmenter la confiance de la notification
                        int newConfidence = Math.min(100, notif.confidence + 15);
                        eventDb.updateConfidence(notif.eventId, newConfidence);
                        
                        break;
                    }
                }
                
                if (hasMatch) break;
            }
            
            // Si correspondance trouvée, envoyer alerte combinée
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
            
            // Événements des dernières 24h
            long since = System.currentTimeMillis() - (24 * 60 * 60 * 1000);
            
            List<EventDatabase.StoredEvent> assetEvents = 
                eventDb.getEventsByAsset(asset, since);
            
            if (assetEvents.isEmpty()) {
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog("[DETECTOR] Aucun événement pour " + asset);
                }
                return;
            }
            
            // Statistiques
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
            
            // Alerte si beaucoup d'événements HIGH
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
            
            // Événements à venir dans les 4 prochaines heures
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
            
            // Actifs les plus actifs
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
            
            NotificationService.sendTelegramAlert(
                event.country,
                event.affectedAssets,
                "⏰ " + event.indicator + " dans " + minutesUntil + "min",
                message.toString(),
                event.importance,
                95
            );
            
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
            
            NotificationService.sendTelegramAlert(
                calendarEvent.country,
                calendarEvent.affectedAssets,
                "🔗 Corrélation: " + calendarEvent.indicator,
                message.toString(),
                "HIGH",
                90
            );
            
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
            
            NotificationService.sendTelegramAlert(
                "Multiple",
                Arrays.asList(asset),
                "⚠️ Forte activité sur " + asset,
                message.toString(),
                "HIGH",
                85
            );
            
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
    
    private int calculateCalendarConfidence(EconomicCalendarAPI.CalendarEvent event) {
        int confidence = 80; // Base élevée car source officielle
        
        // Bonus importance
        if ("High".equals(event.importance)) {
            confidence += 15;
        } else if ("Medium".equals(event.importance)) {
            confidence += 5;
        }
        
        // Bonus si forecast disponible
        if (!"N/A".equals(event.forecast)) {
            confidence += 5;
        }
        
        return Math.min(100, confidence);
    }
}
