package com.mine.app;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Daily fasting window (e.g. 8 pm to 10 am). While it runs, a silent ongoing notification counts down.
 * Settings come from the app as JSON: {on, start "HH:mm", end "HH:mm"}.
 */
final class Fasting {
    static final String ACT_START = "com.mine.app.FAST_START";
    static final String ACT_END = "com.mine.app.FAST_END";
    static final String ACT_BREAK = "com.mine.app.FAST_BREAK";
    static final String ACT_REPOST = "com.mine.app.FAST_REPOST";
    private static final String PREFS = "mine_fast";
    private static final String CHANNEL = "mine_fasting";
    private static final int NID = 7700, NID_DONE = 7701;
    private static final long DAY = 24 * 60 * 60 * 1000L;

    private Fasting() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static JSONObject cfg(Context c) {
        try {
            return new JSONObject(prefs(c).getString("cfg", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static void setConfig(Context c, String json) {
        try {
            JSONObject n = new JSONObject(json);
            SharedPreferences p = prefs(c);
            boolean wasOn = cfg(c).optBoolean("on");
            SharedPreferences.Editor e = p.edit().putString("cfg", n.toString());
            if (n.optBoolean("on") && !wasOn) e.putLong("since", System.currentTimeMillis());
            e.apply();
        } catch (Exception ignored) { }
        schedule(c);
        refresh(c);
    }

    private static int minutes(String hm, int def) {
        try {
            String[] p = hm.split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) {
            return def;
        }
    }

    private static int startMin(Context c) {
        return minutes(cfg(c).optString("start", "20:00"), 20 * 60);
    }

    /** Window length in minutes; 0 means no window. */
    private static int lengthMin(Context c) {
        int s = startMin(c), e = minutes(cfg(c).optString("end", "10:00"), 10 * 60);
        return ((e - s) % 1440 + 1440) % 1440;
    }

    private static long startOn(long dayMs, int startMin) {
        Calendar k = Calendar.getInstance();
        k.setTimeInMillis(dayMs);
        k.set(Calendar.HOUR_OF_DAY, startMin / 60);
        k.set(Calendar.MINUTE, startMin % 60);
        k.set(Calendar.SECOND, 0);
        k.set(Calendar.MILLISECOND, 0);
        return k.getTimeInMillis();
    }

    /** Start of the window running at time t, or -1 when t is outside the window. */
    static long windowStart(Context c, long t) {
        int len = lengthMin(c);
        if (len == 0) return -1;
        for (int back = 0; back <= 1; back++) {
            long st = startOn(t - back * DAY, startMin(c));
            if (t >= st && t < st + len * 60000L) return st;
        }
        return -1;
    }

    static long windowEnd(Context c, long start) {
        return start + lengthMin(c) * 60000L;
    }

    private static String id(long start) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(start));
    }

    private static Set<String> broken(Context c) {
        return new HashSet<>(prefs(c).getStringSet("broken", new HashSet<>()));
    }

    /** True while the window runs and today's fast hasn't been broken. */
    static boolean active(Context c) {
        if (!cfg(c).optBoolean("on")) return false;
        long st = windowStart(c, System.currentTimeMillis());
        return st >= 0 && !broken(c).contains(id(st));
    }

    /** Ends the current fast early: blocking stops and the streak restarts tomorrow. */
    static void breakFast(Context c) {
        long st = windowStart(c, System.currentTimeMillis());
        if (st < 0) return;
        Set<String> b = broken(c);
        b.add(id(st));
        prefs(c).edit().putStringSet("broken", b).apply();
        refresh(c);
    }

    /** Fasts completed in a row, counting back from the most recent finished one. */
    static int streak(Context c) {
        if (!cfg(c).optBoolean("on")) return 0;
        int len = lengthMin(c);
        if (len == 0) return 0;
        long now = System.currentTimeMillis(), since = prefs(c).getLong("since", now);
        Set<String> b = broken(c);
        long st = startOn(now, startMin(c));
        while (st + len * 60000L > now) st = dayBefore(st, startMin(c));
        int n = 0;
        while (n < 3650 && st + len * 60000L > since && !b.contains(id(st))) {
            n++;
            st = dayBefore(st, startMin(c));
        }
        return n;
    }

    /** The same start time one calendar day earlier (safe across clock changes). */
    private static long dayBefore(long st, int startMin) {
        Calendar k = Calendar.getInstance();
        k.setTimeInMillis(st);
        k.add(Calendar.DAY_OF_MONTH, -1);
        return startOn(k.getTimeInMillis(), startMin);
    }

    private static PendingIntent pi(Context c, String action, int rc) {
        Intent i = new Intent(c, FastingReceiver.class).setAction(action);
        return PendingIntent.getBroadcast(c, rc, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Arms the next start and end of the window. */
    static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent start = pi(c, ACT_START, 7710), end = pi(c, ACT_END, 7711);
        am.cancel(start);
        am.cancel(end);
        if (!cfg(c).optBoolean("on") || lengthMin(c) == 0) return;
        long now = System.currentTimeMillis();
        long nextStart = startOn(now, startMin(c));
        if (nextStart <= now) nextStart = startOn(now + DAY, startMin(c));
        long running = windowStart(c, now);
        long nextEnd = running >= 0 ? windowEnd(c, running) : windowEnd(c, nextStart);
        set(am, nextStart, start);
        set(am, nextEnd, end);
    }

    private static void set(AlarmManager am, long at, PendingIntent p) {
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p);
    }

    private static void channel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Fasting", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Shows while your fasting window is running");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    static String endLabel(Context c, long end) {
        return new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date(end));
    }

    /** Shows the ongoing notification while fasting, removes it otherwise. */
    static void refresh(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (!active(c)) {
            nm.cancel(NID);
            return;
        }
        channel(c);
        long st = windowStart(c, System.currentTimeMillis()), end = windowEnd(c, st);
        PendingIntent open = PendingIntent.getActivity(c, 7712, new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // minimal: just that it's fasting time and a live countdown to the end
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(Theme.accent(c))
                .setContentTitle("Fasting time")
                .setContentText("Until " + endLabel(c, end))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setWhen(end)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(open)
                // newer Android lets you swipe away ongoing notifications; put it straight back while the fast runs
                .setDeleteIntent(pi(c, ACT_REPOST, 7714));
        nm.notify(NID, b.build());
    }

    /** Called at the end of the window. */
    static void finished(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(NID);
        if (!cfg(c).optBoolean("on")) return;
        long last = windowStart(c, System.currentTimeMillis() - 60000L);
        if (last >= 0 && broken(c).contains(id(last))) return;
        channel(c);
        nm.notify(NID_DONE, new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(Theme.accent(c))
                .setContentTitle("Fasting complete")
                .setAutoCancel(true)
                .setTimeoutAfter(2 * 60 * 60 * 1000L)
                .build());
    }

    static String status(Context c) {
        JSONObject o = new JSONObject();
        try {
            long now = System.currentTimeMillis(), st = windowStart(c, now);
            o.put("active", active(c));
            o.put("inWindow", st >= 0);
            o.put("broken", st >= 0 && broken(c).contains(id(st)));
            o.put("endsAt", st >= 0 ? windowEnd(c, st) : 0);
            o.put("streak", streak(c));
        } catch (Exception ignored) { }
        return o.toString();
    }
}
