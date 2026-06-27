package com.tradingbot.analyzer;

import android.content.Context;
import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.Iterator;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TradingViewFetcher — Récupère DXY, VIX, US10Y, EURUSD, US500, NASDAQ, GOLD
 * via le WebSocket non officiel de TradingView.
 * Version améliorée avec récupération fiable de la MA200.
 */
public class TradingViewFetcher {

    private static final String TAG = "TradingViewFetcher";

    // ── WebSocket URL ──
    private static final String TV_WS_URL =
        "wss://data.tradingview.com/socket.io/websocket";

    // ── Symboles TradingView (mise à jour avec GOLD) ──
    private static final Map<String, String> SYMBOL_MAP = new HashMap<String, String>() {{
        put("DXY",    "TVC:DXY");
        put("VIX",    "CBOE:VIX");
        put("US10Y",  "TVC:US10Y");
        put("EURUSD", "FX:EURUSD");
        put("US500",  "OANDA:SPX500USD");
        put("NASDAQ", "NASDAQ:QQQ");
        put("GOLD",   "TVC:GOLD");    // Source TVC plus fiable
        put("USOIL",  "TVC:USOIL");  // Pétrole WTI
    }};

    // ── Données récupérées ──
    public static class TVMarketData {
        public final String symbol;
        public final double price;
        public final double changePercent;
        public final double ma200;
        public final boolean aboveMA200;
        public final long timestamp;

        public TVMarketData(String symbol, double price, double changePercent,
                            double ma200, long timestamp) {
            this.symbol       = symbol;
            this.price        = price;
            this.changePercent = changePercent;
            this.ma200        = ma200;
            this.aboveMA200   = (ma200 > 0) && (price > ma200);
            this.timestamp    = timestamp;
        }
    }

    // ── Cache thread-safe ──
    private static final ConcurrentHashMap<String, TVMarketData> cache =
        new ConcurrentHashMap<>();

    // ── Refresh toutes les 15 minutes ──
    private static final long REFRESH_INTERVAL_MIN = 15;
    private static ScheduledExecutorService scheduler;
    private static final AtomicBoolean isRunning = new AtomicBoolean(false);
    private static Context appContext;

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
            Log.d(TAG, "[TV] Déjà en cours — ignoré.");
            return;
        }
        appContext = context.getApplicationContext();
        Log.i(TAG, "[TV] Démarrage du fetcher TradingView (refresh toutes les "
            + REFRESH_INTERVAL_MIN + " min).");

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(
            () -> fetchAll(null),
            0,
            REFRESH_INTERVAL_MIN,
            TimeUnit.MINUTES
        );
    }

    public static void stop() {
        isRunning.set(false);
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        Log.i(TAG, "[TV] Fetcher arrêté.");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RÉCUPÉRATION DE TOUTES LES DONNÉES
    // ─────────────────────────────────────────────────────────────────────────

    public static void fetchAll(OnDataReadyListener listener) {
    new Thread(() -> {
        // Paralléliser — max 4 threads simultanés pour éviter le flood TradingView
        java.util.concurrent.ExecutorService pool =
            Executors.newFixedThreadPool(4);
        ConcurrentHashMap<String, TVMarketData> results = new ConcurrentHashMap<>();
        java.util.concurrent.CountDownLatch latch =
            new java.util.concurrent.CountDownLatch(SYMBOL_MAP.size());

        for (Map.Entry<String, String> entry : SYMBOL_MAP.entrySet()) {
            String key   = entry.getKey();
            String tvSym = entry.getValue();
            pool.submit(() -> {
                try {
                    TVMarketData data = null;
for (int attempt = 1; attempt <= 2 && data == null; attempt++) {
    try {
        data = fetchSymbol(key, tvSym);
    } catch (Exception e) {
        Log.w(TAG, "[TV] Tentative " + attempt + "/2 échouée pour " + key
            + " : " + e.getMessage());
        if (attempt < 2) Thread.sleep(3000);
    }
}
                    if (data != null) {
                        results.put(key, data);
                        cache.put(key, data);
                        Log.i(TAG, "[TV] " + key + " → " +
                            String.format(Locale.US, "%.4f", data.price) +
                            " MA200=" + String.format(Locale.US, "%.4f", data.ma200) +
                            (data.aboveMA200 ? " ↗️ ABOVE" : " ↘️ BELOW"));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[TV] Erreur fetch " + key + " : " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            // Attendre max 45 secondes pour tous les symboles
            latch.await(45, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.w(TAG, "[TV] fetchAll interrompu.");
        }
        pool.shutdownNow();
            if (listener != null) {
                if (!results.isEmpty()) {
                    listener.onDataReady(results);
                } else {
                    listener.onError("Aucune donnée récupérée depuis TradingView.");
                }
            }

            Log.i(TAG, "[TV] fetchAll terminé — " + results.size() + "/"
                + SYMBOL_MAP.size() + " symboles récupérés.");
        }).start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // RÉCUPÉRATION D'UN SYMBOLE VIA WEBSOCKET (AMÉLIORÉ)
    // ─────────────────────────────────────────────────────────────────────────

    private static TVMarketData fetchSymbol(String key, String tvSymbol) throws Exception {
        final Object lock = new Object();
        final double[] result = {-1, 0, 0}; // [price, changePercent, ma200]
        final boolean[] done  = {false};

        String wsSession    = "qs_" + randomString(12);
        String chartSession = "cs_" + randomString(12);

        URI uri = new URI(TV_WS_URL);
        Map<String, String> headers = new HashMap<>();
        headers.put("Origin", "https://data.tradingview.com");

        WebSocketClient ws = new WebSocketClient(uri, new org.java_websocket.drafts.Draft_6455(),
                headers, 10000) {

            private List<double[]> candles = new ArrayList<>();
            private boolean seriesRequested = false;

            @Override
            public void onOpen(ServerHandshake handshake) {
                try {
                    // Auth + sessions
                    send(msg("set_auth_token", new String[]{"unauthorized_user_token"}));
                    send(msg("set_locale",     new String[]{"en", "US"}));
                    send(msg("chart_create_session", new String[]{chartSession, ""}));
                    send(msg("quote_create_session", new String[]{wsSession}));

                    // Demande quote (prix actuel + variation)
                    String resolve = "{\"symbol\":\"" + tvSymbol + "\",\"adjustment\":\"splits\"}";
                    send(msg("quote_add_symbols",    new String[]{wsSession, "=" + resolve}));
                    send(msg("quote_set_fields",     new String[]{wsSession,
                        "ch", "chp", "lp", "open_price", "high_price", "low_price", "volume"}));

                    // Demande série daily pour MA200 — avec plage de dates
                    // from = maintenant - 300 jours, to = maintenant (en secondes)
                    send(msg("resolve_symbol", new String[]{chartSession, "sds_sym_1", "=" + resolve}));
                    // create_series: [sessionId, seriesId, seriesName, symbolId, resolution, from, to]
                    // TradingView attend : [chartSession, seriesId, seriesName, symbolId, resolution, barCount]
                    // from/to ne sont PAS des paramètres valides — utiliser le nombre de bougies
                    send(msg("create_series", new String[]{
                       chartSession, "sds_1", "s1", "sds_sym_1", "1D", "250", ""
                    }));
                    seriesRequested = true;
                    Log.d(TAG, "[TV WS] create_series envoyé pour " + tvSymbol);

                } catch (Exception e) {
                    Log.e(TAG, "[TV WS] Erreur onOpen : " + e.getMessage());
                }
            }

            @Override
            public void onMessage(String message) {
                Log.v(TAG, "[TV WS] Message reçu : " + message);
                // Parser les messages TradingView (format ~m~N~m~{json})
                String[] parts = message.split("~m~");
                for (String part : parts) {
                    try {
                        JSONObject json = new JSONObject(part);
                        String m = json.optString("m");

                        if ("qsd".equals(m)) {
                            // Quote data — prix actuel
                            JSONArray p = json.getJSONArray("p");
                            if (p.length() > 1) {
                                JSONObject v = p.getJSONObject(1).optJSONObject("v");
                                if (v != null) {
                                    double lp  = v.optDouble("lp", -1);
                                    double chp = v.optDouble("chp", 0);
                                    if (lp > 0) {
                                        result[0] = lp;
                                        result[1] = chp;
                                    }
                                }
                            }

                        
} else if ("timescale_update".equals(m)) {
    JSONArray p = json.getJSONArray("p");
    if (p.length() > 1) {
        JSONObject dataObj = p.getJSONObject(1);
        JSONObject series = dataObj.optJSONObject("sds_1");
        if (series == null) {
            // Fallback: chercher n'importe quelle clé contenant "s"
            Iterator<String> keys = dataObj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.startsWith("s")) {
                    series = dataObj.optJSONObject(key);
                    if (series != null) break;
                }
            }
        }
        if (series != null) {
            JSONArray s = series.optJSONArray("s");
                                    if (s != null) {
                                        for (int i = 0; i < s.length(); i++) {
                                            JSONArray v = s.getJSONObject(i).getJSONArray("v");
                                            double close = v.optDouble(4, 0);
                                            if (close > 0) candles.add(new double[]{close});
                                        }
                                        if (!candles.isEmpty()) {
                                            result[2] = calculateMA(candles, 200);
                                            Log.d(TAG, "[TV WS] MA200 calculée pour " + tvSymbol + " : " + result[2]);
                                        }
                                    }
                                }
                            }
                            // On a au moins le prix, on peut clore même sans MA200
                            synchronized (lock) {
                                if (result[0] > 0) {
                                    done[0] = true;
                                    lock.notifyAll();
                                }
                            }
                            close();
                        }

                    } catch (JSONException e) {
                        // Ignorer les messages non-JSON (heartbeat, etc.)
                    }
                }

                // Si on a le prix mais pas encore la MA200, on peut fermer après 15 secondes (timeout)
                // géré par le lock.wait plus bas.
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }

            @Override
            public void onError(Exception ex) {
                Log.e(TAG, "[TV WS] Erreur WebSocket : " + ex.getMessage());
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        };

        ws.connect();

        // Attendre max 25 secondes
        synchronized (lock) {
            if (!done[0]) {
                lock.wait(25000);
            }
        }

        if (!ws.isClosed()) ws.close();

        if (result[0] > 0) {
            // Si MA200 est toujours 0, on peut essayer de la récupérer via Twelve Data en fallback
            if (result[2] == 0) {
                Log.w(TAG, "[TV] MA200 non obtenue pour " + key + " — tentative fallback Twelve Data.");
                double ma200Fallback = fetchMA200FromTwelveData(key);
                if (ma200Fallback > 0) result[2] = ma200Fallback;
            }
            return new TVMarketData(key, result[0], result[1], result[2],
                System.currentTimeMillis());
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FALLBACK MA200 VIA TWELVE DATA (en dernier recours)
    // ─────────────────────────────────────────────────────────────────────────

    private static double fetchMA200FromTwelveData(String key) {
        // Mapper le nom interne vers le symbole Twelve Data
        String twelveSymbol = null;
        switch (key) {
            case "DXY":    twelveSymbol = "DXY"; break;
            case "VIX":    twelveSymbol = "VIX"; break;
            case "US10Y":  twelveSymbol = "US10Y"; break;
            case "EURUSD": twelveSymbol = "EURUSD"; break;
            case "US500":  twelveSymbol = "SP500"; break;
            case "NASDAQ": twelveSymbol = "NASDAQ"; break;
            case "GOLD":   twelveSymbol = "GOLD"; break;
        }
        if (twelveSymbol == null) return 0;
        // Utiliser MarketDataFetcher pour obtenir la SMA200
        // Pour l'instant, on renvoie 0 car cela nécessite une méthode supplémentaire
        // Vous pouvez implémenter un appel à l'API Twelve Data /sma
        try {
    String apiKey = appContext
    .getSharedPreferences("TradingBotPrefs", Context.MODE_PRIVATE)
    .getString("twelve_data_key", "");
    if (apiKey.isEmpty()) return 0;
    String url = "https://api.twelvedata.com/sma?symbol=" + twelveSymbol
        + "&interval=1day&time_period=200&apikey=" + apiKey;
    java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
        new java.net.URL(url).openConnection();
    conn.setConnectTimeout(8000);
    conn.setReadTimeout(8000);
    if (conn.getResponseCode() == 200) {
        java.io.BufferedReader br = new java.io.BufferedReader(
            new java.io.InputStreamReader(conn.getInputStream()));
        StringBuilder resp = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) resp.append(line);
        JSONObject json = new JSONObject(resp.toString());
        JSONObject values = json.optJSONArray("values") != null ?
            json.getJSONArray("values").getJSONObject(0) : null;
        if (values != null) {
            return values.optDouble("sma", 0);
        }
    }
    conn.disconnect();
} catch (Exception e) {
    Log.e(TAG, "[TV MA200 Fallback] Erreur TwelveData SMA : " + e.getMessage());
}
return 0;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CALCUL MA200
    // ─────────────────────────────────────────────────────────────────────────

    private static double calculateMA(List<double[]> candles, int period) {
        if (candles.size() < period) {
            Log.w(TAG, "[TV MA] Données insuffisantes : " + candles.size()
                + " bougies (besoin " + period + ")");
            return 0;
        }
        // Prendre les N dernières bougies
        List<double[]> last = candles.subList(candles.size() - period, candles.size());
        double sum = 0;
        for (double[] c : last) sum += c[0];
        return sum / period;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONTEXTE MACRO (inchangé mais intégrera GOLD)
    // ─────────────────────────────────────────────────────────────────────────

    public static String buildContexteMacroGlobal(Context ctx) {
    if (cache.isEmpty()) return "";
    // Vérifier que le cache a moins de 30 minutes
    long now = System.currentTimeMillis();
    boolean cacheStale = true;
for (TVMarketData d : cache.values()) {
    if ((now - d.timestamp) <= 30 * 60 * 1000L) { cacheStale = false; break; }
}
    if (cacheStale) {
        Log.w(TAG, "[TV] Cache périmé (>30min) — contexte macro non injecté.");
        return ""; // Ne pas injecter des données périmées
    }
        StringBuilder sb = new StringBuilder();
        sb.append("═══ CONTEXTE MACRO GLOBAL TEMPS RÉEL ═══\n");

        // ── DXY ──
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

        // ── VIX ──
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

        // ── US10Y ──
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

        // ── EURUSD ──
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

        // ── US500 ──
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

        // ── NASDAQ ──
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

        // ── GOLD ──
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
        // ── USOIL ──
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

        // ── Régime Fed ──
        String regimeFed = ctx.getSharedPreferences("TradingBotPrefs",
            Context.MODE_PRIVATE)
            .getString("fed_regime",
                "PAUSE HAWKISH | Warsh | Taux 3.50-3.75% | CPI 4.2% | Hausse possible Oct 2026");
        sb.append("🏦 RÉGIME FED : ").append(regimeFed).append("\n");

        // ── Règle MA200 ──
        sb.append("─────────────────────────────\n");
        sb.append("RÈGLE MA200 : Si un actif est SOUS sa MA200 et reçoit un signal haussier,\n");
        sb.append("réduire la conviction de 10-15% (signal contre tendance).\n");
        sb.append("Si actif AU-DESSUS MA200 et signal haussier, maintenir ou renforcer.\n");
        sb.append("═══════════════════════════════════════\n");

        return sb.toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ACCÈS AU CACHE
    // ─────────────────────────────────────────────────────────────────────────

    public static Map<String, TVMarketData> getCache() {
        return Collections.unmodifiableMap(cache);
    }

    public static boolean isCacheReady() {
        return !cache.isEmpty();
    }

    public static TVMarketData get(String symbol) {
        return cache.get(symbol);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UTILITAIRES
    // ─────────────────────────────────────────────────────────────────────────

    private static String randomString(int length) {
        String chars = "abcdefghijklmnopqrstuvwxyz";
        Random rand = new Random();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(chars.charAt(rand.nextInt(chars.length())));
        return sb.toString();
    }

    private static String msg(String func, String[] params) throws JSONException {
        JSONArray p = new JSONArray();
        for (String param : params) p.put(param);
        JSONObject json = new JSONObject();
        json.put("m", func);
        json.put("p", p);
        String body = json.toString();
        return "~m~" + body.length() + "~m~" + body;
    }
}
