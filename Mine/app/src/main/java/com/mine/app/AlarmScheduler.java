package com.mine.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

public class AlarmScheduler {
    private static final String PREFS = "mine_alarms";
    private static final String KEY_LIST = "list";     // alarms sent by the app
    private static final String KEY_EXTRA = "extra";   // snoozes and test alarms made natively

    static Uri uriFor(String key) {
        return Uri.parse("mine://alarm/" + Uri.encode(key));
    }

    private static PendingIntent firePendingIntent(Context c, String key, String json, int flags) {
        Intent i = new Intent(c, AlarmReceiver.class)
                .setAction(AlarmReceiver.ACTION_FIRE)
                .setData(uriFor(key));
        if (json != null) i.putExtra("json", json);
        return PendingIntent.getBroadcast(c, key.hashCode(), i, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    static void scheduleOne(Context c, JSONObject o, boolean remember) {
        try {
            String key = o.getString("key");
            long at = o.getLong("at");
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            PendingIntent pi = firePendingIntent(c, key, o.toString(), PendingIntent.FLAG_UPDATE_CURRENT);
            boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            if (exact) {
                Intent show = new Intent(c, MainActivity.class);
                PendingIntent showPi = PendingIntent.getActivity(c, 1, show,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, showPi), pi);
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            }
            if (remember) {
                SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
                JSONArray extra = new JSONArray(p.getString(KEY_EXTRA, "[]"));
                extra.put(o);
                p.edit().putString(KEY_EXTRA, future(extra).toString()).apply();
            }
        } catch (Exception ignored) { }
    }

    static void cancel(Context c, String key) {
        PendingIntent pi = firePendingIntent(c, key, null, PendingIntent.FLAG_NO_CREATE);
        if (pi != null) {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            am.cancel(pi);
            pi.cancel();
        }
    }

    static synchronized void setAll(Context c, String json) {
        try {
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray old = new JSONArray(p.getString(KEY_LIST, "[]"));
            JSONArray incoming = new JSONArray(json);
            Set<String> keys = new HashSet<>();
            for (int i = 0; i < incoming.length(); i++) keys.add(incoming.getJSONObject(i).getString("key"));
            for (int i = 0; i < old.length(); i++) {
                String k = old.getJSONObject(i).getString("key");
                if (!keys.contains(k)) cancel(c, k);
            }
            long now = System.currentTimeMillis();
            JSONArray keep = new JSONArray();
            for (int i = 0; i < incoming.length(); i++) {
                JSONObject o = incoming.getJSONObject(i);
                if (o.getLong("at") > now) {
                    scheduleOne(c, o, false);
                    keep.put(o);
                }
            }
            p.edit().putString(KEY_LIST, keep.toString()).apply();
        } catch (Exception ignored) { }
    }

    static void rescheduleAll(Context c) {
        try {
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray list = future(new JSONArray(p.getString(KEY_LIST, "[]")));
            JSONArray extra = future(new JSONArray(p.getString(KEY_EXTRA, "[]")));
            for (int i = 0; i < list.length(); i++) scheduleOne(c, list.getJSONObject(i), false);
            for (int i = 0; i < extra.length(); i++) scheduleOne(c, extra.getJSONObject(i), false);
            p.edit().putString(KEY_LIST, list.toString()).putString(KEY_EXTRA, extra.toString()).apply();
        } catch (Exception ignored) { }
    }

    static void snooze(Context c, JSONObject o) {
        try {
            long now = System.currentTimeMillis();
            int minutes = Math.max(1, o.optInt("snooze", 10));
            JSONObject n = new JSONObject(o.toString());
            String base = o.getString("key");
            if (base.startsWith("snz:")) base = base.substring(4, Math.max(4, base.lastIndexOf(':')));
            n.put("key", "snz:" + base + ":" + now);
            n.put("at", now + minutes * 60000L);
            n.put("body", "Snoozed " + minutes + " min");
            scheduleOne(c, n, true);
        } catch (Exception ignored) { }
    }

    private static JSONArray future(JSONArray arr) {
        JSONArray out = new JSONArray();
        long cutoff = System.currentTimeMillis() - 60000;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && o.optLong("at", 0) > cutoff) out.put(o);
        }
        return out;
    }
}
