package com.arman.spotplayer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * v5.6.6: receives the fixed sleep-clock / wake-alarm broadcasts,
 * re-arms each for the next day, and re-arms both after reboot.
 */
public class AlarmReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (a == null) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(a)) {
            ClockAlarms.rearmAll(ctx);
            return;
        }
        if (ClockAlarms.ACTION_SLEEP_FIRE.equals(a)) {
            ClockAlarms.armSleep(ctx); // schedule tomorrow's
            startService(ctx, PlaybackService.ACTION_SLEEP_FIRE);
        } else if (ClockAlarms.ACTION_WAKE_FIRE.equals(a)) {
            ClockAlarms.armWake(ctx); // schedule tomorrow's
            startService(ctx, PlaybackService.ACTION_WAKE_FIRE);
        }
    }

    private void startService(Context ctx, String action) {
        try {
            Intent i = new Intent(ctx, PlaybackService.class).setAction(action);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception ignored) {}
    }
}
