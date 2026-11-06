package com.mine.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expense checks that run without the app open:
 * - every day at 9 pm, reads bank SMS from the last week and notifies about debits with no matching expense
 * - every Sunday 8 pm and on the 1st at 9 am, shows the weekly / monthly expense review full screen (silent)
 * Everything stays on the phone: only amount, merchant, card digits and date are kept from each SMS.
 */
public class ExpenseWatch extends BroadcastReceiver {
    static final String ACTION_SCAN = "com.mine.app.SMS_SCAN";
    static final String ACTION_WEEKLY = "com.mine.app.REVIEW_WEEK";
    static final String ACTION_MONTHLY = "com.mine.app.REVIEW_MONTH";
    static final String ACTION_OPEN_SMS = "com.mine.app.OPEN_SMS_REVIEW";
    static final String ACTION_SHOW_REVIEW = "com.mine.app.SHOW_REVIEW";
    private static final String PREFS = "mine_watch";
    private static final String CH_SMS = "mine_sms_v1";
    private static final String CH_REVIEW = "mine_review_v1";
    private static final int NID_SMS = 9101, NID_REVIEW = 9102;
    private static final long DAY = 86400000L;

    @Override
    public void onReceive(Context c, Intent in) {
        String a = in.getAction();
        if (a == null) return;
        PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                if (ACTION_SCAN.equals(a)) {
                    if (smsOn(c)) {
                        scan(c);
                        notifyUnmatched(c);
                    }
                } else if (ACTION_WEEKLY.equals(a)) {
                    if (prefs(c).getBoolean("weekly", true)) showReview(c, "week");
                } else if (ACTION_MONTHLY.equals(a)) {
                    if (prefs(c).getBoolean("monthly", true)) showReview(c, "month");
                }
                scheduleAll(c);
            } catch (Exception ignored) {
            } finally {
                pr.finish();
            }
        }).start();
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean hasSmsPermission(Context c) {
        return c.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean smsOn(Context c) {
        return prefs(c).getBoolean("sms", false) && hasSmsPermission(c);
    }

    static String settings(Context c) {
        JSONObject o = new JSONObject();
        try {
            SharedPreferences p = prefs(c);
            o.put("sms", smsOn(c));
            o.put("smsPerm", hasSmsPermission(c));
            o.put("weekly", p.getBoolean("weekly", true));
            o.put("monthly", p.getBoolean("monthly", true));
            o.put("lastScan", p.getLong("lastScan", 0));
            o.put("since", p.getLong("since", 0));
        } catch (Exception ignored) { }
        return o.toString();
    }

    static void set(Context c, String key, boolean on) {
        prefs(c).edit().putBoolean(key, on).apply();
        scheduleAll(c);
    }

    /* ---------------- scheduling ---------------- */

    static void scheduleAll(Context c) {
        Calendar now = Calendar.getInstance();

        Calendar sms = at(now, 21, 0);
        if (!sms.after(now)) sms.add(Calendar.DAY_OF_YEAR, 1);
        schedule(c, ACTION_SCAN, 31, smsOn(c) ? sms.getTimeInMillis() : 0);

        Calendar wk = at(now, 20, 0);
        while (wk.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY || !wk.after(now)) wk.add(Calendar.DAY_OF_YEAR, 1);
        schedule(c, ACTION_WEEKLY, 32, prefs(c).getBoolean("weekly", true) ? wk.getTimeInMillis() : 0);

        Calendar mo = at(now, 9, 0);
        mo.set(Calendar.DAY_OF_MONTH, 1);
        if (!mo.after(now)) mo.add(Calendar.MONTH, 1);
        schedule(c, ACTION_MONTHLY, 33, prefs(c).getBoolean("monthly", true) ? mo.getTimeInMillis() : 0);
    }

    private static Calendar at(Calendar base, int h, int m) {
        Calendar x = (Calendar) base.clone();
        x.set(Calendar.HOUR_OF_DAY, h);
        x.set(Calendar.MINUTE, m);
        x.set(Calendar.SECOND, 0);
        x.set(Calendar.MILLISECOND, 0);
        return x;
    }

    private static void schedule(Context c, String action, int rc, long when) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        Intent i = new Intent(c, ExpenseWatch.class).setAction(action);
        PendingIntent pi = PendingIntent.getBroadcast(c, rc, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        am.cancel(pi);
        if (when <= 0) return;
        boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
    }

    /* ---------------- SMS reading ---------------- */

    private static final Pattern AMOUNT = Pattern.compile("(?:rs\\.?|inr|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT_BARE = Pattern.compile("(?:debited|spent|paid|sent|withdrawn)\\s*(?:by|of|for|with)?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LAST4 = Pattern.compile("(?:a/?c|acct|account|card)\\s*(?:no\\.?)?\\s*(?:ending\\s*(?:with|in)?)?\\s*[:\\-]?\\s*[x*•.]*\\s*(\\d{4})\\b", Pattern.CASE_INSENSITIVE);
    // e.g. "SMS BLOCKPCC 0750 to ..." when the card number isn't written next to the word card
    private static final Pattern BLOCK4 = Pattern.compile("\\bblock\\w*\\s+(\\d{4})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern[] MERCHANT = {
            Pattern.compile("\\bvpa\\s+([\\w.\\-]+@[\\w.\\-]+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:credited to|paid to|sent to|trf to|transfer to|towards)\\s+([A-Za-z0-9 &'._@\\-]{2,40}?)(?=\\s+(?:on|at|ref\\w*|via|upi|avl|from|a/c)\\b|[.,;(]|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(?:at|to)\\s+([A-Za-z0-9 &'._@\\-]{2,40}?)(?=\\s+(?:on|at|ref\\w*|via|upi|avl|from|txn)\\b|[.,;(]|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bon\\s+([A-Za-z][A-Za-z0-9 &'._@\\-]{1,39}?)(?=\\s+(?:avl|ref|via)\\b|[.,;(]|$)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\binfo[:\\s]+([A-Za-z0-9 &'._@/\\-]{2,40}?)(?=[.,;(]|\\s+avl|$)", Pattern.CASE_INSENSITIVE),
    };
    private static final String[] DEBIT = {"debited", "spent", "paid", "sent", "purchase", "withdrawn", "debit", " dr ", "dr.", "txn of", "used for",
            "used at", "transaction of", "made using", "charged"};
    private static final String[] SKIP = {"otp", "one time password", "will be debited", "to be debited", "requested", "request", "due on", "due by",
            "statement", "min amt", "minimum amount", "offer", "cashback", "reward", "failed", "declined", "reversed", "refund", "unsuccessful"};

    /** Sets the date the review starts from (0 = last 7 days), drops older candidates and reads every SMS since then. */
    static int setSince(Context c, long since) {
        long now = System.currentTimeMillis();
        if (since > now) since = now - DAY;
        if (since > 0 && since < now - 366 * DAY) since = now - 366 * DAY;
        prefs(c).edit().putLong("since", Math.max(0, since)).apply();
        return scan(c, true);
    }

    static int scan(Context c) {
        return scan(c, false);
    }

    /**
     * Reads bank SMS and adds new debits to the candidate list. Returns the number of new ones.
     * The daily check reads the last 7 days; a full scan reads everything since the chosen start date.
     */
    static synchronized int scan(Context c, boolean full) {
        if (!hasSmsPermission(c)) return 0;
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        long since = p.getLong("since", 0);
        long start = since <= 0 ? now - 7 * DAY : full ? since : Math.max(since, now - 7 * DAY);
        long floor = since > 0 ? since : now - 30 * DAY;
        int added = 0;
        try {
            JSONArray cands = new JSONArray(p.getString("cands", "[]"));
            Set<String> known = new HashSet<>();
            for (int i = 0; i < cands.length(); i++) known.add(cands.getJSONObject(i).getString("id"));
            JSONArray ign = new JSONArray(p.getString("ignored", "[]"));
            Set<String> ignIds = new HashSet<>();
            for (int i = 0; i < ign.length(); i++) ignIds.add(ign.getString(i));
            // ignored SMS are kept too, so the review can list them and bring them back
            JSONArray ignCands = new JSONArray(p.getString("ignoredCands", "[]"));
            for (int i = 0; i < ignCands.length(); i++) known.add(ignCands.getJSONObject(i).getString("id"));

            try (Cursor cur = c.getContentResolver().query(Uri.parse("content://sms/inbox"),
                    new String[]{"_id", "address", "body", "date"}, "date > ?",
                    new String[]{String.valueOf(start)}, "date DESC")) {
                while (cur != null && cur.moveToNext()) {
                    String id = "sms" + cur.getLong(0);
                    if (known.contains(id)) continue;
                    String addr = cur.getString(1), body = cur.getString(2);
                    long ts = cur.getLong(3);
                    JSONObject o = parse(addr, body);
                    if (o == null) continue;
                    o.put("id", id);
                    o.put("ts", ts);
                    o.put("date", ymd(ts));
                    known.add(id);
                    if (ignIds.contains(id)) {
                        ignCands.put(o);
                        continue;
                    }
                    cands.put(o);
                    added++;
                }
            }
            // keep candidates from the start date (or the last 30 days) only
            p.edit().putString("cands", keepSince(cands, floor).toString())
                    .putString("ignoredCands", keepSince(ignCands, floor).toString())
                    .putLong("lastScan", now).apply();
        } catch (Exception ignored) { }
        return added;
    }

    private static JSONArray keepSince(JSONArray list, long floor) throws Exception {
        JSONArray keep = new JSONArray();
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            if (o.optLong("ts") >= floor) keep.put(o);
        }
        return keep;
    }

    static JSONObject parse(String addr, String body) {
        if (addr == null || body == null) return null;
        // bank and payment senders use names like "VM-HDFCBK"; skip SMS from personal numbers
        if (!addr.matches(".*[A-Za-z].*")) return null;
        String low = " " + body.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ") + " ";
        boolean debit = false;
        for (String k : DEBIT) if (low.contains(k)) { debit = true; break; }
        if (!debit) return null;
        for (String k : SKIP) if (low.contains(k)) return null;
        if (low.contains("credited") && !low.contains("debited") && !low.contains("credited to")) return null;
        Matcher m = AMOUNT.matcher(body);
        if (!m.find()) {
            m = AMOUNT_BARE.matcher(body);
            if (!m.find()) return null;
        }
        double amt;
        try { amt = Double.parseDouble(m.group(1).replace(",", "")); } catch (Exception e) { return null; }
        if (amt <= 0) return null;
        try {
            JSONObject o = new JSONObject();
            o.put("amt", Math.round(amt * 100) / 100.0);
            Matcher l = LAST4.matcher(body);
            if (l.find()) o.put("last4", l.group(1));
            else if ((l = BLOCK4.matcher(body)).find()) o.put("last4", l.group(1));
            String merchant = "";
            for (Pattern mp : MERCHANT) {
                Matcher mm = mp.matcher(body);
                while (mm.find()) {
                    String s = mm.group(1).trim();
                    String sl = s.toLowerCase(Locale.ROOT);
                    if (s.length() < 2 || sl.matches("(your|you|the|date|a/c|ac|acct|account|card|rs|inr|\\d+)\\b.*")) continue;
                    merchant = s;
                    break;
                }
                if (!merchant.isEmpty()) break;
            }
            o.put("merchant", merchant);
            o.put("sender", addr.replaceAll("^[A-Z]{2}-", ""));
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    private static String ymd(long ts) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ts));
    }

    /** The full SMS for a candidate id like "sms123", as {body, ts, addr}, or "" when it's gone from the inbox. */
    static String message(Context c, String id) {
        if (!hasSmsPermission(c) || id == null || !id.startsWith("sms")) return "";
        try (Cursor cur = c.getContentResolver().query(Uri.parse("content://sms"),
                new String[]{"body", "date", "address"}, "_id = ?", new String[]{id.substring(3)}, null)) {
            if (cur == null || !cur.moveToFirst()) return "";
            JSONObject o = new JSONObject();
            o.put("body", cur.getString(0));
            o.put("ts", cur.getLong(1));
            o.put("addr", cur.getString(2));
            return o.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static String candidates(Context c) {
        return prefs(c).getString("cands", "[]");
    }

    static String ignoredCandidates(Context c) {
        return prefs(c).getString("ignoredCands", "[]");
    }

    static synchronized void ignore(Context c, String id) {
        SharedPreferences p = prefs(c);
        try {
            JSONArray cands = new JSONArray(p.getString("cands", "[]")), keep = new JSONArray();
            JSONArray ignCands = new JSONArray(p.getString("ignoredCands", "[]"));
            for (int i = 0; i < cands.length(); i++) {
                JSONObject o = cands.getJSONObject(i);
                if (!id.equals(o.optString("id"))) keep.put(o);
                else ignCands.put(o);
            }
            JSONArray ign = new JSONArray(p.getString("ignored", "[]"));
            ign.put(id);
            // the SMS window never reaches back further than this, so old ids can go
            JSONArray trimmed = new JSONArray();
            for (int i = Math.max(0, ign.length() - 500); i < ign.length(); i++) trimmed.put(ign.get(i));
            p.edit().putString("cands", keep.toString()).putString("ignored", trimmed.toString())
                    .putString("ignoredCands", ignCands.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** Moves an ignored SMS back to the review list. */
    static synchronized void unignore(Context c, String id) {
        SharedPreferences p = prefs(c);
        try {
            JSONArray cands = new JSONArray(p.getString("cands", "[]"));
            JSONArray ignCands = new JSONArray(p.getString("ignoredCands", "[]")), keepIgn = new JSONArray();
            for (int i = 0; i < ignCands.length(); i++) {
                JSONObject o = ignCands.getJSONObject(i);
                if (id.equals(o.optString("id"))) cands.put(o);
                else keepIgn.put(o);
            }
            JSONArray ign = new JSONArray(p.getString("ignored", "[]")), keepIds = new JSONArray();
            for (int i = 0; i < ign.length(); i++) if (!id.equals(ign.optString(i))) keepIds.put(ign.get(i));
            p.edit().putString("cands", cands.toString()).putString("ignoredCands", keepIgn.toString())
                    .putString("ignored", keepIds.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** Expenses the app already has, as [{d:'yyyy-mm-dd', a:amount, s:smsId}], so matching works while the app is closed. */
    static void setExpenses(Context c, String json) {
        prefs(c).edit().putString("expenses", json).apply();
    }

    /** Candidates with no expense of the same amount within a day either side (same rule as the app). */
    static List<JSONObject> unmatched(Context c) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray cands = new JSONArray(prefs(c).getString("cands", "[]"));
            JSONArray ex = new JSONArray(prefs(c).getString("expenses", "[]"));
            boolean[] used = new boolean[ex.length()];
            Set<String> linked = new HashSet<>();
            for (int j = 0; j < ex.length(); j++) {
                String s = ex.getJSONObject(j).optString("s", "");
                if (!s.isEmpty()) { linked.add(s); used[j] = true; }
            }
            for (int i = 0; i < cands.length(); i++) {
                JSONObject o = cands.getJSONObject(i);
                if (linked.contains(o.getString("id"))) continue;
                long d0 = dayNum(o.getString("date"));
                boolean hit = false;
                for (int j = 0; j < ex.length() && !hit; j++) {
                    if (used[j]) continue;
                    JSONObject e = ex.getJSONObject(j);
                    if (Math.abs(e.optDouble("a") - o.optDouble("amt")) < 1 && Math.abs(dayNum(e.getString("d")) - d0) <= 1) {
                        used[j] = true;
                        hit = true;
                    }
                }
                if (!hit) out.add(o);
            }
        } catch (Exception ignored) { }
        return out;
    }

    private static long dayNum(String ymd) {
        String[] p = ymd.split("-");
        Calendar x = Calendar.getInstance();
        x.clear();
        x.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
        return x.getTimeInMillis() / DAY;
    }

    /* ---------------- notifications ---------------- */

    private static void channels(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CH_SMS) == null) {
            NotificationChannel ch = new NotificationChannel(CH_SMS, "Expenses to review", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Bank SMS that aren't in your expenses yet (checked at 9 pm)");
            nm.createNotificationChannel(ch);
        }
        if (nm.getNotificationChannel(CH_REVIEW) == null) {
            NotificationChannel ch = new NotificationChannel(CH_REVIEW, "Weekly & monthly review", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Full-screen expense review on Sunday evening and the 1st of the month");
            ch.setSound(null, null);
            ch.enableVibration(false);
            ch.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            nm.createNotificationChannel(ch);
        }
    }

    static void notifyUnmatched(Context c) {
        List<JSONObject> list = unmatched(c);
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (list.isEmpty()) { nm.cancel(NID_SMS); return; }
        channels(c);
        double total = 0;
        for (JSONObject o : list) total += o.optDouble("amt");
        NumberFormat nf = NumberFormat.getNumberInstance(new Locale("en", "IN"));
        nf.setMaximumFractionDigits(0);
        int n = list.size();
        Intent open = new Intent(c, MainActivity.class).setAction(ACTION_OPEN_SMS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, NID_SMS, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification nt = new Notification.Builder(c, CH_SMS)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(Theme.accent(c))
                .setContentTitle(n + (n == 1 ? " expense" : " expenses") + " not added yet")
                .setContentText("₹" + nf.format(total) + " from your bank SMS · tap to review")
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(NID_SMS, nt);
    }

    static void showReview(Context c, String period) {
        channels(c);
        boolean week = "week".equals(period);
        Intent full = new Intent(c, MainActivity.class).setAction(ACTION_SHOW_REVIEW).putExtra("period", period)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, NID_REVIEW + (week ? 0 : 1), full,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification nt = new Notification.Builder(c, CH_REVIEW)
                .setSmallIcon(R.drawable.ic_stat)
                .setColor(Theme.accent(c))
                .setContentTitle(week ? "Your weekly expense review" : "Your monthly expense review")
                .setContentText(week ? "See where your money went this week" : "See where your money went last month")
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setTimeoutAfter(6 * 3600000L)
                .build();
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(NID_REVIEW, nt);
    }

    static void clearReviewNotification(Context c) {
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).cancel(NID_REVIEW);
    }
}
