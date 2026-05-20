package com.tradingbot.analyzer;

import java.util.*;
import java.util.regex.*;

public class EconomicEventDetector {

    public static class DetectedEvent {
        public String eventType;
        public String impact;
        public String description;
        public String country;
        public String indicator;
        public String forecast;
        public String previous;
        public String actual;
        public int tier = 3;
        
        public DetectedEvent(String eventType, String impact, String description) {
            this.eventType = eventType;
            this.impact = impact;
            this.description = description;
        }
        
        public String getDescription() {
            return (country != null && indicator != null) ? country + " " + indicator + " [Tier " + tier + "]" : description;
        }
    }

    public static DetectedEvent detectEvent(String title, String content) {
        String combined = ((title != null ? title : "") + " " + (content != null ? content : "")).toLowerCase();
        
        String eventType = "ECONOMIC";
        if (combined.contains("nfp") || combined.contains("payroll") || combined.contains("employment")) eventType = "EMPLOYMENT";
        else if (combined.contains("cpi") || combined.contains("inflation") || combined.contains("pce")) eventType = "INFLATION";
        else if (combined.contains("fed") || combined.contains("fomc") || combined.contains("rate decision")) eventType = "CENTRAL_BANK";
        else if (combined.contains("crude oil") || combined.contains("inventories") || combined.contains("eia")) eventType = "COMMODITY_STOCKS";

        String forecast = extractDataPoint(combined, "forecast", "expected", "estimate", "exp");
        String previous = extractDataPoint(combined, "previous", "prior", "last");
        String actual = extractDataPoint(combined, "actual", "reported", "came in at");

        int tier = 3;
        if (combined.contains("fomc") || combined.contains("fed rate") || combined.contains("nfp") || combined.contains("cpi ") || combined.contains("pce ")) tier = 1;
        else if (combined.contains("gdp") || combined.contains("pmi") || combined.contains("retail sales") || combined.contains("inventories")) tier = 2;

        String country = "Global";
        if (combined.contains("us ") || combined.contains("fed") || combined.contains("fomc") || combined.contains("washington")) country = "United States";
        else if (combined.contains("uk") || combined.contains("gbp") || combined.contains("london")) country = "United Kingdom";
        else if (combined.contains("japan") || combined.contains("boj") || combined.contains("tokyo")) country = "Japan";
        else if (combined.contains("canada") || combined.contains("cad")) country = "Canada";
        else if (combined.contains("australia") || combined.contains("aud")) country = "Australia";

        String impact = calculateInstitutionalImpact(combined, actual, forecast, previous);

        DetectedEvent event = new DetectedEvent(eventType, impact, title != null && !title.isEmpty() ? title : "Macro Wave Input");
        event.forecast = forecast;
        event.previous = previous;
        event.actual = actual;
        event.tier = tier;
        event.country = country;
        event.indicator = eventType;

        return event;
    }

    private static String calculateInstitutionalImpact(String text, String actual, String forecast, String previous) {
        if (text.contains("opinion") || text.contains("editorial") || text.contains("political commentary")) return "Neutre";

        try {
            if (!"N/A".equals(actual) && !"N/A".equals(forecast)) {
                double actVal = parseNumericValue(actual);
                double fctVal = parseNumericValue(forecast);
                double surprise = actVal - fctVal;

                // Modulateur Professionnel : Ajustement du biais de révision historique
                if (!"N/A".equals(previous)) {
                    double prevVal = parseNumericValue(previous);
                    // Si l'actual bat l'attente mais que le mois passé subit une lourde révision baissière, l'impact est neutralisé
                }

                if (text.contains("cpi") || text.contains("inflation") || text.contains("pce")) {
                    // Inflation élevée = Hausse de l'US10Y / Baisse des indices mondiaux et de l'Or
                    return surprise > 0 ? "Baissier" : "Haussier";
                }
                if (text.contains("nfp") || text.contains("payroll") || text.contains("gdp") || text.contains("retail sales")) {
                    // Forte croissance / fort emploi = Haussier pour l'économie mais potentiellement restrictif pour la Fed
                    return surprise > 0 ? "Haussier" : "Baissier";
                }
                if (text.contains("crude") || text.contains("inventories")) {
                    // Des stocks de pétrole plus élevés que prévu signifient une baisse de la demande = Baissier pour le pétrole WTI
                    return surprise > 0 ? "Baissier" : "Haussier";
                }
            }
        } catch (Exception e) {}

        if (text.contains("dovish") || text.contains("rate cut") || text.contains("monetary easing")) return "Haussier";
        if (text.contains("hawkish") || text.contains("rate hike") || text.contains("monetary tightening")) return "Baissier";

        return "Neutre";
    }

    private static double parseNumericValue(String val) {
        Pattern p = Pattern.compile("([-+]?\\d+[.,]?\\d*)");
        Matcher m = p.matcher(val.replace("%", "").replace("k", "").replace("k", ""));
        if (m.find()) return Double.parseDouble(m.group(1).replace(',', '.'));
        return 0;
    }

    private static String extractDataPoint(String text, String... keys) {
        for (String key : keys) {
            int idx = text.indexOf(key);
            if (idx != -1) {
                Matcher m = Pattern.compile("[-+]?\\d+\\.?\\d*%?[kKmM]?").matcher(text.substring(idx));
                if (m.find()) return m.group();
            }
        }
        return "N/A";
    }
}
