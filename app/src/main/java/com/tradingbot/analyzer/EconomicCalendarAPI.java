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

    private static final String FMP_URL          = "https://financialmodelingprep.com/api/v3/economic_calendar";
    private static final String FF_URL_THIS_WEEK = "https://nfs.faireconomy.media/ff_calendar_thisweek.json";
    private static final String FF_URL_NEXT_WEEK = "https://nfs.faireconomy.media/ff_calendar_nextweek.json";
    //private static final String FF_URL_LAST_WEEK = "https://nfs.faireconomy.media/ff_calendar_lastweek.json";

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

    public static void init(Context context) {
        if (context != null) {
            globalAppContext = context.getApplicationContext();
        }
    }
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 1000;

    private static List<CalendarEvent> fetchWithRetry(FetchFunction fetcher, int hoursAhead) {
        long backoff = INITIAL_BACKOFF_MS;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                List<CalendarEvent> events = fetcher.fetch(hoursAhead);
                if (events != null && !events.isEmpty()) {
                    return events;
                }
                // ✅ Log si résultat vide sans exception
                if (attempt < MAX_RETRIES) {
                    logToMain("⚠️ [CALENDRIER] Tentative " + attempt + "/" + MAX_RETRIES + " — réponse vide, retry dans " + (backoff/1000) + "s");
                }
            } catch (Exception e) {
                Log.w(TAG, "Tentative " + attempt + " échouée : " + e.getMessage());
                logToMain("❌ [CALENDRIER] Tentative " + attempt + "/" + MAX_RETRIES + " échouée : " + e.getMessage());
            }
            if (attempt < MAX_RETRIES) {
                try {
                    Thread.sleep(backoff);
                    backoff *= 2;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return new ArrayList<>();
    }

    interface FetchFunction {
        List<CalendarEvent> fetch(int hoursAhead) throws Exception;
    }
    /**
     * Surcharge essentielle pour préserver la compatibilité ascendante avec EventValidator.preloadCalendar()
     */
    public static List<CalendarEvent> fetchUpcomingEvents(int hoursAhead) {
        return fetchUpcomingEvents(globalAppContext, hoursAhead);
    }
    // =========================================================================
    // 🌍 MOTEUR DE PARSING FOREXFACTORY (RÉSOLUTIONS DES ERREURS DE COMPILATION)
    // =========================================================================

    private static List<CalendarEvent> fetchFromForexFactory(int hoursAhead) throws Exception {
        // Charge par défaut la semaine en cours
        return fetchFromForexFactoryUrl(FF_URL_THIS_WEEK, hoursAhead);
    }

    private static List<CalendarEvent> fetchFromForexFactoryUrl(String urlString, int hoursAhead) throws Exception {
        List<CalendarEvent> events = new ArrayList<>();
        HttpURLConnection conn = null;
        BufferedReader reader = null;

        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                throw new IOException("HTTP error code: " + responseCode);
            }

            InputStream in = new BufferedInputStream(conn.getInputStream());
            reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }

            JSONArray jsonArray = new JSONArray(sb.toString());
            long nowMs = System.currentTimeMillis();
            long maxFutureMs = nowMs + ((long) hoursAhead * 3600 * 1000);

            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);

                String title    = obj.optString("title", "");
                String country  = obj.optString("country", "");
                String dateStr  = obj.optString("date", ""); // ISO8601 string
                String impact   = obj.optString("impact", "Medium");
                String forecast = obj.optString("forecast", "N/A");
                String previous = obj.optString("previous", "N/A");
                String actual   = obj.optString("actual", "N/A");

                // Filtrage strict sur vos devises clés pour ne pas surcharger la mémoire
                if (!isTrackedCurrency(country)) continue;

                // Conversion sécurisée de la date ForexFactory
                long eventMs = convertForexFactoryDateToMs(dateStr);

                // Ne capture que les événements pertinents selon la fenêtre horaire (hoursAhead)
                // Si hoursAhead est négatif ou très grand (Backfill/History), on adapte la logique
                if (hoursAhead == 168 || eventMs <= maxFutureMs) { 
                    CalendarEvent event = new CalendarEvent();
                    event.timestamp  = String.valueOf(eventMs / 1000);
                    event.country    = currencyToCountry(country);
                    event.indicator  = title;
                    event.importance = impact.equalsIgnoreCase("High") ? "HIGH" : (impact.equalsIgnoreCase("Low") ? "LOW" : "MEDIUM");
                    event.forecast   = formatValue(forecast);
                    event.previous   = formatValue(previous);
                    event.actual     = formatValue(actual);
                    
                    // Injection automatique de la matrice d'intermarché des 11 actifs
                    event.affectedAssets = mapIndicatorToAssetsIntermarket(event.indicator, event.country);

                    // Filtrage optionnel : Conserver uniquement les impacts Medium/High pour vos actifs
                    if (isMediumHighImpact(event.indicator) || event.importance.equals("HIGH")) {
                        events.add(event);
                    }
                }
            }
        } finally {
            if (reader != null) { try { reader.close(); } catch (IOException ignored) {} }
            if (conn != null) conn.disconnect();
        }
        return events;
     }

    /**
     * Point d'entrée principal (Pipeline de données à 3 niveaux)
     */
    public static List<CalendarEvent> fetchUpcomingEvents(Context context, int hoursAhead) {
        Context targetContext = (context != null) ? context.getApplicationContext() : globalAppContext;
    
        // ── Tentative FMP ──
        
    
        // ── Tentative ForexFactory ──
        logToMain("🔄 [CALENDRIER] Chargement ForexFactory en cours...");
        List<CalendarEvent> events = fetchWithRetry(h -> fetchFromForexFactory(h), hoursAhead);
        if (!events.isEmpty()) {
            logToMain("✅ [CALENDRIER] ForexFactory : " + events.size() + " événements chargés");
            return events;
        }
        logToMain("⚠️ [CALENDRIER] ForexFactory indisponible — retry dans 6h. Aucun fallback fictif injecté.");
        return new ArrayList<>();
    }

    // ✅ Récupération des événements historiques (jusqu'à 30 jours en arrière)
// Utilisé pour le backfill automatique des données manquantes
public static List<CalendarEvent> fetchHistoricalEvents(int daysBack) {
    return fetchHistoricalEvents(globalAppContext, daysBack);
}

   public static List<CalendarEvent> fetchHistoricalEvents(Context context, int daysBack) {
    List<CalendarEvent> allEvents = new ArrayList<>();
    logToMain("🔄 [BACKFILL] Récupération via ForexFactory (thisweek + lastweek)...");

    try {
        // ── 1. Semaine courante — actuals déjà publiés cette semaine ──
        // ── Semaine courante — uniquement événements passés avec actual publié ──
        // ✅ Délai 3s pour éviter le rate limit ForexFactory
        // Le calendrier principal vient de l'appeler au démarrage
       Thread.sleep(3000);
       List<CalendarEvent> thisWeek = fetchFromForexFactoryUrl(FF_URL_THIS_WEEK, 168);
        int countThis = 0;
        long nowSec = System.currentTimeMillis() / 1000;
        for (CalendarEvent e : thisWeek) {
            boolean hasActual = e.actual != null
                    && !e.actual.equals("N/A")
                    && !e.actual.isEmpty();
            boolean isPast = false;
            try {
                long eventTs = Long.parseLong(e.timestamp);
                isPast = eventTs < nowSec;
            } catch (Exception ignored) {
                isPast = hasActual;
            }
            if (hasActual && isPast) {
                allEvents.add(e);
                countThis++;
            }
        }
        logToMain("✅ [BACKFILL] thisweek passés avec actual : " + countThis + " événements");
    } catch (Exception e) {
        logToMain("❌ [BACKFILL] Erreur ForexFactory : " + e.getMessage());
        Log.e(TAG, "Erreur fetchHistoricalEvents", e);
    }

    logToMain("📊 [BACKFILL] Total récupéré : " + allEvents.size()
            + " événements (thisweek + lastweek)");
    return allEvents;
} 

    private static List<CalendarEvent> fetchFromFMP(Context context, int hoursAhead) {
          logToMain("❌ [FMP] Appel réseau bloqué (méthode dépréciée).");
        return new ArrayList<>();

    }
    
    public static List<String> mapIndicatorToAssetsIntermarket(String indicator, String country) {
        List<String> assets = new ArrayList<>();
        String ind = indicator.toLowerCase(Locale.US);
        String cty = country.toLowerCase(Locale.US);
    
        assets.add("US10Y"); // Pivot obligatoire d'analyse macro intermarché
    
        if (cty.contains("united states") || cty.equals("us") || cty.startsWith("us ")) {
    
            if (ind.contains("fomc") || ind.contains("federal reserve") ||
                ind.contains("interest rate") || ind.contains("rate decision") ||
                ind.contains("powell") || ind.contains("warsh") ||
                ind.contains("beige book") || ind.contains("minutes")) {
                assets.addAll(Arrays.asList(
                    "GOLD", "SP500", "NASDAQ", "BITCOIN",
                    "USDJPY", "EURUSD", "USDCAD", "GBPUSD", "AUDUSD", "USOIL"
                ));
            } else if (ind.contains("cpi") || ind.contains("inflation") ||
                       ind.contains("consumer price")) {
                assets.addAll(Arrays.asList(
                    "GOLD", "SP500", "NASDAQ", "BITCOIN",
                    "USDJPY", "EURUSD", "GBPUSD", "USDCAD"
                ));
            } else if (ind.contains("pce") || ind.contains("personal consumption expenditure")) {
                assets.addAll(Arrays.asList(
                    "GOLD", "SP500", "NASDAQ", "BITCOIN",
                    "USDJPY", "EURUSD", "GBPUSD"
                ));
            } else if (ind.contains("ppi") || ind.contains("producer price")) {
                assets.addAll(Arrays.asList(
                    "GOLD", "USDJPY", "EURUSD", "SP500"
                ));
            } else if (ind.contains("non-farm") || ind.contains("nfp") ||
                       ind.contains("payroll")) {
                assets.addAll(Arrays.asList(
                    "GOLD", "SP500", "NASDAQ", "BITCOIN",
                    "USDJPY", "EURUSD", "USDCAD", "GBPUSD", "AUDUSD", "USOIL"
                ));
            } else if (ind.contains("adp")) {
                assets.addAll(Arrays.asList(
                    "SP500", "NASDAQ", "USDJPY", "EURUSD", "GOLD"
                ));
            } else if (ind.contains("jolts") || ind.contains("job openings")) {
                assets.addAll(Arrays.asList(
                    "SP500", "NASDAQ", "USDJPY", "EURUSD", "GOLD"
                ));
            } else if (ind.contains("jobless") || ind.contains("initial claims") ||
                       ind.contains("continuing claims")) {
                assets.addAll(Arrays.asList(
                    "SP500", "NASDAQ", "USDJPY", "EURUSD", "GOLD"
                ));
            } else if (ind.contains("gdp") || ind.contains("gross domestic")) {
                assets.addAll(Arrays.asList(
                    "SP500", "NASDAQ", "USDJPY", "GOLD", "USOIL"
                ));
            } else if (ind.contains("ism") || ind.contains("pmi") ||
                       ind.contains("purchasing managers")) {
                assets.addAll(Arrays.asList(
                    "SP500", "NASDAQ", "USDJPY", "USOIL"
                ));
            } else if (ind.contains("chicago pmi") || ind.contains("empire state") ||
                       ind.contains("philly fed") || ind.contains("philadelphia")) {
                assets.addAll(Arrays.asList(
                    "SP500", "NASDAQ", "USDJPY"
                ));
            } else if (ind.contains("retail") || ind.contains("consumer spending")) {
                assets.addAll(Arrays.asList(
                    "SP500", "NASDAQ", "USDJPY", "BITCOIN"
                ));
            } else if (ind.contains("personal income") || ind.contains("personal spending")) {
                assets.addAll(Arrays.asList(
                    "SP500", "NASDAQ", "USDJPY", "GOLD"
                ));
            } else if (ind.contains("michigan") || ind.contains("consumer sentiment") ||
                       ind.contains("consumer confidence")) {
                assets.addAll(Arrays.asList(
                    "SP500", "NASDAQ", "USOIL", "BITCOIN"
                ));
            } else if (ind.contains("durable goods") || ind.contains("capital goods")) {
                assets.addAll(Arrays.asList(
                    "SP500", "NASDAQ", "USDJPY"
                ));
            } else if (ind.contains("industrial production") || ind.contains("capacity utilization")) {
                assets.addAll(Arrays.asList(
                    "SP500", "NASDAQ", "USOIL", "USDJPY"
                ));
            } else if (ind.contains("housing starts") || ind.contains("building permits")) {
                assets.addAll(Arrays.asList(
                    "SP500", "NASDAQ", "USDJPY"
                ));
            } else if (ind.contains("home sales") || ind.contains("existing home") ||
                       ind.contains("new home") || ind.contains("pending home")) {
                assets.addAll(Arrays.asList(
                    "SP500", "NASDAQ", "USDJPY"
                ));
            } else if (ind.contains("trade balance") || ind.contains("current account")) {
                assets.addAll(Arrays.asList(
                    "USDJPY", "EURUSD", "GBPUSD", "GOLD"
                ));
            } else if (ind.contains("crude oil") || ind.contains("eia") ||
                       ind.contains("oil inventories") || ind.contains("distillate") ||
                       ind.contains("gasoline") || ind.contains("petroleum")) {
                assets.addAll(Arrays.asList(
                    "USOIL", "USDCAD", "GOLD", "SP500"
                ));
            } else if (ind.contains("opec")) {
                assets.addAll(Arrays.asList(
                    "USOIL", "USDCAD", "GOLD", "SP500", "NASDAQ"
                ));
            }
    
        } else if (cty.contains("united kingdom") || cty.contains("uk")) {
                assets.add("GBPUSD");
                if (ind.contains("cpi") || ind.contains("inflation")) {
                    assets.addAll(Arrays.asList("EURUSD", "GOLD", "SP500"));
                } else if (ind.contains("boe") || ind.contains("interest rate") ||
                           ind.contains("monetary policy")) {
                    assets.addAll(Arrays.asList("EURUSD", "GOLD", "SP500", "NASDAQ", "USDJPY"));
                } else if (ind.contains("gdp") || ind.contains("growth")) {
                    assets.addAll(Arrays.asList("EURUSD", "GOLD"));
                } else if (ind.contains("average earnings") || ind.contains("wage")) {
                    assets.addAll(Arrays.asList("EURUSD", "GOLD"));
                } else if (ind.contains("claimant count") || ind.contains("unemployment")) {
                    assets.addAll(Arrays.asList("EURUSD"));
                } else if (ind.contains("pmi") || ind.contains("retail")) {
                    assets.addAll(Arrays.asList("EURUSD", "SP500"));
                }
    
        } else if (cty.contains("japan")) {
                    assets.add("USDJPY");
                    if (ind.contains("boj") || ind.contains("interest rate") ||
                        ind.contains("yield curve") || ind.contains("monetary policy")) {
                        assets.addAll(Arrays.asList("GOLD", "SP500", "NASDAQ", "EURUSD", "AUDUSD"));
                    } else if (ind.contains("cpi") || ind.contains("inflation")) {
                        assets.addAll(Arrays.asList("GOLD"));
                    } else if (ind.contains("tankan")) {
                        assets.addAll(Arrays.asList("GOLD", "AUDUSD"));
                    } else if (ind.contains("gdp")) {
                        assets.addAll(Arrays.asList("GOLD", "AUDUSD"));
                    }
    
        } else if (cty.contains("canada")) {
                assets.addAll(Arrays.asList("USDCAD", "USOIL"));
                if (ind.contains("boc") || ind.contains("interest rate") ||
                    ind.contains("rate decision")) {
                    assets.addAll(Arrays.asList("GOLD", "SP500", "EURUSD"));
                } else if (ind.contains("cpi") || ind.contains("inflation")) {
                    assets.addAll(Arrays.asList("GOLD", "EURUSD"));
                } else if (ind.contains("gdp") || ind.contains("employment")) {
                    assets.addAll(Arrays.asList("GOLD"));
                }
        
        } else if (cty.contains("australia")) {
                assets.add("AUDUSD");
                if (ind.contains("rba") || ind.contains("interest rate") ||
                    ind.contains("rate decision")) {
                    assets.addAll(Arrays.asList("GOLD", "SP500", "NASDAQ", "USDJPY"));
                } else if (ind.contains("cpi") || ind.contains("inflation")) {
                    assets.addAll(Arrays.asList("GOLD", "USDJPY"));
                } else if (ind.contains("gdp") || ind.contains("employment")) {
                    assets.addAll(Arrays.asList("GOLD", "USDJPY"));
                }
        
        } else if (cty.contains("eurozone") || cty.contains("euro area") ||
                       ind.contains("ecb") || ind.contains("lagarde")) {
                assets.add("EURUSD");
                if (ind.contains("ecb") || ind.contains("interest rate") ||
                    ind.contains("rate decision") || ind.contains("lagarde")) {
                    assets.addAll(Arrays.asList("GBPUSD", "GOLD", "SP500", "NASDAQ", "USDJPY"));
                } else if (ind.contains("cpi") || ind.contains("inflation") ||
                           ind.contains("hicp")) {
                    assets.addAll(Arrays.asList("GBPUSD", "GOLD", "SP500"));
                } else if (ind.contains("gdp") || ind.contains("growth")) {
                    assets.addAll(Arrays.asList("GBPUSD", "GOLD", "USOIL"));
                } else if (ind.contains("pmi") || ind.contains("ifo") || ind.contains("zew")) {
                    assets.addAll(Arrays.asList("GBPUSD", "SP500", "USOIL"));
                } else if (ind.contains("retail") || ind.contains("consumer")) {
                    assets.addAll(Arrays.asList("GBPUSD", "SP500"));
                } else if (ind.contains("unemployment") || ind.contains("jobless")) {
                    assets.addAll(Arrays.asList("GBPUSD"));
                }
        
        } else if (cty.contains("china") || cty.contains("chinese")) {
                assets.addAll(Arrays.asList("AUDUSD", "USDJPY", "USOIL"));
                if (ind.contains("caixin") || ind.contains("pmi")) {
                    assets.addAll(Arrays.asList("SP500", "NASDAQ", "GOLD"));
                } else if (ind.contains("gdp") || ind.contains("growth")) {
                    assets.addAll(Arrays.asList("SP500", "NASDAQ", "GOLD", "USOIL"));
                } else if (ind.contains("trade balance") || ind.contains("exports")) {
                    assets.addAll(Arrays.asList("GOLD", "SP500"));
                }
            }
    
            return new ArrayList<>(new LinkedHashSet<>(assets));
    }
    
    private static List<CalendarEvent> generateInstitutionalExhaustiveFallback() {
        List<CalendarEvent> list = new ArrayList<>();
        long nowSeconds = System.currentTimeMillis() / 1000;

        String[][] drivers = {
            {"United States", "FOMC INTEREST RATE DECISION",      "HIGH",   "4.75%",  "5.00%"},
            {"United States", "CORE CPI INFLATION MOM",           "HIGH",   "0.2%",   "0.3%"},
            {"United States", "NON-FARM PAYROLLS EMPLOYMENT",     "HIGH",   "160K",   "185K"},
            {"United States", "CORE PCE PRICE INDEX YOY",         "HIGH",   "2.6%",   "2.7%"},
            {"United States", "ISM MANUFACTURING PMI",            "HIGH",   "49.1",   "48.2"},
            {"United States", "GROSS DOMESTIC PRODUCT (GDP) QOQ", "HIGH",   "2.1%",   "2.5%"}
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
        return ind.contains("initial jobless claims") ||
               ind.contains("jobless claims") ||
               ind.contains("continuing claims") ||
               ind.contains("unemployment claims") ||
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
               ind.contains("building permits")            ||  // ajouté
               ind.contains("housing starts")              ||  // ajouté
               ind.contains("eia")                         ||
               ind.contains("crude oil inventories")       ||
               ind.contains("opec")                        ||
               ind.contains("minutes")                     ||
               ind.contains("powell")                      ||
               ind.contains("warsh")                       ||
               ind.contains("beige book")                  ||
               ind.contains("trade balance")               ||  // ajouté
               ind.contains("current account")             ||  // ajouté
               ind.contains("personal spending")           ||  // ajouté
               ind.contains("personal income");                // ajouté
    }
    private static String convertFMPDateToUnixSeconds(String dateStr) {
    if (dateStr == null || dateStr.isEmpty())
        return String.valueOf(System.currentTimeMillis() / 1000);
    try {
        // ✅ FMP retourne les heures en heure de New York (EST/EDT)
        // America/New_York gère automatiquement le DST (EDT UTC-4 été, EST UTC-5 hiver)
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("America/New_York"));
        return String.valueOf(sdf.parse(dateStr).getTime() / 1000);
    } catch (Exception e) {
        try {
            // ✅ Date sans heure → assigner 00:00 New York
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("America/New_York"));
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
        // ✅ Format avec timezone explicite (ex: 2026-06-05T08:30:00-04:00)
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
        return sdf.parse(dateStr).getTime();
    } catch (Exception e) {
        try {
            // ✅ Format sans timezone → New York par défaut (DST automatique)
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("America/New_York"));
            return sdf.parse(dateStr).getTime();
        } catch (Exception e2) {
            try {
                // ✅ Date seule → New York minuit
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("America/New_York"));
                return sdf.parse(dateStr).getTime();
            } catch (Exception e3) {
                return System.currentTimeMillis();
            }
        }
    }
        }

    private static String formatValue(String value) {
        if (value == null || value.isEmpty() || value.equalsIgnoreCase("null")) return "N/A";
        String trimmed = value.trim();
        if (trimmed.startsWith(".")) {
            trimmed = "0" + trimmed; // Standardisation pour alignement numérique (.3% -> 0.3%)
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
    // ── Log vers MainActivity + Logcat ──
    private static void logToMain(String message) {
        Log.d(TAG, message);
        if (MainActivity.instance != null) {
            try {
                MainActivity.instance.addLog(message);
            } catch (Exception e) {
                Log.w(TAG, "Impossible d'ajouter le log à l'UI : " + e.getMessage());
            }
        }
    }
}
