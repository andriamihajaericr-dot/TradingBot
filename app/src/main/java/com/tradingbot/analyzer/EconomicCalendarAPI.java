package com.tradingbot.analyzer;

import android.content.Context;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;

public class EconomicCalendarAPI {

    private static final String TAG = "EconomicCalendarAPI";
    
    private static final String INVESTING_API_POST = 
        "https://www.investing.com/economic-calendar/Service/getCalendarFilteredData";
    
    // ACTIVEZ POUR DEBUG
    private static final boolean DEBUG_MODE = false; // Mettre false en production
    
    // ACTIVEZ SI LE PARSING NE FONCTIONNE PAS
    private static final boolean USE_TEST_DATA = false;
    
    public static class CalendarEvent {
        public String timestamp;
        public String country;
        public String indicator;
        public String importance;
        public String forecast;
        public String previous;
        public String actual;
        public List<String> affectedAssets; // PRIORISÉS selon le pays
        
        public CalendarEvent() {
            this.affectedAssets = new ArrayList<>();
        }
        
        @Override
        public String toString() {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
            String dateStr = "N/A";
            try {
                long ts = Long.parseLong(timestamp) * 1000;
                dateStr = sdf.format(new Date(ts));
            } catch (Exception e) {}
            
            StringBuilder assetsStr = new StringBuilder();
            if (!affectedAssets.isEmpty()) {
                assetsStr.append(" → ");
                for (int i = 0; i < Math.min(3, affectedAssets.size()); i++) {
                    if (i > 0) assetsStr.append(", ");
                    assetsStr.append(affectedAssets.get(i));
                }
                if (affectedAssets.size() > 3) {
                    assetsStr.append("...");
                }
            }
            
            return "[" + dateStr + "] " + indicator + " (" + country + ") - " + 
                   importance + assetsStr.toString();
        }
    }
    
    // =====================================================
    // POINT D'ENTRÉE
    // =====================================================
    
    public static List<CalendarEvent> fetchUpcomingEvents(int hoursAhead) {
        List<CalendarEvent> events = new ArrayList<>();
        
        try {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[CALENDAR] Démarrage...");
            }
            
            if (USE_TEST_DATA) {
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog("[CALENDAR] Mode TEST activé");
                }
                return generateTestEvents(hoursAhead);
            }
            
            events = fetchFromInvestingAPI(hoursAhead);
            
            if (events.isEmpty()) {
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog("[CALENDAR] ⚠ API vide, mode test");
                }
                events = generateTestEvents(hoursAhead);
            } else {
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog("[CALENDAR] ✓ " + events.size() + " événements chargés");
                }
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[CALENDAR] Erreur: " + e.getMessage());
            }
            Log.e(TAG, "Erreur fetchUpcomingEvents", e);
        }
        
        return events;
    }
    
    // =====================================================
    // REQUÊTE INVESTING.COM
    // =====================================================
    
    private static List<CalendarEvent> fetchFromInvestingAPI(int hoursAhead) {
        List<CalendarEvent> events = new ArrayList<>();
        HttpURLConnection conn = null;
        
        try {
            long now = System.currentTimeMillis();
            long future = now + (hoursAhead * 60 * 60 * 1000);
            
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            String dateFrom = sdf.format(new Date(now));
            String dateTo = sdf.format(new Date(future));
            
            String postData = "dateFrom=" + URLEncoder.encode(dateFrom, "UTF-8") +
                "&dateTo=" + URLEncoder.encode(dateTo, "UTF-8") +
                "&timeZone=55" +
                "&timeFilter=timeRemain" +
                "&currentTab=today" +
                "&submitFilters=1" +
                "&limit_from=0";
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[INVESTING] Requête: " + dateFrom + " → " + dateTo);
            }
            
            URL url = new URL(INVESTING_API_POST);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            conn.setRequestProperty("User-Agent", 
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setRequestProperty("Referer", "https://www.investing.com/economic-calendar/");
            conn.setDoOutput(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(20000);
            
            OutputStream os = conn.getOutputStream();
            os.write(postData.getBytes("UTF-8"));
            os.flush();
            os.close();
            
            int responseCode = conn.getResponseCode();
            
            if (responseCode == 200) {
                BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
                br.close();
                
                String responseData = response.toString();
                
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog(
                        "[INVESTING] ✓ Réponse: " + responseData.length() + " chars"
                    );
                }
                
                // DEBUG
                if (DEBUG_MODE && MainActivity.instance != null) {
                    saveDebugFile(MainActivity.instance, responseData);
                }
                
                // Parser
                events = parseInvestingResponse(responseData);
                
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog(
                        "[INVESTING] ✓ " + events.size() + " événements HIGH"
                    );
                }
            } else {
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog("[INVESTING] ❌ Code " + responseCode);
                }
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[INVESTING] Erreur: " + e.getMessage());
            }
            Log.e(TAG, "Erreur API", e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        
        return events;
    }
    
    // =====================================================
    // SAUVEGARDER DEBUG
    // =====================================================
    
    private static void saveDebugFile(Context context, String data) {
        try {
            File debugDir = new File(context.getExternalFilesDir(null), "Debug");
            debugDir.mkdirs();
            
            File debugFile = new File(debugDir, "investing_debug.txt");
            
            FileWriter writer = new FileWriter(debugFile);
            writer.write("=== INVESTING.COM ===\n");
            writer.write("Date: " + new Date().toString() + "\n");
            writer.write("Longueur: " + data.length() + " chars\n\n");
            writer.write("=== DONNÉES BRUTES ===\n");
            writer.write(data);
            writer.close();
            
            Log.d(TAG, "✓ Debug sauvegardé: " + debugFile.getAbsolutePath());
            
        } catch (Exception e) {
            Log.e(TAG, "Erreur sauvegarde debug", e);
        }
    }
    
    // =====================================================
    // PARSER LA RÉPONSE
    // =====================================================
    
    private static List<CalendarEvent> parseInvestingResponse(String jsonResponse) {
        List<CalendarEvent> events = new ArrayList<>();
        
        try {
            JSONObject root = new JSONObject(jsonResponse);
            
            if (!root.has("data")) {
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog("[PARSE] Pas de champ 'data'");
                }
                return events;
            }
            
            String htmlData = root.getString("data");
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[PARSE] HTML data: " + htmlData.length() + " chars");
            }
            
            events = parseHTMLRows(htmlData);
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[PARSE] Erreur: " + e.getMessage());
            }
            Log.e(TAG, "Erreur parsing", e);
        }
        
        return events;
    }
    
    // =====================================================
    // PARSER HTML
    // =====================================================
    
    private static List<CalendarEvent> parseHTMLRows(String html) {
        List<CalendarEvent> events = new ArrayList<>();
        
        try {
            Pattern rowPattern = Pattern.compile(
                "<tr[^>]*id=\"eventRowId_(\\d+)\"[^>]*data-event-datetime=\"([^\"]+)\"[^>]*>(.*?)</tr>",
                Pattern.DOTALL
            );
            
            Matcher rowMatcher = rowPattern.matcher(html);
            
            int totalRows = 0;
            int highEvents = 0;
            
            while (rowMatcher.find()) {
                totalRows++;
                
                String eventId = rowMatcher.group(1);
                String datetime = rowMatcher.group(2);
                String rowContent = rowMatcher.group(3);
                
                try {
                    int bullCount = countOccurrences(rowContent, "grayFullBullishIcon");
                    
                    if (bullCount >= 3) {
                        highEvents++;
                        
                        CalendarEvent event = new CalendarEvent();
                        event.importance = "High";
                        event.timestamp = convertDateTimeToTimestamp(datetime);
                        event.country = extractCountryFromRow(rowContent);
                        event.indicator = extractIndicatorFromRow(rowContent);
                        event.forecast = extractValueByClass(rowContent, "fore");
                        event.previous = extractValueByClass(rowContent, "prev");
                        event.actual = extractValueByClass(rowContent, "act");
                        
                        if (event.indicator != null && 
                            event.indicator.length() > 3 && 
                            !event.indicator.equalsIgnoreCase("Holiday")) {
                            
                            // PRIORISATION SELON LE PAYS
                            event.affectedAssets = mapIndicatorToAssetsPrioritized(
                                event.indicator, event.country);
                            
                            events.add(event);
                            
                            if (MainActivity.instance != null) {
                                MainActivity.instance.addLog(
                                    "[PARSE] ✓ " + event.country + ": " + event.indicator + 
                                    " → " + event.affectedAssets.get(0)
                                );
                            }
                        }
                    }
                    
                } catch (Exception e) {
                    Log.e(TAG, "Erreur ligne " + eventId, e);
                }
            }
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog(
                    "[PARSE] Lignes: " + totalRows + 
                    " | HIGH: " + highEvents + 
                    " | Valides: " + events.size()
                );
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[PARSE] Erreur HTML: " + e.getMessage());
            }
            Log.e(TAG, "Erreur parseHTMLRows", e);
        }
        
        return events;
    }
    
    private static String extractCountryFromRow(String rowHtml) {
        try {
            Pattern pattern = Pattern.compile("title=\"([^\"]+)\"[^>]*class=\"ceFlags");
            Matcher matcher = pattern.matcher(rowHtml);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur extractCountry", e);
        }
        return "Unknown";
    }
    
    private static String extractIndicatorFromRow(String rowHtml) {
        try {
            Pattern pattern = Pattern.compile(
                "<a[^>]*href=\"/economic-calendar/[^\"]+\"[^>]*>\\s*([^<]+)\\s*</a>",
                Pattern.DOTALL
            );
            Matcher matcher = pattern.matcher(rowHtml);
            if (matcher.find()) {
                String indicator = matcher.group(1).trim();
                indicator = indicator.replaceAll("\\s+", " ");
                return indicator;
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur extractIndicator", e);
        }
        return "Economic Event";
    }
    
    private static String extractValueByClass(String rowHtml, String className) {
        try {
            Pattern pattern = Pattern.compile(
                "<td[^>]*class=\"[^\"]*" + className + "[^\"]*\"[^>]*>([^<]*)</td>",
                Pattern.CASE_INSENSITIVE
            );
            Matcher matcher = pattern.matcher(rowHtml);
            if (matcher.find()) {
                String value = matcher.group(1).trim();
                value = value.replace("&nbsp;", "").trim();
                return value.isEmpty() ? "N/A" : value;
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur extractValue", e);
        }
        return "N/A";
    }
    
    private static String convertDateTimeToTimestamp(String datetime) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US);
            Date date = sdf.parse(datetime);
            return String.valueOf(date.getTime() / 1000);
        } catch (Exception e) {
            Log.e(TAG, "Erreur convert timestamp: " + datetime, e);
            return String.valueOf(System.currentTimeMillis() / 1000);
        }
    }
    
    private static int countOccurrences(String text, String pattern) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(pattern, index)) != -1) {
            count++;
            index += pattern.length();
        }
        return count;
    }
    
    // =====================================================
    // MAPPER ACTIFS AVEC PRIORISATION PAR PAYS
    // =====================================================
    
    private static List<String> mapIndicatorToAssetsPrioritized(String indicator, String country) {
        List<String> assets = new ArrayList<>();
        
        if (indicator == null || country == null) {
            // Fallback par défaut
            assets.add("GOLD");
            assets.add("BTCUSD");
            return assets;
        }
        
        String ind = indicator.toLowerCase();
        String cty = country.toLowerCase();
        
        // ========================================
        // PRIORISATION PAR PAYS
        // ========================================
        
        // === ÉTATS-UNIS → SP500, NASDAQ en priorité ===
        if (cty.contains("us") || cty.contains("united states") || cty.contains("u.s")) {
            
            // NFP - Impact maximal
            if (ind.contains("nfp") || ind.contains("non-farm") || ind.contains("payroll")) {
                assets.add("SP500");
                assets.add("NASDAQ");
                assets.add("GOLD");
                assets.add("BTCUSD");
                assets.add("EURUSD");
                assets.add("GBPUSD");
                assets.add("USDJPY");
            }
            // CPI - Inflation
            else if (ind.contains("cpi") || ind.contains("inflation") || ind.contains("pce")) {
                assets.add("GOLD");
                assets.add("BTCUSD");
                assets.add("SP500");
                assets.add("NASDAQ");
                assets.add("EURUSD");
                assets.add("USDJPY");
            }
            // GDP
            else if (ind.contains("gdp")) {
                assets.add("SP500");
                assets.add("NASDAQ");
                assets.add("GOLD");
                assets.add("EURUSD");
                assets.add("USDJPY");
            }
            // Fed / FOMC
            else if (ind.contains("fed") || ind.contains("fomc") || ind.contains("rate decision")) {
                assets.add("GOLD");
                assets.add("SP500");
                assets.add("NASDAQ");
                assets.add("BTCUSD");
                assets.add("EURUSD");
                assets.add("GBPUSD");
                assets.add("USDJPY");
            }
            // Retail Sales
            else if (ind.contains("retail")) {
                assets.add("SP500");
                assets.add("NASDAQ");
                assets.add("EURUSD");
            }
            // PMI / ISM
            else if (ind.contains("pmi") || ind.contains("ism")) {
                assets.add("SP500");
                assets.add("NASDAQ");
                assets.add("GOLD");
            }
            // Pétrole US (EIA)
            else if (ind.contains("eia") || ind.contains("oil") || ind.contains("crude") || 
                     ind.contains("inventory")) {
                assets.add("OIL");
                assets.add("SP500");
            }
            // Autres événements US
            else {
                assets.add("SP500");
                assets.add("NASDAQ");
                assets.add("GOLD");
            }
        }
        
        // === ROYAUME-UNI → GBPUSD en priorité ===
        else if (cty.contains("uk") || cty.contains("united kingdom") || 
                 cty.contains("britain") || cty.contains("england")) {
            assets.add("GBPUSD");
            
            if (ind.contains("cpi") || ind.contains("inflation") || 
                ind.contains("rate") || ind.contains("boe")) {
                assets.add("GOLD");
            }
            if (ind.contains("gdp")) {
                assets.add("GOLD");
            }
        }
        
        // === JAPON → USDJPY en priorité ===
        else if (cty.contains("japan")) {
            assets.add("USDJPY");
            
            if (ind.contains("cpi") || ind.contains("inflation") || 
                ind.contains("boj") || ind.contains("rate")) {
                assets.add("GOLD");
            }
            if (ind.contains("gdp")) {
                assets.add("GOLD");
            }
        }
        
        // === EUROZONE → EURUSD en priorité ===
        else if (cty.contains("euro") || cty.contains("germany") || cty.contains("german") ||
                 cty.contains("france") || cty.contains("french") || 
                 cty.contains("italy") || cty.contains("italian") ||
                 cty.contains("spain") || cty.contains("spanish")) {
            assets.add("EURUSD");
            
            if (ind.contains("cpi") || ind.contains("inflation") || 
                ind.contains("ecb") || ind.contains("rate")) {
                assets.add("GOLD");
            }
            if (ind.contains("gdp")) {
                assets.add("GOLD");
            }
            if (ind.contains("pmi")) {
                assets.add("GOLD");
            }
        }
        
        // === AUSTRALIE → AUDUSD en priorité ===
        else if (cty.contains("australia") || cty.contains("aussie")) {
            assets.add("AUDUSD");
            
            if (ind.contains("rba") || ind.contains("rate") || 
                ind.contains("cpi") || ind.contains("employment")) {
                assets.add("GOLD");
            }
        }
        
        // === CANADA → Pétrole prioritaire si indicateur pétrolier ===
        else if (cty.contains("canada") || cty.contains("canadian")) {
            
            if (ind.contains("oil") || ind.contains("crude") || 
                ind.contains("energy") || ind.contains("petroleum")) {
                assets.add("OIL");
                assets.add("GOLD");
            } else {
                assets.add("GOLD");
                
                if (ind.contains("boc") || ind.contains("rate")) {
                    assets.add("OIL");
                }
            }
        }
        
        // === CHINE → Impact global ===
        else if (cty.contains("china") || cty.contains("chinese")) {
            assets.add("GOLD");
            assets.add("AUDUSD"); // Australie dépend de la Chine
            
            if (ind.contains("gdp") || ind.contains("pmi")) {
                assets.add("SP500");
            }
        }
        
        // === AUTRES PAYS → GOLD par défaut ===
        else {
            assets.add("GOLD");
            assets.add("BTCUSD");
        }
        
        // ========================================
        // COMPLÉMENTS SELON L'INDICATEUR
        // ========================================
        
        // Pétrole (si pas déjà ajouté)
        if ((ind.contains("oil") || ind.contains("crude") || ind.contains("opec") ||
             ind.contains("eia") || ind.contains("api") || ind.contains("inventory")) &&
            !assets.contains("OIL")) {
            assets.add(0, "OIL"); // Mettre en premier
        }
        
        // Crypto si mention Bitcoin/Crypto
        if ((ind.contains("crypto") || ind.contains("bitcoin") || ind.contains("btc")) &&
            !assets.contains("BTCUSD")) {
            assets.add("BTCUSD");
        }
        
        // Fallback si vide
        if (assets.isEmpty()) {
            assets.add("GOLD");
            assets.add("BTCUSD");
        }
        
        return assets;
    }
    
    // =====================================================
    // DONNÉES TEST
    // =====================================================
    
    private static List<CalendarEvent> generateTestEvents(int hoursAhead) {
        List<CalendarEvent> events = new ArrayList<>();
        
        try {
            long now = System.currentTimeMillis();
            Calendar cal = Calendar.getInstance();
            
            // NFP US
            CalendarEvent nfp = new CalendarEvent();
            cal.setTimeInMillis(now);
            while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY) {
                cal.add(Calendar.DAY_OF_MONTH, 1);
            }
            cal.set(Calendar.HOUR_OF_DAY, 8);
            cal.set(Calendar.MINUTE, 30);
            nfp.timestamp = String.valueOf(cal.getTimeInMillis() / 1000);
            nfp.country = "United States";
            nfp.indicator = "Non-Farm Payrolls (NFP)";
            nfp.importance = "High";
            nfp.forecast = "185K";
            nfp.previous = "180K";
            nfp.actual = "N/A";
            nfp.affectedAssets = Arrays.asList("SP500", "NASDAQ", "GOLD", "BTCUSD", "EURUSD");
            events.add(nfp);
            
            // CPI Germany (EURUSD prioritaire)
            CalendarEvent cpiGer = new CalendarEvent();
            cal.setTimeInMillis(now + (1 * 24 * 60 * 60 * 1000));
            cal.set(Calendar.HOUR_OF_DAY, 8);
            cal.set(Calendar.MINUTE, 0);
            cpiGer.timestamp = String.valueOf(cal.getTimeInMillis() / 1000);
            cpiGer.country = "Germany";
            cpiGer.indicator = "Consumer Price Index (CPI)";
            cpiGer.importance = "High";
            cpiGer.forecast = "2.5%";
            cpiGer.previous = "2.4%";
            cpiGer.actual = "N/A";
            cpiGer.affectedAssets = Arrays.asList("EURUSD", "GOLD");
            events.add(cpiGer);
            
            // BOE Rate (GBPUSD prioritaire)
            CalendarEvent boe = new CalendarEvent();
            cal.setTimeInMillis(now + (2 * 24 * 60 * 60 * 1000));
            cal.set(Calendar.HOUR_OF_DAY, 12);
            cal.set(Calendar.MINUTE, 0);
            boe.timestamp = String.valueOf(cal.getTimeInMillis() / 1000);
            boe.country = "United Kingdom";
            boe.indicator = "BOE Interest Rate Decision";
            boe.importance = "High";
            boe.forecast = "4.50%";
            boe.previous = "4.50%";
            boe.actual = "N/A";
            boe.affectedAssets = Arrays.asList("GBPUSD", "GOLD");
            events.add(boe);
            
            // EIA Oil (OIL prioritaire)
            CalendarEvent oil = new CalendarEvent();
            cal.setTimeInMillis(now + (3 * 24 * 60 * 60 * 1000));
            cal.set(Calendar.HOUR_OF_DAY, 10);
            cal.set(Calendar.MINUTE, 30);
            oil.timestamp = String.valueOf(cal.getTimeInMillis() / 1000);
            oil.country = "United States";
            oil.indicator = "EIA Crude Oil Inventories";
            oil.importance = "High";
            oil.forecast = "-2.5M";
            oil.previous = "-1.8M";
            oil.actual = "N/A";
            oil.affectedAssets = Arrays.asList("OIL", "SP500");
            events.add(oil);
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[TEST] " + events.size() + " événements test générés");
                for (CalendarEvent e : events) {
                    MainActivity.instance.addLog("[TEST] " + e.toString());
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Erreur test", e);
        }
        
        return events;
    }
    
    // =====================================================
    // ÉVÉNEMENTS RÉCENTS
    // =====================================================
    
    public static List<CalendarEvent> fetchRecentEvents(int minutesAgo) {
        List<CalendarEvent> events = new ArrayList<>();
        
        try {
            long now = System.currentTimeMillis();
            long windowStart = now - (minutesAgo * 60 * 1000);
            
            List<CalendarEvent> allEvents = fetchUpcomingEvents(24);
            
            for (CalendarEvent event : allEvents) {
                try {
                    long eventTime = Long.parseLong(event.timestamp) * 1000;
                    if (eventTime >= windowStart && eventTime <= now) {
                        events.add(event);
                    }
                } catch (Exception e) {
                    // Skip
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Erreur fetchRecent", e);
        }
        
        return events;
    }
}
