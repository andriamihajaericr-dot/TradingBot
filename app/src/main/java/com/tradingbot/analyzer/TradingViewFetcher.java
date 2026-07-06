package com.tradingbot.analyzer;

import android.content.Context;
import android.util.Log;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
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
    private static WebSocket webSocket;
    
    private static final Map<String, String> pendingSymbolResolution = new ConcurrentHashMap<>();
    private static final Map<String, String> pendingSymbolChartSession = new ConcurrentHashMap<>();
    
    // ── Matrice des Actifs Fonda IOF ──
    private static final Map<String, String> SYMBOL_MAP = new HashMap<String, String>() {{
        // Indices
        put("NASDAQ",  "SPREADEX:NDX");
        put("SP500",   "SPREADEX:SPX");

        // Matières Premières
        put("GOLD",    "TVC:GOLD");
        put("USOIL",   "TVC:USOIL");

        // Devises (Forex)
        put("USDJPY",  "VANTAGE:USDJPY");
        put("GBPUSD",  "VANTAGE:GBPUSD");
        put("EURUSD",  "VANTAGE:EURUSD");
    }};

    public static class TVMarketData {
        public final String symbol;
        public final double price;
        public final double changePercent;      
        public final double high;
        public final double low;
        public final double open;
        public final double prevClose;
        public final double variance;           
        public final double volatilityPercent;  
        public final double dailyRangePercent;  
        public final boolean isNearHigh;
        public final boolean isNearLow;
        
        public final double ma200;
        public final boolean aboveMA200;
        
        // ⚡ Niveaux H4 Clôturés (Précédents)
        public final double p4hh; 
        public final double p4hl; 
        public final boolean brokeAboveP4HH; 
        public final boolean brokeBelowP4HL;

        // Niveaux Daily Clôturés
        public final double pdh; 
        public final double pdl; 
        public final boolean brokeAbovePDH; 
        public final boolean brokeBelowPDL; 
        
        // Niveaux Weekly Clôturés
        public final double pwh; 
        public final double pwl; 
        public final boolean brokeAbovePWH; 
        public final boolean brokeBelowPWL; 

        // Niveaux Monthly Clôturés
        public final double pmh; 
        public final double pml; 
        public final boolean brokeAbovePMH; 
        public final boolean brokeBelowPML; 
        
        public final long timestamp;

        public TVMarketData(String symbol, double price, double changePercent,
                            double high, double low, double open, double prevClose,
                            double variance, double ma200, double p4hh, double p4hl,
                            double pdh, double pdl, double pwh, double pwl, 
                            double pmh, double pml, long timestamp) {
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
            
            // ⚡ Initialisation H4
            this.p4hh          = p4hh;
            this.p4hl          = p4hl;
            this.brokeAboveP4HH = (p4hh > 0) && (price > p4hh);
            this.brokeBelowP4HL = (p4hl > 0) && (price < p4hl);

            this.pdh           = pdh;
            this.pdl           = pdl;
            this.brokeAbovePDH = (pdh > 0) && (price > pdh);
            this.brokeBelowPDL = (pdl > 0) && (price < pdl);
            
            this.pwh           = pwh;
            this.pwl           = pwl;
            this.brokeAbovePWH = (pwh > 0) && (price > pwh);
            this.brokeBelowPWL = (pwl > 0) && (price < pwl);

            this.pmh           = pmh;
            this.pml           = pml;
            this.brokeAbovePMH = (pmh > 0) && (price > pmh);
            this.brokeBelowPML = (pml > 0) && (price < pml);
            
            this.timestamp     = timestamp;
        }
    }

    public static void injectKeyLevels(String asset, double dh, double dl, double wh, double wl) {
        Log.d(TAG, "📥 [Webhook] Injection désactivée (Priorité WebSocket TV Multi-Timeframe).");
    }

    public interface OnDataReadyListener {
        void onDataReady(Map<String, TVMarketData> data);
        void onError(String error); 
    }

    public static void fetchAll(OnDataReadyListener listener) {
        if (listener == null) return;
        try {
            listener.onDataReady(java.util.Collections.unmodifiableMap(cache));
        } catch (Exception e) {
            listener.onError(e.getMessage());
        }
    }
    
    private static final String PREFS_WEEKLY = "TradingBotPrefs";

    private static final ConcurrentHashMap<String, TVMarketData> cache = new ConcurrentHashMap<>();
    
    // ⚡ Caches mémoires H4
    private static final ConcurrentHashMap<String, Double> p4hhCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> p4hlCache = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, Double> pdhCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> pdlCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> pwhCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> pwlCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> pmhCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> pmlCache = new ConcurrentHashMap<>();
    
    // ⚡ Anti-spam d'alertes H4
    private static final ConcurrentHashMap<String, Boolean> alertFiredP4HH = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> alertFiredP4HL = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, Boolean> alertFiredPDH = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> alertFiredPDL = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> alertFiredPWH = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> alertFiredPWL = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> alertFiredPMH = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> alertFiredPML = new ConcurrentHashMap<>();
    
    private static final AtomicBoolean isRunning = new AtomicBoolean(false);
    private static final ConcurrentHashMap<String, VarianceCalculator> varianceCalculators = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> lastAlertTime = new ConcurrentHashMap<>();
    private static final long ALERT_COOLDOWN_MS = 5 * 60 * 1000;

    private static final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private static final AtomicBoolean connected = new AtomicBoolean(false);
    private static Context appContext;
    private static OkHttpClient client;

    public static ConcurrentHashMap<String, TVMarketData> getCache() {
        return cache;
    }

    public static String buildContexteMacroGlobal(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("═══ CONTEXTE MACRO GLOBAL TEMPS RÉEL ═══\n");
        
        String[] order = {"NASDAQ", "USOIL", "USDJPY", "GOLD", "EURUSD", "SP500", "GBPUSD"};
        
        for (String key : order) {
            TVMarketData data = cache.get(key);
            if (data != null) {
                int decimals = (key.equals("EURUSD") || key.equals("GBPUSD")) ? 5 : (key.equals("USDJPY") ? 3 : 2);
                String fmt = "%." + decimals + "f";
                
                sb.append("• ").append(key).append(" : ")
                  .append(String.format(Locale.US, fmt, data.price)).append(" (")
                  .append(String.format(Locale.US, "%+.2f", data.changePercent)).append("%) | ")
                  .append("Amp: ").append(String.format(Locale.US, "%.2f", data.volatilityPercent)).append("% | ")
                  .append("Range: ").append(String.format(Locale.US, "%.0f", data.dailyRangePercent)).append("%");
                
                if (data.isNearHigh) {
                    sb.append(" 🔺PrèsHaut");
                } else if (data.isNearLow) {
                    sb.append(" 🔻PrèsBas");
                }
                
                sb.append(" | Var: ").append(String.format(Locale.US, "%.6f", data.variance))
                  .append(" | P4HH=").append(String.format(Locale.US, fmt, data.p4hh))
                  .append(" | P4HL=").append(String.format(Locale.US, fmt, data.p4hl))
                  .append(" | PDH=").append(String.format(Locale.US, fmt, data.pdh))
                  .append(" | PDL=").append(String.format(Locale.US, fmt, data.pdl))
                  .append(" | PWH=").append(String.format(Locale.US, fmt, data.pwh))
                  .append(" | PWL=").append(String.format(Locale.US, fmt, data.pwl))
                  .append(" | PMH=").append(String.format(Locale.US, fmt, data.pmh))
                  .append(" | PML=").append(String.format(Locale.US, fmt, data.pml))
                  .append("\n");
            }
        }
        return sb.toString();
    }

    public static void rolloverDailyLevels() {
        alertFiredP4HH.clear(); // ⚡ Nettoyage H4
        alertFiredP4HL.clear();
        alertFiredPDH.clear();
        alertFiredPDL.clear();
        alertFiredPWH.clear();
        alertFiredPWL.clear();
        alertFiredPMH.clear(); 
        alertFiredPML.clear(); 
        logToUI("🔄 [Anti-Spam] Réinitialisation complète de tous les verrous d'alertes pivots.");
    }

    public static void start(Context context) {
        if (isRunning.getAndSet(true)) {
            logToUI("⏳ [TV] Déjà en exécution.");
            return;
        }
        appContext = context.getApplicationContext();
        
        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        
        logToUI("📡 [TV] Démarrage du pipeline TradingView Multi-Session (H4, D, W, M).");
        loadLevelsFromStorage(); 
        connectWebSocket();
    }

    public static void stop() {
        isRunning.set(false);
        isConnecting.set(false);
        connected.set(false);
        if (webSocket != null) {
            webSocket.close(1000, "Arrêt demandé");
            webSocket = null;
        }
        client = null;
        cache.clear();
        varianceCalculators.clear();
        lastAlertTime.clear();
        logToUI("🛑 [TV] Fetcher arrêté.");
    }

    private static synchronized void connectWebSocket() {
        if (!isRunning.get() || connected.get() || isConnecting.getAndSet(true)) {
            return;
        }

        Request request = new Request.Builder()
                .url("wss://data.tradingview.com/socket.io/websocket")
                .header("Origin", "https://www.tradingview.com")
                .build();

        client.newWebSocket(request, new WebSocketListener() {
            private String quoteSessionId;

            @Override
            public void onOpen(WebSocket ws, Response response) {
                webSocket = ws;
                logToUI("✅ [TV WS] Canal réseau connecté.");
                connected.set(true);
                isConnecting.set(false);
                cache.clear();
                pendingSymbolResolution.clear();
                pendingSymbolChartSession.clear();
            
                // 1. Initialisation Flux Temps Réel Principal
                quoteSessionId = "qs_" + UUID.randomUUID().toString().substring(0, 12);
                sendMessage(ws, "set_auth_token", new Object[]{"unauthorized_user_token"});
                sendMessage(ws, "quote_create_session", new Object[]{quoteSessionId});
                sendMessage(ws, "quote_set_fields", new Object[]{
                        quoteSessionId, "lp", "chp", "ch", "high_price", "low_price", "open_price", "prev_close_price"
                });
            
                // 2. Initialisation des Séries Temporelles (H4, Daily, Weekly, Monthly)
                for (String key : SYMBOL_MAP.keySet()) {
                    String ticker = SYMBOL_MAP.get(key);
                    varianceCalculators.putIfAbsent(key, new VarianceCalculator(20));
            
                    sendMessage(ws, "quote_add_symbols", new Object[]{quoteSessionId, ticker});
            
                    // ⚡ Session Chart H4 (Utilise "240" minutes pour l'API TV)
                    String chartSessionIdH4 = "cs_h4_" + key + "_" + UUID.randomUUID().toString().substring(0, 8);
                    sendMessage(ws, "chart_create_session", new Object[]{chartSessionIdH4, ""});
                    sendMessage(ws, "switch_timezone", new Object[]{chartSessionIdH4, "America/New_York"});
                    String symIdH4 = "sid_h4_" + key; 
                    pendingSymbolResolution.put(symIdH4, key);
                    pendingSymbolChartSession.put(symIdH4, chartSessionIdH4);
                    sendMessage(ws, "resolve_symbol", new Object[]{chartSessionIdH4, symIdH4, "={\"symbol\":\"" + ticker + "\",\"adjustment\":\"splits\"}"});

                    // Session Chart Daily
                    String chartSessionIdD = "cs_d_" + key + "_" + UUID.randomUUID().toString().substring(0, 8);
                    sendMessage(ws, "chart_create_session", new Object[]{chartSessionIdD, ""});
                    sendMessage(ws, "switch_timezone", new Object[]{chartSessionIdD, "America/New_York"});
                    String symIdD = "sid_d_" + key; 
                    pendingSymbolResolution.put(symIdD, key);
                    pendingSymbolChartSession.put(symIdD, chartSessionIdD);
                    sendMessage(ws, "resolve_symbol", new Object[]{chartSessionIdD, symIdD, "={\"symbol\":\"" + ticker + "\",\"adjustment\":\"splits\"}"});

                    // Session Chart Weekly
                    String chartSessionIdW = "cs_w_" + key + "_" + UUID.randomUUID().toString().substring(0, 8);
                    sendMessage(ws, "chart_create_session", new Object[]{chartSessionIdW, ""});
                    sendMessage(ws, "switch_timezone", new Object[]{chartSessionIdW, "America/New_York"});
                    String symIdW = "sid_w_" + key; 
                    pendingSymbolResolution.put(symIdW, key);
                    pendingSymbolChartSession.put(symIdW, chartSessionIdW);
                    sendMessage(ws, "resolve_symbol", new Object[]{chartSessionIdW, symIdW, "={\"symbol\":\"" + ticker + "\",\"adjustment\":\"splits\"}"});

                    // Session Chart Monthly
                    String chartSessionIdM = "cs_m_" + key + "_" + UUID.randomUUID().toString().substring(0, 8);
                    sendMessage(ws, "chart_create_session", new Object[]{chartSessionIdM, ""});
                    sendMessage(ws, "switch_timezone", new Object[]{chartSessionIdM, "America/New_York"});
                    String symIdM = "sid_m_" + key; 
                    pendingSymbolResolution.put(symIdM, key);
                    pendingSymbolChartSession.put(symIdM, chartSessionIdM);
                    sendMessage(ws, "resolve_symbol", new Object[]{chartSessionIdM, symIdM, "={\"symbol\":\"" + ticker + "\",\"adjustment\":\"splits\"}"});
                }
                logToUI("📥 [TV WS] Pipeline initialisé. Synchronisation H4, D, W, M opérationnelle.");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                if (text == null) return;
                if (text.contains("~h~")) {
                    ws.send(text);
                    return;
                }

                int idx = 0;
                while (idx < text.length()) {
                    int first = text.indexOf("~m~", idx);
                    if (first == -1) break;
                    int second = text.indexOf("~m~", first + 3);
                    if (second == -1) break;
                    String lenStr = text.substring(first + 3, second);
                    int length;
                    try { length = Integer.parseInt(lenStr); } catch (NumberFormatException e) { break; }
                    int startPayload = second + 3;
                    int endPayload = startPayload + length;
                    if (endPayload > text.length()) break;
                    String payload = text.substring(startPayload, endPayload);
                    processJsonPayload(ws, payload);
                    idx = endPayload;
                }
            }

            private void sendMessage(WebSocket ws, String method, Object[] params) {
                try {
                    JSONArray arr = new JSONArray();
                    for (Object p : params) arr.put(p);
                    JSONObject msg = new JSONObject();
                    msg.put("m", method);
                    msg.put("p", arr);
                    String payload = msg.toString();
                    ws.send("~m~" + payload.length() + "~m~" + payload);
                } catch (Exception e) {
                    Log.e(TAG, "[TV WS] Erreur envoi", e);
                }
            }

            private void processJsonPayload(WebSocket ws, String payload) {
                try {
                    if (!payload.startsWith("{")) return;
                    JSONObject json = new JSONObject(payload);
                    String m = json.optString("m");
            
                    if ("symbol_resolved".equals(m)) {
                        JSONArray p = json.getJSONArray("p");
                        if (p.length() > 1) {
                            String symId = p.getString(1);
                            String key = pendingSymbolResolution.remove(symId);
                            String assetChartSessionId = pendingSymbolChartSession.remove(symId);
                            
                            if (key != null && ws != null && assetChartSessionId != null) {
                                if (symId.startsWith("sid_h4_")) {
                                    // ⚡ Enregistrement de la série H4 ("240" minutes)
                                    sendMessage(ws, "create_series", new Object[]{assetChartSessionId, "ser_h4_" + key, "s1", symId, "240", 50});
                                } else if (symId.startsWith("sid_d_")) {
                                    sendMessage(ws, "create_series", new Object[]{assetChartSessionId, "ser_d_" + key, "s1", symId, "D", 50});
                                } else if (symId.startsWith("sid_w_")) {
                                    sendMessage(ws, "create_series", new Object[]{assetChartSessionId, "ser_w_" + key, "s1", symId, "W", 50});
                                } else if (symId.startsWith("sid_m_")) {
                                    sendMessage(ws, "create_series", new Object[]{assetChartSessionId, "ser_m_" + key, "s1", symId, "M", 50});
                                }
                            }
                        }
                        return;
                    }
            
                    if ("timescale_update".equals(m)) {
                        JSONArray p = json.getJSONArray("p");
                        if (p.length() > 1) {
                            JSONObject seriesData = p.getJSONObject(1);
                            java.util.Iterator<String> keys = seriesData.keys();
            
                            boolean liveSessionActive = isLiveBarActiveAtNewYork();

                            while (keys.hasNext()) {
                                String seriesId = keys.next();
                                JSONObject obj = seriesData.getJSONObject(seriesId);
            
                                if (obj.has("s")) {
                                    JSONArray sArr = obj.getJSONArray("s");
                                    if (sArr.length() >= 2) {
                                        int targetIndex = liveSessionActive ? (sArr.length() - 2) : (sArr.length() - 1);
                                        JSONObject targetBar = sArr.getJSONObject(targetIndex);
                                        
                                        if (targetBar.has("v")) {
                                            JSONArray vArr = targetBar.getJSONArray("v");
                                            if (vArr.length() >= 4) {
                                                double historicalHigh = vArr.getDouble(2);
                                                double historicalLow  = vArr.getDouble(3);
            
                                                if (seriesId.startsWith("ser_h4_")) {
                                                    // ⚡ Extraction H4 (Longueur de prefixe = 7)
                                                    String key = seriesId.substring(7);
                                                    p4hhCache.put(key, historicalHigh);
                                                    p4hlCache.put(key, historicalLow);
                                                    saveLevelToStorage(key, "p4hh", historicalHigh);
                                                    saveLevelToStorage(key, "p4hl", historicalLow);
                                                } else if (seriesId.startsWith("ser_d_")) {
                                                    String key = seriesId.substring(6);
                                                    pdhCache.put(key, historicalHigh);
                                                    pdlCache.put(key, historicalLow);
                                                    saveLevelToStorage(key, "pdh", historicalHigh);
                                                    saveLevelToStorage(key, "pdl", historicalLow);
                                                } else if (seriesId.startsWith("ser_w_")) {
                                                    String key = seriesId.substring(6);
                                                    pwhCache.put(key, historicalHigh);
                                                    pwlCache.put(key, historicalLow);
                                                    saveLevelToStorage(key, "pwh", historicalHigh);
                                                    saveLevelToStorage(key, "pwl", historicalLow);
                                                } else if (seriesId.startsWith("ser_m_")) {
                                                    String key = seriesId.substring(6);
                                                    pmhCache.put(key, historicalHigh);
                                                    pmlCache.put(key, historicalLow);
                                                    saveLevelToStorage(key, "pmh", historicalHigh);
                                                    saveLevelToStorage(key, "pml", historicalLow);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        return;
                    }
            
                    if ("qsd".equals(m)) {
                        JSONArray p = json.getJSONArray("p");
                        if (p.length() > 1) {
                            JSONObject quote = p.getJSONObject(1);
                            String ticker = quote.optString("n");
                            JSONObject v = quote.optJSONObject("v");
            
                            if (v != null && v.has("lp")) {
                                String key = getKeyFromTicker(ticker);
                                if (key != null) {
                                    double price = v.optDouble("lp", 0);
                                    double change = v.optDouble("chp", 0);
            
                                    TVMarketData existing = cache.get(key);
                                    double high      = v.optDouble("high_price",       existing != null && existing.high > 0      ? existing.high      : price);
                                    double low       = v.optDouble("low_price",        existing != null && existing.low  > 0      ? existing.low       : price);
                                    double open      = v.optDouble("open_price",       existing != null && existing.open > 0      ? existing.open      : price);
                                    double prevClose = v.optDouble("prev_close_price", existing != null && existing.prevClose > 0 ? existing.prevClose : price);
            
                                    if (price > high) high = price;
                                    if (price < low) low = price;
            
                                    VarianceCalculator calc = varianceCalculators.get(key);
                                    double variance = 0.0;
                                    if (calc != null) {
                                        calc.addPrice(price);
                                        variance = calc.getVariance();
                                    }
            
                                    // Extraction de tous les paliers du cache
                                    double p4hh = p4hhCache.getOrDefault(key, 0.0);
                                    double p4hl = p4hlCache.getOrDefault(key, 0.0);
                                    double pdh  = pdhCache.getOrDefault(key, 0.0);
                                    double pdl  = pdlCache.getOrDefault(key, 0.0);
                                    double pwh  = pwhCache.getOrDefault(key, 0.0);
                                    double pwl  = pwlCache.getOrDefault(key, 0.0);
                                    double pmh  = pmhCache.getOrDefault(key, 0.0);
                                    double pml  = pmlCache.getOrDefault(key, 0.0);
            
                                    TVMarketData newData = new TVMarketData(
                                            key, price, change, high, low, open, prevClose,
                                            variance, 0.0, p4hh, p4hl, pdh, pdl, pwh, pwl, pmh, pml,
                                            System.currentTimeMillis()
                                    );
                                    cache.put(key, newData);
                                    checkAndAlert(key, newData);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[TV WS] Erreur traitement JSON", e);
                }
            }

            private String getKeyFromTicker(String ticker) {
                for (Map.Entry<String, String> entry : SYMBOL_MAP.entrySet()) {
                    if (ticker.equals(entry.getValue())) return entry.getKey();
                }
                return null;
            }

            private void checkAndAlert(String key, TVMarketData data) {
                if (appContext == null) return;
                long now = System.currentTimeMillis();

                Long last = lastAlertTime.get(key);
                if (last == null || (now - last) > ALERT_COOLDOWN_MS) {
                    if (data.isNearHigh || data.isNearLow) {
                        int decimals = (key.equals("EURUSD") || key.equals("GBPUSD")) ? 5 : (key.equals("USDJPY") ? 3 : 2);
                        String fmt = "%." + decimals + "f";

                        StringBuilder sb = new StringBuilder();
                        sb.append("📊 *").append(key).append(data.isNearHigh ? "* 🔺 Approche du *plus haut du jour*\n\n" : "* 🔻 Approche du *plus bas du jour*\n\n");
                        sb.append("🔹 *PRIX ACTUEL* : `").append(String.format(Locale.US, fmt, data.price)).append("`\n\n");
                        sb.append("📈 *LES 4 INDICATEURS TEMPS RÉEL :*\n");
                        sb.append("• 1. Variation : `").append(String.format(Locale.US, "%+.2f", data.changePercent)).append("%` (vs Clôture)\n");
                        sb.append("• 2. Volatilité Tick (20t) : `").append(String.format(Locale.US, "%.6f", data.variance)).append("` (Variance)\n");
                        sb.append("• 3. Amplitude Daily : `").append(String.format(Locale.US, "%.2f", data.volatilityPercent)).append("%` (High-Low)\n");
                        sb.append("• 4. Position Range : `").append(String.format(Locale.US, "%.1f", data.dailyRangePercent)).append("%` (0=Bas, 100=Haut)\n\n");
                        
                        sb.append("🏛️ *NIVEAUX PIVOTS CLÔTURÉS (TradingView) :*\n");
                        // ⚡ Affichage H4 dans l'alerte d'approche globale
                        sb.append("• *H4 Precedent* : ").append(data.p4hh > 0 ? "P4HH = `" + String.format(Locale.US, fmt, data.p4hh) + "` | P4HL = `" + String.format(Locale.US, fmt, data.p4hl) + "`\n" : "⚠️ En attente du flux graphique H4...\n");
                        sb.append("• *Daily Precedent* : ").append(data.pdh > 0 ? "PDH = `" + String.format(Locale.US, fmt, data.pdh) + "` | PDL = `" + String.format(Locale.US, fmt, data.pdl) + "`\n" : "⚠️ En attente du flux graphique...\n");
                        sb.append("• *Week Precedente* : ").append(data.pwh > 0 ? "PWH = `" + String.format(Locale.US, fmt, data.pwh) + "` | PWL = `" + String.format(Locale.US, fmt, data.pwl) + "`\n" : "⚠️ En attente du flux graphique...\n");
                        sb.append("• *Month Precedent* : ").append(data.pmh > 0 ? "PMH = `" + String.format(Locale.US, fmt, data.pmh) + "` | PML = `" + String.format(Locale.US, fmt, data.pml) + "`\n" : "⚠️ En attente du flux graphique...\n");

                        if (data.brokeAboveP4HH || data.brokeBelowP4HL || data.brokeAbovePDH || data.brokeBelowPDL || data.brokeAbovePWH || data.brokeBelowPWL || data.brokeAbovePMH || data.brokeBelowPML) {
                            sb.append("\n⚡ *Statut Cassure Niveaux Vrais :*");
                            if (data.brokeAboveP4HH) sb.append(" 🧭[Breakout H4]");
                            if (data.brokeBelowP4HL) sb.append(" 📉[Breakdown H4]");
                            if (data.brokeAbovePDH) sb.append(" 🔺[Breakout PDH]");
                            if (data.brokeBelowPDL) sb.append(" 🔻[Breakdown PDL]");
                            if (data.brokeAbovePWH) sb.append(" 🚀[Breakout PWH]");
                            if (data.brokeBelowPWL) sb.append(" 🔥[Breakdown PWL]");
                            if (data.brokeAbovePMH) sb.append(" 🌌[Breakout PMH]");
                            if (data.brokeBelowPML) sb.append(" ⚡[Breakdown PML]");
                            sb.append("\n");
                        }

                        NotificationService.sendTelegramSecure(sb.toString(), appContext);
                        lastAlertTime.put(key, now);
                    }
                }

                // ⚡ Triggers immédiats (Anti-spam par booléen) pour les niveaux H4
                if (data.brokeAboveP4HH && !Boolean.TRUE.equals(alertFiredP4HH.get(key))) {
                    alertFiredP4HH.put(key, true);
                    NotificationService.sendTelegramSecure("🧭 *" + key + "* — Cassure Intraday du *Previous 4H High* (`" + data.price + "`)", appContext);
                }
                if (data.brokeBelowP4HL && !Boolean.TRUE.equals(alertFiredP4HL.get(key))) {
                    alertFiredP4HL.put(key, true);
                    NotificationService.sendTelegramSecure("📉 *" + key + "* — Cassure Intraday du *Previous 4H Low* (`" + data.price + "`)", appContext);
                }

                // Autres cassures
                if (data.brokeAbovePDH && !Boolean.TRUE.equals(alertFiredPDH.get(key))) {
                    alertFiredPDH.put(key, true);
                    NotificationService.sendTelegramSecure("🔺 *" + key + "* — Cassure réelle du *Previous Day High* (`" + data.price + "`)", appContext);
                }
                if (data.brokeBelowPDL && !Boolean.TRUE.equals(alertFiredPDL.get(key))) {
                    alertFiredPDL.put(key, true);
                    NotificationService.sendTelegramSecure("🔻 *" + key + "* — Cassure réelle du *Previous Day Low* (`" + data.price + "`)", appContext);
                }
                if (data.brokeAbovePWH && !Boolean.TRUE.equals(alertFiredPWH.get(key))) {
                    alertFiredPWH.put(key, true);
                    NotificationService.sendTelegramSecure("🚀 *" + key + "* — Breakout validé du *Previous Week High* (`" + data.price + "`) !", appContext);
                }
                if (data.brokeBelowPWL && !Boolean.TRUE.equals(alertFiredPWL.get(key))) {
                    alertFiredPWL.put(key, true);
                    NotificationService.sendTelegramSecure("🔥 *" + key + "* — Breakdown validé du *Previous Week Low* (`" + data.price + "`) !", appContext);
                }
                if (data.brokeAbovePMH && !Boolean.TRUE.equals(alertFiredPMH.get(key))) {
                    alertFiredPMH.put(key, true);
                    NotificationService.sendTelegramSecure("🌌 *" + key + "* — Macro Breakout du *Previous Month High* (`" + data.price + "`) !! Zone institutionnelle majeure franchie.", appContext);
                }
                if (data.brokeBelowPML && !Boolean.TRUE.equals(alertFiredPML.get(key))) {
                    alertFiredPML.put(key, true);
                    NotificationService.sendTelegramSecure("⚡ *" + key + "* — Macro Breakdown du *Previous Month Low* (`" + data.price + "`) !! Zone institutionnelle majeure enfoncée.", appContext);
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) { handleDisconnection(); }
            @Override
            public void onClosed(WebSocket ws, int code, String reason) { handleDisconnection(); }

            private void handleDisconnection() {
                connected.set(false);
                isConnecting.set(false);
                cache.clear();
                logToUI("🔴 [TV WS] Connexion perdue. Reconnexion automatique dans 5s...");
                if (isRunning.get()) {
                    new Thread(() -> {
                        try { TimeUnit.SECONDS.sleep(5); } catch (InterruptedException ignored) {}
                        connectWebSocket();
                    }).start();
                }
            }
        });
    }

    private static boolean isLiveBarActiveAtNewYork() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"), Locale.US);
        int day = cal.get(Calendar.DAY_OF_WEEK);
        int hour = cal.get(Calendar.HOUR_OF_DAY);

        if (day == Calendar.FRIDAY && hour >= 17) return false;
        if (day == Calendar.SATURDAY) return false;
        if (day == Calendar.SUNDAY && hour < 17) return false;

        return true;
    }

    private static void saveLevelToStorage(String key, String type, double value) {
        if (appContext == null || value <= 0) return;
        try {
            SharedPreferences.Editor editor = appContext.getSharedPreferences(PREFS_WEEKLY, Context.MODE_PRIVATE).edit();
            editor.putString(type + "_" + key, String.valueOf(value));
            editor.apply();
        } catch (Exception e) {
            Log.e(TAG, "Erreur cache local pour " + key, e);
        }
    }

    private static void loadLevelsFromStorage() {
        if (appContext == null) return;
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_WEEKLY, Context.MODE_PRIVATE);
        for (String key : SYMBOL_MAP.keySet()) {
            try {
                // ⚡ Restauration H4 locale
                double savedP4hh = Double.parseDouble(prefs.getString("p4hh_" + key, "0"));
                double savedP4hl = Double.parseDouble(prefs.getString("p4hl_" + key, "0"));

                double savedPdh  = Double.parseDouble(prefs.getString("pdh_" + key, "0"));
                double savedPdl  = Double.parseDouble(prefs.getString("pdl_" + key, "0"));
                double savedPwh  = Double.parseDouble(prefs.getString("pwh_" + key, "0"));
                double savedPwl  = Double.parseDouble(prefs.getString("pwl_" + key, "0"));
                double savedPmh  = Double.parseDouble(prefs.getString("pmh_" + key, "0"));
                double savedPml  = Double.parseDouble(prefs.getString("pml_" + key, "0"));

                if (savedP4hh > 0) p4hhCache.put(key, savedP4hh);
                if (savedP4hl > 0) p4hlCache.put(key, savedP4hl);
                if (savedPdh > 0)  pdhCache.put(key, savedPdh);
                if (savedPdl > 0)  pdlCache.put(key, savedPdl);
                if (savedPwh > 0)  pwhCache.put(key, savedPwh);
                if (savedPwl > 0)  pwlCache.put(key, savedPwl);
                if (savedPmh > 0)  pmhCache.put(key, savedPmh);
                if (savedPml > 0)  pmlCache.put(key, savedPml);
            } catch (NumberFormatException ignored) {}
        }
        if (!p4hhCache.isEmpty() || !pdhCache.isEmpty()) {
            logToUI("📦 [Fonda Local Storage] Réintégration complète de la cartographie pivot (H4, H, W, M).");
        }
    }

    private static void logToUI(String message) {
        Log.d(TAG, message);
    }

    private static class VarianceCalculator {
        private final int period;
        private final double[] window;
        private int index = 0;
        private int count = 0;
        private double sum = 0;
        private double sumSq = 0;

        public VarianceCalculator(int period) {
            this.period = period;
            this.window = new double[period];
        }

        public synchronized void addPrice(double price) {
            if (count < period) {
                window[count] = price;
                sum += price;
                sumSq += price * price;
                count++;
            } else {
                double old = window[index];
                sum -= old;
                sumSq -= old * old;
                
                window[index] = price;
                sum += price;
                sumSq += price * price;
                index = (index + 1) % period;
            }
        }

        public synchronized double getVariance() {
            if (count < 2) return 0.0;
            double mean = sum / count;
            double variance = (sumSq / count) - (mean * mean);
            return variance < 0 ? 0.0 : variance;
        }
    }
}
