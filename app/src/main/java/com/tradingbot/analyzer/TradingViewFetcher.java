package com.tradingbot.analyzer;

import android.content.Context;
import android.util.Log;
import android.content.SharedPreferences;

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
    private static WebSocket activeWs;
    private static final Map<String, String> pendingSymbolResolution = new ConcurrentHashMap<>();
    
    // ── Matrice Complète des 11 Actifs Macro Fonda IOF (Flux Institutionnels Centralisés) ──
    private static final Map<String, String> SYMBOL_MAP = new HashMap<String, String>() {{
        // Indices & Obligataire
        put("SP500",   "SPREADEX:SPX");
        put("NASDAQ",  "SPREADEX:NDX");
        //put("US10Y",   "TVC:US10Y");

        // Matières Premières
        put("GOLD",    "TVC:GOLD");
        put("USOIL",   "TVC:USOIL");

        // Devises (Forex Standardisé ICE/FXCM)EURUSD",  "FX_IDC:EURUSD");
        put("USDJPY",  "FX_IDC:USDJPY");
        put("GBPUSD",  "VANTAGE:GBPUSD");
       // put("AUDUSD",  "FX_IDC:AUDUSD");
        //put("USDCAD",  "FX_IDC:USDCAD");

        // Crypto-actifs
      //  put("BITCOIN", "BINANCE:BTCUSDT");
    }};

    // ── Structure de données unifiée avec les 4 indicateurs + Niv pivots ──
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
        
        // Niveaux Daily (TradingView Natif)
        public final double pdh; 
        public final double pdl; 
        public final boolean brokeAbovePDH; 
        public final boolean brokeBelowPDL; 
        
        // Niveaux Weekly (TradingView Natif)
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
            
            // Calcul automatique des indicateurs 3 et 4 sans risque de division par zéro
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

       /**
 * Injecte les niveaux PDH/PDL/PWH/PWL reçus depuis WebhookServer (TradingView Pine Script)
 * Appelé par WebhookServer.saveLevels() à chaque réception d'un event "levels"
 */
public static void injectKeyLevels(String asset, double pdh, double pdl,
                                    double pwh, double pwl) {
    if (asset == null || asset.isEmpty()) return;
    if (pdh > 0) { pdhCache.put(asset, pdh); alertFiredPDH.remove(asset); }
    if (pdl > 0) { pdlCache.put(asset, pdl); alertFiredPDL.remove(asset); }
    if (pwh > 0) { pwhCache.put(asset, pwh); alertFiredPWH.remove(asset); }
    if (pwl > 0) { pwlCache.put(asset, pwl); alertFiredPWL.remove(asset); }
    Log.i("TradingViewFetcher", "[INJECT] " + asset +
        " PDH=" + String.format(java.util.Locale.US, "%.4f", pdh) +
        " PDL=" + String.format(java.util.Locale.US, "%.4f", pdl) +
        " PWH=" + String.format(java.util.Locale.US, "%.4f", pwh) +
        " PWL=" + String.format(java.util.Locale.US, "%.4f", pwl));
    if (MainActivity.instance != null) {
        MainActivity.instance.addLog("✅ [INJECT] " + asset +
            " PDH=" + String.format(java.util.Locale.US, "%.4f", pdh) +
            " PDL=" + String.format(java.util.Locale.US, "%.4f", pdl) +
            " PWH=" + String.format(java.util.Locale.US, "%.4f", pwh) +
            " PWL=" + String.format(java.util.Locale.US, "%.4f", pwl));
    }
}
    private static String twelveDataKey = "";
    private static final String PREFS_WEEKLY = "TradingBotPrefs";

    // ── Caches et gestionnaires mémoire vive ──
    private static final ConcurrentHashMap<String, TVMarketData> cache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> pdhCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> pdlCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> pwhCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> pwlCache = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, Double> pmhCache = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, Double> pmlCache = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, Boolean> alertFiredPMH = new ConcurrentHashMap<>();
    static final ConcurrentHashMap<String, Boolean> alertFiredPML = new ConcurrentHashMap<>();
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

    public static void rolloverDailyLevels() {
        alertFiredPDH.clear();
        alertFiredPDL.clear();
        alertFiredPWH.clear();
        alertFiredPWL.clear();
        logToUI("🔄 [Anti-Spam] Réinitialisation des déclencheurs d'alertes pivots pour la nouvelle session.");
    }


    public static void fetchAll(OnDataReadyListener listener) {
        if (listener == null) return;
        if (cache.isEmpty()) {
            listener.onError("Le cache temps réel est actuellement vide. Attendez que le WebSocket se connecte.");
        } else {
            listener.onDataReady(Collections.unmodifiableMap(cache));
        }
    }

    public static void start(Context context) {
        if (isRunning.getAndSet(true)) {
            logToUI("⏳ [TV] Déjà en cours.");
            return;
        }
        appContext = context.getApplicationContext();
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_WEEKLY, Context.MODE_PRIVATE);
        twelveDataKey = prefs.getString("macro_api_key", "");
        if (twelveDataKey.isEmpty()) twelveDataKey = prefs.getString("twelve_data_key", "");
        if (twelveDataKey.isEmpty()) {
            SharedPreferences prefs2 = appContext.getSharedPreferences("TradingBot", Context.MODE_PRIVATE);
            twelveDataKey = prefs2.getString("macro_api_key", "");
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
            private String chartSessionId;

            @Override
            public void onOpen(WebSocket ws, Response response) {
                activeWs = ws;
                logToUI("✅ [TV WS] Canal ouvert.");
                connected.set(true);
                isConnecting.set(false);
                cache.clear();
            
                // 1. Session de prix Temps Réel (Quotes)[cite: 1]
                quoteSessionId = "qs_" + UUID.randomUUID().toString().substring(0, 12); //[cite: 1]
                sendMessage(ws, "set_auth_token", new Object[]{"unauthorized_user_token"}); //[cite: 1]
                sendMessage(ws, "quote_create_session", new Object[]{quoteSessionId}); //[cite: 1]
                sendMessage(ws, "quote_set_fields", new Object[]{ //[cite: 1]
                        quoteSessionId,
                        "lp", "chp", "ch", "high_price", "low_price",
                        "open_price", "prev_close_price"
                });
            
                // 2. Session Historique Native (Charts)[cite: 1]
                chartSessionId = "cs_" + UUID.randomUUID().toString().substring(0, 12); //[cite: 1]
                sendMessage(ws, "chart_create_session", new Object[]{chartSessionId, ""}); //[cite: 1]
                
                // 🔥 ALIGNEMENT TIMEZONE : Calcule les bougies quotidiennes sur la clôture officielle de New York
                sendMessage(ws, "switch_timezone", new Object[]{chartSessionId, "America/New_York"});
            
                int idCounter = 1;
                for (String key : SYMBOL_MAP.keySet()) {
                    String ticker = SYMBOL_MAP.get(key); //[cite: 1]
                    varianceCalculators.putIfAbsent(key, new VarianceCalculator(5)); //[cite: 1]
                
                    // Flux de cotations en temps réel[cite: 1]
                    sendMessage(ws, "quote_add_symbols", new Object[]{quoteSessionId, ticker}); //[cite: 1]
                
                    // Flux Graphique Historique (Enrobage JSON requis)[cite: 1]
                    String symId = "sym_" + idCounter; //[cite: 1]
                    pendingSymbolResolution.put(symId, key); //[cite: 1]
                    
                    String symbolJson = "={\"symbol\":\"" + ticker + "\",\"adjustment\":\"splits\"}"; //[cite: 1]
                    sendMessage(ws, "resolve_symbol", new Object[]{chartSessionId, symId, symbolJson}); //[cite: 1]
                    idCounter++;
                }
            
                logToUI("📥 [TV WS] Flux temps réel et sessions pivots TradingView initialisés."); //[cite: 1]
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
                    Log.e(TAG, "[TV WS] Erreur envoi message", e);
                }
            }

            private void processJsonPayload(String payload) {
                try {
                    if (!payload.startsWith("{")) return;
                    JSONObject json = new JSONObject(payload);
                    String m = json.optString("m");
            
                    // ── RÉSOLUTION DE SYMBOLE : CRÉATION DES SÉRIES COMPLÈTES ──
                    if ("symbol_resolved".equals(m)) { //[cite: 1]
                        JSONArray p = json.getJSONArray("p"); //[cite: 1]
                        if (p.length() > 1) { //[cite: 1]
                            String symId = p.getString(1); //[cite: 1]
                            String key = pendingSymbolResolution.remove(symId); //[cite: 1]
                            if (key != null && activeWs != null) { //[cite: 1]
                                sendMessage(activeWs, "create_series", new Object[]{chartSessionId, "ser_d_" + key, "s1", symId, "D", 3}); //[cite: 1]
                                sendMessage(activeWs, "create_series", new Object[]{chartSessionId, "ser_w_" + key, "s1", symId, "W", 3}); //[cite: 1]
                            }
                        }
                        return;
                    }
            
                    if ("symbol_error".equals(m) || "series_error".equals(m) || "critical_error".equals(m) || "protocol_error".equals(m)) { //[cite: 1]
                        Log.e(TAG, "[TV WS] Erreur serveur (" + m + ") : " + payload); //[cite: 1]
                        return;
                    }
            
                    // ── EXTRACTION INTELLIGENTE DU HIGH / LOW PRECEDENT (DÉTECTION WEEK-END INCLUSE) ──
                    if ("timescale_update".equals(m)) { //[cite: 1]
                        JSONArray p = json.getJSONArray("p"); //[cite: 1]
                        if (p.length() > 1) { //[cite: 1]
                            JSONObject seriesData = p.getJSONObject(1); //[cite: 1]
                            java.util.Iterator<String> keys = seriesData.keys(); //[cite: 1]
            
                            while (keys.hasNext()) { //[cite: 1]
                                String seriesId = keys.next(); //[cite: 1]
                                JSONObject obj = seriesData.getJSONObject(seriesId); //[cite: 1]
            
                                if (obj.has("s")) { //[cite: 1]
                                    JSONArray sArr = obj.getJSONArray("s"); //[cite: 1]
                                    if (sArr.length() >= 1) {
                                        JSONObject targetBar = null;
                                        JSONObject lastBar = sArr.getJSONObject(sArr.length() - 1);
                                        
                                        if (lastBar.has("v")) {
                                            JSONArray lastV = lastBar.getJSONArray("v");
                                            long lastBarTsSec = lastV.getLong(0);
                                            long nowSec = System.currentTimeMillis() / 1000;
                                            
                                            // Si la dernière bougie date de moins de 24h, la journée en cours est ouverte : on extrait l'avant-dernière (complète)
                                            if ((nowSec - lastBarTsSec) < 86400 && sArr.length() >= 2) {
                                                targetBar = sArr.getJSONObject(sArr.length() - 2);
                                            } else {
                                                // Le marché est fermé (Week-end/Férié) : la dernière bougie du flux est la veille fermée
                                                targetBar = lastBar;
                                            }
                                        }
                                        
                                        if (targetBar != null && targetBar.has("v")) { //[cite: 1]
                                            JSONArray vArr = targetBar.getJSONArray("v"); //[cite: 1]
                                            if (vArr.length() >= 4) { //[cite: 1]
                                                double historicalHigh = vArr.getDouble(2); //[cite: 1]
                                                double historicalLow  = vArr.getDouble(3); //[cite: 1]
            
                                                if (seriesId.startsWith("ser_d_")) { //[cite: 1]
                                                    String key = seriesId.substring(6); //[cite: 1]
                                                    pdhCache.put(key, historicalHigh); //[cite: 1]
                                                    pdlCache.put(key, historicalLow); //[cite: 1]
                                                    saveLevelToStorage(key, "pdh", historicalHigh);
                                                    saveLevelToStorage(key, "pdl", historicalLow);
                                                } else if (seriesId.startsWith("ser_w_")) { //[cite: 1]
                                                    String key = seriesId.substring(6); //[cite: 1]
                                                    pwhCache.put(key, historicalHigh); //[cite: 1]
                                                    pwlCache.put(key, historicalLow); //[cite: 1]
                                                    saveLevelToStorage(key, "pwh", historicalHigh);
                                                    saveLevelToStorage(key, "pwl", historicalLow);
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
                    if ("qsd".equals(m)) { //[cite: 1]
                        JSONArray p = json.getJSONArray("p"); //[cite: 1]
                        if (p.length() > 1) { //[cite: 1]
                            JSONObject quote = p.getJSONObject(1); //[cite: 1]
                            String ticker = quote.optString("n"); //[cite: 1]
                            JSONObject v = quote.optJSONObject("v"); //[cite: 1]
            
                            if (v != null && v.has("lp")) { //[cite: 1]
                                String key = getKeyFromTicker(ticker); //[cite: 1]
                                if (key != null) { //[cite: 1]
                                    double price = v.optDouble("lp", 0); //[cite: 1]
                                    double change = v.optDouble("chp", 0); //[cite: 1]
            
                                    TVMarketData existing = cache.get(key); //[cite: 1]
                                    double high      = v.optDouble("high_price",       existing != null && existing.high > 0      ? existing.high      : price); //[cite: 1]
                                    double low       = v.optDouble("low_price",        existing != null && existing.low  > 0      ? existing.low       : price); //[cite: 1]
                                    double open      = v.optDouble("open_price",       existing != null && existing.open > 0      ? existing.open      : price); //[cite: 1]
                                    double prevClose = v.optDouble("prev_close_price", existing != null && existing.prevClose > 0 ? existing.prevClose : price); //[cite: 1]
            
                                    if (price > high) high = price; //[cite: 1]
                                    if (price < low) low = price; //[cite: 1]
            
                                    VarianceCalculator calc = varianceCalculators.get(key); //[cite: 1]
                                    double variance = 0.0; //[cite: 1]
                                    if (calc != null) { //[cite: 1]
                                        calc.addPrice(price); //[cite: 1]
                                        variance = calc.getVariance(); //[cite: 1]
                                    }
            
                                    double pdh = pdhCache.getOrDefault(key, 0.0); //[cite: 1]
                                    double pdl = pdlCache.getOrDefault(key, 0.0); //[cite: 1]
                                    double pwh = pwhCache.getOrDefault(key, 0.0); //[cite: 1]
                                    double pwl = pwlCache.getOrDefault(key, 0.0); //[cite: 1]
            
                                    TVMarketData newData = new TVMarketData( //[cite: 1]
                                            key, price, change, high, low, open, prevClose, //[cite: 1]
                                            variance, 0.0, pdh, pdl, pwh, pwl, //[cite: 1]
                                            System.currentTimeMillis() //[cite: 1]
                                    ); //[cite: 1]
                                    cache.put(key, newData); //[cite: 1]
            
                                    checkAndAlert(key, newData); //[cite: 1]
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[TV WS] Erreur parse JSON", e); //[cite: 1]
                }
            }

            private String getKeyFromTicker(String ticker) {
                for (Map.Entry<String, String> entry : SYMBOL_MAP.entrySet()) {
                    if (ticker.equals(entry.getValue())) { //[cite: 1]
                        return entry.getKey(); //[cite: 1]
                    }
                }
                return null;
            }

            private void checkAndAlert(String key, TVMarketData data) {
                if (appContext == null) return;
                long now = System.currentTimeMillis();

                Long last = lastAlertTime.get(key); //[cite: 1]
                if (last == null || (now - last) > ALERT_COOLDOWN_MS) { //[cite: 1]
                    if (data.isNearHigh || data.isNearLow) { //[cite: 1]
                        StringBuilder sb = new StringBuilder();
                        
                        if (data.isNearHigh) { //[cite: 1]
                            sb.append("📊 *").append(key).append("* 🔺 Approche du *plus haut du jour*\n\n"); //[cite: 1]
                        } else {
                            sb.append("📊 *").append(key).append("* 🔻 Approche du *plus bas du jour*\n\n"); //[cite: 1]
                        }
                        
                        sb.append("🔹 *PRIX ACTUEL* : `").append(String.format(Locale.US, "%.4f", data.price)).append("`\n\n"); //[cite: 1]
                        sb.append("📈 *LES 4 INDICATEURS TEMPS RÉEL :*\n"); //[cite: 1]
                        sb.append("• 1. Variation : `").append(String.format(Locale.US, "%+.2f", data.changePercent)).append("%` (vs Clôture)\n"); //[cite: 1]
                        sb.append("• 2. Volatilité Tick (20t) : `").append(String.format(Locale.US, "%.6f", data.variance)).append("` (Variance)\n"); //[cite: 1]
                        sb.append("• 3. Amplitude Daily : `").append(String.format(Locale.US, "%.2f", data.volatilityPercent)).append("%` (High-Low)\n"); //[cite: 1]
                        sb.append("• 4. Position Range : `").append(String.format(Locale.US, "%.1f", data.dailyRangePercent)).append("%` (0=Bas, 100=Haut)\n\n"); //[cite: 1]
                        
                        sb.append("🏛️ *NIVEAUX PIVOTS (Natifs TradingView) :*\n"); //[cite: 1]
                        if (data.pdh > 0 || data.pdl > 0) { //[cite: 1]
                            sb.append("• *Daily* : PDH = `").append(String.format(Locale.US, "%.4f", data.pdh)) //[cite: 1]
                              .append("` | PDL = `").append(String.format(Locale.US, "%.4f", data.pdl)).append("`\n"); //[cite: 1]
                        } else {
                            sb.append("• *Daily* : ⚠️ Chargement des séries TV en cours...\n"); //[cite: 1]
                        }
                        
                        if (data.pwh > 0 || data.pwl > 0) { //[cite: 1]
                            sb.append("• *Weekly* : PWH = `").append(String.format(Locale.US, "%.4f", data.pwh)) //[cite: 1]
                              .append("` | PWL = `").append(String.format(Locale.US, "%.4f", data.pwl)).append("`\n"); //[cite: 1]
                        } else {
                            sb.append("• *Weekly* : ⚠️ Chargement des séries TV en cours...\n"); //[cite: 1]
                        }

                        if (data.brokeAbovePDH || data.brokeBelowPDL || data.brokeAbovePWH || data.brokeBelowPWL) { //[cite: 1]
                            sb.append("\n⚡ *Statut de cassure :*"); //[cite: 1]
                            if (data.brokeAbovePDH) sb.append(" 🔺[Breakout PDH]"); //[cite: 1]
                            if (data.brokeBelowPDL) sb.append(" 🔻[Breakdown PDL]"); //[cite: 1]
                            if (data.brokeAbovePWH) sb.append(" 🚀[Breakout PWH]"); //[cite: 1]
                            if (data.brokeBelowPWL) sb.append(" 🔥[Breakdown PWL]"); //[cite: 1]
                            sb.append("\n"); //[cite: 1]
                        }

                        NotificationService.sendTelegramSecure(sb.toString(), appContext); //[cite: 1]
                        lastAlertTime.put(key, now); //[cite: 1]
                    }
                }

                if (data.brokeAbovePDH && !Boolean.TRUE.equals(alertFiredPDH.get(key))) { //[cite: 1]
                    alertFiredPDH.put(key, true); //[cite: 1]
                    NotificationService.sendTelegramSecure("🔺 *" + key + "* — Cassure du *Previous Day High* (`" + data.price + "`)", appContext); //[cite: 1]
                }
                if (data.brokeBelowPDL && !Boolean.TRUE.equals(alertFiredPDL.get(key))) { //[cite: 1]
                    alertFiredPDL.put(key, true); //[cite: 1]
                    NotificationService.sendTelegramSecure("🔻 *" + key + "* — Cassure du *Previous Day Low* (`" + data.price + "`)", appContext); //[cite: 1]
                }
                if (data.brokeAbovePWH && !Boolean.TRUE.equals(alertFiredPWH.get(key))) { //[cite: 1]
                    alertFiredPWH.put(key, true); //[cite: 1]
                    NotificationService.sendTelegramSecure("🚀 *" + key + "* — Breakout *Previous Week High* (`" + data.price + "`) !", appContext); //[cite: 1]
                }
                if (data.brokeBelowPWL && !Boolean.TRUE.equals(alertFiredPWL.get(key))) { //[cite: 1]
                    alertFiredPWL.put(key, true); //[cite: 1]
                    NotificationService.sendTelegramSecure("🔥 *" + key + "* — Breakdown *Previous Week Low* (`" + data.price + "`) !", appContext); //[cite: 1]
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) { handleDisconnection(); }
            @Override
            public void onClosed(WebSocket ws, int code, String reason) { handleDisconnection(); }

            private void handleDisconnection() {
                connected.set(false); //[cite: 1]
                isConnecting.set(false); //[cite: 1]
                cache.clear(); //[cite: 1]
                logToUI("🔴 [TV WS] Déconnecté. Reconnexion dans 5s..."); //[cite: 1]
                if (isRunning.get()) { //[cite: 1]
                    new Thread(() -> {
                        try { TimeUnit.SECONDS.sleep(5); } catch (InterruptedException ignored) {}
                        connectWebSocket();
                    }).start();
                }
            }
        });
    }

public static void fetchPreviousLevels() {
    loadLevelsFromStorage();
    if (!pdhCache.isEmpty()) {
        logToUI("✅ [TV Pivots] " + pdhCache.size() + " niveaux restaurés depuis cache.");
        return;
    }
    fetchFromPolygon();
}
    /**
 * Effectue une requête HTTP GET synchrone standard à l'aide du client OkHttp existant.
 * Doit impérativement être appelé depuis un thread d'arrière-plan.
 */
private static String httpGetSimple(String url) {
    if (client == null) {
        // Sécurité au cas où la méthode serait appelée hors du cycle de démarrage standard
        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    Request request = new Request.Builder()
            .url(url)
            .build();

    try (Response response = client.newCall(request).execute()) {
        if (response.isSuccessful() && response.body() != null) {
            return response.body().string();
        } else {
            Log.e(TAG, "[HTTP] Échec de la requête. Code: " + response.code() + " pour l'URL: " + url);
        }
    } catch (Exception e) {
        Log.e(TAG, "[HTTP] Erreur lors de l'exécution de httpGetSimple: " + e.getMessage(), e);
    }
    return null;
}
 private static void fetchFromPolygon() {
    if (appContext == null) return;
    
    String tempApiKey = appContext.getSharedPreferences("TradingBotPrefs", Context.MODE_PRIVATE)
        .getString("macro_api_key", "");
    if (tempApiKey.isEmpty()) {
        tempApiKey = appContext.getSharedPreferences("TradingBotPrefs", Context.MODE_PRIVATE)
            .getString("twelve_data_key", "");
    }
    if (tempApiKey.isEmpty()) {
        logToUI("⚠️ [Polygon] Clé absente — saisir la clé Polygon dans le champ TwelveData.");
        return;
    }
    
    final String apiKey = tempApiKey;
    logToUI("🔄 [Polygon] Chargement PDH/PDL/PWH/PWL...");

    String[][] polygonMap = {
        {"GOLD",    "C:XAUUSD"},
        {"GBPUSD",  "C:GBPUSD"},
        {"USDJPY",  "C:USDJPY"},
        {"NASDAQ",  "QQQ"},
        {"SP500",   "SPY"},
        {"USOIL",   "C:USOILUSD"},
    };

    new Thread(() -> {
        int count = 0;

        // 🛠️ FIX DATE WEEKLY : On élargit la fenêtre à 30 jours pour charger assez de bougies
        java.util.Calendar calW = java.util.Calendar.getInstance();
        String dateToWeek = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(calW.getTime());
        calW.add(java.util.Calendar.DAY_OF_YEAR, -30); 
        String dateFromWeek = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(calW.getTime());

        // Configuration fuseau UTC+3 pour le calcul journalier
        java.util.TimeZone tzMada = java.util.TimeZone.getTimeZone("GMT+3");
        java.util.Calendar calMada = java.util.Calendar.getInstance(tzMada);
        calMada.set(java.util.Calendar.HOUR_OF_DAY, 0);
        calMada.set(java.util.Calendar.MINUTE, 0);
        calMada.set(java.util.Calendar.SECOND, 0);
        calMada.set(java.util.Calendar.MILLISECOND, 0);
        long localTodayMidnightUTC = calMada.getTimeInMillis();
        long targetStartUTC = localTodayMidnightUTC - (24 * 60 * 60 * 1000);
        long targetEndUTC   = localTodayMidnightUTC - 1;

        java.text.SimpleDateFormat polySdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        polySdf.setTimeZone(tzMada);
        calMada.add(java.util.Calendar.DAY_OF_YEAR, -3);
        String dateFromH = polySdf.format(calMada.getTime());
        String dateToH   = polySdf.format(new java.util.Date());

        for (int i = 0; i < polygonMap.length; i++) {
            String asset  = polygonMap[i][0];
            String ticker = polygonMap[i][1];
            try {
                // ── 1. PDH/PDL Dynamique (UTC+3) ──
                String urlPrev = "https://api.polygon.io/v2/aggs/ticker/" + ticker 
                    + "/range/1/hour/" + dateFromH + "/" + dateToH 
                    + "?adjusted=true&extended_hours=true&sort=asc&apiKey=" + apiKey;

                String respPrev = httpGetSimple(urlPrev);
                if (respPrev != null) {
                    JSONObject json = new JSONObject(respPrev);
                    JSONArray results = json.optJSONArray("results");
                    if (results != null && results.length() > 0) {
                        double maxHighLocal = Double.MIN_VALUE;
                        double minLowLocal  = Double.MAX_VALUE;
                        boolean hashValidData = false;

                        for (int j = 0; j < results.length(); j++) {
                            JSONObject candle = results.getJSONObject(j);
                            long t = candle.optLong("t", 0);
                            if (t >= targetStartUTC && t <= targetEndUTC) {
                                double candleHigh = candle.optDouble("h", 0);
                                double candleLow  = candle.optDouble("l", 0);
                                if (candleHigh > maxHighLocal) maxHighLocal = candleHigh;
                                if (candleLow < minLowLocal && candleLow > 0)  minLowLocal = candleLow;
                                hashValidData = true;
                            }
                        }

                        if (hashValidData) {
                            pdhCache.put(asset, maxHighLocal);
                            pdlCache.put(asset, minLowLocal);
                            saveLevelToStorage(asset, "pdh", maxHighLocal);
                            saveLevelToStorage(asset, "pdl", minLowLocal);
                            alertFiredPDH.remove(asset);
                            alertFiredPDL.remove(asset);
                            count++;
                            logToUI("🎯 [DAILY UTC+3] " + asset + " H=" + String.format(Locale.US, "%.4f", maxHighLocal) + " L=" + String.format(Locale.US, "%.4f", minLowLocal));
                        }
                    }
                }

                // ⏳ Pause anti-429
                Thread.sleep(12500);

                // ── 2. PWH/PWL — Corrigé avec historique large de 30 jours ──
                String urlWeek = "https://api.polygon.io/v2/aggs/ticker/"
                    + ticker + "/range/1/week/"
                    + dateFromWeek + "/" + dateToWeek
                    + "?adjusted=true&sort=desc&limit=2&apiKey=" + apiKey;
                
                String respWeek = httpGetSimple(urlWeek);
                if (respWeek != null) {
                    JSONObject json = new JSONObject(respWeek);
                    JSONArray results = json.optJSONArray("results");
                    
                    // Désormais, length >= 2 sera TOUJOURS vrai car on demande un historique de 30 jours
                    if (results != null && results.length() >= 2) {
                        JSONObject prevWeek = results.getJSONObject(1); // Index 1 = Semaine précédente fermée
                        double pwh = prevWeek.optDouble("h", 0);
                        double pwl = prevWeek.optDouble("l", 0);
                        
                        if (pwh > 0) {
                            pwhCache.put(asset, pwh);
                            pwlCache.put(asset, pwl);
                            saveLevelToStorage(asset, "pwh", pwh);
                            saveLevelToStorage(asset, "pwl", pwl);
                            alertFiredPWH.remove(asset);
                            alertFiredPWL.remove(asset);
                            logToUI("✅ [WEEKLY] " + asset + " H=" + String.format(Locale.US, "%.4f", pwh) + " L=" + String.format(Locale.US, "%.4f", pwl));
                        }
                    }
                }
                
                // ⏳ Pause anti-429 avant le prochain actif
                if (i < polygonMap.length - 1) {
                    Thread.sleep(12500);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "[Polygon] Erreur " + asset + " : " + e.getMessage());
            }
        }
        logToUI("✅ [Polygon] " + count + " actifs chargés (Pivots Daily & Weekly synchronisés).");
    }).start();
    }
    
    private static void saveLevelToStorage(String key, String type, double value) {
        if (appContext == null || value <= 0) return;
        try {
            SharedPreferences.Editor editor = appContext.getSharedPreferences(PREFS_WEEKLY, Context.MODE_PRIVATE).edit();
            editor.putString(type + "_" + key, String.valueOf(value));
            editor.apply();
        } catch (Exception e) {
            Log.e(TAG, "Erreur écriture SharedPreferences pour " + key, e);
        }
    }

    private static void loadLevelsFromStorage() {
        if (appContext == null) return;
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_WEEKLY, Context.MODE_PRIVATE); //[cite: 1]
        for (String key : SYMBOL_MAP.keySet()) { //[cite: 1]
            try {
                double savedPdh = Double.parseDouble(prefs.getString("pdh_" + key, "0")); //[cite: 1]
                double savedPdl = Double.parseDouble(prefs.getString("pdl_" + key, "0")); //[cite: 1]
                double savedPwh = Double.parseDouble(prefs.getString("pwh_" + key, "0")); //[cite: 1]
                double savedPwl = Double.parseDouble(prefs.getString("pwl_" + key, "0")); //[cite: 1]

                if (savedPdh > 0) pdhCache.put(key, savedPdh); //[cite: 1]
                if (savedPdl > 0) pdlCache.put(key, savedPdl); //[cite: 1]
                if (savedPwh > 0) pwhCache.put(key, savedPwh); //[cite: 1]
                if (savedPwl > 0) pwlCache.put(key, savedPwl); //[cite: 1]
            } catch (NumberFormatException ignored) {}
        }
    }

    private static class VarianceCalculator {
        private final int period; private final double[] window; //[cite: 1]
        private int index = 0; private int count = 0; private double sum = 0; private double sumSq = 0; //[cite: 1]
        public VarianceCalculator(int period) { this.period = period; this.window = new double[period]; } //[cite: 1]
        public synchronized void addPrice(double price) {
            if (count < period) { //[cite: 1]
                window[count] = price; sum += price; sumSq += price * price; count++; //[cite: 1]
            } else {
                double old = window[index]; sum -= old; sumSq -= old * old; //[cite: 1]
                window[index] = price; sum += price; sumSq += price * price; //[cite: 1]
                index = (index + 1) % period; //[cite: 1]
            }
        }
        public synchronized double getVariance() {
            if (count < 2) return 0; //[cite: 1]
            int n = Math.min(count, period); //[cite: 1]
            double mean = sum / n; return (sumSq / n) - (mean * mean); //[cite: 1]
        }
    }

    public static Map<String, TVMarketData> getCache() { return Collections.unmodifiableMap(cache); } //[cite: 1]

    public static String buildContexteMacroGlobal(Context ctx) {
        if (cache.isEmpty()) return ""; //[cite: 1]
        StringBuilder sb = new StringBuilder();
        sb.append("═══ CONTEXTE MACRO GLOBAL TEMPS RÉEL ═══\n"); //[cite: 1]

        for (String key : SYMBOL_MAP.keySet()) { //[cite: 1]
            TVMarketData d = cache.get(key); //[cite: 1]
            if (d != null) { //[cite: 1]
                String formatPrice;
                if (key.equals("GBPUSD") || key.equals("EURUSD") || key.equals("AUDUSD") || key.equals("USDCAD")) { formatPrice = "%.5f"; } //[cite: 1]
                else if (key.equals("USDJPY")) { formatPrice = "%.3f"; } //[cite: 1]
                else if (key.equals("NASDAQ") || key.equals("SP500") || key.equals("GOLD") || key.equals("USOIL") || key.equals("BITCOIN")) { formatPrice = "%.2f"; } //[cite: 1]
                else { formatPrice = "%.4f"; } //[cite: 1]

                sb.append("• ").append(key).append(" : ") //[cite: 1]
                  .append(String.format(Locale.US, formatPrice, d.price)) //[cite: 1]
                  .append(" (").append(String.format(Locale.US, "%+.2f", d.changePercent)).append("%)") //[cite: 1]
                  .append(" | Amp: ").append(String.format(Locale.US, "%.2f", d.volatilityPercent)).append("%") //[cite: 1]
                  .append(" | Range: ").append(String.format(Locale.US, "%.0f", d.dailyRangePercent)).append("%") //[cite: 1]
                  .append(d.isNearHigh ? " 🔺PrèsHaut" : d.isNearLow ? " 🔻PrèsBas" : "") //[cite: 1]
                  .append(" | Var: ").append(String.format(Locale.US, "%.6f", d.variance)) //[cite: 1]
                  .append(d.pdh > 0 ? " | PDH=" + String.format(Locale.US, formatPrice, d.pdh) : "") //[cite: 1]
                  .append(d.pdl > 0 ? " | PDL=" + String.format(Locale.US, formatPrice, d.pdl) : "") //[cite: 1]
                  .append(d.brokeAbovePDH ? " 🔺Breakout PDH" : d.brokeBelowPDL ? " 🔻Breakdown PDL" : "") //[cite: 1]
                  .append(d.pwh > 0 ? " | PWH=" + String.format(Locale.US, formatPrice, d.pwh) : "") //[cite: 1]
                  .append(d.pwl > 0 ? " | PWL=" + String.format(Locale.US, formatPrice, d.pwl) : "") //[cite: 1]
                  .append(d.brokeAbovePWH ? " 🚀Breakout PWH" : d.brokeBelowPWL ? " 🔥Breakdown PWL" : "") //[cite: 1]
                  .append("\n"); //[cite: 1]
            }
        }
        String regimeFed = ctx.getSharedPreferences("TradingBotPrefs", Context.MODE_PRIVATE)
                .getString("fed_regime", "PAUSE HAWKISH | Warsh | Taux 3.50-3.75% | CPI 4.2%"); //[cite: 1]
        sb.append("\n🏦 RÉGIME FED : ").append(regimeFed).append("\n"); //[cite: 1]
        return sb.toString(); //[cite: 1]
    }

    private static void logToUI(String msg) {
        if (MainActivity.instance != null) { MainActivity.instance.addLog(msg); } //[cite: 1]
    }
}
