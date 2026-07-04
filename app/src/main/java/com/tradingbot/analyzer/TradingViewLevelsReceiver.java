package com.tradingbot.analyzer;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TradingViewLevelsReceiver
 *
 * Reçoit les niveaux PDH/PDL/PWH/PWL envoyés par TradingView via webhook
 * et les injecte dans TradingViewFetcher.
 *
 * Flux :
 * TradingView Pine Script
 *   → Webhook → Cloudflare Worker
 *     → Telegram Bot
 *       → NotificationService capte la notification
 *         → parseTradingViewLevel() ici
 *           → pdhCache/pdlCache/pwhCache/pwlCache mis à jour
 */
public class TradingViewLevelsReceiver {

    private static final String TAG = "TVLevelsReceiver";

    // ── Niveaux clés reçus depuis TradingView ──
    // Clé = "SYMBOL_TYPE" ex: "EURUSD_daily_high"
    private static final ConcurrentHashMap<String, Double> levels = new ConcurrentHashMap<>();

    // ── Sources des notifications Telegram à intercepter ──
    private static final String TV_LEVEL_TAG  = "#LEVELS";
    private static final String TV_TOUCH_TAG  = "#CONTACT";
    private static final String TV_SIGNAL_TAG = "#SIGNAL";

    /**
     * Appelé depuis NotificationService quand une notification Telegram arrive.
     * Vérifie si c'est un message TradingView webhook et le parse.
     *
     * @param notificationText texte brut de la notification Telegram
     * @param context context Android
     * @return true si le message a été reconnu comme un message TradingView
     */
    public static boolean interceptAndParse(String notificationText, Context context) {
        if (notificationText == null || notificationText.isEmpty()) return false;

        // Détecter les messages TradingView webhook
        if (notificationText.contains(TV_LEVEL_TAG)) {
            parseAndStoreLevels(notificationText, context);
            return true;
        }
        if (notificationText.contains(TV_TOUCH_TAG)) {
            logContact(notificationText, context);
            return false; // Laisser passer pour affichage
        }
        if (notificationText.contains(TV_SIGNAL_TAG)) {
            logSignal(notificationText, context);
            return false; // Laisser passer pour affichage
        }
        return false;
    }

    /**
     * Parse le message de niveaux et met à jour TradingViewFetcher.
     * Format attendu (envoyé par le Cloudflare Worker) :
     * NIVEAUX CLÉS MIS À JOUR
     * EURUSD
     * DAILY High : 1.1434
     * DAILY Low  : 1.1354
     * WEEKLY High : 1.1520
     * WEEKLY Low  : 1.1280
     * ...
     */
    private static void parseAndStoreLevels(String text, Context context) {
        try {
            // Parser les valeurs depuis le texte formaté Telegram
            // Format : "🔺 High : `1.1434`"
            String symbol = extractValue(text, "📌 *", "*\n");
            if (symbol == null || symbol.isEmpty()) {
                Log.w(TAG, "[TVLevels] Symbole non trouvé dans : " + text.substring(0, Math.min(100, text.length())));
                return;
            }

            // Extraire les niveaux
            double dailyHigh   = parseLevel(text, "DAILY", "High");
            double dailyLow    = parseLevel(text, "DAILY", "Low");
            double weeklyHigh  = parseLevel(text, "WEEKLY", "High");
            double weeklyLow   = parseLevel(text, "WEEKLY", "Low");
            double monthlyHigh = parseLevel(text, "MONTHLY", "High");
            double monthlyLow  = parseLevel(text, "MONTHLY", "Low");
            double h4High      = parseLevel(text, "H4", "High");
            double h4Low       = parseLevel(text, "H4", "Low");

            // Stocker dans le cache local
            if (dailyHigh  > 0) levels.put(symbol + "_daily_high",   dailyHigh);
            if (dailyLow   > 0) levels.put(symbol + "_daily_low",    dailyLow);
            if (weeklyHigh > 0) levels.put(symbol + "_weekly_high",  weeklyHigh);
            if (weeklyLow  > 0) levels.put(symbol + "_weekly_low",   weeklyLow);
            if (monthlyHigh> 0) levels.put(symbol + "_monthly_high", monthlyHigh);
            if (monthlyLow > 0) levels.put(symbol + "_monthly_low",  monthlyLow);
            if (h4High     > 0) levels.put(symbol + "_h4_high",      h4High);
            if (h4Low      > 0) levels.put(symbol + "_h4_low",       h4Low);

            // Injecter dans TradingViewFetcher pour les alertes de cassure
            String tvKey = symbolToTVKey(symbol);
            if (tvKey != null) {
                if (dailyHigh > 0) TradingViewFetcher.pdhCache.put(tvKey, dailyHigh);
                if (dailyLow  > 0) TradingViewFetcher.pdlCache.put(tvKey, dailyLow);
                if (weeklyHigh> 0) TradingViewFetcher.pwhCache.put(tvKey, weeklyHigh);
                if (weeklyLow > 0) TradingViewFetcher.pwlCache.put(tvKey, weeklyLow);
                // Reset alertes après mise à jour des niveaux
                TradingViewFetcher.alertFiredPDH.remove(tvKey);
                TradingViewFetcher.alertFiredPDL.remove(tvKey);
                TradingViewFetcher.alertFiredPWH.remove(tvKey);
                TradingViewFetcher.alertFiredPWL.remove(tvKey);
            }

            // Log
            if (MainActivity.instance != null) {
                MainActivity.instance.addLog("✅ [TV LEVELS] " + symbol +
                    " PDH=" + String.format(java.util.Locale.US, "%.4f", dailyHigh) +
                    " PDL=" + String.format(java.util.Locale.US, "%.4f", dailyLow) +
                    " PWH=" + String.format(java.util.Locale.US, "%.4f", weeklyHigh) +
                    " PWL=" + String.format(java.util.Locale.US, "%.4f", weeklyLow));
            }

            // Sauvegarder dans SharedPreferences pour persistance redémarrage
            saveLevelsToPrefs(context, symbol, dailyHigh, dailyLow, weeklyHigh, weeklyLow,
                monthlyHigh, monthlyLow, h4High, h4Low);

            Log.i(TAG, "[TVLevels] " + symbol + " mis à jour : PDH=" + dailyHigh +
                " PDL=" + dailyLow + " PWH=" + weeklyHigh + " PWL=" + weeklyLow);

        } catch (Exception e) {
            Log.e(TAG, "[TVLevels] Erreur parsing : " + e.getMessage());
        }
    }

    /**
     * Sauvegarder les niveaux en SharedPreferences pour survivre au redémarrage
     */
    private static void saveLevelsToPrefs(Context ctx, String symbol,
            double dh, double dl, double wh, double wl,
            double mh, double ml, double h4h, double h4l) {
        try {
            ctx.getSharedPreferences("TVLevels", Context.MODE_PRIVATE).edit()
                .putFloat(symbol + "_dh",  (float) dh)
                .putFloat(symbol + "_dl",  (float) dl)
                .putFloat(symbol + "_wh",  (float) wh)
                .putFloat(symbol + "_wl",  (float) wl)
                .putFloat(symbol + "_mh",  (float) mh)
                .putFloat(symbol + "_ml",  (float) ml)
                .putFloat(symbol + "_h4h", (float) h4h)
                .putFloat(symbol + "_h4l", (float) h4l)
                .apply();
        } catch (Exception e) {
            Log.e(TAG, "[TVLevels] Erreur sauvegarde prefs : " + e.getMessage());
        }
    }

    /**
     * Restaurer les niveaux depuis SharedPreferences au démarrage
     */
    public static void restoreLevelsFromPrefs(Context ctx) {
        try {
            android.content.SharedPreferences prefs =
                ctx.getSharedPreferences("TVLevels", Context.MODE_PRIVATE);
            Map<String, ?> all = prefs.getAll();

            for (Map.Entry<String, ?> entry : all.entrySet()) {
                String key = entry.getKey();
                double val = ((Number) entry.getValue()).doubleValue();
                if (val <= 0) continue;

                // Reconstruire symbol + type depuis la clé
                // ex: "EURUSD_dh" → symbol=EURUSD, type=dh
                int lastUnderscore = key.lastIndexOf("_");
                if (lastUnderscore < 0) continue;
                String symbol = key.substring(0, lastUnderscore);
                String type   = key.substring(lastUnderscore + 1);
                String tvKey  = symbolToTVKey(symbol);
                if (tvKey == null) continue;

                switch (type) {
                    case "dh":
                        TradingViewFetcher.pdhCache.put(tvKey, val);
                        break;
                    case "dl":
                        TradingViewFetcher.pdlCache.put(tvKey, val);
                        break;
                    case "wh":
                        TradingViewFetcher.pwhCache.put(tvKey, val);
                        break;
                    case "wl":
                        TradingViewFetcher.pwlCache.put(tvKey, val);
                        break;
                }
            }

            int count = TradingViewFetcher.pdhCache.size();
            if (MainActivity.instance != null && count > 0) {
                MainActivity.instance.addLog("✅ [TV LEVELS] " + count +
                    " niveaux restaurés depuis SharedPreferences.");
            }
            Log.i(TAG, "[TVLevels] " + count + " niveaux restaurés au démarrage.");

        } catch (Exception e) {
            Log.e(TAG, "[TVLevels] Erreur restauration : " + e.getMessage());
        }
    }

    // ── Utilitaires ──

    private static double parseLevel(String text, String section, String type) {
        try {
            // Chercher "High : `1.1434`" ou "Low  : `1.1354`"
            String marker = type + " : `";
            // On cherche dans la section du bon TF
            int sectionIdx = text.indexOf("*" + section + "*");
            if (sectionIdx < 0) return 0;
            String afterSection = text.substring(sectionIdx);
            int markerIdx = afterSection.indexOf(marker);
            if (markerIdx < 0) return 0;
            int start = markerIdx + marker.length();
            int end = afterSection.indexOf("`", start);
            if (end < 0) return 0;
            String val = afterSection.substring(start, end).trim();
            return Double.parseDouble(val);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String extractValue(String text, String prefix, String suffix) {
        try {
            int start = text.indexOf(prefix);
            if (start < 0) return null;
            start += prefix.length();
            int end = text.indexOf(suffix, start);
            if (end < 0) return null;
            return text.substring(start, end).trim();
        } catch (Exception e) {
            return null;
        }
    }

    // Mapping TradingView ticker → clé interne TradingViewFetcher
    private static String symbolToTVKey(String symbol) {
        Map<String, String> map = new HashMap<String, String>() {{
            put("EURUSD",  "EURUSD");
            put("GBPUSD",  "GBPUSD");
            put("USDJPY",  "USDJPY");
            put("AUDUSD",  "AUDUSD");
            put("USDCAD",  "USDCAD");
            put("XAUUSD",  "GOLD");
            put("GOLD",    "GOLD");
            put("USOIL",   "USOIL");
            put("UKOIL",   "USOIL");
            put("WTICOUSD","USOIL");
            put("NAS100",  "NASDAQ");
            put("QQQ",     "NASDAQ");
            put("NDX",     "NASDAQ");
            put("SPX500",  "US500");
            put("SPY",     "US500");
            put("BTCUSDT", "BITCOIN");
            put("BTCUSD",  "BITCOIN");
            put("DXY",     "DXY");
        }};
        return map.get(symbol.toUpperCase());
    }

    private static void logContact(String text, Context context) {
        Log.i(TAG, "[TVLevels] Contact niveau détecté : " + text.substring(0, Math.min(80, text.length())));
    }

    private static void logSignal(String text, Context context) {
        Log.i(TAG, "[TVLevels] Signal confirmé : " + text.substring(0, Math.min(80, text.length())));
    }

    // ── Accès public aux niveaux ──
    public static double getLevel(String symbol, String type) {
        Double val = levels.get(symbol.toUpperCase() + "_" + type.toLowerCase());
        return val != null ? val : 0;
    }

    public static Map<String, Double> getAllLevels() {
        return java.util.Collections.unmodifiableMap(levels);
    }
}
