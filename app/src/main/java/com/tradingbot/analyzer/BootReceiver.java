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
            
            // APRÈS
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            );

            // 🎯 Ne pas compter uniquement sur le rebind automatique du NotificationListenerService,
            // qui n'est pas garanti immédiat sur tous les OEM après un redémarrage matériel.
            // BOOT_COMPLETED fait partie des exemptions Android autorisant un démarrage direct
            // du foreground service depuis l'arrière-plan — on l'utilise explicitement.
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
            
            Log.d(TAG, "⚙️ [INFRA] Pipeline d'écoute réinitialisé avec succès.");
        }
    }
}
