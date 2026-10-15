package com.arman.spotplayer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Calendar;

/**
 * v5.6.6: fixed wall-clock sleep time + wake-up alarm.
 * Both are set ONCE and re-armed daily by AlarmReceiver (and on boot).
 * The wake alarm uses setAlarmClock (exempt from Doze / exact-alarm
 * permission); the sleep clock uses an exact idle alarm when allowed
 * and falls back to an inexact one.
 */
public final class ClockAlarms {

    public static final String ACTION_SLEEP_FIRE = "com.arman.spotplayer.SLEEP_FIRE";
    public static final String ACTION_WAKE_FIRE = "com.arman.spotplayer.WAKE_FIRE";

    private static final String PREFS = "clockalarms";
    private static final int REQ_SLEEP = 4711;
    private static final int REQ_WAKE = 4712;
    private static final int REQ_SHOW = 4713;

    private ClockAlarms() {}

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean sleepOn(Context ctx) { return prefs(ctx).getBoolean("s_on", false); }
    public static int sleepHour(Context ctx) { return prefs(ctx).getInt("s_h", 23); }
    public static int sleepMinute(Context ctx) { return prefs(ctx).getInt("s_m", 30); }
    public static boolean wakeOn(Context ctx) { return prefs(ctx).getBoolean("w_on", false); }
    public static int wakeHour(Context ctx) { return prefs(ctx).getInt("w_h", 7); }
    public static int wakeMinute(Context ctx) { return prefs(ctx).getInt("w_m", 0); }
    public static boolean anyOn(Context ctx) { return sleepOn(ctx) || wakeOn(ctx); }

    public static void setSleep(Context ctx, boolean on, int h, int m) {
        prefs(ctx).edit().putBoolean("s_on", on).putInt("s_h", h).putInt("s_m", m).apply();
        armSleep(ctx);
    }

    public static void setWake(Context ctx, boolean on, int h, int m) {
        prefs(ctx).edit().putBoolean("w_on", on).putInt("w_h", h).putInt("w_m", m).apply();
        armWake(ctx);
    }

    static long nextTrigger(int h, int m) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, h);
        c.set(Calendar.MINUTE, m);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_YEAR, 1);
        }
        return c.getTimeInMillis();
    }

    private static PendingIntent fireIntent(Context ctx, String action, int req) {
        Intent i = new Intent(ctx, AlarmReceiver.class).setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, req, i, flags);
    }

    public static void armSleep(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = fireIntent(ctx, ACTION_SLEEP_FIRE, REQ_SLEEP);
        try { am.cancel(pi); } catch (Exception ignored) {}
        if (!sleepOn(ctx)) return;
        long t = nextTrigger(sleepHour(ctx), sleepMinute(ctx));
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
        } catch (SecurityException e) {
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi); }
            catch (Exception ignored) {}
        }
    }

    public static void armWake(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = fireIntent(ctx, ACTION_WAKE_FIRE, REQ_WAKE);
        try { am.cancel(pi); } catch (Exception ignored) {}
        if (!wakeOn(ctx)) return;
        long t = nextTrigger(wakeHour(ctx), wakeMinute(ctx));
        Intent show = new Intent(ctx, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent showPi = PendingIntent.getActivity(ctx, REQ_SHOW, show, flags);
        try {
            am.setAlarmClock(new AlarmManager.AlarmClockInfo(t, showPi), pi);
        } catch (Exception ignored) {}
    }

    public static boolean canExact(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        return am == null || Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
    }

    public static void rearmAll(Context ctx) {
        armSleep(ctx);
        armWake(ctx);
    }
}
