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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

public class MarketDataFetcher {
    private static final String TAG = "MarketDataFetcher";

    private static volatile String twelveDataKey = "32370e1ef17645eb86690e3aee0d0660";
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static volatile boolean isShutdown = false;

    // ✅ POINTS 1 & 2 : Stabilisation sur l'endpoint /quote & gestion prudente du paramètre étendu
    private static final boolean ENABLE_PREMARKET_PARAM = true; 

    public interface MarketAnalysisCallback {
        void onAnalysisComplete(String report);
    }

    public static void setApiKey(String key) {
        if (key != null && !key.trim().isEmpty()) {
            twelveDataKey = key;
        }
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

    // ══════════════════════════════════════════════════════════════
    // ✅ POINT 3 : RÉINTRODUCTION DES MÉTHODES UTILITAIRES HISTORIQUES
    // ══════════════════════════════════════════════════════════════
    
    /**
     * Récupère de manière synchrone le dernier prix d'un actif (Utile pour les calculs internes)
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
        return null; 
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
                
                // ✅ POINT 5 : Augmentation des seuils de tolérance réseau pour absorber la latence mobile
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(10000); // 10 secondes pour sécuriser la lecture du flux complet

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

    public static Map<String, MarketData> getMarketDataBatch(List<String> assets) {
        Map<String, MarketData> results = new HashMap<>();
        if (assets == null || assets.isEmpty()) return results;

        List<String> apiSymbols = new ArrayList<>();
        Map<String, String> symbolToAssetMap = new HashMap<>();
        
        for (String asset : assets) {
            String sym = getTwelveDataSymbol(asset);
            if (sym != null) {
                if (!apiSymbols.contains(sym)) {
                    apiSymbols.add(sym);
                    symbolToAssetMap.put(sym, asset);
                }
            } else {
                Log.w(TAG, "Filtrage pré-requête : Actif ignoré (non présent dans la liste des 11 maîtres) : " + asset);
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

            // ✅ POINTS 1 & 2 : Utilisation stable de /quote + injection sécurisée du premarket facultatif
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
                    } else {
                        Log.w(TAG, "Échec d'extraction des métriques pour l'actif unique : " + singleSym);
                    }
                } else {
                    for (String sym : chunk) {
                        if (json.has(sym)) {
                            MarketData data = parseSingleQuoteJson(json.getJSONObject(sym));
                            if (data != null) {
                                results.put(symbolToAssetMap.get(sym), data);
                            } else {
                                Log.w(TAG, "Données corrompues ou nulles reçues pour l'actif : " + sym);
                            }
                        } else {
                            Log.w(TAG, "Symbole absent de la réponse Twelve Data : " + sym);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Log.w(TAG, "Interruption détectée dans la boucle Batch. Sortie immédiate.");
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

        // ✅ POINT 1 : Extraction stable sur l'endpoint /quote uniquement
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
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                Log.w(TAG, "Forçage de l'extinction de l'exécuteur de tâches.");
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Interruption reçue pendant l'extinction forcée.", e);
            Thread.currentThread().interrupt();
        }
    }
}
