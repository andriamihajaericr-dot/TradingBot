package com.tradingbot.analyzer;

import java.util.Locale;

public class EconomicEventDetector {

    public static class DetectedEvent {
        public String eventType;
        public String description;
        public String impact;

        public DetectedEvent(String eventType, String description, String impact) {
            this.eventType = eventType;
            this.description = description;
            this.impact = impact;
        }
    }

    /**
     * Analyse le titre et le texte pour classifier l'événement macroéconomique majeur.
     */
    public static DetectedEvent detectEvent(String title, String text) {
        String unified = (title + " " + text).toUpperCase(Locale.ROOT);
        
        String eventType = "CORE-MACRO";
        String description = "Analyse Flash Institutionnelle";
        String impact = "Neutre";

        // 1. Identification de la nature de l'événement
        if (unified.contains("FOMC") || unified.contains("FED ") || unified.contains("POWELL")) {
            eventType = "FED-MONETARY-POLICY";
            description = "Décision / Discours de la Réserve Fédérale (USA)";
            impact = "Haute Volatilité";
        } else if (unified.contains("CPI ") || unified.contains("INFLATION") || unified.contains("CORE CPI")) {
            eventType = "INFLATION-DATA";
            description = "Indice des Prix à la Consommation (CPI)";
            impact = "Haute Volatilité";
        } else if (unified.contains("NFP") || unified.contains("NON-FARM PAYROLLS") || unified.contains("UNEMPLOYMENT")) {
            eventType = "EMPLOYMENT-REPORT";
            description = "Rapport Mensuel de l'Emploi US (NFP)";
            impact = "Haute Volatilité";
        } else if (unified.contains("INTEREST RATE") || unified.contains("TAUX D'INTÉRÊT") || unified.contains("ECB") || unified.contains("BCE")) {
            eventType = "CENTRAL-BANK-RATE";
            description = "Taux d'intérêt de la Banque Centrale";
            impact = "Haute Volatilité";
        } else if (unified.contains("PMI") || unified.contains("MANUFACTURING PMI") || unified.contains("SERVICES PMI")) {
            eventType = "ECONOMIC-GROWTH-PMI";
            description = "Indice des Directeurs d'Achat (PMI)";
            impact = "Moyenne Volatilité";
        } else if (unified.contains("CRUDE") || unified.contains("EIA") || unified.contains("STOCKS D'ESSENCE")) {
            eventType = "ENERGY-RESERVES";
            description = "Rapport Hebdomadaire des Stocks de Pétrole EIA";
            impact = "Moyenne Volatilité";
        }

        // 2. Détection linguistique du biais directionnel brut (Filtre algorithmique rapide)
        // Ce filtre est complété et sublimé ensuite en arrière-plan par l'intelligence artificielle Groq Llama 3.3
        if (unified.contains("HIGHER THAN EXPECTED") || unified.contains("ABOVE FORECAST") || unified.contains("BEATS ESTIMATES") || unified.contains("HAUSSIER")) {
            impact = "Biais Haussier Détecté";
        } else if (unified.contains("LOWER THAN EXPECTED") || unified.contains("BELOW FORECAST") || unified.contains("MISSES ESTIMATES") || unified.contains("BAISSIER")) {
            impact = "Biais Baissier Détecté";
        } else if (unified.contains("SHOCK") || unified.contains("SURPRISE") || unified.contains("BREAKING")) {
            if ("Neutre".equals(impact)) {
                impact = "Forte Impulsion Neutre";
            }
        }

        return new DetectedEvent(eventType, description, impact);
    }
}
