package com.tradingbot.analyzer;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

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

/**
 * TradingViewFetcher — Récupère l'intégralité de la matrice macro et des actifs
 * via le protocole WebSocket de TradingView.
 */
public class TradingViewFetcher {

    private static final String TAG = "TradingViewFetcher";

    // ── Matrice complète des 11 actifs + 2 Indicateurs Ancres ──
    private static final Map<String, String> SYMBOL_MAP = new HashMap<String, String>() {{
        // Ancres Macro
        put("DXY",     "TVC:DXY");
        put("VIX",     "CBOE:VIX");
        // Taux & Indices
        put("US10Y",   "TVC:US10Y");
        put("US500",   "OANDA:SPX500USD");
        put("NASDAQ",  "NASDAQ:QQQ");
        // Matières Premières
        put("GOLD",    "OANDA:XAUUSD");
        put("USOIL",   "TVC:USOIL");
        // Forex & Crypto
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
    
    private static Context appContext;
    private static OkHttpClient client;
    private static WebSocket webSocket;

    private static final ConcurrentHashMap<String, String> seriesIdToKey = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, MovingAverage200Calculator> calculators = new ConcurrentHashMap<>();
    private static int seriesCounter = 0;

    public interface OnDataReadyListener {
        void onDataReady(Map<String, TVMarketData> data);
        void onError(String error);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GESTION DU CYCLE DE VIE
    // ─────────────────────────────────────────────────────────────────────────

    public static void start(Context context) {
        if (isRunning.getAndSet(true)) {
            Log.d(TAG, "[TV] Déjà en cours d'exécution.");
            return;
        }
        appContext = context.getApplicationContext();
        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        Log.i(TAG, "[TV] Initialisation du pipeline TradingView.");
        connectWebSocket();
    }

    public static void stop() {
        isRunning.set(false);
        isConnecting.set(false);
        connected.set(false);
        if (webSocket != null) {
            try {
                webSocket.close(1000, "Arrêt normal demandé");
            } catch (Exception ignored) {}
            webSocket = null;
        }
        client = null;
        seriesIdToKey.clear();
        seriesCounter = 0;
        Log.i(TAG, "[TV] Pipeline arrêté proprement.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONNEXION ET PROTOCOLE DE COMMANDE
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
                Log.d(TAG, "[TV WS] Canal réseau ouvert.");
                connected.set(true);
                isConnecting.set(false);

                seriesCounter = 0;
                seriesIdToKey.clear();

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
                    sendMessage(ws, "create_series", new String[]{chartSessionId, seriesId, "s1", "sym_" + seriesId, "1D", "300"});

                    sendMessage(ws, "quote_add_symbols", new String[]{quoteSessionId, ticker});
                }
                Log.i(TAG, "[TV WS] Enregistrement de la matrice terminé (" + SYMBOL_MAP.size() + " flux).");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                if (text == null || text.isEmpty()) return;

                int index = 0;
                while (index < text.length()) {
                    int firstM = text.indexOf("~m~", index);
                    if (firstM == -1) break;

                    int secondM = text.indexOf("~m~", firstM + 3);
                    if (secondM == -1) break;

                    String lenStr = text.substring(firstM + 3, secondM);
                    int length;
                    try {
                        length = Integer.parseInt(lenStr);
                    } catch (NumberFormatException e) {
                        break;
                    }

                    int startPayload = secondM + 3;
                    int endPayload = startPayload + length;
                    if (length <= 0 || endPayload > text.length()) {
                        break;
                    }

                    String payload = text.substring(startPayload, endPayload);
                    
                    // Sécurisation stricte du traitement du Heartbeat
                    if (payload.startsWith("~h~")) {
                        ws.send(text); 
                    } else {
                        processJsonPayload(payload);
                    }

                    index = endPayload;
                }
            }

            private void processJsonPayload(String payload) {
                if (!payload.startsWith("{")) return;
                try {
                    JSONObject json = new JSONObject(payload);
                    String method = json.optString("m");

                    if ("timescale_update".equals(method)) {
                        JSONArray p = json.getJSONArray("p");
                        if (p.length() > 1) {
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
                                        for (int c = 0; c < candles.length(); c++) {
                                            JSONObject candle = candles.getJSONObject(c);
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
                                            
                                            cache.put(key, new TVMarketData(key, lastPrice, change, ma, System.currentTimeMillis()));
                                        }
                                    }
                                }
                            }
                        }
                    } 
                    else if ("qsd".equals(method)) {
                        JSONArray p = json.getJSONArray("p");
                        if (p.length() > 1) {
                            JSONObject quote = p.getJSONObject(1);
                            String ticker = quote.optString("n");
                            JSONObject v = quote.optJSONObject("v");

                            if (v != null) {
                                for (Map.Entry<String, String> entry : SYMBOL_MAP.entrySet()) {
                                    if (entry.getValue().equals(ticker)) {
                                        String key = entry.getKey();
                                        TVMarketData existing = cache.get(key);

                                        // Extraction résiliente par delta typique de TradingView
                                        double price = v.has("lp") ? v.optDouble("lp", 0.0) : (existing != null ? existing.price : 0.0);
                                        double change = v.has("chp") ? v.optDouble("chp", 0.0) : (existing != null ? existing.changePercent : 0.0);
                                        double ma = (existing != null) ? existing.ma200 : 0.0;

                                        if (price > 0 || change != 0) {
                                            cache.put(key, new TVMarketData(key, price, change, ma, System.currentTimeMillis()));
                                        }
                                        break;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[TV WS] Erreur lors de l'analyse structurelle du JSON", e);
                }
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
                if (isRunning.get()) {
                    new Thread(() -> {
                        try {
                            TimeUnit.SECONDS.sleep(5);
                            Log.w(TAG, "[TV WS] Reconnexion automatique lancée...");
                            connectWebSocket();
                        } catch (InterruptedException ignored) {}
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
                    Log.e(TAG, "[TV WS] Échec de l'encapsulation du message frame", e);
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ACCÈS DONNÉES CACHE
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
                    listener.onError("Matrice incomplète après expiration du délai d'acquisition.");
                }
            }
        }).start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // COMPILATEUR CONTEXTE MACRO (MATRICE SYNCHRONISÉE)
    // ─────────────────────────────────────────────────────────────────────────

    public static String buildContexteMacroGlobal(Context ctx) {
        if (cache.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("═══ CONTEXTE MACRO GLOBAL TEMPS RÉEL ═══\n");

        // 1. ANCRES DIRECTRICES
        TVMarketData dxy = cache.get("DXY");
        if (dxy != null) {
            String trend = dxy.changePercent > 0.3 ? "↗️ FORT" : dxy.changePercent < -0.3 ? "↘️ FAIBLE" : "➡️ STABLE";
            String impact = dxy.changePercent > 0.3 ? "→ Dollar fort : pression baissière GOLD, EURUSD, GBPUSD, AUDUSD" :
                            dxy.changePercent < -0.3 ? "→ Dollar faible : soutien haussier GOLD, EURUSD, GBPUSD, AUDUSD" : "→ Corrélations normales";
            sb.append("💵 DXY : ").append(String.format(Locale.US, "%.2f", dxy.price)).append(" ").append(trend)
              .append(" | MA200=").append(String.format(Locale.US, "%.2f", dxy.ma200))
              .append(dxy.aboveMA200 ? " [HAUSSIER]" : " [BAISSIER]").append("\n   ").append(impact).append("\n");
        }

        TVMarketData vix = cache.get("VIX");
        if (vix != null) {
            String regime = vix.price > 30 ? "🔴 PANIQUE" : vix.price > 20 ? "🟠 STRESS" : "🟢 CALME";
            String impact = vix.price > 30 ? "→ Corrélations instables — Risque de débouclage généralisé" :
                            vix.price > 20 ? "→ Risk-off dominant — filtrage des signaux haussiers requis" : "→ Signaux macro hautement fiables";
            sb.append("📊 VIX : ").append(String.format(Locale.US, "%.1f", vix.price)).append(" ").append(regime)
              .append("\n   ").append(impact).append("\n");
        }

        TVMarketData us10y = cache.get("US10Y");
        if (us10y != null) {
            String impact = us10y.price > 4.5 ? "→ Taux élevés : pression severe sur GOLD, NASDAQ, BTC" :
                            us10y.price < 3.5 ? "→ Taux bas : soutien GOLD, NASDAQ, BTC" : "→ Taux neutres";
            sb.append("📈 US10Y : ").append(String.format(Locale.US, "%.3f", us10y.price)).append("%")
              .append(" | MA200=").append(String.format(Locale.US, "%.3f", us10y.ma200)).append("%\n   ").append(impact).append("\n");
        }

        sb.append("─── ACTIFS & CORRÉLATIONS DYNAMIQUES ───\n");

        // 2. MATRICE DE SUIVI ACTIONS / INDICES
        TVMarketData us500 = cache.get("US500");
        if (us500 != null) {
            sb.append("🇺🇸 SP500 : ").append(String.format(Locale.US, "%.1f", us500.price))
              .append(String.format(Locale.US, " (%+.2f%%)", us500.changePercent))
              .append(us500.aboveMA200 ? " [BULL]" : " [BEAR — Risk-Off renforcé]").append("\n");
        }

        TVMarketData nasdaq = cache.get("NASDAQ");
        if (nasdaq != null) {
            sb.append("💻 NASDAQ : ").append(String.format(Locale.US, "%.2f", nasdaq.price))
              .append(String.format(Locale.US, " (%+.2f%%)", nasdaq.changePercent))
              .append(nasdaq.aboveMA200 ? " [MOMENTUM OK]" : " [SOUS MA200 — Risque technologique]").append("\n");
        }

        // 3. REFUGE & ÉNERGIE
        TVMarketData gold = cache.get("GOLD");
        if (gold != null) {
            double dist = gold.ma200 > 0 ? ((gold.price - gold.ma200) / gold.ma200 * 100) : 0;
            String status = gold.aboveMA200 ? String.format(Locale.US, "[+%.1f%% au-dessus MA200 — Refuge validé]", dist) :
                                              String.format(Locale.US, "[%.1f%% sous MA200 — Réduire conviction haussière]", dist);
            sb.append("🏆 GOLD : ").append(String.format(Locale.US, "%.2f", gold.price))
              .append(String.format(Locale.US, " (%+.2f%%)", gold.changePercent)).append(" ").append(status).append("\n");
        }

        TVMarketData usoil = cache.get("USOIL");
        if (usoil != null) {
            sb.append("🛢️ USOIL : ").append(String.format(Locale.US, "%.2f", usoil.price))
              .append(String.format(Locale.US, " (%+.2f%%)", usoil.changePercent))
              .append(usoil.aboveMA200 ? " [Structure Haussière]" : " [Structure Baissière]").append("\n");
        }

        // 4. PANIER FOREX SPECIFIQUE & CRYPTO
        TVMarketData eurusd = cache.get("EURUSD");
        if (eurusd != null) {
            sb.append("🇪🇺 EURUSD : ").append(String.format(Locale.US, "%.4f", eurusd.price))
              .append(String.format(Locale.US, " (%+.2f%%)", eurusd.changePercent)).append("\n");
        }

        TVMarketData usdjpy = cache.get("USDJPY");
        if (usdjpy != null) {
            sb.append("🇯🇵 USDJPY : ").append(String.format(Locale.US, "%.2f", usdjpy.price))
              .append(String.format(Locale.US, " (%+.2f%%)", usdjpy.changePercent)).append("\n");
        }

        TVMarketData gbpusd = cache.get("GBPUSD");
        if (gbpusd != null) {
            sb.append("🇬🇧 GBPUSD : ").append(String.format(Locale.US, "%.4f", gbpusd.price))
              .append(String.format(Locale.US, " (%+.2f%%)", gbpusd.changePercent)).append("\n");
        }

        TVMarketData audusd = cache.get("AUDUSD");
        if (audusd != null) {
            sb.append("🇦🇺 AUDUSD : ").append(String.format(Locale.US, "%.4f", audusd.price))
              .append(String.format(Locale.US, " (%+.2f%%)", audusd.changePercent)).append("\n");
        }

        TVMarketData usdcad = cache.get("USDCAD");
        if (usdcad != null) {
            sb.append("🇨🇦 USDCAD : ").append(String.format(Locale.US, "%.4f", usdcad.price))
              .append(String.format(Locale.US, " (%+.2f%%)", usdcad.changePercent)).append("\n");
        }

        TVMarketData btc = cache.get("BITCOIN");
        if (btc != null) {
            sb.append("🪙 BITCOIN : ").append(String.format(Locale.US, "%.0f", btc.price))
              .append(String.format(Locale.US, " (%+.2f%%)", btc.changePercent)).append("\n");
        }

        // 5. BLOC INSTITUTIONNEL ANCRE
        String regimeFed = ctx.getSharedPreferences("TradingBotPrefs", Context.MODE_PRIVATE)
                .getString("fed_regime", "PAUSE HAWKISH | Warsh | Taux 3.50-3.75% | CPI 4.2%");
        sb.append("🏦 RÉGIME FED : ").append(regimeFed).append("\n");

        sb.append("─────────────────────────────\n");
        sb.append("REGULATION FLUX : Si un actif évolue sous sa MA200,\n");
        sb.append("réduire d'office la conviction de 10-15% sur les signaux d'achats.\n");
        sb.append("═══════════════════════════════════════\n");

        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CALCULATEUR SYNCHRONISÉ DE TENDANCE FOND
    // ─────────────────────────────────────────────────────────────────────────

    private static class MovingAverage200Calculator {
        private static final int PERIOD = 200;
        private final double[] window = new double[PERIOD];
        private double currentSum = 0.0;
        private boolean initialized = false;

        public synchronized void initializeWithHistory(List<Double> historicalCloses) {
            if (historicalCloses == null || historicalCloses.size() < PERIOD) {
                return;
            }
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
