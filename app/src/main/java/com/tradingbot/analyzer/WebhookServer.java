package com.tradingbot.analyzer;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class WebhookServer extends NanoHTTPD {

    private static final String TAG = "WebhookServer";
    private final Context context;
    private static final long COOLDOWN_MS = 30 * 60 * 1000; // 30 min

    public WebhookServer(Context context) throws IOException {
        super(8080); // Port 8080
        this.context = context.getApplicationContext();
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        Log.i(TAG, "✅ Serveur Webhook démarré sur le port 8080");
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if (!"/webhook".equals(uri)) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
        }

        try {
            Map<String, String> body = new HashMap<>();
            session.parseBody(body);
            String json = body.get("postData");
            JSONObject obj = new JSONObject(json);

            String event = obj.getString("event");
            String symbol = obj.getString("symbol");
            String asset = mapSymbolToAsset(symbol);

            if ("levels".equals(event)) {
                // Stocker les niveaux
                saveLevels(asset, obj);
                Log.d(TAG, "📊 Niveaux reçus pour " + asset);
            } else if ("touch".equals(event)) {
                String tf = obj.getString("timeframe");
                String level = obj.getString("level");
                double price = obj.getDouble("price");
                String waitingFor = obj.getString("waiting_for");
                String msg = "🔔 *" + asset + "* – " + tf + " " + level + " testé à " + price +
                        "\n⏳ On attend un " + waitingFor.replace("_", " ") + " pour confirmation.";
                NotificationService.sendTelegramSecure(msg, context);
            } else if ("confirmed".equals(event)) {
                String tf = obj.getString("timeframe");
                String level = obj.getString("level");
                String direction = obj.getString("direction");
                String revTF = obj.getString("reversal_timeframe");
                double price = obj.getDouble("price");
                String action = direction.equals("Bullish") ? "Rebond probable" : "Retournement baissier";
                String msg = "✅ *" + asset + "* – " + tf + " " + level + " testé à " + price +
                        "\n🔄 Reversal *" + direction + "* " + revTF + " confirmé – " + action + ".";
                NotificationService.sendTelegramSecure(msg, context);
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Erreur traitement webhook", e);
        }
        return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK");
    }

    // ── Mapping des symboles TradingView → vos noms d'actifs ──
    private String mapSymbolToAsset(String tvSymbol) {
        switch (tvSymbol) {
            case "OANDA:XAUUSD": return "GOLD";
            case "FX:GBPUSD":    return "GBPUSD";
            case "NASDAQ:QQQ":   return "NASDAQ";
            case "OANDA:SPX500USD": return "SP500";
            case "FX:USDJPY":    return "USDJPY";
            case "TVC:USOIL":    return "USOIL";
            default: return tvSymbol;
        }
    }

    // ── Sauvegarde et Injection en temps réel des niveaux ──
    private void saveLevels(String asset, JSONObject obj) throws org.json.JSONException {
        double dh = obj.getDouble("daily_high");
        double dl = obj.getDouble("daily_low");
        double wh = obj.getDouble("weekly_high");
        double wl = obj.getDouble("weekly_low");
        double mh = obj.getDouble("monthly_high");
        double ml = obj.getDouble("monthly_low");
        double h4h = obj.getDouble("h4_high");
        double h4l = obj.getDouble("h4_low");

        // 1. Stockage local pour le prochain redémarrage
        SharedPreferences prefs = context.getSharedPreferences("KeyLevels", Context.MODE_PRIVATE);
        prefs.edit()
            .putFloat(asset + "_daily_high", (float) dh)
            .putFloat(asset + "_daily_low", (float) dl)
            .putFloat(asset + "_weekly_high", (float) wh)
            .putFloat(asset + "_weekly_low", (float) wl)
            .putFloat(asset + "_monthly_high", (float) mh)
            .putFloat(asset + "_monthly_low", (float) ml)
            .putFloat(asset + "_h4_high", (float) h4h)
            .putFloat(asset + "_h4_low", (float) h4l)
            .apply();

        // 2. Injection directe dans le moteur d'alerte temps réel de l'application
        TradingViewFetcher.injectKeyLevels(asset, dh, dl, wh, wl);
    }
}
