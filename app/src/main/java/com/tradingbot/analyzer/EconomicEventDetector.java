package com.tradingbot.analyzer;

import java.util.Locale;

public class EconomicEventDetector {

    public static class DetectedEvent {
        public String eventType;
        public String description;
        public String impact;

        public DetectedEvent(String eventType, String description, String impact) {
            this.eventType   = eventType;
            this.description = description;
            this.impact      = impact;
        }
    }

    public static DetectedEvent detectEvent(String title, String text) {
        String unified = (title + " " + text).toUpperCase(Locale.ROOT).trim();

        String eventType   = "CORE-MACRO";
        String description = "Analyse Flash Institutionnelle";
        String impact      = "Neutre";

        // ── CLASSIFICATION HIÉRARCHIQUE (plus stricte) ─────────────

        // 1. RANG SUPRÊME - Fed & Politique Monétaire US
        if (containsAny(unified, "FOMC", "FED ", "POWELL", "WARSH", "BARKIN", "GOOLSBEE", 
                       "HAMMACK", "WALLER", "WILLIAMS", "KUGLER", "FEDERAL RESERVE", "FED CHAIR")) {
            eventType   = "FED-MONETARY-POLICY";
            description = "Décision / Discours Réserve Fédérale (USA)";
            impact      = "Haute Volatilité";
        }

        // 2. Inflation US
        else if (containsAny(unified, "CPI ", "CORE CPI", "PCE", "CORE PCE", "PPI", "INFLATION")) {
            eventType   = "INFLATION-DATA";
            description = "Données d'Inflation (CPI / PCE / PPI)";
            impact      = "Haute Volatilité";
        }

        // 3. Emploi US
        else if (containsAny(unified, "NFP", "NON-FARM", "PAYROLLS", "UNEMPLOYMENT", "JOBLESS CLAIMS", 
                            "ADP", "JOLTS")) {
            eventType   = "EMPLOYMENT-REPORT";
            description = "Données du Marché de l'Emploi US";
            impact      = "Haute Volatilité";
        }

        // 4. Banques Centrales Étrangères
        else if (containsAny(unified, "ECB", "LAGARDE", "BOE", "BAILEY", "BOJ", "UEDA", 
                            "BOC", "MACKLEM", "RBA", "BULLOCK", "INTEREST RATE", "RATE DECISION")) {
            eventType   = "CENTRAL-BANK-RATE";
            description = "Taux / Politique Monétaire Banque Centrale";
            impact      = "Haute Volatilité";
        }

        // 5. Géopolitique (plus précis)
        else if (containsAny(unified, "ISRAEL", "IRAN", "HEZBOLLAH", "HOUTHI", "HORMUZ", "GAZA", "LEBANON")) {
            eventType   = "GEO-MIDDLE-EAST";
            description = "Événement Géopolitique — Moyen-Orient";
            impact      = "Choc Géopolitique USOIL/GOLD";
        }
        else if (containsAny(unified, "UKRAINE", "RUSSIA", "PUTIN", "ZELENSKY", "NATO")) {
            eventType   = "GEO-EUROPE-EST";
            description = "Événement Géopolitique — Europe de l'Est";
            impact      = "Choc Géopolitique EUR/USOIL";
        }
        else if (containsAny(unified, "CHINA", "TAIWAN", "XI JINPING", "TSMC")) {
            eventType   = "GEO-ASIA-PACIFIC";
            description = "Événement Géopolitique — Asie-Pacifique";
            impact      = "Choc Géopolitique AUD/NASDAQ";
        }

        // 6. Autres macro (secondaire)
        else if (containsAny(unified, "GDP", "PMI", "ISM", "RETAIL SALES", "MICHIGAN", "CONSUMER CONFIDENCE")) {
            eventType   = "ECONOMIC-GROWTH-DATA";
            description = "Données Macroéconomiques Secondaires";
            impact      = "Moyenne Volatilité";
        }

        // ── BIAS DIRECTIONNEL (amélioré) ───────────────────────────
        if (containsAny(unified, "HIGHER THAN EXPECTED", "BEATS ESTIMATES", "ABOVE FORECAST", 
                       "ABOVE EXPECTATIONS", "BETTER THAN EXPECTED", "HAWKISH")) {
            impact = "Biais Haussier Détecté";
        } 
        else if (containsAny(unified, "LOWER THAN EXPECTED", "MISSES ESTIMATES", "BELOW FORECAST",
                            "BELOW EXPECTATIONS", "WORSE THAN EXPECTED", "DOVISH")) {
            impact = "Biais Baissier Détecté";
        } 
        else if (containsAny(unified, "SHOCK", "SURPRISE", "BREAKING", "EMERGENCY")) {
            if ("Neutre".equals(impact)) {
                impact = "Forte Impulsion Neutre";
            }
        }

        return new DetectedEvent(eventType, description, impact);
    }

    // Méthode utilitaire pour éviter les répétitions
    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
