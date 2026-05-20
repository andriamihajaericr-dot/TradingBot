package com.tradingbot.analyzer;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class EventValidator {

    private static Map<String, EconomicCalendarAPI.CalendarEvent> upcomingEvents = 
        new ConcurrentHashMap<>();

    public static class ValidationResult {
        public boolean isConfirmed = false;
        public int confidence = 0;
        public String forecast = "N/A";
        public String previous = "N/A";
        public String actual = "N/A";
        public boolean assetsEnriched = false;
        public String reason = "";

        public ValidationResult() {}

        public ValidationResult(boolean isConfirmed, int confidence, String reason) {
            this.isConfirmed = isConfirmed;
            this.confidence = confidence;
            this.reason = reason;
        }
    }

    public static ValidationResult validate(
            String title,
            String content,
            long timestamp,
            List<String> detectedAssets
    ) {
        ValidationResult result = new ValidationResult();

        if (title == null) title = "";
        if (content == null) content = "";
        if (detectedAssets == null) detectedAssets = new ArrayList<>();

        String combined = (title + " " + content).toLowerCase();

        // ❌ Filtre Institutionnel : Élimination immédiate du bruit éditorial et politique
        if (containsPoliticalOrOpinionContent(combined)) {
            result.confidence = 0;
            result.isConfirmed = false;
            result.reason = "Bruit macroéconomique (Opinion/Politique/Éditorial)";
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[VALIDATOR] ❌ Rejeté - Contenu non factuel/opinion");
            }
            return result;
        }

        EconomicCalendarAPI.CalendarEvent match = findMatchingEvent(title, content, timestamp);

        if (match != null) {
            result.isConfirmed = true;
            result.confidence = 98; // Niveau institutionnel si validé par le calendrier
            result.forecast = match.forecast != null ? match.forecast : "N/A";
            result.previous = match.previous != null ? match.previous : "N/A";
            result.actual = match.actual != null ? match.actual : "N/A";
            result.reason = "Confirmé par calendrier économique global";

            if (match.affectedAssets != null) {
                for (String asset : match.affectedAssets) {
                    if (asset != null && !detectedAssets.contains(asset)) {
                        detectedAssets.add(asset);
                        result.assetsEnriched = true;
                    }
                }
            }

            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[VALIDATOR] ✓ Confirmé: " + (match.indicator != null ? match.indicator : "Inconnu"));
            }

        } else {
            result.confidence = calculateBreakingNewsConfidence(title, content);
            result.reason = "Breaking News (Flux Interbancaire)";

            if (result.confidence < 65) {
                result.confidence = 0;
                result.isConfirmed = false;
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog("[VALIDATOR] ❌ Confiance insuffisante : " + result.confidence + "%");
                }
            }
        }

        return result;
    }

    private static boolean containsPoliticalOrOpinionContent(String text) {
        if (text == null) return false;
        return text.contains("opinion") || text.contains("democrat") ||
               text.contains("republican") || text.contains("party") ||
               text.contains("politician") || text.contains("editorial") ||
               text.contains("op-ed") || text.contains("commentary") ||
               text.contains("think tank") || text.contains("rumor");
    }

    private static EconomicCalendarAPI.CalendarEvent findMatchingEvent(
            String title, String content, long timestamp) {

        String combined = (title + " " + content).toLowerCase();
        long window = 10 * 60 * 1000; // Fenêtre stricte de ±10 minutes

        for (EconomicCalendarAPI.CalendarEvent event : upcomingEvents.values()) {
            if (event == null || event.timestamp == null || event.indicator == null) continue;
            
            long eventTime = parseTimestamp(event.timestamp);

            if (Math.abs(eventTime - timestamp) < window) {
                String indicator = event.indicator.toLowerCase();
                String country = event.country != null ? event.country : "";

                if (combined.contains(indicator) || matchesIndicatorKeywords(combined, indicator, country)) {
                    return event;
                }
            }
        }
        return null;
    }

    private static boolean matchesIndicatorKeywords(String text, String indicator, String country) {
        if (text == null || indicator == null) return false;
        if (indicator.contains("nfp") || indicator.contains("non-farm")) {
            return text.contains("nfp") || text.contains("non-farm") || text.contains("payroll");
        }
        if (indicator.contains("cpi") || indicator.contains("inflation")) {
            return text.contains("cpi") || text.contains("inflation") || text.contains("pce");
        }
        if (indicator.contains("gdp") || indicator.contains("growth")) {
            return text.contains("gdp") || text.contains("gross domestic");
        }
        if (indicator.contains("fed") || indicator.contains("fomc") || indicator.contains("rate")) {
            return text.contains("fed") || text.contains("rate") || text.contains("fomc") || text.contains("powell");
        }
        return false;
    }

    private static int calculateBreakingNewsConfidence(String title, String content) {
        int score = 40;
        String lower = ((title != null ? title : "") + " " + (content != null ? content : "")).toLowerCase();

        if (lower.contains("breaking")) score += 25;
        if (lower.contains("urgent") || lower.contains("alert")) score += 20;
        if (lower.contains("fxhedgers") || lower.contains("deltaone")) score += 25;
        if (lower.contains("federal reserve") || lower.contains("fomc")) score += 20;
        if (content != null && content.matches(".*\\d+\\.\\d+%.*")) score += 15;

        return Math.min(100, score);
    }

    private static String createEventKey(String indicator, String timestamp) {
        if (indicator == null || timestamp == null) {
            return UUID.randomUUID().toString();
        }
        return indicator.toLowerCase().replaceAll("[^a-z0-9]", "_") + "_" + timestamp;
    }

    private static long parseTimestamp(String timestamp) {
        if (timestamp == null) return System.currentTimeMillis();
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

    public static void preloadCalendar() {
        try {
            List<EconomicCalendarAPI.CalendarEvent> events = EconomicCalendarAPI.fetchUpcomingEvents(24);
            if (events == null) return;
            upcomingEvents.clear();

            for (EconomicCalendarAPI.CalendarEvent event : events) {
                if (event == null) continue;
                String key = createEventKey(event.indicator, event.timestamp);
                upcomingEvents.put(key, event);
            }
        } catch (Exception e) {}
    }
}
