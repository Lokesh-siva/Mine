package com.mine.app;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;

import org.json.JSONObject;

public class AlarmReceiver extends BroadcastReceiver {
    static final String ACTION_FIRE = "com.mine.app.FIRE";
    static final String ACTION_SNOOZE = "com.mine.app.SNOOZE";
    static final String ACTION_DISMISS = "com.mine.app.DISMISS";
    static final String ACTION_DONE = "com.mine.app.DONE";
    private static final String PREFS = "mine_alarm";
    static final int[] SNOOZE_MIN = {5, 30, 60};

    @Override
    public void onReceive(Context c, Intent in) {
        String action = in.getAction();
        if (action == null) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            AlarmScheduler.rescheduleAll(c);
            ExpenseWatch.scheduleAll(c);
            Fasting.schedule(c);
            Fasting.refresh(c);
            return;
        }
        String json = in.getStringExtra("json");
        if (json == null) return;
        try {
            JSONObject o = new JSONObject(json);
            int nid = o.getString("key").hashCode();
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            if (ACTION_DISMISS.equals(action)) {
                nm.cancel(nid);
            } else if (ACTION_SNOOZE.equals(action)) {
                nm.cancel(nid);
                AlarmScheduler.snooze(c, o, in.getIntExtra("min", 5));
            } else if (ACTION_FIRE.equals(action)) {
                show(c, o);
            }
        } catch (Exception ignored) { }
    }

    // A channel's sound can't change once created, so each tone choice gets a new channel id
    static String channel(Context c) {
        return "mine_alarms_v" + c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("ver", 2);
    }

    static Uri tone(Context c) {
        String t = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tone", null);
        if (t != null) return Uri.parse(t);
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        return sound != null ? sound : RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
    }

    static String toneName(Context c) {
        try {
            android.media.Ringtone r = RingtoneManager.getRingtone(c, tone(c));
            if (r != null) return r.getTitle(c);
        } catch (Exception ignored) { }
        return "Phone’s alarm tone";
    }

    static void setTone(Context c, Uri u) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.deleteNotificationChannel(channel(c));
        android.content.SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putString("tone", u.toString()).putInt("ver", p.getInt("ver", 2) + 1).apply();
        ensureChannel(c);
    }

    static void ensureChannel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        String id = channel(c);
        if (nm.getNotificationChannel(id) != null) return;
        // the first channel showed full details on the lock screen; replace it with a private one
        if (nm.getNotificationChannel("mine_alarms_v1") != null) nm.deleteNotificationChannel("mine_alarms_v1");
        NotificationChannel ch = new NotificationChannel(id, "Alarms & reminders", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Task, habit, bill, gym and focus timer alarms");
        Uri sound = tone(c);
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        ch.setSound(sound, aa);
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 700, 500, 700, 500, 700});
        ch.enableLights(true);
        ch.setLightColor(Theme.accent(c));
        ch.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(ch);
    }

    private static PendingIntent broadcast(Context c, String action, String key, String json, int rc) {
        Intent i = new Intent(c, AlarmReceiver.class)
                .setAction(action)
                .setData(AlarmScheduler.uriFor(key))
                .putExtra("json", json);
        return PendingIntent.getBroadcast(c, rc, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent snoozeIntent(Context c, String key, String json, int min, int rc) {
        Intent i = new Intent(c, AlarmReceiver.class)
                .setAction(ACTION_SNOOZE)
                .setData(AlarmScheduler.uriFor(key))
                .putExtra("json", json)
                .putExtra("min", min);
        return PendingIntent.getBroadcast(c, rc, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static Intent doneIntent(Context c, String key, String json) {
        return new Intent(c, MainActivity.class)
                .setAction(ACTION_DONE)
                .setData(AlarmScheduler.uriFor(key))
                .putExtra("json", json)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    static void show(Context c, JSONObject o) throws Exception {
        ensureChannel(c);
        String key = o.getString("key");
        String json = o.toString();
        int nid = key.hashCode();
        String kind = o.optString("kind", "task");
        // On a locked phone the alarm says only that something is due; details show after unlocking
        KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        boolean locked = km != null && km.isKeyguardLocked();

        Intent full = new Intent(c, AlarmActivity.class)
                .setData(AlarmScheduler.uriFor(key))
                .putExtra("json", json)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent fullPi = PendingIntent.getActivity(c, nid, full,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Icon icon = Icon.createWithResource(c, R.drawable.ic_stat);
        Notification hidden = new Notification.Builder(c, channel(c))
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(Theme.accent(c))
                .setContentTitle("Mine reminder")
                .setContentText("Unlock your phone to see it")
                .build();
        Notification.Builder b = new Notification.Builder(c, channel(c))
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(Theme.accent(c))
                .setContentTitle(locked ? "Mine reminder" : o.optString("title", "Reminder"))
                .setContentText(locked ? "Unlock your phone to see it" : o.optString("body", ""))
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(hidden)
                .setFullScreenIntent(fullPi, true)
                .setContentIntent(fullPi)
                .setOngoing(true)
                .setAutoCancel(false)
                .setShowWhen(true)
                .setWhen(o.optLong("at", System.currentTimeMillis()))
                .setTimeoutAfter(10 * 60 * 1000L);

        if (!locked && ("task".equals(kind) || "habit".equals(kind))) {
            PendingIntent donePi = PendingIntent.getActivity(c, nid + 3, doneIntent(c, key, json),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(icon, "task".equals(kind) ? "Done" : "Check in", donePi).build());
        }
        b.addAction(new Notification.Action.Builder(icon, "Snooze 5 min", snoozeIntent(c, key, json, 5, nid + 1)).build());
        if (locked || !("task".equals(kind) || "habit".equals(kind))) {
            b.addAction(new Notification.Action.Builder(icon, "30 min", snoozeIntent(c, key, json, 30, nid + 4)).build());
        }
        b.addAction(new Notification.Action.Builder(icon, "Dismiss",
                broadcast(c, ACTION_DISMISS, key, json, nid + 2)).build());

        Notification n = b.build();
        n.flags |= Notification.FLAG_INSISTENT;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(nid, n);
    }
}
