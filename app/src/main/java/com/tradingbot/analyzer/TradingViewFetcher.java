package com.tradingbot.analyzer;

import android.content.Context;
import android.util.Log;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
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
    private static WebSocket webSocket;
    
    private static final Map<String, String> pendingSymbolResolution = new ConcurrentHashMap<>();
    private static final Map<String, String> pendingSymbolChartSession = new ConcurrentHashMap<>();
    
    // ── Matrice des Actifs Fonda IOF ──
    private static final Map<String, String> SYMBOL_MAP = new HashMap<String, String>() {{
        // Indices
        put("NASDAQ",  "SPREADEX:NDX");
        put("SP500",   "SPREADEX:SPX");
        // Matières Premières
        put("GOLD",    "TVC:GOLD");
        put("USOIL",   "TVC:USOIL");

        // Devises (Forex)
        put("USDJPY",  "VANTAGE:USDJPY");
        put("GBPUSD",  "VANTAGE:GBPUSD");
        put("EURUSD",  "VANTAGE:EURUSD");
    }};

    // Structure interne pour l'analyse algorithmique des bougies H4
    public static class Candle {
        public double open;
        public double high;
        public double low;
        public double close;

        public Candle(double open, double high, double low, double close) {
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
        }
    }

    public static class TVMarketData {
        public final String symbol;
        public final double price;
        public final double changePercent;      
        public final double high;
        public final double low;
        public final double open;
        public final double prevClose;
        public final double variance;           
        public final double volatilityPercent;  
        public final double dailyRangePercent;  
        public final boolean isNearHigh;
        public final boolean isNearLow;
        
        public final double ma200;
        public final boolean aboveMA200;
        
        // ⚡ Niveaux H4 Clôturés (Précédents)
        public final double p4hh; 
        public final double p4hl; 
        public final boolean brokeAboveP4HH; 
        public final boolean brokeBelowP4HL;

        // Niveaux Daily Clôturés
        public final double pdh; 
        public final double pdl; 
        public final boolean brokeAbovePDH; 
        public final boolean brokeBelowPDL; 
        
        // Niveaux Weekly Clôturés
        public final double pwh; 
        public final double pwl; 
        public final boolean brokeAbovePWH; 
        public final boolean brokeBelowPWL; 

        // Niveaux Monthly Clôturés
        public final double pmh; 
        public final double pml; 
        public final boolean brokeAbovePMH; 
        public final boolean brokeBelowPML; 
        
        public final long timestamp;

        public TVMarketData(String symbol, double price, double changePercent,
                            double high, double low, double open, double prevClose,
                            double variance, double ma200, double p4hh, double p4hl,
                            double pdh, double pdl, double pwh, double pwl, 
                            double pmh, double pml, long timestamp) {
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
            
            // ⚡ Initialisation H4
            this.p4hh          = p4hh;
            this.p4hl          = p4hl;
            this.brokeAboveP4HH = (p4hh > 0) && (price > p4hh);
            this.brokeBelowP4HL = (p4hl > 0) && (price < p4hl);

            this.pdh           = pdh;
            this.pdl           = pdl;
            this.brokeAbovePDH = (pdh > 0) && (price > pdh);
            this.brokeBelowPDL = (pdl > 0) && (price < pdl);
            
            this.pwh           = pwh;
            this.pwl           = pwl;
            this.brokeAbovePWH = (pwh > 0) && (price > pwh);
            this.brokeBelowPWL = (pwl > 0) && (price < pwl);

            this.pmh           = pmh;
            this.pml           = pml;
            this.brokeAbovePMH = (pmh > 0) && (price > pmh);
            this.brokeBelowPML = (pml > 0) && (price < pml);
            
            this.timestamp     = timestamp;
        }
    }

    public static void injectKeyLevels(String asset, double dh, double dl, double wh, double wl) {
        Log.d(TAG, "📥 [Webhook] Injection désactivée (Priorité WebSocket TV Multi-Timeframe).");
    }

    public interface OnDataReadyListener {
        void onDataReady(Map<String, TVMarketData> data);
        void onError(String error); 
    }

    public static void fetchAll(OnDataReadyListener listener) {
        if (listener == null) return;
        try {
            listener.onDataReady(java.util.Collections.unmodifiableMap(cache));
        } catch (Exception e) {
            listener.onError(e.getMessage());
        }
    }
    
    private static final String PREFS_WEEKLY = "TradingBotPrefs";

    private static final ConcurrentHashMap<String, TVMarketData> cache = new ConcurrentHashMap<>();
    
    // ⚡ Caches mémoires Niveaux et Bougies H4
    private static final ConcurrentHashMap<String, Double> p4hhCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> p4hlCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Candle[]> h4CandlesCache = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, Double> pdhCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> pdlCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> pwhCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> pwlCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> pmhCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> pmlCache = new ConcurrentHashMap<>();
     
    // ⚡ Anti-spam d'alertes H4 et Reversals
    private static final ConcurrentHashMap<String, Boolean> alertFiredP4HH = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> alertFiredP4HL = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> alertFiredH4BullishRev = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> alertFiredH4BearishRev = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, Boolean> alertFiredPDH = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> alertFiredPDL = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> alertFiredPWH = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> alertFiredPWL = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> alertFiredPMH = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> alertFiredPML = new ConcurrentHashMap<>();
    // ⚡ Anti-burst : empêche de traiter l'état déjà cassé au démarrage comme une cassure "en direct"
    private static final ConcurrentHashMap<String, Boolean> alertBaselineSet = new ConcurrentHashMap<>();

    // ⚡ Mémoire de contact PDH/PDL : permet de détecter un reversal H4 même si le prix
    // s'est déjà éloigné du niveau au moment où la bougie H4 se clôture (ex: reconnexion tardive).
    private static final ConcurrentHashMap<String, Long> lastPdlTouchTime = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> lastPdhTouchTime = new ConcurrentHashMap<>();
    private static final long TOUCH_MEMORY_WINDOW_MS = 45 * 60 * 1000; // 45 minutes
    
    private static final AtomicBoolean isRunning = new AtomicBoolean(false);
    private static final ConcurrentHashMap<String, VarianceCalculator> varianceCalculators = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> lastAlertTime = new ConcurrentHashMap<>();
    private static final long ALERT_COOLDOWN_MS = 5 * 60 * 1000;

    private static final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private static final AtomicBoolean connected = new AtomicBoolean(false);
    private static Context appContext;
    private static OkHttpClient client;

    public static ConcurrentHashMap<String, TVMarketData> getCache() {
        return cache;
    }

    public static String buildContexteMacroGlobal(Context context) {
    StringBuilder sb = new StringBuilder();
    sb.append("═══ CONTEXTE MARCHÉ TEMPS RÉEL ═══\n\n");

    sb.append("CONSIGNE ANALYTIQUE PRIORITAIRE :\n");
    sb.append("Chaque actif ci-dessous contient un [VERDICT TECHNIQUE] issu de l'analyse\n");
    sb.append("structurelle temps réel (BOS, Liquidity Sweep, Premium/Discount, Fair Value).\n");
    sb.append("Tu DOIS croiser ce verdict avec le driver fondamental détecté et conclure :\n");
    sb.append("→ CONFLUENCE ✅ : technique ET fondamental alignés → signal prioritaire, conviction maximale\n");
    sb.append("→ DIVERGENCE ⚠️ : technique et fondamental opposés → signaler le conflit explicitement\n");
    sb.append("→ NEUTRE 🟡   : verdict suspect (flux gelé) OU fondamental ambigu → observation uniquement\n\n");
    sb.append("RÈGLE DE CORRÉLATION :\n");
    sb.append("- [BOS] ✅ Institutionnel confirmé + fondamental aligné → CONFLUENCE, conviction élevée\n");
    sb.append("- [BOS] ⚠️ Signal suspect (flux gelé) + fondamental fort → fondamental prime, technique ignoré\n");
    sb.append("- [BOS] ✅ + fondamental opposé → DIVERGENCE à signaler dans le FAIT MARQUANT\n");
    sb.append("- [LIQUIDITY SWEEP] ✅ + flux refuge fondamental → setup institutionnel haute probabilité\n");
    sb.append("- [FAIR VALUE ZONE] + fondamental neutre → pas de signal, marché en attente\n\n");

    String[] order = {"NASDAQ", "USOIL", "USDJPY", "GOLD", "EURUSD", "SP500", "GBPUSD"};

    for (String key : order) {
        TVMarketData data = cache.get(key);
        if (data != null) {
            int decimals = (key.equals("EURUSD") || key.equals("GBPUSD")) ? 5 : (key.equals("USDJPY") ? 3 : 2);
            String fmt = "%." + decimals + "f";

            // ── Bloc actif : verdict technique + niveaux structurels ──
            sb.append("▶ ").append(key).append("\n");
            sb.append("  [VERDICT TECHNIQUE] ").append(buildTechnicalInterpretation(key, data)).append("\n");
            sb.append("  [NIVEAUX CLÉS] ");
            sb.append("H4[").append(String.format(Locale.US, fmt, data.p4hh)).append("/").append(String.format(Locale.US, fmt, data.p4hl)).append("] ");
            sb.append("D[").append(String.format(Locale.US, fmt, data.pdh)).append("/").append(String.format(Locale.US, fmt, data.pdl)).append("] ");
            sb.append("W[").append(String.format(Locale.US, fmt, data.pwh)).append("/").append(String.format(Locale.US, fmt, data.pwl)).append("] ");
            sb.append("M[").append(String.format(Locale.US, fmt, data.pmh)).append("/").append(String.format(Locale.US, fmt, data.pml)).append("]\n\n");
        }
    }
    return sb.toString();
    }
    
    /**
     * 🧠 MOTEUR D'INTERPRÉTATION QUANTITATIVE (DÉTERMINISTE)
     * Évalue l'état de la structure de marché via l'alignement des pivots multi-timeframes,
     * la distribution des zones de liquidité (Premium/Discount) et l'Order Flow H4.
     * Sans appel réseau — Résolution instantanée basée sur le cache LKV.
     */
    public static String buildTechnicalInterpretation(String key, TVMarketData data) {
    if (data == null) return "[DATA INDISPONIBLE] Flux TradingView non initialisé.";
    long now = System.currentTimeMillis();

    // ─── Qualification des 4 indicateurs ───────────────────────────────────
    boolean fluxGele     = data.variance < 0.000001;
    boolean fluxActif    = data.variance > 0.0001;
    boolean momentumFort = Math.abs(data.changePercent) >= 1.0;
    boolean momentumNul  = Math.abs(data.changePercent) < 0.05;
    boolean rangExplosif = data.volatilityPercent >= 1.0;
    boolean rangComprime = data.volatilityPercent < 0.3;

    String suffixe = buildSuffixe(fluxGele, fluxActif, momentumFort, momentumNul, rangExplosif, rangComprime, data);

    // ─── Décimales selon actif ──────────────────────────────────────────────
    int dec = (key.equals("EURUSD") || key.equals("GBPUSD")) ? 5 : (key.equals("USDJPY") ? 3 : 2);
    String fmt = "%." + dec + "f";

    // 1️⃣ Détection BOS (Priorité HTF > LTF)
    String structureLevel  = null;
    String structureVector = null;
    double niveauCasse     = 0;

    if      (data.brokeAbovePMH)  { structureLevel = "PMH"; structureVector = "Impulsion Haussière";    niveauCasse = data.pmh; }
    else if (data.brokeBelowPML)  { structureLevel = "PML"; structureVector = "Expansion Baissière";    niveauCasse = data.pml; }
    else if (data.brokeAbovePWH)  { structureLevel = "PWH"; structureVector = "Impulsion Haussière";    niveauCasse = data.pwh; }
    else if (data.brokeBelowPWL)  { structureLevel = "PWL"; structureVector = "Expansion Baissière";    niveauCasse = data.pwl; }
    else if (data.brokeAbovePDH)  { structureLevel = "PDH"; structureVector = "Impulsion Haussière";    niveauCasse = data.pdh; }
    else if (data.brokeBelowPDL)  { structureLevel = "PDL"; structureVector = "Expansion Baissière";    niveauCasse = data.pdl; }
    else if (data.brokeAboveP4HH) { structureLevel = "P4HH"; structureVector = "Accélération Intra-day"; niveauCasse = data.p4hh; }
    else if (data.brokeBelowP4HL) { structureLevel = "P4HL"; structureVector = "Pression Intra-day";   niveauCasse = data.p4hl; }

    // 2️⃣ Mémoire liquidité (45 min)
    Long pdlTouch = lastPdlTouchTime.get(key);
    Long pdhTouch = lastPdhTouchTime.get(key);
    boolean isPdlLiquiditySwept  = pdlTouch != null && (now - pdlTouch) <= TOUCH_MEMORY_WINDOW_MS;
    boolean isPdhLiquiditySwept  = pdhTouch != null && (now - pdhTouch) <= TOUCH_MEMORY_WINDOW_MS;
    boolean hasH4BullishAbsorption = Boolean.TRUE.equals(alertFiredH4BullishRev.get(key));
    boolean hasH4BearishAbsorption = Boolean.TRUE.equals(alertFiredH4BearishRev.get(key));

    // 🛑 BRANCHE A — BOS
    if (structureLevel != null) {
        String niveauStr = String.format(Locale.US, fmt, niveauCasse);
        String confirmation = fluxGele
            ? "⚠️ Signal suspect"
            : fluxActif && momentumFort && rangExplosif
                ? "✅ Institutionnel confirmé"
                : fluxActif
                    ? "🟡 Partiellement confirmé"
                    : "⚠️ Flux faible";
        return String.format("[BOS] %s au-dessus du %s %s %s%s",
            structureVector, structureLevel, niveauStr, confirmation, suffixe);
    }

    // 🛑 BRANCHE B — Liquidity Sweep
    if (hasH4BullishAbsorption && isPdlLiquiditySwept) {
        String niveauStr = String.format(Locale.US, fmt, data.pdl);
        String force = momentumNul || fluxGele
            ? "⚠️ Absorption faible"
            : "✅ Absorption active";
        return String.format("[LIQUIDITY SWEEP] Capture de liquidité sous PDL %s + H4 Reversal Bullish. %s%s",
            niveauStr, force, suffixe);
    }
    if (hasH4BearishAbsorption && isPdhLiquiditySwept) {
        String niveauStr = String.format(Locale.US, fmt, data.pdh);
        String force = momentumNul || fluxGele
            ? "⚠️ Absorption faible"
            : "✅ Absorption active";
        return String.format("[LIQUIDITY SWEEP] Capture de liquidité au-dessus PDH %s + H4 Reversal Bearish. %s%s",
            niveauStr, force, suffixe);
    }

    // 🛑 BRANCHE C — Premium / Discount
    if (data.isNearHigh) {
        String niveauStr = String.format(Locale.US, fmt, data.pdh);
        String contexte = momentumFort
            ? "risque de continuation au-delà du PDH " + niveauStr
            : "probabilité de rejet sur PDH " + niveauStr;
        return String.format("[PREMIUM ZONE] Test de résistance sur PDH %s — %s%s",
            niveauStr, contexte, suffixe);
    }
    if (data.isNearLow) {
        String niveauStr = String.format(Locale.US, fmt, data.pdl);
        String contexte = momentumFort
            ? "risque de continuation baissière sous PDL " + niveauStr
            : "probabilité de rebond sur PDL " + niveauStr;
        return String.format("[DISCOUNT ZONE] Test de support sur PDL %s — %s%s",
            niveauStr, contexte, suffixe);
    }

    // 🛑 BRANCHE D — Fair Value
    String etat = fluxGele && momentumNul
        ? "marché en attente de catalyseur"
        : rangExplosif
            ? "amplitude explosive sans direction claire — risque de faux signal"
            : "équilibre temporaire entre acheteurs et vendeurs";
    return String.format("[FAIR VALUE ZONE] Entre PDL %s et PDH %s — %s%s",
        String.format(Locale.US, fmt, data.pdl),
        String.format(Locale.US, fmt, data.pdh),
        etat, suffixe);
}

// Suffixe commun aux 4 branches — résumé chiffré des indicateurs pour traçabilité
 private static String buildSuffixe(boolean fluxGele, boolean fluxActif, boolean momentumFort,
        boolean momentumNul, boolean rangExplosif, boolean rangComprime, TVMarketData data) {
    StringBuilder s = new StringBuilder();

    // ── Interprétation de l'amplitude journalière ──
    String lectureRange = rangExplosif
        ? String.format(Locale.US, "expansion journalière de %.2f%% confirmant la rupture", data.volatilityPercent)
        : rangComprime
            ? String.format(Locale.US, "amplitude journalière de %.2f%% indiquant un marché compressé", data.volatilityPercent)
            : String.format(Locale.US, "amplitude journalière modérée de %.2f%%", data.volatilityPercent);

    // ── Interprétation du momentum ──
    String lectureMomentum = momentumFort
        ? String.format(Locale.US, "momentum de %+.2f%% validant la pression directionnelle", data.changePercent)
        : momentumNul
            ? String.format(Locale.US, "momentum nul à %.2f%% insuffisant pour valider le mouvement", data.changePercent)
            : String.format(Locale.US, "momentum de %+.2f%% insuffisant pour valider la rupture", data.changePercent);

    // ── Interprétation du flux tick ──
    String lectureFlux = fluxGele
        ? "marché gelé sans activité tick"
        : fluxActif
            ? "flux institutionnel dominant"
            : "flux faible en attente d'activation";

    // ── Niveau suivant pertinent selon position dans le range ──
    String niveauSuivant = "";
    if (data.dailyRangePercent >= 85.0 && data.pwh > 0) {
        int dec = (data.pwh < 10) ? 5 : 2;
        niveauSuivant = String.format(Locale.US, ", résistance hebdomadaire suivante PWH %." + dec + "f", data.pwh);
    } else if (data.dailyRangePercent <= 15.0 && data.pwl > 0) {
        int dec = (data.pwl < 10) ? 5 : 2;
        niveauSuivant = String.format(Locale.US, ", prochain support clé PWL %." + dec + "f en cas de rupture", data.pwl);
    }

    s.append(lectureRange).append(", ").append(lectureMomentum)
     .append(", ").append(lectureFlux).append(niveauSuivant).append(".");

    return " — " + s; 
   }
    public static void rolloverDailyLevels() {
        alertFiredP4HH.clear(); 
        alertFiredP4HL.clear();
        alertFiredH4BullishRev.clear();
        alertFiredH4BearishRev.clear();
        alertFiredPDH.clear();
        alertFiredPDL.clear();
        alertFiredPWH.clear();
        alertFiredPWL.clear();
        alertFiredPMH.clear(); 
        alertFiredPML.clear(); 
        alertBaselineSet.clear(); // ⚡ Réarme le mécanisme anti-burst pour les nouveaux niveaux du jour
        lastPdlTouchTime.clear();
        lastPdhTouchTime.clear();
        logToUI("🔄 [Anti-Spam] Réinitialisation complète de tous les verrous d'alertes pivots et reversals.");
    }

    public static void start(Context context) {
        if (isRunning.getAndSet(true)) {
            logToUI("⏳ [TV] Déjà en exécution.");
            return;
        }
        appContext = context.getApplicationContext();
        
        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        
        logToUI("📡 [TV] Démarrage du pipeline TradingView Multi-Session (H4, D, W, M).");
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
        h4CandlesCache.clear();
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

        client.newWebSocket(request, new WebSocketListener() {
            private String quoteSessionId;

            @Override
            public void onOpen(WebSocket ws, Response response) {
                webSocket = ws;
                logToUI("✅ [TV WS] Canal réseau connecté.");
                connected.set(true);
                isConnecting.set(false);
                cache.clear();
                pendingSymbolResolution.clear();
                pendingSymbolChartSession.clear();
            
                // 1. Initialisation Flux Temps Réel Principal
                quoteSessionId = "qs_" + UUID.randomUUID().toString().substring(0, 12);
                sendMessage(ws, "set_auth_token", new Object[]{"unauthorized_user_token"});
                sendMessage(ws, "quote_create_session", new Object[]{quoteSessionId});
                sendMessage(ws, "quote_set_fields", new Object[]{
                        quoteSessionId, "lp", "chp", "ch", "high_price", "low_price", "open_price", "prev_close_price"
                });
            
                // 2. Initialisation des Séries Temporelles (H4, Daily, Weekly, Monthly)
                for (String key : SYMBOL_MAP.keySet()) {
                    String ticker = SYMBOL_MAP.get(key);
                    varianceCalculators.putIfAbsent(key, new VarianceCalculator(20));
            
                    sendMessage(ws, "quote_add_symbols", new Object[]{quoteSessionId, ticker});
            
                    // ⚡ Session Chart H4 (Utilise "240" minutes pour l'API TV)
                    String chartSessionIdH4 = "cs_h4_" + key + "_" + UUID.randomUUID().toString().substring(0, 8);
                    sendMessage(ws, "chart_create_session", new Object[]{chartSessionIdH4, ""});
                    sendMessage(ws, "switch_timezone", new Object[]{chartSessionIdH4, "America/New_York"});
                    String symIdH4 = "sid_h4_" + key; 
                    pendingSymbolResolution.put(symIdH4, key);
                    pendingSymbolChartSession.put(symIdH4, chartSessionIdH4);
                    sendMessage(ws, "resolve_symbol", new Object[]{chartSessionIdH4, symIdH4, "={\"symbol\":\"" + ticker + "\",\"adjustment\":\"splits\"}"});

                    // Session Chart Daily
                    String chartSessionIdD = "cs_d_" + key + "_" + UUID.randomUUID().toString().substring(0, 8);
                    sendMessage(ws, "chart_create_session", new Object[]{chartSessionIdD, ""});
                    sendMessage(ws, "switch_timezone", new Object[]{chartSessionIdD, "America/New_York"});
                    String symIdD = "sid_d_" + key; 
                    pendingSymbolResolution.put(symIdD, key);
                    pendingSymbolChartSession.put(symIdD, chartSessionIdD);
                    sendMessage(ws, "resolve_symbol", new Object[]{chartSessionIdD, symIdD, "={\"symbol\":\"" + ticker + "\",\"adjustment\":\"splits\"}"});

                    // Session Chart Weekly
                    String chartSessionIdW = "cs_w_" + key + "_" + UUID.randomUUID().toString().substring(0, 8);
                    sendMessage(ws, "chart_create_session", new Object[]{chartSessionIdW, ""});
                    sendMessage(ws, "switch_timezone", new Object[]{chartSessionIdW, "America/New_York"});
                    String symIdW = "sid_w_" + key; 
                    pendingSymbolResolution.put(symIdW, key);
                    pendingSymbolChartSession.put(symIdW, chartSessionIdW);
                    sendMessage(ws, "resolve_symbol", new Object[]{chartSessionIdW, symIdW, "={\"symbol\":\"" + ticker + "\",\"adjustment\":\"splits\"}"});

                    // Session Chart Monthly
                    String chartSessionIdM = "cs_m_" + key + "_" + UUID.randomUUID().toString().substring(0, 8);
                    sendMessage(ws, "chart_create_session", new Object[]{chartSessionIdM, ""});
                    sendMessage(ws, "switch_timezone", new Object[]{chartSessionIdM, "America/New_York"});
                    String symIdM = "sid_m_" + key; 
                    pendingSymbolResolution.put(symIdM, key);
                    pendingSymbolChartSession.put(symIdM, chartSessionIdM);
                    sendMessage(ws, "resolve_symbol", new Object[]{chartSessionIdM, symIdM, "={\"symbol\":\"" + ticker + "\",\"adjustment\":\"splits\"}"});
                }
                logToUI("📥 [TV WS] Pipeline initialisé. Synchronisation H4, D, W, M opérationnelle.");
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
                    processJsonPayload(ws, payload);
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
                    Log.e(TAG, "[TV WS] Erreur envoi", e);
                }
            }

            private void processJsonPayload(WebSocket ws, String payload) {
                try {
                       if (!payload.startsWith("{")) return;
                      JSONObject json = new JSONObject(payload);
                      String m = json.optString("m");

                     if ("critical_error".equals(m) || "protocol_error".equals(m) || "study_error".equals(m)) {
                     Log.e(TAG, "🔴 [TV WS] Erreur serveur : " + payload);
                     logToUI("🔴 [TV WS] Erreur serveur TradingView (" + m + ") — voir Logcat");
                      return;
                     }

                       if ("symbol_resolved".equals(m)) {
                        JSONArray p = json.getJSONArray("p");
                        if (p.length() > 1) {
                            String symId = p.getString(1);
                            String key = pendingSymbolResolution.remove(symId);
                            String assetChartSessionId = pendingSymbolChartSession.remove(symId);
                            
                            if (key != null && ws != null && assetChartSessionId != null) {
                                if (symId.startsWith("sid_h4_")) {
                                    sendMessage(ws, "create_series", new Object[]{assetChartSessionId, "ser_h4_" + key, "s1", symId, "240", 50});
                                } else if (symId.startsWith("sid_d_")) {
                                    sendMessage(ws, "create_series", new Object[]{assetChartSessionId, "ser_d_" + key, "s1", symId, "D", 50});
                                } else if (symId.startsWith("sid_w_")) {
                                    sendMessage(ws, "create_series", new Object[]{assetChartSessionId, "ser_w_" + key, "s1", symId, "W", 50});
                                } else if (symId.startsWith("sid_m_")) {
                                    sendMessage(ws, "create_series", new Object[]{assetChartSessionId, "ser_m_" + key, "s1", symId, "M", 50});
                                }
                            }
                        }
                        return;
                    }
            
                    if ("timescale_update".equals(m)) {
                        JSONArray p = json.getJSONArray("p");
                        if (p.length() > 1) {
                            JSONObject seriesData = p.getJSONObject(1);
                            java.util.Iterator<String> keys = seriesData.keys();
            
                            boolean liveSessionActive = isLiveBarActiveAtNewYork();

                            while (keys.hasNext()) {
                                String seriesId = keys.next();
                                JSONObject obj = seriesData.getJSONObject(seriesId);
            
                                if (obj.has("s")) {
                                    JSONArray sArr = obj.getJSONArray("s");
                                    if (sArr.length() >= 2) {
                                        int targetIndex = liveSessionActive ? (sArr.length() - 2) : (sArr.length() - 1);
                                        JSONObject targetBar = sArr.getJSONObject(targetIndex);
                                        
                                        if (targetBar.has("v")) {
                                            JSONArray vArr = targetBar.getJSONArray("v");
                                            if (vArr.length() >= 5) {
                                                double historicalHigh = vArr.getDouble(2);
                                                double historicalLow  = vArr.getDouble(3);
            
                                                if (seriesId.startsWith("ser_h4_")) {
                                                    String key = seriesId.substring(7);
                                                    p4hhCache.put(key, historicalHigh);
                                                    p4hlCache.put(key, historicalLow);
                                                    saveLevelToStorage(key, "p4hh", historicalHigh);
                                                    saveLevelToStorage(key, "p4hl", historicalLow);
                                                    
                                                    // ⚡ Extraction de c1 et c2 H4 pour le moteur de Reversal local
                                                    if (sArr.length() >= 3) {
                                                        int c1Index = liveSessionActive ? (sArr.length() - 2) : (sArr.length() - 1);
                                                        int c2Index = c1Index - 1;
                                                        
                                                        if (c2Index >= 0) {
                                                            JSONArray v1 = sArr.getJSONObject(c1Index).getJSONArray("v");
                                                            JSONArray v2 = sArr.getJSONObject(c2Index).getJSONArray("v");
                                                            
                                                            if (v1.length() >= 5 && v2.length() >= 5) {
                                                                Candle c1 = new Candle(v1.getDouble(1), v1.getDouble(2), v1.getDouble(3), v1.getDouble(4));
                                                                Candle c2 = new Candle(v2.getDouble(1), v2.getDouble(2), v2.getDouble(3), v2.getDouble(4));
                                                                h4CandlesCache.put(key, new Candle[]{c1, c2});
                                                            }
                                                        }
                                                    }
                                                } else if (seriesId.startsWith("ser_d_")) {
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
                                                } else if (seriesId.startsWith("ser_m_")) {
                                                    String key = seriesId.substring(6);
                                                    pmhCache.put(key, historicalHigh);
                                                    pmlCache.put(key, historicalLow);
                                                    saveLevelToStorage(key, "pmh", historicalHigh);
                                                    saveLevelToStorage(key, "pml", historicalLow);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        return;
                    }
            
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
            
                                    double p4hh = p4hhCache.getOrDefault(key, 0.0);
                                    double p4hl = p4hlCache.getOrDefault(key, 0.0);
                                    double pdh  = pdhCache.getOrDefault(key, 0.0);
                                    double pdl  = pdlCache.getOrDefault(key, 0.0);
                                    double pwh  = pwhCache.getOrDefault(key, 0.0);
                                    double pwl  = pwlCache.getOrDefault(key, 0.0);
                                    double pmh  = pmhCache.getOrDefault(key, 0.0);
                                    double pml  = pmlCache.getOrDefault(key, 0.0);
            
                                    TVMarketData newData = new TVMarketData(
                                            key, price, change, high, low, open, prevClose,
                                            variance, 0.0, p4hh, p4hl, pdh, pdl, pwh, pwl, pmh, pml,
                                            System.currentTimeMillis()
                                    );
                                    cache.put(key, newData);
                                    checkAndAlert(key, newData);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[TV WS] Erreur traitement JSON", e);
                }
            }

            private String getKeyFromTicker(String ticker) {
                for (Map.Entry<String, String> entry : SYMBOL_MAP.entrySet()) {
                    if (ticker.equals(entry.getValue())) return entry.getKey();
                }
                return null;
            }

            private void checkAndAlert(String key, TVMarketData data) {
                if (appContext == null) return;
                long now = System.currentTimeMillis();
                if (!Boolean.TRUE.equals(alertBaselineSet.get(key))) {
                    alertFiredP4HH.put(key, data.brokeAboveP4HH);
                    alertFiredP4HL.put(key, data.brokeBelowP4HL);
                    alertFiredPDH.put(key, data.brokeAbovePDH);
                    alertFiredPDL.put(key, data.brokeBelowPDL);
                    alertFiredPWH.put(key, data.brokeAbovePWH);
                    alertFiredPWL.put(key, data.brokeBelowPWL);
                    alertFiredPMH.put(key, data.brokeAbovePMH);
                    alertFiredPML.put(key, data.brokeBelowPML);
                    lastAlertTime.put(key, now); // évite aussi le spam immédiat "Approche Haut/Bas"

                    // 🕒 Si on se reconnecte alors que le prix est déjà sur la zone PDL/PDH,
                    // on enregistre quand même le contact pour que la fenêtre de reversal fonctionne.
                    double toleranceZoneBaseline = 0.15 / 100.0;
                    if (data.pdl > 0 && Math.abs(data.price - data.pdl) <= (data.pdl * toleranceZoneBaseline)) {
                        lastPdlTouchTime.put(key, now);
                    }
                    if (data.pdh > 0 && Math.abs(data.price - data.pdh) <= (data.pdh * toleranceZoneBaseline)) {
                        lastPdhTouchTime.put(key, now);
                    }

                    alertBaselineSet.put(key, true);
                    logToUI("🛡️ [Anti-Burst] " + key + " — Baseline armée silencieusement (0 notif rétroactive).");
                    return;
                }
                // ── MOTEUR DE DÉTECTION LOCAL : CONTACT + REVERSAL H4 ──
                  // ── Fix 1 : tolérance élargie à 0.30% pour capturer approche + cassure légère ──
double toleranceZone = 0.30 / 100.0;

// ── Fix 3 : contact PDL enregistré si prix DANS la zone OU EN DESSOUS (cassure) ──
// Cas 1 : prix dans la zone ±0.30% au-dessus du PDL (approche)
// Cas 2 : prix en dessous du PDL mais pas plus de 0.30% sous lui (cassure légère + retour)
if (data.pdl > 0) {
    boolean touchApproche = data.price >= data.pdl && Math.abs(data.price - data.pdl) <= (data.pdl * toleranceZone);
    boolean touchCassure  = data.price < data.pdl  && Math.abs(data.price - data.pdl) <= (data.pdl * toleranceZone);
    if (touchApproche || touchCassure) {
        lastPdlTouchTime.put(key, now);
    }
}
if (data.pdh > 0) {
    boolean touchApproche = data.price <= data.pdh && Math.abs(data.price - data.pdh) <= (data.pdh * toleranceZone);
    boolean touchCassure  = data.price > data.pdh  && Math.abs(data.price - data.pdh) <= (data.pdh * toleranceZone);
    if (touchApproche || touchCassure) {
        lastPdhTouchTime.put(key, now);
    }
}

Long pdlTouch = lastPdlTouchTime.get(key);
boolean pdlTouchedRecently = pdlTouch != null && (now - pdlTouch) <= TOUCH_MEMORY_WINDOW_MS;

Long pdhTouch = lastPdhTouchTime.get(key);
boolean pdhTouchedRecently = pdhTouch != null && (now - pdhTouch) <= TOUCH_MEMORY_WINDOW_MS;

// Scénario A — Reversal Bullish H4 après contact PDL
if (pdlTouchedRecently) {
    // ── Fix 4 : conditions assouplies ──
    // c2 = bougie H4 baissière (descente vers PDL)
    // c1 = bougie H4 haussière (reversal — peut avoir un wick bas sous c2)
    // On supprime c1.low > c2.low : un spike bas suivi de clôture haussière = reversal valide
    // pas un piège bearish
    // Bullish : c2 rouge → c1 verte dont le HIGH casse le HIGH de c2
    boolean isBullishH4Rev = isBear2 && isBull1
    && (c1.high > c2.high)
    && !englobanteBearishTrap;

    if (isBullishH4Rev && !Boolean.TRUE.equals(alertFiredH4BullishRev.get(key))) {
        alertFiredH4BullishRev.put(key, true);
        String msg = "⚡ *[FONDA IOF]* — *" + key + "*\n" +
                     "🔻 Zone *Previous Day Low* touchée (`" + String.format(Locale.US, "%.4f", data.pdl) + "`)\n" +
                     "✅ *Confirmation : Reversal Bullish H4 validé* à `" + String.format(Locale.US, "%.4f", data.price) + "` !";
        NotificationService.sendTelegramSecure(msg, appContext);
    }
}

// Scénario B — Reversal Bearish H4 après contact PDH
if (pdhTouchedRecently) {
    // c2 = bougie H4 haussière (montée vers PDH)
    // c1 = bougie H4 baissière (reversal — peut avoir un wick haut au-dessus de c2)
    // pas un piège bullish
    
    // Bearish : c2 verte → c1 rouge dont le LOW casse le LOW de c2
    boolean isBearishH4Rev = isBull2 && isBear1
    && (c1.low < c2.low)
    && !englobanteBullishTrap;

    if (isBearishH4Rev && !Boolean.TRUE.equals(alertFiredH4BearishRev.get(key))) {
        alertFiredH4BearishRev.put(key, true);
        String msg = "⚡ *[FONDA IOF]* — *" + key + "*\n" +
                     "🔺 Zone *Previous Day High* touchée (`" + String.format(Locale.US, "%.4f", data.pdh) + "`)\n" +
                     "🚨 *Confirmation : Reversal Bearish H4 validé* à `" + String.format(Locale.US, "%.4f", data.price) + "` !";
        NotificationService.sendTelegramSecure(msg, appContext);
    }
 }

                // Scénario B : Daily High touché (à l'instant OU dans les 45 dernières min) -> Attente Reversal Bearish H4
                if (pdhTouchedRecently) {
                    boolean isBearishH4Rev = isBull2 && isBear1 && (c1.high < c2.high || b1_englobe_b2) && !englobanteBullishTrap;
                    
                    if (isBearishH4Rev && !Boolean.TRUE.equals(alertFiredH4BearishRev.get(key))) {
                        alertFiredH4BearishRev.put(key, true);
                        String msg = "⚡ *[FONDA IOF]* — *" + key + "*\n" +
                                     "🔺 Zone *Previous Day High* touchée (`" + String.format(Locale.US, "%.4f", data.pdh) + "`)\n" +
                                     "🚨 *Confirmation : Reversal Bearish H4 validé* à `" + String.format(Locale.US, "%.4f", data.price) + "` !";
                        NotificationService.sendTelegramSecure(msg, appContext);
                    }
                }
                }

                // ── ALERTES CLASSIQUES TEMPS RÉEL D'APPROCHE (COOLDOWN 5 MIN) ──
                Long last = lastAlertTime.get(key);
                if (last == null || (now - last) > ALERT_COOLDOWN_MS) {
                    if (data.isNearHigh || data.isNearLow) {
                        int decimals = (key.equals("EURUSD") || key.equals("GBPUSD")) ? 5 : (key.equals("USDJPY") ? 3 : 2);
                        String fmt = "%." + decimals + "f";

                           StringBuilder sb = new StringBuilder();
                           sb.append("📊 *").append(key).append(data.isNearHigh ? "* 🔺 Approche du *plus haut du jour*\n\n" : "* 🔻 Approche du *plus bas du jour*\n\n");
                           sb.append("↳ ").append(buildTechnicalInterpretation(key, data)).append("\n\n");
                           sb.append("🔹 `").append(String.format(Locale.US, fmt, data.price)).append("` (").append(String.format(Locale.US, "%+.2f", data.changePercent)).append("%) | Range : ").append(String.format(Locale.US, "%.2f", data.volatilityPercent)).append("%\n");
                           sb.append("📐 ");
                           if (data.pdh > 0) sb.append("PDH `").append(String.format(Locale.US, fmt, data.pdh)).append("` | PDL `").append(String.format(Locale.US, fmt, data.pdl)).append("` | ");
                           if (data.pwh > 0) sb.append("PWH `").append(String.format(Locale.US, fmt, data.pwh)).append("` | PWL `").append(String.format(Locale.US, fmt, data.pwl)).append("`");
                           sb.append("\n");
                           NotificationService.sendTelegramSecure(sb.toString(), appContext);
                          lastAlertTime.put(key, now);
                    }
                }

                // Triggers immédiats (Anti-spam par booléen) pour les cassures pures
                int decimalsAlert = (key.equals("EURUSD") || key.equals("GBPUSD")) ? 5 : (key.equals("USDJPY") ? 3 : 2);
                String fmtAlert = "%." + decimalsAlert + "f";
                String prixFormate = String.format(Locale.US, fmtAlert, data.price);

             if (data.brokeAboveP4HH && !Boolean.TRUE.equals(alertFiredP4HH.get(key))) {
    alertFiredP4HH.put(key, true);
    NotificationService.sendTelegramSecure("🧭 *" + key + "* — Cassure du *Previous High H4* (`" + prixFormate + "`)\n↳ " + buildTechnicalInterpretation(key, data), appContext);
}
if (data.brokeBelowP4HL && !Boolean.TRUE.equals(alertFiredP4HL.get(key))) {
    alertFiredP4HL.put(key, true);
    NotificationService.sendTelegramSecure("📉 *" + key + "* — Cassure du *Previous Low H4* (`" + prixFormate + "`)\n↳ " + buildTechnicalInterpretation(key, data), appContext);
}
if (data.brokeAbovePDH && !Boolean.TRUE.equals(alertFiredPDH.get(key))) {
    alertFiredPDH.put(key, true);
    NotificationService.sendTelegramSecure("🔺 *" + key + "* — Cassure réelle du *Previous Day High* (`" + prixFormate + "`)\n↳ " + buildTechnicalInterpretation(key, data), appContext);
}
if (data.brokeBelowPDL && !Boolean.TRUE.equals(alertFiredPDL.get(key))) {
    alertFiredPDL.put(key, true);
    NotificationService.sendTelegramSecure("🔻 *" + key + "* — Cassure réelle du *Previous Day Low* (`" + prixFormate + "`)\n↳ " + buildTechnicalInterpretation(key, data), appContext);
}
if (data.brokeAbovePWH && !Boolean.TRUE.equals(alertFiredPWH.get(key))) {
    alertFiredPWH.put(key, true);
    NotificationService.sendTelegramSecure("🚀 *" + key + "* — Breakout validé du *Previous Week High* (`" + prixFormate + "`) !\n↳ " + buildTechnicalInterpretation(key, data), appContext);
}
if (data.brokeBelowPWL && !Boolean.TRUE.equals(alertFiredPWL.get(key))) {
    alertFiredPWL.put(key, true);
    NotificationService.sendTelegramSecure("🔥 *" + key + "* — Breakdown validé du *Previous Week Low* (`" + prixFormate + "`) !\n↳ " + buildTechnicalInterpretation(key, data), appContext);
}
if (data.brokeAbovePMH && !Boolean.TRUE.equals(alertFiredPMH.get(key))) {
    alertFiredPMH.put(key, true);
    NotificationService.sendTelegramSecure("🌌 *" + key + "* — Macro Breakout du *Previous Month High* (`" + prixFormate + "`) !!\n↳ " + buildTechnicalInterpretation(key, data), appContext);
}
if (data.brokeBelowPML && !Boolean.TRUE.equals(alertFiredPML.get(key))) {
    alertFiredPML.put(key, true);
    NotificationService.sendTelegramSecure("⚡ *" + key + "* — Macro Breakdown du *Previous Month Low* (`" + prixFormate + "`) !!\n↳ " + buildTechnicalInterpretation(key, data), appContext);
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
                logToUI("🔴 [TV WS] Connexion perdue. Reconnexion automatique dans 5s...");
                if (isRunning.get()) {
                    new Thread(() -> {
                        try { TimeUnit.SECONDS.sleep(5); } catch (InterruptedException ignored) {}
                        connectWebSocket();
                    }).start();
                }
            }
        });
    }

    private static boolean isLiveBarActiveAtNewYork() {
    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("America/New_York"), Locale.US);
    int day  = cal.get(Calendar.DAY_OF_WEEK);
    int hour = cal.get(Calendar.HOUR_OF_DAY);
    int min  = cal.get(Calendar.MINUTE);

    // Week-end : samedi fermé toute la journée
    if (day == Calendar.SATURDAY) return false;

    // Dimanche : marché fermé avant 17h00 New York (réouverture Forex/Futures)
    if (day == Calendar.SUNDAY && hour < 17) return false;

    // Vendredi :
    // - Forex / Futures (GOLD, USOIL, USDJPY, GBPUSD, EURUSD) → clôture 17h00 NY
    // - Indices (NASDAQ, SP500) → clôture 16h00 NY
    // On utilise 16h00 comme seuil conservateur pour couvrir les deux cas.
    // La bougie live de 16h-17h sur Forex/Futures reste valide mais on la traite
    // comme clôturée → lecture sur l'avant-dernière bougie = comportement sûr.
    if (day == Calendar.FRIDAY && (hour > 16 || (hour == 16 && min >= 0))) return false;

    return true;
    }

    private static void saveLevelToStorage(String key, String type, double value) {
        if (appContext == null || value <= 0) return;
        try {
            SharedPreferences.Editor editor = appContext.getSharedPreferences(PREFS_WEEKLY, Context.MODE_PRIVATE).edit();
            editor.putString(type + "_" + key, String.valueOf(value));
            editor.apply();
        } catch (Exception e) {
            Log.e(TAG, "Erreur cache local pour " + key, e);
        }
    }

    private static void loadLevelsFromStorage() {
        if (appContext == null) return;
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_WEEKLY, Context.MODE_PRIVATE);
        for (String key : SYMBOL_MAP.keySet()) {
            try {
                double savedP4hh = Double.parseDouble(prefs.getString("p4hh_" + key, "0"));
                double savedP4hl = Double.parseDouble(prefs.getString("p4hl_" + key, "0"));

                double savedPdh  = Double.parseDouble(prefs.getString("pdh_" + key, "0"));
                double savedPdl  = Double.parseDouble(prefs.getString("pdl_" + key, "0"));
                double savedPwh  = Double.parseDouble(prefs.getString("pwh_" + key, "0"));
                double savedPwl  = Double.parseDouble(prefs.getString("pwl_" + key, "0"));
                double savedPmh  = Double.parseDouble(prefs.getString("pmh_" + key, "0"));
                double savedPml  = Double.parseDouble(prefs.getString("pml_" + key, "0"));

                if (savedP4hh > 0) p4hhCache.put(key, savedP4hh);
                if (savedP4hl > 0) p4hlCache.put(key, savedP4hl);
                if (savedPdh > 0)  pdhCache.put(key, savedPdh);
                if (savedPdl > 0)  pdlCache.put(key, savedPdl);
                if (savedPwh > 0)  pwhCache.put(key, savedPwh);
                if (savedPwl > 0)  pwlCache.put(key, savedPwl);
                if (savedPmh > 0)  pmhCache.put(key, savedPmh);
                if (savedPml > 0)  pmlCache.put(key, savedPml);
            } catch (NumberFormatException ignored) {}
        }
        if (!p4hhCache.isEmpty() || !pdhCache.isEmpty()) {
            logToUI("📦 [Fonda Local Storage] Réintégration complète de la cartographie pivot (H4, D, W, M).");
        }
    }

    private static void logToUI(String message) {
        Log.d(TAG, message);
    }

    private static class VarianceCalculator {
        private final int period;
        private final double[] window;
        private int index = 0;
        private int count = 0;
        private double sum = 0;
        private double sumSq = 0;

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
            if (count < 2) return 0.0;
            double mean = sum / count;
            double variance = (sumSq / count) - (mean * mean);
            return variance < 0 ? 0.0 : variance;
        }
    }
}
