package com.tradingbot.analyzer;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import android.content.SharedPreferences;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class TradingViewFetcher {

    private static final String TAG = "TradingViewFetcher";

    // ── Tickers TradingView (mode anonyme) ──
    private static final Map<String, String> SYMBOL_MAP = new HashMap<String, String>() {{
        put("SP500",   "SPREADEX:SPX");      // S&P 500 via Spreadex[cite: 3]
        put("NASDAQ",  "SPREADEX:NDX");      // Nasdaq 100 via Spreadex[cite: 3]
        put("GOLD",    "TVC:GOLD");          //[cite: 3]
        put("USOIL",   "TVC:USOIL");         //[cite: 3]
        put("USDJPY",  "VANTAGE:USDJPY");    // USD/JPY via Vantage[cite: 3]
        put("GBPUSD",  "VANTAGE:GBPUSD");    // GBP/USD via Vantage[cite: 3]
    }};

    // ── Structure de données unifiée avec les 4 indicateurs + Niveaux pivots ──
    public static class TVMarketData {
        public final String symbol;
        public final double price;
        public final double changePercent;      // 1. Tendance depuis la clôture de la veille[cite: 3]
        public final double high;
        public final double low;
        public final double open;
        public final double prevClose;
        public final double variance;           // 2. Volatilité intraday (sur 20 ticks)[cite: 3]
        public final double volatilityPercent;  // 3. Amplitude daily (High-Low) en %[cite: 3]
        public final double dailyRangePercent;  // 4. Position dans la fourchette du jour (0=Low, 100=High)[cite: 3]
        public final boolean isNearHigh;
        public final boolean isNearLow;
        
        public final double ma200;
        public final boolean aboveMA200;
        
        // Niveaux Daily (TradingView Nactif)[cite: 3]
        public final double pdh; 
        public final double pdl; 
        public final boolean brokeAbovePDH; 
        public final boolean brokeBelowPDL; 
        
        // Niveaux Weekly (TradingView Nactif)[cite: 3]
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
            
            // Calcul automatique des indicateurs 3 et 4[cite: 3]
            this.volatilityPercent = (high > 0 && low > 0 && high != low)
                    ? (high - low) / ((high + low) / 2) * 100 : 0.0;[cite: 3]
            double range = high - low;[cite: 3]
            this.dailyRangePercent = (range > 0) ? ((price - low) / range) * 100 : 50.0;[cite: 3]
            this.isNearHigh = this.dailyRangePercent >= 95.0;[cite: 3]
            this.isNearLow  = this.dailyRangePercent <= 5.0;[cite: 3]
            
            this.ma200         = ma200;[cite: 3]
            this.aboveMA200    = (ma200 > 0) && (price > ma200);[cite: 3]
            
            this.pdh           = pdh;[cite: 3]
            this.pdl           = pdl;[cite: 3]
            this.brokeAbovePDH = (pdh > 0) && (price > pdh);[cite: 3]
            this.brokeBelowPDL = (pdl > 0) && (price < pdl);[cite: 3]
            
            this.pwh           = pwh;[cite: 3]
            this.pwl           = pwl;[cite: 3]
            this.brokeAbovePWH = (pwh > 0) && (price > pwh);[cite: 3]
            this.brokeBelowPWL = (pwl > 0) && (price < pwl);[cite: 3]
            
            this.timestamp     = timestamp;[cite: 3]
        }
    }

    private static String twelveDataKey = "";[cite: 3]
    private static final String PREFS_WEEKLY = "TradingBotPrefs";[cite: 3]

    // ── Caches et gestionnaires mémoire vive ──
    private static final ConcurrentHashMap<String, TVMarketData> cache = new ConcurrentHashMap<>();[cite: 3]
    private static final ConcurrentHashMap<String, Double> pdhCache = new ConcurrentHashMap<>();[cite: 3]
    private static final ConcurrentHashMap<String, Double> pdlCache = new ConcurrentHashMap<>();[cite: 3]
    private static final ConcurrentHashMap<String, Double> pwhCache = new ConcurrentHashMap<>();[cite: 3]
    private static final ConcurrentHashMap<String, Double> pwlCache = new ConcurrentHashMap<>();[cite: 3]
    
    // Anti-spam alertes[cite: 3]
    private static final ConcurrentHashMap<String, Boolean> alertFiredPDH = new ConcurrentHashMap<>();[cite: 3]
    private static final ConcurrentHashMap<String, Boolean> alertFiredPDL = new ConcurrentHashMap<>();[cite: 3]
    private static final ConcurrentHashMap<String, Boolean> alertFiredPWH = new ConcurrentHashMap<>();[cite: 3]
    private static final ConcurrentHashMap<String, Boolean> alertFiredPWL = new ConcurrentHashMap<>();[cite: 3]
    
    private static final AtomicBoolean isRunning = new AtomicBoolean(false);[cite: 3]
    private static final ConcurrentHashMap<String, VarianceCalculator> varianceCalculators = new ConcurrentHashMap<>();[cite: 3]
    private static final ConcurrentHashMap<String, Long> lastAlertTime = new ConcurrentHashMap<>();[cite: 3]
    private static final long ALERT_COOLDOWN_MS = 5 * 60 * 1000; // 5 minutes[cite: 3]

    private static final AtomicBoolean isConnecting = new AtomicBoolean(false);[cite: 3]
    private static final AtomicBoolean connected = new AtomicBoolean(false);[cite: 3]
    private static Context appContext;[cite: 3]
    private static OkHttpClient client;[cite: 3]
    private static WebSocket webSocket;[cite: 3]

    public interface OnDataReadyListener {[cite: 3]
        void onDataReady(Map<String, TVMarketData> data);[cite: 3]
        void onError(String error);[cite: 3]
    }[cite: 3]

    public static void rolloverDailyLevels() {[cite: 3]
        alertFiredPDH.clear();[cite: 3]
        alertFiredPDL.clear();[cite: 3]
        alertFiredPWH.clear();[cite: 3]
        alertFiredPWL.clear();[cite: 3]
        logToUI("🔄 [Anti-Spam] Réinitialisation des déclencheurs d'alertes pivots pour la nouvelle session.");[cite: 3]
    }

    public static void fetchAll(OnDataReadyListener listener) {[cite: 3]
        if (listener == null) return;[cite: 3]
        if (cache.isEmpty()) {[cite: 3]
            listener.onError("Le cache temps réel est actuellement vide. Attendez que le WebSocket se connecte.");[cite: 3]
        } else {[cite: 3]
            listener.onDataReady(Collections.unmodifiableMap(cache));[cite: 3]
        }[cite: 3]
    }

    public static void start(Context context) {[cite: 3]
        if (isRunning.getAndSet(true)) {[cite: 3]
            logToUI("⏳ [TV] Déjà en cours.");[cite: 3]
            return;[cite: 3]
        }[cite: 3]
        appContext = context.getApplicationContext();[cite: 3]
        SharedPreferences prefs = appContext.getSharedPreferences("TradingBotPrefs", Context.MODE_PRIVATE);[cite: 3]
        twelveDataKey = prefs.getString("macro_api_key", "");[cite: 3]
        if (twelveDataKey.isEmpty()) twelveDataKey = prefs.getString("twelve_data_key", "");[cite: 3]
        if (twelveDataKey.isEmpty()) {[cite: 3]
            SharedPreferences prefs2 = appContext.getSharedPreferences("TradingBot", Context.MODE_PRIVATE);[cite: 3]
            twelveDataKey = prefs2.getString("macro_api_key", "");[cite: 3]
        }[cite: 3]
        
        client = new OkHttpClient.Builder()[cite: 3]
                .connectTimeout(15, TimeUnit.SECONDS)[cite: 3]
                .readTimeout(15, TimeUnit.SECONDS)[cite: 3]
                .build();[cite: 3]
        logToUI("📡 [TV] Démarrage du pipeline TradingView (WebSocket).");[cite: 3]
        connectWebSocket();
        
        if (MarketDataFetcher.tryAcquireBatchSlot()) {[cite: 3]
            fetchPreviousLevels();[cite: 3]
        } else {[cite: 3]
            new Thread(() -> {[cite: 3]
                try {[cite: 3]
                    Thread.sleep(65000L);[cite: 3]
                    fetchPreviousLevels();[cite: 3]
                } catch (InterruptedException ignored) {}[cite: 3]
            }).start();[cite: 3]
        }[cite: 3]
    }

    public static void stop() {[cite: 3]
        isRunning.set(false);[cite: 3]
        isConnecting.set(false);[cite: 3]
        connected.set(false);[cite: 3]
        if (webSocket != null) {[cite: 3]
            webSocket.close(1000, "Arrêt demandé");[cite: 3]
            webSocket = null;[cite: 3]
        }[cite: 3]
        client = null;[cite: 3]
        cache.clear();[cite: 3]
        varianceCalculators.clear();[cite: 3]
        lastAlertTime.clear();[cite: 3]
        logToUI("🛑 [TV] Fetcher arrêté.");[cite: 3]
    }

    private static synchronized void connectWebSocket() {
        if (!isRunning.get() || connected.get() || isConnecting.getAndSet(true)) {
            return;
        }

        Request request = new Request.Builder()
                .url("wss://data.tradingview.com/socket.io/websocket")
                .header("Origin", "https://www.tradingview.com")
                .build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            private String quoteSessionId;
            private String chartSessionId;

            @Override
            public void onOpen(WebSocket ws, Response response) {
                logToUI("✅ [TV WS] Canal ouvert.");
                connected.set(true);
                isConnecting.set(false);
                cache.clear();

                // 1. Session de prix Temps Réel (Quotes)
                quoteSessionId = "qs_" + UUID.randomUUID().toString().substring(0, 12);
                sendMessage(ws, "set_auth_token", new String[]{"unauthorized_user_token"});
                sendMessage(ws, "quote_create_session", new String[]{quoteSessionId});
                sendMessage(ws, "quote_set_fields", new String[]{
                        quoteSessionId,
                        "lp", "chp", "ch", "high_price", "low_price",
                        "open_price", "prev_close_price"
                });

                // 2. Session Historique Native (Charts) - Récupération sans TwelveData
                chartSessionId = "cs_" + UUID.randomUUID().toString().substring(0, 12);
                sendMessage(ws, "chart_create_session", new String[]{chartSessionId, ""});

                int idCounter = 1;
                for (String key : SYMBOL_MAP.keySet()) {
                    String ticker = SYMBOL_MAP.get(key);
                    varianceCalculators.putIfAbsent(key, new VarianceCalculator(5)); //[cite: 3]
                    
                    // Flux de cotations en temps réel
                    sendMessage(ws, "quote_add_symbols", new String[]{quoteSessionId, ticker});

                    // Configuration des flux de graphes pour obtenir les chandeliers fermés passés
                    String symId = "sym_" + idCounter;
                    String serIdD = "ser_d_" + key;
                    String serIdW = "ser_w_" + key;

                    sendMessage(ws, "resolve_symbol", new String[]{chartSessionId, symId, "={\"symbol\":\"" + ticker + "\",\"adjustment\":\"splits\"}"});
                    sendMessage(ws, "create_series", new String[]{chartSessionId, serIdD, "s1", symId, "D", "3"});
                    sendMessage(ws, "create_series", new String[]{chartSessionId, serIdW, "s1", symId, "W", "3"});

                    idCounter++;
                }
                logToUI("📥 [TV WS] Flux temps réel et sessions pivots TradingView initialisés.");
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
                    processJsonPayload(payload);
                    idx = endPayload;
                }
            }

            private void processJsonPayload(String payload) {
                try {
                    if (!payload.startsWith("{")) return;
                    JSONObject json = new JSONObject(payload);
                    String m = json.optString("m");

                    // ── EXTRACTION NATIVE ET EXCLUSIVE DU HIGH / LOW PRECEDENT (CHART ENDPOINT) ──
                    if ("timescale_update".equals(m)) {
                        JSONArray p = json.getJSONArray("p");
                        if (p.length() > 1) {
                            JSONObject seriesData = p.getJSONObject(1);
                            java.util.Iterator<String> keys = seriesData.keys();
                            
                            while (keys.hasNext()) {
                                String seriesId = keys.next(); 
                                JSONObject obj = seriesData.getJSONObject(seriesId);
                                
                                if (obj.has("s")) {
                                    JSONArray sArr = obj.getJSONArray("s");
                                    if (sArr.length() >= 2) {
                                        // Index length - 2 extrait l'avant-dernière bougie (complète et fermée)
                                        JSONObject prevBar = sArr.getJSONObject(sArr.length() - 2);
                                        if (prevBar.has("v")) {
                                            JSONArray vArr = prevBar.getJSONArray("v"); // [TS, Open, High, Low, Close]
                                            if (vArr.length() >= 4) {
                                                double historicalHigh = vArr.getDouble(2); // High brut de la mèche
                                                double historicalLow  = vArr.getDouble(3); // Low brut de la mèche
                                                
                                                if (seriesId.startsWith("ser_d_")) {
                                                    String key = seriesId.substring(6);
                                                    pdhCache.put(key, historicalHigh);
                                                    pdlCache.put(key, historicalLow);
                                                } else if (seriesId.startsWith("ser_w_")) {
                                                    String key = seriesId.substring(6);
                                                    pwhCache.put(key, historicalHigh);
                                                    pwlCache.put(key, historicalLow);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        return;
                    }

                    // ── PARSING FLUX TEMPS RÉEL (QUOTES) ──
                    if ("qsd".equals(m)) {
                        JSONArray p = json.getJSONArray("p");
                        if (p.length() > 1) {
                            JSONObject quote = p.getJSONObject(1);
                            String ticker = quote.optString("n");
                            JSONObject v = quote.optJSONObject("v");
                            
                            if (v != null && v.has("lp")) {
                                String key = getKeyFromTicker(ticker);
                                if (key != null) {
                                    double price = v.optDouble("lp", 0);[cite: 3]
                                    double change = v.optDouble("chp", 0);[cite: 3]
                                        
                                    TVMarketData existing = cache.get(key);[cite: 3]
                                    double high      = v.optDouble("high_price",       existing != null && existing.high > 0      ? existing.high      : price);[cite: 3]
                                    double low       = v.optDouble("low_price",        existing != null && existing.low  > 0      ? existing.low       : price);[cite: 3]
                                    double open      = v.optDouble("open_price",       existing != null && existing.open > 0      ? existing.open      : price);[cite: 3]
                                    double prevClose = v.optDouble("prev_close_price", existing != null && existing.prevClose > 0 ? existing.prevClose : price);[cite: 3]

                                    // Sécurité d'élargissement dynamique intraday
                                    if (price > high) high = price;
                                    if (price < low) low = price;

                                    VarianceCalculator calc = varianceCalculators.get(key);[cite: 3]
                                    double variance = 0.0;[cite: 3]
                                    if (calc != null) {[cite: 3]
                                        calc.addPrice(price);[cite: 3]
                                        variance = calc.getVariance();[cite: 3]
                                    }[cite: 3]

                                    double pdh = pdhCache.getOrDefault(key, 0.0);[cite: 3]
                                    double pdl = pdlCache.getOrDefault(key, 0.0);[cite: 3]
                                    double pwh = pwhCache.getOrDefault(key, 0.0);[cite: 3]
                                    double pwl = pwlCache.getOrDefault(key, 0.0);[cite: 3]

                                    TVMarketData newData = new TVMarketData(
                                            key, price, change, high, low, open, prevClose,
                                            variance, 0.0, pdh, pdl, pwh, pwl,
                                            System.currentTimeMillis()
                                    );[cite: 3]
                                    cache.put(key, newData);[cite: 3]

                                    checkAndAlert(key, newData);[cite: 3]
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[TV WS] Erreur parse JSON", e);
                }
            }

            private String getKeyFromTicker(String ticker) {
                for (Map.Entry<String, String> entry : SYMBOL_MAP.entrySet()) {
                    if (ticker.equals(entry.getValue())) {
                        return entry.getKey();
                    }
                }
                return null;
            }

            private void checkAndAlert(String key, TVMarketData data) {
                if (appContext == null) return;
                long now = System.currentTimeMillis();

                Long last = lastAlertTime.get(key);
                if (last == null || (now - last) > ALERT_COOLDOWN_MS) {
                    if (data.isNearHigh || data.isNearLow) {
                        StringBuilder sb = new StringBuilder();
                        
                        if (data.isNearHigh) {
                            sb.append("📊 *").append(key).append("* 🔺 Approche du *plus haut du jour*\n\n");
                        } else {
                            sb.append("📊 *").append(key).append("* 🔻 Approche du *plus bas du jour*\n\n");
                        }
                        
                        sb.append("🔹 *PRIX ACTUEL* : `").append(String.format(Locale.US, "%.4f", data.price)).append("`\n\n");
                        sb.append("📈 *LES 4 INDICATEURS TEMPS RÉEL :*\n");
                        sb.append("• 1. Variation : `").append(String.format(Locale.US, "%+.2f", data.changePercent)).append("%` (vs Clôture)\n");
                        sb.append("• 2. Volatilité Tick (20t) : `").append(String.format(Locale.US, "%.6f", data.variance)).append("` (Variance)\n");
                        sb.append("• 3. Amplitude Daily : `").append(String.format(Locale.US, "%.2f", data.volatilityPercent)).append("%` (High-Low)\n");
                        sb.append("• 4. Position Range : `").append(String.format(Locale.US, "%.1f", data.dailyRangePercent)).append("%` (0=Bas, 100=Haut)\n\n");
                        
                        sb.append("🏛️ *NIVEAUX PIVOTS (Natifs TradingView) :*\n");
                        if (data.pdh > 0 || data.pdl > 0) {
                            sb.append("• *Daily* : PDH = `").append(String.format(Locale.US, "%.4f", data.pdh))
                              .append("` | PDL = `").append(String.format(Locale.US, "%.4f", data.pdl)).append("`\n");
                        } else {
                            sb.append("• *Daily* : ⚠️ Chargement des séries TV en cours...\n");
                        }
                        
                        if (data.pwh > 0 || data.pwl > 0) {
                            sb.append("• *Weekly* : PWH = `").append(String.format(Locale.US, "%.4f", data.pwh))
                              .append("` | PWL = `").append(String.format(Locale.US, "%.4f", data.pwl)).append("`\n");
                        } else {
                            sb.append("• *Weekly* : ⚠️ Chargement des séries TV en cours...\n");
                        }

                        if (data.brokeAbovePDH || data.brokeBelowPDL || data.brokeAbovePWH || data.brokeBelowPWL) {
                            sb.append("\n⚡ *Statut de cassure :*");
                            if (data.brokeAbovePDH) sb.append(" 🔺[Breakout PDH]");
                            if (data.brokeBelowPDL) sb.append(" 🔻[Breakdown PDL]");
                            if (data.brokeAbovePWH) sb.append(" 🚀[Breakout PWH]");
                            if (data.brokeBelowPWL) sb.append(" 🔥[Breakdown PWL]");
                            sb.append("\n");
                        }

                        NotificationService.sendTelegramSecure(sb.toString(), appContext);
                        lastAlertTime.put(key, now);
                    }
                }

                if (data.brokeAbovePDH && !Boolean.TRUE.equals(alertFiredPDH.get(key))) {
                    alertFiredPDH.put(key, true);
                    NotificationService.sendTelegramSecure("🔺 *" + key + "* — Cassure du *Previous Day High* (`" + data.price + "`)", appContext);
                }
                if (data.brokeBelowPDL && !Boolean.TRUE.equals(alertFiredPDL.get(key))) {
                    alertFiredPDL.put(key, true);
                    NotificationService.sendTelegramSecure("🔻 *" + key + "* — Cassure du *Previous Day Low* (`" + data.price + "`)", appContext);
                }
                if (data.brokeAbovePWH && !Boolean.TRUE.equals(alertFiredPWH.get(key))) {
                    alertFiredPWH.put(key, true);
                    NotificationService.sendTelegramSecure("🚀 *" + key + "* — Breakout *Previous Week High* (`" + data.price + "`) !", appContext);
                }
                if (data.brokeBelowPWL && !Boolean.TRUE.equals(alertFiredPWL.get(key))) {
                    alertFiredPWL.put(key, true);
                    NotificationService.sendTelegramSecure("🔥 *" + key + "* — Breakdown *Previous Week Low* (`" + data.price + "`) !", appContext);
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
                logToUI("🔴 [TV WS] Déconnecté. Reconnexion dans 5s...");
                if (isRunning.get()) {
                    new Thread(() -> {
                        try { TimeUnit.SECONDS.sleep(5); } catch (InterruptedException ignored) {}
                        connectWebSocket();
                    }).start();
                }
            }

            private void sendMessage(WebSocket ws, String method, String[] params) {
                try {
                    JSONArray arr = new JSONArray();
                    for (String p : params) arr.put(p);
                    JSONObject msg = new JSONObject();
                    msg.put("m", method);
                    msg.put("p", arr);
                    String payload = msg.toString();
                    ws.send("~m~" + payload.length() + "~m~" + payload);
                } catch (Exception e) {
                    Log.e(TAG, "[TV WS] Erreur envoi message", e);
                }
            }
        });
    }

    public static void fetchPreviousLevels() {
        loadLevelsFromStorage();[cite: 3]
        logToUI("🏛️ [TV Pivots] Niveaux du stockage local injectés. Relais automatique pris par le flux graphique temps réel.");
    }

    private static void loadLevelsFromStorage() {
        if (appContext == null) return;[cite: 3]
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_WEEKLY, Context.MODE_PRIVATE);[cite: 3]
        for (String key : SYMBOL_MAP.keySet()) {[cite: 3]
            double savedPdh = Double.parseDouble(prefs.getString("pdh_" + key, "0"));[cite: 3]
            double savedPdl = Double.parseDouble(prefs.getString("pdl_" + key, "0"));[cite: 3]
            double savedPwh = Double.parseDouble(prefs.getString("pwh_" + key, "0"));[cite: 3]
            double savedPwl = Double.parseDouble(prefs.getString("pwl_" + key, "0"));[cite: 3]

            if (savedPdh > 0) pdhCache.put(key, savedPdh);[cite: 3]
            if (savedPdl > 0) pdlCache.put(key, savedPdl);[cite: 3]
            if (savedPwh > 0) pwhCache.put(key, savedPwh);[cite: 3]
            if (savedPwl > 0) pwlCache.put(key, savedPwl);[cite: 3]
        }[cite: 3]
    }

    private static class VarianceCalculator {
        private final int period; private final double[] window;
        private int index = 0; private int count = 0; private double sum = 0; private double sumSq = 0;
        private boolean initialized = false;
        public VarianceCalculator(int period) { this.period = period; this.window = new double[period]; } //[cite: 3]
        public synchronized void addPrice(double price) {
            if (count < period) {
                window[count] = price; sum += price; sumSq += price * price; count++;
                if (count == period) initialized = true;
            } else {
                double old = window[index]; sum -= old; sumSq -= old * old;
                window[index] = price; sum += price; sumSq += price * price;
                index = (index + 1) % period;
            }
        }
        public synchronized double getVariance() {
            if (count < 2) return 0; //[cite: 3]
            int n = Math.min(count, period); //[cite: 3]
            double mean = sum / n; return (sumSq / n) - (mean * mean); //[cite: 3]
        }
    }

    public static Map<String, TVMarketData> getCache() { return Collections.unmodifiableMap(cache); } //[cite: 3]

    public static String buildContexteMacroGlobal(Context ctx) {
        if (cache.isEmpty()) return "";[cite: 3]
        StringBuilder sb = new StringBuilder();[cite: 3]
        sb.append("═══ CONTEXTE MACRO GLOBAL TEMPS RÉEL ═══\n");[cite: 3]

        for (String key : SYMBOL_MAP.keySet()) {[cite: 3]
            TVMarketData d = cache.get(key);[cite: 3]
            if (d != null) {[cite: 3]
                String formatPrice;[cite: 3]
                if (key.equals("GBPUSD")) { formatPrice = "%.5f"; } //[cite: 3]
                else if (key.equals("USDJPY")) { formatPrice = "%.3f"; } //[cite: 3]
                else if (key.equals("NASDAQ") || key.equals("SP500")) { formatPrice = "%.2f"; } //[cite: 3]
                else { formatPrice = "%.4f"; } //[cite: 3]

                sb.append("• ").append(key).append(" : ")[cite: 3]
                  .append(String.format(Locale.US, formatPrice, d.price))[cite: 3]
                  .append(" (").append(String.format(Locale.US, "%+.2f", d.changePercent)).append("%)")[cite: 3]
                  .append(" | Amp: ").append(String.format(Locale.US, "%.2f", d.volatilityPercent)).append("%")[cite: 3]
                  .append(" | Range: ").append(String.format(Locale.US, "%.0f", d.dailyRangePercent)).append("%")[cite: 3]
                  .append(d.isNearHigh ? " 🔺PrèsHaut" : d.isNearLow ? " 🔻PrèsBas" : "")[cite: 3]
                  .append(" | Var: ").append(String.format(Locale.US, "%.6f", d.variance)) //[cite: 3]
                  .append(d.pdh > 0 ? " | PDH=" + String.format(Locale.US, formatPrice, d.pdh) : "") //[cite: 3]
                  .append(d.pdl > 0 ? " | PDL=" + String.format(Locale.US, formatPrice, d.pdl) : "") //[cite: 3]
                  .append(d.brokeAbovePDH ? " 🔺Breakout PDH" : d.brokeBelowPDL ? " 🔻Breakdown PDL" : "") //[cite: 3]
                  .append(d.pwh > 0 ? " | PWH=" + String.format(Locale.US, formatPrice, d.pwh) : "") //[cite: 3]
                  .append(d.pwl > 0 ? " | PWL=" + String.format(Locale.US, formatPrice, d.pwl) : "") //[cite: 3]
                  .append(d.brokeAbovePWH ? " 🚀Breakout PWH" : d.brokeBelowPWL ? " 🔥Breakdown PWL" : "") //[cite: 3]
                  .append("\n");[cite: 3]
            }
        }
        String regimeFed = ctx.getSharedPreferences("TradingBotPrefs", Context.MODE_PRIVATE)[cite: 3]
                .getString("fed_regime", "PAUSE HAWKISH | Warsh | Taux 3.50-3.75% | CPI 4.2%"); //[cite: 3]
        sb.append("\n🏦 RÉGIME FED : ").append(regimeFed).append("\n"); //[cite: 3]
        return sb.toString(); //[cite: 3]
    }

    private static void logToUI(String msg) {
        if (MainActivity.instance != null) { MainActivity.instance.addLog(msg); } //[cite: 3]
    }
}
