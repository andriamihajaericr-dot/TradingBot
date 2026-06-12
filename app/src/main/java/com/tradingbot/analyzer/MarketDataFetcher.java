package com.tradingbot.analyzer;

import android.util.Log;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MarketDataFetcher {
    private static final String TAG = "MarketDataFetcher";

    // 🔑 Clé API Twelve Data (usage personnel)
    private static final String TWELVE_DATA_KEY = "32370e1ef17645eb86690e3aee0d0660";

    // Executor partagé pour tous les scans (un seul thread)
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    // Indique si l'executor a été arrêté (pour éviter de nouvelles soumissions)
    private static volatile boolean isShutdown = false;

    public interface MarketAnalysisCallback {
        void onAnalysisComplete(String report);
    }

    // ══════════════════════════════════════════════════════════════
    // MAPPING DES 11 ACTIFS AVEC SYMBOLES TWELVE DATA
    // ══════════════════════════════════════════════════════════════
    private static final Object[][] ASSET_MAP = {
        // {NomActif, Symbole, Inverted, SeuilChoc%, SeuilAlerte%}
        {"SP500",   "SPY",      false, 1.5, 0.8},
        {"NASDAQ",  "QQQ",      false, 1.5, 0.8},
        {"GOLD",    "XAU/USD",  false, 1.5, 0.7},
        {"BITCOIN", "BTC/USD",  false, 3.0, 1.5},
        {"EURUSD",  "EUR/USD",  false, 0.5, 0.25},
        {"GBPUSD",  "GBP/USD",  false, 0.5, 0.25},
        {"AUDUSD",  "AUD/USD",  false, 0.5, 0.25},
        // Pour USD/JPY et USD/CAD, inverted = true car une hausse du symbole = dollar fort = mouvement interprété comme baissier pour la devise ? 
        // Non : dans l'interprétation, on a "Dollar fort" pour une variation positive. Or USD/JPY en hausse = dollar fort, donc inverted=false. 
        // Mais par cohérence avec l'inversion d'origine (Finnhub), on laisse à false car Twelve Data donne déjà la variation correcte.
        {"USDJPY",  "USD/JPY",  false, 0.5, 0.25},
        {"USDCAD",  "USD/CAD",  false, 0.5, 0.25},
        {"USOIL",   "WTI",      false, 2.0, 1.0},
        {"US10Y",   "TLT",      false, 1.8, 1.0}   // Seuil ajusté pour TLT (volatilité ~1% par jour)
    };

    // ══════════════════════════════════════════════════════════════
    // PRE-MARKET SCANNER
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
            int openMin = 9 * 60 + 30 + diff;
            return String.format(Locale.US, "%02dh%02d", openMin / 60, openMin % 60);
        }

        /**
         * Lance un scan asynchrone. Ne fait rien si l'executor a été arrêté.
         */
        public static void scan(PreMarketCallback callback) {
            if (isShutdown) {
                Log.w(TAG, "Executor arrêté, scan ignoré.");
                return;
            }
            executor.execute(() -> {
                StringBuilder rapport = new StringBuilder();
                boolean hasChoc   = false;
                boolean hasAlerte = false;
                int detectes = 0;

                rapport.append("🔭 *PRE-MARKET GLOBAL SCAN (TWELVE DATA)*\n");
                rapport.append("📅 Ouverture NYSE : ").append(getNYSEOpenMadaTime()).append(" (Mada)\n");
                rapport.append("─────────────────────────────────\n");

                for (Object[] asset : ASSET_MAP) {
                    String nom       = (String)  asset[0];
                    String symbol    = (String)  asset[1];
                    boolean inverted = (Boolean) asset[2];
                    double sChoc     = (Double)  asset[3];
                    double sAlerte   = (Double)  asset[4];

                    MarketData data = fetchMarketData(symbol);
                    if (data == null || data.price <= 0) {
                        Log.d(TAG, "Actif ignoré (données invalides) : " + nom);
                        continue;
                    }

                    double change = inverted ? -data.changePercent : data.changePercent;
                    double absChange = Math.abs(change);
                    // Filtrage anti-bruit : ignore les variations inférieures à 40% du seuil d'alerte
                    if (absChange < sAlerte * 0.4) continue;

                    detectes++;
                    String arrow  = change >= 0 ? "📈" : "📉";
                    String icone, niveau;

                    if (absChange >= sChoc) {
                        icone = "🚨"; niveau = "CHOC"; hasChoc = true;
                    } else if (absChange >= sAlerte) {
                        icone = "⚠️"; niveau = "ALERTE"; hasAlerte = true;
                    } else {
                        icone = "🔸"; niveau = "TENSION";
                    }

                    rapport.append(String.format(Locale.US,
                        "%s %s %-8s : %-7s %+.3f%% | %.4f\n",
                        icone, arrow, nom, niveau, change, data.price));

                    String interp = interpreterMouvement(nom, change);
                    if (!interp.isEmpty()) {
                        rapport.append("   └ ").append(interp).append("\n");
                    }
                }

                rapport.append("─────────────────────────────────\n");
                if (detectes == 0) {
                    rapport.append("✅ Marchés stables — aucun mouvement anormal.\n");
                } else if (hasChoc) {
                    rapport.append("🚨 *CHOC DE VOLATILITÉ MAJEUR*\n");
                    rapport.append("→ Risque de propagation. Gel temporaire requis.\n");
                } else if (hasAlerte) {
                    rapport.append("⚠️ *ALERTE DE TENSION CONSTATÉE*\n");
                    rapport.append("→ Stratégie prudente recommandée.\n");
                }

                callback.onAlerte(rapport.toString(), hasChoc || hasAlerte);
            });
        }

        private static String interpreterMouvement(String nom, double change) {
            boolean h = change > 0;
            switch (nom) {
                case "SP500":
                case "NASDAQ":
                    return h ? "Risk-on → flux acheteur sur les indices" : "Risk-off → liquidation boursière";
                case "GOLD":
                    return h ? "Fuite vers la sécurité → tensions macro/géo" : "Détente macro → or délaissé";
                case "BITCOIN":
                    return h ? "Spéculation haussière → appétit pour le risque" : "Aversion au risque → capitulation crypto";
                case "EURUSD":
                case "GBPUSD":
                case "AUDUSD":
                    return h ? "Affaiblissement du Dollar US" : "Dollar dominant → pressions devises";
                case "USDJPY":
                    return h ? "Dollar fort face au Yen" : "Yen fort (dollar faible)";
                case "USDCAD":
                    return h ? "Dollar fort face au CAD" : "CAD fort (dollar faible)";
                case "USOIL":
                    return h ? "Tensions sur l'offre / Risque géopolitique" : "Surplus d'offre / Ralentissement global";
                case "US10Y":
                    return h ? "Prix des obligations en hausse → Taux en baisse (dovish)" : "Prix des obligations en baisse → Taux en hausse (hawkish)";
                default: return "";
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // STRUCTURE DE DONNÉES UNIFIÉE
    // ══════════════════════════════════════════════════════════════
    public static class MarketData {
        public final double price;
        public final double changePercent;
        public final double high;
        public final double low;
        MarketData(double p, double c, double h, double l) {
            price = p; changePercent = c; high = h; low = l;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // REQUÊTE UNIFIÉE TWELVE DATA AVEC GESTION D'ERREUR AMÉLIORÉE
    // ══════════════════════════════════════════════════════════════
    
    /**
     * Traduit dynamiquement un nom d'actif (ex: "GOLD", "WTI", "EURUSD") en symbole Twelve Data strict.
     * Parcourt l'ASSET_MAP pour assurer une corrélation sans faille et une maintenance centralisée.
     */
    public static String getTwelveDataSymbol(String assetName) {
        if (assetName == null) return null;
        // Normalisation stricte de l'entrée (ex: "EUR/USD" ou "eurusd" -> "EURUSD")
        String normalizedInput = assetName.toUpperCase(Locale.ROOT).trim().replace("/", "");
        
        for (Object[] asset : ASSET_MAP) {
            String nomConfig = ((String) asset[0]).toUpperCase(Locale.ROOT).replace("/", "");
            String symConfig = ((String) asset[1]).toUpperCase(Locale.ROOT).replace("/", "");
            
            // Si l'entrée correspond au nom générique ou au symbole configuré
            if (nomConfig.equals(normalizedInput) || symConfig.equals(normalizedInput)) {
                return (String) asset[1]; // Renvoie le symbole officiel (ex: "XAU/USD", "USD/JPY")
            }
        }
        return assetName; // Protection fallback : si non trouvé, renvoie la chaîne d'origine
    }

    // ✅ Passerelle publique pour injection sécurisée dans NotificationService
    public static MarketData fetchMarketDataPublic(String symbol) {
        return fetchMarketData(symbol);
    }

    // 🔒 Logique d'infrastructure réseau d'origine (CONSERVÉE À 100% À L'IDENTIQUE)
    private static MarketData fetchMarketData(String symbol) {
        String urlStr = "https://api.twelvedata.com/quote?symbol=" + symbol + "&apikey=" + TWELVE_DATA_KEY;
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "HTTP " + responseCode + " pour " + symbol);
                return null;
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            JSONObject json = new JSONObject(sb.toString());

            // Vérification des erreurs explicites de l'API
            if (json.has("status") && "error".equals(json.optString("status"))) {
                String msg = json.optString("message", "Unknown error");
                Log.e(TAG, "API error pour " + symbol + " : " + msg);
                return null;
            }
            if (json.has("code") && json.optInt("code") != 200) {
                Log.e(TAG, "API code error " + json.optInt("code") + " pour " + symbol);
                return null;
            }

            double price = json.optDouble("close", 0.0);
            double changePercent = json.optDouble("percent_change", 0.0);
            double high = json.optDouble("high", price);
            double low = json.optDouble("low", price);

            if (price <= 0.0) {
                Log.w(TAG, "Prix invalide (<=0) pour " + symbol);
                return null;
            }
            return new MarketData(price, changePercent, high, low);
        } catch (Exception e) {
            Log.e(TAG, "Exception Twelve Data (" + symbol + "): " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
    /**
     * Récupération synchrone d'un prix (pour usage ponctuel).
     */
    public static double fetchPriceSync(String symbol) {
        MarketData data = fetchMarketData(symbol);
        return (data != null) ? data.price : 0.0;
    }

    /**
     * Arrête proprement l'ExecutorService. À appeler dans onDestroy() du service ou de l'activité.
     */
    public static void shutdownExecutor() {
        isShutdown = true;
        executor.shutdownNow(); // Interrompt les tâches en cours
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                Log.w(TAG, "Executor n'a pas terminé dans les délais");
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Interruption lors de l'arrêt de l'executor", e);
            Thread.currentThread().interrupt();
        }
    }
}
