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
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class AlarmActivity extends Activity {
    private static final int BG = Color.parseColor("#0A0F0C");
    private static final int GREEN = Color.parseColor("#3DDC84");
    private static final int INK = Color.parseColor("#E8F1EB");
    private static final int MUTED = Color.parseColor("#9DB0A4");
    private static final int DARK = Color.parseColor("#04140A");

    private JSONObject alarm;
    private int nid;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km != null) km.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        try {
            alarm = new JSONObject(getIntent().getStringExtra("json"));
        } catch (Exception e) {
            finish();
            return;
        }
        nid = alarm.optString("key", "").hashCode();
        String kind = alarm.optString("kind", "task");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(BG);
        int pad = dp(28);
        root.setPadding(pad, pad, pad, pad);

        TextView label = text("⏰  Mine", 16, GREEN, true);
        root.addView(label);

        String time = new SimpleDateFormat("h:mm a", Locale.getDefault())
                .format(new Date(alarm.optLong("at", System.currentTimeMillis())));
        TextView clock = text(time, 64, GREEN, true);
        clock.setPadding(0, dp(18), 0, 0);
        root.addView(clock);

        TextView title = text(alarm.optString("title", "Reminder"), 26, INK, true);
        title.setPadding(0, dp(14), 0, 0);
        root.addView(title);

        String body = alarm.optString("body", "");
        if (!body.isEmpty()) {
            TextView sub = text(body, 16, MUTED, false);
            sub.setPadding(0, dp(6), 0, 0);
            root.addView(sub);
        }

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.VERTICAL);
        buttons.setGravity(Gravity.CENTER);
        buttons.setPadding(0, dp(36), 0, 0);

        if ("task".equals(kind) || "habit".equals(kind)) {
            buttons.addView(button("task".equals(kind) ? "Mark done" : "Check in", true, v -> {
                cancelNotification();
                startActivity(AlarmReceiver.doneIntent(this, alarm.optString("key", ""), alarm.toString()));
                finish();
            }));
        }
        int snooze = alarm.optInt("snooze", 10);
        buttons.addView(button("Snooze " + snooze + " min", !("task".equals(kind) || "habit".equals(kind)), v -> {
            cancelNotification();
            AlarmScheduler.snooze(this, alarm);
            finish();
        }));
        buttons.addView(button("Dismiss", false, v -> {
            cancelNotification();
            finish();
        }));
        root.addView(buttons);
        setContentView(root);
    }

    private void cancelNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.cancel(nid);
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return t;
    }

    private Button button(String label, boolean primary, android.view.View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        b.setTextColor(primary ? DARK : GREEN);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(30));
        if (primary) bg.setColor(GREEN);
        else {
            bg.setColor(Color.TRANSPARENT);
            bg.setStroke(dp(2), GREEN);
        }
        b.setBackground(bg);
        b.setStateListAnimator(null);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(260), dp(56));
        lp.setMargins(0, dp(6), 0, dp(6));
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
