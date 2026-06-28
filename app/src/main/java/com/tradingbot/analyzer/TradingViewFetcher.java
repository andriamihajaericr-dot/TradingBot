package com.tradingbot.analyzer;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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

    // ── Matrice complète (13 actifs) – tickers TradingView ──
    private static final Map<String, String> SYMBOL_MAP = new HashMap<String, String>() {{
        put("DXY",     "TVC:DXY");
        put("VIX",     "CBOE:VIX");
        put("US10Y",   "TVC:US10Y");
        put("US500",   "OANDA:SPX500USD");
        put("NASDAQ",  "NASDAQ:QQQ");
        put("GOLD",    "OANDA:XAUUSD");
        put("USOIL",   "TVC:USOIL");
        put("EURUSD",  "FX_IDC:EURUSD");
        put("USDJPY",  "FX_IDC:USDJPY");
        put("GBPUSD",  "FX_IDC:GBPUSD");
        put("AUDUSD",  "FX_IDC:AUDUSD");
        put("USDCAD",  "FX_IDC:USDCAD");
        put("BITCOIN", "BINANCE:BTCUSDT");
    }};

    // ── Fallback TwelveData (pour SMA200) ──
    private static final Map<String, String> TWELVE_SYMBOL_MAP = new HashMap<String, String>() {{
        put("US500",   "SPY");
        put("NASDAQ",  "QQQ");
        put("GOLD",    "XAU/USD");
        put("USOIL",   "WTI");
        put("EURUSD",  "EUR/USD");
        put("USDJPY",  "USD/JPY");
        put("GBPUSD",  "GBP/USD");
        put("AUDUSD",  "AUD/USD");
        put("USDCAD",  "USD/CAD");
        put("BITCOIN", "BTC/USD");
        // DXY, VIX, US10Y ne sont pas disponibles en SMA sur TwelveData
    }};

    public static class TVMarketData {
        public final String symbol;
        public final double price;
        public final double changePercent;
        public final double ma200;
        public final boolean aboveMA200;
        public final long timestamp;

        public TVMarketData(String symbol, double price, double changePercent,
                            double ma200, long timestamp) {
            this.symbol        = symbol;
            this.price         = price;
            this.changePercent = changePercent;
            this.ma200         = ma200;
            this.aboveMA200   = (ma200 > 0) && (price > ma200);
            this.timestamp    = timestamp;
        }
    }

    private static final ConcurrentHashMap<String, TVMarketData> cache = new ConcurrentHashMap<>();
    private static final AtomicBoolean isRunning = new AtomicBoolean(false);
    private static final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private static final AtomicBoolean connected = new AtomicBoolean(false);
    private static final ConcurrentHashMap<String, String> seriesIdToKey = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, MovingAverage200Calculator> calculators = new ConcurrentHashMap<>();
    private static int seriesCounter = 0;
    private static Context appContext;
    private static OkHttpClient client;
    private static WebSocket webSocket;
    private static String twelveDataKey = "";

    public interface OnDataReadyListener {
        void onDataReady(Map<String, TVMarketData> data);
        void onError(String error);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CYCLE DE VIE
    // ─────────────────────────────────────────────────────────────────────────

    public static void start(Context context) {
        if (isRunning.getAndSet(true)) {
            logToUI("⏳ [TV] Déjà en cours.");
            return;
        }
        appContext = context.getApplicationContext();
        SharedPreferences prefs = appContext.getSharedPreferences("TradingBot", Context.MODE_PRIVATE);
        twelveDataKey = prefs.getString("macro_api_key", "");
        if (twelveDataKey.isEmpty()) {
            logToUI("⚠️ [TV] Clé TwelveData absente – fallback SMA désactivé.");
        }

        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        logToUI("📡 [TV] Démarrage du pipeline TradingView (WebSocket).");
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
        seriesIdToKey.clear();
        seriesCounter = 0;
        cache.clear();
        logToUI("🛑 [TV] Fetcher arrêté.");
        Log.i(TAG, "[TV] Arrêté.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONNEXION WEBSOCKET
    // ─────────────────────────────────────────────────────────────────────────

    private static synchronized void connectWebSocket() {
        if (!isRunning.get() || connected.get() || isConnecting.getAndSet(true)) {
            return;
        }

        Request request = new Request.Builder()
                .url("wss://data.tradingview.com/socket.io/websocket")
                .header("Origin", "https://www.tradingview.com")
                .build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            private String chartSessionId;
            private String quoteSessionId;

            @Override
            public void onOpen(WebSocket ws, Response response) {
                logToUI("✅ [TV WS] Canal ouvert.");
                Log.d(TAG, "[TV WS] Ouvert.");
                connected.set(true);
                isConnecting.set(false);
                seriesCounter = 0;
                seriesIdToKey.clear();
                cache.clear();

                chartSessionId = "cs_" + UUID.randomUUID().toString().substring(0, 12);
                quoteSessionId = "qs_" + UUID.randomUUID().toString().substring(0, 12);

                sendMessage(ws, "set_auth_token", new String[]{"unauthorized_user_token"});
                sendMessage(ws, "chart_create_session", new String[]{chartSessionId, ""});
                sendMessage(ws, "quote_create_session", new String[]{quoteSessionId});
                sendMessage(ws, "quote_set_fields", new String[]{quoteSessionId, "lp", "chp", "ch"});

                for (String key : SYMBOL_MAP.keySet()) {
                    String ticker = SYMBOL_MAP.get(key);
                    seriesCounter++;
                    String seriesId = "s" + seriesCounter;
                    seriesIdToKey.put(seriesId, key);
                    calculators.putIfAbsent(key, new MovingAverage200Calculator());

                    String resolvePayload = "{\"symbol\":\"" + ticker + "\",\"adjustment\":\"splits\"}";
                    sendMessage(ws, "resolve_symbol", new String[]{chartSessionId, "sym_" + seriesId, resolvePayload});
                    try { Thread.sleep(150); } catch (InterruptedException ignored) {}
                    sendMessage(ws, "create_series", new String[]{chartSessionId, seriesId, "s1", "sym_" + seriesId, "1D", "300"});

                    // Abonnement aux quotes avec flags force_permission
                    JSONObject flags = new JSONObject();
                    flags.put("flags", new JSONArray().put("force_permission"));
                    sendMessage(ws, "quote_add_symbols", new String[]{quoteSessionId, ticker, flags.toString()});
                }
                logToUI("📥 [TV WS] " + SYMBOL_MAP.size() + " symboles demandés.");
                Log.i(TAG, "[TV WS] " + SYMBOL_MAP.size() + " symboles demandés.");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                if (text == null) return;
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
                    if (payload.startsWith("~h~")) {
                        ws.send(text);
                    } else {
                        processJsonPayload(payload);
                    }
                    idx = endPayload;
                }
            }

            private void processJsonPayload(String payload) {
                try {
                    if (!payload.startsWith("{")) return;
                    JSONObject json = new JSONObject(payload);
                    String m = json.optString("m");

                    if ("timescale_update".equals(m)) {
                        JSONArray p = json.getJSONArray("p");
                        if (p.length() < 2) return;
                        JSONObject dataObj = p.getJSONObject(1);
                        for (Map.Entry<String, String> entry : seriesIdToKey.entrySet()) {
                            String sid = entry.getKey();
                            if (dataObj.has(sid)) {
                                JSONObject series = dataObj.getJSONObject(sid);
                                String key = entry.getValue();
                                MovingAverage200Calculator calc = calculators.get(key);
                                if (calc == null) continue;
                                if (series.has("s")) {
                                    JSONArray candles = series.getJSONArray("s");
                                    List<Double> closes = new ArrayList<>();
                                    for (int i = 0; i < candles.length(); i++) {
                                        JSONObject candle = candles.getJSONObject(i);
                                        JSONArray v = candle.getJSONArray("v");
                                        double close = v.optDouble(4, 0);
                                        if (close > 0) closes.add(close);
                                    }
                                    if (closes.size() >= 200) {
                                        calc.initializeWithHistory(closes);
                                        double ma = calc.getCurrentMA();
                                        double lastPrice = closes.get(closes.size() - 1);
                                        TVMarketData existing = cache.get(key);
                                        double change = (existing != null) ? existing.changePercent : 0.0;
                                        updateCache(key, lastPrice, change, ma);
                                        logToUI("📊 [TV MA200] " + key + " MA200 = " + String.format(Locale.US, "%.2f", ma));
                                    } else {
                                        logToUI("⚠️ [TV MA200] " + key + " bougies insuffisantes (" + closes.size() + "), fallback SMA.");
                                        double maFallback = fetchMA200FromTwelveData(key);
                                        if (maFallback > 0) {
                                            double lastPrice = closes.isEmpty() ? 0 : closes.get(closes.size() - 1);
                                            if (lastPrice > 0) {
                                                TVMarketData existing = cache.get(key);
                                                double change = (existing != null) ? existing.changePercent : 0.0;
                                                updateCache(key, lastPrice, change, maFallback);
                                                logToUI("✅ [TV MA200 Fallback] " + key + " SMA = " + String.format(Locale.US, "%.2f", maFallback));
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else if ("qsd".equals(m)) {
                        JSONArray p = json.getJSONArray("p");
                        if (p.length() > 1) {
                            JSONObject quote = p.getJSONObject(1);
                            String ticker = quote.optString("n");
                            JSONObject v = quote.optJSONObject("v");
                            if (v != null && v.has("lp")) {
                                double price = v.optDouble("lp", 0);
                                double change = v.optDouble("chp", 0);
                                // Trouver la clé correspondante
                                for (Map.Entry<String, String> entry : SYMBOL_MAP.entrySet()) {
                                    if (ticker.equals(entry.getValue()) || ticker.endsWith(entry.getValue())) {
                                        String key = entry.getKey();
                                        TVMarketData existing = cache.get(key);
                                        double ma = (existing != null) ? existing.ma200 : 0.0;
                                        updateCache(key, price, change, ma);
                                        break;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[TV WS] Erreur parse JSON", e);
                }
            }

            // Méthode unique pour mettre à jour le cache
            private void updateCache(String key, double price, double change, double ma) {
                cache.put(key, new TVMarketData(key, price, change, ma, System.currentTimeMillis()));
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                handleDisconnection();
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                handleDisconnection();
            }

            private void handleDisconnection() {
                connected.set(false);
                isConnecting.set(false);
                cache.clear();
                logToUI("🔴 [TV WS] Déconnecté. Reconnexion dans 5s...");
                if (isRunning.get()) {
                    new Thread(() -> {
                        try { TimeUnit.SECONDS.sleep(5); } catch (InterruptedException ignored) {}
                        logToUI("🔄 [TV WS] Tentative de reconnexion...");
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

    // ─────────────────────────────────────────────────────────────────────────
    // FALLBACK SMA200 VIA TWELVE DATA
    // ─────────────────────────────────────────────────────────────────────────

    private static double fetchMA200FromTwelveData(String key) {
        if (twelveDataKey.isEmpty()) return 0;
        String twelveSymbol = TWELVE_SYMBOL_MAP.get(key);
        if (twelveSymbol == null) return 0;
        try {
            String urlStr = "https://api.twelvedata.com/sma?symbol=" + twelveSymbol
                    + "&interval=1day&time_period=200&apikey=" + twelveDataKey;
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();
                JSONObject json = new JSONObject(sb.toString());
                JSONArray values = json.optJSONArray("values");
                if (values != null && values.length() > 0) {
                    double sma = values.getJSONObject(0).optDouble("sma", 0);
                    if (sma > 0) return sma;
                }
            }
            conn.disconnect();
        } catch (Exception e) {
            Log.e(TAG, "[TV MA200 Fallback] Erreur pour " + key + " : " + e.getMessage());
        }
        return 0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ACCÈS PUBLIC
    // ─────────────────────────────────────────────────────────────────────────

    public static Map<String, TVMarketData> getCache() {
        return Collections.unmodifiableMap(cache);
    }

    public static boolean isCacheReady() {
        return cache.size() >= SYMBOL_MAP.size();
    }

    public static TVMarketData get(String symbol) {
        return cache.get(symbol);
    }

    public static void fetchAll(OnDataReadyListener listener) {
        new Thread(() -> {
            long start = System.currentTimeMillis();
            while (cache.size() < SYMBOL_MAP.size() && (System.currentTimeMillis() - start < 15000)) {
                try { TimeUnit.MILLISECONDS.sleep(500); } catch (InterruptedException ignored) {}
            }
            if (listener != null) {
                if (!cache.isEmpty()) {
                    listener.onDataReady(cache);
                } else {
                    listener.onError("Aucune donnée reçue après 15s.");
                }
            }
        }).start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONTEXTE MACRO (inchangé)
    // ─────────────────────────────────────────────────────────────────────────

    public static String buildContexteMacroGlobal(Context ctx) {
        if (cache.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("═══ CONTEXTE MACRO GLOBAL TEMPS RÉEL ═══\n");

        TVMarketData dxy = cache.get("DXY");
        if (dxy != null) {
            sb.append("💵 DXY : ").append(String.format(Locale.US, "%.2f", dxy.price))
                    .append(" (").append(String.format(Locale.US, "%+.2f", dxy.changePercent)).append("%)")
                    .append(" | MA200=").append(String.format(Locale.US, "%.2f", dxy.ma200))
                    .append(dxy.aboveMA200 ? " [HAUSSIER]" : " [BAISSIER]").append("\n");
        }

        TVMarketData vix = cache.get("VIX");
        if (vix != null) {
            String regime = vix.price > 30 ? "🔴 PANIQUE" : vix.price > 20 ? "🟠 STRESS" : "🟢 CALME";
            sb.append("📊 VIX : ").append(String.format(Locale.US, "%.1f", vix.price)).append(" ").append(regime).append("\n");
        }

        TVMarketData us10y = cache.get("US10Y");
        if (us10y != null) {
            sb.append("📈 US10Y : ").append(String.format(Locale.US, "%.3f", us10y.price)).append("%")
                    .append(" | MA200=").append(String.format(Locale.US, "%.3f", us10y.ma200)).append("%")
                    .append(us10y.aboveMA200 ? " [HAUSSE]" : " [BAISSE]").append("\n");
        }

        TVMarketData us500 = cache.get("US500");
        if (us500 != null) {
            sb.append("📊 SP500 : ").append(String.format(Locale.US, "%.1f", us500.price))
                    .append(String.format(Locale.US, " (%+.2f%%)", us500.changePercent))
                    .append(us500.aboveMA200 ? " [BULL]" : " [BEAR]").append("\n");
        }

        TVMarketData nasdaq = cache.get("NASDAQ");
        if (nasdaq != null) {
            sb.append("💻 NASDAQ : ").append(String.format(Locale.US, "%.2f", nasdaq.price))
                    .append(String.format(Locale.US, " (%+.2f%%)", nasdaq.changePercent))
                    .append(nasdaq.aboveMA200 ? " [HAUSSIER]" : " [BAISSIER]").append("\n");
        }

        TVMarketData gold = cache.get("GOLD");
        if (gold != null) {
            sb.append("🏆 GOLD : ").append(String.format(Locale.US, "%.2f", gold.price))
                    .append(String.format(Locale.US, " (%+.2f%%)", gold.changePercent))
                    .append(" | MA200=").append(String.format(Locale.US, "%.2f", gold.ma200))
                    .append(gold.aboveMA200 ? " [AU-DESSUS]" : " [EN-DESSOUS]").append("\n");
        }

        TVMarketData usoil = cache.get("USOIL");
        if (usoil != null) {
            sb.append("🛢️ USOIL : ").append(String.format(Locale.US, "%.2f", usoil.price))
                    .append(String.format(Locale.US, " (%+.2f%%)", usoil.changePercent))
                    .append(usoil.aboveMA200 ? " [HAUSSIER]" : " [BAISSIER]").append("\n");
        }

        for (String pair : new String[]{"EURUSD", "USDJPY", "GBPUSD", "AUDUSD", "USDCAD"}) {
            TVMarketData p = cache.get(pair);
            if (p != null) {
                sb.append(pair).append(" : ").append(String.format(Locale.US, "%.4f", p.price))
                        .append(String.format(Locale.US, " (%+.2f%%)", p.changePercent)).append("\n");
            }
        }

        TVMarketData btc = cache.get("BITCOIN");
        if (btc != null) {
            sb.append("₿ BITCOIN : ").append(String.format(Locale.US, "%.0f", btc.price))
                    .append(String.format(Locale.US, " (%+.2f%%)", btc.changePercent)).append("\n");
        }

        String regimeFed = ctx.getSharedPreferences("TradingBotPrefs", Context.MODE_PRIVATE)
                .getString("fed_regime", "PAUSE HAWKISH | Warsh | Taux 3.50-3.75% | CPI 4.2%");
        sb.append("🏦 RÉGIME FED : ").append(regimeFed).append("\n");
        sb.append("─────────────────────────────\n");
        sb.append("RÈGLE MA200 : Si actif sous MA200, réduire conviction de 10-15%.\n");
        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UTILITAIRE LOG UI
    // ─────────────────────────────────────────────────────────────────────────

    private static void logToUI(String msg) {
        if (MainActivity.instance != null) {
            MainActivity.instance.addLog(msg);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CALCULATEUR MA200
    // ─────────────────────────────────────────────────────────────────────────

    private static class MovingAverage200Calculator {
        private static final int PERIOD = 200;
        private final double[] window = new double[PERIOD];
        private double currentSum = 0.0;
        private boolean initialized = false;

        public synchronized void initializeWithHistory(List<Double> historicalCloses) {
            if (historicalCloses == null || historicalCloses.size() < PERIOD) return;
            currentSum = 0.0;
            for (int i = 0; i < PERIOD; i++) {
                window[i] = historicalCloses.get(historicalCloses.size() - PERIOD + i);
                currentSum += window[i];
            }
            initialized = true;
        }

        public synchronized double getCurrentMA() {
            return initialized ? currentSum / PERIOD : 0.0;
        }
    }
}
