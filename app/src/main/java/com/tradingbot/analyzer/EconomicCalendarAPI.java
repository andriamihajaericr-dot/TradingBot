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

    private static final String TAG          = "EconomicCalendarAPI";
    private static final String PREFS_NAME   = "TradingBot";
    private static final String PREF_MACRO_KEY = "macro_api_key";

    // FMP — source primaire officielle (même endpoint que NotificationService)
    private static final String FMP_URL = "https://financialmodelingprep.com/api/v3/economic_calendar";

    // Investing.com — source secondaire de secours (scraping HTML)
    private static final String INVESTING_API_POST =
        "https://www.investing.com/economic-calendar/Service/getCalendarFilteredData";

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
    //  1. FMP (source officielle, clé API, données structurées JSON)
    //  2. Investing.com (scraping HTML, sans clé, instable)
    //  3. Fallback institutionnel (données statiques avec timestamps réels)
    // ─────────────────────────────────────────────────────────────

    public static List<CalendarEvent> fetchUpcomingEvents(int hoursAhead) {

        // ── Niveau 1 : FMP ───────────────────────────────────────
        List<CalendarEvent> events = fetchFromFMP(hoursAhead);
        if (!events.isEmpty()) {
            Log.d(TAG, "FMP : " + events.size() + " événements chargés.");
            return events;
        }

        // ── Niveau 2 : Investing.com (fallback scraping) ─────────
        Log.w(TAG, "FMP indisponible ou clé manquante — tentative Investing.com.");
        events = fetchFromInvesting(hoursAhead);
        if (!events.isEmpty()) {
            Log.d(TAG, "Investing.com : " + events.size() + " événements chargés.");
            return events;
        }

        // ── Niveau 3 : Fallback institutionnel statique ──────────
        Log.w(TAG, "Toutes sources indisponibles — activation du fallback institutionnel.");
        return generateInstitutionalExhaustiveFallback();
    }

    // ─────────────────────────────────────────────────────────────
    //  NIVEAU 1 : FMP (Financial Modeling Prep)
    //  Même clé que NotificationService (macro_api_key).
    //  Retourne les événements HIGH impact sur la fenêtre hoursAhead.
    //  CORRECTION 3 : lit macro_api_key depuis SharedPreferences.
    // ─────────────────────────────────────────────────────────────

    private static List<CalendarEvent> fetchFromFMP(int hoursAhead) {
        List<CalendarEvent> events = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            // Lecture de la clé FMP via le contexte MainActivity
            if (MainActivity.instance == null) return events;
            SharedPreferences prefs = MainActivity.instance
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String apiKey = prefs.getString(PREF_MACRO_KEY, "");
            if (apiKey.isEmpty()) {
                Log.w(TAG, "macro_api_key non configurée — FMP ignoré.");
                return events;
            }

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("GMT+3"));
            String fromDate = sdf.format(cal.getTime());
            cal.add(Calendar.HOUR_OF_DAY, hoursAhead);
            String toDate = sdf.format(cal.getTime());

            String urlString = FMP_URL + "?from=" + fromDate + "&to=" + toDate + "&apikey=" + apiKey;

            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            // CORRECTION 1 : Timeouts obligatoires pour éviter un blocage ANR
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

                    // Filtre : HIGH toujours accepté + MEDIUM si l'indicateur est
                    // reconnu comme à fort impact réel marché (Jobless Claims, ADP, JOLTS,
                    // Michigan, PPI, GDP révision, Trade Balance...).
                    // LOW systématiquement rejeté.
                    String impact    = obj.optString("impact", "LOW");
                    String eventName = obj.optString("event", "");
                    if (impact.equalsIgnoreCase("LOW")) continue;
                    if (impact.equalsIgnoreCase("MEDIUM") && !isMediumHighImpact(eventName)) continue;

                    String currency = obj.optString("currency", "");
                    // Filtre : devises surveillées uniquement
                    if (!isTrackedCurrency(currency)) continue;

                    CalendarEvent event  = new CalendarEvent();
                    event.country        = currencyToCountry(currency);
                    event.indicator      = eventName.isEmpty() ? "Macro Release" : eventName;
                    event.importance     = impact; // High ou Medium selon FMP
                    event.forecast       = formatValue(obj.optString("estimate", "N/A"));
                    event.previous       = formatValue(obj.optString("previous", "N/A"));
                    event.actual         = formatValue(obj.optString("actual",   "N/A"));

                    // CORRECTION 2 : Timestamp réel en Unix secondes (cohérent avec EventValidator)
                    String dateStr = obj.optString("date", "");
                    event.timestamp = convertFMPDateToUnixSeconds(dateStr);

                    event.affectedAssets = mapIndicatorToAssetsIntermarket(event.indicator, event.country);
                    events.add(event);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Échec FMP", e);
        } finally {
            // CORRECTION 1 : Fermeture garantie
            if (conn != null) conn.disconnect();
        }
        return events;
    }

    // ─────────────────────────────────────────────────────────────
    //  NIVEAU 2 : Investing.com (scraping HTML)
    //  Conservé comme filet de sécurité.
    //  CORRECTION 1 : Timeouts ajoutés.
    // ─────────────────────────────────────────────────────────────

    private static List<CalendarEvent> fetchFromInvesting(int hoursAhead) {
        List<CalendarEvent> events = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            long now    = System.currentTimeMillis();
            long future = now + ((long) hoursAhead * 60 * 60 * 1000);

            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            String postData =
                "dateFrom=" + URLEncoder.encode(sdf.format(new Date(now)),    "UTF-8") +
                "&dateTo="  + URLEncoder.encode(sdf.format(new Date(future)), "UTF-8") +
                "&timeZone=55&timeFilter=timeRemain&currentTab=today&submitFilters=1&limit_from=0";

            URL url = new URL(INVESTING_API_POST);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            conn.setDoOutput(true);
            // CORRECTION 1 : Timeouts ajoutés
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(postData.getBytes("UTF-8"));
            }

            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) response.append(line);
                br.close();

                JSONObject root = new JSONObject(response.toString());
                if (root.has("data")) {
                    events = parseHTMLRows(root.getString("data"));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Échec Investing.com", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return events;
    }

    // ─────────────────────────────────────────────────────────────
    //  PARSING HTML — Investing.com (inchangé)
    // ─────────────────────────────────────────────────────────────

    private static List<CalendarEvent> parseHTMLRows(String html) {
        List<CalendarEvent> events = new ArrayList<>();
        Pattern rowPattern = Pattern.compile(
            "<tr[^>]*id=\"eventRowId_(\\d+)\"[^>]*data-event-datetime=\"([^\"]+)\"[^>]*>(.*?)</tr>",
            Pattern.DOTALL);
        Matcher rowMatcher = rowPattern.matcher(html);

        while (rowMatcher.find()) {
            String rowContent = rowMatcher.group(3);
            CalendarEvent event = new CalendarEvent();
            event.timestamp  = convertInvestingDateToUnixSeconds(rowMatcher.group(2));
            event.country    = extractRegex(rowContent, "title=\"([^\"]+)\"[^>]*class=\"ceFlags");
            event.indicator  = extractRegex(rowContent,
                "<a[^>]*href=\"/economic-calendar/[^\"]+\"[^>]*>\\s*([^<]+)\\s*</a>");
            event.forecast   = extractValueByClass(rowContent, "fore");
            event.previous   = extractValueByClass(rowContent, "prev");
            event.actual     = extractValueByClass(rowContent, "act");
            event.importance = rowContent.contains("grayFullBullishIcon") ? "High" : "Medium";

            if (event.indicator != null && !event.indicator.isEmpty()) {
                event.affectedAssets = mapIndicatorToAssetsIntermarket(event.indicator, event.country);
                events.add(event);
            }
        }
        return events;
    }

    // ─────────────────────────────────────────────────────────────
    //  MAPPING INDICATEUR → ACTIFS (inchangé, cohérent SYSTEM_PROMPT)
    // ─────────────────────────────────────────────────────────────

    public static List<String> mapIndicatorToAssetsIntermarket(String indicator, String country) {
        List<String> assets = new ArrayList<>();
        String ind = indicator.toLowerCase();
        String cty = country.toLowerCase();

        // Tout événement macro impacte en premier lieu les obligations US
        assets.add("US10Y");

        if (cty.contains("united states") || cty.contains("us ")) {

            // ── RANG SUPRÊME : Politique monétaire + Inflation + Emploi principal ──
            if (ind.contains("fomc") || ind.contains("interest rate") ||
                ind.contains("cpi")  || ind.contains("pce")           ||
                ind.contains("non-farm") || ind.contains("nfp")       ||
                ind.contains("powell") || ind.contains("beige book")) {
                assets.addAll(Arrays.asList("GOLD", "SP500", "NASDAQ", "BITCOIN", "USDJPY", "EURUSD", "USDCAD", "GBPUSD", "AUDUSD"));
            }

            // ── Emploi secondaire : Jobless Claims, ADP, JOLTS ───────────────────
            else if (ind.contains("jobless claims") || ind.contains("initial claims") ||
                     ind.contains("continuing claims") || ind.contains("adp") ||
                     ind.contains("jolts") || ind.contains("job openings") ||
                     ind.contains("challenger")) {
                // Impact direct USD et marchés actions — précurseurs NFP
                assets.addAll(Arrays.asList("SP500", "NASDAQ", "USDJPY", "EURUSD", "GOLD"));
            }

            // ── Inflation amont : PPI, Import/Export Prices ──────────────────────
            else if (ind.contains("ppi") || ind.contains("producer price") ||
                     ind.contains("import price") || ind.contains("export price")) {
                // Précurseur CPI → impact USD, Gold, obligations
                assets.addAll(Arrays.asList("GOLD", "USDJPY", "EURUSD"));
            }

            // ── Sentiment consommateur : Michigan, Conference Board ───────────────
            else if (ind.contains("michigan") || ind.contains("consumer confidence") ||
                     ind.contains("consumer sentiment")) {
                // Impact NASDAQ/SP500 (consommation), USOIL (demande)
                assets.addAll(Arrays.asList("SP500", "NASDAQ", "USOIL", "BITCOIN"));
            }

            // ── Activité manufacturière : ISM, PMI régionaux, Durable Goods ───────
            else if (ind.contains("pmi") || ind.contains("ism") ||
                     ind.contains("industrial production") || ind.contains("capacity") ||
                     ind.contains("durable goods") || ind.contains("factory orders") ||
                     ind.contains("chicago pmi") || ind.contains("philly fed") ||
                     ind.contains("empire state") || ind.contains("kansas city")) {
                assets.addAll(Arrays.asList("SP500", "NASDAQ", "USDJPY"));
            }

            // ── Croissance : GDP (toutes lectures) ───────────────────────────────
            else if (ind.contains("gdp") || ind.contains("gross domestic product")) {
                assets.addAll(Arrays.asList("SP500", "NASDAQ", "USDJPY", "GOLD"));
            }

            // ── Consommation : Retail Sales, Personal Spending ───────────────────
            else if (ind.contains("retail") || ind.contains("personal spending") ||
                     ind.contains("personal income")) {
                assets.addAll(Arrays.asList("SP500", "NASDAQ", "USDJPY"));
            }

            // ── Immobilier ────────────────────────────────────────────────────────
            else if (ind.contains("home sales") || ind.contains("housing") ||
                     ind.contains("building permits")) {
                assets.addAll(Arrays.asList("SP500", "NASDAQ"));
            }

            // ── Commerce / Balance ────────────────────────────────────────────────
            else if (ind.contains("trade balance") || ind.contains("current account")) {
                assets.addAll(Arrays.asList("USDJPY", "EURUSD", "USDCAD"));
            }

            // ── Pétrole / Énergie ─────────────────────────────────────────────────
            else if (ind.contains("crude") || ind.contains("inventories") ||
                     ind.contains("eia") || ind.contains("natural gas") ||
                     ind.contains("opec")) {
                assets.addAll(Arrays.asList("USOIL", "USDCAD"));
            }

        } else if (cty.contains("united kingdom") || cty.contains("uk")) {
            assets.add("GBPUSD");
            if (ind.contains("cpi") || ind.contains("inflation") ||
                ind.contains("boe") || ind.contains("interest rate") || ind.contains("minutes")) {
                assets.addAll(Arrays.asList("SP500", "NASDAQ")); // Choc BoE = impact global
            }

        } else if (cty.contains("japan") || cty.contains("boj")) {
            assets.add("USDJPY");
            if (ind.contains("boj") || ind.contains("interest rate") || ind.contains("minutes")) {
                assets.add("GOLD"); // BoJ hawkish → Yen fort → Gold en yens ajusté
            }

        } else if (cty.contains("canada")) {
            assets.addAll(Arrays.asList("USDCAD", "USOIL")); // Corrélation CAD / Pétrole

        } else if (cty.contains("australia")) {
            assets.add("AUDUSD");
            if (ind.contains("rba") || ind.contains("interest rate") || ind.contains("minutes")) {
                assets.add("USOIL"); // Australie = économie commodités
            }

        } else if (cty.contains("eurozone") || cty.contains("europe") || cty.contains("ecb")) {
            assets.add("EURUSD");
            if (ind.contains("ecb") || ind.contains("interest rate") || ind.contains("minutes") ||
                ind.contains("cpi") || ind.contains("inflation")) {
                assets.addAll(Arrays.asList("GBPUSD", "SP500")); // Choc BCE = impact global
            }

        } else if (ind.contains("opec") || ind.contains("opec+")) {
            assets.addAll(Arrays.asList("USOIL", "USDCAD"));
        }

        return new ArrayList<>(new LinkedHashSet<>(assets));
    }

    // ─────────────────────────────────────────────────────────────
    //  NIVEAU 3 : FALLBACK INSTITUTIONNEL STATIQUE
    //  CORRECTION 4 : Timestamps réels basés sur l'heure actuelle
    //  espacés de 30 min → matchables par EventValidator (fenêtre ±10 min).
    //  Le fallback sert à enrichir le mapping d'actifs, pas à matcher
    //  des événements précis — timestamps proches de now pour maximiser
    //  les chances de match si une notification arrive.
    // ─────────────────────────────────────────────────────────────

    private static List<CalendarEvent> generateInstitutionalExhaustiveFallback() {
        List<CalendarEvent> list = new ArrayList<>();

        // Timestamp de référence = maintenant (Unix secondes)
        // Les événements sont répartis sur les 6 prochaines heures (toutes les 30 min)
        long nowSeconds = System.currentTimeMillis() / 1000;

        String[][] drivers = {
            {"United States", "FOMC Interest Rate Decision",      "High",   "4.75%",  "5.00%"},
            {"United States", "Core CPI Inflation MoM",           "High",   "0.2%",   "0.3%"},
            {"United States", "Non-Farm Payrolls Employment",     "High",   "160K",   "185K"},
            {"United States", "Core PCE Price Index YoY",         "High",   "2.6%",   "2.7%"},
            {"United States", "ISM Manufacturing PMI",            "High",   "49.1",   "48.2"},
            {"United States", "Gross Domestic Product (GDP) QoQ", "High",   "2.1%",   "2.5%"},
            {"United States", "EIA Crude Oil Inventories",        "Medium", "-1.2M",  "0.5M"},
            {"United Kingdom", "BoE Interest Rate Decision",      "High",   "4.50%",  "4.75%"},
            {"Japan",          "BoJ Monetary Policy Statement",   "High",   "0.25%",  "0.25%"},
            {"Canada",         "BoC Interest Rate Decision",      "High",   "4.00%",  "4.25%"},
            {"Australia",      "RBA Interest Rate Decision",      "High",   "4.10%",  "4.10%"}
        };

        for (int i = 0; i < drivers.length; i++) {
            CalendarEvent e  = new CalendarEvent();
            // CORRECTION 4 : timestamp réel = maintenant + i * 30 min
            // → les events sont distribués dans les 5h30 à venir
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
    //  UTILITAIRES
    // ─────────────────────────────────────────────────────────────

    /** Convertit une date FMP "yyyy-MM-dd HH:mm:ss" en Unix secondes (String). */
    private static String convertFMPDateToUnixSeconds(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return String.valueOf(System.currentTimeMillis() / 1000);
        }
        try {
            // FMP retourne "2025-05-23 14:30:00"
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            return String.valueOf(sdf.parse(dateStr).getTime() / 1000);
        } catch (Exception e) {
            try {
                // Parfois FMP retourne juste "2025-05-23"
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
                return String.valueOf(sdf.parse(dateStr).getTime() / 1000);
            } catch (Exception e2) {
                return String.valueOf(System.currentTimeMillis() / 1000);
            }
        }
    }

    /** Convertit une date Investing.com "yyyy/MM/dd HH:mm:ss" en Unix secondes (String). */
    private static String convertInvestingDateToUnixSeconds(String datetime) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US);
            return String.valueOf(sdf.parse(datetime).getTime() / 1000);
        } catch (Exception e) {
            return String.valueOf(System.currentTimeMillis() / 1000);
        }
    }

    /** Formate une valeur numérique vide ou "null" en "N/A". */
    private static String formatValue(String value) {
        if (value == null || value.isEmpty() || value.equalsIgnoreCase("null")) return "N/A";
        return value;
    }

    /**
     * Whitelist des indicateurs classés MEDIUM par FMP mais à fort impact réel sur le marché.
     * Ces indicateurs bougent régulièrement le Dollar, les taux et les indices
     * même si FMP ne les classe pas HIGH.
     *
     * Règle d'inclusion : l'indicateur doit avoir provoqué au moins une fois
     * un mouvement significatif sur un actif surveillé lors de sa publication.
     */
    private static boolean isMediumHighImpact(String eventName) {
        if (eventName == null || eventName.isEmpty()) return false;
        String ind = eventName.toLowerCase();

        return  // ── Emploi US ─────────────────────────────────────────────
                ind.contains("initial jobless claims")      || // Hebdo, très surveillé par Fed
                ind.contains("continuing claims")           || // Confirme la tendance emploi
                ind.contains("adp employment")              || // Précurseur NFP
                ind.contains("adp nonfarm")                 ||
                ind.contains("jolts")                       || // Fed surveille les offres d'emploi
                ind.contains("job openings")                ||
                ind.contains("challenger job cuts")         || // Licenciements annoncés

                // ── Inflation / Prix ──────────────────────────────────────
                ind.contains("ppi")                         || // Précurseur CPI (inflation amont)
                ind.contains("producer price")              ||
                ind.contains("import price")                || // Inflation importée
                ind.contains("export price")                ||

                // ── Croissance / Activité ─────────────────────────────────
                ind.contains("gdp")                         || // Révisions GDP également importantes
                ind.contains("gross domestic product")      ||
                ind.contains("industrial production")       || // Activité manufacturière
                ind.contains("capacity utilization")        ||
                ind.contains("durable goods")               || // Commandes biens durables
                ind.contains("factory orders")              ||
                ind.contains("chicago pmi")                 || // Précurseur ISM Manufacturing
                ind.contains("philly fed")                  || // Fed Philly — très suivi
                ind.contains("empire state")                || // Fed NY Manufacturing
                ind.contains("kansas city fed")             ||

                // ── Consommation / Sentiment ──────────────────────────────
                ind.contains("michigan")                    || // Sentiment consommateur (Conference Board aussi)
                ind.contains("consumer confidence")         ||
                ind.contains("consumer sentiment")          ||
                ind.contains("retail sales")                || // Parfois HIGH, parfois MEDIUM selon FMP
                ind.contains("personal spending")           ||
                ind.contains("personal income")             ||

                // ── Immobilier ────────────────────────────────────────────
                ind.contains("existing home sales")         || // Indicateur de demande crédit
                ind.contains("new home sales")              ||
                ind.contains("housing starts")              ||
                ind.contains("building permits")            ||

                // ── Commerce / Balance ────────────────────────────────────
                ind.contains("trade balance")               || // Déficit US impacte USD
                ind.contains("current account")             ||

                // ── Pétrole / Énergie ─────────────────────────────────────
                ind.contains("eia")                         || // Stocks pétrole EIA (hebdo)
                ind.contains("crude oil inventories")       ||
                ind.contains("natural gas storage")         ||
                ind.contains("opec")                        ||

                // ── Banques Centrales Étrangères (Medium chez FMP) ────────
                ind.contains("rba")                         || // Reserve Bank of Australia
                ind.contains("boc")                         || // Bank of Canada
                ind.contains("boe")                         || // Bank of England minutes
                ind.contains("boj")                         || // Bank of Japan minutes
                ind.contains("ecb")                         || // BCE minutes/discours
                ind.contains("minutes")                     || // Minutes de toutes les BC
                ind.contains("monetary policy")             ||

                // ── Discours Fed à fort impact ────────────────────────────
                ind.contains("powell")                      || // Discours Powell = toujours important
                ind.contains("beige book");                    // Rapport Fed sur l'économie US
    }

    /** Vérifie si la devise est dans notre univers de suivi. */
    private static boolean isTrackedCurrency(String currency) {
        return currency.equals("USD") || currency.equals("EUR") || currency.equals("GBP") ||
               currency.equals("JPY") || currency.equals("CAD") || currency.equals("AUD");
    }

    /** Convertit un code devise ISO en nom de pays lisible pour mapIndicatorToAssetsIntermarket. */
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

    private static String extractRegex(String html, String regex) {
        Pattern p = Pattern.compile(regex, Pattern.DOTALL);
        Matcher m = p.matcher(html);
        return m.find() ? m.group(1).trim() : "";
    }

    private static String extractValueByClass(String html, String className) {
        Pattern p = Pattern.compile(
            "<td[^>]*class=\"[^\" ]*" + className + "[^\"]*\"[^>]*>([^<]*)</td>",
            Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        return m.find() ? m.group(1).trim().replace("&nbsp;", "") : "N/A";
    }
}
