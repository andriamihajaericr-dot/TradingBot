package com.tradingbot.analyzer;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class EconomicCalendarAPI {

    // API Investing.com (alternative gratuite)
    private static final String INVESTING_API = 
        "https://www.investing.com/economic-calendar/Service/getCalendarFilteredData";
    
    // Alternative: ForexFactory
    private static final String FOREX_FACTORY_URL = 
        "https://www.forexfactory.com/calendar.php";
    
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
    }
    
    // Récupérer événements à venir (prochaines X heures)
    public static List<CalendarEvent> fetchUpcomingEvents(int hoursAhead) {
        List<CalendarEvent> events = new ArrayList<>();
        
        try {
            // Tentative 1: API Investing.com
            events = fetchFromInvestingAPI(hoursAhead);
            
            if (events.isEmpty()) {
                // Fallback: Scraping ForexFactory
                events = scrapeForexFactory(hoursAhead);
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[CALENDAR-API] Erreur: " + e.getMessage());
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
            
            // Récupérer événements des dernières 2 heures
            List<CalendarEvent> allEvents = fetchUpcomingEvents(2);
            
            for (CalendarEvent event : allEvents) {
                long eventTime = parseTimestamp(event.timestamp);
                
                // Événement dans la fenêtre de temps
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
    
    private static List<CalendarEvent> fetchFromInvestingAPI(int hoursAhead) {
        List<CalendarEvent> events = new ArrayList<>();
        
        try {
            long now = System.currentTimeMillis();
            long future = now + (hoursAhead * 60 * 60 * 1000);
            
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            String dateFrom = sdf.format(new Date(now));
            String dateTo = sdf.format(new Date(future));
            
            // Construction de la requête POST
            String postData = "dateFrom=" + URLEncoder.encode(dateFrom, "UTF-8") +
                "&dateTo=" + URLEncoder.encode(dateTo, "UTF-8") +
                "&timeZone=55&timeFilter=timeRemain" +
                "&currentTab=today" +
                "&limit_from=0";
            
            URL url = new URL(INVESTING_API);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            
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
                
                events = parseInvestingResponse(response.toString());
            }
            
            conn.disconnect();
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[INVESTING-API] Erreur: " + e.getMessage());
            }
        }
        
        return events;
    }
    
    private static List<CalendarEvent> parseInvestingResponse(String jsonResponse) {
        List<CalendarEvent> events = new ArrayList<>();
        
        try {
            JSONObject root = new JSONObject(jsonResponse);
            
            if (root.has("data")) {
                JSONArray dataArray = root.getJSONArray("data");
                
                for (int i = 0; i < dataArray.length(); i++) {
                    JSONObject eventObj = dataArray.getJSONObject(i);
                    
                    CalendarEvent event = new CalendarEvent();
                    
                    // Timestamp
                    if (eventObj.has("timestamp")) {
                        event.timestamp = eventObj.getString("timestamp");
                    }
                    
                    // Pays
                    if (eventObj.has("country")) {
                        event.country = eventObj.getString("country");
                    }
                    
                    // Indicateur
                    if (eventObj.has("event")) {
                        event.indicator = eventObj.getString("event");
                    }
                    
                    // Importance (1=Low, 2=Medium, 3=High)
                    if (eventObj.has("importance")) {
                        int imp = eventObj.getInt("importance");
                        event.importance = imp == 3 ? "High" : (imp == 2 ? "Medium" : "Low");
                    }
                    
                    // Forecast, Previous, Actual
                    if (eventObj.has("forecast")) {
                        event.forecast = eventObj.getString("forecast");
                    }
                    if (eventObj.has("previous")) {
                        event.previous = eventObj.getString("previous");
                    }
                    if (eventObj.has("actual")) {
                        event.actual = eventObj.getString("actual");
                    }
                    
                    // Filtrer uniquement High importance
                    if ("High".equals(event.importance)) {
                        // Mapper actifs affectés
                        event.affectedAssets = mapIndicatorToAssets(event.indicator, event.country);
                        events.add(event);
                    }
                }
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[PARSING] Erreur parsing Investing: " + 
                    e.getMessage());
            }
        }
        
        return events;
    }
    
    private static List<CalendarEvent> scrapeForexFactory(int hoursAhead) {
        List<CalendarEvent> events = new ArrayList<>();
        
        try {
            // Scraping basique ForexFactory
            URL url = new URL(FOREX_FACTORY_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", 
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            
            BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream()));
            StringBuilder html = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                html.append(line);
            }
            br.close();
            conn.disconnect();
            
            // Parser HTML (simpliste)
            events = parseForexFactoryHTML(html.toString());
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[FF-SCRAPER] Erreur: " + e.getMessage());
            }
        }
        
        return events;
    }
    
    private static List<CalendarEvent> parseForexFactoryHTML(String html) {
        List<CalendarEvent> events = new ArrayList<>();
        
        // Parser basique (regex simple pour extraction)
        // Format: <td class="calendar__event">NFP</td>
        // Cette partie nécessiterait un parser HTML complet (JSoup)
        // Pour simplifier, on retourne une liste vide
        // En production, utiliser JSoup ou équivalent
        
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
        
        // === PÉTROLE (tous pays producteurs) ===
        if (ind.contains("oil") || ind.contains("crude") || ind.contains("opec") ||
            ind.contains("eia") || ind.contains("api") || ind.contains("inventory")) {
            assets.add("OIL");
            assets.add("USDCAD");
        }
        
        // Si aucun actif détecté, ajouter Gold + BTC par défaut
        if (assets.isEmpty()) {
            assets.add("GOLD");
            assets.add("BTCUSD");
        }
        
        return assets;
    }
    
    private static long parseTimestamp(String timestamp) {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            return sdf.parse(timestamp).getTime();
        } catch (Exception e) {
            try {
                // Format alternatif: epoch seconds
                return Long.parseLong(timestamp) * 1000;
            } catch (Exception e2) {
                return System.currentTimeMillis();
            }
        }
    }
}
