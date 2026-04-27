// EconomicEventDetector.java
package com.tradingbot.analyzer;

import org.json.JSONObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EconomicEventDetector {
    
    // Patterns pour événements économiques
    private static final Pattern INDICATOR_PATTERN = Pattern.compile(
        "(CPI|NFP|GDP|PMI|PPI|Retail Sales|Unemployment|Interest Rate|FOMC|" +
        "Core CPI|PCE|Jobless Claims|Housing Starts|Trade Balance|" +
        "Industrial Production|Consumer Confidence|ISM|ZEW|IFO)",
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern VALUE_PATTERN = Pattern.compile(
        "(Forecast|Expected|Previous|Actual|Consensus)\\s*:?\\s*([+-]?[\\d.]+[%MBK]?)",
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern COUNTRY_PATTERN = Pattern.compile(
        "\\b(US|USA|United States|UK|Britain|EU|Europe|Japan|China|Canada|Australia|Germany|France)\\b",
        Pattern.CASE_INSENSITIVE
    );
    
    // Patterns pour événements pétroliers
    private static final Pattern OIL_INVENTORY_PATTERN = Pattern.compile(
        "(EIA|API)\\s+(?:Crude)?\\s*(?:Oil)?\\s*(?:Inventory|Stockpile)[:\\s]*([+-][\\d.]+)\\s*(M|million)?",
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern OPEC_PATTERN = Pattern.compile(
        "OPEC\\+?\\s+(?:cut|increase|reduce|boost)\\s+(?:production)?[:\\s]*([\\d.]+)?\\s*(M|million)?",
        Pattern.CASE_INSENSITIVE
    );
    
    private static final Pattern OIL_PRICE_PATTERN = Pattern.compile(
        "(WTI|Brent)[:\\s@]*\\$?([\\d.]+)",
        Pattern.CASE_INSENSITIVE
    );
    
    public static DetectedEvent detectEvent(String title, String content) {
        String fullText = title + " " + content;
        
        DetectedEvent event = new DetectedEvent();
        
        // 1. Vérifier si c'est du pétrole
        if (isOilEvent(fullText)) {
            parseOilDetails(event, fullText);
            event.eventType = "oil";
        }
        // 2. Sinon vérifier si c'est économique
        else if (isEconomicEvent(fullText)) {
            parseEconomicDetails(event, fullText);
            event.eventType = "economic";
        }
        // 3. Sinon c'est une news générale
        else {
            event.eventType = "news";
        }
        
        // Déterminer l'IMPACT (Haussier/Baissier/Neutre)
        event.impact = determineImpact(event, fullText);
        
        return event;
    }
    
    private static void parseOilDetails(DetectedEvent event, String text) {
        // Inventaires EIA/API
        Matcher inventoryMatcher = OIL_INVENTORY_PATTERN.matcher(text);
        if (inventoryMatcher.find()) {
            event.indicator = inventoryMatcher.group(1) + " Inventory";
            event.actual = inventoryMatcher.group(2);
            event.country = "US";
        }
        
        // OPEC
        Matcher opecMatcher = OPEC_PATTERN.matcher(text);
        if (opecMatcher.find()) {
            event.indicator = "OPEC Production";
            event.actual = opecMatcher.group(1);
            event.country = "OPEC";
        }
        
        // Prix
        Matcher priceMatcher = OIL_PRICE_PATTERN.matcher(text);
        if (priceMatcher.find()) {
            event.indicator = priceMatcher.group(1) + " Crude";
            event.actual = priceMatcher.group(2);
        }
    }
    
    private static void parseEconomicDetails(DetectedEvent event, String text) {
        // Indicateur
        Matcher indicatorMatcher = INDICATOR_PATTERN.matcher(text);
        if (indicatorMatcher.find()) {
            event.indicator = indicatorMatcher.group(1);
        }
        
        // Pays
        Matcher countryMatcher = COUNTRY_PATTERN.matcher(text);
        if (countryMatcher.find()) {
            event.country = countryMatcher.group(1);
        }
        
        // Valeurs
        Matcher valueMatcher = VALUE_PATTERN.matcher(text);
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
    }
    
    private static String determineImpact(DetectedEvent event, String text) {
        String lower = text.toLowerCase();
        
        // === PÉTROLE ===
        if (event.eventType.equals("oil")) {
            // Inventaires EIA/API
            if (event.indicator != null && event.indicator.contains("Inventory") && 
                event.actual != null) {
                try {
                    double change = Double.parseDouble(event.actual.replace("+", ""));
                    // Augmentation inventaires = baissier pour pétrole
                    if (Math.abs(change) >= 2.0) { // >= 2M barils = significatif
                        return change > 0 ? "Baissier" : "Haussier";
                    }
                } catch (Exception e) {}
            }
            
            // OPEC coupe production = haussier
            if (lower.contains("opec") && (lower.contains("cut") || lower.contains("reduce"))) {
                return "Haussier";
            }
            
            // OPEC augmente production = baissier
            if (lower.contains("opec") && (lower.contains("increase") || lower.contains("boost"))) {
                return "Baissier";
            }
            
            // Géopolitique pétrolière
            if (isOilGeopolitical(lower)) {
                if (lower.contains("sanction") || lower.contains("attack") || 
                    lower.contains("conflict")) {
                    return "Haussier"; // Risque = hausse pétrole
                }
            }
        }
        
        // === ÉCONOMIQUE ===
        else if (event.eventType.equals("economic")) {
            // Comparer actual vs forecast
            if (event.actual != null && event.forecast != null) {
                try {
                    double actual = parseNumericValue(event.actual);
                    double forecast = parseNumericValue(event.forecast);
                    double diff = actual - forecast;
                    
                    // Seuil de significativité
                    if (Math.abs(diff) < 0.1) {
                        return "Neutre"; // Différence trop faible
                    }
                    
                    // Analyser selon l'indicateur
                    String indicator = event.indicator != null ? 
                        event.indicator.toUpperCase() : "";
                    
                    // Inflation (CPI, PPI) supérieure = baissier (risque hausse taux)
                    if (indicator.contains("CPI") || indicator.contains("PPI") || 
                        indicator.contains("INFLATION")) {
                        return diff > 0 ? "Baissier" : "Haussier";
                    }
                    
                    // Emploi (NFP) supérieur = haussier
                    if (indicator.contains("NFP") || indicator.contains("EMPLOYMENT")) {
                        return diff > 0 ? "Haussier" : "Baissier";
                    }
                    
                    // GDP supérieur = haussier
                    if (indicator.contains("GDP")) {
                        return diff > 0 ? "Haussier" : "Baissier";
                    }
                    
                    // Chômage supérieur = baissier
                    if (indicator.contains("UNEMPLOYMENT") || indicator.contains("JOBLESS")) {
                        return diff > 0 ? "Baissier" : "Haussier";
                    }
                    
                    // PMI/ISM supérieur = haussier
                    if (indicator.contains("PMI") || indicator.contains("ISM")) {
                        return diff > 0 ? "Haussier" : "Baissier";
                    }
                    
                } catch (Exception e) {}
            }
        }
        
        // === NEWS GÉNÉRALES ===
        else {
            // Guerre / conflit = haussier pour or, baissier pour actions
            if (lower.contains("war") || lower.contains("invasion") || 
                lower.contains("nuclear") || lower.contains("missile")) {
                return "Baissier"; // Général = risk-off
            }
            
            // Fed hawkish = baissier
            if (lower.contains("fed") && (lower.contains("rate hike") || 
                lower.contains("hawkish") || lower.contains("tighten"))) {
                return "Baissier";
            }
            
            // Fed dovish = haussier
            if (lower.contains("fed") && (lower.contains("rate cut") || 
                lower.contains("dovish") || lower.contains("pause"))) {
                return "Haussier";
            }
        }
        
        return "Neutre";
    }
    
    private static boolean isOilEvent(String text) {
        String lower = text.toLowerCase();
        return (lower.contains("oil") || lower.contains("crude") || 
                lower.contains("wti") || lower.contains("brent") ||
                lower.contains("opec") || lower.contains("eia") ||
                lower.contains("petroleum") || lower.contains("barrel")) &&
               !lower.contains("olive oil"); // Exclure huile d'olive !
    }
    
    private static boolean isEconomicEvent(String text) {
        return text.matches("(?i).*(forecast|expected|actual|previous|consensus|release).*") &&
               INDICATOR_PATTERN.matcher(text).find();
    }
    
    private static boolean isOilGeopolitical(String lower) {
        boolean hasProducer = lower.contains("russia") || lower.contains("saudi") ||
                             lower.contains("iran") || lower.contains("iraq") ||
                             lower.contains("venezuela");
        
        boolean hasEvent = lower.contains("sanction") || lower.contains("war") ||
                          lower.contains("attack") || lower.contains("conflict");
        
        return hasProducer && hasEvent;
    }
    
    private static double parseNumericValue(String value) {
        // Retirer %, M, B, K et parser
        String cleaned = value.replaceAll("[%MBK]", "").trim();
        return Double.parseDouble(cleaned);
    }
    
    public static class DetectedEvent {
        public String eventType; // "economic", "oil", "news"
        public String indicator;
        public String country;
        public String forecast;
        public String previous;
        public String actual;
        public String impact; // "Haussier", "Baissier", "Neutre"
        
        public boolean shouldNotify() {
            // RÈGLE STRICTE: notifier SEULEMENT si impact = Haussier ou Baissier
            return "Haussier".equals(impact) || "Baissier".equals(impact);
        }
        
        public String getDescription() {
            if (indicator != null) {
                return indicator + (country != null ? " (" + country + ")" : "");
            }
            return "Événement " + eventType;
        }
    }
}
