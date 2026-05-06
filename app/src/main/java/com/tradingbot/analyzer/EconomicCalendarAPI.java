package com.tradingbot.analyzer;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;

public class EconomicCalendarAPI {

    private static final String INVESTING_API_POST = 
        "https://www.investing.com/economic-calendar/Service/getCalendarFilteredData";
    
    // ACTIVEZ POUR DEBUG
    private static final boolean DEBUG_MODE = true;
    
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
        public List<String> affectedAssets;
        
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
            
            return "[" + dateStr + "] " + indicator + " (" + country + ") - " + importance;
        }
    }
    
    // =====================================================
    // POINT D'ENTRÉE
    // =====================================================
    
    public static List<CalendarEvent> fetchUpcomingEvents(int hoursAhead) {
        List<CalendarEvent> events = new ArrayList<>();
        
        try {
            if (USE_TEST_DATA) {
                return generateTestEvents(hoursAhead);
            }
            
            events = fetchFromInvestingAPI(hoursAhead);
            
            if (events.isEmpty()) {
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog("[CALENDAR] ⚠ Aucun événement parsé, mode test");
                }
                events = generateTestEvents(hoursAhead);
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[CALENDAR] Erreur: " + e.getMessage());
            }
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
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[INVESTING] ✓ Code: " + responseCode);
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
                
                String responseData = response.toString();
                
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog(
                        "[INVESTING] ✓ Données reçues: " + responseData.length() + " chars"
                    );
                }
                
                // DEBUG : Sauvegarder la réponse complète
                if (DEBUG_MODE) {
                    saveDebugFile(responseData);
                }
                
                // Parser
                events = parseInvestingResponse(responseData);
                
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog(
                        "[INVESTING] " + events.size() + " événements HIGH extraits"
                    );
                }
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[INVESTING] Erreur: " + e.getMessage());
                e.printStackTrace();
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
        
        return events;
    }
    
    // =====================================================
    // SAUVEGARDER POUR DEBUG
    // =====================================================
    
    private static void saveDebugFile(String data) {
        try {
            File debugFile = new File("/storage/emulated/0/DCIM/investing_debug.txt");
            FileWriter writer = new FileWriter(debugFile);
            writer.write("=== RÉPONSE INVESTING.COM ===\n");
            writer.write("Longueur: " + data.length() + " chars\n");
            writer.write("Date: " + new Date().toString() + "\n\n");
            writer.write(data);
            writer.close();
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[DEBUG] ✓ Fichier sauvegardé: " + debugFile.getAbsolutePath());
                MainActivity.instance.addLog("[DEBUG] Ouvrez ce fichier pour voir la réponse exacte");
            }
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[DEBUG] Erreur sauvegarde: " + e.getMessage());
            }
        }
    }
    
    // =====================================================
    // PARSER LA RÉPONSE
    // =====================================================
    
    private static List<CalendarEvent> parseInvestingResponse(String data) {
        List<CalendarEvent> events = new ArrayList<>();
        
        try {
            // Détecter le format
            boolean isJson = data.trim().startsWith("{");
            boolean isHtml = data.contains("<tr") || data.contains("<table");
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[PARSE] Format détecté: " + 
                    (isJson ? "JSON" : isHtml ? "HTML" : "INCONNU"));
            }
            
            if (isJson) {
                // Tenter parsing JSON
                try {
                    JSONObject root = new JSONObject(data);
                    
                    if (MainActivity.instance != null) {
                        MainActivity.instance.addLog("[PARSE] Clés JSON: " + 
                            root.keys().toString());
                    }
                    
                    if (root.has("data")) {
                        String htmlData = root.getString("data");
                        
                        if (MainActivity.instance != null) {
                            MainActivity.instance.addLog("[PARSE] Champ 'data' trouvé: " + 
                                htmlData.length() + " chars");
                        }
                        
                        events = parseHTMLRows(htmlData);
                    }
                    
                } catch (Exception e) {
                    if (MainActivity.instance != null) {
                        MainActivity.instance.addLog("[PARSE] Erreur JSON: " + e.getMessage());
                    }
                }
            }
            
            if (events.isEmpty() && isHtml) {
                // Parser directement HTML
                events = parseHTMLRows(data);
            }
            
            if (events.isEmpty()) {
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog("[PARSE] ⚠ Aucun événement extrait");
                    MainActivity.instance.addLog("[PARSE] Vérifiez /sdcard/investing_debug.txt");
                }
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[PARSE] Erreur globale: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        return events;
    }
    
    // =====================================================
    // PARSER HTML
    // =====================================================
    
    private static List<CalendarEvent> parseHTMLRows(String html) {
        List<CalendarEvent> events = new ArrayList<>();
        
        try {
            // Compter les <tr>
            int trCount = countOccurrences(html, "<tr");
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[HTML-PARSE] " + trCount + " balises <tr> détectées");
            }
            
            // Pattern simple pour extraire les lignes
            Pattern rowPattern = Pattern.compile(
                "<tr[^>]*>(.*?)</tr>",
                Pattern.DOTALL
            );
            
            Matcher rowMatcher = rowPattern.matcher(html);
            
            int rowCount = 0;
            int highCount = 0;
            
            while (rowMatcher.find()) {
                rowCount++;
                String rowHtml = rowMatcher.group(1);
                
                // Détecter importance (3 bulls = High)
                int bulls = countOccurrences(rowHtml, "grayFullBullishIcon");
                
                if (bulls >= 3) {
                    highCount++;
                    
                    CalendarEvent event = new CalendarEvent();
                    event.importance = "High";
                    event.timestamp = String.valueOf(System.currentTimeMillis() / 1000);
                    
                    // Extraire le texte de l'événement (simplifié)
                    event.indicator = extractEventName(rowHtml);
                    event.country = extractCountry(rowHtml);
                    event.forecast = "N/A";
                    event.previous = "N/A";
                    event.actual = "N/A";
                    
                    if (event.indicator != null && !event.indicator.isEmpty()) {
                        event.affectedAssets = mapIndicatorToAssets(
                            event.indicator, event.country);
                        events.add(event);
                        
                        if (DEBUG_MODE && MainActivity.instance != null) {
                            MainActivity.instance.addLog(
                                "[HTML-PARSE] ✓ " + event.indicator + " (" + event.country + ")"
                            );
                        }
                    }
                }
            }
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog(
                    "[HTML-PARSE] Total lignes: " + rowCount + 
                    " | HIGH: " + highCount + 
                    " | Extraits: " + events.size()
                );
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[HTML-PARSE] Erreur: " + e.getMessage());
            }
        }
        
        return events;
    }
    
    // Extraire le nom de l'événement
    private static String extractEventName(String html) {
        try {
            // Chercher dans les liens
            Pattern pattern = Pattern.compile(">([^<]{10,})</a>");
            Matcher matcher = pattern.matcher(html);
            
            while (matcher.find()) {
                String text = matcher.group(1).trim();
                if (!text.matches(".*\\d{4}.*") && text.length() > 5) {
                    return text;
                }
            }
            
            // Fallback
            pattern = Pattern.compile(">([A-Z][^<]{5,})<");
            matcher = pattern.matcher(html);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
            
        } catch (Exception e) {}
        
        return "Economic Event";
    }
    
    // Extraire le pays
    private static String extractCountry(String html) {
        try {
            Pattern pattern = Pattern.compile("title=\"([^\"]+)\"[^>]*flag");
            Matcher matcher = pattern.matcher(html);
            if (matcher.find()) {
                return matcher.group(1);
            }
        } catch (Exception e) {}
        
        return "Unknown";
    }
    
    // Compter occurrences
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
    // DONNÉES TEST
    // =====================================================
    
    private static List<CalendarEvent> generateTestEvents(int hoursAhead) {
        List<CalendarEvent> events = new ArrayList<>();
        
        try {
            long now = System.currentTimeMillis();
            
            // NFP
            CalendarEvent nfp = new CalendarEvent();
            nfp.timestamp = String.valueOf((now + 2 * 60 * 60 * 1000) / 1000);
            nfp.country = "United States";
            nfp.indicator = "Non-Farm Payrolls (NFP)";
            nfp.importance = "High";
            nfp.forecast = "185K";
            nfp.previous = "180K";
            nfp.actual = "N/A";
            nfp.affectedAssets = Arrays.asList("GOLD", "BTCUSD", "EURUSD", "SP500");
            events.add(nfp);
            
            // CPI
            CalendarEvent cpi = new CalendarEvent();
            cpi.timestamp = String.valueOf((now + 4 * 60 * 60 * 1000) / 1000);
            cpi.country = "United States";
            cpi.indicator = "Consumer Price Index (CPI)";
            cpi.importance = "High";
            cpi.forecast = "3.3%";
            cpi.previous = "3.1%";
            cpi.actual = "N/A";
            cpi.affectedAssets = Arrays.asList("GOLD", "BTCUSD", "EURUSD");
            events.add(cpi);
            
            // FOMC
            CalendarEvent fomc = new CalendarEvent();
            fomc.timestamp = String.valueOf((now + 6 * 60 * 60 * 1000) / 1000);
            fomc.country = "United States";
            fomc.indicator = "FOMC Rate Decision";
            fomc.importance = "High";
            fomc.forecast = "5.25%";
            fomc.previous = "5.25%";
            fomc.actual = "N/A";
            fomc.affectedAssets = Arrays.asList("GOLD", "BTCUSD", "EURUSD", "SP500");
            events.add(fomc);
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[TEST] " + events.size() + " événements test");
            }
            
        } catch (Exception e) {}
        
        return events;
    }
    
    public static List<CalendarEvent> fetchRecentEvents(int minutesAgo) {
        return new ArrayList<>();
    }
    
    private static List<String> mapIndicatorToAssets(String indicator, String country) {
        List<String> assets = new ArrayList<>();
        
        if (indicator == null) return assets;
        
        String ind = indicator.toLowerCase();
        
        if (ind.contains("nfp") || ind.contains("payroll")) {
            assets.addAll(Arrays.asList("GOLD", "BTCUSD", "EURUSD", "GBPUSD", "SP500"));
        } else if (ind.contains("cpi") || ind.contains("inflation")) {
            assets.addAll(Arrays.asList("GOLD", "BTCUSD", "EURUSD"));
        } else if (ind.contains("fomc") || ind.contains("fed")) {
            assets.addAll(Arrays.asList("GOLD", "BTCUSD", "EURUSD", "SP500"));
        } else if (ind.contains("gdp")) {
            assets.addAll(Arrays.asList("GOLD", "EURUSD", "SP500"));
        } else {
            assets.addAll(Arrays.asList("GOLD", "BTCUSD"));
        }
        
        return assets;
    }
}
