package com.tradingbot.analyzer;

import android.os.Environment;
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
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[CALENDAR] ===== DÉMARRAGE =====");
                MainActivity.instance.addLog("[CALENDAR] hoursAhead = " + hoursAhead);
                MainActivity.instance.addLog("[CALENDAR] DEBUG_MODE = " + DEBUG_MODE);
                MainActivity.instance.addLog("[CALENDAR] USE_TEST_DATA = " + USE_TEST_DATA);
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
                    MainActivity.instance.addLog("[CALENDAR] ⚠ Aucun événement parsé, mode test");
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
                e.printStackTrace();
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
    // SAUVEGARDER POUR DEBUG (MÉTHODE CORRIGÉE)
    // =====================================================
    
    private static void saveDebugFile(String data) {
        FileWriter writer = null;
        
        try {
            // MÉTHODE 1: Dossier de l'app (fonctionne toujours, pas besoin de permission)
            File appDir = MainActivity.instance.getFilesDir();
            File debugFile = new File(appDir, "investing_debug.txt");
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[DEBUG] Tentative sauvegarde: " + debugFile.getAbsolutePath());
            }
            
            writer = new FileWriter(debugFile);
            writer.write("=== RÉPONSE INVESTING.COM ===\n");
            writer.write("Longueur: " + data.length() + " chars\n");
            writer.write("Date: " + new Date().toString() + "\n");
            writer.write("Chemin: " + debugFile.getAbsolutePath() + "\n\n");
            writer.write("=== DONNÉES ===\n");
            writer.write(data);
            writer.flush();
            writer.close();
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[DEBUG] ✓✓✓ FICHIER SAUVEGARDÉ ✓✓✓");
                MainActivity.instance.addLog("[DEBUG] Chemin: " + debugFile.getAbsolutePath());
                MainActivity.instance.addLog("[DEBUG] Taille: " + debugFile.length() + " bytes");
                
                // BONUS: Afficher les 500 premiers caractères dans les logs
                String preview = data.length() > 500 ? data.substring(0, 500) + "..." : data;
                MainActivity.instance.addLog("[DEBUG] === APERÇU RÉPONSE ===");
                MainActivity.instance.addLog(preview);
                MainActivity.instance.addLog("[DEBUG] === FIN APERÇU ===");
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[DEBUG] ❌ Erreur sauvegarde fichier: " + e.getMessage());
                MainActivity.instance.addLog("[DEBUG] StackTrace: " + android.util.Log.getStackTraceString(e));
                
                // FALLBACK: Afficher tout dans les logs (découpé)
                MainActivity.instance.addLog("[DEBUG] === FALLBACK: AFFICHAGE DANS LOGS ===");
                
                int chunkSize = 1000;
                int chunks = (data.length() + chunkSize - 1) / chunkSize;
                
                MainActivity.instance.addLog("[DEBUG] Total: " + data.length() + " chars en " + chunks + " morceaux");
                
                for (int i = 0; i < data.length(); i += chunkSize) {
                    int end = Math.min(i + chunkSize, data.length());
                    String chunk = data.substring(i, end);
                    int chunkNum = (i / chunkSize) + 1;
                    MainActivity.instance.addLog("[CHUNK-" + chunkNum + "/" + chunks + "] " + chunk);
                }
                
                MainActivity.instance.addLog("[DEBUG] === FIN FALLBACK ===");
            }
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    // Ignore
                }
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
                    
                    // Extraire le texte de l'événement
                    event.indicator = extractEventName(rowHtml);
                    event.country = extractCountry(rowHtml);
                    event.forecast = extractTdValue(rowHtml, "forecast");
                    event.previous = extractTdValue(rowHtml, "previous");
                    event.actual = extractTdValue(rowHtml, "actual");
                    
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
    
    // Extraire valeur d'un <td>
    private static String extractTdValue(String html, String type) {
        try {
            String classPattern = "";
            if (type.equals("forecast")) {
                classPattern = "bold|forecast";
            } else if (type.equals("previous")) {
                classPattern = "previous|blackFont";
            } else if (type.equals("actual")) {
                classPattern = "actual|act";
            }
            
            Pattern pattern = Pattern.compile(
                "<td[^>]*class=['\"][^'\"]*(" + classPattern + ")[^'\"]*['\"][^>]*>([^<]*)</td>"
            );
            Matcher matcher = pattern.matcher(html);
            
            if (matcher.find()) {
                String value = matcher.group(2).trim();
                return value.isEmpty() ? "N/A" : value;
            }
        } catch (Exception e) {}
        
        return "N/A";
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
            Calendar cal = Calendar.getInstance();
            
            // NFP
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
            nfp.affectedAssets = Arrays.asList("GOLD", "BTCUSD", "EURUSD", "GBPUSD", "SP500", "NASDAQ");
            events.add(nfp);
            
            // CPI
            CalendarEvent cpi = new CalendarEvent();
            cal.setTimeInMillis(now + (2 * 24 * 60 * 60 * 1000));
            cal.set(Calendar.HOUR_OF_DAY, 8);
            cal.set(Calendar.MINUTE, 30);
            cpi.timestamp = String.valueOf(cal.getTimeInMillis() / 1000);
            cpi.country = "United States";
            cpi.indicator = "Consumer Price Index (CPI)";
            cpi.importance = "High";
            cpi.forecast = "3.3%";
            cpi.previous = "3.1%";
            cpi.actual = "N/A";
            cpi.affectedAssets = Arrays.asList("GOLD", "BTCUSD", "EURUSD", "GBPUSD", "USDJPY");
            events.add(cpi);
            
            // FOMC
            CalendarEvent fomc = new CalendarEvent();
            cal.setTimeInMillis(now + (3 * 24 * 60 * 60 * 1000));
            cal.set(Calendar.HOUR_OF_DAY, 14);
            cal.set(Calendar.MINUTE, 0);
            fomc.timestamp = String.valueOf(cal.getTimeInMillis() / 1000);
            fomc.country = "United States";
            fomc.indicator = "FOMC Rate Decision";
            fomc.importance = "High";
            fomc.forecast = "5.25%";
            fomc.previous = "5.25%";
            fomc.actual = "N/A";
            fomc.affectedAssets = Arrays.asList("GOLD", "BTCUSD", "EURUSD", "GBPUSD", "SP500", "NASDAQ");
            events.add(fomc);
            
            // GDP
            CalendarEvent gdp = new CalendarEvent();
            cal.setTimeInMillis(now + (4 * 24 * 60 * 60 * 1000));
            cal.set(Calendar.HOUR_OF_DAY, 8);
            cal.set(Calendar.MINUTE, 30);
            gdp.timestamp = String.valueOf(cal.getTimeInMillis() / 1000);
            gdp.country = "United States";
            gdp.indicator = "GDP Growth Rate";
            gdp.importance = "High";
            gdp.forecast = "2.8%";
            gdp.previous = "2.5%";
            gdp.actual = "N/A";
            gdp.affectedAssets = Arrays.asList("GOLD", "EURUSD", "USDJPY", "SP500", "NASDAQ");
            events.add(gdp);
            
            // Retail Sales
            CalendarEvent retail = new CalendarEvent();
            cal.setTimeInMillis(now + (5 * 24 * 60 * 60 * 1000));
            cal.set(Calendar.HOUR_OF_DAY, 8);
            cal.set(Calendar.MINUTE, 30);
            retail.timestamp = String.valueOf(cal.getTimeInMillis() / 1000);
            retail.country = "United States";
            retail.indicator = "Retail Sales";
            retail.importance = "High";
            retail.forecast = "0.5%";
            retail.previous = "0.3%";
            retail.actual = "N/A";
            retail.affectedAssets = Arrays.asList("SP500", "NASDAQ", "EURUSD");
            events.add(retail);
            
            // EIA Oil
            CalendarEvent oil = new CalendarEvent();
            cal.setTimeInMillis(now + (6 * 24 * 60 * 60 * 1000));
            cal.set(Calendar.HOUR_OF_DAY, 10);
            cal.set(Calendar.MINUTE, 30);
            oil.timestamp = String.valueOf(cal.getTimeInMillis() / 1000);
            oil.country = "United States";
            oil.indicator = "EIA Crude Oil Inventories";
            oil.importance = "High";
            oil.forecast = "-2.5M";
            oil.previous = "-1.8M";
            oil.actual = "N/A";
            oil.affectedAssets = Arrays.asList("OIL", "USDCAD");
            events.add(oil);
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[TEST] " + events.size() + " événements test générés");
                for (CalendarEvent e : events) {
                    MainActivity.instance.addLog("[TEST] • " + e.toString());
                }
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[TEST] Erreur: " + e.getMessage());
            }
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
            
            List<CalendarEvent> allEvents = fetchUpcomingEvents(48);
            
            for (CalendarEvent event : allEvents) {
                try {
                    long eventTime = Long.parseLong(event.timestamp) * 1000;
                    if (eventTime >= windowStart && eventTime <= now) {
                        events.add(event);
                    }
                } catch (Exception e) {
                    // Skip cet événement
                }
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[RECENT] Erreur: " + e.getMessage());
            }
        }
        
        return events;
    }
    
    // =====================================================
    // MAPPER ACTIFS
    // =====================================================
    
    private static List<String> mapIndicatorToAssets(String indicator, String country) {
        List<String> assets = new ArrayList<>();
        
        if (indicator == null || country == null) {
            assets.add("GOLD");
            assets.add("BTCUSD");
            return assets;
        }
        
        String ind = indicator.toLowerCase();
        String cty = country.toLowerCase();
        
        // US
        if (cty.contains("us") || cty.contains("united states")) {
            if (ind.contains("nfp") || ind.contains("payroll")) {
                assets.add("GOLD");
                assets.add("BTCUSD");
                assets.add("EURUSD");
                assets.add("GBPUSD");
                assets.add("USDJPY");
                assets.add("SP500");
                assets.add("NASDAQ");
            }
            else if (ind.contains("cpi") || ind.contains("inflation")) {
                assets.add("GOLD");
                assets.add("BTCUSD");
                assets.add("EURUSD");
                assets.add("GBPUSD");
                assets.add("USDJPY");
            }
            else if (ind.contains("gdp")) {
                assets.add("GOLD");
                assets.add("EURUSD");
                assets.add("USDJPY");
                assets.add("SP500");
                assets.add("NASDAQ");
            }
            else if (ind.contains("fed") || ind.contains("fomc")) {
                assets.add("GOLD");
                assets.add("BTCUSD");
                assets.add("EURUSD");
                assets.add("GBPUSD");
                assets.add("USDJPY");
                assets.add("SP500");
                assets.add("NASDAQ");
            }
            else if (ind.contains("retail")) {
                assets.add("SP500");
                assets.add("NASDAQ");
                assets.add("EURUSD");
            }
            else if (ind.contains("pmi") || ind.contains("ism")) {
                assets.add("SP500");
                assets.add("NASDAQ");
                assets.add("EURUSD");
                assets.add("GOLD");
            }
            else {
                assets.add("GOLD");
                assets.add("EURUSD");
            }
        }
        // UK
        else if (cty.contains("uk") || cty.contains("britain")) {
            assets.add("GBPUSD");
            if (ind.contains("cpi") || ind.contains("boe")) {
                assets.add("GOLD");
            }
        }
        // Japan
        else if (cty.contains("japan")) {
            assets.add("USDJPY");
            if (ind.contains("boj")) {
                assets.add("GOLD");
            }
        }
        // Eurozone
        else if (cty.contains("euro") || cty.contains("germany")) {
            assets.add("EURUSD");
            if (ind.contains("ecb")) {
                assets.add("GOLD");
            }
        }
        // Australia
        else if (cty.contains("australia")) {
            assets.add("AUDUSD");
            if (ind.contains("rba")) {
                assets.add("GOLD");
            }
        }
        // Canada
        else if (cty.contains("canada")) {
            assets.add("USDCAD");
            if (ind.contains("boc")) {
                assets.add("GOLD");
            }
        }
        
        // Oil
        if (ind.contains("oil") || ind.contains("eia") || ind.contains("crude")) {
            assets.add("OIL");
            assets.add("USDCAD");
        }
        
        // Default
        if (assets.isEmpty()) {
            assets.add("GOLD");
            assets.add("BTCUSD");
        }
        
        return assets;
    }
}
