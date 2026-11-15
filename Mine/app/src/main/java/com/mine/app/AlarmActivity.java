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

/** Minimal neon alarm screen: big glowing time, one main action, small snooze pills, a quiet dismiss. */
public class AlarmActivity extends Activity {
    private static final int BG = Color.parseColor("#07060B");
    private static final int INK = Color.parseColor("#F3F1FA");
    private static final int MUTED = Color.parseColor("#9C97B0");
    private static final int LINE = Color.parseColor("#26222F");
    private int accent = Theme.DEFAULT_ACCENT;
    private int accent2 = Theme.DEFAULT_ACCENT;
    private int onAccent = Color.WHITE;

    private JSONObject alarm;
    private int nid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        accent = Theme.accent(this);
        onAccent = Theme.ink(accent);
        // partner colour for the neon gradient: the accent turned about 40° round the colour wheel
        float[] hsv = new float[3];
        Color.colorToHSV(accent, hsv);
        hsv[0] = (hsv[0] + 40) % 360;
        accent2 = Color.HSVToColor(hsv);
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
        int pad = dp(28);
        root.setPadding(pad, dp(72), pad, dp(36));
        // soft neon halo behind the time
        GradientDrawable halo = new GradientDrawable();
        halo.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        halo.setColors(new int[]{withAlpha(accent, 0x55), withAlpha(accent2, 0x18), BG});
        halo.setGradientCenter(0.5f, 0.3f);
        halo.setGradientRadius(getResources().getDisplayMetrics().widthPixels * 0.95f);
        root.setBackground(halo);

        TextView brand = text("MINE", 13, MUTED, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        brand.setLetterSpacing(0.35f);
        root.addView(brand);

        String time = new SimpleDateFormat("h:mm", Locale.getDefault())
                .format(new Date(alarm.optLong("at", System.currentTimeMillis())));
        String ampm = new SimpleDateFormat("a", Locale.getDefault())
                .format(new Date(alarm.optLong("at", System.currentTimeMillis())));
        TextView clock = text(time, 92, INK, Typeface.create("sans-serif-thin", Typeface.NORMAL));
        clock.setShadowLayer(dp(18), 0, 0, accent);
        clock.setPadding(0, dp(28), 0, 0);
        root.addView(clock);
        TextView ap = text(ampm.toUpperCase(Locale.getDefault()), 14, accent, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        ap.setLetterSpacing(0.25f);
        root.addView(ap);

        TextView title = text(details ? alarm.optString("title", "Reminder") : "Reminder", 22, INK,
                Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(0, dp(30), 0, 0);
        root.addView(title);
        String body = details ? alarm.optString("body", "") : "Unlock to see what it’s for";
        if (!body.isEmpty()) {
            TextView sub = text(body, 14, MUTED, Typeface.DEFAULT);
            sub.setPadding(0, dp(6), 0, 0);
            root.addView(sub);
        }
        if (!details) {
            TextView show = text("Show details", 14, accent, Typeface.create("sans-serif-medium", Typeface.NORMAL));
            show.setPadding(dp(12), dp(14), dp(12), dp(6));
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

        // one main action: done for tasks and habits, otherwise a 5-minute snooze
        if (details && doneKind) {
            root.addView(primary("task".equals(kind) ? "Mark done" : "Check in", v -> {
                cancelNotification();
                startActivity(AlarmReceiver.doneIntent(this, alarm.optString("key", ""), alarm.toString()));
                finish();
            }));
        }

        TextView snoozeLabel = text("SNOOZE", 11, MUTED, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        snoozeLabel.setLetterSpacing(0.3f);
        snoozeLabel.setPadding(0, dp(22), 0, dp(8));
        root.addView(snoozeLabel);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        for (int min : AlarmReceiver.SNOOZE_MIN) {
            String label = min >= 60 ? (min / 60) + " hr" : min + " min";
            boolean main = !(details && doneKind) && min == AlarmReceiver.SNOOZE_MIN[0];
            row.addView(pill(label, main, v -> {
                cancelNotification();
                AlarmScheduler.snooze(this, alarm, min);
                finish();
            }));
        }
        root.addView(row);

        TextView dismiss = text("Dismiss", 15, MUTED, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        dismiss.setPadding(dp(24), dp(22), dp(24), dp(4));
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

    /** Wide neon-gradient pill. */
    private Button primary(String label, View.OnClickListener l) {
        Button b = baseButton(label, 17, onAccent, l);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{accent, accent2});
        bg.setCornerRadius(dp(32));
        b.setBackground(bg);
        if (Build.VERSION.SDK_INT >= 28) {
            b.setElevation(dp(14));
            b.setOutlineSpotShadowColor(accent);
            b.setOutlineAmbientShadowColor(accent);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(60));
        b.setLayoutParams(lp);
        return b;
    }

    /** Small snooze pill; the main one is filled with the neon gradient. */
    private Button pill(String label, boolean main, View.OnClickListener l) {
        Button b = baseButton(label, 15, main ? onAccent : INK, l);
        GradientDrawable bg = main
                ? new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{accent, accent2})
                : new GradientDrawable();
        bg.setCornerRadius(dp(26));
        if (!main) {
            bg.setColor(withAlpha(Color.WHITE, 0x0D));
            bg.setStroke(dp(1), LINE);
        }
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        lp.setMargins(dp(5), 0, dp(5), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private Button baseButton(String label, int sp, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(color);
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        return b;
    }

    private static int withAlpha(int c, int a) {
        return (c & 0x00FFFFFF) | (a << 24);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
