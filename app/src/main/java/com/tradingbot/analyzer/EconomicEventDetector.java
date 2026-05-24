package com.tradingbot.analyzer;

import java.util.Locale;

public class EconomicEventDetector {

    // ─────────────────────────────────────────────────────────────
    //  MODÈLE DE DONNÉES
    // ─────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────
    //  POINT D'ENTRÉE PRINCIPAL
    //
    //  Classifie l'événement en 4 étapes :
    //  1. Type d'événement (Fed, Inflation, Emploi, BC étrangère, Géo...)
    //  2. Biais directionnel brut (Haussier / Baissier / Neutre)
    //  3. Résultat enrichi transmis au pipeline Groq
    // ─────────────────────────────────────────────────────────────

    public static DetectedEvent detectEvent(String title, String text) {
        String unified = (title + " " + text).toUpperCase(Locale.ROOT);

        String eventType   = "CORE-MACRO";
        String description = "Analyse Flash Institutionnelle";
        String impact      = "Neutre";

        // ── ÉTAPE 1 : Classification de l'événement ──────────────

        // ── Réserve Fédérale US (RANG SUPRÊME) ───────────────────
        if (unified.contains("FOMC")    || unified.contains("FED ") ||
            unified.contains("WARSH")   || unified.contains("POWELL")   ||
            unified.contains("BARKIN")  || unified.contains("GOOLSBEE") ||
            unified.contains("HAMMACK") || unified.contains("WALLER")   ||
            unified.contains("WILLIAMS")|| unified.contains("KUGLER")   ||
            unified.contains("FEDERAL RESERVE") || unified.contains("FED CHAIR")) {
            eventType   = "FED-MONETARY-POLICY";
            description = "Décision / Discours de la Réserve Fédérale (USA)";
            impact      = "Haute Volatilité";

        // ── Inflation US (RANG SUPRÊME) ───────────────────────────
        } else if (unified.contains("CPI ")     || unified.contains("CORE CPI") ||
                   unified.contains("INFLATION") || unified.contains("PCE")      ||
                   unified.contains("CORE PCE")  || unified.contains("PPI")      ||
                   unified.contains("PRODUCER PRICE") || unified.contains("IMPORT PRICE") ||
                   unified.contains("EXPORT PRICE")) {
            eventType   = "INFLATION-DATA";
            description = "Données d'Inflation (CPI / PCE / PPI)";
            impact      = "Haute Volatilité";

        // ── Emploi US (RANG SUPRÊME) ──────────────────────────────
        } else if (unified.contains("NFP")            || unified.contains("NON-FARM PAYROLLS") ||
                   unified.contains("UNEMPLOYMENT")   || unified.contains("JOBLESS CLAIMS")    ||
                   unified.contains("INITIAL CLAIMS") || unified.contains("CONTINUING CLAIMS") ||
                   unified.contains("ADP")            || unified.contains("JOLTS")             ||
                   unified.contains("JOB OPENINGS")   || unified.contains("CHALLENGER")) {
            eventType   = "EMPLOYMENT-REPORT";
            description = "Données du Marché de l'Emploi US";
            impact      = "Haute Volatilité";

        // ── Taux / Banques Centrales Étrangères (RANG SUPRÊME) ────
        } else if (unified.contains("INTEREST RATE")   || unified.contains("TAUX D'INTÉRÊT") ||
                   unified.contains("ECB")             || unified.contains("BCE")            ||
                   unified.contains("LAGARDE")         ||
                   unified.contains("BOE")             || unified.contains("BAILEY")         ||
                   unified.contains("BOC")             || unified.contains("MACKLEM")        ||
                   unified.contains("RBA")             || unified.contains("BULLOCK")        ||
                   unified.contains("BOJ")             || unified.contains("UEDA")           ||
                   unified.contains("MONETARY POLICY") || unified.contains("RATE DECISION")  ||
                   unified.contains("MINUTES")) {
            eventType   = "CENTRAL-BANK-RATE";
            description = "Taux / Politique Monétaire de Banque Centrale";
            impact      = "Haute Volatilité";

        // ── Croissance US : GDP (RANG SECONDAIRE) ─────────────────
        } else if (unified.contains("GDP")              || unified.contains("PIB")           ||
                   unified.contains("GROSS DOMESTIC")   || unified.contains("RETAIL SALES")  ||
                   unified.contains("PERSONAL SPENDING")|| unified.contains("PERSONAL INCOME")||
                   unified.contains("DURABLE GOODS")    || unified.contains("FACTORY ORDERS") ||
                   unified.contains("TRADE BALANCE")    || unified.contains("CURRENT ACCOUNT")||
                   unified.contains("INDUSTRIAL PRODUCTION") || unified.contains("CAPACITY UTILIZATION")) {
            eventType   = "ECONOMIC-GROWTH-DATA";
            description = "Données de Croissance Économique US";
            impact      = "Moyenne-Haute Volatilité";

        // ── Sentiment / Confiance (RANG TACTIQUE) ─────────────────
        } else if (unified.contains("MICHIGAN")            || unified.contains("CONSUMER CONFIDENCE") ||
                   unified.contains("CONSUMER SENTIMENT")  || unified.contains("CONFERENCE BOARD")) {
            eventType   = "CONSUMER-SENTIMENT";
            description = "Indice de Confiance des Consommateurs";
            impact      = "Moyenne Volatilité";

        // ── PMI / ISM / Activité Manufacturière (RANG SECONDAIRE) ─
        } else if (unified.contains("PMI")          || unified.contains("ISM")          ||
                   unified.contains("MANUFACTURING") || unified.contains("SERVICES PMI") ||
                   unified.contains("CHICAGO PMI")  || unified.contains("PHILLY FED")   ||
                   unified.contains("EMPIRE STATE")  || unified.contains("KANSAS CITY")  ||
                   unified.contains("BEIGE BOOK")) {
            eventType   = "ECONOMIC-GROWTH-PMI";
            description = "Indice des Directeurs d'Achat / Activité Manufacturière";
            impact      = "Moyenne Volatilité";

        // ── Immobilier (RANG SECONDAIRE) ──────────────────────────
        } else if (unified.contains("HOME SALES")     || unified.contains("HOUSING STARTS") ||
                   unified.contains("BUILDING PERMITS")|| unified.contains("EXISTING HOME") ||
                   unified.contains("NEW HOME")) {
            eventType   = "HOUSING-DATA";
            description = "Données du Marché Immobilier US";
            impact      = "Moyenne Volatilité";

        // ── Énergie / Pétrole EIA / OPEC (RANG SECONDAIRE) ───────
        } else if (unified.contains("CRUDE")         || unified.contains("EIA")          ||
                   unified.contains("OIL INVENTORIES")|| unified.contains("NATURAL GAS") ||
                   unified.contains("OPEC")          || unified.contains("OPEC+")        ||
                   unified.contains("STOCKS D'ESSENCE")) {
            eventType   = "ENERGY-RESERVES";
            description = "Rapport Stocks Pétrole EIA / Décision OPEC";
            impact      = "Moyenne Volatilité";

        // ── Géopolitique — Moyen-Orient ───────────────────────────
        } else if (unified.contains("ISRAEL")    || unified.contains("IRAN")      ||
                   unified.contains("GAZA")      || unified.contains("HAMAS")     ||
                   unified.contains("HEZBOLLAH") || unified.contains("HOUTHI")    ||
                   unified.contains("YEMEN")     || unified.contains("RED SEA")   ||
                   unified.contains("HORMUZ")    || unified.contains("MIDDLE EAST")) {
            eventType   = "GEO-MIDDLE-EAST";
            description = "Événement Géopolitique — Moyen-Orient / Pétrole";
            impact      = "Choc Géopolitique USOIL/GOLD";

        // ── Géopolitique — Europe de l'Est / OTAN ─────────────────
        } else if (unified.contains("UKRAINE")  || unified.contains("RUSSIA")   ||
                   unified.contains("KREMLIN")  || unified.contains("PUTIN")    ||
                   unified.contains("NATO")     || unified.contains("OTAN")     ||
                   unified.contains("ZELENSKY") || unified.contains("MOSCOW")) {
            eventType   = "GEO-EUROPE-EST";
            description = "Événement Géopolitique — Europe de l'Est / OTAN";
            impact      = "Choc Géopolitique EUR/USOIL";

        // ── Géopolitique — Asie-Pacifique / Chine ─────────────────
        } else if (unified.contains("CHINA")    || unified.contains("TAIWAN")    ||
                   unified.contains("BEIJING")  || unified.contains("XI JINPING")||
                   unified.contains("NORTH KOREA") || unified.contains("SOUTH CHINA SEA") ||
                   unified.contains("TSMC")     || unified.contains("SEMICONDUCTOR")) {
            eventType   = "GEO-ASIA-PACIFIC";
            description = "Événement Géopolitique — Asie-Pacifique / Chine";
            impact      = "Choc Géopolitique AUD/NASDAQ";

        // ── Sanctions / Commerce / Tarifs ─────────────────────────
        } else if (unified.contains("SANCTIONS") || unified.contains("TARIFF")     ||
                   unified.contains("TRADE WAR") || unified.contains("EMBARGO")    ||
                   unified.contains("BLOCKADE")  || unified.contains("CEASEFIRE")) {
            eventType   = "GEO-SANCTIONS-TRADE";
            description = "Sanctions / Guerre Commerciale / Accord Géopolitique";
            impact      = "Choc Géopolitique Multi-Actifs";
        }

        // ── ÉTAPE 2 : Biais directionnel brut ────────────────────
        // Ce filtre est complété et sublimé ensuite par l'IA Groq Llama 3.3

        if (unified.contains("HIGHER THAN EXPECTED") || unified.contains("ABOVE FORECAST")  ||
            unified.contains("BEATS ESTIMATES")       || unified.contains("HAUSSIER")        ||
            unified.contains("ABOVE EXPECTATIONS")    || unified.contains("BETTER THAN EXPECTED")) {
            impact = "Biais Haussier Détecté";

        } else if (unified.contains("LOWER THAN EXPECTED") || unified.contains("BELOW FORECAST") ||
                   unified.contains("MISSES ESTIMATES")     || unified.contains("BAISSIER")       ||
                   unified.contains("BELOW EXPECTATIONS")   || unified.contains("WORSE THAN EXPECTED") ||
                   unified.contains("DISAPPOINTS")) {
            impact = "Biais Baissier Détecté";

        } else if (unified.contains("SHOCK") || unified.contains("SURPRISE") ||
                   unified.contains("BREAKING") || unified.contains("FLASH CRASH") ||
                   unified.contains("EMERGENCY")) {
            // Ne remplace l'impact que s'il est encore neutre
            // (les événements géo ont déjà leur propre impact)
            if ("Neutre".equals(impact)) {
                impact = "Forte Impulsion Neutre";
            }
        }

        return new DetectedEvent(eventType, description, impact);
    }
}
