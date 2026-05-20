package com.tradingbot.analyzer;

import java.text.SimpleDateFormat;
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
        
        // Alerte : Rupture du flux d'informations pendant la session de trading active (1 heure sans news)
        if (timeSinceLastEvent > 1 * 60 * 60 * 1000 && isTradingHours()) {
            long minutesSince = timeSinceLastEvent / 60000;
            sendSystemAlert("⚠️ RISK-WARN: Rupture potentielle du flux d'alimentation macro. Aucune donnée captée depuis " + minutesSince + " minutes.");
        }
        
        // Surveillance de la couverture de vos actifs institutionnels spécifiques
        if (isTradingDay()) {
            String[] coreAssets = {"US10Y", "GOLD", "SP500", "NASDAQ", "GBPUSD", "USOIL", "AUDUSD", "USDCAD", "USDJPY", "BITCOIN"};
            for (String asset : coreAssets) {
                int count = eventCountByAsset.getOrDefault(asset, 0);
                if (count == 0) {
                    sendSystemAlert("📊 COUVERTURE FAIBLE: L'actif " + getAssetEmoji(asset) + " " + asset + " n'a reçu aucune mise à jour de flux aujourd'hui.");
                }
            }
        }
    }
    
    public static void generateDailyHealthReport() {
        StringBuilder sb = new StringBuilder("⚙️ **RAPPORT METRIQUES DE SANTE DU MOTEUR**\n\n");
        sb.append("Total alertes traitées aujourd'hui: `").append(totalEventsToday).append("`\n\n");
        sb.append("📈 **VOLUME PAR ACTIF :**\n");
        
        eventCountByAsset.forEach((asset, count) -> 
            sb.append("• ").append(getAssetEmoji(asset)).append(" ").append(asset).append(": ").append(count).append(" analyses\n")
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
        
        // Session macro mondiale active: Lundi au Vendredi, de 07h00 à 23h00 (Heures d'Europe/US)
        return day >= Calendar.MONDAY && day <= Calendar.FRIDAY && hour >= 7 && hour <= 23;
    }
    
    private static boolean isTradingDay() {
        Calendar cal = Calendar.getInstance();
        int day = cal.get(Calendar.DAY_OF_WEEK);
        return day >= Calendar.MONDAY && day <= Calendar.FRIDAY;
    }
    
    private static void sendSystemAlert(String message) {
        // Redirection vers le point d'entrée réseau crypté et sécurisé de votre infrastructure
        NotificationService.sendTelegramSecure("🔧 " + message);
        
        if (MainActivity.instance != null) {
            MainActivity.instance.addLog("[MONITOR-SYS] " + message);
        }
    }
    
    private static String getAssetEmoji(String asset) {
        switch (asset) {
            case "US10Y":   return "🏛️"; // Obligations d'État / Pivot
            case "GOLD":    return "🥇"; // Valeur refuge
            case "SP500":   return "📊"; // Indice large US
            case "NASDAQ":  return "💻"; // Indice technologique
            case "GBPUSD":  return "🇬🇧"; // Cable
            case "USOIL":   return "🛢️"; // West Texas Intermediate
            case "AUDUSD":  return "🇦🇺"; // Devise matières premières
            case "USDCAD":  return "🇨🇦"; // Devise corrélée pétrole
            case "USDJPY":  return "🇯🇵"; // Devise carry trade
            case "BITCOIN": return "₿";  // Or numérique
            default:        return "📈";
        }
    }
}
