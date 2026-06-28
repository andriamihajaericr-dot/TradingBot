package com.tradingbot.analyzer;

import android.content.Context;
import android.util.Log;

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

    // ── Tickers TradingView (mode anonyme) ──
    private static final Map<String, String> SYMBOL_MAP = new HashMap<String, String>() {{
        put("DXY",     "TVC:DXY");
        put("VIX",     "CBOE:VIX");
        put("US10Y",   "TVC:US10Y");
        put("US500",   "OANDA:SPX500USD");
        put("NASDAQ",  "QQQ");
        put("GOLD",    "OANDA:XAUUSD");
        put("USOIL",   "TVC:USOIL");
        put("EURUSD",  "FX:EURUSD");
        put("USDJPY",  "FX:USDJPY");
        put("GBPUSD",  "FX:GBPUSD");
        put("AUDUSD",  "FX:AUDUSD");
        put("USDCAD",  "FX:USDCAD");
        put("BITCOIN", "BINANCE:BTCUSDT");
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
        public final long timestamp;

        public TVMarketData(String symbol, double price, double changePercent,
                            double high, double low, double open, double prevClose,
                            double variance, long timestamp) {
            this.symbol        = symbol;
            this.price         = price;
            this.changePercent = changePercent;
            this.high          = high;
            this.low           = low;
            this.open          = open;
            this.prevClose     = prevClose;
            this.variance      = variance;
            this.timestamp     = timestamp;
        }
    }

    private static final ConcurrentHashMap<String, TVMarketData> cache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, VarianceCalculator> varianceCalculators = new ConcurrentHashMap<>();
    private static final AtomicBoolean isRunning = new AtomicBoolean(false);
    private static final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private static final AtomicBoolean connected = new AtomicBoolean(false);
    private static Context appContext;
    private static OkHttpClient client;
    private static WebSocket webSocket;

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
        cache.clear();
        varianceCalculators.clear();
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
            private String quoteSessionId;

            @Override
            public void onOpen(WebSocket ws, Response response) {
                logToUI("✅ [TV WS] Canal ouvert.");
                Log.d(TAG, "[TV WS] Ouvert.");
                connected.set(true);
                isConnecting.set(false);
                cache.clear();

                quoteSessionId = "qs_" + UUID.randomUUID().toString().substring(0, 12);

                sendMessage(ws, "set_auth_token", new String[]{"unauthorized_user_token"});
                sendMessage(ws, "quote_create_session", new String[]{quoteSessionId});
                sendMessage(ws, "quote_set_fields", new String[]{
                        quoteSessionId,
                        "lp", "chp", "ch", "high_price", "low_price",
                        "open_price", "prev_close_price"
                });

                // Abonnement à tous les symboles
                for (String key : SYMBOL_MAP.keySet()) {
                    String ticker = SYMBOL_MAP.get(key);
                    varianceCalculators.putIfAbsent(key, new VarianceCalculator(20));
                    sendMessage(ws, "quote_add_symbols", new String[]{quoteSessionId, ticker});
                    logToUI("📥 [TV] Abonnement demandé pour " + key + " (" + ticker + ")");
                }
                logToUI("📥 [TV WS] " + SYMBOL_MAP.size() + " symboles abonnés.");
                Log.i(TAG, "[TV WS] " + SYMBOL_MAP.size() + " symboles abonnés.");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                // Log des messages bruts pour diagnostiquer
                String preview = text.length() > 50 ? text.substring(0, 50) + "..." : text;
                Log.d(TAG, "[TV WS] Message brut: " + preview);
                logToUI("📨 [TV WS] Reçu: " + preview);

                if (text == null) return;
                // Heartbeat
                if (text.contains("~h~")) {
                    ws.send(text);
                    return;
                }

                // Parser les messages au format ~m~length~m~payload
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

                    if ("qsd".equals(m)) {
                        JSONArray p = json.getJSONArray("p");
                        if (p.length() > 1) {
                            JSONObject quote = p.getJSONObject(1);
                            String ticker = quote.optString("n");
                            JSONObject v = quote.optJSONObject("v");
                            if (v != null && v.has("lp")) {
                                double price = v.optDouble("lp", 0);
                                double change = v.optDouble("chp", 0);
                                double high = v.optDouble("high_price", price);
                                double low = v.optDouble("low_price", price);
                                double open = v.optDouble("open_price", price);
                                double prevClose = v.optDouble("prev_close_price", price);

                                String key = getKeyFromTicker(ticker);
                                if (key != null) {
                                    VarianceCalculator calc = varianceCalculators.get(key);
                                    if (calc != null) {
                                        calc.addPrice(price);
                                        double variance = calc.getVariance();
                                        updateCache(key, price, change, high, low, open, prevClose, variance);
                                        logToUI("📊 [TV] " + key + " prix=" + price);
                                    }
                                } else {
                                    logToUI("⚠️ [TV] Ticker non reconnu: " + ticker);
                                }
                            }
                        }
                    } else {
                        // Autres types de messages (protocol, etc.)
                        Log.d(TAG, "[TV WS] Autre message m=" + m + " : " + payload);
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

            private void updateCache(String key, double price, double change,
                                     double high, double low, double open,
                                     double prevClose, double variance) {
                cache.put(key, new TVMarketData(key, price, change,
                        high, low, open, prevClose,
                        variance, System.currentTimeMillis()));
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
    // CALCULATEUR DE VARIANCE
    // ─────────────────────────────────────────────────────────────────────────

    private static class VarianceCalculator {
        private final int period;
        private final double[] window;
        private int index = 0;
        private int count = 0;
        private double sum = 0;
        private double sumSq = 0;
        private boolean initialized = false;

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
                if (count == period) initialized = true;
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
            if (!initialized) return 0;
            double mean = sum / period;
            return (sumSq / period) - (mean * mean);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ACCÈS PUBLIC
    // ─────────────────────────────────────────────────────────────────────────

    public static Map<String, TVMarketData> getCache() {
        return Collections.unmodifiableMap(cache);
    }

    public static TVMarketData get(String symbol) {
        return cache.get(symbol);
    }

    public static void fetchAll(OnDataReadyListener listener) {
        new Thread(() -> {
            long start = System.currentTimeMillis();
            while (cache.isEmpty() && (System.currentTimeMillis() - start < 15000)) {
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
    // CONTEXTE MACRO
    // ─────────────────────────────────────────────────────────────────────────

    public static String buildContexteMacroGlobal(Context ctx) {
        if (cache.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("═══ CONTEXTE MACRO GLOBAL TEMPS RÉEL ═══\n");

        for (String key : SYMBOL_MAP.keySet()) {
            TVMarketData d = cache.get(key);
            if (d != null) {
                sb.append(key).append(" : ")
                        .append(String.format(Locale.US, "%.4f", d.price))
                        .append(" (").append(String.format(Locale.US, "%+.2f", d.changePercent)).append("%)")
                        .append(" | H: ").append(String.format(Locale.US, "%.4f", d.high))
                        .append(" | L: ").append(String.format(Locale.US, "%.4f", d.low))
                        .append(" | Var: ").append(String.format(Locale.US, "%.4f", d.variance))
                        .append("\n");
            }
        }

        String regimeFed = ctx.getSharedPreferences("TradingBotPrefs", Context.MODE_PRIVATE)
                .getString("fed_regime", "PAUSE HAWKISH | Warsh | Taux 3.50-3.75% | CPI 4.2%");
        sb.append("🏦 RÉGIME FED : ").append(regimeFed).append("\n");
        sb.append("─────────────────────────────\n");
        sb.append("INDICATEURS : Variance = volatilité intraday (écart-type sur 20 ticks).\n");
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
}
