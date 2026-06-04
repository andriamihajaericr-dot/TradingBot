package com.tradingbot.analyzer;

import java.util.Locale;

public class EconomicEventDetector {

    public static class DetectedEvent {
        public String eventType;
        public String description;
        public String impact; // Chaîne combinée descriptive (ex: "Haute Volatilité (Biais Haussier)")

        public DetectedEvent(String eventType, String description, String impact) {
            this.eventType   = eventType;
            this.description = description;
            this.impact      = impact;
        }

        /**
         * Extrait l'impact brut normalisé pour l'alignement strict avec EventDatabase
         */
        public String getRawImpact() {
    if (impact == null) return "NEUTRE";

    // Rang Suprême — Volatilité confirmée institutionnelle
    if (impact.contains("Haute Volatilité") || impact.contains("Choc Géopolitique")) {
        return "HIGH";
    }
    // Rang Intermédiaire — Impulsion non confirmée ou volatilité modérée
    if (impact.contains("Moyenne Volatilité") || impact.contains("Forte Impulsion")) {
        return "MEDIUM";
    }
    // Rang Faible — Biais directionnel seul sans volatilité confirmée
    if (impact.contains("Biais Haussier") || impact.contains("Biais Baissier")) {
        return "LOW";
    }
    return "NEUTRE";
    }

        /**
         * Extrait le biais directionnel de manière isolée pour les décisions algorithmiques de l'IA
         */
        public String getDirectionalBias() {
            if (impact == null) return "NEUTRE";
            if (impact.contains("Biais Haussier") || impact.contains("HAWKISH")) return "HAWKISH";
            if (impact.contains("Biais Baissier") || impact.contains("DOVISH")) return "DOVISH";
            return "NEUTRE";
        }
    }

    public static DetectedEvent detectEvent(String title, String text) {
        // Remplacement de sécurité des sauts de ligne et forçage de casse universel via Locale.ROOT
        String cleanTitle = (title != null) ? title : "";
        String cleanText = (text != null) ? text : "";
        String combined = (cleanTitle + " " + cleanText + " ").toUpperCase(Locale.ROOT);
        String unified  = combined.replaceAll("[\\r\\n]+", " ");

        String eventType   = "CORE-MACRO";
        String description = "Analyse Flash Institutionnelle";
        String impact      = "Neutre";

        // ── 1. CLASSIFICATION HIÉRARCHIQUE (Du plus spécifique au plus général) ──

        // Fed & Politique Monétaire US (Rang Suprême - Poids 5)
        if (containsAny(unified, "FEDERAL RESERVE", "FED CHAIR", "FOMC MINUTES", "FOMC", "FED ", "POWELL", 
                       "WARSH", "BARKIN", "GOOLSBEE", "HAMMACK", "WALLER", "WILLIAMS", "KUGLER", "RATE STANDS")) {
            eventType   = "FED-MONETARY-POLICY";
            description = "Décision / Discours Réserve Fédérale (USA)";
            impact      = "Haute Volatilité";

        // Inflation US (Rang Suprême - Poids 5)
        } else if (containsAny(unified, "CORE CPI", "CORE PCE", "CPI ", "PCE", "PPI", "INFLATION")) {
            eventType   = "INFLATION-DATA";
            description = "Données d'Inflation (CPI / PCE / PPI)";
            impact      = "Haute Volatilité";

        // Rapport sur l'Emploi US (Poids 4)
        } else if (containsAny(unified, "NON-FARM PAYROLLS", "JOBLESS CLAIMS", "INITIAL CLAIMS", "NFP", "PAYROLLS", "UNEMPLOYMENT", "ADP", "JOLTS")) {
            eventType   = "EMPLOYMENT-REPORT";
            description = "Données du Marché de l'Emploi US";
            impact      = "Haute Volatilité";

        // Banques Centrales Étrangères (Poids 4 - Protection EURUSD, USDJPY, GBPUSD, AUDUSD, USDCAD)
        } else if (containsAny(unified, "INTEREST RATE", "RATE DECISION", "ECB", "LAGARDE", "BOE", "BAILEY", "BOJ", "UEDA",
                       "BOC", "MACKLEM", "RBA", "BULLOCK")) {
            eventType   = "CENTRAL-BANK-RATE";
            description = "Taux / Politique Monétaire Banque Centrale";
            impact      = "Haute Volatilité";

        // Géopolitique — Moyen-Orient (Sécurisation USOIL/GOLD - Poids 4)
        } else if (containsAny(unified, "HORMUZ STRAIT", "RED SEA", "ISRAEL", "IRAN", "HEZBOLLAH", "HOUTHI", "HORMUZ", "GAZA", "LEBANON")) {
            eventType   = "GEO-MIDDLE-EAST";
            description = "Événement Géopolitique — Moyen-Orient";
            impact      = "Choc Géopolitique USOIL/GOLD";

        // Géopolitique — Europe de l'Est (Poids 4)
        } else if (containsAny(unified, "UKRAINE", "RUSSIA", "PUTIN", "ZELENSKY", "NATO")) {
            eventType   = "GEO-EUROPE-EST";
            description = "Événement Géopolitique — Europe de l'Est";
            impact      = "Choc Géopolitique EUR/USOIL";

        // Géopolitique — Asie-Pacifique (Sécurisation AUD/NASDAQ - Poids 4)
        } else if (containsAny(unified, "TAIWAN STRAIT", "XI JINPING", "CHINA", "TAIWAN", "TSMC")) {
            eventType   = "GEO-ASIA-PACIFIC";
            description = "Événement Géopolitique — Asie-Pacifique";
            impact      = "Choc Géopolitique AUD/NASDAQ";

        // Macro secondaire (Poids 2)
        } else if (containsAny(unified, "RETAIL SALES", "CONSUMER CONFIDENCE", "CONSUMER SENTIMENT", "GDP", "PMI", "ISM", "MICHIGAN")) {
            eventType   = "ECONOMIC-GROWTH-DATA";
            description = "Données Macroéconomiques Secondaires";
            impact      = "Moyenne Volatilité";
        }

        // ── 2. EXTRACTION ET ACCUMULATION DU BIAIS DIRECTIONNEL FONDAMENTAL ──
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
            } else if (!impact.contains("Choc") && !impact.contains("Forte Impulsion")) {
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
