package com.accessibilitymanager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * 开机自启广播接收器
 * <p>
 * 监听 BOOT_COMPLETED 广播，在开机时拉起 DaemonService。
 */
public class StartReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        SharedPreferences sp = context.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE);
        if (!sp.getBoolean(AppConstants.KEY_AUTO_BOOT, true)) return;

        Log.d("StartReceiver", "Boot completed, starting DaemonService...");

        Intent serviceIntent = new Intent(context, DaemonService.class);
        context.startForegroundService(serviceIntent);
    }
}