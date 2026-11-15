package com.mine.app;

import android.app.Activity;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Minimal alarm screen: the time, what it is for, three snooze times and dismiss. */
public class AlarmActivity extends Activity {
    private static final int BG = Color.parseColor("#0A0F0C");
    private static final int INK = Color.parseColor("#E8F1EB");
    private static final int MUTED = Color.parseColor("#7D9085");
    private static final int LINE = Color.parseColor("#26332C");
    private int accent = Theme.DEFAULT_ACCENT;

    private JSONObject alarm;
    private int nid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        accent = Theme.accent(this);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        try {
            alarm = new JSONObject(getIntent().getStringExtra("json"));
        } catch (Exception e) {
            finish();
            return;
        }
        nid = alarm.optString("key", "").hashCode();
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        build(km == null || !km.isKeyguardLocked());
    }

    /** While the phone is locked, only the time shows; what the alarm is for appears after unlocking. */
    private void build(boolean details) {
        String kind = alarm.optString("kind", "task");
        boolean doneKind = "task".equals(kind) || "habit".equals(kind);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(BG);
        int pad = dp(32);
        root.setPadding(pad, dp(110), pad, dp(40));

        String time = new SimpleDateFormat("h:mm", Locale.getDefault())
                .format(new Date(alarm.optLong("at", System.currentTimeMillis())));
        root.addView(text(time, 96, INK, Typeface.create("sans-serif-thin", Typeface.NORMAL)));

        TextView title = text(details ? alarm.optString("title", "Reminder") : "Reminder", 20, INK,
                Typeface.create("sans-serif", Typeface.NORMAL));
        title.setPadding(0, dp(18), 0, 0);
        root.addView(title);
        String body = details ? alarm.optString("body", "") : "";
        if (!body.isEmpty()) {
            TextView sub = text(body, 14, MUTED, Typeface.DEFAULT);
            sub.setPadding(0, dp(6), 0, 0);
            root.addView(sub);
        }
        if (!details) {
            TextView show = text("Unlock to see details", 14, MUTED, Typeface.DEFAULT);
            show.setPadding(dp(12), dp(10), dp(12), dp(6));
            show.setOnClickListener(v -> {
                KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
                if (km == null) { build(true); return; }
                km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                    @Override
                    public void onDismissSucceeded() {
                        build(true);
                    }
                });
            });
            root.addView(show);
        }

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        if (details && doneKind) {
            root.addView(button("task".equals(kind) ? "Done" : "Check in", accent, true, LinearLayout.LayoutParams.MATCH_PARENT, v -> {
                cancelNotification();
                startActivity(AlarmReceiver.doneIntent(this, alarm.optString("key", ""), alarm.toString()));
                finish();
            }));
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(12), 0, 0);
        for (int min : AlarmReceiver.SNOOZE_MIN) {
            String label = min >= 60 ? (min / 60) + " hr" : min + " min";
            Button b = button(label, INK, false, 0, v -> {
                cancelNotification();
                AlarmScheduler.snooze(this, alarm, min);
                finish();
            });
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
            lp.weight = 1f;
            lp.setMargins(dp(4), 0, dp(4), 0);
            row.addView(b);
        }
        root.addView(row);

        TextView dismiss = text("Dismiss", 15, MUTED, Typeface.DEFAULT);
        dismiss.setPadding(dp(24), dp(24), dp(24), dp(4));
        dismiss.setOnClickListener(v -> {
            cancelNotification();
            finish();
        });
        root.addView(dismiss);
        setContentView(root);
    }

    private void cancelNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.cancel(nid);
    }

    private TextView text(String s, int sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(tf);
        return t;
    }

    /** Thin outlined button; the stroke is the only colour on the screen. */
    private Button button(String label, int color, boolean accentStroke, int width, View.OnClickListener l) {
        Button b = baseButton(label, 15, color, l);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(26));
        bg.setColor(Color.TRANSPARENT);
        bg.setStroke(dp(1), accentStroke ? accent : LINE);
        b.setBackground(bg);
        b.setLayoutParams(new LinearLayout.LayoutParams(width, dp(52)));
        return b;
    }

    private Button baseButton(String label, int sp, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        b.setTextColor(color);
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
