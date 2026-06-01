package com.tradingbot.analyzer;

import android.util.Log;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EconomicAnalyzer {

    private static final String TAG = "BOT_ECONOMIC_ANALYZER";

    // Modèle de données retourné après chaque analyse
    public static class EvaluationResult {
        public int weight = 1;                     // Poids final affecté (1 à 4)
        public String marketImpact = "NEUTRAL";     // Identifiant pour la matrice de dominance
        public String directionText = "";          // Message explicatif avec flèches pour Telegram
        public double deviation = 0.0;             // Écart brut calculé (Actual - Forecast)
        public boolean isParsed = false;           // Indique si des chiffres ont été extraits
    }

    // Structure interne pour l'extraction des données numériques
    private static class ParsedValues {
        double actual = Double.NaN;
        double forecast = Double.NaN;

        boolean isValid() {
            return !Double.isNaN(actual) && !Double.isNaN(forecast);
        }
    }

    /**
     * Point d'entrée principal : Analyse le titre et le texte brut d'un événement macroéconomique,
     * calcule l'écart mathématique exact et configure la matrice d'impact pour les actifs.
     */
    public static EvaluationResult analyserEvenement(String title, String text) {
        EvaluationResult result = new EvaluationResult();
        
        if (title == null || text == null) {
            return result;
        }

        String event = title.toUpperCase();
        String body = text.toUpperCase();

        // 1️⃣ EXTRACTION NUMÉRIQUE AUTOMATIQUE (Actual & Forecast)
        ParsedValues valeurs = extraireChiffres(text);
        
        if (!valeurs.isValid()) {
            // Si pas de chiffres (ex: Discours de la Fed), on attribue un poids selon l'importance de l'institution
            traiterEvenementTextuel(event, result);
            return result;
        }

        result.isParsed = true;
        result.deviation = valeurs.actual - valeurs.forecast;
        double absEcart = Math.abs(result.deviation);

        // 2️⃣ DICTIONNAIRE ET SÉCURISATION DES SEUILS PAR INDICATEUR

        // ==========================================
        // SÉRIE 1 : INDICES DE CROISSANCE / EMPLOI
        // ==========================================
        
        // GDP / Advance GDP (Produit Intérieur Brut)
        if (event.contains("GDP") || event.contains("PIB")) {
            attribuerPoids(absEcart, 0.2, 0.5, result);
            if (result.deviation > 0) {
                result.marketImpact = "US_GDP_BULLISH";
                result.directionText = "📈 GDP FORTE: USD ↗️, USOIL ↗️, NASDAQ/SP500 ↗️, GOLD ↘️";
            } else {
                result.marketImpact = "US_GDP_BEARISH";
                result.directionText = "📉 RISQUE RÉCESSION: USD ↘️, GOLD ↗️, INDICES ↘️";
            }
        }
        // NFP / ADPNF / EC (Emplois Non-Agricoles & Coûts d'emploi)
        else if (event.contains("NFP") || event.contains("NON-FARM") || event.contains("ADP") || event.contains("EMPLOYMENT")) {
            // Échelle en milliers (k). Ex: Surprise de 30k ou 60k emplois
            attribuerPoids(absEcart, 25.0, 55.0, result);
            if (result.deviation > 0) {
                result.marketImpact = "US_NFP_STRONG";
                result.directionText = "💪 EMPLOI SOLIDE: USD ↗️, USOIL ↗️, GOLD ↘️, NASDAQ ↘️ (Peur Taux)";
            } else {
                result.marketImpact = "US_NFP_WEAK";
                result.directionText = "⚠️ MARCHE DU TRAVAIL FAIBLE: USD ↘️, GOLD ↗️, NASDAQ/SP500 ↗️";
            }
        }
        // PMI / ISM (Manufacturing, Services, Non-Manufacturing)
        else if (event.contains("PMI") || event.contains("ISM")) {
            attribuerPoids(absEcart, 0.6, 1.2, result);
            if (result.deviation > 0) {
                result.marketImpact = "US_PMI_EXPANSION";
                result.directionText = "🏭 EXPANSION PMI: USOIL ↗️, USD ↗️, NASDAQ/SP500 ↗️, GOLD ↘️";
            } else {
                result.marketImpact = "US_PMI_CONTRACTION";
                result.directionText = "🛑 CONTRACTION PMI: USOIL ↘️, USD ↘️, GOLD ↗️";
            }
        }
        // COI (Crude Oil Inventories / Stocks de Pétrole EIA)
        else if (event.contains("INVENTORIES") && (event.contains("OIL") || event.contains("CRUDE"))) {
            // Échelle en Millions de barils.
            attribuerPoids(absEcart, 2.0, 4.5, result);
            // ATTENTION INVERSÉ : Plus de stocks que prévu = Baisse des cours du brut
            if (result.deviation > 0) {
                result.marketImpact = "OIL_INVENTORIES_SURPLUS";
                result.directionText = "🛢️ SURPLUS DE STOCKS: USOIL ↘️ (Baisse de la demande)";
            } else {
                result.marketImpact = "OIL_INVENTORIES_DEFICIT";
                result.directionText = "🛢️ MANQUE DE STOCKS: USOIL ↗️ (Forte consommation)";
            }
        }
        // IJC (Initial Jobless Claims / Demandes d'allocation chômage)
        else if (event.contains("JOBLESS CLAIMS") || event.contains("IJC")) {
            attribuerPoids(absEcart, 10.0, 20.0, result);
            // ATTENTION INVERSÉ : Plus d'inscriptions au chômage = Mauvais pour l'économie
            if (result.deviation > 0) {
                result.marketImpact = "US_IJC_BAD";
                result.directionText = "⚠️ HAUSSE DES CHÔMEURS: USD ↘️, GOLD ↗️, NASDAQ ↗️";
            } else {
                result.marketImpact = "US_IJC_GOOD";
                result.directionText = "🦅 MOINS DE CHÔMEURS: USD ↗️, GOLD ↘️, USOIL ↗️";
            }
        }
        // ER (Unemployment Rate / Taux de chômage brut)
        else if (event.contains("UNEMPLOYMENT RATE") || event.contains("ER")) {
            attribuerPoids(absEcart, 0.1, 0.2, result);
            // ATTENTION INVERSÉ : Une hausse du taux de chômage affaiblit le Dollar
            if (result.deviation > 0) {
                result.marketImpact = "US_UNEMPLOYMENT_HIGH";
                result.directionText = "🚨 CHÔMAGE EN HAUSSE: USD ↘️, GOLD ↗️, INDICES ↗️ (Dovish)";
            } else {
                result.marketImpact = "US_UNEMPLOYMENT_LOW";
                result.directionText = "🦅 PLEIN EMPLOI: USD ↗️, GOLD ↘️, USOIL ↗️ (Hawkish)";
            }
        }
        // EHS (Existing Home Sales) / BP (Building Permits) / CDG (Core Durable Goods)
        else if (event.contains("HOME SALES") || event.contains("BUILDING PERMITS") || event.contains("DURABLE GOODS")) {
            attribuerPoids(absEcart, 1.0, 2.5, result);
            if (result.deviation > 0) {
                result.marketImpact = "US_HOUSING_STRONG";
                result.directionText = "🏠 IMMOBILIER/REVENUS FORTS: USD ↗️, INDICES ↗️";
            } else {
                result.marketImpact = "US_HOUSING_WEAK";
                result.directionText = "📉 IMMOBILIER/REVENUS EN BAISSE: USD ↘️";
            }
        }

        // ==========================================
        // SÉRIE 2 : INDICES D'INFLATION / CONSOMMATION
        // ==========================================
        
        // CPI (Consumer Price Index) / PPI (Producer Price Index) / PCE (Personal Consumption Expenditures)
        else if (event.contains("CPI") || event.contains("PPI") || event.contains("PCE") || event.contains("INFLATION")) {
            attribuerPoids(absEcart, 0.1, 0.2, result); // Extrêmement sensible sur les décimales %
            if (result.deviation > 0) {
                result.marketImpact = "US_CPI_HAWKISH";
                result.directionText = "🔥 CHOC D'INFLATION: USD ↗️, USDJPY ↗️, GOLD ↘️, NASDAQ/SP500 ↘️, BTC ↘️";
            } else {
                result.marketImpact = "US_CPI_DOVISH";
                result.directionText = "🍃 INFLATION EN BAISSE: USD ↘️, GOLD ↗️, NASDAQ/SP500 ↗️, BTC ↗️";
            }
        }
        // RS (Retail Sales / Ventes au détail)
        else if (event.contains("RETAIL SALES") || event.contains("RS")) {
            attribuerPoids(absEcart, 0.3, 0.7, result);
            if (result.deviation > 0) {
                result.marketImpact = "US_RETAIL_STRONG";
                result.directionText = "🛍️ CONSOMMATION FORTE: USD ↗️, NASDAQ/SP500 ↗️, GOLD ↘️";
            } else {
                result.marketImpact = "US_RETAIL_WEAK";
                result.directionText = "📉 CONSOMMATION EN BERNE: USD ↘️, GOLD ↗️";
            }
        }
        // CBCC (CB Consumer Confidence / Confiance des consommateurs)
        else if (event.contains("CONSUMER CONFIDENCE") || event.contains("CBCC")) {
            attribuerPoids(absEcart, 2.0, 5.0, result);
            if (result.deviation > 0) {
                result.marketImpact = "US_CONFIDENCE_HIGH";
                result.directionText = "🧠 MORAL DES MÉNAGES EN HAUSSE: USD ↗️, INDICES ↗️";
            } else {
                result.marketImpact = "US_CONFIDENCE_LOW";
                result.directionText = "📉 MORAL EN BAISSE: USD ↘️, GOLD ↗️";
            }
        }

        // ==========================================
        // SÉRIE 3 : DECISIONS DE TAUX D'INTÉRÊT (Règle brute)
        // ==========================================
        else if (event.contains("RATE") || event.contains("FUNDS") || event.contains("INTEREST") || event.contains("IRD")) {
            result.weight = 4; // Priorité absolue d'office (Choc systémique)
            if (result.deviation > 0) {
                result.marketImpact = "RATE_HIKE";
                result.directionText = "🦅 HAUSSE DES TAUX DIRECTEURS: USD ↗️, GOLD ↘️, INDICES ↘️, BTC ↘️";
            } else if (result.deviation < 0) {
                result.marketImpact = "RATE_CUT";
                result.directionText = "🕊️ BAISSE DES TAUX DIRECTEURS: USD ↘️, GOLD ↗️, INDICES ↗️, BTC ↗️";
            } else {
                result.weight = 3; // Taux inchangés mais reste un événement majeur
                result.marketImpact = "RATE_UNCHANGED";
                result.directionText = "💤 TAUX INCHANGÉS: Stabilité court terme attendue.";
            }
        }
        
        // Sécurité par défaut si l'indicateur est inconnu mais contient des valeurs chiffrées
        else {
            attribuerPoids(absEcart, 1.0, 2.0, result);
            result.marketImpact = "UNKNOWN_MACRO";
            result.directionText = (result.deviation > 0) ? "ℹ️ DONNÉE SUPÉRIEURE AUX ATTENTES" : "ℹ️ DONNÉE INFÉRIEURE AUX ATTENTES";
        }

        Log.d(TAG, "Analyse complétée : " + result.marketImpact + " [Poids: " + result.weight + "]");
        return result;
    }

    /**
     * Analyse les événements sans données chiffrées directes (Discours, réunions, minutes)
     */
    private static void traiterEvenementTextuel(String event, EvaluationResult result) {
        // FOMC / FED / BCE / BOJ / BOE (Banques Centrales et Organismes)
        if (event.contains("FOMC") || event.contains("FED") || event.contains("FEDERAL RESERVE") || event.contains("POWELL")) {
            result.weight = 4; // Alerte critique d'office pour la Fed américaine
            result.marketImpact = "FOMC_STATEMENT";
            result.directionText = "🗣️ ALERTE DISCOURS / MINUTES FED (US): Volatilité extrême imminente.";
        } else if (event.contains("BCE") || event.contains("ECB") || event.contains("LAGARDE")) {
            result.weight = 3;
            result.marketImpact = "ECB_STATEMENT";
            result.directionText = "🇪🇺 DISCOURS BCE: Impact direct sur la paire EURUSD.";
        } else if (event.contains("BOJ") || event.contains("BANK OF JAPAN")) {
            result.weight = 4; // Crucial pour contrer la neutralité spéculative sur le USDJPY
            result.marketImpact = "BOJ_STATEMENT";
            result.directionText = "🇯🇵 ALERTE BANQUE DU JAPON (BOJ): Alignement requis pour le USDJPY.";
        } else if (event.contains("BOE") || event.contains("BANK OF ENGLAND")) {
            result.weight = 3;
            result.marketImpact = "BOE_STATEMENT";
            result.directionText = "🇬🇧 DISCOURS BOE: Impact direct sur la paire GBPUSD.";
        } else {
            result.weight = 1;
            result.marketImpact = "RAW_NEWS";
        }
    }

    /**
     * Calcule dynamiquement le poids en fonction de l'écart absolu constaté et des seuils configurés
     */
    private static void attribuerPoids(double absEcart, double seuilModere, double seuilViolent, EvaluationResult result) {
        if (absEcart >= seuilViolent) {
            result.weight = 4; // Choc Macroéconomique critique -> Envoi immédiat validé !
        } else if (absEcart >= seuilModere) {
            result.weight = 3; // Impact Modéré -> Éligible au résumé quotidien et Telegram
        } else if (absEcart > 0.0) {
            result.weight = 2; // Conforme ou proche des attentes -> Stockage historique simple
        } else {
            result.weight = 1;
        }
    }

    /**
     * Module d'extraction Regex : Isole les nombres rattachés aux étiquettes Actual et Forecast
     */
    private static ParsedValues extraireChiffres(String texte) {
        ParsedValues values = new ParsedValues();
        if (texte == null) return values;

        // Supprime les virgules de formatage des milliers pour éviter les erreurs de parsing (ex: 250,000 -> 250000)
        String texteNettoye = texte.replace(",", "");

        values.actual = chercherRegex(texteNettoye, "ACTUAL:\\s*([0-9.-]+)");
        values.forecast = chercherRegex(texteNettoye, "FORECAST:\\s*([0-9.-]+)");

        return values;
    }

    private static double chercherRegex(String texte, String expression) {
        try {
            Pattern pattern = Pattern.compile(expression, Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(texte);
            if (matcher.find()) {
                return Double.parseDouble(matcher.group(1));
            }
        } catch (Exception e) {
            // Échec local de capture pour cette étiquette
        }
        return Double.NaN;
    }
}
