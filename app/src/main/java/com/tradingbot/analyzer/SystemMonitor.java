package com.tradingbot.analyzer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SystemMonitor {
    
    private static final Map<String, Integer> eventCountByAsset = new ConcurrentHashMap<>();
    private static final Map<String, Integer> eventCountBySource = new ConcurrentHashMap<>();
    private static long lastEventTime = System.currentTimeMillis();
    private static int totalEventsToday = 0;
    
    public static void registerEvent(String source, List<String> assets) {
        lastEventTime = System.currentTimeMillis();
        totalEventsToday++;
        
        eventCountBySource.put(source, eventCountBySource.getOrDefault(source, 0) + 1);
        
        if (assets != null) {
            for (String asset : assets) {
                eventCountByAsset.put(asset, eventCountByAsset.getOrDefault(asset, 0) + 1);
            }
        }
    }
    
    public static void checkSystemHealth() {
        long now = System.currentTimeMillis();
        long timeSinceLastEvent = now - lastEventTime;
        
        if (timeSinceLastEvent > 1 * 60 * 60 * 1000 && isTradingHours()) {
            long minutesSince = timeSinceLastEvent / 60000;
            sendSystemAlert("⚠️ RISK-WARN: Rupture du flux. Aucun flux reçu de FinancialJuice/Investing/X depuis " + minutesSince + " minutes.");
        }
        
        if (isTradingDay()) {
            String[] coreAssets = {"US10Y", "GOLD", "SP500", "NASDAQ", "GBPUSD", "USOIL", "AUDUSD", "USDCAD", "USDJPY", "BITCOIN"};
            for (String asset : coreAssets) {
                if (eventCountByAsset.getOrDefault(asset, 0) == 0) {
                    sendSystemAlert("📊 COUVERTURE ACQUISITION : L'actif " + getAssetEmoji(asset) + " " + asset + " est aveugle (0 analyses aujourd'hui).");
                }
            }
        }
    }
    
    public static void generateDailyHealthReport() {
        StringBuilder sb = new StringBuilder("⚙️ **RAPPORT QUALITÉ ACQUISITION MACRO**\n\n");
        sb.append("Alertes totales absorbées : `").append(totalEventsToday).append("`\n\n");
        
        sb.append("📡 **RÉPARTITION PAR SOURCE FLUX :**\n");
        eventCountBySource.forEach((src, count) -> 
            sb.append("• ").append(src).append(" : ").append(count).append(" pushs reçus\n")
        );
        
        sb.append("\n📈 **VOLUME TOTAL PAR ACTIF CIBLE :**\n");
        eventCountByAsset.forEach((asset, count) -> 
            sb.append("• ").append(getAssetEmoji(asset)).append(" ").append(asset).append(" : ").append(count).append(" analyses générées\n")
        );
        
        NotificationService.sendTelegramSecure(sb.toString());
    }
    
    public static void resetDailyCounters() {
        eventCountByAsset.clear();
        eventCountBySource.clear();
        totalEventsToday = 0;
        lastEventTime = System.currentTimeMillis();
    }
    
    private static boolean isTradingHours() {
        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int day = cal.get(Calendar.DAY_OF_WEEK);
        return day >= Calendar.MONDAY && day <= Calendar.FRIDAY && hour >= 7 && hour <= 23;
    }
    
    private static boolean isTradingDay() {
        Calendar cal = Calendar.getInstance();
        int day = cal.get(Calendar.DAY_OF_WEEK);
        return day >= Calendar.MONDAY && day <= Calendar.FRIDAY;
    }
    
    private static void sendSystemAlert(String message) {
        NotificationService.sendTelegramSecure("🔧 " + message);
        if (MainActivity.instance != null) {
            MainActivity.instance.addLog("[MONITOR] " + message);
        }
    }
    
    private static String getAssetEmoji(String asset) {
        switch (asset) {
            case "US10Y":   return "🏛️";
            case "GOLD":    return "🥇";
            case "SP500":   return "📊";
            case "NASDAQ":  return "💻";
            case "GBPUSD":  return "🇬🇧";
            case "USOIL":   return "🛢️";
            case "AUDUSD":  return "🇦🇺";
            case "USDCAD":  return "🇨🇦";
            case "USDJPY":  return "🇯🇵";
            case "BITCOIN": return "₿";
            default:        return "📈";
        }
    }
}
