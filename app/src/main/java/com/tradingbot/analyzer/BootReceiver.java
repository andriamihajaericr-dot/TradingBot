package com.tradingbot.analyzer;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // NotificationListenerService redémarre automatiquement au boot
    }
}
