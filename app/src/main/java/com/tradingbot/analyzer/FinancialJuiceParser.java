package com.tradingbot.analyzer;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parse les publications du calendrier économique FinancialJuice.
 *
 * Format des valeurs (corps) :
 *   Actual <x>[%|k|M|B] (Forecast <y>[%|k|M|B]|-, Previous <z>[%|k|M|B])
 * Format du nom (titre) : "US ADP Wkly Employment Change", "Dutch CPI YoY", etc.
 *
 * Cette classe n'émet aucun signal de marché. Elle extrait les chiffres, classe la
 * publication par zone, et indique dans quelle catégorie elle tombe. La direction
 * est calculée ailleurs (MatriceMacro), uniquement si surpriseDisponible() est vrai.
 */
public final class FinancialJuiceParser {

    public enum Zone { US, ZONE_EURO, ROYAUME_UNI, JAPON, HORS_PERIMETRE, INCONNUE }

    public enum Categorie {
        /** Publication US chiffrée : peut alimenter la matrice US si la surprise existe. */
        US_CHIFFRE,
        /** Décision ou discours d'une banque centrale hors Fed : confirmation requise. */
        BANQUE_CENTRALE_EXTERNE,
        /** Information de contexte uniquement : aucun signal. */
        CONTEXTE_SEULEMENT,
        /** Pays hors de nos six actifs : aucun signal. */
        HORS_PERIMETRE
    }

    // Valeurs : groupes 1/2 = actual + unité, 3/4 = forecast (ou "-") + unité, 5/6 = previous + unité
    private static final Pattern VALEURS = Pattern.compile(
            "Actual\\s*:?\\s*([-+]?\\d+(?:\\.\\d+)?)\\s*(%|[kKmMbB])?" +
            "\\s*\\(\\s*Forecast\\s*:?\\s*(-|[-+]?\\d+(?:\\.\\d+)?)\\s*(%|[kKmMbB])?" +
            "(?:\\s*,\\s*Previous\\s*:?\\s*([-+]?\\d+(?:\\.\\d+)?)\\s*(%|[kKmMbB])?)?" +
            "\\s*\\)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern P_HORS = Pattern.compile(
            "\\b(NORWAY|NORWEGIAN|SWEDEN|SWEDISH|SWITZERLAND|SWISS|NEW ZEALAND|NZD|KOREA|KOREAN|" +
            "INDIA|BRAZIL|MEXICO|SOUTH AFRICA|CANADA|CANADIAN|CAD|AUSTRALIA|AUSTRALIAN|AUSSIE|AUD|" +
            "CHINA|CHINESE|CNY)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern P_ZONE_EURO = Pattern.compile(
            "\\b(EUROZONE|EURO AREA|EURO|EUR|GERMAN|GERMANY|FRENCH|FRANCE|ITALIAN|ITALY|" +
            "SPANISH|SPAIN|DUTCH|NETHERLANDS|ECB|BCE|IFO|ZEW|HICP|LAGARDE)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern P_ROYAUME_UNI = Pattern.compile(
            "\\b(UK|UNITED KINGDOM|BRITISH|BRITAIN|BOE|BAILEY|GBP|GILT|MPC)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern P_JAPON = Pattern.compile(
            "\\b(JAPAN|JAPANESE|BOJ|UEDA|TANKAN|JPY|YEN)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern P_US = Pattern.compile(
            "\\b(US|USA|UNITED STATES|FED|FOMC|POWELL|NFP|ADP|JOLTS|ISM|MICHIGAN|PAYROLLS?|NON-FARM|EIA)\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Indicateurs retenus (liste blanche). Un nom est retenu s'il contient un de ces mots.
     * Les mots sont ceux que le code reconnaît déjà pour FinancialJuice.
     */
    private static final List<String> INDICATEURS_RETENUS = Collections.unmodifiableList(Arrays.asList(
            "CPI", "PCE", "PPI", "NFP", "NON-FARM", "PAYROLL", "ADP", "JOLTS",
            "JOBLESS CLAIMS", "INITIAL CLAIMS", "CONTINUING CLAIMS", "UNEMPLOYMENT RATE",
            "GDP", "ISM", "PMI", "RETAIL SALES", "MICHIGAN", "CONSUMER CONFIDENCE",
            "DURABLE GOODS", "HOUSING STARTS", "EXISTING HOME SALES", "PENDING HOME SALES",
            "FOMC", "FED", "INTEREST RATE", "RATE DECISION", "MINUTES", "BEIGE BOOK",
            "BOE", "BAILEY", "BOJ", "UEDA", "TANKAN",
            "ECB", "BCE", "LAGARDE", "HICP", "IFO", "ZEW",
            "EIA", "CRUDE", "INVENTORIES"));

    /** Mots indiquant une décision ou un discours de banque centrale. */
    private static final List<String> MOTS_BANQUE_CENTRALE = Collections.unmodifiableList(Arrays.asList(
            "BOE", "BAILEY", "BOJ", "UEDA", "ECB", "BCE", "LAGARDE", "INTEREST RATE", "RATE DECISION"));

    // ────────────────────────────────────────────────────────────────────────

    /** Résultat d'un parsing réussi. */
    public static final class Publication {
        public final String indicateur;
        public final Zone zone;
        public final boolean retenu;
        /** NaN si absent. Valeurs en unité de base (k → ×1000, M → ×10⁶, B → ×10⁹, % inchangé). */
        public final double actual;
        public final double forecast;
        public final double previous;

        Publication(String indicateur, Zone zone, boolean retenu,
                    double actual, double forecast, double previous) {
            this.indicateur = indicateur;
            this.zone = zone;
            this.retenu = retenu;
            this.actual = actual;
            this.forecast = forecast;
            this.previous = previous;
        }

        /** Vrai si actual et forecast sont tous deux présents et de même unité. */
        public boolean surpriseDisponible() {
            return !Double.isNaN(actual) && !Double.isNaN(forecast);
        }

        /** Surprise signée (actual − forecast), ou NaN si indisponible. */
        public double surprise() {
            return surpriseDisponible() ? actual - forecast : Double.NaN;
        }

        /** Vrai si la publication est exactement conforme au consensus. */
        public boolean conforme() {
            return surpriseDisponible() && Math.abs(surprise()) < 1e-9;
        }

        /** Catégorie de traitement. Ne tient pas compte de la présence de la surprise. */
        public Categorie categorie() {
            if (zone == Zone.HORS_PERIMETRE) return Categorie.HORS_PERIMETRE;
            if (!retenu) return Categorie.CONTEXTE_SEULEMENT;
            switch (zone) {
                case US:
                    return Categorie.US_CHIFFRE;
                case ZONE_EURO:
                case ROYAUME_UNI:
                case JAPON:
                    return estBanqueCentrale(indicateur)
                            ? Categorie.BANQUE_CENTRALE_EXTERNE
                            : Categorie.CONTEXTE_SEULEMENT;
                default:
                    return Categorie.CONTEXTE_SEULEMENT;
            }
        }

        @Override
        public String toString() {
            return indicateur + " | zone=" + zone + " | retenu=" + retenu
                    + " | actual=" + actual + " | forecast=" + forecast
                    + " | previous=" + previous + " | catégorie=" + categorie();
        }
    }

    private FinancialJuiceParser() {}

    /**
     * Parse une notification dont le nom et les valeurs sont dans le même texte.
     * Retourne null si le texte ne contient pas le format Actual / Forecast.
     */
    public static Publication parser(String texte) {
        if (texte == null) return null;
        String t = texte.trim();
        Matcher m = VALEURS.matcher(t);
        if (!m.find()) return null;
        String nom = t.substring(0, m.start())
                .replaceAll("(?i)\\b(Final|Flash|Prelim)\\b", "")
                .trim();
        return construire(nom, m);
    }

    /**
     * Parse une notification où le nom est dans le titre et les valeurs dans le corps.
     * Si le corps ne contient pas les valeurs, tente le texte du titre seul.
     */
    public static Publication parser(String titre, String corps) {
        String t = (titre == null) ? "" : titre.trim();
        String c = (corps == null) ? "" : corps.trim();
        Matcher m = VALEURS.matcher(c);
        if (m.find()) {
            return construire(t, m);
        }
        return parser(t);
    }

    // ────────────────────────────────────────────────────────────────────────

    private static Publication construire(String nom, Matcher m) {
        String uniteActual = m.group(2);
        String uniteForecast = m.group(4);
        String uniteprevious = m.group(6);

        double actual = valeur(m.group(1), uniteActual);
        double forecast = valeur(m.group(3), uniteForecast);
        double previous = valeur(m.group(5), uniteprevious);

        // Surprise seulement si les deux valeurs ont la même unité (sinon comparaison invalide)
        boolean unitesIdentiques = (uniteActual == null && uniteForecast == null)
                || (uniteActual != null && uniteForecast != null
                    && uniteActual.equalsIgnoreCase(uniteForecast));
        if (!unitesIdentiques) {
            forecast = Double.NaN;
        }

        Zone zone = detecterZone(nom);
        boolean retenu = estRetenu(nom);
        return new Publication(nom, zone, retenu, actual, forecast, previous);
    }

    /** Convertit un nombre texte en valeur de base selon son unité. NaN si absent ou "-". */
    private static double valeur(String nombre, String unite) {
        if (nombre == null || nombre.equals("-")) return Double.NaN;
        try {
            return Double.parseDouble(nombre) * facteur(unite);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static double facteur(String unite) {
        if (unite == null) return 1.0;
        switch (unite.toUpperCase(Locale.ROOT)) {
            case "K": return 1e3;
            case "M": return 1e6;
            case "B": return 1e9;
            default:  return 1.0;   // "%" ou aucune unité
        }
    }

    /** Zone déduite du nom. L'ordre compte : pays hors périmètre d'abord. */
    static Zone detecterZone(String nom) {
        if (nom == null) return Zone.INCONNUE;
        String n = nom.toUpperCase(Locale.ROOT);
        if (P_HORS.matcher(n).find()) return Zone.HORS_PERIMETRE;
        if (P_ZONE_EURO.matcher(n).find()) return Zone.ZONE_EURO;
        if (P_ROYAUME_UNI.matcher(n).find()) return Zone.ROYAUME_UNI;
        if (P_JAPON.matcher(n).find()) return Zone.JAPON;
        if (P_US.matcher(n).find()) return Zone.US;
        return Zone.INCONNUE;
    }

    static boolean estRetenu(String nom) {
        if (nom == null) return false;
        String n = nom.toUpperCase(Locale.ROOT);
        for (String mot : INDICATEURS_RETENUS) {
            if (n.contains(mot)) return true;
        }
        return false;
    }

    static boolean estBanqueCentrale(String nom) {
        if (nom == null) return false;
        String n = nom.toUpperCase(Locale.ROOT);
        for (String mot : MOTS_BANQUE_CENTRALE) {
            if (n.contains(mot)) return true;
        }
        return false;
    }
}
