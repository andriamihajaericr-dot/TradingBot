package com.tradingbot.analyzer;

import android.content.Context;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class EconomicCalendarAPI {
    private static final String TAG            = "EconomicCalendarAPI";

    // ✅ Clean & Strict : Uniquement Forex Factory stable
    private static final String FF_URL_LAST_WEEK = "https://nfs.faireconomy.media/ff_calendar_lastweek.json";
    private static final String FF_URL_THIS_WEEK = "https://nfs.faireconomy.media/ff_calendar_thisweek.json";
    private static final String FF_URL_NEXT_WEEK = "https://nfs.faireconomy.media/ff_calendar_nextweek.json";
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
    private static final long INITIAL_BACKOFF_MS = 3000; // Augmenté à 3s pour éviter d'enchaîner trop vite

    private static List<CalendarEvent> fetchWithRetry(FetchFunction fetcher, int hoursAhead) {
        long backoff = INITIAL_BACKOFF_MS;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                List<CalendarEvent> events = fetcher.fetch(hoursAhead);
                if (events != null && !events.isEmpty()) {
                    return events;
                }
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
                    backoff *= 2; // Backoff exponentiel (3s, 6s, 12s)
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

    public static List<CalendarEvent> fetchUpcomingEvents(int hoursAhead) {
        return fetchUpcomingEvents(globalAppContext, hoursAhead);
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
            
            // ✅ CRITIQUE : User-Agent complet de navigateur pour contourner les protections anti-bot 429
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            if (responseCode == 429) {
                logToMain("⚠️ [CALENDRIER] Limite de requêtes atteinte (HTTP 429) sur " + urlString + ". Pause forcée...");
                throw new IOException("HTTP error code: 429 (Too Many Requests)");
            }
            if (responseCode == 404) {
                logToMain("❌ [CALENDRIER] Ressource introuvable (HTTP 404) pour : " + urlString);
                return events; // Retourne une liste vide proprement au lieu de crash
            }
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

            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);

                String title    = obj.optString("title", "");
                String country  = obj.optString("country", "");
                String dateStr  = obj.optString("date", ""); 
                String impact   = obj.optString("impact", "Medium");
                String forecast = obj.optString("forecast", "N/A");
                String previous = obj.optString("previous", "N/A");
                String actual   = obj.optString("actual", "N/A");

                if (!isTrackedCurrency(country)) continue;

                // ✅ CORRECTION ICI : Suppression du paramètre 'timeStr' inexistant
                long eventMs = convertForexFactoryDateToMs(dateStr);
                long maxFutureMs = nowMs + ((long) hoursAhead * 60 * 60 * 1000);
                
                // Tolérance de 45 min dans le passé pour capturer les publications à la minute près
                long minPastMs = nowMs - (45 * 60 * 1000L); 

                if (hoursAhead == 168 || (eventMs <= maxFutureMs && eventMs >= minPastMs)) {
                    CalendarEvent event = new CalendarEvent();
                    event.timestamp  = String.valueOf(eventMs / 1000);
                    event.country    = currencyToCountry(country);
                    event.indicator  = title;
                    event.importance = impact.equalsIgnoreCase("High") ? "HIGH" : (impact.equalsIgnoreCase("Low") ? "LOW" : "MEDIUM");
                    event.forecast   = formatValue(forecast);
                    event.previous   = formatValue(previous);
                    event.actual     = formatValue(actual);
                    
                    event.affectedAssets = mapIndicatorToAssetsIntermarket(event.indicator, event.country);

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

    
    public static List<CalendarEvent> fetchUpcomingEvents(Context context, int hoursAhead) {
        logToMain("🔄 [CALENDRIER] Chargement ForexFactory (This Week)...");
        List<CalendarEvent> events = fetchWithRetry(h -> fetchFromForexFactoryUrl(FF_URL_THIS_WEEK, h), hoursAhead);
        if (events == null) events = new ArrayList<>();

        try {
            logToMain("🔄 [CALENDRIER] Chargement ForexFactory (Next Week) pour complétion...");
            List<CalendarEvent> nextWeekEvents = fetchWithRetry(h -> fetchFromForexFactoryUrl(FF_URL_NEXT_WEEK, h), hoursAhead);
            if (nextWeekEvents != null && !nextWeekEvents.isEmpty()) {
                events.addAll(nextWeekEvents);
            }
        } catch (Exception e) {
            Log.w(TAG, "Semaine suivante indisponible: " + e.getMessage());
        }

        if (!events.isEmpty()) {
            Set<CalendarEvent> uniqueEvents = new TreeSet<>((e1, e2) -> {
                String k1 = e1.timestamp + "_" + e1.indicator;
                String k2 = e2.timestamp + "_" + e2.indicator;
                return k1.compareTo(k2);
            });
            uniqueEvents.addAll(events);
            List<CalendarEvent> cleanedList = new ArrayList<>(uniqueEvents);

            logToMain("✅ [CALENDRIER] ForexFactory : " + cleanedList.size() + " événements chargés.");
            return cleanedList;
        }

    public static List<CalendarEvent> fetchHistoricalEvents(int daysBack) {
        return fetchHistoricalEvents(globalAppContext, daysBack);
    }

    public static List<CalendarEvent> fetchHistoricalEvents(Context context, int daysBack) {
        List<CalendarEvent> allEvents = new ArrayList<>();
        long nowSec = System.currentTimeMillis() / 1000;

        try {
            logToMain("🔄 [BACKFILL] Étape 1 : Récupération de LAST_WEEK...");
            List<CalendarEvent> lastWeek = fetchFromForexFactoryUrl(FF_URL_LAST_WEEK, 168);
            int countLast = 0;
            if (lastWeek != null) {
                for (CalendarEvent e : lastWeek) {
                    if (isValidPastEvent(e, nowSec)) {
                        allEvents.add(e);
                        countLast++;
                    }
                }
            }
            logToMain("✅ [BACKFILL] LastWeek traités : " + countLast + " événements passés trouvés.");
        } catch (Exception e) {
            logToMain("❌ [BACKFILL] Erreur sur LAST_WEEK : " + e.getMessage());
        }

        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}

        try {
            logToMain("🔄 [BACKFILL] Étape 2 : Récupération de THIS_WEEK...");
            List<CalendarEvent> thisWeek = fetchFromForexFactoryUrl(FF_URL_THIS_WEEK, 168);
            int countThis = 0;
            if (thisWeek != null) {
                for (CalendarEvent e : thisWeek) {
                    if (isValidPastEvent(e, nowSec)) {
                        allEvents.add(e);
                        countThis++;
                    }
                }
            }
            logToMain("✅ [BACKFILL] ThisWeek traités : " + countThis + " événements passés trouvés.");
        } catch (Exception e) {
            logToMain("❌ [BACKFILL] Erreur sur THIS_WEEK : " + e.getMessage());
        }

        logToMain("📊 [BACKFILL] Total validé pour insertion SQLite : " + allEvents.size() + " événements.");
        return allEvents;
    }

    private static boolean isValidPastEvent(CalendarEvent e, long nowSec) {
        boolean hasActual = e.actual != null && !e.actual.equalsIgnoreCase("N/A") 
                            && !e.actual.trim().isEmpty() && !e.actual.equalsIgnoreCase("null");
        boolean isPast = false;
        try {
            long eventTs = Long.parseLong(e.timestamp);
            isPast = eventTs < nowSec;
        } catch (Exception ignored) {
            isPast = hasActual;
        }
        return hasActual && isPast;
    }
    
    public static List<String> mapIndicatorToAssetsIntermarket(String indicator, String country) {
        List<String> assets = new ArrayList<>();
        String ind = indicator.toLowerCase(Locale.US);
        String cty = country.toLowerCase(Locale.US);
    
        assets.add("US10Y"); 
    
        if (cty.contains("united states") || cty.equals("us") || cty.startsWith("us ")) {
            if (ind.contains("fomc") || ind.contains("federal reserve") ||
                ind.contains("interest rate") || ind.contains("rate decision") ||
                ind.contains("powell") || ind.contains("warsh") ||
                ind.contains("beige book") || ind.contains("minutes")) {
                assets.addAll(Arrays.asList("GOLD", "SP500", "NASDAQ", "BITCOIN", "USDJPY", "EURUSD", "USDCAD", "GBPUSD", "AUDUSD", "USOIL"));
            } else if (ind.contains("cpi") || ind.contains("inflation") || ind.contains("consumer price")) {
                assets.addAll(Arrays.asList("GOLD", "SP500", "NASDAQ", "BITCOIN", "USDJPY", "EURUSD", "GBPUSD", "USDCAD"));
            } else if (ind.contains("pce") || ind.contains("personal consumption expenditure")) {
                assets.addAll(Arrays.asList("GOLD", "SP500", "NASDAQ", "BITCOIN", "USDJPY", "EURUSD", "GBPUSD"));
            } else if (ind.contains("ppi") || ind.contains("producer price")) {
                assets.addAll(Arrays.asList("GOLD", "USDJPY", "EURUSD", "SP500"));
            } else if (ind.contains("non-farm") || ind.contains("nfp") || ind.contains("payroll")) {
                assets.addAll(Arrays.asList("GOLD", "SP500", "NASDAQ", "BITCOIN", "USDJPY", "EURUSD", "USDCAD", "GBPUSD", "AUDUSD", "USOIL"));
            } else if (ind.contains("gdp") || ind.contains("gross domestic")) {
                assets.addAll(Arrays.asList("SP500", "NASDAQ", "USDJPY", "GOLD", "USOIL"));
            } else if (ind.contains("crude oil") || ind.contains("eia") || ind.contains("oil inventories")) {
                assets.addAll(Arrays.asList("USOIL", "USDCAD", "GOLD", "SP500"));
            }
        } else if (cty.contains("united kingdom") || cty.contains("uk")) {
            assets.add("GBPUSD");
            if (ind.contains("cpi") || ind.contains("inflation") || ind.contains("boe")) {
                assets.addAll(Arrays.asList("EURUSD", "GOLD", "SP500"));
            }
        } else if (cty.contains("japan")) {
            assets.add("USDJPY");
            if (ind.contains("boj") || ind.contains("interest rate")) {
                assets.addAll(Arrays.asList("GOLD", "SP500", "NASDAQ", "EURUSD"));
            }
        } else if (cty.contains("eurozone") || cty.contains("euro area") || ind.contains("ecb")) {
            assets.add("EURUSD");
            if (ind.contains("ecb") || ind.contains("cpi") || ind.contains("inflation")) {
                assets.addAll(Arrays.asList("GBPUSD", "GOLD", "SP500", "USDJPY"));
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(assets));
    }

    private static boolean isMediumHighImpact(String eventName) {
        if (eventName == null || eventName.isEmpty()) return false;
        String ind = eventName.toLowerCase(Locale.US);
        return ind.contains("initial jobless claims") || ind.contains("jobless claims") ||
               ind.contains("adp employment") || ind.contains("percent") ||
               ind.contains("ppi") || ind.contains("producer price") ||
               ind.contains("gdp") || ind.contains("gross domestic product") ||
               ind.contains("crude oil inventories") || ind.contains("eia") ||
               ind.contains("powell") || ind.contains("minutes");
    }

    // ✅ METHODE DE CONVERSION TEMPORELLE STRICTE ET CORRIGÉE
    private static long convertForexFactoryDateToMs(String dateStr) {
        if (dateStr == null || dateStr.isEmpty())
            return System.currentTimeMillis();
        try {
            // Format standard avec Timezone ISO8601 (ex: 2026-06-05T08:30:00-04:00)
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
            return sdf.parse(dateStr).getTime();
        } catch (Exception e) {
            try {
                // Fallback sans timezone -> Défaut New York
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
                sdf.setTimeZone(TimeZone.getTimeZone("America/New_York"));
                return sdf.parse(dateStr).getTime();
            } catch (Exception e2) {
                try {
                    // Date brute sans heure -> Minuit New York
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
            trimmed = "0" + trimmed; // (.3% -> 0.3%)
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

    public static void refreshMissingActuals(Context context) {
        logToMain("🔄 [FOREXFACTORY] Vérification des résultats publiés (Actuals)...");
        try {
            List<CalendarEvent> currentWeekEvents = fetchFromForexFactoryUrl(FF_URL_THIS_WEEK, 168);
            EventDatabase db = EventDatabase.getInstance(context);
            int updatedCount = 0;

            for (CalendarEvent webEvent : currentWeekEvents) {
                if (webEvent.actual != null && !webEvent.actual.equalsIgnoreCase("N/A") && !webEvent.actual.isEmpty()) {
                    long timestampSec = Long.parseLong(webEvent.timestamp);
                    boolean updated = db.updateActualIfMissing(webEvent.indicator, timestampSec, webEvent.actual);
                    if (updated) {
                        updatedCount++;
                        logToMain("✅ [MAJ ACTUAL] " + webEvent.indicator + " -> " + webEvent.actual);
                    }
                }
            }
            
            if (updatedCount > 0) {
                logToMain("📊 [FOREXFACTORY] Synchronisation terminée : " + updatedCount + " valeurs 'actual' mises à jour.");
            } else {
                logToMain("ℹ️ [FOREXFACTORY] Aucun nouveau résultat à mettre à jour.");
            }
        } catch (Exception e) {
            logToMain("❌ [FOREXFACTORY] Erreur lors du refresh des actuals : " + e.getMessage());
        }
    }

    private static void logToMain(String message) {
        Log.d(TAG, message);
        if (MainActivity.instance != null) {
            try {
                MainActivity.instance.addLog(message);
            } catch (Exception e) {
                Log.w(TAG, "Impossible de pousser le log sur l'UI: " + e.getMessage());
            }
        }
    }
}
