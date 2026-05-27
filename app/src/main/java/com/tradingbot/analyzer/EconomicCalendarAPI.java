package com.tradingbot.analyzer;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class EconomicCalendarAPI {

    private static final String TAG            = "EconomicCalendarAPI";
    private static final String PREFS_NAME     = "TradingBot";
    private static final String PREF_MACRO_KEY = "macro_api_key";

    private static final String FMP_URL = "https://financialmodelingprep.com/api/v3/economic_calendar";
    private static final String FF_URL_THIS_WEEK = "https://nfs.faireconomy.media/ff_calendar_thisweek.json";
    private static final String FF_URL_NEXT_WEEK = "https://nfs.faireconomy.media/ff_calendar_nextweek.json";

    // Contexte global optionnel pour garantir la compatibilité ascendante avec EventValidator
    private static Context globalAppContext = null;

    public static class CalendarEvent {
        public String       timestamp      = "";
        public String       country        = "Global";
        public String       indicator      = "Macro Economic Release";
        public String       importance     = "Medium";
        public String       forecast       = "N/A";
        public String       previous       = "N/A";
        public String       actual         = "N/A";
        public List<String> affectedAssets = new ArrayList<>();
    }

    /**
     * Permet d'initialiser optionnellement le contexte depuis votre Application class ou NotificationService
     */
    public static void init(Context context) {
        if (context != null) {
            globalAppContext = context.getApplicationContext();
        }
    }

    /**
     * SURCHARGE DE COMPATIBILITÉ : Permet à EventValidator.preloadCalendar() de compiler sans modification
     */
    public static List<CalendarEvent> fetchUpcomingEvents(int hoursAhead) {
        return fetchUpcomingEvents(globalAppContext, hoursAhead);
    }

    /**
     * POINT D'ENTRÉE PRINCIPAL (Pipeline de données à 3 niveaux)
     */
    public static List<CalendarEvent> fetchUpcomingEvents(Context context, int hoursAhead) {
        Context targetContext = (context != null) ? context.getApplicationContext() : globalAppContext;

        // ── Niveau 1 : FMP (Si le contexte est disponible pour lire la clé API) ──
        if (targetContext != null) {
            List<CalendarEvent> events = fetchFromFMP(targetContext, hoursAhead);
            if (!events.isEmpty()) {
                Log.d(TAG, "FMP : " + events.size() + " événements chargés avec succès.");
                return events;
            }
        } else {
            Log.w(TAG, "Aucun contexte fourni pour fetchUpcomingEvents — Sauts vers l'étape ForexFactory.");
        }

        // ── Niveau 2 : ForexFactory ──
        Log.w(TAG, "FMP indisponible, clé manquante ou erreur réseau — tentative ForexFactory.");
        List<CalendarEvent> events = fetchFromForexFactory(hoursAhead);
        if (!events.isEmpty()) {
            Log.d(TAG, "ForexFactory : " + events.size() + " événements chargés.");
            return events;
        }

        // ── Niveau 3 : Fallback institutionnel statique exhaustif ──
        Log.w(TAG, "Toutes sources en ligne indisponibles — activation du fallback de secours.");
        return generateInstitutionalExhaustiveFallback();
    }

    private static List<CalendarEvent> fetchFromFMP(Context context, int hoursAhead) {
        List<CalendarEvent> events = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String apiKey = prefs.getString(PREF_MACRO_KEY, "");
            if (apiKey.isEmpty()) {
                Log.w(TAG, "macro_api_key absente des SharedPreferences — FMP ignoré.");
                return events;
            }

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            
            long nowMs = System.currentTimeMillis();
            long pastMs = nowMs - (24 * 60 * 60 * 1000L); // Fenêtre de sécurité de 24h passées
            long futureMs = nowMs + ((long) hoursAhead * 60 * 60 * 1000L);

            String fromDate = sdf.format(new Date(pastMs));
            String toDate = sdf.format(new Date(futureMs));

            String urlString = FMP_URL + "?from=" + fromDate + "&to=" + toDate + "&apikey=" + apiKey;
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);

            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) response.append(line);
                br.close();

                JSONArray array = new JSONArray(response.toString());
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj  = array.getJSONObject(i);
                    String impact   = obj.optString("impact", "LOW");
                    String eventName = obj.optString("event", "");

                    if (impact.equalsIgnoreCase("LOW")) continue;
                    if (impact.equalsIgnoreCase("MEDIUM") && !isMediumHighImpact(eventName)) continue;

                    String currency = obj.optString("currency", "");
                    if (!isTrackedCurrency(currency)) continue;

                    CalendarEvent event = new CalendarEvent();
                    event.country    = currencyToCountry(currency);
                    event.indicator  = eventName.isEmpty() ? "Macro Release" : eventName;
                    event.importance = impact.toUpperCase(Locale.US);
                    event.forecast   = formatValue(obj.optString("estimate", "N/A"));
                    event.previous   = formatValue(obj.optString("previous", "N/A"));
                    event.actual     = formatValue(obj.optString("actual",   "N/A"));
                    event.timestamp  = convertFMPDateToUnixSeconds(obj.optString("date", ""));
                    event.affectedAssets = mapIndicatorToAssetsIntermarket(event.indicator, event.country);
                    events.add(event);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Échec critique FMP", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return events;
    }

    private static List<CalendarEvent> fetchFromForexFactory(int hoursAhead) {
        List<CalendarEvent> events = new ArrayList<>();
        events.addAll(fetchFromForexFactoryUrl(FF_URL_THIS_WEEK, hoursAhead));
        if (events.isEmpty() || hoursAhead > 120) {
            events.addAll(fetchFromForexFactoryUrl(FF_URL_NEXT_WEEK, hoursAhead));
        }
        return events;
    }

    private static List<CalendarEvent> fetchFromForexFactoryUrl(String urlString, int hoursAhead) {
        List<CalendarEvent> events = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            long nowMs = System.currentTimeMillis();
            long futureMs = nowMs + ((long) hoursAhead * 60 * 60 * 1000);
            long pastThresholdMs = nowMs - (24 * 60 * 60 * 1000); 

            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);

            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) response.append(line);
                br.close();

                JSONArray array = new JSONArray(response.toString());
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);

                    String impact = obj.optString("impact", "Low");
                    String eventName = obj.optString("title", "");
                    if (impact.equalsIgnoreCase("Low")) continue;
                    if (impact.equalsIgnoreCase("Medium") && !isMediumHighImpact(eventName)) continue;

                    String currency = obj.optString("country", "");
                    if (!isTrackedCurrency(currency)) continue;

                    String dateStr = obj.optString("date", "");
                    long eventMs = convertForexFactoryDateToMs(dateStr);
                    
                    if (eventMs < pastThresholdMs || eventMs > futureMs) continue;

                    CalendarEvent event = new CalendarEvent();
                    event.country    = currencyToCountry(currency);
                    event.indicator  = eventName.isEmpty() ? "Macro Release" : eventName;
                    event.importance = impact.toUpperCase(Locale.US);
                    event.forecast   = formatValue(obj.optString("forecast", "N/A"));
                    event.previous   = formatValue(obj.optString("previous", "N/A"));
                    event.actual     = formatValue(obj.optString("actual",   "N/A"));
                    event.timestamp  = String.valueOf(eventMs / 1000);
                    event.affectedAssets = mapIndicatorToAssetsIntermarket(event.indicator, event.country);
                    events.add(event);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Échec de lecture ForexFactory [" + urlString + "]", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return events;
    }

    public static List<String> mapIndicatorToAssetsIntermarket(String indicator, String country) {
        List<String> assets = new ArrayList<>();
        String ind = indicator.toLowerCase(Locale.US);
        String cty = country.toLowerCase(Locale.US);

        assets.add("US10Y"); // Matrice intermarché pivot

        if (cty.contains("united states") || cty.contains("us ")) {
            if (ind.contains("fomc") || ind.contains("interest rate") ||
                ind.contains("cpi")  || ind.contains("pce")           ||
                ind.contains("non-farm") || ind.contains("nfp")       ||
                ind.contains("powell") || ind.contains("warsh")       ||
                ind.contains("beige book")) {
                assets.addAll(Arrays.asList("GOLD", "SP500", "NASDAQ", "BITCOIN", "USDJPY", "EURUSD", "USDCAD", "GBPUSD", "AUDUSD"));
            }
            else if (ind.contains("jobless claims") || ind.contains("initial claims") ||
                     ind.contains("continuing claims") || ind.contains("adp") ||
                     ind.contains("jolts") || ind.contains("job openings")) {
                assets.addAll(Arrays.asList("SP500", "NASDAQ", "USDJPY", "EURUSD", "GOLD"));
            }
            else if (ind.contains("ppi") || ind.contains("producer price")) {
                assets.addAll(Arrays.asList("GOLD", "USDJPY", "EURUSD"));
            }
            else if (ind.contains("michigan") || ind.contains("consumer confidence") || ind.contains("consumer sentiment")) {
                assets.addAll(Arrays.asList("SP500", "NASDAQ", "USOIL", "BITCOIN"));
            }
            else if (ind.contains("pmi") || ind.contains("ism") || ind.contains("industrial production") || ind.contains("durable goods")) {
                assets.addAll(Arrays.asList("SP500", "NASDAQ", "USDJPY"));
            }
            else if (ind.contains("gdp") || ind.contains("gross domestic product")) {
                assets.addAll(Arrays.asList("SP500", "NASDAQ", "USDJPY", "GOLD"));
            }
            else if (ind.contains("retail") || ind.contains("personal spending")) {
                assets.addAll(Arrays.asList("SP500", "NASDAQ", "USDJPY"));
            }
            else if (ind.contains("crude") || ind.contains("inventories") || ind.contains("eia") || ind.contains("opec")) {
                assets.addAll(Arrays.asList("USOIL", "USDCAD"));
            }
        } else if (cty.contains("united kingdom") || cty.contains("uk")) {
            assets.add("GBPUSD");
            if (ind.contains("cpi") || ind.contains("boe") || ind.contains("interest rate")) {
                assets.addAll(Arrays.asList("SP500", "NASDAQ"));
            }
        } else if (cty.contains("japan") || cty.contains("boj")) {
            assets.add("USDJPY");
            if (ind.contains("boj") || ind.contains("interest rate")) {
                assets.add("GOLD");
            }
        } else if (cty.contains("canada")) {
            assets.addAll(Arrays.asList("USDCAD", "USOIL"));
        } else if (cty.contains("australia")) {
            assets.add("AUDUSD");
        } else if (cty.contains("eurozone") || cty.contains("ecb")) {
            assets.add("EURUSD");
        }
        return new ArrayList<>(new LinkedHashSet<>(assets));
    }

    private static List<CalendarEvent> generateInstitutionalExhaustiveFallback() {
        List<CalendarEvent> list = new ArrayList<>();
        long nowSeconds = System.currentTimeMillis() / 1000;

        String[][] drivers = {
            {"United States", "FOMC Interest Rate Decision",      "High",   "4.75%",  "5.00%"},
            {"United States", "Core CPI Inflation MoM",           "High",   "0.2%",   "0.3%"},
            {"United States", "Non-Farm Payrolls Employment",     "High",   "160K",   "185K"},
            {"United States", "Core PCE Price Index YoY",         "High",   "2.6%",   "2.7%"},
            {"United States", "ISM Manufacturing PMI",            "High",   "49.1",   "48.2"},
            {"United States", "Gross Domestic Product (GDP) QoQ", "High",   "2.1%",   "2.5%"}
        };

        for (int i = 0; i < drivers.length; i++) {
            CalendarEvent e  = new CalendarEvent();
            e.timestamp      = String.valueOf(nowSeconds + ((long) i * 1800));
            e.country        = drivers[i][0];
            e.indicator      = drivers[i][1];
            e.importance     = drivers[i][2];
            e.forecast       = drivers[i][3];
            e.previous       = drivers[i][4];
            e.actual         = "N/A";
            e.affectedAssets = mapIndicatorToAssetsIntermarket(e.indicator, e.country);
            list.add(e);
        }
        return list;
    }

    private static boolean isMediumHighImpact(String eventName) {
        if (eventName == null || eventName.isEmpty()) return false;
        String ind = eventName.toLowerCase(Locale.US);
        return ind.contains("initial jobless claims")      ||
               ind.contains("continuing claims")           ||
               ind.contains("adp employment")              ||
               ind.contains("jolts")                       ||
               ind.contains("job openings")                ||
               ind.contains("ppi")                         ||
               ind.contains("producer price")              ||
               ind.contains("gdp")                         ||
               ind.contains("gross domestic product")      ||
               ind.contains("industrial production")       ||
               ind.contains("durable goods")               ||
               ind.contains("chicago pmi")                 ||
               ind.contains("philly fed")                  ||
               ind.contains("empire state")                ||
               ind.contains("michigan")                    ||
               ind.contains("consumer confidence")         ||
               ind.contains("consumer sentiment")          ||
               ind.contains("retail sales")                ||
               ind.contains("building permits")            ||
               ind.contains("eia")                         ||
               ind.contains("crude oil inventories")       ||
               ind.contains("opec")                        ||
               ind.contains("minutes")                     ||
               ind.contains("powell")                      ||
               ind.contains("warsh")                       ||
               ind.contains("beige book");
    }

    private static String convertFMPDateToUnixSeconds(String dateStr) {
        if (dateStr == null || dateStr.isEmpty())
            return String.valueOf(System.currentTimeMillis() / 1000);
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            return String.valueOf(sdf.parse(dateStr).getTime() / 1000);
        } catch (Exception e) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                return String.valueOf(sdf.parse(dateStr).getTime() / 1000);
            } catch (Exception e2) {
                return String.valueOf(System.currentTimeMillis() / 1000);
            }
        }
    }

    private static long convertForexFactoryDateToMs(String dateStr) {
        if (dateStr == null || dateStr.isEmpty())
            return System.currentTimeMillis();
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
            return sdf.parse(dateStr).getTime();
        } catch (Exception e) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                return sdf.parse(dateStr).getTime();
            } catch (Exception e2) {
                return System.currentTimeMillis();
            }
        }
    }

    private static String formatValue(String value) {
        if (value == null || value.isEmpty() || value.equalsIgnoreCase("null")) return "N/A";
        String trimmed = value.trim();
        // Corrige les valeurs décimales tronquées (ex: .3% -> 0.3%)
        if (trimmed.startsWith(".")) {
            trimmed = "0" + trimmed;
        }
        return trimmed;
    }

    private static boolean isTrackedCurrency(String currency) {
        return currency.equals("USD") || currency.equals("EUR") || currency.equals("GBP") ||
               currency.equals("JPY") || currency.equals("CAD") || currency.equals("AUD");
    }

    private static String currencyToCountry(String currency) {
        switch (currency) {
            case "USD": return "United States";
            case "EUR": return "Eurozone";
            case "GBP": return "United Kingdom";
            case "JPY": return "Japan";
            case "CAD": return "Canada";
            case "AUD": return "Australia";
            default:    return "Global";
        }
    }
}
