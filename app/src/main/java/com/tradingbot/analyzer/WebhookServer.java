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

    // ── Sauvegarde des niveaux dans SharedPreferences ──
    private void saveLevels(String asset, JSONObject obj) throws org.json.JSONException {
        SharedPreferences prefs = context.getSharedPreferences("KeyLevels", Context.MODE_PRIVATE);
        prefs.edit()
            .putFloat(asset + "_daily_high", (float) obj.getDouble("daily_high"))
            .putFloat(asset + "_daily_low", (float) obj.getDouble("daily_low"))
            .putFloat(asset + "_weekly_high", (float) obj.getDouble("weekly_high"))
            .putFloat(asset + "_weekly_low", (float) obj.getDouble("weekly_low"))
            .putFloat(asset + "_monthly_high", (float) obj.getDouble("monthly_high"))
            .putFloat(asset + "_monthly_low", (float) obj.getDouble("monthly_low"))
            .putFloat(asset + "_h4_high", (float) obj.getDouble("h4_high"))
            .putFloat(asset + "_h4_low", (float) obj.getDouble("h4_low"))
            .apply();
    }
}
