// EconomicEventDetector.java
package com.tradingbot.analyzer;

import org.json.JSONObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EconomicEventDetector {
    
    // Mots-clés pour détecter les événements économiques
    private static final Pattern INDICATOR_PATTERN = Pattern.compile(
        "(CPI|NFP|GDP|PMI|PPI|Retail Sales|Unemployment|Interest Rate|FOMC|" +
        "Core CPI|PCE|Jobless Claims|Housing Starts|Trade Balance|" +
        "Industrial Production|Consumer Confidence|ISM|ZEW|IFO)",
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern VALUE_PATTERN = Pattern.compile(
        "(Forecast|Expected|Previous|Actual|Consensus)\\s*:?\\s*([\\d.]+[%MBK]?)",
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern COUNTRY_PATTERN = Pattern.compile(
        "\\b(US|USA|United States|UK|Britain|EU|Europe|Japan|China|Canada|Australia|Germany|France)\\b",
        Pattern.CASE_INSENSITIVE
    );
    
    public static EconomicEvent parseEconomicEvent(String title, String content) {
        String fullText = title + " " + content;
        
        // Vérifier si c'est un événement économique
        if (!isEconomicEvent(fullText)) {
            return null;
        }
        
        EconomicEvent event = new EconomicEvent();
        
        // Extraire l'indicateur
        Matcher indicatorMatcher = INDICATOR_PATTERN.matcher(fullText);
        if (indicatorMatcher.find()) {
            event.indicator = indicatorMatcher.group(1);
        }
        
        // Extraire le pays
        Matcher countryMatcher = COUNTRY_PATTERN.matcher(fullText);
        if (countryMatcher.find()) {
            event.country = countryMatcher.group(1);
        }
        
        // Extraire les valeurs
        Matcher valueMatcher = VALUE_PATTERN.matcher(fullText);
        while (valueMatcher.find()) {
            String type = valueMatcher.group(1).toLowerCase();
            String value = valueMatcher.group(2);
            
            if (type.contains("forecast") || type.contains("expected") || type.contains("consensus")) {
                event.forecast = value;
            } else if (type.contains("previous")) {
                event.previous = value;
            } else if (type.contains("actual")) {
                event.actual = value;
            }
        }
        
        // Déterminer l'impact
        event.impact = determineImpact(event.indicator, fullText);
        
        return event;
    }
    
    private static boolean isEconomicEvent(String text) {
        String lower = text.toLowerCase();
        return lower.contains("forecast") || lower.contains("expected") ||
               lower.contains("previous") || lower.contains("actual") ||
               lower.contains("release") || lower.contains("data") ||
               INDICATOR_PATTERN.matcher(text).find();
    }
    
    private static String determineImpact(String indicator, String text) {
        if (indicator == null) return "Medium";
        
        String lower = text.toLowerCase();
        
        // Événements à fort impact
        if (indicator.matches("(?i)(NFP|CPI|FOMC|Interest Rate|GDP)") ||
            lower.contains("breaking") || lower.contains("urgent")) {
            return "High";
        }
        
        // Événements à faible impact
        if (indicator.matches("(?i)(Jobless Claims|Housing Starts)")) {
            return "Low";
        }
        
        return "Medium";
    }
    
    public static class EconomicEvent {
        public String indicator;
        public String country;
        public String forecast;
        public String previous;
        public String actual;
        public String impact = "Medium";
        
        public boolean isComplete() {
            return indicator != null && country != null;
        }
        
        public JSONObject toJSON() {
            try {
                JSONObject json = new JSONObject();
                json.put("indicator", indicator != null ? indicator : "Unknown");
                json.put("country", country != null ? country : "Unknown");
                json.put("forecast", forecast != null ? forecast : "N/A");
                json.put("previous", previous != null ? previous : "N/A");
                json.put("actual", actual != null ? actual : "N/A");
                json.put("impact", impact);
                return json;
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }
}
