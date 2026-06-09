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
        // ── Warsh — Futur Chair Fed : poids supérieur à tout autre membre ──
if (containsAny(unified, "WARSH", "KEVIN WARSH")) {
    eventType   = "FED-WARSH-SIGNAL";
    description = "Kevin Warsh (Futur Chair Fed) — Signal Politique Monétaire Majeur";
    impact      = "Haute Volatilité";

} else if (containsAny(unified, "FEDERAL RESERVE", "FED CHAIR", "FOMC MINUTES",
               "FOMC", "FED ", "POWELL", "BARKIN", "GOOLSBEE",
               "HAMMACK", "WALLER", "WILLIAMS", "KUGLER", "RATE STANDS")) {
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
            // Ajouter dans detectEvent — avant "Macro secondaire"
        } else if (containsAny(unified, "TARIFF", "TARIFFS", "TRADE WAR", "TRADE DEAL", "IMPORT TAX", "CUSTOMS DUTY", "SANCTIONS", "EMBARGO", "TRADE AGREEMENT", "SECTION 301", "SECTION 232")) {
            eventType   = "TRADE-TARIFF";
            description = "Tarifs Douaniers / Guerre Commerciale";
            impact      = "Haute Volatilité";
            
        } else if (containsAny(unified, "REVISED TO", "REVISED UP", "REVISED DOWN", "REVISION", "UPWARD REVISION", "DOWNWARD REVISION", "PRIOR REVISED", "PREVIOUS REVISED")) {
            eventType   = "DATA-REVISION";
            description = "Révision de Donnée Macro — Impact sur Sentiment";
            impact      = "Moyenne Volatilité";
        // Macro secondaire (Poids 2)
        // ── ISM Services / Manufacturing — Leading Indicator US (Poids 4) ──
// ── GDP Advance — Rang Suprême Trimestriel (Poids 4) ──
} else if (containsAny(unified, "GDP ADVANCE", "ADVANCE GDP",
           "GDP GROWTH", "GROSS DOMESTIC PRODUCT", "GDP QOQ",
           "GDP YOY", "GDP ANNUALIZED", "GDP FLASH")) {
    eventType   = "GDP-ADVANCE";
    description = "GDP Advance — Indicateur Récession/Expansion US";
    impact      = "Haute Volatilité";

} else if (containsAny(unified, "ISM SERVICES", "ISM NON-MANUFACTURING",
           "ISM MANUFACTURING", "ISM REPORT", "ISM PMI")) {
    eventType   = "ISM-INDICATOR";
    description = "ISM — Baromètre Industrie/Services US";
    impact      = "Haute Volatilité";

// ── PMI Flash / Preliminary — Leading Indicator (Poids 3) ──
} else if (containsAny(unified, "PMI FLASH", "FLASH PMI", "PMI PRELIMINARY",
           "PRELIMINARY PMI", "COMPOSITE PMI", "SERVICES PMI",
           "MANUFACTURING PMI", "PMI MANUFACTURING", "PMI SERVICES")) {
    eventType   = "PMI-FLASH";
    description = "PMI Flash — Indicateur Avancé Croissance";
    impact      = "Moyenne Volatilité (Forte Impulsion)";

// ── Michigan Sentiment Preliminary — Proxy Inflation Fed (Poids 3) ──
} else if (containsAny(unified, "MICHIGAN", "CONSUMER SENTIMENT PREL",
           "SENTIMENT PRELIMINARY", "SENTIMENT PREL", "UOM SENTIMENT",
           "UNIVERSITY OF MICHIGAN")) {
    eventType   = "MICHIGAN-SENTIMENT";
    description = "Michigan Sentiment Preliminary — Proxy Anticipations Inflation";
    impact      = "Moyenne Volatilité (Forte Impulsion)";

// ── Macro secondaire (Poids 2) — indicateurs de confirmation ──
} else if (containsAny(unified, "RETAIL SALES", "CONSUMER CONFIDENCE",
           "CONSUMER SENTIMENT", "GDP", "PMI", "ISM", "MICHIGAN")) {
    eventType   = "ECONOMIC-GROWTH-DATA";
    description = "Données Macroéconomiques Secondaires";
    impact      = "Moyenne Volatilité";
         
        } else if (containsAny(unified, "DXY", "DOLLAR INDEX", "DOLLAR STRENGTH", "DOLLAR WEAKNESS")) {
            eventType   = "DOLLAR-INDEX";
            description = "Indice Dollar (DXY) — Baromètre Intermarché";
            impact      = "Haute Volatilité";
        } else if (containsAny(unified, "NOMINATED", "APPOINTED", "NOMINATION", "APPOINTMENT", "FED CHAIR", "FED VICE CHAIR", "ECB PRESIDENT", "BOJ GOVERNOR", "REPLACE POWELL", "REPLACE LAGARDE", "REPLACE UEDA")) {
            eventType   = "CENTRAL-BANK-NOMINATION";
            description = "Nomination Banque Centrale — Changement de Politique Potentiel";
            impact      = "Haute Volatilité";
        
        } else if (containsAny(unified, "CHINA CPI", "CHINA PPI", "CHINA GDP", "CHINA PMI", "PBOC", "YUAN", "CNY", "RENMINBI", "CHINESE ECONOMY", "CHINA STIMULUS", "CHINA PROPERTY", "EVERGRANDE","NPC", "POLITBURO", "XI JINPING ECONOMY")) {
            eventType   = "CHINA-MACRO";
            description = "Données Macroéconomiques Chine / PBOC";
            impact      = "Haute Volatilité";

        // ── Treasury / Debt Ceiling ──
        } else if (containsAny(unified, "TREASURY AUCTION", "BID TO COVER",
                "DEBT CEILING", "BUDGET DEFICIT", "YIELD SPIKE",
                "BOND SELLOFF", "FOREIGN SELLING TREASURIES")) {
            eventType   = "TREASURY-MARKET";
            description = "Marché Obligataire US — Pression sur les Taux";
            impact      = "Haute Volatilité";

        // ── Carry Trade / MOF Intervention ──
        } else if (containsAny(unified, "CARRY TRADE", "FX INTERVENTION",
                "MOF JAPAN", "VERBAL INTERVENTION", "WATCHING CLOSELY",
                "EXCESSIVE MOVES", "SHARP YEN MOVES")) {
            eventType   = "FX-INTERVENTION";
            description = "Intervention FX / Carry Trade — Signal Yen Majeur";
            impact      = "Haute Volatilité";

        // ── Big Tech Earnings ──
        } else if (containsAny(unified, "EARNINGS", "PROFIT WARNING", "GUIDANCE",
                "REVENUE MISS", "REVENUE BEAT", "EPS BEAT", "EPS MISS",
                "NVDA EARNINGS", "AAPL EARNINGS", "MSFT EARNINGS",
                "AMZN EARNINGS", "META EARNINGS", "TESLA EARNINGS",
                "ALPHABET EARNINGS")) {
            eventType   = "TECH-EARNINGS";
            description = "Résultats Trimestriels Big Tech — Impact NASDAQ/SP500";
            impact      = "Haute Volatilité";

        // ── Bitcoin ETF / Halving / Regulatory ──
        } else if (containsAny(unified, "BITCOIN ETF", "ETF FLOWS", "IBIT",
                "FBTC", "HALVING", "SEC CRYPTO", "CRYPTO BAN",
                "EXCHANGE HACK", "CRYPTO REGULATION", "STABLECOIN CRISIS",
                "TETHER DEPEG", "FTX", "CELSIUS", "EXCHANGE COLLAPSE")) {
            eventType   = "CRYPTO-SPECIFIC";
            description = "Événement Crypto Spécifique — Bitcoin Driver Majeur";
            impact      = "Haute Volatilité";

        // ── Systemic Risk / Bank Run ──
        } else if (containsAny(unified, "BANK RUN", "SYSTEMIC RISK",
                "BANK COLLAPSE", "BANK FAILURE", "BANKING CRISIS",
                "CREDIT SUISSE", "SVB", "SILICON VALLEY BANK",
                "CONTAGION", "BAILOUT", "FDIC")) {
            eventType   = "SYSTEMIC-RISK";
            description = "Risque Systémique Bancaire — Contagion Financière";
            impact      = "Choc Géopolitique AUD/NASDAQ";

        // ── Iron Ore / Copper — AUD proxy ──
        } else if (containsAny(unified, "IRON ORE", "COPPER PRICE",
                "COPPER DEMAND", "IRON ORE PRICE", "CHINA STEEL",
                "CHINA INFRASTRUCTURE", "COMMODITY DEMAND")) {
            eventType   = "COMMODITY-METALS";
            description = "Métaux Industriels — Proxy AUD/Chine";
            impact      = "Moyenne Volatilité";

        // ── Sovereign Debt Crisis / Spreads ──
        } else if (containsAny(unified, "BTP SPREAD", "OAT SPREAD",
                "SOVEREIGN SPREAD", "ITALIAN BONDS", "FRENCH BONDS",
                "CREDIT DEFAULT SWAP", "CDS SPREAD", "DEBT CRISIS",
                "GREEK CRISIS", "SOVEREIGN DEBT")) {
            eventType   = "SOVEREIGN-DEBT";
            description = "Crise Souveraine Européenne — Spreads Obligataires";
            impact      = "Haute Volatilité";
        }

        // ── 2. EXTRACTION ET ACCUMULATION...
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
