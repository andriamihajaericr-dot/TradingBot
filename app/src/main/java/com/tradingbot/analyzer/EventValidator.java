package com.tradingbot.analyzer;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class EventValidator {

    private static Map<String, EconomicCalendarAPI.CalendarEvent> upcomingEvents = 
        new ConcurrentHashMap<>();

    // ====================== CLASSE INTERNE ======================
    public static class ValidationResult {
        public boolean isConfirmed = false;
        public int confidence = 0;
        public String forecast;
        public String previous;
        public String actual;
        public boolean assetsEnriched = false;
        public String reason = "";

        public ValidationResult() {
        }

        public ValidationResult(boolean isConfirmed, int confidence, String reason) {
            this.isConfirmed = isConfirmed;
            this.confidence = confidence;
            this.reason = reason;
        }
    }

    // ====================== MÉTHODE PRINCIPALE ======================
    public static ValidationResult validate(
            String title,
            String content,
            long timestamp,
            List<String> detectedAssets
    ) {

        ValidationResult result = new ValidationResult();

        String combined = (title + " " + content).toLowerCase();

        // ❌ Rejeter immédiatement si contenu politique/opinion
        if (containsPoliticalContent(combined)) {
            result.confidence = 0;
            result.isConfirmed = false;
            result.reason = "Contenu politique/opinion";
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[VALIDATOR] ❌ Rejeté - Contenu politique/opinion");
            }
            return result;
        }

        // 1. Chercher événement correspondant dans calendrier
        EconomicCalendarAPI.CalendarEvent match = 
            findMatchingEvent(title, content, timestamp);

        if (match != null) {
            // ✅ Événement confirmé par calendrier
            result.isConfirmed = true;
            result.confidence = 95;
            result.forecast = match.forecast;
            result.previous = match.previous;
            result.actual = match.actual;
            result.reason = "Confirmé par calendrier";

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
            result.reason = "Breaking news";

            if (result.confidence < 60) {
                result.confidence = 0;
                result.isConfirmed = false;
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog("[VALIDATOR] ❌ Confiance insuffisante: " 
                        + result.confidence + "%");
                }
            } else {
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog("[VALIDATOR] Breaking news - Confiance: " 
                        + result.confidence + "%");
                }
            }
        }

        return result;
    }

    private static boolean containsPoliticalContent(String text) {
        return text.contains("opinion") || text.contains("democrat") ||
               text.contains("republican") || text.contains("party") ||
               text.contains("politician") || text.contains("editorial");
    }

    // ====================== MÉTHODES INTERNES ======================
    private static EconomicCalendarAPI.CalendarEvent findMatchingEvent(
            String title, String content, long timestamp) {

        String combined = (title + " " + content).toLowerCase();
        long window = 10 * 60 * 1000; // ±10 minutes

        for (EconomicCalendarAPI.CalendarEvent event : upcomingEvents.values()) {
            long eventTime = parseTimestamp(event.timestamp);

            if (Math.abs(eventTime - timestamp) < window) {
                String indicator = event.indicator.toLowerCase();

                if (combined.contains(indicator) || 
                    matchesIndicatorKeywords(combined, indicator, event.country)) {
                    return event;
                }
            }
        }
        return null;
    }

    private static boolean matchesIndicatorKeywords(String text, String indicator, String country) {
        // NFP
        if (indicator.contains("nfp") || indicator.contains("non-farm")) {
            return text.contains("nfp") || text.contains("non-farm") || text.contains("payroll");
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
        // BoE, BoJ, EIA, OPEC...
        if (indicator.contains("boe") || (country != null && country.toLowerCase().contains("uk"))) {
            return text.contains("boe") || text.contains("bank of england");
        }
        if (indicator.contains("boj") || (country != null && country.toLowerCase().contains("japan"))) {
            return text.contains("boj") || text.contains("bank of japan");
        }
        if (indicator.contains("eia") || indicator.contains("oil inventory")) {
            return text.contains("eia") || text.contains("oil inventory") || text.contains("crude inventory");
        }
        if (indicator.contains("opec")) {
            return text.contains("opec");
        }
        return false;
    }

    private static int calculateBreakingNewsConfidence(String title, String content) {
        int score = 50;
        String lower = (title + " " + content).toLowerCase();

        if (lower.contains("breaking")) score += 20;
        if (lower.contains("urgent") || lower.contains("alert")) score += 15;
        if (lower.contains("flash")) score += 10;

        if (lower.contains("fxhedgers")) score += 20;
        if (lower.contains("deltaone") || lower.contains("firstsquawk")) score += 20;
        if (lower.contains("financialjuice")) score += 15;

        if (lower.contains("federal reserve")) score += 25;
        if (lower.contains("bank of england")) score += 25;
        if (lower.contains("eia")) score += 20;

        if (content.matches(".*\\d+\\.\\d+%.*")) score += 10;
        if (content.matches(".*\\d+[KM].*")) score += 5;

        if (lower.contains("war") || lower.contains("attack") || lower.contains("nuclear")) score += 15;
        if (lower.contains("fed") || lower.contains("boe") || lower.contains("boj") || lower.contains("ecb")) score += 10;

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

    // ====================== PRELOAD ======================
    public static void preloadCalendar() {
        try {
            List<EconomicCalendarAPI.CalendarEvent> events = 
                EconomicCalendarAPI.fetchUpcomingEvents(24);

            upcomingEvents.clear();

            for (EconomicCalendarAPI.CalendarEvent event : events) {
                String key = createEventKey(event.indicator, event.timestamp);
                upcomingEvents.put(key, event);
            }

            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[VALIDATOR] Calendrier chargé: " + events.size() + " événements");
            }
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[VALIDATOR] Erreur chargement: " + e.getMessage());
            }
        }
    }
}
