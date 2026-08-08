package com.tradingbot.analyzer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";
    private static final long WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L; // 15 minutes

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d(TAG, "⚡ [SYSTEM-BOOT] Détection d'un redémarrage matériel.");

            // --- Ton code de réactivation du NotificationListenerService ---
            ComponentName componentName = new ComponentName(context, NotificationService.class);
            PackageManager packageManager = context.getPackageManager();

            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            );

            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            );

            try {
                Intent serviceIntent = new Intent(context, NotificationService.class);
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
                Log.d(TAG, "🚀 [INFRA] Démarrage explicite du foreground service au boot.");
            } catch (Exception e) {
                Log.e(TAG, "❌ [INFRA] Échec démarrage explicite au boot : " + e.getMessage());
            }
            // -------------------------------------------------------------

            // 🕒 Programmation de l'alarme de surveillance (watchdog)
            scheduleWatchdogAlarm(context);
            Log.d(TAG, "⚙️ [INFRA] Pipeline d'écoute réinitialisé avec succès.");
        }
    }

    private void scheduleWatchdogAlarm(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            Log.e(TAG, "AlarmManager non disponible");
            return;
        }

        Intent watchdogIntent = new Intent(context, WatchdogReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(
                context,
                0,
                watchdogIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        long firstTriggerAt = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS;
        am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                firstTriggerAt,
                WATCHDOG_INTERVAL_MS,
                pi
        );

        Log.d(TAG, "⏰ [WATCHDOG] Alarme programmée (premier déclenchement dans 15 min)");
    }
}
