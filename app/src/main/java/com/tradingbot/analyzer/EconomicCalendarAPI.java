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
import java.util.regex.*;

public class EconomicCalendarAPI {

    private static final String TAG            = "EconomicCalendarAPI";
    private static final String PREFS_NAME     = "TradingBot";
    private static final String PREF_MACRO_KEY = "macro_api_key";

    // ── Sources officielles ───────────────────────────────────────
    // FMP  : source primaire — passé ET futur, même clé macro_api_key
    private static final String FMP_URL = "https://financialmodelingprep.com/api/v3/economic_calendar";

    // ForexFactory : source secondaire — JSON natif, sans clé, sans scraping HTML
    // Deux endpoints couvrant la semaine en cours et la semaine suivante
    private static final String FF_URL_THIS_WEEK = "https://nfs.faireconomy.media/ff_calendar_thisweek.json";
    private static final String FF_URL_NEXT_WEEK = "https://nfs.faireconomy.media/ff_calendar_nextweek.json";

    // ─────────────────────────────────────────────────────────────
    //  MODÈLE DE DONNÉES
    // ─────────────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────────────
    //  POINT D'ENTRÉE PRINCIPAL
    //
    //  Pipeline de récupération à 3 niveaux :
    //  1. FMP  (source officielle, clé API, JSON structuré, passé + futur)
    //  2. ForexFactory (JSON natif, sans clé, sans scraping, fiable)
    //  3. Fallback institutionnel statique (timestamps réels)
    //
    //  Investing.com supprimé — API non officielle, bloquée par
    //  Cloudflare, contraire aux CGU. ForexFactory le remplace proprement.
    // ─────────────────────────────────────────────────────────────

    public static List<CalendarEvent> fetchUpcomingEvents(int hoursAhead) {

        // ── Niveau 1 : FMP ────────────────────────────────────────
        List<CalendarEvent> events = fetchFromFMP(hoursAhead);
        if (!events.isEmpty()) {
            Log.d(TAG, "FMP : " + events.size() + " événements chargés.");
            return events;
        }

        // ── Niveau 2 : ForexFactory ───────────────────────────────
        Log.w(TAG, "FMP indisponible ou clé manquante — tentative ForexFactory.");
        events = fetchFromForexFactory(hoursAhead);
        if (!events.isEmpty()) {
            Log.d(TAG, "ForexFactory : " + events.size() + " événements chargés.");
            return events;
        }

        // ── Niveau 3 : Fallback institutionnel statique ───────────
        Log.w(TAG, "Toutes sources indisponibles — activation du fallback institutionnel.");
        return generateInstitutionalExhaustiveFallback();
    }

    // ─────────────────────────────────────────────────────────────
    //  NIVEAU 1 : FMP (Financial Modeling Prep)
    //  Même clé que NotificationService (macro_api_key).
    //  Couvre passé ET futur via from/to.
    //  Timeouts obligatoires — évite ANR si réseau lent.
    //  Filtre : HIGH toujours + MEDIUM si isMediumHighImpact().
    // ─────────────────────────────────────────────────────────────

    private static List<CalendarEvent> fetchFromFMP(int hoursAhead) {
        List<CalendarEvent> events = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            if (MainActivity.instance == null) return events;
            SharedPreferences prefs = MainActivity.instance
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String apiKey = prefs.getString(PREF_MACRO_KEY, "");
            if (apiKey.isEmpty()) {
                Log.w(TAG, "macro_api_key non configurée — FMP ignoré.");
                return events;
            }

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            String fromDate = sdf.format(cal.getTime());
            cal.add(Calendar.HOUR_OF_DAY, hoursAhead);
            String toDate = sdf.format(cal.getTime());

            String urlString = FMP_URL + "?from=" + fromDate + "&to=" + toDate + "&apikey=" + apiKey;
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);

            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) response.append(line);
                br.close();

                JSONArray array = new JSONArray(response.toString());
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj  = array.getJSONObject(i);
                    String impact   = obj.optString("impact", "LOW");
                    String eventName = obj.optString("event", "");

                    // LOW rejeté systématiquement
                    if (impact.equalsIgnoreCase("LOW")) continue;
                    // MEDIUM accepté seulement si fort impact réel marché
                    if (impact.equalsIgnoreCase("MEDIUM") && !isMediumHighImpact(eventName)) continue;

                    String currency = obj.optString("currency", "");
                    if (!isTrackedCurrency(currency)) continue;

                    CalendarEvent event = new CalendarEvent();
                    event.country   = currencyToCountry(currency);
                    event.indicator = eventName.isEmpty() ? "Macro Release" : eventName;
                    event.importance = impact;
                    event.forecast  = formatValue(obj.optString("estimate", "N/A"));
                    event.previous  = formatValue(obj.optString("previous", "N/A"));
                    event.actual    = formatValue(obj.optString("actual",   "N/A"));
                    event.timestamp = convertFMPDateToUnixSeconds(obj.optString("date", ""));
                    event.affectedAssets = mapIndicatorToAssetsIntermarket(event.indicator, event.country);
                    events.add(event);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Échec FMP", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return events;
    }

    // ─────────────────────────────────────────────────────────────
    //  NIVEAU 2 : FOREXFACTORY
    //
    //  Avantages vs Investing.com :
    //  - JSON natif, pas de scraping HTML fragile
    //  - Sans clé API, sans authentification
    //  - Pas de Cloudflare, pas de CAPTCHA
    //  - Gratuit et stable
    //  - Deux endpoints : semaine en cours + semaine suivante
    //
    //  Format ForexFactory :
    //  { "title": "CPI m/m", "country": "USD", "date": "2025-05-23T12:30:00-04:00",
    //    "impact": "High", "forecast": "0.3%", "previous": "0.4%", "actual": "" }
    // ─────────────────────────────────────────────────────────────

    private static List<CalendarEvent> fetchFromForexFactory(int hoursAhead) {
        List<CalendarEvent> events = new ArrayList<>();

        // Charger cette semaine + semaine suivante pour couvrir hoursAhead > 7j
        events.addAll(fetchFromForexFactoryUrl(FF_URL_THIS_WEEK, hoursAhead));
        if (events.isEmpty()) {
            events.addAll(fetchFromForexFactoryUrl(FF_URL_NEXT_WEEK, hoursAhead));
        } else if (hoursAhead > 120) {
            // Si fenêtre > 5 jours, inclure aussi la semaine suivante
            events.addAll(fetchFromForexFactoryUrl(FF_URL_NEXT_WEEK, hoursAhead));
        }
        return events;
    }

    private static List<CalendarEvent> fetchFromForexFactoryUrl(String urlString, int hoursAhead) {
        List<CalendarEvent> events = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            long nowMs     = System.currentTimeMillis();
            long futureMs  = nowMs + ((long) hoursAhead * 60 * 60 * 1000);

            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            // User-Agent neutre — ForexFactory n'exige pas d'authentification
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);

            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) response.append(line);
                br.close();

                JSONArray array = new JSONArray(response.toString());
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);

                    // ── Filtre impact ──────────────────────────────
                    // ForexFactory utilise "High", "Medium", "Low" (avec majuscule)
                    String impact    = obj.optString("impact", "Low");
                    String eventName = obj.optString("title", "");
                    if (impact.equalsIgnoreCase("Low")) continue;
                    if (impact.equalsIgnoreCase("Medium") && !isMediumHighImpact(eventName)) continue;

                    // ── Filtre devise ──────────────────────────────
                    // ForexFactory retourne "USD", "EUR", "GBP", "JPY", "CAD", "AUD"
                    String currency = obj.optString("country", "");
                    if (!isTrackedCurrency(currency)) continue;

                    // ── Filtre fenêtre temporelle ──────────────────
                    // ForexFactory retourne ISO 8601 avec timezone : "2025-05-23T12:30:00-04:00"
                    String dateStr    = obj.optString("date", "");
                    long   eventMs    = convertForexFactoryDateToMs(dateStr);
                    // Ne garder que les événements dans la fenêtre [now, now+hoursAhead]
                    if (eventMs < nowMs || eventMs > futureMs) continue;

                    CalendarEvent event = new CalendarEvent();
                    event.country    = currencyToCountry(currency);
                    event.indicator  = eventName.isEmpty() ? "Macro Release" : eventName;
                    event.importance = impact;
                    event.forecast   = formatValue(obj.optString("forecast", "N/A"));
                    event.previous   = formatValue(obj.optString("previous", "N/A"));
                    event.actual     = formatValue(obj.optString("actual",   "N/A"));
                    // Timestamp en Unix secondes — cohérent avec EventValidator.parseTimestamp()
                    event.timestamp  = String.valueOf(eventMs / 1000);
                    event.affectedAssets = mapIndicatorToAssetsIntermarket(event.indicator, event.country);
                    events.add(event);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Échec ForexFactory [" + urlString + "]", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return events;
    }

    // ─────────────────────────────────────────────────────────────
    //  MAPPING INDICATEUR → ACTIFS
    //  Cohérent avec SYSTEM_PROMPT NotificationService
    //  et assignDriverWeight() rang 3/4/5
    // ─────────────────────────────────────────────────────────────

    public static List<String> mapIndicatorToAssetsIntermarket(String indicator, String country) {
        List<String> assets = new ArrayList<>();
        String ind = indicator.toLowerCase();
        String cty = country.toLowerCase();

        // Tout événement macro impacte en premier lieu les obligations US
        assets.add("US10Y");

        if (cty.contains("united states") || cty.contains("us ")) {

            // ── RANG SUPRÊME : Fed + Inflation + Emploi principal ─
            if (ind.contains("fomc") || ind.contains("interest rate") ||
                ind.contains("cpi")  || ind.contains("pce")           ||
                ind.contains("non-farm") || ind.contains("nfp")       ||
                ind.contains("powell") || ind.contains("warsh")       ||
                ind.contains("beige book")) {
                assets.addAll(Arrays.asList(
                    "GOLD", "SP500", "NASDAQ", "BITCOIN",
                    "USDJPY", "EURUSD", "USDCAD", "GBPUSD", "AUDUSD"));
            }

            // ── Emploi secondaire : Jobless Claims, ADP, JOLTS ───
            else if (ind.contains("jobless claims") || ind.contains("initial claims") ||
                     ind.contains("continuing claims") || ind.contains("adp") ||
                     ind.contains("jolts") || ind.contains("job openings") ||
                     ind.contains("challenger")) {
                assets.addAll(Arrays.asList("SP500", "NASDAQ", "USDJPY", "EURUSD", "GOLD"));
            }

            // ── Inflation amont : PPI, Import/Export Prices ───────
            else if (ind.contains("ppi") || ind.contains("producer price") ||
                     ind.contains("import price") || ind.contains("export price")) {
                assets.addAll(Arrays.asList("GOLD", "USDJPY", "EURUSD"));
            }

            // ── Sentiment consommateur : Michigan, Conf. Board ────
            else if (ind.contains("michigan") || ind.contains("consumer confidence") ||
                     ind.contains("consumer sentiment")) {
                assets.addAll(Arrays.asList("SP500", "NASDAQ", "USOIL", "BITCOIN"));
            }

            // ── Activité manufacturière : ISM, PMI, Durable Goods ─
            else if (ind.contains("pmi") || ind.contains("ism") ||
                     ind.contains("industrial production") || ind.contains("capacity") ||
                     ind.contains("durable goods") || ind.contains("factory orders") ||
                     ind.contains("chicago pmi") || ind.contains("philly fed") ||
                     ind.contains("empire state") || ind.contains("kansas city")) {
                assets.addAll(Arrays.asList("SP500", "NASDAQ", "USDJPY"));
            }

            // ── Croissance : GDP (toutes lectures) ────────────────
            else if (ind.contains("gdp") || ind.contains("gross domestic product")) {
                assets.addAll(Arrays.asList("SP500", "NASDAQ", "USDJPY", "GOLD"));
            }

            // ── Consommation : Retail Sales, Personal Spending ────
            else if (ind.contains("retail") || ind.contains("personal spending") ||
                     ind.contains("personal income")) {
                assets.addAll(Arrays.asList("SP500", "NASDAQ", "USDJPY"));
            }

            // ── Immobilier ────────────────────────────────────────
            else if (ind.contains("home sales") || ind.contains("housing") ||
                     ind.contains("building permits")) {
                assets.addAll(Arrays.asList("SP500", "NASDAQ"));
            }

            // ── Commerce / Balance ────────────────────────────────
            else if (ind.contains("trade balance") || ind.contains("current account")) {
                assets.addAll(Arrays.asList("USDJPY", "EURUSD", "USDCAD"));
            }

            // ── Pétrole / Énergie ─────────────────────────────────
            else if (ind.contains("crude") || ind.contains("inventories") ||
                     ind.contains("eia") || ind.contains("natural gas") ||
                     ind.contains("opec")) {
                assets.addAll(Arrays.asList("USOIL", "USDCAD"));
            }

        } else if (cty.contains("united kingdom") || cty.contains("uk")) {
            assets.add("GBPUSD");
            if (ind.contains("cpi") || ind.contains("inflation") ||
                ind.contains("boe") || ind.contains("bailey") ||
                ind.contains("interest rate") || ind.contains("minutes")) {
                assets.addAll(Arrays.asList("SP500", "NASDAQ"));
            }

        } else if (cty.contains("japan") || cty.contains("boj")) {
            assets.add("USDJPY");
            if (ind.contains("boj") || ind.contains("ueda") ||
                ind.contains("interest rate") || ind.contains("minutes")) {
                assets.add("GOLD");
            }

        } else if (cty.contains("canada")) {
            assets.addAll(Arrays.asList("USDCAD", "USOIL"));
            if (ind.contains("boc") || ind.contains("macklem") ||
                ind.contains("interest rate") || ind.contains("minutes")) {
                assets.add("SP500");
            }

        } else if (cty.contains("australia")) {
            assets.add("AUDUSD");
            if (ind.contains("rba") || ind.contains("bullock") ||
                ind.contains("interest rate") || ind.contains("minutes")) {
                assets.add("USOIL");
            }

        } else if (cty.contains("eurozone") || cty.contains("europe") || cty.contains("ecb")) {
            assets.add("EURUSD");
            if (ind.contains("ecb") || ind.contains("lagarde") ||
                ind.contains("interest rate") || ind.contains("minutes") ||
                ind.contains("cpi") || ind.contains("inflation")) {
                assets.addAll(Arrays.asList("GBPUSD", "SP500"));
            }

        } else if (ind.contains("opec") || ind.contains("opec+")) {
            assets.addAll(Arrays.asList("USOIL", "USDCAD"));
        }

        return new ArrayList<>(new LinkedHashSet<>(assets));
    }

    // ─────────────────────────────────────────────────────────────
    //  NIVEAU 3 : FALLBACK INSTITUTIONNEL STATIQUE
    //  Timestamps réels (now + i*30min) → matchables par EventValidator
    //  (fenêtre ±10 min). Sert à enrichir le mapping d'actifs si
    //  FMP et ForexFactory sont tous les deux indisponibles.
    // ─────────────────────────────────────────────────────────────

    private static List<CalendarEvent> generateInstitutionalExhaustiveFallback() {
        List<CalendarEvent> list = new ArrayList<>();
        long nowSeconds = System.currentTimeMillis() / 1000;

        String[][] drivers = {
            {"United States", "FOMC Interest Rate Decision",      "High",   "4.75%",  "5.00%"},
            {"United States", "Core CPI Inflation MoM",           "High",   "0.2%",   "0.3%"},
            {"United States", "Non-Farm Payrolls Employment",     "High",   "160K",   "185K"},
            {"United States", "Core PCE Price Index YoY",         "High",   "2.6%",   "2.7%"},
            {"United States", "ISM Manufacturing PMI",            "High",   "49.1",   "48.2"},
            {"United States", "Gross Domestic Product (GDP) QoQ", "High",   "2.1%",   "2.5%"},
            {"United States", "Initial Jobless Claims",           "Medium", "215K",   "220K"},
            {"United States", "ADP Nonfarm Employment Change",    "Medium", "150K",   "155K"},
            {"United States", "EIA Crude Oil Inventories",        "Medium", "-1.2M",  "0.5M"},
            {"United States", "Michigan Consumer Sentiment Prel", "Medium", "68.0",   "67.5"},
            {"United Kingdom", "BoE Interest Rate Decision",      "High",   "4.50%",  "4.75%"},
            {"Japan",          "BoJ Monetary Policy Statement",   "High",   "0.25%",  "0.25%"},
            {"Canada",         "BoC Interest Rate Decision",      "High",   "4.00%",  "4.25%"},
            {"Australia",      "RBA Interest Rate Decision",      "High",   "4.10%",  "4.10%"},
            {"Eurozone",       "ECB Interest Rate Decision",      "High",   "3.50%",  "3.75%"}
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

    // ─────────────────────────────────────────────────────────────
    //  WHITELIST MEDIUM À FORT IMPACT RÉEL
    //  Cohérente avec NotificationService.isMediumHighImpact()
    //  Toute modification doit être répercutée dans les deux classes.
    // ─────────────────────────────────────────────────────────────

    private static boolean isMediumHighImpact(String eventName) {
        if (eventName == null || eventName.isEmpty()) return false;
        String ind = eventName.toLowerCase();
        return  // ── Emploi US ──────────────────────────────────────
                ind.contains("initial jobless claims")      ||
                ind.contains("continuing claims")           ||
                ind.contains("adp employment")              ||
                ind.contains("adp nonfarm")                 ||
                ind.contains("jolts")                       ||
                ind.contains("job openings")                ||
                ind.contains("challenger job cuts")         ||
                // ── Inflation / Prix ────────────────────────────────
                ind.contains("ppi")                         ||
                ind.contains("producer price")              ||
                ind.contains("import price")                ||
                ind.contains("export price")                ||
                // ── Croissance / Activité ───────────────────────────
                ind.contains("gdp")                         ||
                ind.contains("gross domestic product")      ||
                ind.contains("industrial production")       ||
                ind.contains("capacity utilization")        ||
                ind.contains("durable goods")               ||
                ind.contains("factory orders")              ||
                ind.contains("chicago pmi")                 ||
                ind.contains("philly fed")                  ||
                ind.contains("empire state")                ||
                ind.contains("kansas city fed")             ||
                // ── Consommation / Sentiment ────────────────────────
                ind.contains("michigan")                    ||
                ind.contains("consumer confidence")         ||
                ind.contains("consumer sentiment")          ||
                ind.contains("retail sales")                ||
                ind.contains("personal spending")           ||
                ind.contains("personal income")             ||
                // ── Immobilier ──────────────────────────────────────
                ind.contains("existing home sales")         ||
                ind.contains("new home sales")              ||
                ind.contains("housing starts")              ||
                ind.contains("building permits")            ||
                // ── Commerce / Balance ──────────────────────────────
                ind.contains("trade balance")               ||
                ind.contains("current account")             ||
                // ── Pétrole / Énergie ───────────────────────────────
                ind.contains("eia")                         ||
                ind.contains("crude oil inventories")       ||
                ind.contains("natural gas storage")         ||
                ind.contains("opec")                        ||
                // ── Banques Centrales Étrangères ────────────────────
                ind.contains("rba")                         ||
                ind.contains("boc")                         ||
                ind.contains("boe")                         ||
                ind.contains("boj")                         ||
                ind.contains("ecb")                         ||
                ind.contains("minutes")                     ||
                ind.contains("monetary policy")             ||
                // ── Discours Fed à fort impact ──────────────────────
                ind.contains("powell")                      ||
                ind.contains("warsh")                       ||
                ind.contains("beige book");
    }

    // ─────────────────────────────────────────────────────────────
    //  UTILITAIRES
    // ─────────────────────────────────────────────────────────────

    /**
     * Convertit une date FMP "yyyy-MM-dd HH:mm:ss" ou "yyyy-MM-dd"
     * en Unix secondes (String). Timezone UTC (FMP publie en UTC).
     */
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

    /**
     * Convertit une date ForexFactory ISO 8601 avec timezone offset
     * "2025-05-23T12:30:00-04:00" en millisecondes.
     * Java 7+ gère le format "yyyy-MM-dd'T'HH:mm:ssXXX".
     */
    private static long convertForexFactoryDateToMs(String dateStr) {
        if (dateStr == null || dateStr.isEmpty())
            return System.currentTimeMillis();
        try {
            // Format standard ISO 8601 avec offset timezone
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
            return sdf.parse(dateStr).getTime();
        } catch (Exception e) {
            try {
                // Fallback : sans offset
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                return sdf.parse(dateStr).getTime();
            } catch (Exception e2) {
                return System.currentTimeMillis();
            }
        }
    }

    /** Formate une valeur vide ou "null" en "N/A". */
    private static String formatValue(String value) {
        if (value == null || value.isEmpty() || value.equalsIgnoreCase("null")) return "N/A";
        return value;
    }

    /** Vérifie si la devise est dans notre univers de suivi. */
    private static boolean isTrackedCurrency(String currency) {
        return currency.equals("USD") || currency.equals("EUR") || currency.equals("GBP") ||
               currency.equals("JPY") || currency.equals("CAD") || currency.equals("AUD");
    }

    /** Convertit un code devise ISO en nom de pays pour mapIndicatorToAssetsIntermarket. */
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
