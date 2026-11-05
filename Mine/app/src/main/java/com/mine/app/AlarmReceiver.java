package com.mine.app;

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
    static final String CHANNEL = "mine_alarms_v1";

    @Override
    public void onReceive(Context c, Intent in) {
        String action = in.getAction();
        if (action == null) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            AlarmScheduler.rescheduleAll(c);
            ExpenseWatch.scheduleAll(c);
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
                AlarmScheduler.snooze(c, o);
            } else if (ACTION_FIRE.equals(action)) {
                show(c, o);
            }
        } catch (Exception ignored) { }
    }

    static void ensureChannel(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Alarms & reminders", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Task, habit, bill and focus timer alarms");
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (sound == null) sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        ch.setSound(sound, aa);
        ch.enableVibration(true);
        ch.setVibrationPattern(new long[]{0, 700, 500, 700, 500, 700});
        ch.enableLights(true);
        ch.setLightColor(0xFF3DDC84);
        ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(ch);
    }

    private static PendingIntent broadcast(Context c, String action, String key, String json, int rc) {
        Intent i = new Intent(c, AlarmReceiver.class)
                .setAction(action)
                .setData(AlarmScheduler.uriFor(key))
                .putExtra("json", json);
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
        int snooze = o.optInt("snooze", 10);

        Intent full = new Intent(c, AlarmActivity.class)
                .setData(AlarmScheduler.uriFor(key))
                .putExtra("json", json)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
        PendingIntent fullPi = PendingIntent.getActivity(c, nid, full,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Icon icon = Icon.createWithResource(c, R.drawable.ic_stat);
        Notification.Builder b = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(0xFF3DDC84)
                .setContentTitle(o.optString("title", "Reminder"))
                .setContentText(o.optString("body", ""))
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fullPi, true)
                .setContentIntent(fullPi)
                .setOngoing(true)
                .setAutoCancel(false)
                .setShowWhen(true)
                .setWhen(o.optLong("at", System.currentTimeMillis()))
                .setTimeoutAfter(10 * 60 * 1000L);

        if ("task".equals(kind) || "habit".equals(kind)) {
            PendingIntent donePi = PendingIntent.getActivity(c, nid + 3, doneIntent(c, key, json),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(icon, "task".equals(kind) ? "Done" : "Check in", donePi).build());
        }
        b.addAction(new Notification.Action.Builder(icon, "Snooze " + snooze + " min",
                broadcast(c, ACTION_SNOOZE, key, json, nid + 1)).build());
        b.addAction(new Notification.Action.Builder(icon, "Dismiss",
                broadcast(c, ACTION_DISMISS, key, json, nid + 2)).build());

        Notification n = b.build();
        n.flags |= Notification.FLAG_INSISTENT;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(nid, n);
    }
}
