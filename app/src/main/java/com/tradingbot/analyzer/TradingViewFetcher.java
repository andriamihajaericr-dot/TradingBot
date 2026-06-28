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
 * TradingViewFetcher — Récupère DXY, VIX, US10Y, EURUSD, US500, NASDAQ, GOLD, USOIL
 * via le WebSocket de TradingView.
 * Version autonome corrigée et sécurisée pour la production.
 */
public class TradingViewFetcher {

    private static final String TAG = "TradingViewFetcher";

    // ── Mapping clé interne → ticker TradingView ──
    private static final Map<String, String> SYMBOL_MAP = new HashMap<String, String>() {{
        put("DXY",    "TVC:DXY");
        put("VIX",    "CBOE:VIX");
        put("US10Y",  "TVC:US10Y");
        put("EURUSD", "FX:EURUSD");
        put("US500",  "OANDA:SPX500USD");
        put("NASDAQ", "NASDAQ:QQQ");
        put("GOLD",   "OANDA:XAUUSD");
        put("USOIL",  "TVC:USOIL");
    }};

    // ── Données de marché ──
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

    // ── Cache et variables d'état synchronisés ──
    private static final ConcurrentHashMap<String, TVMarketData> cache = new ConcurrentHashMap<>();
    private static final AtomicBoolean isRunning = new AtomicBoolean(false);
    private static Context appContext;
    private static OkHttpClient client;
    private static WebSocket webSocket;
    private static volatile boolean connected = false;

    // ── Gestion thread-safe des sessions dynamiques du WS ──
    private static final ConcurrentHashMap<String, String> seriesIdToKey = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, MovingAverage200Calculator> calculators = new ConcurrentHashMap<>();
    private static int seriesCounter = 0;

    // ── Interface callback ──
    public interface OnDataReadyListener {
        void onDataReady(Map<String, TVMarketData> data);
        void onError(String error);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DÉMARRAGE / ARRÊT
    // ─────────────────────────────────────────────────────────────────────────

    public static void start(Context context) {
        if (isRunning.getAndSet(true)) {
            Log.d(TAG, "[TV] Déjà en cours.");
            return;
        }
        appContext = context.getApplicationContext();
        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        Log.i(TAG, "[TV] Démarrage du fetcher TradingView (OkHttp).");
        connectWebSocket();
    }

    public static void stop() {
        isRunning.set(false);
        if (webSocket != null) {
            try {
                webSocket.close(1000, "Arrêt normal");
            } catch (Exception ignored) {}
            webSocket = null;
        }
        client = null;
        connected = false;
        seriesIdToKey.clear();
        seriesCounter = 0;
        Log.i(TAG, "[TV] Fetcher arrêté proprement.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONNEXION ET GESTION DU PROTOCOLE WEBSOCKET
    // ─────────────────────────────────────────────────────────────────────────

    private static synchronized void connectWebSocket() {
        if (!isRunning.get()) return;

        Request request = new Request.Builder()
                .url("wss://data.tradingview.com/socket.io/websocket")
                .header("Origin", "https://www.tradingview.com")
                .build();

        webSocket = client.newWebSocket(request, new WebSocketListener() {
            private String chartSessionId;
            private String quoteSessionId;

            @Override
            public void onOpen(WebSocket ws, Response response) {
                Log.d(TAG, "[TV WS] Connecté au serveur.");
                connected = true;

                // Réinitialisation critique des compteurs à chaque nouvelle connexion
                seriesCounter = 0;
                seriesIdToKey.clear();

                chartSessionId = "cs_" + UUID.randomUUID().toString().substring(0, 12);
                quoteSessionId = "qs_" + UUID.randomUUID().toString().substring(0, 12);

                // 1. Authentification anonyme requise par TV
                sendMessage(ws, "set_auth_token", new String[]{"unauthorized_user_token"});

                // 2. Initialisation des DEUX sessions (Graphique pour l'historique MA200 & Quotes pour le temps réel)
                sendMessage(ws, "chart_create_session", new String[]{chartSessionId, ""});
                sendMessage(ws, "quote_create_session", new String[]{quoteSessionId});
                sendMessage(ws, "quote_set_fields", new String[]{quoteSessionId, "lp", "chp", "ch"});

                // 3. Enregistrement des flux pour chaque actif défini
                for (String key : SYMBOL_MAP.keySet()) {
                    String ticker = SYMBOL_MAP.get(key);
                    seriesCounter++;
                    String seriesId = "s" + seriesCounter;

                    seriesIdToKey.put(seriesId, key);
                    calculators.putIfAbsent(key, new MovingAverage200Calculator());

                    // Configuration du flux historique journalier (300 bougies pour la MA200)
                    String resolvePayload = "{\"symbol\":\"" + ticker + "\",\"adjustment\":\"splits\"}";
                    sendMessage(ws, "resolve_symbol", new String[]{chartSessionId, "sym_" + seriesId, resolvePayload});
                    sendMessage(ws, "create_series", new String[]{chartSessionId, seriesId, "s1", "sym_" + seriesId, "1D", "300"});

                    // Inscription obligatoire au flux Temps Réel "qsd"
                    sendMessage(ws, "quote_add_symbols", new String[]{quoteSessionId, ticker});

                    Log.d(TAG, "[TV WS] Inscription validée pour " + key + " (" + ticker + ") ID: " + seriesId);
                }
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                if (text == null || text.isEmpty()) return;

                // Gestion immédiate du Heartbeat (Doit renvoyer la trame brute à l'identique)
                if (text.contains("~h~")) {
                    ws.send(text);
                    return;
                }

                // Découpage robuste du protocole applicatif de TradingView (~m~[longueur]~m~[payload])
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
                    if (endPayload > text.length()) break;

                    String payload = text.substring(startPayload, endPayload);
                    processJsonPayload(payload);

                    index = endPayload;
                }
            }

            private void processJsonPayload(String payload) {
                if (!payload.startsWith("{")) return;
                try {
                    JSONObject json = new JSONObject(payload);
                    String method = json.optString("m");

                    // Traitement de l'historique daily (Calcul de la MA200)
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
                                            
                                            TVMarketData data = new TVMarketData(key, lastPrice, change, ma, System.currentTimeMillis());
                                            cache.put(key, data);
                                            Log.i(TAG, "[TV MA200] " + key + " initialisée avec succès. MA200 = " + ma);
                                        }
                                    }
                                }
                            }
                        }
                    } 
                    // Traitement des variations et prix TICK PAR TICK en temps réel
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

                                        // Extraction résiliente des champs (TradingView n'envoie que les deltas modifiés)
                                        double price = v.has("lp") ? v.getDouble("lp") : (existing != null ? existing.price : 0.0);
                                        double change = v.has("chp") ? v.getDouble("chp") : (existing != null ? existing.changePercent : 0.0);
                                        double ma = (existing != null) ? existing.ma200 : 0.0;

                                        if (price > 0 || change != 0) {
                                            TVMarketData updated = new TVMarketData(key, price, change, ma, System.currentTimeMillis());
                                            cache.put(key, updated);
                                        }
                                        break;
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[TV WS] Erreur lors du parsing du payload", e);
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                Log.e(TAG, "[TV WS] Connexion perdue ou en échec.", t);
                connected = false;
                scheduleReconnection();
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                Log.d(TAG, "[TV WS] Socket fermé (" + code + ") : " + reason);
                connected = false;
                scheduleReconnection();
            }

            private void scheduleReconnection() {
                if (isRunning.get()) {
                    new Thread(() -> {
                        try {
                            TimeUnit.SECONDS.sleep(5);
                            Log.i(TAG, "[TV WS] Tentative de reconnexion en cours...");
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
                    Log.e(TAG, "[TV WS] Erreur lors de l'encapsulation du message", e);
                }
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // METHODES D'ACCÈS PUBLIC ET ETAT DU CACHE
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
            // Attend jusqu'à 15 secondes que tous les actifs soient mappés dans le cache
            while (cache.size() < SYMBOL_MAP.size() && (System.currentTimeMillis() - start < 15000)) {
                try { TimeUnit.MILLISECONDS.sleep(500); } catch (InterruptedException ignored) {}
            }
            if (listener != null) {
                if (!cache.isEmpty()) {
                    listener.onDataReady(cache);
                } else {
                    listener.onError("Aucune donnée reçue après 15 secondes d'attente.");
                }
            }
        }).start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONTEXTE MACRO GLOBAL (VERSION INTACTE ET SÉCURISÉE LOGIC-WISE)
    // ─────────────────────────────────────────────────────────────────────────

    public static String buildContexteMacroGlobal(Context ctx) {
        if (cache.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("═══ CONTEXTE MACRO GLOBAL TEMPS RÉEL ═══\n");

        TVMarketData dxy = cache.get("DXY");
        if (dxy != null) {
            String trend = dxy.changePercent > 0.3 ? "↗️ FORT" :
                    dxy.changePercent < -0.3 ? "↘️ FAIBLE" : "➡️ STABLE";
            String impact = dxy.changePercent > 0.3 ?
                    "→ Dollar fort : pression baissière GOLD, EURUSD, GBPUSD, AUDUSD" :
                    dxy.changePercent < -0.3 ?
                            "→ Dollar faible : soutien haussier GOLD, EURUSD, GBPUSD, AUDUSD" :
                            "→ Dollar stable : corrélations normales";
            sb.append("💵 DXY : ").append(String.format(Locale.US, "%.2f", dxy.price))
                    .append(" ").append(trend)
                    .append(" | MA200=").append(String.format(Locale.US, "%.2f", dxy.ma200))
                    .append(dxy.aboveMA200 ? " [TENDANCE HAUSSIÈRE]" : " [TENDANCE BAISSIÈRE]")
                    .append("\n   ").append(impact).append("\n");
        }

        TVMarketData vix = cache.get("VIX");
        if (vix != null) {
            String regime, impact;
            if (vix.price > 30) {
                regime = "🔴 PANIQUE";
                impact = "→ Corrélations instables — GOLD peut baisser avec actions, BTC décorrèle";
            } else if (vix.price > 20) {
                regime = "🟠 STRESS";
                impact = "→ Risk-off dominant — pondérer signaux haussiers avec prudence";
            } else {
                regime = "🟢 CALME";
                impact = "→ Corrélations stables — signaux macro fiables";
            }
            sb.append("📊 VIX : ").append(String.format(Locale.US, "%.1f", vix.price))
                    .append(" ").append(regime).append("\n   ").append(impact).append("\n");
        }

        TVMarketData us10y = cache.get("US10Y");
        if (us10y != null) {
            String trend = us10y.changePercent > 0.5 ? "↗️ HAUSSE" :
                    us10y.changePercent < -0.5 ? "↘️ BAISSE" : "➡️ STABLE";
            String impact = us10y.price > 4.5 ?
                    "→ Taux élevés : pression sur GOLD, NASDAQ, BTC" :
                    us10y.price < 3.5 ?
                            "→ Taux bas : soutien GOLD, NASDAQ, BTC" :
                            "→ Taux neutres";
            sb.append("📈 US10Y : ").append(String.format(Locale.US, "%.3f", us10y.price))
                    .append("% ").append(trend)
                    .append(" | MA200=").append(String.format(Locale.US, "%.3f", us10y.ma200))
                    .append("%").append("\n   ").append(impact).append("\n");
        }

        TVMarketData eurusd = cache.get("EURUSD");
        if (eurusd != null) {
            sb.append("🇪🇺 EURUSD : ")
                    .append(String.format(Locale.US, "%.4f", eurusd.price))
                    .append(eurusd.changePercent > 0 ? " ↗️" : " ↘️")
                    .append(String.format(Locale.US, " (%+.2f%%)", eurusd.changePercent))
                    .append(" | MA200=").append(String.format(Locale.US, "%.4f", eurusd.ma200))
                    .append(eurusd.aboveMA200 ? " [EURO FORT]" : " [EURO FAIBLE]")
                    .append("\n");
        }

        TVMarketData us500 = cache.get("US500");
        if (us500 != null) {
            sb.append("📊 SP500 : ")
                    .append(String.format(Locale.US, "%.1f", us500.price))
                    .append(us500.changePercent > 0 ? " ↗️" : " ↘️")
                    .append(String.format(Locale.US, " (%+.2f%%)", us500.changePercent))
                    .append(" | MA200=").append(String.format(Locale.US, "%.1f", us500.ma200))
                    .append(us500.aboveMA200 ? " [BULL]" : " [BEAR — risk-off amplifié]")
                    .append("\n");
        }

        TVMarketData nasdaq = cache.get("NASDAQ");
        if (nasdaq != null) {
            sb.append("💻 NASDAQ : ")
                    .append(String.format(Locale.US, "%.2f", nasdaq.price))
                    .append(nasdaq.changePercent > 0 ? " ↗️" : " ↘️")
                    .append(String.format(Locale.US, " (%+.2f%%)", nasdaq.changePercent))
                    .append(" | MA200=").append(String.format(Locale.US, "%.2f", nasdaq.ma200))
                    .append(nasdaq.aboveMA200 ? " [MOMENTUM HAUSSIER]" : " [SOUS MA200 — signal baissier renforcé]")
                    .append("\n");
        }

        TVMarketData gold = cache.get("GOLD");
        if (gold != null) {
            double goldDistMA = gold.ma200 > 0 ?
                    ((gold.price - gold.ma200) / gold.ma200 * 100) : 0;
            String goldSignal = gold.aboveMA200 ?
                    String.format(Locale.US, "[+%.1f%% AU-DESSUS MA200 — refuge confirmé]", goldDistMA) :
                    String.format(Locale.US, "[%.1f%% SOUS MA200 — réduire conviction GÉO haussier 15%%]", goldDistMA);
            sb.append("🏆 GOLD : ")
                    .append(String.format(Locale.US, "%.2f", gold.price))
                    .append(gold.changePercent > 0 ? " ↗️" : " ↘️")
                    .append(String.format(Locale.US, " (%+.2f%%)", gold.changePercent))
                    .append(" | MA200=").append(String.format(Locale.US, "%.2f", gold.ma200))
                    .append(" ").append(goldSignal).append("\n");
        }

        TVMarketData usoil = cache.get("USOIL");
        if (usoil != null) {
            double distMA = usoil.ma200 > 0 ?
                    ((usoil.price - usoil.ma200) / usoil.ma200 * 100) : 0;
            String maSignal = usoil.aboveMA200 ?
                    String.format(Locale.US, "[+%.1f%% AU-DESSUS MA200 — prime GÉO déjà pricée]", distMA) :
                    String.format(Locale.US, "[%.1f%% SOUS MA200 — potentiel limité]", distMA);
            sb.append("🛢️ USOIL : ")
                    .append(String.format(Locale.US, "%.2f", usoil.price))
                    .append(usoil.changePercent > 0 ? " ↗️" : " ↘️")
                    .append(String.format(Locale.US, " (%+.2f%%)", usoil.changePercent))
                    .append(" | MA200=").append(String.format(Locale.US, "%.2f", usoil.ma200))
                    .append(" ").append(maSignal).append("\n");
        }

        // Récupération dynamique du régime de la FED depuis les SharedPreferences
        String regimeFed = ctx.getSharedPreferences("TradingBotPrefs", Context.MODE_PRIVATE)
                .getString("fed_regime", "PAUSE HAWKISH | Warsh | Taux 3.50-3.75% | CPI 4.2% | Hausse possible Oct 2026");
        sb.append("🏦 RÉGIME FED : ").append(regimeFed).append("\n");

        sb.append("─────────────────────────────\n");
        sb.append("RÈGLE MA200 : Si un actif est SOUS sa MA200 et reçoit un signal haussier,\n");
        sb.append("réduire la conviction de 10-15% (signal contre tendance).\n");
        sb.append("Si actif AU-DESSUS MA200 et signal haussier, maintenir ou renforcer.\n");
        sb.append("═══════════════════════════════════════\n");

        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CLASSE INTERNE : CALCULATEUR SYNC DE LA MA200
    // ─────────────────────────────────────────────────────────────────────────

    private static class MovingAverage200Calculator {
        private static final int PERIOD = 200;
        private final double[] window = new double[PERIOD];
        private double currentSum = 0.0;
        private boolean initialized = false;

        public synchronized void initializeWithHistory(List<Double> historicalCloses) {
            if (historicalCloses == null || historicalCloses.size() < PERIOD) {
                Log.w(TAG, "[MA200] Données historiques insuffisantes pour l'initialisation.");
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
