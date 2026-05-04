package com.tradingbot.analyzer;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SystemMonitor {
    
    private static Map<String, Integer> eventCountByAsset = new ConcurrentHashMap<>();
    private static long lastEventTime = System.currentTimeMillis();
    private static int totalEventsToday = 0;
    
    // Compteurs par source
    private static Map<String, Integer> eventCountBySource = new ConcurrentHashMap<>();
    
    // Vérifier santé du système
    public static void checkSystemHealth() {
        long now = System.currentTimeMillis();
        long timeSinceLastEvent = now - lastEventTime;
        
        // === ALERTE 1: Pas d'événement depuis 2 heures pendant heures de trading ===
        if (timeSinceLastEvent > 1 * 60 * 60 * 1000 && isTradingHours()) {
            long minutesSince = timeSinceLastEvent / 60000;
            sendSystemAlert("⚠️ ALERTE SYSTÈME: Aucun événement depuis " + 
                minutesSince + " minutes");
        }
        
        // === ALERTE 2: Actifs sous-représentés ===
        for (String asset : Arrays.asList("GOLD", "BTCUSD", "EURUSD", "SP500", "NASDAQ")) {
            int count = eventCountByAsset.getOrDefault(asset, 0);
            
            // Moins de 3 événements en 24h pour actif majeur
            if (count < 3 && isTradingDay()) {
                sendSystemAlert("⚠️ COUVERTURE FAIBLE: " + asset + 
                    " - seulement " + count + " événements aujourd'hui");
            }
        }
        
        // === ALERTE 3: Performance globale faible ===
        if (totalEventsToday < 10 && isTradingDay() && 
            getCurrentHour() > 16) { // Après 16h
            sendSystemAlert("⚠️ PERFORMANCE GLOBALE FAIBLE: Seulement " + 
                totalEventsToday + " événements aujourd'hui");
        }
        
        // === RAPPORT DE SANTÉ ===
        if (getCurrentHour() == 22 && getCurrentMinute() == 0) {
            generateHealthReport();
        }
        
        // === RESET QUOTIDIEN ===
        Calendar cal = Calendar.getInstance();
        if (cal.get(Calendar.HOUR_OF_DAY) == 0 && 
            cal.get(Calendar.MINUTE) == 0) {
            resetDailyCounters();
        }
    }
    
    // Enregistrer un événement
    public static void recordEvent(String asset) {
        // Compteur par actif
        eventCountByAsset.put(asset, 
            eventCountByAsset.getOrDefault(asset, 0) + 1);
        
        // Dernière activité
        lastEventTime = System.currentTimeMillis();
        
        // Total journalier
        totalEventsToday++;
    }
    
    // Enregistrer source
    public static void recordSource(String source) {
        eventCountBySource.put(source, 
            eventCountBySource.getOrDefault(source, 0) + 1);
    }
    
    private static void generateHealthReport() {
        StringBuilder report = new StringBuilder();
        
        report.append("📊 *RAPPORT DE SANTÉ QUOTIDIEN*\n");
        report.append(new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            .format(new Date()));
        report.append("\n\n");
        
        // Total événements
        report.append("*TOTAL: ").append(totalEventsToday).append(" événements*\n\n");
        
        // Par actif
        report.append("*PAR ACTIF:*\n");
        for (String asset : Arrays.asList("GOLD", "BTCUSD", "GBPUSD", "USDJPY", 
                                          "EURUSD", "SP500", "NASDAQ", "OIL", 
                                          "USDCAD", "AUDUSD")) {
            int count = eventCountByAsset.getOrDefault(asset, 0);
            String emoji = getAssetEmoji(asset);
            report.append(emoji).append(" ").append(asset).append(": ")
                  .append(count).append(" evt\n");
        }
        
        report.append("\n");
        
        // Par source
        report.append("*PAR SOURCE:*\n");
        List<Map.Entry<String, Integer>> sortedSources = 
            new ArrayList<>(eventCountBySource.entrySet());
        sortedSources.sort((a, b) -> b.getValue().compareTo(a.getValue()));
        
        for (int i = 0; i < Math.min(5, sortedSources.size()); i++) {
            Map.Entry<String, Integer> entry = sortedSources.get(i);
            report.append("• ").append(entry.getKey()).append(": ")
                  .append(entry.getValue()).append(" evt\n");
        }
        
        // Évaluation
        report.append("\n*ÉVALUATION:*\n");
        if (totalEventsToday >= 30) {
            report.append("✅ Excellente couverture\n");
        } else if (totalEventsToday >= 20) {
            report.append("✓ Bonne couverture\n");
        } else if (totalEventsToday >= 10) {
            report.append("⚠️ Couverture moyenne\n");
        } else {
            report.append("❌ Couverture insuffisante\n");
        }
        
        NotificationService.sendTelegram(report.toString());
        
        if (MainActivity.instance != null) {
            MainActivity.instance.addLog("[MONITOR] Rapport quotidien généré");
        }
    }
    
    private static void resetDailyCounters() {
        eventCountByAsset.clear();
        eventCountBySource.clear();
        totalEventsToday = 0;
        
        if (MainActivity.instance != null) {
            MainActivity.instance.addLog("[MONITOR] Compteurs réinitialisés");
        }
    }
    
    private static boolean isTradingHours() {
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int day = cal.get(Calendar.DAY_OF_WEEK);
        
        // Lundi-Vendredi, 8h-22h (heures de trading actives)
        return day >= Calendar.MONDAY && day <= Calendar.FRIDAY &&
               hour >= 8 && hour <= 22;
    }
    
    private static boolean isTradingDay() {
        Calendar cal = Calendar.getInstance();
        int day = cal.get(Calendar.DAY_OF_WEEK);
        return day >= Calendar.MONDAY && day <= Calendar.FRIDAY;
    }
    
    private static int getCurrentHour() {
        return Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
    }
    
    private static int getCurrentMinute() {
        return Calendar.getInstance().get(Calendar.MINUTE);
    }
    
    private static void sendSystemAlert(String message) {
        // Envoyer alerte Telegram avec tag spécial
        NotificationService.sendTelegram("🔧 " + message);
        
        if (MainActivity.instance != null) {
            MainActivity.instance.addLog("[MONITOR] " + message);
        }
    }
    
    private static String getAssetEmoji(String asset) {
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
}
