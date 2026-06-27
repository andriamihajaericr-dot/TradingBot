package com.tradingbot.analyzer;

import android.content.Context;
import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

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
 * TradingViewFetcher — Récupère DXY, VIX, US10Y, EURUSD, US500, NASDAQ
 * via le WebSocket non officiel de TradingView.
 *
 * ⚠️ AVERTISSEMENT : API non officielle — TradingView peut modifier le protocole
 * sans préavis. Utiliser uniquement à des fins éducatives.
 *
 * Dépendance Gradle à ajouter dans build.gradle :
 *   implementation 'org.java-websocket:Java-WebSocket:1.5.3'
 */
public class TradingViewFetcher {

    private static final String TAG = "TradingViewFetcher";

    // ── WebSocket URL ──
    private static final String TV_WS_URL =
        "wss://data.tradingview.com/socket.io/websocket";

    // ── Symboles TradingView ──
    // Clé interne → Symbole TradingView
    private static final Map<String, String> SYMBOL_MAP = new HashMap<String, String>() {{
        put("DXY",    "TVC:DXY");
        put("VIX",    "CBOE:VIX");
        put("US10Y",  "TVC:US10Y");
        put("EURUSD", "FX:EURUSD");
        put("US500",  "OANDA:SPX500USD");
        put("NASDAQ", "NASDAQ:QQQ");
    }};

    // ── Données récupérées ──
    public static class TVMarketData {
        public final String symbol;
        public final double price;
        public final double changePercent;
        public final double ma200;          // Moyenne mobile 200 périodes (daily)
        public final boolean aboveMA200;    // Prix au-dessus de la MA200
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
            Log.d(TAG, "[TV] Début fetchAll — " + SYMBOL_MAP.size() + " symboles.");
            Map<String, TVMarketData> results = new HashMap<>();

            for (Map.Entry<String, String> entry : SYMBOL_MAP.entrySet()) {
                String key    = entry.getKey();
                String tvSym  = entry.getValue();
                try {
                    TVMarketData data = fetchSymbol(key, tvSym);
                    if (data != null) {
                        results.put(key, data);
                        cache.put(key, data);
                        Log.i(TAG, "[TV] " + key + " → " + 
                            String.format(Locale.US, "%.4f", data.price) +
                            " (" + String.format(Locale.US, "%+.2f", data.changePercent) + "%)" +
                            " MA200=" + String.format(Locale.US, "%.4f", data.ma200) +
                            (data.aboveMA200 ? " ↗️ ABOVE" : " ↘️ BELOW"));
                    }
                    // Pause entre symboles pour éviter le flood
                    Thread.sleep(2000);
                } catch (Exception e) {
                    Log.e(TAG, "[TV] Erreur fetch " + key + " : " + e.getMessage());
                }
            }

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
    // RÉCUPÉRATION D'UN SYMBOLE VIA WEBSOCKET
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

                    // Demande série daily pour MA200
                    send(msg("resolve_symbol", new String[]{chartSession, "sds_sym_1", "=" + resolve}));
                    send(msg("create_series",  new String[]{chartSession, "sds_1", "s1",
                        "sds_sym_1", "1D", "250", ""})); // 250 jours = MA200 + marge

                } catch (Exception e) {
                    Log.e(TAG, "[TV WS] Erreur onOpen : " + e.getMessage());
                }
            }

            @Override
            public void onMessage(String message) {
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
                            // Série daily — calculer MA200
                            JSONArray p = json.getJSONArray("p");
                            if (p.length() > 1) {
                                JSONObject sds = p.getJSONObject(1).optJSONObject("sds_1");
                                if (sds != null) {
                                    JSONArray s = sds.optJSONArray("s");
                                    if (s != null) {
                                        for (int i = 0; i < s.length(); i++) {
                                            JSONArray v = s.getJSONObject(i).getJSONArray("v");
                                            double close = v.optDouble(4, 0);
                                            if (close > 0) candles.add(new double[]{close});
                                        }
                                        result[2] = calculateMA(candles, 200);

                                        // On a tout — fermer
                                        synchronized (lock) {
                                            done[0] = true;
                                            lock.notifyAll();
                                        }
                                        close();
                                    }
                                }
                            }
                        }

                    } catch (JSONException e) {
                        // Ignorer les messages non-JSON (heartbeat, etc.)
                    }
                }
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

        // Attendre max 20 secondes
        synchronized (lock) {
            if (!done[0]) {
                lock.wait(20000);
            }
        }

        if (!ws.isClosed()) ws.close();

        if (result[0] > 0) {
            return new TVMarketData(key, result[0], result[1], result[2],
                System.currentTimeMillis());
        }
        return null;
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
    // CONTEXTE MACRO — Injecter dans le prompt Groq
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Génère le bloc contexte macro à injecter dans le SYSTEM_PROMPT ou userContent.
     * Utilise le cache LKV si disponible.
     */
    public static String buildContexteMacroGlobal(Context ctx) {
        if (cache.isEmpty()) {
            return ""; // Pas encore de données — ne rien injecter
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

        // ── Régime Fed ──
        String regimeFed = ctx.getSharedPreferences("TradingBotPrefs",
            Context.MODE_PRIVATE)
            .getString("fed_regime",
                "PAUSE HAWKISH | Warsh | Taux 3.50-3.75% | CPI 4.2% | Hausse possible Oct 2026");
        sb.append("🏦 RÉGIME FED : ").append(regimeFed).append("\n");

        // ── Règle MA200 pour le modèle ──
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

    /**
     * Formate un message WebSocket TradingView.
     * Format : ~m~{longueur}~m~{json}
     */
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
