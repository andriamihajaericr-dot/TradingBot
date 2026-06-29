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

    // ── Structure de données avec 4 indicateurs ──
    public static class TVMarketData {
    public final String symbol;
    public final double price;
    public final double changePercent;
    public final double ma200;
    public final boolean aboveMA200;
    // ── Previous Day High/Low ──
    public final double pdh; // Previous Day High
    public final double pdl; // Previous Day Low
    public final boolean brokeAbovePDH; // prix > PDH
    public final boolean brokeBelowPDL; // prix < PDL
    // ── Previous Week High/Low ──
    public final double pwh; // Previous Week High
    public final double pwl; // Previous Week Low
    public final boolean brokeAbovePWH; // prix > PWH
    public final boolean brokeBelowPWL; // prix < PWL
    public final long timestamp;

    public TVMarketData(String symbol, double price, double changePercent,
                        double ma200, double pdh, double pdl,
                        double pwh, double pwl, long timestamp) {
        this.symbol        = symbol;
        this.price         = price;
        this.changePercent = changePercent;
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
    private static String twelveDataKey = "";
    // Clés SharedPreferences pour weekly levels
private static final String PREFS_WEEKLY = "TradingBotPrefs";
private static final String PREF_WEEKLY_UPDATED = "weekly_levels_updated";

// ── Caches et gestionnaires ──
private static final ConcurrentHashMap<String, TVMarketData> cache = new ConcurrentHashMap<>();
// Cache PDH/PDL (mis à jour 1x/jour à minuit)
private static final ConcurrentHashMap<String, Double> pdhCache = new ConcurrentHashMap<>();
private static final ConcurrentHashMap<String, Double> pdlCache = new ConcurrentHashMap<>();
// Cache PWH/PWL (mis à jour 1x/semaine le lundi)
private static final ConcurrentHashMap<String, Double> pwhCache = new ConcurrentHashMap<>();
private static final ConcurrentHashMap<String, Double> pwlCache = new ConcurrentHashMap<>();
// Anti-spam alertes (1 alerte par niveau par actif max)
private static final ConcurrentHashMap<String, Boolean> alertFiredPDH = new ConcurrentHashMap<>();
private static final ConcurrentHashMap<String, Boolean> alertFiredPDL = new ConcurrentHashMap<>();
private static final ConcurrentHashMap<String, Boolean> alertFiredPWH = new ConcurrentHashMap<>();
private static final ConcurrentHashMap<String, Boolean> alertFiredPWL = new ConcurrentHashMap<>();
private static final AtomicBoolean isRunning = new AtomicBoolean(false);
    private static final ConcurrentHashMap<String, VarianceCalculator> varianceCalculators = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> lastAlertTime = new ConcurrentHashMap<>(); // anti-spam
    private static final long ALERT_COOLDOWN_MS = 5 * 60 * 1000; // 5 minutes

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
SharedPreferences prefs = appContext.getSharedPreferences("TradingBotPrefs", Context.MODE_PRIVATE);
twelveDataKey = prefs.getString("macro_api_key", "");
if (twelveDataKey.isEmpty()) twelveDataKey = prefs.getString("twelve_data_key", "");
if (twelveDataKey.isEmpty()) {
    SharedPreferences prefs2 = appContext.getSharedPreferences("TradingBot", Context.MODE_PRIVATE);
    twelveDataKey = prefs2.getString("macro_api_key", "");
}
if (twelveDataKey.isEmpty()) {
    logToUI("⚠️ [TV] Clé TwelveData absente – fallback SMA et PDH/PWH désactivés.");
}
client = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build();
logToUI("📡 [TV] Démarrage du pipeline TradingView (WebSocket).");
connectWebSocket();
// Charger PDH/PDL et PWH/PWL au démarrage via TwelveData
fetchPreviousLevels();
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

                for (String key : SYMBOL_MAP.keySet()) {
                    String ticker = SYMBOL_MAP.get(key);
                    varianceCalculators.putIfAbsent(key, new VarianceCalculator(20));
                    sendMessage(ws, "quote_add_symbols", new String[]{quoteSessionId, ticker});
                }
                logToUI("📥 [TV WS] " + SYMBOL_MAP.size() + " symboles abonnés.");
                Log.i(TAG, "[TV WS] " + SYMBOL_MAP.size() + " symboles abonnés.");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                if (text == null) return;
                // Heartbeat
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
                                    double variance = 0.0;
                                    if (calc != null) {
                                        calc.addPrice(price);
                                        variance = calc.getVariance();
                                    }
                                    // Mise à jour du cache
                                    double pdh = pdhCache.getOrDefault(key, 0.0);
double pdl = pdlCache.getOrDefault(key, 0.0);
double pwh = pwhCache.getOrDefault(key, 0.0);
double pwl = pwlCache.getOrDefault(key, 0.0);
TVMarketData newData = new TVMarketData(key, price, change,
        high, low, open, prevClose,
        variance, pdh, pdl, pwh, pwl,
        System.currentTimeMillis());
cache.put(key, newData);

// Vérification cassures PDH/PDL/PWH/PWL
checkAndAlert(key, newData);

                
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

    // ── PDH cassé à la hausse ──
    if (data.brokeAbovePDH && !Boolean.TRUE.equals(alertFiredPDH.get(key))) {
        alertFiredPDH.put(key, true);
        String msg = "🔺 *" + key + "* — Cassure du *Previous Day High*\n" +
            "Prix : `" + String.format(Locale.US, "%.4f", data.price) + "`\n" +
            "PDH : `" + String.format(Locale.US, "%.4f", data.pdh) + "`\n" +
            "💡 *Signal haussier* : Les acheteurs ont absorbé la résistance du jour précédent.\n" +
            "📈 Surveiller la clôture au-dessus pour confirmation du momentum.";
        NotificationService.sendTelegramSecure(msg, appContext);
        logToUI("🔺 [PDH CASSÉ] " + key + " > " + String.format(Locale.US, "%.4f", data.pdh));
    }

    // ── PDL cassé à la baisse ──
    if (data.brokeBelowPDL && !Boolean.TRUE.equals(alertFiredPDL.get(key))) {
        alertFiredPDL.put(key, true);
        String msg = "🔻 *" + key + "* — Cassure du *Previous Day Low*\n" +
            "Prix : `" + String.format(Locale.US, "%.4f", data.price) + "`\n" +
            "PDL : `" + String.format(Locale.US, "%.4f", data.pdl) + "`\n" +
            "💡 *Signal baissier* : Les vendeurs ont brisé le support du jour précédent.\n" +
            "📉 Surveiller la clôture en dessous pour confirmation de la pression vendeuse.";
        NotificationService.sendTelegramSecure(msg, appContext);
        logToUI("🔻 [PDL CASSÉ] " + key + " < " + String.format(Locale.US, "%.4f", data.pdl));
    }

    // ── PWH cassé à la hausse ──
    if (data.brokeAbovePWH && !Boolean.TRUE.equals(alertFiredPWH.get(key))) {
        alertFiredPWH.put(key, true);
        String msg = "🚀 *" + key + "* — Breakout *Previous Week High* !\n" +
            "Prix : `" + String.format(Locale.US, "%.4f", data.price) + "`\n" +
            "PWH : `" + String.format(Locale.US, "%.4f", data.pwh) + "`\n" +
            "💡 *Signal institutionnel fort* : Breakout weekly confirmé — les institutions ont validé la hausse.\n" +
            "📈 Zone de continuation haussière — surveiller les retracements vers PWH comme support.";
        NotificationService.sendTelegramSecure(msg, appContext);
        logToUI("🚀 [PWH CASSÉ] " + key + " > " + String.format(Locale.US, "%.4f", data.pwh));
    }

    // ── PWL cassé à la baisse ──
    if (data.brokeBelowPWL && !Boolean.TRUE.equals(alertFiredPWL.get(key))) {
        alertFiredPWL.put(key, true);
        String msg = "🔥 *" + key + "* — Breakdown *Previous Week Low* !\n" +
            "Prix : `" + String.format(Locale.US, "%.4f", data.price) + "`\n" +
            "PWL : `" + String.format(Locale.US, "%.4f", data.pwl) + "`\n" +
            "💡 *Signal institutionnel fort* : Breakdown weekly — pression vendeuse majeure.\n" +
            "📉 Zone de continuation baissière — surveiller les rebonds vers PWL comme résistance.";
        NotificationService.sendTelegramSecure(msg, appContext);
        logToUI("🔥 [PWL CASSÉ] " + key + " < " + String.format(Locale.US, "%.4f", data.pwl));
    }
 }
   // ─────────────────────────────────────────────────────────────────────────
// RÉCUPÉRATION PDH/PDL (veille) ET PWH/PWL (semaine précédente)
// ─────────────────────────────────────────────────────────────────────────

public static void fetchPreviousLevels() {
    if (twelveDataKey.isEmpty() || appContext == null) {
        logToUI("⚠️ [TV] Clé absente — PDH/PDL/PWH/PWL non chargés.");
        return;
    }
    new Thread(() -> {
        logToUI("🔄 [TV] Chargement PDH/PDL/PWH/PWL...");
        Map<String, String> tdMap = new HashMap<String, String>() {{
            put("GOLD",    "XAU/USD");
            put("USOIL",   "WTI/USD");
            put("EURUSD",  "EUR/USD");
            put("USDJPY",  "USD/JPY");
            put("GBPUSD",  "GBP/USD");
            put("AUDUSD",  "AUD/USD");
            put("USDCAD",  "USD/CAD");
            put("BITCOIN", "BTC/USD");
            put("NASDAQ",  "QQQ");
            put("US500",   "SPY");
            put("DXY",     "DXY");
        }};
        int count = 0;
        for (Map.Entry<String, String> entry : tdMap.entrySet()) {
            String key   = entry.getKey();
            String tdSym = entry.getValue();
            try {
                // PDH/PDL — 3 bougies daily, index 1 = veille
                String urlD = "https://api.twelvedata.com/time_series?symbol=" + tdSym
                    + "&interval=1day&outputsize=3&apikey=" + twelveDataKey;
                String respD = httpGetSimple(urlD);
                if (respD != null) {
                    JSONObject json = new JSONObject(respD);
                    JSONArray vals  = json.optJSONArray("values");
                    if (vals != null && vals.length() >= 2) {
                        JSONObject prev = vals.getJSONObject(1);
                        double pdh = prev.optDouble("high", 0);
                        double pdl = prev.optDouble("low",  0);
                        if (pdh > 0) { pdhCache.put(key, pdh); alertFiredPDH.remove(key); }
                        if (pdl > 0) { pdlCache.put(key, pdl); alertFiredPDL.remove(key); }
                        logToUI("📅 [PDH/PDL] " + key + " PDH=" +
                            String.format(Locale.US, "%.4f", pdh) +
                            " PDL=" + String.format(Locale.US, "%.4f", pdl));
                    }
                }
                // PWH/PWL — 2 bougies weekly, index 1 = semaine précédente
                String urlW = "https://api.twelvedata.com/time_series?symbol=" + tdSym
                    + "&interval=1week&outputsize=2&apikey=" + twelveDataKey;
                String respW = httpGetSimple(urlW);
                if (respW != null) {
                    JSONObject json = new JSONObject(respW);
                    JSONArray vals  = json.optJSONArray("values");
                    if (vals != null && vals.length() >= 2) {
                        JSONObject prevW = vals.getJSONObject(1);
                        double pwh = prevW.optDouble("high", 0);
                        double pwl = prevW.optDouble("low",  0);
                        if (pwh > 0) { pwhCache.put(key, pwh); alertFiredPWH.remove(key); }
                        if (pwl > 0) { pwlCache.put(key, pwl); alertFiredPWL.remove(key); }
                        logToUI("📅 [PWH/PWL] " + key + " PWH=" +
                            String.format(Locale.US, "%.4f", pwh) +
                            " PWL=" + String.format(Locale.US, "%.4f", pwl));
                    }
                }
                count++;
                Thread.sleep(600);
            } catch (Exception e) {
                Log.e(TAG, "[TV PDH/PWH] Erreur " + key + " : " + e.getMessage());
            }
        }
        logToUI("✅ [TV] PDH/PDL/PWH/PWL chargés pour " + count + " actifs.");
    }).start();
}

private static String httpGetSimple(String urlStr) {
    try {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        if (conn.getResponseCode() == 200) {
            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            conn.disconnect();
            return sb.toString();
        }
        conn.disconnect();
    } catch (Exception e) {
        Log.e(TAG, "[TV HTTP] Erreur : " + e.getMessage());
    }
    return null;
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
    // CALCULATEUR DE VARIANCE SUR TICKS
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
    // CONTEXTE MACRO AVEC 4 INDICATEURS
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
  .append(d.aboveMA200 ? " ↗️ MA200" : " ↘️ MA200")
  .append(d.pdh > 0 ? " | PDH=" + String.format(Locale.US, "%.4f", d.pdh) : "")
  .append(d.pdl > 0 ? " PDL=" + String.format(Locale.US, "%.4f", d.pdl) : "")
  .append(d.brokeAbovePDH ? " 🔺PDH" : d.brokeBelowPDL ? " 🔻PDL" : "")
  .append(d.pwh > 0 ? " | PWH=" + String.format(Locale.US, "%.4f", d.pwh) : "")
  .append(d.pwl > 0 ? " PWL=" + String.format(Locale.US, "%.4f", d.pwl) : "")
  .append(d.brokeAbovePWH ? " 🚀PWH" : d.brokeBelowPWL ? " 🔥PWL" : "")
  .append("\n");
            }
        }

        String regimeFed = ctx.getSharedPreferences("TradingBotPrefs", Context.MODE_PRIVATE)
                .getString("fed_regime", "PAUSE HAWKISH | Warsh | Taux 3.50-3.75% | CPI 4.2%");
        sb.append("🏦 RÉGIME FED : ").append(regimeFed).append("\n");
        sb.append("─────────────────────────────\n");
        sb.append("📊 INDICATEURS :\n");
        sb.append("🔔 Alertes automatiques : cassure PDH/PDL (daily) et PWH/PWL (weekly) envoyées sur Telegram.");
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
