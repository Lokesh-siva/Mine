package com.mine.app;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class Bridge {
    private final MainActivity activity;

    Bridge(MainActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public boolean isNative() {
        return true;
    }

    @JavascriptInterface
    public void setAlarms(String json) {
        AlarmScheduler.setAll(activity.getApplicationContext(), json);
    }

    @JavascriptInterface
    public void testAlarm(int seconds) {
        try {
            long now = System.currentTimeMillis();
            JSONObject o = new JSONObject();
            o.put("key", "test@" + now);
            o.put("at", now + seconds * 1000L);
            o.put("title", "Test alarm");
            o.put("body", "Your alarms are working");
            o.put("kind", "test");
            o.put("id", "");
            o.put("snooze", 5);
            AlarmScheduler.scheduleOne(activity.getApplicationContext(), o, true);
        } catch (Exception ignored) { }
    }

    @JavascriptInterface
    public String alarmStatus() {
        JSONObject o = new JSONObject();
        try {
            Context c = activity.getApplicationContext();
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            boolean notif = nm.areNotificationsEnabled();
            boolean exact = true;
            if (Build.VERSION.SDK_INT >= 31) {
                AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
                exact = am.canScheduleExactAlarms();
            }
            boolean fullscreen = true;
            if (Build.VERSION.SDK_INT >= 34) fullscreen = nm.canUseFullScreenIntent();
            o.put("notif", notif);
            o.put("exact", exact);
            o.put("fullscreen", fullscreen);
        } catch (Exception ignored) { }
        return o.toString();
    }

    @JavascriptInterface
    public void openAlarmSettings(String which) {
        activity.runOnUiThread(() -> {
            String pkg = activity.getPackageName();
            try {
                Intent i;
                if ("exact".equals(which) && Build.VERSION.SDK_INT >= 31) {
                    i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + pkg));
                } else if ("fullscreen".equals(which) && Build.VERSION.SDK_INT >= 34) {
                    i = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:" + pkg));
                } else {
                    i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    i.putExtra(Settings.EXTRA_APP_PACKAGE, pkg);
                }
                activity.startActivity(i);
            } catch (Exception e) {
                try {
                    activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
                } catch (Exception ignored) { }
            }
        });
    }

    @JavascriptInterface
    public boolean canLock() {
        return activity.deviceSecure();
    }

    @JavascriptInterface
    public boolean lockEnabled() {
        return activity.lockEnabled();
    }

    @JavascriptInterface
    public boolean setLock(boolean on) {
        activity.setLockEnabled(on);
        ExpenseWidget.refresh(activity.getApplicationContext());
        return activity.lockEnabled();
    }

    @JavascriptInterface
    public String watchSettings() {
        return ExpenseWatch.settings(activity.getApplicationContext());
    }

    @JavascriptInterface
    public void setWatch(String key, boolean on) {
        if ("weekly".equals(key) || "monthly".equals(key) || "sms".equals(key)) {
            ExpenseWatch.set(activity.getApplicationContext(), key, on);
        }
    }

    @JavascriptInterface
    public void requestSms() {
        activity.runOnUiThread(activity::requestSms);
    }

    @JavascriptInterface
    public int smsScan() {
        return ExpenseWatch.scan(activity.getApplicationContext(), true);
    }

    @JavascriptInterface
    public int smsSince(double since) {
        return ExpenseWatch.setSince(activity.getApplicationContext(), (long) since);
    }

    @JavascriptInterface
    public String smsCandidates() {
        return ExpenseWatch.candidates(activity.getApplicationContext());
    }

    @JavascriptInterface
    public void smsIgnore(String id) {
        ExpenseWatch.ignore(activity.getApplicationContext(), id);
    }

    @JavascriptInterface
    public String smsMessage(String id) {
        return ExpenseWatch.message(activity.getApplicationContext(), id);
    }

    /** Opens the sender's conversation in the phone's messaging app. */
    @JavascriptInterface
    public boolean smsOpen(String addr) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("smsto:" + Uri.encode(addr)));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public String smsIgnored() {
        return ExpenseWatch.ignoredCandidates(activity.getApplicationContext());
    }

    @JavascriptInterface
    public void smsUnignore(String id) {
        ExpenseWatch.unignore(activity.getApplicationContext(), id);
    }

    @JavascriptInterface
    public void setExpenseIndex(String json) {
        ExpenseWatch.setExpenses(activity.getApplicationContext(), json);
    }

    @JavascriptInterface
    public void reviewClosed() {
        activity.runOnUiThread(activity::reviewClosed);
    }

    @JavascriptInterface
    public void openAppSettings() {
        activity.runOnUiThread(() -> {
            try {
                activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.getPackageName())));
            } catch (Exception ignored) { }
        });
    }

    @JavascriptInterface
    public void setWidget(String label, String amount) {
        ExpenseWidget.save(activity.getApplicationContext(), label, amount);
    }

    @JavascriptInterface
    public void setAccent(String hex) {
        Theme.setAccent(activity.getApplicationContext(), hex);
    }

    /** Keeps the screen on during a workout so the rest timer stays visible. */
    @JavascriptInterface
    public void keepAwake(boolean on) {
        activity.runOnUiThread(() -> {
            if (on) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    @JavascriptInterface
    public String saveFile(String name, String content) {
        try {
            name = name == null ? "mine.txt" : name.replaceAll("[^A-Za-z0-9._ -]", "_");
            if (name.startsWith(".") || name.isEmpty()) name = "mine" + name;
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, mime(name));
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Mine");
                Uri uri = activity.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return "Could not save the file";
                try (OutputStream os = activity.getContentResolver().openOutputStream(uri)) {
                    if (os == null) return "Could not save the file";
                    os.write(bytes);
                }
                return "Saved to Downloads/Mine/" + name;
            } else {
                File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) return "Could not save the file";
                File f = new File(dir, name);
                try (FileOutputStream os = new FileOutputStream(f)) {
                    os.write(bytes);
                }
                return "Saved to " + f.getAbsolutePath();
            }
        } catch (Exception e) {
            return "Could not save: " + e.getMessage();
        }
    }

    private static String mime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".csv")) return "text/csv";
        if (n.endsWith(".ics")) return "text/calendar";
        return "text/plain";
    }
}
