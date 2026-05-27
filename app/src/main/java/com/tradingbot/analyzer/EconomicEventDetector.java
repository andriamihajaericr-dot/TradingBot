package com.tradingbot.analyzer;

import java.util.Locale;

public class EconomicEventDetector {

    public static class DetectedEvent {
        public String eventType;
        public String description;
        public String impact; // Contient la chaîne combinée (ex: "Haute Volatilité (Biais Haussier)")

        public DetectedEvent(String eventType, String description, String impact) {
            this.eventType   = eventType;
            this.description = description;
            this.impact      = impact;
        }

        /**
         * Extrait l'impact brut (Utile pour EventDatabase et les filtres de poids)
         */
        public String getRawImpact() {
            if (impact == null) return "NEUTRE";
            if (impact.contains("Haute Volatilité")) return "HIGH";
            if (impact.contains("Moyenne Volatilité")) return "MEDIUM";
            if (impact.contains("Choc Géopolitique")) return "HIGH";
            return "LOW";
        }

        /**
         * Extrait le biais directionnel de manière isolée pour l'analyse algorithmique
         */
        public String getDirectionalBias() {
            if (impact == null) return "NEUTRE";
            if (impact.contains("Biais Haussier")) return "HAWKISH";
            if (impact.contains("Biais Baissier")) return "DOVISH";
            return "NEUTRE";
        }
    }

    public static DetectedEvent detectEvent(String title, String text) {
        // Utilisation constante de Locale.US
        String unified = (title + " " + text).toUpperCase(Locale.US).trim();

        String eventType   = "CORE-MACRO";
        String description = "Analyse Flash Institutionnelle";
        String impact      = "Neutre";

        // ── 1. CLASSIFICATION HIÉRARCHIQUE (Du plus spécifique au plus général) ──

        // Fed & Politique Monétaire US
        if (containsAny(unified, "FEDERAL RESERVE", "FED CHAIR", "FOMC", "FED ", "POWELL", "WARSH", "BARKIN", "GOOLSBEE",
                       "HAMMACK", "WALLER", "WILLIAMS", "KUGLER")) {
            eventType   = "FED-MONETARY-POLICY";
            description = "Décision / Discours Réserve Fédérale (USA)";
            impact      = "Haute Volatilité";

        // Inflation US
        } else if (containsAny(unified, "CORE CPI", "CORE PCE", "CPI ", "PCE", "PPI", "INFLATION")) {
            eventType   = "INFLATION-DATA";
            description = "Données d'Inflation (CPI / PCE / PPI)";
            impact      = "Haute Volatilité";

        // Emploi US
        } else if (containsAny(unified, "NON-FARM PAYROLLS", "JOBLESS CLAIMS", "INITIAL CLAIMS", "NFP", "PAYROLLS", "UNEMPLOYMENT", "ADP", "JOLTS")) {
            eventType   = "EMPLOYMENT-REPORT";
            description = "Données du Marché de l'Emploi US";
            impact      = "Haute Volatilité";

        // Banques Centrales Étrangères
        } else if (containsAny(unified, "INTEREST RATE", "RATE DECISION", "ECB", "LAGARDE", "BOE", "BAILEY", "BOJ", "UEDA",
                       "BOC", "MACKLEM", "RBA", "BULLOCK")) {
            eventType   = "CENTRAL-BANK-RATE";
            description = "Taux / Politique Monétaire Banque Centrale";
            impact      = "Haute Volatilité";

        // Géopolitique — Moyen-Orient
        } else if (containsAny(unified, "HORMUZ STRAIT", "ISRAEL", "IRAN", "HEZBOLLAH", "HOUTHI", "HORMUZ", "GAZA", "LEBANON")) {
            eventType   = "GEO-MIDDLE-EAST";
            description = "Événement Géopolitique — Moyen-Orient";
            impact      = "Choc Géopolitique USOIL/GOLD";

        // Géopolitique — Europe de l'Est
        } else if (containsAny(unified, "UKRAINE", "RUSSIA", "PUTIN", "ZELENSKY", "NATO")) {
            eventType   = "GEO-EUROPE-EST";
            description = "Événement Géopolitique — Europe de l'Est";
            impact      = "Choc Géopolitique EUR/USOIL";

        // Géopolitique — Asie-Pacifique
        } else if (containsAny(unified, "TAIWAN STRAIT", "XI JINPING", "CHINA", "TAIWAN", "TSMC")) {
            eventType   = "GEO-ASIA-PACIFIC";
            description = "Événement Géopolitique — Asie-Pacifique";
            impact      = "Choc Géopolitique AUD/NASDAQ";

        // Macro secondaire
        } else if (containsAny(unified, "RETAIL SALES", "CONSUMER CONFIDENCE", "CONSUMER SENTIMENT", "GDP", "PMI", "ISM", "MICHIGAN")) {
            eventType   = "ECONOMIC-GROWTH-DATA";
            description = "Données Macroéconomiques Secondaires";
            impact      = "Moyenne Volatilité";
        }

        // ── 2. EXTRACTION DU BIAIS FONDAMENTAL ──
        if (containsAny(unified, "HIGHER THAN EXPECTED", "BEATS ESTIMATES", "ABOVE FORECAST",
                       "ABOVE EXPECTATIONS", "BETTER THAN EXPECTED", "HAWKISH")) {
            if (impact.equals("Neutre")) {
                impact = "Biais Haussier";
            } else {
                impact = impact + " (Biais Haussier)";
            }
        } 
        else if (containsAny(unified, "LOWER THAN EXPECTED", "MISSES ESTIMATES", "BELOW FORECAST",
                            "BELOW EXPECTATIONS", "WORSE THAN EXPECTED", "DOVISH")) {
            if (impact.equals("Neutre")) {
                impact = "Biais Baissier";
            } else {
                impact = impact + " (Biais Baissier)";
            }
        } 
        else if (containsAny(unified, "SHOCK", "SURPRISE", "BREAKING", "EMERGENCY")) {
            if (impact.equals("Neutre")) {
                impact = "Forte Impulsion Neutre";
            } else if (!impact.contains("Choc")) {
                impact = "Forte Impulsion - " + impact;
            }
        }

        return new DetectedEvent(eventType, description, impact);
    }

    private static boolean containsAny(String text, String... keywords) {
        if (text == null || keywords == null) return false;
        for (String keyword : keywords) {
            if (keyword != null && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
