package com.tradingbot.analyzer;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class EconomicCalendarAPI {

    // API Investing.com (peut être bloquée)
    private static final String INVESTING_API = 
        "https://www.investing.com/economic-calendar/Service/getCalendarFilteredData";
    
    // Alternative: Trading Economics (gratuit avec limite)
    private static final String TRADING_ECONOMICS_API = 
        "https://api.tradingeconomics.com/calendar";
    
    // Alternative: ForexFactory
    private static final String FOREX_FACTORY_URL = 
        "https://www.forexfactory.com/calendar.php";
    
    // Mode test pour générer des données fictives
    private static final boolean TEST_MODE = true;
    
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
            
            // Tentative 1: API Investing.com
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[CALENDAR] Tentative Investing.com...");
            }
            events = fetchFromInvestingAPI(hoursAhead);
            
            if (!events.isEmpty()) {
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog(
                        "[CALENDAR] ✓ Investing.com: " + events.size() + " événements"
                    );
                }
                return events;
            }
            
            // Tentative 2: Scraping ForexFactory
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[CALENDAR] Tentative ForexFactory...");
            }
            events = scrapeForexFactory(hoursAhead);
            
            if (!events.isEmpty()) {
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog(
                        "[CALENDAR] ✓ ForexFactory: " + events.size() + " événements"
                    );
                }
                return events;
            }
            
            // Tentative 3: Données statiques (événements récurrents)
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[CALENDAR] Utilisation calendrier statique...");
            }
            events = getStaticCalendarEvents(hoursAhead);
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[CALENDAR-API] Erreur globale: " + e.getMessage());
                e.printStackTrace();
            }
        }
        
        return events;
    }
    
    // NOUVEAU: Générer événements de test
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
    
    // NOUVEAU: Calendrier statique (événements récurrents)
    private static List<CalendarEvent> getStaticCalendarEvents(int hoursAhead) {
        List<CalendarEvent> events = new ArrayList<>();
        
        try {
            Calendar cal = Calendar.getInstance();
            int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);
            int hour = cal.get(Calendar.HOUR_OF_DAY);
            
            // Vendredi 8h30 EST : NFP (1er vendredi du mois)
            if (dayOfWeek == Calendar.FRIDAY && hour >= 6 && hour <= 14) {
                CalendarEvent nfp = new CalendarEvent();
                nfp.timestamp = String.valueOf(System.currentTimeMillis() / 1000);
                nfp.country = "United States";
                nfp.indicator = "Non-Farm Payrolls (NFP)";
                nfp.importance = "High";
                nfp.forecast = "N/A";
                nfp.previous = "N/A";
                nfp.actual = "N/A";
                nfp.affectedAssets = mapIndicatorToAssets(nfp.indicator, nfp.country);
                events.add(nfp);
            }
            
            // Mercredi 14h00 EST : FOMC (8 fois/an)
            if (dayOfWeek == Calendar.WEDNESDAY && hour >= 12 && hour <= 20) {
                CalendarEvent fomc = new CalendarEvent();
                fomc.timestamp = String.valueOf(System.currentTimeMillis() / 1000);
                fomc.country = "United States";
                fomc.indicator = "FOMC Rate Decision";
                fomc.importance = "High";
                fomc.forecast = "N/A";
                fomc.previous = "N/A";
                fomc.actual = "N/A";
                fomc.affectedAssets = mapIndicatorToAssets(fomc.indicator, fomc.country);
                events.add(fomc);
            }
            
            if (MainActivity.instance != null && !events.isEmpty()) {
                MainActivity.instance.addLog(
                    "[CALENDAR-STATIC] " + events.size() + " événements statiques"
                );
            }
            
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[CALENDAR-STATIC] Erreur: " + e.getMessage());
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
        HttpURLConnection conn = null;
        
        try {
            long now = System.currentTimeMillis();
            long future = now + (hoursAhead * 60 * 60 * 1000);
            
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            String dateFrom = sdf.format(new Date(now));
            String dateTo = sdf.format(new Date(future));
            
            // Construction de la requête POST
            String postData = "dateFrom=" + URLEncoder.encode(dateFrom, "UTF-8") +
                "&dateTo=" + URLEncoder.encode(dateTo, "UTF-8") +
                "&timeZone=55" +
                "&timeFilter=timeRemain" +
                "&currentTab=today" +
                "&submitFilters=1" +
                "&limit_from=0";
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[INVESTING-API] Requête: " + dateFrom + " → " + dateTo);
                MainActivity.instance.addLog("[INVESTING-API] URL: " + INVESTING_API);
            }
            
            URL url = new URL(INVESTING_API);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
            conn.setRequestProperty("User-Agent", 
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Accept", "application/json, text/javascript, */*; q=0.01");
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            conn.setRequestProperty("Referer", "https://www.investing.com/economic-calendar/");
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setConnectTimeout(20000); // 20 secondes
            conn.setReadTimeout(20000);
            
            // Écrire les données POST
            OutputStream os = conn.getOutputStream();
            os.write(postData.getBytes("UTF-8"));
            os.flush();
            os.close();
            
            int responseCode = conn.getResponseCode();
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[INVESTING-API] Code réponse: " + responseCode);
            }
            
            if (responseCode == 200) {
                BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
                br.close();
                
                String jsonResponse = response.toString();
                
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog(
                        "[INVESTING-API] Réponse longueur: " + jsonResponse.length() + " chars"
                    );
                    
                    // Afficher début de la réponse pour debug
                    String preview = jsonResponse.length() > 300 ? 
                        jsonResponse.substring(0, 300) + "..." : jsonResponse;
                    MainActivity.instance.addLog("[INVESTING-API] Aperçu: " + preview);
                }
                
                // Vérifier si c'est du HTML (erreur)
                if (jsonResponse.trim().startsWith("<")) {
                    if (MainActivity.instance != null) {
                        MainActivity.instance.addLog(
                            "[INVESTING-API] ⚠ Réponse HTML au lieu de JSON (probablement bloqué)"
                        );
                    }
                    return events;
                }
                
                events = parseInvestingResponse(jsonResponse);
                
            } else if (responseCode == 403 || responseCode == 429) {
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog(
                        "[INVESTING-API] ⚠ Accès bloqué (code " + responseCode + ")"
                    );
                }
            } else {
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog(
                        "[INVESTING-API] Erreur HTTP " + responseCode
                    );
                    
                    // Lire le message d'erreur
                    try {
                        BufferedReader errReader = new BufferedReader(
                            new InputStreamReader(conn.getErrorStream()));
                        StringBuilder errMsg = new StringBuilder();
                        String errLine;
                        while ((errLine = errReader.readLine()) != null) {
                            errMsg.append(errLine);
                        }
                        errReader.close();
                        MainActivity.instance.addLog("[INVESTING-API] Erreur: " + 
                            errMsg.substring(0, Math.min(200, errMsg.length())));
                    } catch (Exception ignored) {}
                }
            }
            
        } catch (SocketTimeoutException e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[INVESTING-API] ⏱ Timeout de connexion");
            }
        } catch (IOException e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[INVESTING-API] Erreur réseau: " + e.getMessage());
            }
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[INVESTING-API] Erreur: " + e.getMessage());
                e.printStackTrace();
            }
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        
        return events;
    }
    
    private static List<CalendarEvent> parseInvestingResponse(String jsonResponse) {
        List<CalendarEvent> events = new ArrayList<>();
        
        try {
            // Vérification préliminaire
            if (jsonResponse == null || jsonResponse.trim().isEmpty()) {
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog("[PARSING] Réponse vide");
                }
                return events;
            }
            
            // Nettoyer la réponse (supprimer BOM, espaces)
            jsonResponse = jsonResponse.trim();
            if (jsonResponse.startsWith("\uFEFF")) {
                jsonResponse = jsonResponse.substring(1);
            }
            
            JSONObject root = new JSONObject(jsonResponse);
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog(
                    "[PARSING] Clés JSON: " + root.keys().toString()
                );
            }
            
            // Vérifier si la réponse contient des données
            if (!root.has("data")) {
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog("[PARSING] Pas de champ 'data' dans la réponse");
                }
                return events;
            }
            
            Object dataObj = root.get("data");
            
            // Vérifier si data est un tableau
            if (!(dataObj instanceof JSONArray)) {
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog(
                        "[PARSING] 'data' n'est pas un tableau: " + dataObj.getClass().getSimpleName()
                    );
                }
                return events;
            }
            
            JSONArray dataArray = (JSONArray) dataObj;
            
            if (dataArray.length() == 0) {
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog("[PARSING] Tableau 'data' vide");
                }
                return events;
            }
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog(
                    "[PARSING] Traitement de " + dataArray.length() + " événements..."
                );
            }
            
            for (int i = 0; i < dataArray.length(); i++) {
                try {
                    JSONObject eventObj = dataArray.getJSONObject(i);
                    CalendarEvent event = new CalendarEvent();
                    
                    // Timestamp (obligatoire)
                    event.timestamp = getStringSafe(eventObj, "timestamp", "");
                    if (event.timestamp.isEmpty()) {
                        event.timestamp = getStringSafe(eventObj, "date", "");
                    }
                    if (event.timestamp.isEmpty()) {
                        continue;
                    }
                    
                    // Pays
                    event.country = getStringSafe(eventObj, "country", "Unknown");
                    
                    // Indicateur (obligatoire)
                    event.indicator = getStringSafe(eventObj, "event", "");
                    if (event.indicator.isEmpty()) {
                        event.indicator = getStringSafe(eventObj, "name", "");
                    }
                    if (event.indicator.isEmpty()) {
                        continue;
                    }
                    
                    // Importance (1=Low, 2=Medium, 3=High)
                    int importance = getIntSafe(eventObj, "importance", 1);
                    event.importance = importance == 3 ? "High" : 
                                      (importance == 2 ? "Medium" : "Low");
                    
                    // Forecast, Previous, Actual
                    event.forecast = getStringSafe(eventObj, "forecast", "N/A");
                    event.previous = getStringSafe(eventObj, "previous", "N/A");
                    event.actual = getStringSafe(eventObj, "actual", "N/A");
                    
                    // Filtrer uniquement High importance
                    if ("High".equals(event.importance)) {
                        event.affectedAssets = mapIndicatorToAssets(
                            event.indicator, 
                            event.country
                        );
                        
                        events.add(event);
                        
                        if (MainActivity.instance != null) {
                            MainActivity.instance.addLog(
                                "[PARSING] ✓ " + event.indicator + " (" + event.country + ")"
                            );
                        }
                    }
                    
                } catch (Exception e) {
                    if (MainActivity.instance != null) {
                        MainActivity.instance.addLog(
                            "[PARSING] Erreur événement " + i + ": " + e.getMessage()
                        );
                    }
                    continue;
                }
            }
            
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog(
                    "[PARSING] ✓ " + events.size() + " événements HIGH importance extraits"
                );
            }
            
        } catch (org.json.JSONException e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("[PARSING] Erreur JSON: " + e.getMessage());
                MainActivity.instance.addLog("[PARSING] Position: " + 
                    (e.getMessage() != null ? e.getMessage() : "inconnue"));
            }
        } catch (Exception e) {
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog(
                    "[PARSING] Erreur parsing: " + e.getClass().getSimpleName() + 
                    " - " + e.getMessage()
                );
                e.printStackTrace();
            }
        }
        
        return events;
    }
    
    // Méthodes utilitaires pour extraction sécurisée
    private static String getStringSafe(JSONObject obj, String key, String defaultValue) {
        try {
            if (obj.has(key) && !obj.isNull(key)) {
                String value = obj.getString(key);
                return value != null && !value.trim().isEmpty() ? value.trim() : defaultValue;
            }
        } catch (Exception e) {
            // Ignore
        }
        return defaultValue;
    }
    
    private static int getIntSafe(JSONObject obj, String key, int defaultValue) {
        try {
            if (obj.has(key) && !obj.isNull(key)) {
                return obj.getInt(key);
            }
        } catch (Exception e) {
            // Ignore
        }
        return defaultValue;
    }
    
    private static List<CalendarEvent> scrapeForexFactory(int hoursAhead) {
        List<CalendarEvent> events = new ArrayList<>();
        
        try {
            URL url = new URL(FOREX_FACTORY_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", 
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            
            int responseCode = conn.getResponseCode();
            
            if (responseCode == 200) {
                BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
                StringBuilder html = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    html.append(line);
                }
                br.close();
                
                events = parseForexFactoryHTML(html.toString());
            }
            
            conn.disconnect();
            
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
        
        // === PÉTROLE ===
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
