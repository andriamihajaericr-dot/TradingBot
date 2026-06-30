package com.tradingbot.analyzer;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

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
       // put("DXY",     "TVC:DXY");
       // put("VIX",     "CBOE:VIX");
       // put("US10Y",   "TVC:US10Y");
        put("US500",   "OANDA:SPX500USD");
        put("NASDAQ",  "QQQ");
        put("GOLD",    "TVC:GOLD");
        put("USOIL",   "TVC:USOIL");
        //put("EURUSD",  "FX:EURUSD");
        put("USDJPY",  "FX:USDJPY");
        put("GBPUSD",  "FX:GBPUSD");
        //put("AUDUSD",  "FX:AUDUSD");
        //put("USDCAD",  "FX:USDCAD");
       // put("BITCOIN", "BINANCE:BTCUSDT");
    }};

    // ── Structure de données unifiée avec les 4 indicateurs + Niveaux pivots ──
    public static class TVMarketData {
        public final String symbol;
        public final double price;
        public final double changePercent;      // 1. Tendance depuis la clôture de la veille
        public final double high;
        public final double low;
        public final double open;
        public final double prevClose;
        public final double variance;           // 2. Volatilité intraday (sur 20 ticks)
        public final double volatilityPercent;  // 3. Amplitude daily (High-Low) en %
        public final double dailyRangePercent;  // 4. Position dans la fourchette du jour (0=Low, 100=High)
        public final boolean isNearHigh;
        public final boolean isNearLow;
        
        public final double ma200;
        public final boolean aboveMA200;
        
        // Niveaux Daily (TwelveData)
        public final double pdh; 
        public final double pdl; 
        public final boolean brokeAbovePDH; 
        public final boolean brokeBelowPDL; 
        
        // Niveaux Weekly (TwelveData)
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
            
            // Calcul automatique des indicateurs 3 et 4
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

    private static String twelveDataKey = "";
    private static final String PREFS_WEEKLY = "TradingBotPrefs";
    private static final String PREF_WEEKLY_UPDATED = "weekly_levels_updated";

    // ── Caches et gestionnaires mémoire vive ──
    private static final ConcurrentHashMap<String, TVMarketData> cache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> pdhCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> pdlCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> pwhCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> pwlCache = new ConcurrentHashMap<>();
    
    // Anti-spam alertes
    private static final ConcurrentHashMap<String, Boolean> alertFiredPDH = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> alertFiredPDL = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> alertFiredPWH = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> alertFiredPWL = new ConcurrentHashMap<>();
    
    private static final AtomicBoolean isRunning = new AtomicBoolean(false);
    private static final ConcurrentHashMap<String, VarianceCalculator> varianceCalculators = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> lastAlertTime = new ConcurrentHashMap<>(); 
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

    // ── AJOUTEZ CETTE MÉTHODE ICI ──
    public static void fetchAll(OnDataReadyListener listener) {
        if (listener == null) return;
        
        if (cache.isEmpty()) {
            listener.onError("Le cache temps réel est actuellement vide. Attendez que le WebSocket se connecte.");
        } else {
            // Renvoie une copie non modifiable du cache actuel à la MainActivity
            listener.onDataReady(Collections.unmodifiableMap(cache));
        }
    }

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
            logToUI("⚠️ [TV] Clé TwelveData absente – fallback pivots désactivés.");
        }
        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        logToUI("📡 [TV] Démarrage du pipeline TradingView (WebSocket).");
        connectWebSocket();
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

            @Override
            public void onOpen(WebSocket ws, Response response) {
                logToUI("✅ [TV WS] Canal ouvert.");
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

                                    double pdh = pdhCache.getOrDefault(key, 0.0);
                                    double pdl = pdlCache.getOrDefault(key, 0.0);
                                    double pwh = pwhCache.getOrDefault(key, 0.0);
                                    double pwl = pwlCache.getOrDefault(key, 0.0);

                                    // ALIGNEMENT PARFAIT DU CONSTRUCTEUR (14 PARAMÈTRES)
                                    TVMarketData newData = new TVMarketData(
                                            key, price, change, high, low, open, prevClose,
                                            variance, 0.0, pdh, pdl, pwh, pwl,
                                            System.currentTimeMillis()
                                    );
                                    cache.put(key, newData);

                                    // Vérification des cassures et alertes
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

                // Alertes extrêmes intraday (Anti-spam 5 min)
                Long last = lastAlertTime.get(key);
                if (last == null || (now - last) > ALERT_COOLDOWN_MS) {
                    if (data.isNearHigh || data.isNearLow) {
                        StringBuilder sb = new StringBuilder();
                        
                        // En-tête dynamique selon l'extrême touché
                        if (data.isNearHigh) {
                            sb.append("📊 *").append(key).append("* 🔺 Approche du *plus haut du jour*\n\n");
                        } else {
                            sb.append("📊 *").append(key).append("* 🔻 Approche du *plus bas du jour*\n\n");
                        }
                        
                        sb.append("🔹 *PRIX ACTUEL* : `").append(String.format(Locale.US, "%.4f", data.price)).append("`\n\n");
                        
                        // ── INTEGRATION DES 4 INDICATEURS COMPLETS ──
                        sb.append("📈 *LES 4 INDICATEURS TEMPS RÉEL :*\n");
                        sb.append("• 1. Variation : `").append(String.format(Locale.US, "%+.2f", data.changePercent)).append("%` (vs Clôture)\n");
                        sb.append("• 2. Volatilité Tick (20t) : `").append(String.format(Locale.US, "%.6f", data.variance)).append("` (Variance)\n");
                        sb.append("• 3. Amplitude Daily : `").append(String.format(Locale.US, "%.2f", data.volatilityPercent)).append("%` (High-Low)\n");
                        sb.append("• 4. Position Range : `").append(String.format(Locale.US, "%.1f", data.dailyRangePercent)).append("%` (0=Bas, 100=Haut)\n\n");
                        
                        // ── RECONSTITUTION DES NIVEAUX PIVOTS INSTITUTIONNELS ──
                        sb.append("🏛️ *NIVEAUX PIVOTS (TwelveData) :*\n");
                        if (data.pdh > 0 || data.pdl > 0) {
                            sb.append("• *Daily* : PDH = `").append(String.format(Locale.US, "%.4f", data.pdh))
                              .append("` | PDL = `").append(String.format(Locale.US, "%.4f", data.pdl)).append("`\n");
                        } else {
                            sb.append("• *Daily* : ⚠️ En attente du chargement TwelveData\n");
                        }
                        
                        if (data.pwh > 0 || data.pwl > 0) {
                            sb.append("• *Weekly* : PWH = `").append(String.format(Locale.US, "%.4f", data.pwh))
                              .append("` | PWL = `").append(String.format(Locale.US, "%.4f", data.pwl)).append("`\n");
                        } else {
                            sb.append("• *Weekly* : ⚠️ En attente du chargement TwelveData\n");
                        }

                        // État visuel immédiat en cas de cassure confirmée simultanée
                        if (data.brokeAbovePDH || data.brokeBelowPDL || data.brokeAbovePWH || data.brokeBelowPWL) {
                            sb.append("\n⚡ *Statut de cassure :*");
                            if (data.brokeAbovePDH) sb.append(" 🔺[Breakout PDH]");
                            if (data.brokeBelowPDL) sb.append(" 🔻[Breakdown PDL]");
                            if (data.brokeAbovePWH) sb.append(" 🚀[Breakout PWH]");
                            if (data.brokeBelowPWL) sb.append(" 🔥[Breakdown PWL]");
                            sb.append("\n");
                        }

                        // Envoi sécurisé à Telegram
                        NotificationService.sendTelegramSecure(sb.toString(), appContext);
                        lastAlertTime.put(key, now);
                    }
                }

                // Alertes de cassures Institutionnelles uniques (Conservées pour déclenchement sur ligne)
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
        if (twelveDataKey.isEmpty() || appContext == null) return;
        new Thread(() -> {
            logToUI("🔄 [TV] Chargement PDH/PDL/PWH/PWL via TwelveData...");
            Map<String, String> tdMap = new HashMap<String, String>() {{
                put("GOLD", "XAU/USD"); put("USOIL", "WTI/USD"); 
                put("USDJPY", "USD/JPY"); put("GBPUSD", "GBP/USD"); 
                put("NASDAQ", "QQQ");
                put("US500", "SPY");
            }};
            for (Map.Entry<String, String> entry : tdMap.entrySet()) {
                String key = entry.getKey();
                String tdSym = entry.getValue();
                try {
                    // Daily (index 1 = veille)
                    String urlD = "https://api.twelvedata.com/time_series?symbol=" + tdSym + "&interval=1day&outputsize=3&apikey=" + twelveDataKey;
                    String respD = httpGetSimple(urlD);
                    if (respD != null) {
                        JSONArray vals = new JSONObject(respD).optJSONArray("values");
                        if (vals != null && vals.length() >= 2) {
                            pdhCache.put(key, vals.getJSONObject(1).optDouble("high", 0));
                            pdlCache.put(key, vals.getJSONObject(1).optDouble("low", 0));
                        }
                    }
                    // Weekly (index 1 = semaine précédente)
                    String urlW = "https://api.twelvedata.com/time_series?symbol=" + tdSym + "&interval=1week&outputsize=2&apikey=" + twelveDataKey;
                    String respW = httpGetSimple(urlW);
                    if (respW != null) {
                        JSONArray vals = new JSONObject(respW).optJSONArray("values");
                        if (vals != null && vals.length() >= 2) {
                            pwhCache.put(key, vals.getJSONObject(1).optDouble("high", 0));
                            pwlCache.put(key, vals.getJSONObject(1).optDouble("low", 0));
                        }
                    }
                    Thread.sleep(600);
                } catch (Exception e) { Log.e(TAG, "Erreur niveaux " + key, e); }
            }
            logToUI("✅ [TV] Niveaux pivots chargés en mémoire vive.");
        }).start();
    }

    private static String httpGetSimple(String urlStr) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(8000); conn.setReadTimeout(8000);
            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close(); conn.disconnect(); return sb.toString();
            }
            conn.disconnect();
        } catch (Exception e) { Log.e(TAG, "HTTP Erreur: " + e.getMessage()); }
        return null;
    }

    private static class VarianceCalculator {
        private final int period; private final double[] window;
        private int index = 0; private int count = 0; private double sum = 0; private double sumSq = 0;
        private boolean initialized = false;
        public VarianceCalculator(int period) { this.period = period; this.window = new double[period]; }
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
            if (!initialized) return 0;
            double mean = sum / period; return (sumSq / period) - (mean * mean);
        }
    }

    public static Map<String, TVMarketData> getCache() { return Collections.unmodifiableMap(cache); }

    // ── AFFICHAGE ADAPTÉ POUR MAINACTIVITY (4 Indicateurs + Cassures) ──
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
                  .append(" | Amp: ").append(String.format(Locale.US, "%.2f", d.volatilityPercent)).append("%")
                  .append(" | Range: ").append(String.format(Locale.US, "%.0f", d.dailyRangePercent)).append("%")
                  .append(d.isNearHigh ? " 🔺PrèsHaut" : d.isNearLow ? " 🔻PrèsBas" : "")
                  .append(d.variance > 0.001 ? " | Var: " + String.format(Locale.US, "%.4f", d.variance) : "")
                  
                  // ── AJOUT DES VALEURS NUMÉRIQUES DES NIVEAUX PIVOTS ──
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
        sb.append("• Variation (%) = Tendance depuis clôture précédente\n");
        sb.append("• Amplitude (%) = Volatilité journalière (High-Low)\n");
        sb.append("• Range pos (%) = Position actuelle dans la journée (0% = Low, 100% = High)\n");
        sb.append("• Var tick = Volatilité intraday en direct (sur 20 ticks)\n");
        return sb.toString();
    }

    private static void logToUI(String msg) {
        if (MainActivity.instance != null) { MainActivity.instance.addLog(msg); }
    }
}
