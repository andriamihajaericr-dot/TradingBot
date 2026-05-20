package com.tradingbot.analyzer;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.d(TAG, "⚡ [SYSTEM-BOOT] Détection d'un redémarrage matériel.");
            
            // Forcer le composant Android NotificationListenerService à se ré-enregistrer activement auprès du noyau
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
            
            Log.d(TAG, "⚙️ [INFRA] Pipeline d'écoute réinitialisé avec succès.");
        }
    }
}
