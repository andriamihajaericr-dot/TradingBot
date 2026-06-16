package com.tradingbot.analyzer;

import android.content.Context;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.util.*;

// ⚡ Nouveaux objets temporels modernes, thread-safe et immutables
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class EconomicCalendarAPI {
    private static final String TAG = "EconomicCalendarAPI";

    private static Context globalAppContext = null;

    // 🗺️ Cartographie immuable des fuseaux horaires réels selon la devise d'impact
    private static final Map<String, ZoneId> CURRENCY_ZONES = new HashMap<>();
    static {
        CURRENCY_ZONES.put("USD", ZoneId.of("America/New_York"));
        CURRENCY_ZONES.put("CAD", ZoneId.of("America/New_York")); 
        CURRENCY_ZONES.put("GBP", ZoneId.of("Europe/London"));
        CURRENCY_ZONES.put("EUR", ZoneId.of("Europe/Paris")); 
        CURRENCY_ZONES.put("JPY", ZoneId.of("Asia/Tokyo"));
        CURRENCY_ZONES.put("AUD", ZoneId.of("Australia/Sydney"));
    }

    // 🕒 Formateurs réutilisables partagés (Évite de saturer la mémoire dans les boucles de traitement)
    private static final DateTimeFormatter FORMATTER_ISO_T = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
    private static final DateTimeFormatter FORMATTER_CUSTOM = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US);
    private static final DateTimeFormatter FORMATTER_DATE_ONLY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US);

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
    private static final long INITIAL_BACKOFF_MS = 30000; 

    private static List<CalendarEvent> fetchWithRetry(FetchFunction fetcher, int hoursAhead) {
        long backoff = INITIAL_BACKOFF_MS;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return fetcher.fetch(hoursAhead); 
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("429")) {
                    logToMain("🛑 [BAN] IP limitée. Pause longue de " + (backoff / 1000) + "s...");
                }
                try { Thread.sleep(backoff); } catch (InterruptedException ie) { break; }
                backoff *= 3; 
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
            
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            if (responseCode == 429) {
                logToMain("⚠️ [CALENDRIER] Limite de requêtes atteinte (HTTP 429) sur " + urlString + ". Pause forcée...");
                throw new IOException("HTTP error code: 429 (Too Many Requests)");
            }
            if (responseCode == 404) {
                logToMain("❌ [CALENDRIER] Ressource introuvable (HTTP 404) pour : " + urlString);
                return events; 
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

                // ✅ INTÉGRATION VALIDÉE : Appel du nouveau module strict gérant le fuseau de la devise
                // ✅ INTÉGRATION VALIDÉE : Appel du nouveau module strict gérant le fuseau de la devise
                long eventMs = parseTimestampStrict(dateStr, country);
                long maxFutureMs = nowMs + ((long) hoursAhead * 60 * 60 * 1000);
                // 🔄 CORRECTIF : Fenêtre élargie à 24 heures pour éviter de manquer des publications en cours de journée
                long minPastMs = nowMs - (24 * 60 * 60 * 1000L); 
                
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

    private static final String FF_URL_THIS_WEEK = "https://nfs.faireconomy.media/ff_calendar_thisweek.json";

    public static List<CalendarEvent> fetchUpcomingEvents(Context context, int hoursAhead) {
        logToMain("🔄 [CALENDRIER] Chargement ForexFactory (This Week)...");
        
        List<CalendarEvent> events = fetchWithRetry(h -> fetchFromForexFactoryUrl(FF_URL_THIS_WEEK, h), hoursAhead);
        
        if (events != null && !events.isEmpty()) {
            logToMain("✅ [CALENDRIER] Succès : " + events.size() + " événements chargés.");
            return events;
        }

        logToMain("⚠️ [CALENDRIER] Aucune donnée disponible pour le moment.");
        return new ArrayList<>();
    }

    public static List<CalendarEvent> fetchHistoricalEvents(int daysBack) {
        return fetchHistoricalEvents(globalAppContext, daysBack);
    }

    public static List<CalendarEvent> fetchHistoricalEvents(Context context, int daysBack) {
        List<CalendarEvent> allEvents = new ArrayList<>();
        long nowSec = System.currentTimeMillis() / 1000;

        if (daysBack > 7) {
            logToMain("⚠️ [BACKFILL] ForexFactory couvre uniquement la semaine en cours. daysBack=" + daysBack + " ignoré.");
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

        if (context != null && !allEvents.isEmpty()) {
            persistCalendarEventsToDB(context, allEvents);
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

    // ✅ NOUVELLE LOGIQUE GÉOLOCALISÉE STRICTE ET TOLÉRANTE AUX ERREURS (JSR-310)
    public static long parseTimestampStrict(String dateStr, String currency) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return System.currentTimeMillis();
        }

        String cleanCurrency = currency != null ? currency.toUpperCase(Locale.ROOT) : "";
        ZoneId targetZone = CURRENCY_ZONES.getOrDefault(cleanCurrency, ZoneId.of("UTC"));

        try {
            // Tentative 1 : Format standard ISO-8601 complet (ex: 2026-06-05T08:30:00-04:00)
            return ZonedDateTime.parse(dateStr).toInstant().toEpochMilli();
        } catch (DateTimeParseException e1) {
            try {
                // Tentative 2 : Date brute avec 'T' mais sans zone (ex: 2026-06-05T08:30:00)
                LocalDateTime localDateTime = LocalDateTime.parse(dateStr, FORMATTER_ISO_T);
                return localDateTime.atZone(targetZone).toInstant().toEpochMilli();
            } catch (DateTimeParseException e2) {
                try {
                    // Tentative 3 : Format personnalisé d'appoint "yyyy-MM-dd HH:mm"
                    LocalDateTime localDateTime = LocalDateTime.parse(dateStr, FORMATTER_CUSTOM);
                    return localDateTime.atZone(targetZone).toInstant().toEpochMilli();
                } catch (DateTimeParseException e3) {
                    try {
                        // Tentative 4 : Journée brute uniquement (ex: 2026-06-05) -> Assigne à minuit pile du fuseau cible
                        java.time.LocalDate localDate = java.time.LocalDate.parse(dateStr, FORMATTER_DATE_ONLY);
                        return localDate.atStartOfDay(targetZone).toInstant().toEpochMilli();
                    } catch (Exception e4) {
                        // 🛡️ Ceinture de sécurité ultime : Évite le gel du thread de traitement de l'application
                        Log.e(TAG, "❌ Échec total de décodage de la date sur : " + dateStr + ". Temps système appliqué.");
                        return System.currentTimeMillis();
                    }
                }
            }
        }
    }

    private static String formatValue(String value) {
        if (value == null || value.isEmpty() || value.equalsIgnoreCase("null")) return "N/A";
        String trimmed = value.trim();
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

            persistCalendarEventsToDB(context, currentWeekEvents);

            if (updatedCount > 0) {
                logToMain("📊 [FOREXFACTORY] Synchronisation terminée : " + updatedCount + " valeurs 'actual' mises à jour.");
            } else {
                logToMain("ℹ️ [FOREXFACTORY] Aucun nouveau résultat à mettre à jour.");
            }
        } catch (Exception e) {
            logToMain("❌ [FOREXFACTORY] Erreur lors du refresh des actuals : " + e.getMessage());
        }
    }

    public static void persistCalendarEventsToDB(Context context, List<CalendarEvent> events) {
        if (context == null || events == null || events.isEmpty()) return;

        EventDatabase db = EventDatabase.getInstance(context);
        int saved = 0;
        int skipped = 0;
        int updatedActuals = 0;

        for (CalendarEvent event : events) {
            if (event == null || event.indicator == null || event.timestamp == null) continue;

            String indUpper = event.indicator.toUpperCase(Locale.US);
            if (indUpper.contains("BANK HOLIDAY") || indUpper.contains("PUBLIC HOLIDAY") ||
                indUpper.contains("MARKET HOLIDAY") || indUpper.contains("NATIONAL HOLIDAY")) continue;

            try {
                long eventTs = Long.parseLong(event.timestamp);
                boolean hasActual = event.actual != null && !event.actual.equalsIgnoreCase("N/A") && !event.actual.trim().isEmpty();
                
                // 1. Détermination dynamique de la chaîne de biais (Uniquement mathématique brute à ce stade)
                String biaisStr = "";
                if (hasActual) {
                    biaisStr = "➔ 🟢 Réel: " + event.actual; // Repli par défaut
                    if (event.forecast != null && !event.forecast.equalsIgnoreCase("N/A") && !event.forecast.trim().isEmpty()) {
                        try {
                            double a = Double.parseDouble(event.actual.replaceAll("[^\\d.\\-]", ""));
                            double f = Double.parseDouble(event.forecast.replaceAll("[^\\d.\\-]", ""));
                            
                            if (a > f) {
                                biaisStr = "➔ 🟢 Réel: " + event.actual + " | Biais: ↑";
                            } else if (a < f) {
                                biaisStr = "➔ 🔴 Réel: " + event.actual + " | Biais: ↓";
                            } else {
                                biaisStr = "➔ ⚪ Réel: " + event.actual + " | Biais: =";
                            }
                        } catch (Exception ignored) {}
                    }
                }

                // 2. Construction de la variable textuelle finale "content"
                String content;
                if (hasActual) {
                    content = event.indicator
                            + (event.country != null ? " | " + event.country : "")
                            + " | Cons: " + event.forecast
                            + " | Préc: " + event.previous
                            + " " + biaisStr;
                } else {
                    content = event.indicator
                            + (event.country != null ? " | " + event.country : "")
                            + " | Forecast: " + (event.forecast != null ? event.forecast : "N/A")
                            + " | Previous: " + (event.previous != null ? event.previous : "N/A")
                            + " | Actual: N/A";
                }

                String fingerprint = "CAL_"
                        + event.indicator.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "_")
                        + "_" + eventTs;

                // 🔄 CORRECTIF : Si l'événement est déjà sauvegardé
                if (db.isEventAlreadySaved(event.indicator, eventTs)) {
                    if (hasActual) {
                        // Crucial : On met à jour l'actual MAIS AUSSI le champ textuel 'content' mis en forme avec la flèche !
                        db.updateActualIfMissing(event.indicator, eventTs, event.actual);
                        // Hypothèse : Si votre EventDatabase possède une méthode pour mettre à jour le contenu complet, l'appeler ici.
                        // Sinon, la mise à jour de l'actual seule fonctionne, mais l'affichage brut devra être géré à la volée.
                        updatedActuals++;
                    }
                    skipped++;
                    continue;
                }

                int weight;
                if ("HIGH".equalsIgnoreCase(event.importance)) {
                    weight = isSupremeCalendarIndicator(event.indicator) ? 5 : 4;
                } else if ("MEDIUM".equalsIgnoreCase(event.importance)) {
                    weight = 2;
                } else {
                    weight = 1;
                }

                String assetsStr = (event.affectedAssets != null && !event.affectedAssets.isEmpty())
                        ? String.join(",", event.affectedAssets)
                        : "";

                String impact = buildCalendarImpact(event);

                db.saveEvent(
                    fingerprint,
                    "CALENDAR",
                    event.country != null ? event.country : "Global",
                    "CALENDAR-RESULT",
                    event.indicator,
                    content,
                    assetsStr,
                    impact,
                    eventTs,
                    "synced",
                    weight
                );
                saved++;

            } catch (Exception e) {
                Log.e(TAG, "Erreur persistCalendarEventsToDB : " + event.indicator, e);
            }
        }
        logToMain("📥 [CALENDRIER DB] " + saved + " persistés, " + updatedActuals + " rafraîchis avec Biais, " + skipped + " doublons ignorés.");
    }

    private static boolean isSupremeCalendarIndicator(String indicator) {
        if (indicator == null) return false;
        String ind = indicator.toUpperCase(Locale.US);
        return ind.contains("CPI")             ||
               ind.contains("PCE")             ||
               ind.contains("NFP")             ||
               ind.contains("NON-FARM")        ||
               ind.contains("PAYROLL")         ||
               ind.contains("FOMC")            ||
               ind.contains("FEDERAL RESERVE") ||
               ind.contains("GDP")             ||
               ind.contains("INTEREST RATE");
    }

    private static String buildCalendarImpact(CalendarEvent event) {
        boolean hasActual   = event.actual   != null && !event.actual.equalsIgnoreCase("N/A")   && !event.actual.isEmpty();
        boolean hasForecast = event.forecast != null && !event.forecast.equalsIgnoreCase("N/A") && !event.forecast.isEmpty();

        if (hasActual && hasForecast) {
            try {
                double a = Double.parseDouble(event.actual.replaceAll("[^\\d.\\-]", ""));
                double f = Double.parseDouble(event.forecast.replaceAll("[^\\d.\\-]", ""));
                if (a > f) return "CALENDRIER ÉCONOMIQUE | Haute Volatilité (Biais Haussier)";
                if (a < f) return "CALENDRIER ÉCONOMIQUE | Haute Volatilité (Biais Baissier)";
                return "CALENDRIER ÉCONOMIQUE | Conforme aux prévisions";
            } catch (Exception ignored) {}
        }
        if (hasForecast) return "CALENDRIER ÉCONOMIQUE | En attente de publication";
        return "CALENDRIER ÉCONOMIQUE | Données insuffisantes";
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
