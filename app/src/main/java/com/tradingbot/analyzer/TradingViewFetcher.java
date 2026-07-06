package com.tradingbot.analyzer;

import android.content.Context;
import android.util.Log;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class TradingViewFetcher {

    private static final String TAG = "TradingViewFetcher";
    private static WebSocket activeWs;
    
    private static final Map<String, String> pendingSymbolResolution = new ConcurrentHashMap<>();
    private static final Map<String, String> pendingSymbolChartSession = new ConcurrentHashMap<>();
    
    // ── Matrice des Actifs Fonda IOF (Flux Alignés sur vos Graphiques) ──
    // ── Matrice des Actifs Fonda IOF (Flux Alignés sur vos Graphiques) ──
    private static final Map<String, String> SYMBOL_MAP = new HashMap<String, String>() {{
        // Indices
        put("NASDAQ",  "SPREADEX:NDX"); //[cite: 1]
        put("SP500",   "SPREADEX:SPX"); //[cite: 1]

        // Matières Premières
        put("GOLD",    "TVC:GOLD"); //[cite: 1]
        put("USOIL",   "TVC:USOIL"); //[cite: 1]

        // Devises (Forex)
        put("USDJPY",  "VANTAGE:USDJPY"); //[cite: 1]
        put("GBPUSD",  "VANTAGE:GBPUSD"); //[cite: 1]
        put("EURUSD",  "VANTAGE:EURUSD"); //[cite: 1]
    }};

    public static class TVMarketData {
        public final String symbol;
        public final double price;
        public final double changePercent;      // 1. Tendance (vs Clôture)
        public final double high;
        public final double low;
        public final double open;
        public final double prevClose;
        public final double variance;           // 2. Volatilité intraday (20 ticks)
        public final double volatilityPercent;  // 3. Amplitude daily (High-Low %)
        public final double dailyRangePercent;  // 4. Position dans le range (0-100%)
        public final boolean isNearHigh;
        public final boolean isNearLow;
        
        public final double ma200;
        public final boolean aboveMA200;
        
        // Niveaux Daily Clôturés (TradingView Charts)
        public final double pdh; 
        public final double pdl; 
        public final boolean brokeAbovePDH; 
        public final boolean brokeBelowPDL; 
        
        // Niveaux Weekly Clôturés (TradingView Charts)
        public final double pwh; 
        public final double pwl; 
        public final boolean brokeAbovePWH; 
        public final boolean brokeBelowPWL; 
        public final long timestamp;

        public TVMarketData(String symbol, double price, double changePercent,
                            double high, double low, double open, double prevClose,
                            double variance, double ma200, double pdh, double pdl,
                            double pwh, double pwl, long timestamp) {
            this.symbol        = symbol;
            this.price         = price;
            this.changePercent = changePercent;
            this.high          = high;
            this.low           = low;
            this.open          = open;
            this.prevClose     = prevClose;
            this.variance      = variance;
            
            this.volatilityPercent = (high > 0 && low > 0 && high != low)
                    ? (high - low) / ((high + low) / 2) * 100 : 0.0;
            double range = high - low;
            this.dailyRangePercent = (range > 0) ? ((price - low) / range) * 100 : 50.0;
            this.isNearHigh = this.dailyRangePercent >= 95.0;
            this.isNearLow  = this.dailyRangePercent <= 5.0;
            
            this.ma200         = ma200;
            this.aboveMA200    = (ma200 > 0) && (price > ma200);
            
            this.pdh           = pdh;
            this.pdl           = pdl;
            this.brokeAbovePDH = (pdh > 0) && (price > pdh);
            this.brokeBelowPDL = (pdl > 0) && (price < pdl);
            
            this.pwh           = pwh;
            this.pwl           = pwl;
            this.brokeAbovePWH = (pwh > 0) && (price > pwh);
            this.brokeBelowPWL = (pwl > 0) && (price < pwl);
            
            this.timestamp     = timestamp;
        }
    }

    private static final String PREFS_WEEKLY = "TradingBotPrefs";[cite: 1]

    private static final ConcurrentHashMap<String, TVMarketData> cache = new ConcurrentHashMap<>();[cite: 1]
    private static final ConcurrentHashMap<String, Double> pdhCache = new ConcurrentHashMap<>();[cite: 1]
    private static final ConcurrentHashMap<String, Double> pdlCache = new ConcurrentHashMap<>();[cite: 1]
    private static final ConcurrentHashMap<String, Double> pwhCache = new ConcurrentHashMap<>();[cite: 1]
    private static final ConcurrentHashMap<String, Double> pwlCache = new ConcurrentHashMap<>();[cite: 1]
    
    private static final ConcurrentHashMap<String, Boolean> alertFiredPDH = new ConcurrentHashMap<>();[cite: 1]
    private static final ConcurrentHashMap<String, Boolean> alertFiredPDL = new ConcurrentHashMap<>();[cite: 1]
    private static final ConcurrentHashMap<String, Boolean> alertFiredPWH = new ConcurrentHashMap<>();[cite: 1]
    private static final ConcurrentHashMap<String, Boolean> alertFiredPWL = new ConcurrentHashMap<>();[cite: 1]
    
    private static final AtomicBoolean isRunning = new AtomicBoolean(false);[cite: 1]
    private static final ConcurrentHashMap<String, VarianceCalculator> varianceCalculators = new ConcurrentHashMap<>();[cite: 1]
    private static final ConcurrentHashMap<String, Long> lastAlertTime = new ConcurrentHashMap<>();[cite: 1]
    private static final long ALERT_COOLDOWN_MS = 5 * 60 * 1000;[cite: 1]

    private static final AtomicBoolean isConnecting = new AtomicBoolean(false);[cite: 1]
    private static final AtomicBoolean connected = new AtomicBoolean(false);[cite: 1]
    private static Context appContext;
    private static OkHttpClient client;
    private static WebSocket webSocket;

    public static void rolloverDailyLevels() {
        alertFiredPDH.clear();[cite: 1]
        alertFiredPDL.clear();[cite: 1]
        alertFiredPWH.clear();[cite: 1]
        alertFiredPWL.clear();[cite: 1]
        logToUI("🔄 [Anti-Spam] Réinitialisation des déclencheurs d'alertes pivots pour la nouvelle session.");[cite: 1]
    }

    public static void start(Context context) {
        if (isRunning.getAndSet(true)) {[cite: 1]
            logToUI("⏳ [TV] Déjà en cours.");[cite: 1]
            return;
        }
        appContext = context.getApplicationContext();[cite: 1]
        
        client = new OkHttpClient.Builder()[cite: 1]
                .connectTimeout(15, TimeUnit.SECONDS)[cite: 1]
                .readTimeout(15, TimeUnit.SECONDS)[cite: 1]
                .build();[cite: 1]
        
        logToUI("📡 [TV] Démarrage du pipeline 100% TradingView Exclusif.");
        loadLevelsFromStorage(); // Restauration immédiate en attendant la première trame WS
        connectWebSocket();[cite: 1]
    }

    public static void stop() {
        isRunning.set(false);[cite: 1]
        isConnecting.set(false);[cite: 1]
        connected.set(false);[cite: 1]
        if (webSocket != null) {[cite: 1]
            webSocket.close(1000, "Arrêt demandé");[cite: 1]
            webSocket = null;[cite: 1]
        }
        client = null;[cite: 1]
        cache.clear();[cite: 1]
        varianceCalculators.clear();[cite: 1]
        lastAlertTime.clear();[cite: 1]
        logToUI("🛑 [TV] Fetcher arrêté.");[cite: 1]
    }

    private static synchronized void connectWebSocket() {
        if (!isRunning.get() || connected.get() || isConnecting.getAndSet(true)) {[cite: 1]
            return;
        }

        Request request = new Request.Builder()[cite: 1]
                .url("wss://data.tradingview.com/socket.io/websocket")[cite: 1]
                .header("Origin", "https://www.tradingview.com")[cite: 1]
                .build();[cite: 1]

        webSocket = client.newWebSocket(request, new WebSocketListener() {[cite: 1]
            private String quoteSessionId;

            @Override
            public void onOpen(WebSocket ws, Response response) {
                activeWs = ws;[cite: 1]
                logToUI("✅ [TV WS] Canal connecté.");[cite: 1]
                connected.set(true);[cite: 1]
                isConnecting.set(false);[cite: 1]
                cache.clear();[cite: 1]
                pendingSymbolResolution.clear();[cite: 1]
                pendingSymbolChartSession.clear();
            
                // 1. Initialisation Session Temps Réel (Quotes)
                quoteSessionId = "qs_" + UUID.randomUUID().toString().substring(0, 12);[cite: 1]
                sendMessage(ws, "set_auth_token", new Object[]{"unauthorized_user_token"});[cite: 1]
                sendMessage(ws, "quote_create_session", new Object[]{quoteSessionId});[cite: 1]
                sendMessage(ws, "quote_set_fields", new Object[]{[cite: 1]
                        quoteSessionId, "lp", "chp", "ch", "high_price", "low_price", "open_price", "prev_close_price"
                });[cite: 1]
            
                // 2. Initialisation Sessions Graphiques Dédiées (Charts) pour Extraire les Pivots
                for (String key : SYMBOL_MAP.keySet()) {[cite: 1]
                    String ticker = SYMBOL_MAP.get(key);[cite: 1]
                    varianceCalculators.putIfAbsent(key, new VarianceCalculator(5));[cite: 1]
                
                    // Liaison flux temps réel principal
                    sendMessage(ws, "quote_add_symbols", new Object[]{quoteSessionId, ticker});[cite: 1]
                
                    // ── Session Graphique Daily (Anti-Saisons DST) ──
                    String chartSessionIdD = "cs_d_" + key + "_" + UUID.randomUUID().toString().substring(0, 8);
                    sendMessage(ws, "chart_create_session", new Object[]{chartSessionIdD, ""});
                    sendMessage(ws, "switch_timezone", new Object[]{chartSessionIdD, "America/New_York"});

                    String symIdD = "sid_d_" + key; 
                    pendingSymbolResolution.put(symIdD, key);[cite: 1]
                    pendingSymbolChartSession.put(symIdD, chartSessionIdD);
                    sendMessage(ws, "resolve_symbol", new Object[]{chartSessionIdD, symIdD, "={\"symbol\":\"" + ticker + "\",\"adjustment\":\"splits\"}"});[cite: 1]

                    // ── Session Graphique Weekly (Anti-Doublon du Lundi) ──
                    String chartSessionIdW = "cs_w_" + key + "_" + UUID.randomUUID().toString().substring(0, 8);
                    sendMessage(ws, "chart_create_session", new Object[]{chartSessionIdW, ""});
                    sendMessage(ws, "switch_timezone", new Object[]{chartSessionIdW, "America/New_York"});

                    String symIdW = "sid_w_" + key; 
                    pendingSymbolResolution.put(symIdW, key);
                    pendingSymbolChartSession.put(symIdW, chartSessionIdW);
                    sendMessage(ws, "resolve_symbol", new Object[]{chartSessionIdW, symIdW, "={\"symbol\":\"" + ticker + "\",\"adjustment\":\"splits\"}"});
                }
                logToUI("📥 [TV WS] Pipeline configuré à 100%. Synchronisation des niveaux pivots en cours...");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                if (text == null) return;
                if (text.contains("~h~")) {[cite: 1]
                    ws.send(text);[cite: 1]
                    return;
                }

                int idx = 0;
                while (idx < text.length()) {[cite: 1]
                    int first = text.indexOf("~m~", idx);[cite: 1]
                    if (first == -1) break;
                    int second = text.indexOf("~m~", first + 3);[cite: 1]
                    if (second == -1) break;
                    String lenStr = text.substring(first + 3, second);[cite: 1]
                    int length;
                    try { length = Integer.parseInt(lenStr); } catch (NumberFormatException e) { break; }[cite: 1]
                    int startPayload = second + 3;[cite: 1]
                    int endPayload = startPayload + length;[cite: 1]
                    if (endPayload > text.length()) break;[cite: 1]
                    String payload = text.substring(startPayload, endPayload);[cite: 1]
                    processJsonPayload(payload);[cite: 1]
                    idx = endPayload;[cite: 1]
                }
            }

            private void sendMessage(WebSocket ws, String method, Object[] params) {
                try {
                    JSONArray arr = new JSONArray();[cite: 1]
                    for (Object p : params) arr.put(p);[cite: 1]
                    JSONObject msg = new JSONObject();[cite: 1]
                    msg.put("m", method);[cite: 1]
                    msg.put("p", arr);[cite: 1]
                    String payload = msg.toString();[cite: 1]
                    ws.send("~m~" + payload.length() + "~m~" + payload);[cite: 1]
                } catch (Exception e) {
                    Log.e(TAG, "[TV WS] Erreur envoi message", e);[cite: 1]
                }
            }

            private void processJsonPayload(String payload) {
                try {
                    if (!payload.startsWith("{")) return;[cite: 1]
                    JSONObject json = new JSONObject(payload);[cite: 1]
                    String m = json.optString("m");[cite: 1]
            
                    // ── LIAISON ET INSTANCIATION DES SÉRIES TEMPORELLES ──
                    if ("symbol_resolved".equals(m)) {[cite: 1]
                        JSONArray p = json.getJSONArray("p");[cite: 1]
                        if (p.length() > 1) {[cite: 1]
                            String symId = p.getString(1);[cite: 1]
                            String key = pendingSymbolResolution.remove(symId);[cite: 1]
                            String assetChartSessionId = pendingSymbolChartSession.remove(symId);
                            
                            if (key != null && activeWs != null && assetChartSessionId != null) {[cite: 1]
                                if (symId.startsWith("sid_d_")) {
                                    sendMessage(activeWs, "create_series", new Object[]{assetChartSessionId, "ser_d_" + key, "s1", symId, "D", 3});[cite: 1]
                                } else if (symId.startsWith("sid_w_")) {
                                    sendMessage(activeWs, "create_series", new Object[]{assetChartSessionId, "ser_w_" + key, "s1", symId, "W", 3});
                                }
                            }
                        }
                        return;
                    }
            
                    if ("symbol_error".equals(m) || "series_error".equals(m) || "critical_error".equals(m)) {[cite: 1]
                        return; // Sourdine sur les logs d'erreurs d'autorisation graphiques (sans impact sur les quotes)
                    }
            
                    // ── PARSEUR DE PIVOTS SÉCURISÉ (ANTI-DST & ANTI-DOUBLON LUNDI) ──
                    if ("timescale_update".equals(m)) {[cite: 1]
                        JSONArray p = json.getJSONArray("p");[cite: 1]
                        if (p.length() > 1) {[cite: 1]
                            JSONObject seriesData = p.getJSONObject(1);[cite: 1]
                            java.util.Iterator<String> keys = seriesData.keys();[cite: 1]
            
                            while (keys.hasNext()) {[cite: 1]
                                String seriesId = keys.next();[cite: 1]
                                JSONObject obj = seriesData.getJSONObject(seriesId);[cite: 1]
            
                                if (obj.has("s")) {[cite: 1]
                                    JSONArray sArr = obj.getJSONArray("s");[cite: 1]
                                    if (sArr.length() >= 1) {
                                        JSONObject targetBar = null;
                                        
                                        // 🧠 Traitement Daily Sécurisé Calendaire (New York DST)
                                        if (seriesId.startsWith("ser_d_")) {
                                            String key = seriesId.substring(6);
                                            JSONObject lastBar = sArr.getJSONObject(sArr.length() - 1);
                                            if (lastBar.has("v")) {
                                                long lastBarTsSec = lastBar.getJSONArray("v").getLong(0);
                                                
                                                java.util.Calendar barCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/New_York"));
                                                barCal.setTimeInMillis(lastBarTsSec * 1000);
                                                java.util.Calendar todayCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/New_York"));
                                                
                                                if (barCal.get(java.util.Calendar.DAY_OF_YEAR) == todayCal.get(java.util.Calendar.DAY_OF_YEAR) && sArr.length() >= 2) {
                                                    targetBar = sArr.getJSONObject(sArr.length() - 2);
                                                } else {
                                                    targetBar = lastBar;
                                                }
                                            }
                                        } 
                                        // 🧠 Traitement Weekly Sécurisé Calendaire (Filtre dynamique de la semaine)
                                        else if (seriesId.startsWith("ser_w_")) {
                                            String key = seriesId.substring(6);
                                            JSONObject lastBar = sArr.getJSONObject(sArr.length() - 1);
                                            if (lastBar.has("v") && sArr.length() >= 2) {
                                                long lastBarTsSec = lastBar.getJSONArray("v").getLong(0);
                                                
                                                java.util.Calendar barCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/New_York"));
                                                barCal.setTimeInMillis(lastBarTsSec * 1000);
                                                int barWeek = barCal.get(java.util.Calendar.WEEK_OF_YEAR);
                                                
                                                java.util.Calendar currentCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/New_York"));
                                                int currentWeek = currentCal.get(java.util.Calendar.WEEK_OF_YEAR);
                                                
                                                if (barWeek == currentWeek) {
                                                    targetBar = sArr.getJSONObject(sArr.length() - 2);
                                                } else {
                                                    targetBar = lastBar;
                                                }
                                            } else {
                                                targetBar = lastBar;
                                            }
                                        }
                                        
                                        if (targetBar != null && targetBar.has("v")) {[cite: 1]
                                            JSONArray vArr = targetBar.getJSONArray("v");[cite: 1]
                                            if (vArr.length() >= 4) {[cite: 1]
                                                double historicalHigh = vArr.getDouble(2);[cite: 1]
                                                double historicalLow  = vArr.getDouble(3);[cite: 1]
            
                                                if (seriesId.startsWith("ser_d_")) {[cite: 1]
                                                    String key = seriesId.substring(6);[cite: 1]
                                                    pdhCache.put(key, historicalHigh);[cite: 1]
                                                    pdlCache.put(key, historicalLow);[cite: 1]
                                                    saveLevelToStorage(key, "pdh", historicalHigh);
                                                    saveLevelToStorage(key, "pdl", historicalLow);
                                                } else if (seriesId.startsWith("ser_w_")) {[cite: 1]
                                                    String key = seriesId.substring(6);[cite: 1]
                                                    pwhCache.put(key, historicalHigh);[cite: 1]
                                                    pwlCache.put(key, historicalLow);[cite: 1]
                                                    saveLevelToStorage(key, "pwh", historicalHigh);
                                                    saveLevelToStorage(key, "pwl", historicalLow);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        return;
                    }
            
                    // ── FLUX TRADINGVIEW TEMPS RÉEL (QUOTES) ──
                    if ("qsd".equals(m)) {[cite: 1]
                        JSONArray p = json.getJSONArray("p");[cite: 1]
                        if (p.length() > 1) {[cite: 1]
                            JSONObject quote = p.getJSONObject(1);[cite: 1]
                            String ticker = quote.optString("n");[cite: 1]
                            JSONObject v = quote.optJSONObject("v");[cite: 1]
            
                            if (v != null && v.has("lp")) {[cite: 1]
                                String key = getKeyFromTicker(ticker);[cite: 1]
                                if (key != null) {[cite: 1]
                                    double price = v.optDouble("lp", 0);[cite: 1]
                                    double change = v.optDouble("chp", 0);[cite: 1]
            
                                    TVMarketData existing = cache.get(key);[cite: 1]
                                    double high      = v.optDouble("high_price",       existing != null && existing.high > 0      ? existing.high      : price);[cite: 1]
                                    double low       = v.optDouble("low_price",        existing != null && existing.low  > 0      ? existing.low       : price);[cite: 1]
                                    double open      = v.optDouble("open_price",       existing != null && existing.open > 0      ? existing.open      : price);[cite: 1]
                                    double prevClose = v.optDouble("prev_close_price", existing != null && existing.prevClose > 0 ? existing.prevClose : price);[cite: 1]
            
                                    if (price > high) high = price;[cite: 1]
                                    if (price < low) low = price;[cite: 1]
            
                                    VarianceCalculator calc = varianceCalculators.get(key);[cite: 1]
                                    double variance = 0.0;[cite: 1]
                                    if (calc != null) {[cite: 1]
                                        calc.addPrice(price);[cite: 1]
                                        variance = calc.getVariance();[cite: 1]
                                    }
            
                                    double pdh = pdhCache.getOrDefault(key, 0.0);[cite: 1]
                                    double pdl = pdlCache.getOrDefault(key, 0.0);[cite: 1]
                                    double pwh = pwhCache.getOrDefault(key, 0.0);[cite: 1]
                                    double pwl = pwlCache.getOrDefault(key, 0.0);[cite: 1]
            
                                    TVMarketData newData = new TVMarketData([cite: 1]
                                            key, price, change, high, low, open, prevClose,[cite: 1]
                                            variance, 0.0, pdh, pdl, pwh, pwl,[cite: 1]
                                            System.currentTimeMillis()[cite: 1]
                                    );[cite: 1]
                                    cache.put(key, newData);[cite: 1]
            
                                    checkAndAlert(key, newData);[cite: 1]
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[TV WS] Erreur parse JSON", e);[cite: 1]
                }
            }

            private String getKeyFromTicker(String ticker) {
                for (Map.Entry<String, String> entry : SYMBOL_MAP.entrySet()) {
                    if (ticker.equals(entry.getValue())) {[cite: 1]
                        return entry.getKey();[cite: 1]
                    }
                }
                return null;
            }

            private void checkAndAlert(String key, TVMarketData data) {
                if (appContext == null) return;
                long now = System.currentTimeMillis();

                Long last = lastAlertTime.get(key);[cite: 1]
                if (last == null || (now - last) > ALERT_COOLDOWN_MS) {[cite: 1]
                    if (data.isNearHigh || data.isNearLow) {[cite: 1]
                        StringBuilder sb = new StringBuilder();
                        sb.append("📊 *").append(key).append(data.isNearHigh ? "* 🔺 Approche du *plus haut du jour*\n\n" : "* 🔻 Approche du *plus bas du jour*\n\n");[cite: 1]
                        sb.append("🔹 *PRIX ACTUEL* : `").append(String.format(Locale.US, "%.4f", data.price)).append("`\n\n");[cite: 1]
                        sb.append("📈 *LES 4 INDICATEURS TEMPS RÉEL :*\n");[cite: 1]
                        sb.append("• 1. Variation : `").append(String.format(Locale.US, "%+.2f", data.changePercent)).append("%` (vs Clôture)\n");[cite: 1]
                        sb.append("• 2. Volatilité Tick (20t) : `").append(String.format(Locale.US, "%.6f", data.variance)).append("` (Variance)\n");[cite: 1]
                        sb.append("• 3. Amplitude Daily : `").append(String.format(Locale.US, "%.2f", data.volatilityPercent)).append("%` (High-Low)\n");[cite: 1]
                        sb.append("• 4. Position Range : `").append(String.format(Locale.US, "%.1f", data.dailyRangePercent)).append("%` (0=Bas, 100=Haut)\n\n");[cite: 1]
                        
                        sb.append("🏛️ *NIVEAUX PIVOTS (Natifs TradingView) :*\n");[cite: 1]
                        sb.append("• *Daily* : ").append(data.pdh > 0 ? "PDH = `" + String.format(Locale.US, "%.4f", data.pdh) + "` | PDL = `" + String.format(Locale.US, "%.4f", data.pdl) + "`\n" : "⚠️ Analyse du flux graphique...\n");[cite: 1]
                        sb.append("• *Weekly* : ").append(data.pwh > 0 ? "PWH = `" + String.format(Locale.US, "%.4f", data.pwh) + "` | PWL = `" + String.format(Locale.US, "%.4f", data.pwl) + "`\n" : "⚠️ Analyse du flux graphique...\n");[cite: 1]

                        if (data.brokeAbovePDH || data.brokeBelowPDL || data.brokeAbovePWH || data.brokeBelowPWL) {[cite: 1]
                            sb.append("\n⚡ *Statut de cassure :*");[cite: 1]
                            if (data.brokeAbovePDH) sb.append(" 🔺[Breakout PDH]");[cite: 1]
                            if (data.brokeBelowPDL) sb.append(" 🔻[Breakdown PDL]");[cite: 1]
                            if (data.brokeAbovePWH) sb.append(" 🚀[Breakout PWH]");[cite: 1]
                            if (data.brokeBelowPWL) sb.append(" 🔥[Breakdown PWL]");[cite: 1]
                            sb.append("\n");[cite: 1]
                        }

                        NotificationService.sendTelegramSecure(sb.toString(), appContext);[cite: 1]
                        lastAlertTime.put(key, now);[cite: 1]
                    }
                }

                if (data.brokeAbovePDH && !Boolean.TRUE.equals(alertFiredPDH.get(key))) {[cite: 1]
                    alertFiredPDH.put(key, true);[cite: 1]
                    NotificationService.sendTelegramSecure("🔺 *" + key + "* — Cassure du *Previous Day High* (`" + data.price + "`)", appContext);[cite: 1]
                }
                if (data.brokeBelowPDL && !Boolean.TRUE.equals(alertFiredPDL.get(key))) {[cite: 1]
                    alertFiredPDL.put(key, true);[cite: 1]
                    NotificationService.sendTelegramSecure("🔻 *" + key + "* — Cassure du *Previous Day Low* (`" + data.price + "`)", appContext);[cite: 1]
                }
                if (data.brokeAbovePWH && !Boolean.TRUE.equals(alertFiredPWH.get(key))) {[cite: 1]
                    alertFiredPWH.put(key, true);[cite: 1]
                    NotificationService.sendTelegramSecure("🚀 *" + key + "* — Breakout *Previous Week High* (`" + data.price + "`) !", appContext);[cite: 1]
                }
                if (data.brokeBelowPWL && !Boolean.TRUE.equals(alertFiredPWL.get(key))) {[cite: 1]
                    alertFiredPWL.put(key, true);[cite: 1]
                    NotificationService.sendTelegramSecure("🔥 *" + key + "* — Breakdown *Previous Week Low* (`" + data.price + "`) !", appContext);[cite: 1]
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) { handleDisconnection(); }
            @Override
            public void onClosed(WebSocket ws, int code, String reason) { handleDisconnection(); }

            private void handleDisconnection() {
                connected.set(false);[cite: 1]
                isConnecting.set(false);[cite: 1]
                cache.clear();[cite: 1]
                logToUI("🔴 [TV WS] Déconnecté. Reconnexion automatique dans 5s...");[cite: 1]
                if (isRunning.get()) {[cite: 1]
                    new Thread(() -> {
                        try { TimeUnit.SECONDS.sleep(5); } catch (InterruptedException ignored) {}
                        connectWebSocket();
                    }).start();
                }
            }
        });
    }

    private static void saveLevelToStorage(String key, String type, double value) {
        if (appContext == null || value <= 0) return;
        try {
            SharedPreferences.Editor editor = appContext.getSharedPreferences(PREFS_WEEKLY, Context.MODE_PRIVATE).edit();
            editor.putString(type + "_" + key, String.valueOf(value));
            editor.apply();
        } catch (Exception e) {
            Log.e(TAG, "Erreur persistance SharedPreferences pour " + key, e);
        }
    }

    private static void loadLevelsFromStorage() {
        if (appContext == null) return;
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_WEEKLY, Context.MODE_PRIVATE).getSharedPreferences(PREFS_WEEKLY, Context.MODE_PRIVATE);[cite: 1]
        for (String key : SYMBOL_MAP.keySet()) {[cite: 1]
            try {
                double savedPdh = Double.parseDouble(prefs.getString("pdh_" + key, "0"));[cite: 1]
                double savedPdl = Double.parseDouble(prefs.getString("pdl_" + key, "0"));[cite: 1]
                double savedPwh = Double.parseDouble(prefs.getString("pwh_" + key, "0"));[cite: 1]
                double savedPwl = Double.parseDouble(prefs.getString("pwl_" + key, "0"));[cite: 1]

                if (savedPdh > 0) pdhCache.put(key, savedPdh);[cite: 1]
                if (savedPdl > 0) pdlCache.put(key, savedPdl);[cite: 1]
                if (savedPwh > 0) pwhCache.put(key, savedPwh);[cite: 1]
                if (savedPwl > 0) pwlCache.put(key, savedPwl);[cite: 1]
            } catch (NumberFormatException ignored) {}
        }
        if (!pdhCache.isEmpty()) {
            logToUI("📦 [Fonda Storage] " + pdhCache.size() + " structures de pivots restaurées localement au démarrage.");
        }
    }

    private static class VarianceCalculator {
        private final int period; private final double[] window;[cite: 1]
        private int index = 0; private int count = 0; private double sum = 0; private double sumSq = 0;[cite: 1]
        public VarianceCalculator(int period) { this.period = period; this.window = new double[period]; }[cite: 1]
        public synchronized void addPrice(double price) {
            if (count < period) {[cite: 1]
                window[count] = price; sum += price; sumSq += price * price; count++;[cite: 1]
            } else {
                double old = window[index]; sum -= old; sumSq -= old * old;[cite: 1]
                window[index] = price; sum += price; sumSq += price * price;[cite: 1]
                index = (index + 1) % period;[cite: 1]
            }
        }
        public synchronized double getVariance() {
            if (count < 2) return 0;[cite: 1]
            int n = Math.min(count, period);[cite: 1]
            double mean = sum / n; return (sumSq / n) - (mean * mean);[cite: 1]
        }
    }

    public static Map<String, TVMarketData> getCache() { return Collections.unmodifiableMap(cache); }[cite: 1]

    public static String buildContexteMacroGlobal(Context ctx) {
        if (cache.isEmpty()) return "";[cite: 1]
        StringBuilder sb = new StringBuilder();
        sb.append("═══ CONTEXTE MACRO GLOBAL TEMPS RÉEL ═══\n");[cite: 1]

        for (String key : SYMBOL_MAP.keySet()) {[cite: 1]
            TVMarketData d = cache.get(key);[cite: 1]
            if (d != null) {[cite: 1]
                String formatPrice = (key.equals("GBPUSD") || key.equals("EURUSD")) ? "%.5f" : (key.equals("USDJPY") ? "%.3f" : "%.2f");[cite: 1]
                sb.append("• ").append(key).append(" : ")[cite: 1]
                  .append(String.format(Locale.US, formatPrice, d.price))[cite: 1]
                  .append(" (").append(String.format(Locale.US, "%+.2f", d.changePercent)).append("%)")[cite: 1]
                  .append(" | Amp: ").append(String.format(Locale.US, "%.2f", d.volatilityPercent)).append("%")[cite: 1]
                  .append(" | Range: ").append(String.format(Locale.US, "%.0f", d.dailyRangePercent)).append("%")[cite: 1]
                  .append(d.isNearHigh ? " 🔺PrèsHaut" : d.isNearLow ? " 🔻PrèsBas" : "")[cite: 1]
                  .append(" | Var: ").append(String.format(Locale.US, "%.6f", d.variance))[cite: 1]
                  .append(d.pdh > 0 ? " | PDH=" + String.format(Locale.US, formatPrice, d.pdh) : "")[cite: 1]
                  .append(d.pdl > 0 ? " | PDL=" + String.format(Locale.US, formatPrice, d.pdl) : "")[cite: 1]
                  .append(d.brokeAbovePDH ? " 🔺Breakout PDH" : d.brokeBelowPDL ? " 🔻Breakdown PDL" : "")[cite: 1]
                  .append(d.pwh > 0 ? " | PWH=" + String.format(Locale.US, formatPrice, d.pwh) : "")[cite: 1]
                  .append(d.pwl > 0 ? " | PWL=" + String.format(Locale.US, formatPrice, d.pwl) : "")[cite: 1]
                  .append(d.brokeAbovePWH ? " 🚀Breakout PWH" : d.brokeBelowPWL ? " 🔥Breakdown PWL" : "")[cite: 1]
                  .append("\n");[cite: 1]
            }
        }
        String regimeFed = ctx.getSharedPreferences(PREFS_WEEKLY, Context.MODE_PRIVATE).getString("fed_regime", "PAUSE HAWKISH | CPI 4.2%");[cite: 1]
        sb.append("\n🏦 RÉGIME FED : ").append(regimeFed).append("\n");[cite: 1]
        return sb.toString();[cite: 1]
    }

    private static void logToUI(String msg) {
        if (MainActivity.instance != null) { MainActivity.instance.addLog(msg); }[cite: 1]
    }
}
