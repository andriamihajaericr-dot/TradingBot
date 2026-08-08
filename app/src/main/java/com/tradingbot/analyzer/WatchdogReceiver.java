package com.tradingbot.analyzer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class WatchdogReceiver extends BroadcastReceiver {
    private static final long SEUIL_ALERTE_MS = 15 * 60 * 1000; // 15 min sans battement = alerte

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences("TradingBotPrefs", Context.MODE_PRIVATE);
        long dernierBattement = prefs.getLong("last_heartbeat", 0L);
        long maintenant = System.currentTimeMillis();

        if (dernierBattement == 0L || (maintenant - dernierBattement) > SEUIL_ALERTE_MS) {
            NotificationService.sendTelegramSecure(
                "🔴 *WATCHDOG* : aucun signe de vie du TradingBot depuis plus de 15 min. Tentative de relance...",
                context.getApplicationContext());

            Intent serviceIntent = new Intent(context, NotificationService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        }
    }
}
