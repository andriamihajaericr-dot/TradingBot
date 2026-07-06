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
    private static final Map<String, String> pendingSymbolChartSession = new ConcurrentHashMap<>();
    
    // ── Matrice des Actifs Fonda IOF (Flux Alignés sur vos Graphiques) ──
    private static final Map<String, String> SYMBOL_MAP = new HashMap<String, String>() {{
        // Indices
        put("NASDAQ",  "SPREADEX:NDX");
        put("SP500",   "SPREADEX:SPX");

        // Matières Premières
        put("GOLD",    "TVC:GOLD");
        put("USOIL",   "TVC:USOIL");

        // Devises (Forex)
        put("USDJPY",  "FX_IDC:USDJPY");
        put("GBPUSD",  "VANTAGE:GBPUSD");
        put("EURUSD",  "VANTAGE:EURUSD");
    }};

    public static class TVMarketData {
        public final String symbol;
        public final double price;
        public final double changePercent;      // 1. Tendance (vs Clôture)
        public final double high;
        public final double low;
        public final double open;
        public final double prevClose;
        public final double variance;           // 2. Volatilité intraday (20 ticks)
        public final double volatilityPercent;  // 3. Amplitude daily (High-Low %)
        public final double dailyRangePercent;  // 4. Position dans le range (0-100%)
        public final boolean isNearHigh;
        public final boolean isNearLow;
        
        public final double ma200;
        public final boolean aboveMA200;
        
        // Niveaux Daily Clôturés (TradingView Charts)
        public final double pdh; 
        public final double pdl; 
        public final boolean brokeAbovePDH; 
        public final boolean brokeBelowPDL; 
        
        // Niveaux Weekly Clôturés (TradingView Charts)
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
     public static void injectKeyLevels(String asset, double dh, double dl, double wh, double wl) {
    // On laisse la méthode vide ou on log simplement.
    // De cette façon, le WebhookServer compile, mais n'écrase pas le flux WebSocket natif.
    Log.d("TradingViewFetcher", "📥 [Webhook] Injection ignorée pour " + asset + " (Priorité absolue au WebSocket TV).");
     }
    private static final String PREFS_WEEKLY = "TradingBotPrefs";

    private static final ConcurrentHashMap<String, TVMarketData> cache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> pdhCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> pdlCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> pwhCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> pwlCache = new ConcurrentHashMap<>();
    
    private static final ConcurrentHashMap<String, Boolean> alertFiredPDH = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> alertFiredPDL = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> alertFiredPWH = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> alertFiredPWL = new ConcurrentHashMap<>();
    
    private static final AtomicBoolean isRunning = new AtomicBoolean(false);
    private static final ConcurrentHashMap<String, VarianceCalculator> varianceCalculators = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> lastAlertTime = new ConcurrentHashMap<>();
    private static final long ALERT_COOLDOWN_MS = 5 * 60 * 1000;

    private static final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private static final AtomicBoolean connected = new AtomicBoolean(false);
    private static Context appContext;
    private static OkHttpClient client;
    private static WebSocket webSocket;

    public static void rolloverDailyLevels() {
        alertFiredPDH.clear();
        alertFiredPDL.clear();
        alertFiredPWH.clear();
        alertFiredPWL.clear();
        logToUI("🔄 [Anti-Spam] Réinitialisation des déclencheurs d'alertes pivots pour la nouvelle session.");
    }

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
        
        logToUI("📡 [TV] Démarrage du pipeline 100% TradingView Exclusif.");
        loadLevelsFromStorage(); 
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
                activeWs = ws;
                logToUI("✅ [TV WS] Canal connecté.");
                connected.set(true);
                isConnecting.set(false);
                cache.clear();
                pendingSymbolResolution.clear();
                pendingSymbolChartSession.clear();
            
                // 1. Initialisation Session Temps Réel (Quotes)
                quoteSessionId = "qs_" + UUID.randomUUID().toString().substring(0, 12);
                sendMessage(ws, "set_auth_token", new Object[]{"unauthorized_user_token"});
                sendMessage(ws, "quote_create_session", new Object[]{quoteSessionId});
                sendMessage(ws, "quote_set_fields", new Object[]{
                        quoteSessionId, "lp", "chp", "ch", "high_price", "low_price", "open_price", "prev_close_price"
                });
            
                // 2. Initialisation Sessions Graphiques Dédiées (Charts) pour Extraire les Pivots
                for (String key : SYMBOL_MAP.keySet()) {
                    String ticker = SYMBOL_MAP.get(key);
                    varianceCalculators.putIfAbsent(key, new VarianceCalculator(5));
                
                    // Liaison flux temps réel principal
                    sendMessage(ws, "quote_add_symbols", new Object[]{quoteSessionId, ticker});
                
                    // ── Session Graphique Daily ──
                    String chartSessionIdD = "cs_d_" + key + "_" + UUID.randomUUID().toString().substring(0, 8);
                    sendMessage(ws, "chart_create_session", new Object[]{chartSessionIdD, ""});
                    sendMessage(ws, "switch_timezone", new Object[]{chartSessionIdD, "America/New_York"});

                    String symIdD = "sid_d_" + key; 
                    pendingSymbolResolution.put(symIdD, key);
                    pendingSymbolChartSession.put(symIdD, chartSessionIdD);
                    sendMessage(ws, "resolve_symbol", new Object[]{chartSessionIdD, symIdD, "={\"symbol\":\"" + ticker + "\",\"adjustment\":\"splits\"}"});

                    // ── Session Graphique Weekly ──
                    String chartSessionIdW = "cs_w_" + key + "_" + UUID.randomUUID().toString().substring(0, 8);
                    sendMessage(ws, "chart_create_session", new Object[]{chartSessionIdW, ""});
                    sendMessage(ws, "switch_timezone", new Object[]{chartSessionIdW, "America/New_York"});

                    String symIdW = "sid_w_" + key; 
                    pendingSymbolResolution.put(symIdW, key);
                    pendingSymbolChartSession.put(symIdW, chartSessionIdW);
                    sendMessage(ws, "resolve_symbol", new Object[]{chartSessionIdW, symIdW, "={\"symbol\":\"" + ticker + "\",\"adjustment\":\"splits\"}"});
                }
                logToUI("📥 [TV WS] Pipeline configuré à 100%. Synchronisation des niveaux pivots en cours...");
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
            
                    // ── LIAISON ET INSTANCIATION DES SÉRIES TEMPORELLES ──
                    if ("symbol_resolved".equals(m)) {
                        JSONArray p = json.getJSONArray("p");
                        if (p.length() > 1) {
                            String symId = p.getString(1);
                            String key = pendingSymbolResolution.remove(symId);
                            String assetChartSessionId = pendingSymbolChartSession.remove(symId);
                            
                            if (key != null && activeWs != null && assetChartSessionId != null) {
                                    if (symId.startsWith("sid_d_")) {
                                        sendMessage(activeWs, "create_series", new Object[]{assetChartSessionId, "ser_d_" + key, "s1", symId, "D", 3});
                                    } else if (symId.startsWith("sid_w_")) {
                                        sendMessage(activeWs, "create_series", new Object[]{assetChartSessionId, "ser_w_" + key, "s1", symId, "W", 3});
                                    }
                            }
                        }
                        return;
                    }
            
                    if ("symbol_error".equals(m) || "series_error".equals(m) || "critical_error".equals(m)) {
                        return; 
                    }
            
                    // ── PARSEUR DE PIVOTS SÉCURISÉ (ANTI-DST & ANTI-DOUBLON LUNDI) ──
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
                                    if (sArr.length() >= 1) {
                                        JSONObject targetBar = null;
                                        
                                        // Traitement Daily Sécurisé Calendaire (New York DST)
                                        if (seriesId.startsWith("ser_d_")) {
                                            String key = seriesId.substring(6);
                                            JSONObject lastBar = sArr.getJSONObject(sArr.length() - 1);
                                            if (lastBar.has("v")) {
                                                long lastBarTsSec = lastBar.getJSONArray("v").getLong(0);
                                                
                                                java.util.Calendar barCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/New_York"));
                                                barCal.setTimeInMillis(lastBarTsSec * 1000);
                                                java.util.Calendar todayCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/New_York"));
                                                
                                                if (barCal.get(java.util.Calendar.DAY_OF_YEAR) == todayCal.get(java.util.Calendar.DAY_OF_YEAR) && sArr.length() >= 2) {
                                                    targetBar = sArr.getJSONObject(sArr.length() - 2);
                                                } else {
                                                    targetBar = lastBar;
                                                }
                                            }
                                        } 
                                        // Traitement Weekly Sécurisé Calendaire (Filtre dynamique de la semaine)
                                        else if (seriesId.startsWith("ser_w_")) {
                                            String key = seriesId.substring(6);
                                            JSONObject lastBar = sArr.getJSONObject(sArr.length() - 1);
                                            if (lastBar.has("v") && sArr.length() >= 2) {
                                                long lastBarTsSec = lastBar.getJSONArray("v").getLong(0);
                                                
                                                java.util.Calendar barCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/New_York"));
                                                barCal.setTimeInMillis(lastBarTsSec * 1000);
                                                int barWeek = barCal.get(java.util.Calendar.WEEK_OF_YEAR);
                                                
                                                java.util.Calendar currentCal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("America/New_York"));
                                                int currentWeek = currentCal.get(java.util.Calendar.WEEK_OF_YEAR);
                                                
                                                if (barWeek == currentWeek) {
                                                    targetBar = sArr.getJSONObject(sArr.length() - 2);
                                                } else {
                                                    targetBar = lastBar;
                                                }
                                            } else {
                                                targetBar = lastBar;
                                            }
                                        }
                                        
                                        if (targetBar != null && targetBar.has("v")) {
                                            JSONArray vArr = targetBar.getJSONArray("v");
                                            if (vArr.length() >= 4) {
                                                double historicalHigh = vArr.getDouble(2);
                                                double historicalLow  = vArr.getDouble(3);
            
                                                if (seriesId.startsWith("ser_d_")) {
                                                    String key = seriesId.substring(6);
                                                    pdhCache.put(key, historicalHigh);
                                                    pdlCache.put(key, historicalLow);
                                                    saveLevelToStorage(key, "pdh", historicalHigh);
                                                    saveLevelToStorage(key, "pdl", historicalLow);
                                                } else if (seriesId.startsWith("ser_w_")) {
                                                    String key = seriesId.substring(6);
                                                    pwhCache.put(key, historicalHigh);
                                                    pwlCache.put(key, historicalLow);
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
            
                    // ── FLUX TRADINGVIEW TEMPS RÉEL (QUOTES) ──
                    if ("qsd".equals(m)) {
                        JSONArray p = json.getJSONArray("p");
                        if (p.length() > 1) {
                            JSONObject quote = p.getJSONObject(1);
                            String ticker = quote.optString("n");
                            JSONObject v = quote.optJSONObject("v");
            
                            if (v != null && v.has("lp")) {
                                String key = getKeyFromTicker(ticker);
                                if (key != null) {
                                    double price = v.optDouble("lp", 0);
                                    double change = v.optDouble("chp", 0);
            
                                    TVMarketData existing = cache.get(key);
                                    double high      = v.optDouble("high_price",       existing != null && existing.high > 0      ? existing.high      : price);
                                    double low       = v.optDouble("low_price",        existing != null && existing.low  > 0      ? existing.low       : price);
                                    double open      = v.optDouble("open_price",       existing != null && existing.open > 0      ? existing.open      : price);
                                    double prevClose = v.optDouble("prev_close_price", existing != null && existing.prevClose > 0 ? existing.prevClose : price);
            
                                    if (price > high) high = price;
                                    if (price < low) low = price;
            
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
            
                                    TVMarketData newData = new TVMarketData(
                                            key, price, change, high, low, open, prevClose,
                                            variance, 0.0, pdh, pdl, pwh, pwl,
                                            System.currentTimeMillis()
                                    );
                                    cache.put(key, newData);
            
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

                Long last = lastAlertTime.get(key);
                if (last == null || (now - last) > ALERT_COOLDOWN_MS) {
                    if (data.isNearHigh || data.isNearLow) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("📊 *").append(key).append(data.isNearHigh ? "* 🔺 Approche du *plus haut du jour*\n\n" : "* 🔻 Approche du *plus bas du jour*\n\n");
                        sb.append("🔹 *PRIX ACTUEL* : `").append(String.format(Locale.US, "%.4f", data.price)).append("`\n\n");
                        sb.append("📈 *LES 4 INDICATEURS TEMPS RÉEL :*\n");
                        sb.append("• 1. Variation : `").append(String.format(Locale.US, "%+.2f", data.changePercent)).append("%` (vs Clôture)\n");
                        sb.append("• 2. Volatilité Tick (20t) : `").append(String.format(Locale.US, "%.6f", data.variance)).append("` (Variance)\n");
                        sb.append("• 3. Amplitude Daily : `").append(String.format(Locale.US, "%.2f", data.volatilityPercent)).append("%` (High-Low)\n");
                        sb.append("• 4. Position Range : `").append(String.format(Locale.US, "%.1f", data.dailyRangePercent)).append("%` (0=Bas, 100=Haut)\n\n");
                        
                        sb.append("🏛️ *NIVEAUX PIVOTS (Natifs TradingView) :*\n");
                        sb.append("• *Daily* : ").append(data.pdh > 0 ? "PDH = `" + String.format(Locale.US, "%.4f", data.pdh) + "` | PDL = `" + String.format(Locale.US, "%.4f", data.pdl) + "`\n" : "⚠️ Analyse du flux graphique...\n");
                        sb.append("• *Weekly* : ").append(data.pwh > 0 ? "PWH = `" + String.format(Locale.US, "%.4f", data.pwh) + "` | PWL = `" + String.format(Locale.US, "%.4f", data.pwl) + "`\n" : "⚠️ Analyse du flux graphique...\n");

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
                logToUI("🔴 [TV WS] Déconnecté. Reconnexion automatique dans 5s...");
                if (isRunning.get()) {
                    new Thread(() -> {
                        try { TimeUnit.SECONDS.sleep(5); } catch (InterruptedException ignored) {}
                        connectWebSocket();
                    }).start();
                }
            }
        });
    }

    private static void saveLevelToStorage(String key, String type, double value) {
        if (appContext == null || value <= 0) return;
        try {
            SharedPreferences.Editor editor = appContext.getSharedPreferences(PREFS_WEEKLY, Context.MODE_PRIVATE).edit();
            editor.putString(type + "_" + key, String.valueOf(value));
            editor.apply();
        } catch (Exception e) {
            Log.e(TAG, "Erreur persistance SharedPreferences pour " + key, e);
        }
    }

    private static void loadLevelsFromStorage() {
        if (appContext == null) return;
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_WEEKLY, Context.MODE_PRIVATE);
        for (String key : SYMBOL_MAP.keySet()) {
            try {
                double savedPdh = Double.parseDouble(prefs.getString("pdh_" + key, "0"));
                double savedPdl = Double.parseDouble(prefs.getString("pdl_" + key, "0"));
                double savedPwh = Double.parseDouble(prefs.getString("pwh_" + key, "0"));
                double savedPwl = Double.parseDouble(prefs.getString("pwl_" + key, "0"));

                if (savedPdh > 0) pdhCache.put(key, savedPdh);
                if (savedPdl > 0) pdlCache.put(key, savedPdl);
                if (savedPwh > 0) pwhCache.put(key, savedPwh);
                if (savedPwl > 0) pwlCache.put(key, savedPwl);
            } catch (NumberFormatException ignored) {}
        }
        if (!pdhCache.isEmpty()) {
            logToUI("📦 [Fonda Storage] " + pdhCache.size() + " structures de pivots restaurées localement au démarrage.");
        }
    }

    private static class VarianceCalculator {
        private final int period; private final double[] window;
        private int index = 0; private int count = 0; private double sum = 0; private double sumSq = 0;
        public VarianceCalculator(int period) { this.period = period; this.window = new double[period]; }
        public synchronized void addPrice(double price) {
            if (count < period) {
                window[count] = price; sum += price; sumSq += price * price; count++;
            } else {
                double old = window[index]; sum -= old; sumSq -= old * old;
                window[index] = price; sum += price; sumSq += price * price;
                index = (index + 1) % period;
            }
        }
        public synchronized double getVariance() {
            if (count < 2) return 0;
            int n = Math.min(count, period);
            double mean = sum / n; return (sumSq / n) - (mean * mean);
        }
    }

    public static Map<String, TVMarketData> getCache() { return Collections.unmodifiableMap(cache); }

    public static String buildContexteMacroGlobal(Context ctx) {
        if (cache.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("═══ CONTEXTE MACRO GLOBAL TEMPS RÉEL ═══\n");

        for (String key : SYMBOL_MAP.keySet()) {
            TVMarketData d = cache.get(key);
            if (d != null) {
                String formatPrice = (key.equals("GBPUSD") || key.equals("EURUSD")) ? "%.5f" : (key.equals("USDJPY") ? "%.3f" : "%.2f");
                sb.append("• ").append(key).append(" : ")
                  .append(String.format(Locale.US, formatPrice, d.price))
                  .append(" (").append(String.format(Locale.US, "%+.2f", d.changePercent)).append("%)")
                  .append(" | Amp: ").append(String.format(Locale.US, "%.2f", d.volatilityPercent)).append("%")
                  .append(" | Range: ").append(String.format(Locale.US, "%.0f", d.dailyRangePercent)).append("%")
                  .append(d.isNearHigh ? " 🔺PrèsHaut" : d.isNearLow ? " 🔻PrèsBas" : "")
                  .append(" | Var: ").append(String.format(Locale.US, "%.6f", d.variance))
                  .append(d.pdh > 0 ? " | PDH=" + String.format(Locale.US, formatPrice, d.pdh) : "")
                  .append(d.pdl > 0 ? " | PDL=" + String.format(Locale.US, formatPrice, d.pdl) : "")
                  .append(d.brokeAbovePDH ? " 🔺Breakout PDH" : d.brokeBelowPDL ? " 🔻Breakdown PDL" : "")
                  .append(d.pwh > 0 ? " | PWH=" + String.format(Locale.US, formatPrice, d.pwh) : "")
                  .append(d.pwl > 0 ? " | PWL=" + String.format(Locale.US, formatPrice, d.pwl) : "")
                  .append(d.brokeAbovePWH ? " 🚀Breakout PWH" : d.brokeBelowPWL ? " 🔥Breakdown PWL" : "")
                  .append("\n");
            }
        }
        String regimeFed = ctx.getSharedPreferences(PREFS_WEEKLY, Context.MODE_PRIVATE).getString("fed_regime", "PAUSE HAWKISH | CPI 4.2%");
        sb.append("\n🏦 RÉGIME FED : ").append(regimeFed).append("\n");
        return sb.toString();
    }

    private static void logToUI(String msg) {
        if (MainActivity.instance != null) { MainActivity.instance.addLog(msg); }
    }
}
