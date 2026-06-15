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
    public double actual = Double.NaN;    // ✅ valeur publiée
    public double forecast = Double.NaN; // ✅ valeur attendue
    public boolean isParsed = false;
    public String currency = "USD";
    }

    private static class ParsedValues {
        double actual = Double.NaN;
        double forecast = Double.NaN;
        double previous = 0.0; // ✅ Ajouté pour accueillir la valeur révisée d'origine
        String currency = "USD";

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
        String currency = detectCurrency(combined);
        result.currency = currency;

        ParsedValues valeurs = extraireChiffres(text);

        if (!valeurs.isValid()) {
            traiterEvenementTextuel(combined, result, currency);
            return result;
        }

        result.isParsed  = true;
        result.actual    = valeurs.actual;    // ✅
        result.forecast  = valeurs.forecast;  // ✅
        result.deviation = valeurs.actual - valeurs.forecast;
        double absEcart  = Math.abs(result.deviation);

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
            // Fallback pour Chine ou autre (via AUD car proxy)
            analyserAUD(valeurs, result, combined, absEcart);
        }

        // Log dans l'interface utilisateur
        if (MainActivity.instance != null) {
            MainActivity.instance.addLog("[EconomicAnalyzer] " + currency + " | Poids=" + result.weight + " | " + result.directionText);
        }

        Log.d(TAG, "Analyse " + currency + " : " + result.marketImpact + " [Poids: " + result.weight + "]");
        return result;
    }

    private static String detectCurrency(String upperText) {
        // Eurozone
        if (upperText.contains("ECB") || upperText.contains("EUROZONE") ||
            (upperText.contains("CPI") && (upperText.contains("GERMAN") || upperText.contains("FRENCH") || upperText.contains("ITALIAN"))) ||
            upperText.contains("IFO") || upperText.contains("ZEW")) {
            return "EUR";
        }
        // UK
        if (upperText.contains("BOE") || upperText.contains("UK") || upperText.contains("BRITISH") ||
            upperText.contains("AVERAGE EARNINGS") || upperText.contains("CLAIMANT COUNT")) {
            return "GBP";
        }
        // Japan
        if (upperText.contains("BOJ") || upperText.contains("JAPAN") || upperText.contains("TANKAN")) {
            return "JPY";
        }
        // Canada
        if (upperText.contains("BOC") || upperText.contains("CANADA") || upperText.contains("IVEY")) {
            return "CAD";
        }
        // Australia
        if (upperText.contains("RBA") || upperText.contains("AUSTRALIA") || upperText.contains("AUSSIE") ||
            upperText.contains("NAB") || upperText.contains("WESTPAC") || upperText.contains("CAIXIN")) {
            return "AUD";
        }
        // Default USD
        return "USD";
    }

    // ==================== ANALYSE PAR DEVISE ====================

    private static void analyserUS(ParsedValues v, EvaluationResult r, String combined, double absEcart) {
        // Inflation (CPI, PCE, PPI)
        if (combined.contains("CPI") || combined.contains("PCE") || combined.contains("PPI") || combined.contains("INFLATION")) {
            attribuerPoids(absEcart, 0.1, 0.2, r);
            if (r.deviation > 0) {
                r.marketImpact = "US_CPI_HAWKISH";
                r.directionText = "🔥 HAUSSE INFLATION US: USD ↗️, USDJPY ↗️, GOLD ↘️, NASDAQ/SP500 ↘️, BTC ↘️";
            } else {
                r.marketImpact = "US_CPI_DOVISH";
                r.directionText = "🍃 BAISSE INFLATION US: USD ↘️, GOLD ↗️, NASDAQ/SP500 ↗️, BTC ↗️";
            }
        }
        // Emploi : NFP, ADP, JOLTS
        else if (combined.contains("NON-FARM PAYROLLS") || combined.contains("NFP") ||
        combined.contains("NONFARM") || combined.contains("PAYROLL") ||
        combined.contains("NON-FARM EMPLOYMENT CHANGE") || 
        combined.contains("NONFARM EMPLOYMENT"))  {
            attribuerPoids(absEcart, 25.0, 55.0, r);
            if (r.deviation > 0) {
                r.marketImpact = "US_NFP_STRONG";
                r.directionText = "💪 EMPLOI SOLIDE US: USD ↗️, USOIL ↗️, GOLD ↘️, NASDAQ ↘️";
            } else {
                r.marketImpact = "US_NFP_WEAK";
                r.directionText = "⚠️ EMPLOI FAIBLE US: USD ↘️, GOLD ↗️, NASDAQ/SP500 ↗️";
            }
        }
        else if (combined.contains("ADP")) {
            attribuerPoids(absEcart, 25.0, 55.0, r);
            if (r.deviation > 0) {
                r.marketImpact = "US_ADP_STRONG";
                r.directionText = "💪 ADP SUPÉRIEUR: USD ↗️, NASDAQ/SP500 ↗️, GOLD ↘️";
            } else {
                r.marketImpact = "US_ADP_WEAK";
                r.directionText = "⚠️ ADP INFÉRIEUR: USD ↘️, GOLD ↗️, NASDAQ/SP500 ↘️";
            }
        }
        else if (combined.contains("JOLTS") || combined.contains("JOB OPENINGS")) {
            attribuerPoids(absEcart, 200.0, 500.0, r);
            if (r.deviation > 0) {
                r.marketImpact = "US_JOLTS_HIGH";
                r.directionText = "📊 OFFRE D'EMPLOI ÉLEVÉE US: USD ↗️, GOLD ↘️, NASDAQ/SP500 ↗️";
            } else {
                r.marketImpact = "US_JOLTS_LOW";
                r.directionText = "⚠️ BAISSE DES OFFRES US: USD ↘️, GOLD ↗️, NASDAQ/SP500 ↘️";
            }
        }
        // PIB
        else if (combined.contains("GDP") || combined.contains("PIB")) {
            attribuerPoids(absEcart, 0.2, 0.5, r);
            if (r.deviation > 0) {
                r.marketImpact = "US_GDP_BULLISH";
                r.directionText = "📈 GDP FORTE US: USD ↗️, USOIL ↗️, NASDAQ/SP500 ↗️, GOLD ↘️";
            } else {
                r.marketImpact = "US_GDP_BEARISH";
                r.directionText = "📉 RISQUE RÉCESSION US: USD ↘️, GOLD ↗️, INDICES ↘️";
            }
        }
        // PMI / ISM
        else if (combined.contains("PMI") || combined.contains("ISM")) {
            attribuerPoids(absEcart, 0.6, 1.2, r);
            if (r.deviation > 0) {
                r.marketImpact = "US_PMI_EXPANSION";
                r.directionText = "🏭 PMI EXPANSION US: USOIL ↗️, USD ↗️, NASDAQ/SP500 ↗️";
            } else {
                r.marketImpact = "US_PMI_CONTRACTION";
                r.directionText = "🛑 PMI CONTRACTION US: USOIL ↘️, USD ↘️, GOLD ↗️";
            }
        }
        // Chômage
        else if (combined.contains("JOBLESS CLAIMS") || combined.contains("IJC") || combined.contains("INITIAL CLAIMS") || combined.contains("CONTINUING CLAIMS") || combined.contains("UNEMPLOYMENT CLAIMS") || combined.contains("WEEKLY CLAIMS")) {
            attribuerPoids(absEcart, 10.0, 20.0, r);
            if (r.deviation > 0) {
                r.marketImpact = "US_IJC_BAD";
                r.directionText = "⚠️ HAUSSE CHÔMAGE US: USD ↘️, GOLD ↗️, NASDAQ ↗️";
            } else {
                r.marketImpact = "US_IJC_GOOD";
                r.directionText = "🦅 BAISSE CHÔMAGE US: USD ↗️, GOLD ↘️, USOIL ↗️";
            }
        }
        // Ventes au détail
        else if (combined.contains("RETAIL SALES")) {
            attribuerPoids(absEcart, 0.3, 0.7, r);
            if (r.deviation > 0) {
                r.marketImpact = "US_RETAIL_STRONG";
                r.directionText = "🛍️ VENTES AU DÉTAIL FORTES US: USD ↗️, NASDAQ/SP500 ↗️";
            } else {
                r.marketImpact = "US_RETAIL_WEAK";
                r.directionText = "📉 VENTES FAIBLES US: USD ↘️, GOLD ↗️";
            }
        }
        // Confiance consommateurs
        else if (combined.contains("CONSUMER CONFIDENCE") || combined.contains("MICHIGAN")) {
            attribuerPoids(absEcart, 2.0, 5.0, r);
            if (r.deviation > 0) {
                r.marketImpact = "US_CONFIDENCE_HIGH";
                r.directionText = "🧠 CONFIANCE HAUTE US: USD ↗️, INDICES ↗️";
            } else {
                r.marketImpact = "US_CONFIDENCE_LOW";
                r.directionText = "📉 CONFIANCE BASSE US: USD ↘️, GOLD ↗️";
            }
        }
        // Production industrielle
        else if (combined.contains("INDUSTRIAL PRODUCTION") || combined.contains("CAPACITY UTILIZATION")) {
            attribuerPoids(absEcart, 0.3, 0.7, r);
            if (r.deviation > 0) {
                r.marketImpact = "US_INDUSTRIAL_UP";
                r.directionText = "🏭 PRODUCTION INDUSTRIELLE FORTE: USD ↗️, USOIL ↗️, NASDAQ/SP500 ↗️";
            } else {
                r.marketImpact = "US_INDUSTRIAL_DOWN";
                r.directionText = "📉 PRODUCTION INDUSTRIELLE FAIBLE: USD ↘️, USOIL ↘️";
            }
        }
        // Biens durables
        else if (combined.contains("DURABLE GOODS")) {
            attribuerPoids(absEcart, 0.5, 1.0, r);
            if (r.deviation > 0) {
                r.marketImpact = "US_DURABLE_STRONG";
                r.directionText = "📦 COMMANDES DE BIENS DURABLES FORTES: USD ↗️, NASDAQ ↗️";
            } else {
                r.marketImpact = "US_DURABLE_WEAK";
                r.directionText = "📦 COMMANDES FAIBLES: USD ↘️";
            }
        }
        // Mises en chantier / permis de construire
        else if (combined.contains("HOUSING STARTS") || combined.contains("BUILDING PERMITS")) {
            attribuerPoids(absEcart, 50.0, 150.0, r);
            if (r.deviation > 0) {
                r.marketImpact = "US_HOUSING_STRONG";
                r.directionText = "🏠 MARCHÉ IMMOBILIER FORT: USD ↗️, NASDAQ ↗️";
            } else {
                r.marketImpact = "US_HOUSING_WEAK";
                r.directionText = "🏠 MARCHÉ IMMOBILIER FAIBLE: USD ↘️, GOLD ↗️";
            }
        }
        // Balance commerciale
        else if (combined.contains("TRADE BALANCE")) {
            attribuerPoids(absEcart, 2.0, 5.0, r);
            if (r.deviation < 0) { // déficit plus élevé (négatif plus grand) -> USD faible
                r.marketImpact = "US_TRADE_DEFICIT_WIDENS";
                r.directionText = "📉 DÉFICIT COMMERCIAL CREUX: USD ↘️, GOLD ↗️";
            } else {
                r.marketImpact = "US_TRADE_IMPROVES";
                r.directionText = "📈 DÉFICIT COMMERCIAL SE RESSERRE: USD ↗️";
            }
        }
        // Stocks pétrole
        else if (combined.contains("OIL") || combined.contains("EIA") || combined.contains("INVENTORIES")) {
            attribuerPoids(absEcart, 2.0, 4.5, r);
            if (r.deviation > 0) {
                r.marketImpact = "OIL_INVENTORIES_SURPLUS";
                r.directionText = "🛢️ SURPLUS STOCKS PÉTROLE: USOIL ↘️";
            } else {
                r.marketImpact = "OIL_INVENTORIES_DEFICIT";
                r.directionText = "🛢️ DÉFICIT STOCKS PÉTROLE: USOIL ↗️";
            }
        }
        // ✅ Average Hourly Earnings — proxy inflation salariale
       else if (combined.contains("AVERAGE HOURLY EARNINGS") ||
         combined.contains("HOURLY EARNINGS") ||
         combined.contains("WAGE GROWTH") ||
         combined.contains("AVG HOURLY")) {
          attribuerPoids(absEcart, 0.1, 0.3, r);
            if (r.deviation > 0) {
             r.marketImpact = "US_WAGES_HOT";
             r.directionText = "🦅 SALAIRES ÉLEVÉS US: USD ↗️, GOLD ↘️, NASDAQ ↘️ (pression inflation)";
            } else {
               r.marketImpact = "US_WAGES_COOL";
               r.directionText = "🕊️ SALAIRES FAIBLES US: USD ↘️, GOLD ↗️, NASDAQ ↗️ (détente inflation)";
             }
        }
        else {
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
        } else if (combined.contains("IFO")) {
            attribuerPoids(absEcart, 0.5, 1.0, r);
            if (r.deviation > 0) {
                r.marketImpact = "EUR_IFO_UP";
                r.directionText = "📈 INDICATEUR IFO ALLEMAGNE FORT: EUR ↗️, EURUSD ↗️";
            } else {
                r.marketImpact = "EUR_IFO_DOWN";
                r.directionText = "📉 INDICATEUR IFO FAIBLE: EUR ↘️, EURUSD ↘️";
            }
        } else if (combined.contains("ZEW")) {
            attribuerPoids(absEcart, 5.0, 15.0, r);
            if (r.deviation > 0) {
                r.marketImpact = "EUR_ZEW_UP";
                r.directionText = "📈 SENTIMENT ZEW EN HAUSSE: EUR ↗️";
            } else {
                r.marketImpact = "EUR_ZEW_DOWN";
                r.directionText = "📉 SENTIMENT ZEW EN BAISSE: EUR ↘️";
            }
        } else if (combined.contains("RETAIL SALES") || combined.contains("CONSUMER SPENDING")) {
            attribuerPoids(absEcart, 0.3, 0.7, r);
            r.marketImpact = r.deviation > 0 ? "EUR_RETAIL_STRONG" : "EUR_RETAIL_WEAK";
            r.directionText = r.deviation > 0 ? "📈 CONSOMMATION EURO FORTE: EUR ↗️" : "📉 CONSOMMATION EURO FAIBLE: EUR ↘️";
        } else if (combined.contains("UNEMPLOYMENT") || combined.contains("JOBLESS")) {
            attribuerPoids(absEcart, 0.1, 0.2, r);
            r.marketImpact = r.deviation > 0 ? "EUR_UNEMPLOYMENT_HIGH" : "EUR_UNEMPLOYMENT_LOW";
            r.directionText = r.deviation > 0 ? "⚠️ CHÔMAGE HAUT EURO: EUR ↘️" : "✅ CHÔMAGE BAS EURO: EUR ↗️";
        } else if (combined.contains("INDUSTRIAL PRODUCTION")) {
            attribuerPoids(absEcart, 0.5, 1.0, r);
            r.marketImpact = r.deviation > 0 ? "EUR_INDUSTRIAL_UP" : "EUR_INDUSTRIAL_DOWN";
            r.directionText = r.deviation > 0 ? "🏭 PRODUCTION INDUSTRIELLE EUROPÉENNE FORTE: EUR ↗️" : "📉 PRODUCTION INDUSTRIELLE EUROPÉENNE FAIBLE: EUR ↘️";
        } else if (combined.contains("TRADE BALANCE")) {
            attribuerPoids(absEcart, 1.0, 2.0, r);
            r.marketImpact = r.deviation > 0 ? "EUR_TRADE_SURPLUS" : "EUR_TRADE_DEFICIT";
            r.directionText = r.deviation > 0 ? "📈 EXCÉDENT COMMERCIAL EURO: EUR ↗️" : "📉 DÉFICIT COMMERCIAL EURO: EUR ↘️";
        } else {
            attribuerPoids(absEcart, 1.0, 2.0, r);
            r.marketImpact = "EUR_MACRO_OTHER";
            r.directionText = r.deviation > 0 ? "Donnée zone euro supérieure" : "Donnée zone euro inférieure";
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
        } else if (combined.contains("AVERAGE EARNINGS")) {
            attribuerPoids(absEcart, 0.2, 0.5, r);
            r.marketImpact = r.deviation > 0 ? "GBP_EARNINGS_UP" : "GBP_EARNINGS_DOWN";
            r.directionText = r.deviation > 0 ? "💰 SALAIRES EN HAUSSE UK: GBP ↗️ (pression BoE)" : "💰 SALAIRES EN BAISSE UK: GBP ↘️";
        } else if (combined.contains("CLAIMANT COUNT")) {
            attribuerPoids(absEcart, 5.0, 15.0, r);
            r.marketImpact = r.deviation > 0 ? "GBP_CLAIMANTS_UP" : "GBP_CLAIMANTS_DOWN";
            r.directionText = r.deviation > 0 ? "⚠️ HAUSSE DEMANDEURS D'EMPLOI UK: GBP ↘️" : "✅ BAISSE DEMANDEURS D'EMPLOI UK: GBP ↗️";
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
        } else if (combined.contains("TANKAN")) {
            attribuerPoids(absEcart, 2.0, 5.0, r);
            r.marketImpact = r.deviation > 0 ? "JPY_TANKAN_UP" : "JPY_TANKAN_DOWN";
            r.directionText = r.deviation > 0 ? "📈 ENQUÊTE TANKAN JAPON ORIENTÉE HAUSSE: JPY ↗️, USDJPY ↘️" : "📉 ENQUÊTE TANKAN EN BAISSE: JPY ↘️, USDJPY ↗️";
        } else if (combined.contains("INDUSTRIAL PRODUCTION") || combined.contains("TRADE BALANCE")) {
            attribuerPoids(absEcart, 0.5, 1.0, r);
            r.marketImpact = r.deviation > 0 ? "JPY_INDUSTRY_UP" : "JPY_INDUSTRY_DOWN";
            r.directionText = r.deviation > 0 ? "🏭 PRODUCTION INDUSTRIELLE JAPON FORTE: JPY ↗️" : "📉 PRODUCTION INDUSTRIELLE JAPON FAIBLE: JPY ↘️";
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
        } else if (combined.contains("IVEY PMI")) {
            attribuerPoids(absEcart, 0.6, 1.2, r);
            r.marketImpact = r.deviation > 0 ? "CAD_IVEY_UP" : "CAD_IVEY_DOWN";
            r.directionText = r.deviation > 0 ? "🏭 PMI IVEY CANADA EN HAUSSE: CAD ↗️" : "🛑 PMI IVEY CANADA EN BAISSE: CAD ↘️";
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
        } else if (combined.contains("NAB BUSINESS CONFIDENCE")) {
            attribuerPoids(absEcart, 2.0, 5.0, r);
            r.marketImpact = r.deviation > 0 ? "AUD_NAB_UP" : "AUD_NAB_DOWN";
            r.directionText = r.deviation > 0 ? "📈 CONFIDENCE NAB AUSTRALIE EN HAUSSE: AUD ↗️" : "📉 CONFIDENCE NAB EN BAISSE: AUD ↘️";
        } else if (combined.contains("WESTPAC") || combined.contains("CONSUMER SENTIMENT")) {
            attribuerPoids(absEcart, 2.0, 5.0, r);
            r.marketImpact = r.deviation > 0 ? "AUD_WESTPAC_UP" : "AUD_WESTPAC_DOWN";
            r.directionText = r.deviation > 0 ? "🧠 SENTIMENT WESTPAC AUSTRALIE EN HAUSSE: AUD ↗️" : "📉 SENTIMENT WESTPAC EN BAISSE: AUD ↘️";
        } else if (combined.contains("TRADE BALANCE")) {
            attribuerPoids(absEcart, 0.5, 1.0, r);
            r.marketImpact = r.deviation > 0 ? "AUD_TRADE_SURPLUS" : "AUD_TRADE_DEFICIT";
            r.directionText = r.deviation > 0 ? "📈 EXCÉDENT COMMERCIAL AUSTRALIE: AUD ↗️" : "📉 DÉFICIT COMMERCIAL AUSTRALIE: AUD ↘️";
        }
        // Indicateurs chinois (proxy AUD)
        else if (combined.contains("CAIXIN PMI")) {
            attribuerPoids(absEcart, 0.6, 1.2, r);
            r.marketImpact = r.deviation > 0 ? "CHINA_CAIXIN_UP" : "CHINA_CAIXIN_DOWN";
            r.directionText = r.deviation > 0 ? "🏭 PMI CAIXIN CHINE EXPANSION: AUD ↗️, USOIL ↗️, GOLD ↗️" : "🛑 PMI CAIXIN CHINE CONTRACTION: AUD ↘️, USOIL ↘️";
        } else if (combined.contains("CHINA") && (combined.contains("GDP") || combined.contains("PIB"))) {
            attribuerPoids(absEcart, 0.2, 0.5, r);
            r.marketImpact = r.deviation > 0 ? "CHINA_GDP_UP" : "CHINA_GDP_DOWN";
            r.directionText = r.deviation > 0 ? "📈 CROISSANCE CHINOISE FORTE: AUD ↗️, USOIL ↗️" : "📉 CROISSANCE CHINOISE FAIBLE: AUD ↘️, USOIL ↘️";
        } else {
            attribuerPoids(absEcart, 1.0, 2.0, r);
            r.marketImpact = "AUD_MACRO_OTHER";
            r.directionText = r.deviation > 0 ? "Donnée Australie/Chine supérieure" : "Donnée Australie/Chine inférieure";
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
        } 
        // ✅ AJOUT POUR ADP (et autres rapports d'emploi majeurs)
        else if (text.contains("ADP") || text.contains("EMPLOYMENT REPORT")) {
            result.weight = 3;  // ou 4 selon l'importance que vous voulez donner
            result.marketImpact = "ADP_REPORT";
            result.directionText = "📊 RAPPORT ADP – Impact modéré à élevé sur USD et indices.";
        }
        else {
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
        // ✅ Extraction de la valeur précédente de référence (Prior/Previous)
        String[] priorPatterns = {
            "PRIOR:\\s*([0-9.\\-]+)", "PREVIOUS:\\s*([0-9.\\-]+)", "PRIOR\\s*[=:]\\s*([0-9.\\-]+)"
        };
        for (String p : priorPatterns) {
            double val = chercherRegex(texteNettoye, p);
            if (!Double.isNaN(val)) { values.previous = val; break; }
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
