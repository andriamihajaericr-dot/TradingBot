package com.tradingbot.analyzer;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;

public class EconomicCalendarAPI {

    // API Investing.com
    private static final String INVESTING_CALENDAR_URL = 
        "https://www.investing.com/economic-calendar/";
    
    // Alternative: ForexFactory
    private static final String FOREX_FACTORY_URL = 
        "https://www.forexfactory.com/calendar.php";
    
    // Mode test pour générer des données fictives
    private static final boolean TEST_MODE = false; // CHANGEZ À true SI BESOIN
    
    public static class CalendarEvent {
        public String timestamp;
        public String country;
        public String indicator;
        public String importance; // High, Medium, Low
        public String forecast;
        public String previous;
        public String actual;
        public List<String> affectedAssets;
        
        public CalendarEvent() {
            this.affectedAssets = new ArrayList<>();
        }
        
        @Override
        public String toString() {
            return indicator + " (" + country + ") - " + importance + 
                   " | Forecast: " + forecast + " | Previous: " + previous;
        }
    }
    
    // Récupérer événements à venir (prochaines X heures)
    public static List<CalendarEvent> fetchUpcomingEvents(int hoursAhead) {
        List<CalendarEvent> events = new ArrayList<>();
        
        try {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[CALENDAR] Démarrage fetch events...");
            }
            
            // Mode test : générer données fictives
            if (TEST_MODE) {
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog("[CALENDAR] MODE TEST activé");
                }
                return generateTestEvents(hoursAhead);
            }
            
            // Parser HTML Investing.com
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[CALENDAR] Parsing HTML Investing.com...");
            }
            events = parseInvestingHTML(hoursAhead);
            
            if (!events.isEmpty()) {
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog(
                        "[CALENDAR] ✓ Investing.com: " + events.size() + " événements"
                    );
                }
                return events;
            }
            
            // Fallback: données test
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[CALENDAR] Fallback mode test...");
            }
            events = generateTestEvents(hoursAhead);
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[CALENDAR-API] Erreur globale: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        return events;
    }
    
    // NOUVEAU: Parser le HTML directement
    private static List<CalendarEvent> parseInvestingHTML(int hoursAhead) {
        List<CalendarEvent> events = new ArrayList<>();
        HttpURLConnection conn = null;
        
        try {
            URL url = new URL(INVESTING_CALENDAR_URL);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", 
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);
            
            int responseCode = conn.getResponseCode();
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[INVESTING-HTML] Code réponse: " + responseCode);
            }
            
            if (responseCode == 200) {
                BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line).append("\n");
                }
                br.close();
                
                String html = response.toString();
                
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog(
                        "[INVESTING-HTML] HTML longueur: " + html.length() + " chars"
                    );
                }
                
                events = parseHTMLCalendar(html);
                
            } else {
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog(
                        "[INVESTING-HTML] Erreur HTTP " + responseCode
                    );
                }
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[INVESTING-HTML] Erreur: " + e.getMessage());
                e.printStackTrace();
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        
        return events;
    }
    
    // Parser le HTML du calendrier
    private static List<CalendarEvent> parseHTMLCalendar(String html) {
        List<CalendarEvent> events = new ArrayList<>();
        
        try {
            // Pattern pour extraire les lignes d'événements
            // Format typique: <tr id="eventRowId_123456" class="js-event-item ...">
            Pattern rowPattern = Pattern.compile(
                "<tr[^>]*id=\"eventRowId_(\\d+)\"[^>]*class=\"[^\"]*js-event-item[^\"]*\"[^>]*>(.*?)</tr>",
                Pattern.DOTALL
            );
            
            Matcher rowMatcher = rowPattern.matcher(html);
            
            int count = 0;
            while (rowMatcher.find()) {
                count++;
                String eventId = rowMatcher.group(1);
                String rowHtml = rowMatcher.group(2);
                
                try {
                    CalendarEvent event = parseEventRow(eventId, rowHtml);
                    
                    if (event != null && "High".equals(event.importance)) {
                        event.affectedAssets = mapIndicatorToAssets(
                            event.indicator, 
                            event.country
                        );
                        events.add(event);
                        
                        if (MainActivity.instance != null) {
                            MainActivity.instance.addLog(
                                "[HTML-PARSE] ✓ " + event.indicator + " (" + event.country + ")"
                            );
                        }
                    }
                    
                } catch (Exception e) {
                    if (MainActivity.instance != null) {
                        MainActivity.instance.addLog(
                            "[HTML-PARSE] Erreur ligne " + count + ": " + e.getMessage()
                        );
                    }
                }
            }
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog(
                    "[HTML-PARSE] " + count + " lignes traitées, " + 
                    events.size() + " événements HIGH extraits"
                );
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[HTML-PARSE] Erreur: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        return events;
    }
    
    // Parser une ligne d'événement
    private static CalendarEvent parseEventRow(String eventId, String rowHtml) {
        CalendarEvent event = new CalendarEvent();
        
        try {
            // Timestamp (data-event-datetime="2024/01/15 14:30:00")
            Pattern timePattern = Pattern.compile("data-event-datetime=\"([^\"]+)\"");
            Matcher timeMatcher = timePattern.matcher(rowHtml);
            if (timeMatcher.find()) {
                String datetime = timeMatcher.group(1);
                event.timestamp = convertDateTimeToTimestamp(datetime);
            } else {
                event.timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            }
            
            // Pays (flagCur dans une balise <span>)
            Pattern countryPattern = Pattern.compile("title=\"([^\"]+)\"[^>]*class=\"[^\"]*ceFlags[^\"]*\"");
            Matcher countryMatcher = countryPattern.matcher(rowHtml);
            if (countryMatcher.find()) {
                event.country = countryMatcher.group(1);
            } else {
                event.country = "Unknown";
            }
            
            // Importance (bull icons)
            int bullCount = countOccurrences(rowHtml, "grayFullBullishIcon");
            event.importance = bullCount == 3 ? "High" : (bullCount == 2 ? "Medium" : "Low");
            
            // Indicateur (dans event link)
            Pattern indicatorPattern = Pattern.compile(
                "<a[^>]*class=\"[^\"]*event-link[^\"]*\"[^>]*>([^<]+)</a>"
            );
            Matcher indicatorMatcher = indicatorPattern.matcher(rowHtml);
            if (indicatorMatcher.find()) {
                event.indicator = indicatorMatcher.group(1).trim();
            } else {
                // Fallback: chercher dans td class="left event"
                Pattern eventPattern = Pattern.compile(
                    "<td[^>]*class=\"[^\"]*left[^\"]*event[^\"]*\"[^>]*>([^<]+)</td>"
                );
                Matcher eventMatcher = eventPattern.matcher(rowHtml);
                if (eventMatcher.find()) {
                    event.indicator = eventMatcher.group(1).trim();
                } else {
                    return null; // Pas d'indicateur = skip
                }
            }
            
            // Forecast (bold dans td.bold)
            event.forecast = extractValue(rowHtml, "bold", "forecast");
            
            // Previous (dans td)
            event.previous = extractValue(rowHtml, "blackFont", "previous");
            
            // Actual (dans td.act)
            event.actual = extractValue(rowHtml, "act", "actual");
            
            return event;
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog(
                    "[PARSE-ROW] Erreur eventId " + eventId + ": " + e.getMessage()
                );
            }
            return null;
        }
    }
    
    // Extraire valeur d'une cellule
    private static String extractValue(String html, String className, String type) {
        try {
            Pattern pattern = Pattern.compile(
                "<td[^>]*class=\"[^\"]*" + className + "[^\"]*\"[^>]*>([^<]*)</td>"
            );
            Matcher matcher = pattern.matcher(html);
            if (matcher.find()) {
                String value = matcher.group(1).trim();
                return value.isEmpty() ? "N/A" : value;
            }
        } catch (Exception e) {
            // Ignore
        }
        return "N/A";
    }
    
    // Convertir datetime string → timestamp
    private static String convertDateTimeToTimestamp(String datetime) {
        try {
            // Format: "2024/01/15 14:30:00"
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US);
            Date date = sdf.parse(datetime);
            return String.valueOf(date.getTime() / 1000);
        } catch (Exception e) {
            return String.valueOf(System.currentTimeMillis() / 1000);
        }
    }
    
    // Compter occurrences dans string
    private static int countOccurrences(String text, String pattern) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(pattern, index)) != -1) {
            count++;
            index += pattern.length();
        }
        return count;
    }
    
    // GÉNÉRER ÉVÉNEMENTS DE TEST
    private static List<CalendarEvent> generateTestEvents(int hoursAhead) {
        List<CalendarEvent> events = new ArrayList<>();
        
        try {
            long now = System.currentTimeMillis();
            
            // Événement 1: NFP (dans 2 heures)
            CalendarEvent nfp = new CalendarEvent();
            nfp.timestamp = String.valueOf((now + 2 * 60 * 60 * 1000) / 1000);
            nfp.country = "United States";
            nfp.indicator = "Non-Farm Payrolls (NFP)";
            nfp.importance = "High";
            nfp.forecast = "180K";
            nfp.previous = "175K";
            nfp.actual = "N/A";
            nfp.affectedAssets = mapIndicatorToAssets(nfp.indicator, nfp.country);
            events.add(nfp);
            
            // Événement 2: CPI (dans 4 heures)
            CalendarEvent cpi = new CalendarEvent();
            cpi.timestamp = String.valueOf((now + 4 * 60 * 60 * 1000) / 1000);
            cpi.country = "United States";
            cpi.indicator = "Consumer Price Index (CPI)";
            cpi.importance = "High";
            cpi.forecast = "3.2%";
            cpi.previous = "3.1%";
            cpi.actual = "N/A";
            cpi.affectedAssets = mapIndicatorToAssets(cpi.indicator, cpi.country);
            events.add(cpi);
            
            // Événement 3: FOMC (dans 6 heures)
            CalendarEvent fomc = new CalendarEvent();
            fomc.timestamp = String.valueOf((now + 6 * 60 * 60 * 1000) / 1000);
            fomc.country = "United States";
            fomc.indicator = "FOMC Rate Decision";
            fomc.importance = "High";
            fomc.forecast = "5.25%";
            fomc.previous = "5.25%";
            fomc.actual = "N/A";
            fomc.affectedAssets = mapIndicatorToAssets(fomc.indicator, fomc.country);
            events.add(fomc);
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog(
                    "[CALENDAR-TEST] " + events.size() + " événements test générés"
                );
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[CALENDAR-TEST] Erreur: " + e.getMessage());
            }
        }
        
        return events;
    }
    
    // Récupérer événements récents (dernières X minutes)
    public static List<CalendarEvent> fetchRecentEvents(int minutesAgo) {
        List<CalendarEvent> events = new ArrayList<>();
        
        try {
            long now = System.currentTimeMillis();
            long windowStart = now - (minutesAgo * 60 * 1000);
            
            List<CalendarEvent> allEvents = fetchUpcomingEvents(24); // Dernieres 24h
            
            for (CalendarEvent event : allEvents) {
                long eventTime = parseTimestamp(event.timestamp);
                
                if (eventTime >= windowStart && eventTime <= now) {
                    events.add(event);
                }
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[CALENDAR-API] Erreur récupération récents: " + 
                    e.getMessage());
            }
        }
        
        return events;
    }
    
    // Mapper indicateur + pays → actifs affectés
    private static List<String> mapIndicatorToAssets(String indicator, String country) {
        List<String> assets = new ArrayList<>();
        
        if (indicator == null || country == null) {
            return assets;
        }
        
        String ind = indicator.toLowerCase();
        String cty = country.toLowerCase();
        
        // === DONNÉES MACRO US ===
        if (cty.contains("us") || cty.contains("united states")) {
            
            // NFP
            if (ind.contains("nfp") || ind.contains("non-farm") || ind.contains("payroll")) {
                assets.add("GOLD");
                assets.add("BTCUSD");
                assets.add("EURUSD");
                assets.add("GBPUSD");
                assets.add("USDJPY");
                assets.add("SP500");
                assets.add("NASDAQ");
            }
            
            // CPI
            else if (ind.contains("cpi") || ind.contains("inflation")) {
                assets.add("GOLD");
                assets.add("BTCUSD");
                assets.add("EURUSD");
                assets.add("GBPUSD");
                assets.add("USDJPY");
            }
            
            // GDP
            else if (ind.contains("gdp") || ind.contains("gross domestic")) {
                assets.add("GOLD");
                assets.add("EURUSD");
                assets.add("USDJPY");
                assets.add("SP500");
                assets.add("NASDAQ");
            }
            
            // Fed
            else if (ind.contains("fed") || ind.contains("fomc") || ind.contains("rate decision")) {
                assets.add("GOLD");
                assets.add("BTCUSD");
                assets.add("EURUSD");
                assets.add("GBPUSD");
                assets.add("USDJPY");
                assets.add("SP500");
                assets.add("NASDAQ");
            }
            
            // Retail Sales
            else if (ind.contains("retail")) {
                assets.add("SP500");
                assets.add("NASDAQ");
                assets.add("EURUSD");
            }
            
            // PMI
            else if (ind.contains("pmi") || ind.contains("ism")) {
                assets.add("SP500");
                assets.add("NASDAQ");
                assets.add("EURUSD");
                assets.add("GOLD");
            }
            
            // Autres US
            else {
                assets.add("GOLD");
                assets.add("EURUSD");
            }
        }
        
        // === DONNÉES UK ===
        else if (cty.contains("uk") || cty.contains("united kingdom") || 
                 cty.contains("britain")) {
            assets.add("GBPUSD");
            
            if (ind.contains("cpi") || ind.contains("inflation") || 
                ind.contains("rate") || ind.contains("boe")) {
                assets.add("GOLD");
            }
        }
        
        // === DONNÉES JAPON ===
        else if (cty.contains("japan")) {
            assets.add("USDJPY");
            
            if (ind.contains("cpi") || ind.contains("gdp") || 
                ind.contains("boj") || ind.contains("intervention")) {
                assets.add("GOLD");
            }
        }
        
        // === DONNÉES EUROZONE ===
        else if (cty.contains("euro") || cty.contains("germany") || 
                 cty.contains("france") || cty.contains("italy")) {
            assets.add("EURUSD");
            
            if (ind.contains("cpi") || ind.contains("gdp") || 
                ind.contains("ecb") || ind.contains("rate")) {
                assets.add("GOLD");
            }
        }
        
        // === DONNÉES AUSTRALIE ===
        else if (cty.contains("australia")) {
            assets.add("AUDUSD");
            
            if (ind.contains("rba") || ind.contains("rate") || ind.contains("employment")) {
                assets.add("GOLD");
            }
        }
        
        // === DONNÉES CANADA ===
        else if (cty.contains("canada")) {
            assets.add("USDCAD");
            
            if (ind.contains("boc") || ind.contains("rate")) {
                assets.add("GOLD");
                assets.add("OIL");
            }
        }
        
        // === PÉTROLE ===
        if (ind.contains("oil") || ind.contains("crude") || ind.contains("opec") ||
            ind.contains("eia") || ind.contains("api") || ind.contains("inventory")) {
            assets.add("OIL");
            assets.add("USDCAD");
        }
        
        // Si aucun actif détecté
        if (assets.isEmpty()) {
            assets.add("GOLD");
            assets.add("BTCUSD");
        }
        
        return assets;
    }
    
    private static long parseTimestamp(String timestamp) {
        try {
            return Long.parseLong(timestamp) * 1000;
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }
}
