package com.mine.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full-screen "not while fasting" message over a paused app, styled like the alarm screen. */
public class FastingBlockActivity extends Activity {
    private static final int BG = Color.parseColor("#0A0F0C");
    private static final int INK = Color.parseColor("#E8F1EB");
    private static final int MUTED = Color.parseColor("#7D9085");
    private static final int LINE = Color.parseColor("#33433A");
    private final Handler tick = new Handler(Looper.getMainLooper());
    private TextView left;
    private long end;
    private boolean armed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        int accent = Theme.accent(this);
        String pkg = getIntent().getStringExtra("pkg");
        String app = "This app";
        try {
            PackageManager pm = getPackageManager();
            app = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception ignored) { }
        long st = Fasting.windowStart(this, System.currentTimeMillis());
        end = st >= 0 ? Fasting.windowEnd(this, st) : System.currentTimeMillis();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(32), dp(110), dp(32), dp(40));

        // same layout as the alarm screen: big thin countdown, a little green, two buttons at the bottom
        left = text("", 96, INK, Typeface.create("sans-serif-thin", Typeface.NORMAL));
        root.addView(left);
        TextView until = text("LEFT · UNTIL " + Fasting.endLabel(this, end).toUpperCase(java.util.Locale.getDefault()), 13, accent,
                Typeface.create("sans-serif-medium", Typeface.NORMAL));
        until.setLetterSpacing(0.2f);
        root.addView(until);

        TextView title = text("Not available while fasting", 20, INK, Typeface.create("sans-serif", Typeface.NORMAL));
        title.setPadding(0, dp(22), 0, 0);
        root.addView(title);
        TextView sub = text(app + " opens again at " + Fasting.endLabel(this, end), 14, MUTED, Typeface.DEFAULT);
        sub.setPadding(0, dp(6), 0, 0);
        root.addView(sub);

        int s = Fasting.streak(this);
        String prog = Fasting.progress(this);
        String line = (s > 0 ? "🔥 " + s + "-day streak" : "Finish tonight to start your streak") + (prog.isEmpty() ? "" : "\n" + prog);
        TextView streak = text(line, 14, MUTED, Typeface.DEFAULT);
        streak.setPadding(0, dp(26), 0, 0);
        streak.setLineSpacing(dp(3), 1f);
        root.addView(streak);

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        Button anyway = button("Open anyway", INK, false);
        anyway.setOnClickListener(v -> {
            if (!armed) {
                armed = true;
                anyway.setText("Tap again to end fast");
                return;
            }
            Fasting.breakFast(this);
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg == null ? "" : pkg);
            finish();
            if (launch != null) startActivity(launch);
        });
        row.addView(anyway);
        Button back = button("Back to home", accent, true);
        back.setOnClickListener(v -> goHome());
        row.addView(back);
        root.addView(row);
        setContentView(root);
        update();
    }

    private final Runnable every = this::update;

    private void update() {
        long ms = Math.max(0, end - System.currentTimeMillis());
        if (ms == 0) {
            finish();
            return;
        }
        long m = ms / 60000;
        left.setText((m / 60) + ":" + String.format(java.util.Locale.US, "%02d", m % 60));
        tick.postDelayed(every, 30000);
    }

    @Override
    protected void onDestroy() {
        tick.removeCallbacks(every);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        goHome();
    }

    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(home);
        finish();
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

    private Button button(String label, int color, boolean filled) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        b.setStateListAnimator(null);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(26));
        if (filled) {
            bg.setColor(color);
            b.setTextColor(Theme.ink(color));
        } else {
            bg.setColor(Color.TRANSPARENT);
            bg.setStroke(dp(1), LINE);
            b.setTextColor(INK);
        }
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(52), 1f);
        lp.setMargins(dp(5), 0, dp(5), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
