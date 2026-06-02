package com.tradingbot.analyzer;

import android.util.Log;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class EconomicAnalyzer {

    private static final String TAG = "BOT_ECONOMIC_ANALYZER";

    public static class EvaluationResult {
        public int weight = 1;
        public String marketImpact = "NEUTRAL";
        public String directionText = "";
        public double deviation = 0.0;
        public boolean isParsed = false;
        public String currency = "USD";  // Nouveau champ
    }

    private static class ParsedValues {
        double actual = Double.NaN;
        double forecast = Double.NaN;
        String currency = "USD";  // Devise détectée
        boolean isValid() {
        return !Double.isNaN(actual) && !Double.isNaN(forecast);
        }
    }

    /**
     * Point d'entrée principal avec détection de la devise
     */
    public static EvaluationResult analyserEvenement(String title, String text) {
        EvaluationResult result = new EvaluationResult();
        if (title == null || text == null) return result;

        String combined = (title + " " + text).toUpperCase(Locale.ROOT);
        
        // Détection de la devise (priorité aux mentions explicites)
        String currency = detectCurrency(combined);
        result.currency = currency;

        ParsedValues valeurs = extraireChiffres(text);
        
        if (!valeurs.isValid()) {
            traiterEvenementTextuel(combined, result, currency);
            return result;
        }

        valeurs.currency = currency;
        result.isParsed = true;
        result.deviation = valeurs.actual - valeurs.forecast;
        double absEcart = Math.abs(result.deviation);

        // Appliquer l'analyse selon la devise
        if (currency.equals("USD")) {
            analyserUS(valeurs, result, combined, absEcart);
        } else if (currency.equals("EUR")) {
            analyserEUR(valeurs, result, combined, absEcart);
        } else if (currency.equals("GBP")) {
            analyserGBP(valeurs, result, combined, absEcart);
        } else if (currency.equals("JPY")) {
            analyserJPY(valeurs, result, combined, absEcart);
        } else if (currency.equals("CAD")) {
            analyserCAD(valeurs, result, combined, absEcart);
        } else if (currency.equals("AUD")) {
            analyserAUD(valeurs, result, combined, absEcart);
        } else {
            // Fallback
            attribuerPoids(absEcart, 1.0, 2.0, result);
            result.marketImpact = "UNKNOWN_MACRO";
            result.directionText = (result.deviation > 0) ? "Donnée supérieure aux attentes" : "Donnée inférieure aux attentes";
        }

        Log.d(TAG, "Analyse " + currency + " : " + result.marketImpact + " [Poids: " + result.weight + "]");
        // Envoi dans l'interface
        if (MainActivity.instance != null) {
           MainActivity.instance.addLog("[EconomicAnalyzer] " + currency + " | Poids=" + result.weight + " | " + result.directionText);
           }
        return result;
        }

    private static String detectCurrency(String upperText) {
        if (upperText.contains("ECB") || upperText.contains("EUROZONE") || 
            (upperText.contains("CPI") && upperText.contains("GERMAN")) ||
            upperText.contains("FRENCH") || upperText.contains("ITALIAN")) {
            return "EUR";
        }
        if (upperText.contains("BOE") || upperText.contains("UK") || upperText.contains("BRITISH")) {
            return "GBP";
        }
        if (upperText.contains("BOJ") || upperText.contains("JAPAN")) {
            return "JPY";
        }
        if (upperText.contains("BOC") || upperText.contains("CANADA")) {
            return "CAD";
        }
        if (upperText.contains("RBA") || upperText.contains("AUSTRALIA")) {
            return "AUD";
        }
        // Par défaut USD (FED, FOMC, etc.)
        return "USD";
    }

    // ==================== ANALYSE PAR DEVISE ====================

    private static void analyserUS(ParsedValues v, EvaluationResult r, String combined, double absEcart) {
        if (combined.contains("CPI") || combined.contains("PCE") || combined.contains("PPI") || combined.contains("INFLATION")) {
            attribuerPoids(absEcart, 0.1, 0.2, r);
            if (r.deviation > 0) {
                r.marketImpact = "US_CPI_HAWKISH";
                r.directionText = "🔥 HAUSSE INFLATION US: USD ↗️, USDJPY ↗️, GOLD ↘️, NASDAQ/SP500 ↘️, BTC ↘️";
            } else {
                r.marketImpact = "US_CPI_DOVISH";
                r.directionText = "🍃 BAISSE INFLATION US: USD ↘️, GOLD ↗️, NASDAQ/SP500 ↗️, BTC ↗️";
            }
        } else if (combined.contains("NFP") || combined.contains("NON-FARM") || combined.contains("ADP") || combined.contains("EMPLOYMENT")) {
            attribuerPoids(absEcart, 25.0, 55.0, r);
            if (r.deviation > 0) {
                r.marketImpact = "US_NFP_STRONG";
                r.directionText = "💪 EMPLOI SOLIDE US: USD ↗️, USOIL ↗️, GOLD ↘️, NASDAQ ↘️";
            } else {
                r.marketImpact = "US_NFP_WEAK";
                r.directionText = "⚠️ EMPLOI FAIBLE US: USD ↘️, GOLD ↗️, NASDAQ/SP500 ↗️";
            }
        } else if (combined.contains("GDP") || combined.contains("PIB")) {
            attribuerPoids(absEcart, 0.2, 0.5, r);
            if (r.deviation > 0) {
                r.marketImpact = "US_GDP_BULLISH";
                r.directionText = "📈 GDP FORTE US: USD ↗️, USOIL ↗️, NASDAQ/SP500 ↗️, GOLD ↘️";
            } else {
                r.marketImpact = "US_GDP_BEARISH";
                r.directionText = "📉 RISQUE RÉCESSION US: USD ↘️, GOLD ↗️, INDICES ↘️";
            }
        } else if (combined.contains("PMI") || combined.contains("ISM")) {
            attribuerPoids(absEcart, 0.6, 1.2, r);
            if (r.deviation > 0) {
                r.marketImpact = "US_PMI_EXPANSION";
                r.directionText = "🏭 PMI EXPANSION US: USOIL ↗️, USD ↗️, NASDAQ/SP500 ↗️";
            } else {
                r.marketImpact = "US_PMI_CONTRACTION";
                r.directionText = "🛑 PMI CONTRACTION US: USOIL ↘️, USD ↘️, GOLD ↗️";
            }
        } else if (combined.contains("JOBLESS CLAIMS") || combined.contains("IJC")) {
            attribuerPoids(absEcart, 10.0, 20.0, r);
            if (r.deviation > 0) {
                r.marketImpact = "US_IJC_BAD";
                r.directionText = "⚠️ HAUSSE CHÔMAGE US: USD ↘️, GOLD ↗️, NASDAQ ↗️";
            } else {
                r.marketImpact = "US_IJC_GOOD";
                r.directionText = "🦅 BAISSE CHÔMAGE US: USD ↗️, GOLD ↘️, USOIL ↗️";
            }
        } else if (combined.contains("RETAIL SALES")) {
            attribuerPoids(absEcart, 0.3, 0.7, r);
            if (r.deviation > 0) {
                r.marketImpact = "US_RETAIL_STRONG";
                r.directionText = "🛍️ VENTES AU DÉTAIL FORTES US: USD ↗️, NASDAQ/SP500 ↗️";
            } else {
                r.marketImpact = "US_RETAIL_WEAK";
                r.directionText = "📉 VENTES FAIBLES US: USD ↘️, GOLD ↗️";
            }
        } else if (combined.contains("CONSUMER CONFIDENCE") || combined.contains("MICHIGAN")) {
            attribuerPoids(absEcart, 2.0, 5.0, r);
            if (r.deviation > 0) {
                r.marketImpact = "US_CONFIDENCE_HIGH";
                r.directionText = "🧠 CONFIANCE HAUTE US: USD ↗️, INDICES ↗️";
            } else {
                r.marketImpact = "US_CONFIDENCE_LOW";
                r.directionText = "📉 CONFIANCE BASSE US: USD ↘️, GOLD ↗️";
            }
        } else if (combined.contains("OIL") || combined.contains("EIA") || combined.contains("INVENTORIES")) {
            attribuerPoids(absEcart, 2.0, 4.5, r);
            if (r.deviation > 0) {
                r.marketImpact = "OIL_INVENTORIES_SURPLUS";
                r.directionText = "🛢️ SURPLUS STOCKS PÉTROLE: USOIL ↘️";
            } else {
                r.marketImpact = "OIL_INVENTORIES_DEFICIT";
                r.directionText = "🛢️ DÉFICIT STOCKS PÉTROLE: USOIL ↗️";
            }
        } else {
            attribuerPoids(absEcart, 1.0, 2.0, r);
            r.marketImpact = "US_MACRO_OTHER";
            r.directionText = r.deviation > 0 ? "Donnée US supérieure aux attentes" : "Donnée US inférieure aux attentes";
        }
    }

    private static void analyserEUR(ParsedValues v, EvaluationResult r, String combined, double absEcart) {
        if (combined.contains("CPI") || combined.contains("INFLATION") || combined.contains("HICP")) {
            attribuerPoids(absEcart, 0.1, 0.2, r);
            if (r.deviation > 0) {
                r.marketImpact = "EUR_CPI_HAWKISH";
                r.directionText = "🔥 HAUSSE INFLATION ZONE EURO: EUR ↗️, GOLD ↗️, USOIL ↗️, EURUSD ↗️";
            } else {
                r.marketImpact = "EUR_CPI_DOVISH";
                r.directionText = "🍃 BAISSE INFLATION ZONE EURO: EUR ↘️, EURUSD ↘️, USOIL ↘️";
            }
        } else if (combined.contains("GDP") || combined.contains("PIB")) {
            attribuerPoids(absEcart, 0.2, 0.5, r);
            if (r.deviation > 0) {
                r.marketImpact = "EUR_GDP_BULLISH";
                r.directionText = "📈 CROISSANCE EURO FORTE: EUR ↗️, EURUSD ↗️, SP500 ↗️ (sentiment global)";
            } else {
                r.marketImpact = "EUR_GDP_BEARISH";
                r.directionText = "📉 RÉCESSION EURO: EUR ↘️, EURUSD ↘️, GOLD ↗️ (refuge)";
            }
        } else if (combined.contains("PMI")) {
            attribuerPoids(absEcart, 0.6, 1.2, r);
            if (r.deviation > 0) {
                r.marketImpact = "EUR_PMI_EXPANSION";
                r.directionText = "🏭 PMI EURO EXPANSION: EUR ↗️, EURUSD ↗️, USOIL ↗️";
            } else {
                r.marketImpact = "EUR_PMI_CONTRACTION";
                r.directionText = "🛑 PMI EURO CONTRACTION: EUR ↘️, EURUSD ↘️, GOLD ↗️";
            }
        } else if (combined.contains("RETAIL SALES") || combined.contains("CONSUMER SPENDING")) {
            attribuerPoids(absEcart, 0.3, 0.7, r);
            r.marketImpact = r.deviation > 0 ? "EUR_RETAIL_STRONG" : "EUR_RETAIL_WEAK";
            r.directionText = r.deviation > 0 ? "📈 CONSOMMATION EURO FORTE: EUR ↗️" : "📉 CONSOMMATION EURO FAIBLE: EUR ↘️";
        } else if (combined.contains("UNEMPLOYMENT") || combined.contains("JOBLESS")) {
            attribuerPoids(absEcart, 0.1, 0.2, r);
            r.marketImpact = r.deviation > 0 ? "EUR_UNEMPLOYMENT_HIGH" : "EUR_UNEMPLOYMENT_LOW";
            r.directionText = r.deviation > 0 ? "⚠️ CHÔMAGE HAUT EURO: EUR ↘️" : "✅ CHÔMAGE BAS EURO: EUR ↗️";
        } else {
            attribuerPoids(absEcart, 1.0, 2.0, r);
            r.marketImpact = "EUR_MACRO_OTHER";
            r.directionText = r.deviation > 0 ? "Donnée zone euro supérieure aux attentes" : "Donnée zone euro inférieure aux attentes";
        }
    }

    private static void analyserGBP(ParsedValues v, EvaluationResult r, String combined, double absEcart) {
        if (combined.contains("CPI") || combined.contains("INFLATION")) {
            attribuerPoids(absEcart, 0.1, 0.2, r);
            r.marketImpact = r.deviation > 0 ? "GBP_CPI_HAWKISH" : "GBP_CPI_DOVISH";
            r.directionText = r.deviation > 0 ? "🔥 INFLATION UK HAUTE: GBP ↗️, GBPUSD ↗️" : "🍃 INFLATION UK BASSE: GBP ↘️, GBPUSD ↘️";
        } else if (combined.contains("GDP") || combined.contains("GROWTH")) {
            attribuerPoids(absEcart, 0.2, 0.5, r);
            r.marketImpact = r.deviation > 0 ? "GBP_GDP_BULLISH" : "GBP_GDP_BEARISH";
            r.directionText = r.deviation > 0 ? "📈 CROISSANCE UK FORTE: GBP ↗️" : "📉 RÉCESSION UK: GBP ↘️, GOLD ↗️";
        } else if (combined.contains("PMI")) {
            attribuerPoids(absEcart, 0.6, 1.2, r);
            r.marketImpact = r.deviation > 0 ? "GBP_PMI_EXPANSION" : "GBP_PMI_CONTRACTION";
            r.directionText = r.deviation > 0 ? "🏭 PMI UK EXPANSION: GBP ↗️" : "🛑 PMI UK CONTRACTION: GBP ↘️";
        } else {
            attribuerPoids(absEcart, 1.0, 2.0, r);
            r.marketImpact = "GBP_MACRO_OTHER";
            r.directionText = r.deviation > 0 ? "Donnée UK supérieure" : "Donnée UK inférieure";
        }
    }

    private static void analyserJPY(ParsedValues v, EvaluationResult r, String combined, double absEcart) {
        if (combined.contains("CPI") || combined.contains("INFLATION")) {
            attribuerPoids(absEcart, 0.1, 0.2, r);
            r.marketImpact = r.deviation > 0 ? "JPY_CPI_HAWKISH" : "JPY_CPI_DOVISH";
            r.directionText = r.deviation > 0 ? "🔥 INFLATION JAPON HAUTE: JPY ↗️, USDJPY ↘️" : "🍃 INFLATION JAPON BASSE: JPY ↘️, USDJPY ↗️";
        } else if (combined.contains("GDP")) {
            attribuerPoids(absEcart, 0.2, 0.5, r);
            r.marketImpact = r.deviation > 0 ? "JPY_GDP_BULLISH" : "JPY_GDP_BEARISH";
            r.directionText = r.deviation > 0 ? "📈 CROISSANCE JAPON FORTE: JPY ↗️" : "📉 RÉCESSION JAPON: JPY ↘️";
        } else if (combined.contains("BOJ") || combined.contains("RATE")) {
            r.weight = 4;
            r.marketImpact = r.deviation > 0 ? "BOJ_HAWKISH" : "BOJ_DOVISH";
            r.directionText = r.deviation > 0 ? "🦅 BOJ HAWKISH: USDJPY ↘️, JPY ↗️" : "🕊️ BOJ DOVISH: USDJPY ↗️, JPY ↘️";
        } else {
            attribuerPoids(absEcart, 1.0, 2.0, r);
            r.marketImpact = "JPY_MACRO_OTHER";
            r.directionText = r.deviation > 0 ? "Donnée Japon supérieure" : "Donnée Japon inférieure";
        }
    }

    private static void analyserCAD(ParsedValues v, EvaluationResult r, String combined, double absEcart) {
        if (combined.contains("CPI") || combined.contains("INFLATION")) {
            attribuerPoids(absEcart, 0.1, 0.2, r);
            r.marketImpact = r.deviation > 0 ? "CAD_CPI_HAWKISH" : "CAD_CPI_DOVISH";
            r.directionText = r.deviation > 0 ? "🔥 INFLATION CANADA HAUTE: CAD ↗️, USDCAD ↘️" : "🍃 INFLATION CANADA BASSE: CAD ↘️, USDCAD ↗️";
        } else if (combined.contains("GDP")) {
            attribuerPoids(absEcart, 0.2, 0.5, r);
            r.marketImpact = r.deviation > 0 ? "CAD_GDP_BULLISH" : "CAD_GDP_BEARISH";
            r.directionText = r.deviation > 0 ? "📈 CROISSANCE CANADA FORTE: CAD ↗️, USOIL ↗️" : "📉 RÉCESSION CANADA: CAD ↘️, USOIL ↘️";
        } else if (combined.contains("EMPLOYMENT") || combined.contains("JOBLESS")) {
            attribuerPoids(absEcart, 10.0, 20.0, r);
            r.marketImpact = r.deviation > 0 ? "CAD_JOBS_STRONG" : "CAD_JOBS_WEAK";
            r.directionText = r.deviation > 0 ? "💪 EMPLOI CANADA FORT: CAD ↗️" : "⚠️ EMPLOI CANADA FAIBLE: CAD ↘️";
        } else {
            attribuerPoids(absEcart, 1.0, 2.0, r);
            r.marketImpact = "CAD_MACRO_OTHER";
            r.directionText = r.deviation > 0 ? "Donnée Canada supérieure" : "Donnée Canada inférieure";
        }
    }

    private static void analyserAUD(ParsedValues v, EvaluationResult r, String combined, double absEcart) {
        if (combined.contains("CPI") || combined.contains("INFLATION")) {
            attribuerPoids(absEcart, 0.1, 0.2, r);
            r.marketImpact = r.deviation > 0 ? "AUD_CPI_HAWKISH" : "AUD_CPI_DOVISH";
            r.directionText = r.deviation > 0 ? "🔥 INFLATION AUSTRALIE HAUTE: AUD ↗️, AUDUSD ↗️" : "🍃 INFLATION AUSTRALIE BASSE: AUD ↘️, AUDUSD ↘️";
        } else if (combined.contains("GDP")) {
            attribuerPoids(absEcart, 0.2, 0.5, r);
            r.marketImpact = r.deviation > 0 ? "AUD_GDP_BULLISH" : "AUD_GDP_BEARISH";
            r.directionText = r.deviation > 0 ? "📈 CROISSANCE AUSTRALIE FORTE: AUD ↗️" : "📉 RÉCESSION AUSTRALIE: AUD ↘️";
        } else if (combined.contains("RBA") || combined.contains("RATE")) {
            r.weight = 4;
            r.marketImpact = r.deviation > 0 ? "RBA_HAWKISH" : "RBA_DOVISH";
            r.directionText = r.deviation > 0 ? "🦅 RBA HAWKISH: AUDUSD ↗️" : "🕊️ RBA DOVISH: AUDUSD ↘️";
        } else {
            attribuerPoids(absEcart, 1.0, 2.0, r);
            r.marketImpact = "AUD_MACRO_OTHER";
            r.directionText = r.deviation > 0 ? "Donnée Australie supérieure" : "Donnée Australie inférieure";
        }
    }

    private static void traiterEvenementTextuel(String text, EvaluationResult result, String currency) {
        if (text.contains("FOMC") || text.contains("FED") || text.contains("POWELL")) {
            result.weight = 4;
            result.marketImpact = "FOMC_STATEMENT";
            result.directionText = "🗣️ ALERTE FED – Volatilité extrême imminente.";
        } else if (text.contains("ECB") || text.contains("LAGARDE")) {
            result.weight = 3;
            result.marketImpact = "ECB_STATEMENT";
            result.directionText = "🇪🇺 DISCOURS BCE – Impact direct sur EURUSD.";
        } else if (text.contains("BOJ")) {
            result.weight = 4;
            result.marketImpact = "BOJ_STATEMENT";
            result.directionText = "🇯🇵 ALERTE BOJ – Impact direct sur USDJPY.";
        } else if (text.contains("BOE") || text.contains("BAILEY")) {
            result.weight = 3;
            result.marketImpact = "BOE_STATEMENT";
            result.directionText = "🇬🇧 DISCOURS BOE – Impact sur GBPUSD.";
        } else if (text.contains("BOC") || text.contains("MACKLEM")) {
            result.weight = 3;
            result.marketImpact = "BOC_STATEMENT";
            result.directionText = "🇨🇦 DISCOURS BOC – Impact sur USDCAD et USOIL.";
        } else if (text.contains("RBA") || text.contains("BULLOCK")) {
            result.weight = 3;
            result.marketImpact = "RBA_STATEMENT";
            result.directionText = "🇦🇺 DISCOURS RBA – Impact sur AUDUSD.";
        } else {
            result.weight = 1;
            result.marketImpact = "RAW_NEWS";
        }
    }

    private static void attribuerPoids(double absEcart, double seuilModere, double seuilViolent, EvaluationResult result) {
        if (absEcart >= seuilViolent) result.weight = 4;
        else if (absEcart >= seuilModere) result.weight = 3;
        else if (absEcart > 0.0) result.weight = 2;
        else result.weight = 1;
    }

    private static ParsedValues extraireChiffres(String texte) {
        ParsedValues values = new ParsedValues();
        if (texte == null) return values;
        String texteNettoye = texte.replace(',', '.')
                                   .replaceAll("[^\\d.\\-\\s%]", " ");
        String[] actualPatterns = {
            "ACTUAL:\\s*([0-9.\\-]+)", "ACTUAL\\s*[=:]\\s*([0-9.\\-]+)",
            "ACT\\s*[=:]\\s*([0-9.\\-]+)", "REAL\\s*[=:]\\s*([0-9.\\-]+)"
        };
        String[] forecastPatterns = {
            "FORECAST:\\s*([0-9.\\-]+)", "FORECAST\\s*[=:]\\s*([0-9.\\-]+)",
            "EXP\\s*[=:]\\s*([0-9.\\-]+)", "EST\\s*[=:]\\s*([0-9.\\-]+)",
            "CONSENSUS\\s*[=:]\\s*([0-9.\\-]+)"
        };
        for (String p : actualPatterns) {
            double val = chercherRegex(texteNettoye, p);
            if (!Double.isNaN(val)) { values.actual = val; break; }
        }
        for (String p : forecastPatterns) {
            double val = chercherRegex(texteNettoye, p);
            if (!Double.isNaN(val)) { values.forecast = val; break; }
        }
        return values;
    }

    private static double chercherRegex(String texte, String expression) {
        try {
            Pattern pattern = Pattern.compile(expression, Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(texte);
            if (matcher.find()) {
                String numStr = matcher.group(1).replace("%", "");
                return Double.parseDouble(numStr);
            }
        } catch (Exception e) { }
        return Double.NaN;
    }
}
