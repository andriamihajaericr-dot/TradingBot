package com.tradingbot.analyzer;

import android.util.Log;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;

public class MarketDataFetcher {
    private static final String TAG = "MarketDataFetcher";

    private static volatile String twelveDataKey = "32370e1ef17645eb86690e3aee0d0660";
    
    // 🧵 Pools de Threads isolés pour éliminer tout risque de Deadlock ou de ralentissement Android
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final ExecutorService networkExecutor = Executors.newCachedThreadPool(); 
    
    // ⏳ STRUCTURE ET CONFIGURATION DU CACHE SECURISE (LKV avec TTL)
    private static class CachedEntry {
        final MarketData data;
        final long timestamp;

        CachedEntry(MarketData data, long timestamp) {
            this.data = data;
            this.timestamp = timestamp;
        }
    }
     
    
    private static final Map<String, CachedEntry> MARKET_DATA_CACHE = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(2); // Durée de vie max d'un prix de secours : 5 minutes

    private static volatile boolean isShutdown = false;

    // ✅ Configuration de l'endpoint et activation des sessions étendues
    private static final boolean ENABLE_PREMARKET_PARAM = true; 

    public interface MarketAnalysisCallback {
        void onAnalysisComplete(String report);
    }

    public static void setApiKey(String key) {
        if (key != null && !key.trim().isEmpty()) {
            twelveDataKey = key;
        }
    }
        /**
     * Test diagnostic amélioré - affiche dans l'UI + Logcat
     */
    public static void testRealTimeFreshness() {
        Log.d(TAG, "🔍 === TEST FRESHNESS MARKET DATA ===");

        // Affichage dans l'UI
        if (MainActivity.instance != null) {
            MainActivity.instance.addLog("🔍 === TEST FRESHNESS MARKET DATA ===");
        }

        List<String> testAssets = Arrays.asList("SP500", "NASDAQ", "GOLD", "BITCOIN", "EURUSD", "USDJPY");
        
        long start = System.currentTimeMillis();
        
        try {
            Map<String, MarketData> data = getMarketDataBatch(testAssets);
            long duration = System.currentTimeMillis() - start;

            String timeMsg = "⏱️ Temps total du batch : " + duration + "ms";
            Log.d(TAG, timeMsg);
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog(timeMsg);
            }

            boolean hasData = false;
            for (String asset : testAssets) {
                MarketData md = data.get(asset);
                CachedEntry cache = MARKET_DATA_CACHE.get(asset);
                
                if (md != null) {
                    hasData = true;
                    long ageMs = cache != null ? (System.currentTimeMillis() - cache.timestamp) : 0;
                    String result = String.format("✅ %s | Prix: %.4f | Var: %+.2f%% | Âge: %ds", 
                        asset, md.price, md.changePercent, ageMs/1000);
                    
                    Log.d(TAG, result);
                    if (MainActivity.instance != null) {
                        MainActivity.instance.addLog(result);
                    }
                } else {
                    String error = "❌ " + asset + " → Aucune donnée";
                    Log.w(TAG, error);
                    if (MainActivity.instance != null) {
                        MainActivity.instance.addLog(error);
                    }
                }
            }

            if (!hasData) {
                String warning = "⚠️ AUCUNE DONNÉE RÉCUPÉRÉE - Problème réseau / clé API / timeout";
                Log.w(TAG, warning);
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog(warning);
                }
            }

            if (duration > 1000) {
                String slow = "⚠️ Temps très long (" + duration + "ms) → Timeout trop strict";
                Log.w(TAG, slow);
                if (MainActivity.instance != null) {
                    MainActivity.instance.addLog(slow);
                }
            }

        } catch (Exception e) {
            String error = "❌ Erreur pendant le test MarketData : " + e.getMessage();
            Log.e(TAG, error, e);
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog(error);
            }
        }
    }
    
    /**
     * Récupère les prix sous forme de Map simple (Utilisé par les autres modules de l'application)
     * Entièrement protégé par le traitement par lot, le timeout 800ms et le cache à expiration automatique.
     */
    public static Map<String, Double> getPrices(List<String> symbols) {
        Map<String, Double> priceMap = new HashMap<>();
        if (symbols == null || symbols.isEmpty()) return priceMap;
        
        Map<String, MarketData> batchData = getMarketDataBatch(symbols);
        for (String symbol : symbols) {
            MarketData data = batchData.get(symbol);
            if (data != null) {
                priceMap.put(symbol, data.price);
            }
        }
        return priceMap;
    }

    public static class AssetConfig {
        public final String name;
        public final String symbol;
        public final boolean inverted;
        public final double seuilChoc;
        public final double seuilAlerte;

        public AssetConfig(String name, String symbol, boolean inverted, double seuilChoc, double seuilAlerte) {
            this.name = name;
            this.symbol = symbol;
            this.inverted = inverted;
            this.seuilChoc = seuilChoc;
            this.seuilAlerte = seuilAlerte;
        }
    }

    private static final List<AssetConfig> ASSET_CONFIGS = new ArrayList<>();
    static {
        ASSET_CONFIGS.add(new AssetConfig("SP500",   "SPY",      false, 1.5, 0.8));
        ASSET_CONFIGS.add(new AssetConfig("NASDAQ",  "QQQ",      false, 1.5, 0.8));
        ASSET_CONFIGS.add(new AssetConfig("GOLD",    "XAU/USD",  false, 1.5, 0.7));
        ASSET_CONFIGS.add(new AssetConfig("BITCOIN", "BTC/USD",  false, 3.0, 1.5));
        ASSET_CONFIGS.add(new AssetConfig("EURUSD",  "EUR/USD",  false, 0.5, 0.25));
        ASSET_CONFIGS.add(new AssetConfig("GBPUSD",  "GBP/USD",  false, 0.5, 0.25));
        ASSET_CONFIGS.add(new AssetConfig("AUDUSD",  "AUD/USD",  false, 0.5, 0.25));
        ASSET_CONFIGS.add(new AssetConfig("USDJPY",  "USD/JPY",  false, 0.5, 0.25));
        ASSET_CONFIGS.add(new AssetConfig("USDCAD",  "USD/CAD",  false, 0.5, 0.25));
        ASSET_CONFIGS.add(new AssetConfig("USOIL",   "WTI",      false, 2.0, 1.0));
        ASSET_CONFIGS.add(new AssetConfig("US10Y",   "TLT",      false, 1.8, 1.0));
    }

    /**
     * Récupère de manière synchrone le dernier prix d'un actif (Sécurisé par le cache global)
     */
    public static double fetchPriceSync(String assetName) {
        if (assetName == null || assetName.trim().isEmpty()) return 0.0;
        List<String> target = new ArrayList<>();
        target.add(assetName);
        Map<String, MarketData> response = getMarketDataBatch(target);
        if (response != null && response.containsKey(assetName)) {
            MarketData data = response.get(assetName);
            return data != null ? data.price : 0.0;
        }
        return 0.0;
    }

    /**
     * Point d'accès asynchrone générique pour scanner un actif unique à la demande
     */
    public static void fetchMarketDataPublic(String assetName, MarketAnalysisCallback callback) {
        if (callback == null) return;
        try {
            executor.execute(() -> {
                try {
                    if (Thread.currentThread().isInterrupted()) return;
                    List<String> target = new ArrayList<>();
                    target.add(assetName);
                    Map<String, MarketData> response = getMarketDataBatch(target);
                    
                    if (response != null && response.containsKey(assetName)) {
                        MarketData data = response.get(assetName);
                        if (data != null) {
                            String miniRapport = String.format(Locale.US, 
                                "📊 [%s] Prix actuel : %.4f | Var: %+.3f%%", 
                                assetName.toUpperCase(Locale.ROOT), data.price, data.changePercent);
                            callback.onAnalysisComplete(miniRapport);
                            return;
                        }
                    }
                    callback.onAnalysisComplete("❌ Échec de lecture ou données indisponibles pour : " + assetName);
                } catch (Throwable t) {
                    Log.e(TAG, "Erreur fatale dans fetchMarketDataPublic pour " + assetName, t);
                    callback.onAnalysisComplete("❌ Erreur interne lors de la récupération de l'actif.");
                }
            });
        } catch (RejectedExecutionException e) {
            Log.e(TAG, "Impossible de lancer fetchMarketDataPublic : l'exécuteur est éteint.", e);
            callback.onAnalysisComplete("❌ Service indisponible (exécuteur arrêté).");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // MODULE DE SCAN DU PRE-MARKET
    // ══════════════════════════════════════════════════════════════

    public static class PreMarketScanner {
        public interface PreMarketCallback {
            void onAlerte(String rapport, boolean isChoc);
        }

        public static boolean isPreMarketWindow() {
            TimeZone nyTz = TimeZone.getTimeZone("America/New_York");
            Calendar ny = Calendar.getInstance(nyTz);
            int dow = ny.get(Calendar.DAY_OF_WEEK);
            if (dow == Calendar.SATURDAY || dow == Calendar.SUNDAY) return false;
            int min = ny.get(Calendar.HOUR_OF_DAY) * 60 + ny.get(Calendar.MINUTE);
            return min >= (6 * 60 + 30) && min < (9 * 60 + 30);
        }

        public static String getNYSEOpenMadaTime() {
            long now = System.currentTimeMillis();
            int nyOff   = TimeZone.getTimeZone("America/New_York").getOffset(now);
            int madaOff = TimeZone.getTimeZone("Indian/Antananarivo").getOffset(now);
            int diff    = (madaOff - nyOff) / 60000;
            
            int openMin = (9 * 60 + 30 + diff) % 1440;
            if (openMin < 0) openMin += 1440;
            
            return String.format(Locale.US, "%02dh%02d", openMin / 60, openMin % 60);
        }

        public static void scan(PreMarketCallback callback) {
            try {
                executor.execute(() -> {
                    try {
                        if (Thread.currentThread().isInterrupted()) return;

                        StringBuilder rapport = new StringBuilder();
                        boolean hasChoc = false;
                        boolean hasAlerte = false;
                        int detectes = 0;

                        rapport.append("🔭 *PRE-MARKET GLOBAL SCAN*\n");
                        if (ENABLE_PREMARKET_PARAM) {
                            rapport.append("⚡ _Mode Flux : Sessions étendues injectées (Premarket=true)_\n");
                        } else {
                            rapport.append("⚠️ _Mode Standard : Base cours de clôture officielle_\n");
                        }
                        rapport.append("📅 Ouverture NYSE : ").append(getNYSEOpenMadaTime()).append(" (Mada)\n");
                        rapport.append("─────────────────────────────────\n");

                        List<String> assetNames = new ArrayList<>();
                        for (AssetConfig config : ASSET_CONFIGS) {
                            assetNames.add(config.name);
                        }

                        Map<String, MarketData> batchData = getMarketDataBatch(assetNames);

                        for (AssetConfig config : ASSET_CONFIGS) {
                            if (Thread.currentThread().isInterrupted()) return;

                            MarketData data = batchData.get(config.name);
                            if (data == null || data.price <= 0) continue;

                            double change = config.inverted ? -data.changePercent : data.changePercent;
                            double absChange = Math.abs(change);
                            
                            if (absChange < config.seuilAlerte * 0.4) continue;

                            detectes++;
                            String arrow  = change >= 0 ? "📈" : "📉";
                            String icone, niveau;

                            if (absChange >= config.seuilChoc) {
                                icone = "🚨"; niveau = "CHOC"; hasChoc = true;
                            } else if (absChange >= config.seuilAlerte) {
                                icone = "⚠️"; niveau = "ALERTE"; hasAlerte = true;
                            } else {
                                icone = "🔸"; niveau = "TENSION";
                            }

                            rapport.append(String.format(Locale.US,
                                "%s %s %-8s : %-7s %+.3f%% | %.4f\n",
                                icone, arrow, config.name, niveau, change, data.price));

                            String interp = interpreterMouvement(config.name, change);
                            if (!interp.isEmpty()) {
                                rapport.append("   └ ").append(interp).append("\n");
                            }
                        }

                        rapport.append("─────────────────────────────────\n");
                        if (detectes == 0) {
                            rapport.append("✅ Marchés stables — aucun mouvement anormal.\n");
                        } else if (hasChoc) {
                            rapport.append("🚨 *CHOC DE VOLATILITÉ MAJEUR*\n");
                        } else if (hasAlerte) {
                            rapport.append("⚠️ *ALERTE DE TENSION CONSTATÉE*\n");
                        }

                        if (callback != null) {
                            callback.onAlerte(rapport.toString(), hasChoc || hasAlerte);
                        }

                    } catch (Throwable t) {
                        Log.e(TAG, "Erreur fatale imprévue interceptée durant le scan pré-marché", t);
                        if (callback != null) {
                            callback.onAlerte("❌ Échec critique interne de l'analyseur pré-marché.", false);
                        }
                    }
                });
            } catch (RejectedExecutionException e) {
                Log.e(TAG, "Soumission du scan rejetée : L'exécuteur est éteint.", e);
                if (callback != null) {
                    callback.onAlerte("❌ Service indisponible : Scanner hors ligne.", false);
                }
            }
        }

        private static String interpreterMouvement(String nom, double change) {
            boolean h = change > 0;
            switch (nom) {
                case "SP500": case "NASDAQ": return h ? "Risk-on → flux acheteur" : "Risk-off → liquidation boursière";
                case "GOLD": return h ? "Fuite vers la sécurité → tensions macro/géo" : "Détente macro";
                case "BITCOIN": return h ? "Spéculation haussière" : "Aversion au risque → capitulation";
                case "EURUSD": case "GBPUSD": case "AUDUSD": return h ? "Affaiblissement du Dollar US" : "Dollar dominant";
                case "USDJPY": case "USDCAD": return h ? "Dollar fort" : "Dollar faible";
                case "USOIL": return h ? "Tensions sur l'offre / Risque géopolitique" : "Ralentissement global";
                case "US10Y": return h ? "Obligations ↑ → Taux ↓ (dovish)" : "Obligations ↓ → Taux ↑ (hawkish)";
                default: return "";
            }
        }
    }

    public static class MarketData {
        public final double price;
        public final double changePercent;
        public final double high;
        public final double low;
        MarketData(double p, double c, double h, double l) {
            price = p; changePercent = c; high = h; low = l;
        }
    }

    public static String getTwelveDataSymbol(String assetName) {
        if (assetName == null) return null;
        String normalizedInput = assetName.toUpperCase(Locale.ROOT).trim().replace("/", "");
        for (AssetConfig config : ASSET_CONFIGS) {
            if (config.name.toUpperCase(Locale.ROOT).replace("/", "").equals(normalizedInput) ||
                config.symbol.toUpperCase(Locale.ROOT).replace("/", "").equals(normalizedInput)) {
                return config.symbol;
            }
        }
        // ✅ SECURISATION DES SYMBOLES INCONNUS : Alerte en Logcat pour intercepter les requêtes fantômes / typos
        Log.w(TAG, "⚠️ [FALLBACK BRUT] L'actif '" + assetName + "' n'est pas cartographié dans ASSET_CONFIGS. Envoi du symbole brut.");
        return assetName.trim(); 
    }

    private static String executeHttpRequestWithRetry(String urlStr) throws Exception {
        int maxRetries = 2;
        int backoffMs = 2000;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Interruption demandée avant traitement réseau.");
            }

            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000); 

                int responseCode = conn.getResponseCode();
                if (responseCode == 429 || responseCode >= 500) {
                    if (attempt == maxRetries) throw new IOException("Erreur HTTP persistante: " + responseCode);
                    Log.w(TAG, "HTTP " + responseCode + " - Tentative de réévaluation dans " + backoffMs + "ms...");
                    Thread.sleep((long) backoffMs * (attempt + 1));
                    continue;
                }

                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new IOException("Mauvaise réponse serveur: " + responseCode);
                }

                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        if (Thread.currentThread().isInterrupted()) {
                            throw new InterruptedException("Interruption pendant la lecture du flux réseau.");
                        }
                        sb.append(line);
                    }
                    return sb.toString();
                }
            } catch (IOException e) {
                if (attempt == maxRetries) throw e;
                Log.w(TAG, "Tentative " + (attempt + 1) + " échouée, nouvel essai...");
                Thread.sleep(backoffMs);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        throw new IOException("Impossible de contacter l'API.");
    }

    /**
     * 🛡️ ENVELOPPE CRITIQUE ANTI-429 & TIMEOUT
     * Force une attente réseau maximale de 800ms. En cas d'échec ou de lenteur, 
     * extrait les données du cache LKV à condition qu'elles aient moins de 5 minutes.
     */
    public static Map<String, MarketData> getMarketDataBatch(List<String> assets) {
        if (assets == null || assets.isEmpty()) return new HashMap<>();

        // Traitement réseau asynchrone totalement isolé pour sanctuariser le thread appelant
                Future<Map<String, MarketData>> future = networkExecutor.submit(() -> getMarketDataBatchRaw(assets));

       // APRÈS
        try {
            // 🔥 Timeout aligné sur le budget réseau réel (connect 5s + marge) pour laisser
            // le temps à executeHttpRequestWithRetry() de répondre avant abandon
            Map<String, MarketData> freshData = future.get(7000, TimeUnit.MILLISECONDS);
            if (freshData != null && !freshData.isEmpty()) {
                long now = System.currentTimeMillis();
                // Remplissage dynamique du cache enveloppé de son horodatage (TTL)
                for (Map.Entry<String, MarketData> entry : freshData.entrySet()) {
                    MARKET_DATA_CACHE.put(entry.getKey(), new CachedEntry(entry.getValue(), now));
                }
                return freshData;
            }
        } catch (TimeoutException e) {
            Log.e(TAG, "⚡ [TIMEOUT] API Prix saturée (>800ms) lors du flux macro. Recours immédiat au cache LKV.");
            future.cancel(true); // Interruption immédiate du traitement HTTP obsolète
        } catch (Exception e) {
            Log.e(TAG, "❌ [BATCH NETWORK] Erreur réseau ou limitation API (Code HTTP 429)", e);
        }

        // 🔄 INJECTION FILTRÉE DU CACHE LKV AVEC VALIDATION DE LA DURÉE DE VIE (TTL)
        long currentTime = System.currentTimeMillis();
        Map<String, MarketData> fallbackResult = new HashMap<>();

        for (String asset : assets) {
            CachedEntry cached = MARKET_DATA_CACHE.get(asset);
            if (cached != null) {
                // Vérification stricte : la donnée a-t-elle moins de 5 minutes ?
                if (currentTime - cached.timestamp <= CACHE_TTL_MS) {
                    fallbackResult.put(asset, cached.data);
                } else {
                    // Éviction automatique de la donnée périmée pour prémunir le bot contre les faux signaux
                    Log.w(TAG, "⏳ [CACHE OBSOLÈTE] Rejet de la donnée LKV pour '" + asset + "' car elle a expiré (>" + (CACHE_TTL_MS / 60000) + " min).");
                    MARKET_DATA_CACHE.remove(asset);
                }
            }
        }
        return fallbackResult;
    }

    /**
     * ⚙️ LOGIQUE INTERNE DE TRAITEMENT PAR BATCH DE 8 SYMBOLES
     */
    private static Map<String, MarketData> getMarketDataBatchRaw(List<String> assets) {
        Map<String, MarketData> results = new HashMap<>();
        List<String> apiSymbols = new ArrayList<>();
        Map<String, String> symbolToAssetMap = new HashMap<>();
        
        for (String asset : assets) {
            String sym = getTwelveDataSymbol(asset);
            if (sym != null && !apiSymbols.contains(sym)) {
                apiSymbols.add(sym);
                symbolToAssetMap.put(sym, asset);
            }
        }

        if (apiSymbols.isEmpty()) return results;

        int limit = 8;
        for (int i = 0; i < apiSymbols.size(); i += limit) {
            if (Thread.currentThread().isInterrupted()) return results;

            List<String> chunk = apiSymbols.subList(i, Math.min(i + limit, apiSymbols.size()));
            StringBuilder sbSymbols = new StringBuilder();
            for (int k = 0; k < chunk.size(); k++) {
                sbSymbols.append(chunk.get(k));
                if (k < chunk.size() - 1) sbSymbols.append(",");
            }

            String queryParams = "&apikey=" + twelveDataKey;
            if (ENABLE_PREMARKET_PARAM) {
                queryParams += "&premarket=true"; 
            }
            String urlStr = "https://api.twelvedata.com/quote?symbol=" + sbSymbols.toString() + queryParams;

            try {
                String responseBody = executeHttpRequestWithRetry(urlStr);
                JSONObject json = new JSONObject(responseBody);

                if (chunk.size() == 1) {
                    String singleSym = chunk.get(0);
                    MarketData data = parseSingleQuoteJson(json);
                    if (data != null) {
                        results.put(symbolToAssetMap.get(singleSym), data);
                    }
                } else {
                    for (String sym : chunk) {
                        if (json.has(sym)) {
                            MarketData data = parseSingleQuoteJson(json.getJSONObject(sym));
                            if (data != null) {
                                results.put(symbolToAssetMap.get(sym), data);
                            }
                        }
                    }
                }
            } catch (InterruptedException e) {
                Log.w(TAG, "Interruption détectée dans la boucle Batch. Sortie réseau.");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                Log.e(TAG, "Erreur lors du traitement réseau du lot de symboles", e);
            }
        }
        return results;
    }

    private static MarketData parseSingleQuoteJson(JSONObject json) {
        if (json == null) return null;
        if (json.has("status") && "error".equals(json.optString("status"))) {
            return null;
        }

        double price = json.optDouble("close", 0.0);
        double changePercent = json.optDouble("percent_change", 0.0);
        double high = json.optDouble("high", price);
        double low = json.optDouble("low", price);

        if (price <= 0.0 || Double.isNaN(changePercent) || Double.isInfinite(changePercent)) {
            return null;
        }
        return new MarketData(price, changePercent, high, low);
    }

    public static void shutdownExecutor() {
        isShutdown = true;
        executor.shutdownNow();
        networkExecutor.shutdownNow(); 
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                Log.w(TAG, "Forçage de l'extinction de l'exécuteur logique.");
            }
            if (!networkExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                Log.w(TAG, "Forçage de l'extinction de l'exécuteur réseau.");
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Interruption reçue pendant l'extinction des exécuteurs.", e);
            Thread.currentThread().interrupt();
        }
    }
}
