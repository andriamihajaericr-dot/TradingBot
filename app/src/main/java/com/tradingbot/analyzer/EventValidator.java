package com.tradingbot.analyzer;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class EventValidator {
    
    private static Map<String, EconomicCalendarAPI.CalendarEvent> upcomingEvents = 
        new ConcurrentHashMap<>();
    
    public static class ValidationResult {
        public boolean isConfirmed;
        public int confidence; // 0-100
        public String forecast;
        public String previous;
        public String actual;
        public boolean assetsEnriched;
        
        public ValidationResult() {
            this.confidence = 50; // Base
            this.isConfirmed = false;
            this.assetsEnriched = false;
        }
    }
    
    // Pré-charger les événements du calendrier économique
    public static void preloadCalendar() {
        try {
            // Récupérer événements des prochaines 24h
            List<EconomicCalendarAPI.CalendarEvent> events = 
                EconomicCalendarAPI.fetchUpcomingEvents(24);
            
            // Nettoyer anciens événements
            upcomingEvents.clear();
            
            // Stocker nouveaux événements
            for (EconomicCalendarAPI.CalendarEvent event : events) {
                String key = createEventKey(event.indicator, event.timestamp);
                upcomingEvents.put(key, event);
            }
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[VALIDATOR] Calendrier chargé: " + 
                    events.size() + " événements");
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[VALIDATOR] Erreur chargement: " + e.getMessage());
            }
        }
    }
    
    // Valider une notification contre le calendrier économique
    public static ValidationResult validate(
        String title, 
        String content, 
        long timestamp,
        List<String> detectedAssets
    ) {
        
        ValidationResult result = new ValidationResult();
        
        // 1. Chercher événement correspondant dans calendrier
        EconomicCalendarAPI.CalendarEvent match = 
            findMatchingEvent(title, content, timestamp);
        
        if (match != null) {
            // Événement confirmé par calendrier
            result.isConfirmed = true;
            result.confidence = 95;
            result.forecast = match.forecast;
            result.previous = match.previous;
            result.actual = match.actual;
            
            // Enrichir actifs avec calendrier
            for (String asset : match.affectedAssets) {
                if (!detectedAssets.contains(asset)) {
                    detectedAssets.add(asset);
                    result.assetsEnriched = true;
                }
            }
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[VALIDATOR] ✓ Confirmé: " + match.indicator);
            }
            
        } else {
            // Événement non dans calendrier (breaking news)
            result.confidence = calculateBreakingNewsConfidence(title, content);
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[VALIDATOR] Breaking news - Confiance: " + 
                    result.confidence + "%");
            }
        }
        
        return result;
    }
    
    private static EconomicCalendarAPI.CalendarEvent findMatchingEvent(
        String title, 
        String content, 
        long timestamp
    ) {
        
        String combined = (title + " " + content).toLowerCase();
        
        // Fenêtre de +/- 10 minutes
        long window = 10 * 60 * 1000;
        
        for (EconomicCalendarAPI.CalendarEvent event : upcomingEvents.values()) {
            long eventTime = parseTimestamp(event.timestamp);
            
            // Vérifier si dans la fenêtre de temps
            if (Math.abs(eventTime - timestamp) < window) {
                
                // Vérifier si indicateur correspond
                String indicator = event.indicator.toLowerCase();
                
                // Matching exact
                if (combined.contains(indicator)) {
                    return event;
                }
                
                // Matching partiel (mots clés)
                if (matchesIndicatorKeywords(combined, indicator, event.country)) {
                    return event;
                }
            }
        }
        
        return null;
    }
    
    private static boolean matchesIndicatorKeywords(
        String text, 
        String indicator, 
        String country
    ) {
        
        // NFP
        if (indicator.contains("nfp") || indicator.contains("non-farm")) {
            return text.contains("nfp") || text.contains("non-farm") || 
                   text.contains("payroll");
        }
        
        // CPI
        if (indicator.contains("cpi")) {
            return text.contains("cpi") || text.contains("inflation");
        }
        
        // GDP
        if (indicator.contains("gdp")) {
            return text.contains("gdp") || text.contains("gross domestic");
        }
        
        // Fed Rate
        if (indicator.contains("fed") && indicator.contains("rate")) {
            return text.contains("fed") && (text.contains("rate") || text.contains("fomc"));
        }
        
        // BoE Rate
        if (indicator.contains("boe") || 
            (country != null && country.toLowerCase().contains("uk"))) {
            return text.contains("boe") || text.contains("bank of england");
        }
        
        // BoJ
        if (indicator.contains("boj") || 
            (country != null && country.toLowerCase().contains("japan"))) {
            return text.contains("boj") || text.contains("bank of japan");
        }
        
        // EIA
        if (indicator.contains("eia") || indicator.contains("oil inventory")) {
            return text.contains("eia") || text.contains("oil inventory") || 
                   text.contains("crude inventory");
        }
        
        // OPEC
        if (indicator.contains("opec")) {
            return text.contains("opec");
        }
        
        return false;
    }
    
    private static int calculateBreakingNewsConfidence(String title, String content) {
        int score = 50; // Base
        
        String lower = (title + " " + content).toLowerCase();
        
        // Boost si mots-clés urgents
        if (lower.contains("breaking")) score += 20;
        if (lower.contains("urgent") || lower.contains("alert")) score += 15;
        if (lower.contains("flash")) score += 10;
        
        // Boost si source très fiable (priorité 5)
        if (lower.contains("fxhedgers")) score += 20;
        if (lower.contains("deltaone") || lower.contains("firstsquawk")) score += 20;
        if (lower.contains("financialjuice")) score += 15;
        
        // Boost si source officielle
        if (lower.contains("federal reserve") || lower.contains("@federalreserve")) score += 25;
        if (lower.contains("bank of england") || lower.contains("@bankofengland")) score += 25;
        if (lower.contains("eia") || lower.contains("@eiagov")) score += 20;
        
        // Boost si données chiffrées (précision)
        if (content.matches(".*\\d+\\.\\d+%.*")) score += 10;
        if (content.matches(".*\\d+[KM].*")) score += 5; // 150K, 2.5M
        
        // Boost si événement géopolitique majeur
        if (lower.contains("war") || lower.contains("attack") || lower.contains("nuclear")) {
            score += 15;
        }
        
        // Boost si banque centrale
        if (lower.contains("fed") || lower.contains("boe") || lower.contains("boj") || 
            lower.contains("ecb")) {
            score += 10;
        }
        
        return Math.min(100, score);
    }
    
    private static String createEventKey(String indicator, String timestamp) {
        if (indicator == null || timestamp == null) {
            return UUID.randomUUID().toString();
        }
        return indicator.toLowerCase().replaceAll("[^a-z0-9]", "_") + "_" + timestamp;
    }
    
    private static long parseTimestamp(String timestamp) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            return sdf.parse(timestamp).getTime();
        } catch (Exception e) {
            try {
                return Long.parseLong(timestamp) * 1000;
            } catch (Exception e2) {
                return System.currentTimeMillis();
            }
        }
    }
}
