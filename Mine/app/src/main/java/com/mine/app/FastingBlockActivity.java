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

/** Shown instead of a blocked app during a fast: time left, streak, gym progress. */
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
        root.setPadding(dp(32), dp(100), dp(32), dp(40));

        TextView moon = text("🌙", 44, INK, Typeface.DEFAULT);
        root.addView(moon);
        TextView title = text("You’re fasting", 28, INK, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(0, dp(14), 0, 0);
        root.addView(title);
        TextView sub = text(app + " is paused until " + Fasting.endLabel(this, end), 15, MUTED, Typeface.DEFAULT);
        sub.setPadding(0, dp(6), 0, 0);
        root.addView(sub);

        left = text("", 56, accent, Typeface.create("sans-serif-thin", Typeface.NORMAL));
        left.setPadding(0, dp(30), 0, 0);
        root.addView(left);
        TextView leftLabel = text("LEFT", 11, MUTED, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        leftLabel.setLetterSpacing(0.25f);
        root.addView(leftLabel);

        int s = Fasting.streak(this);
        TextView streak = text(s > 0 ? "🔥 " + s + "-day streak" : "Finish tonight to start your streak", 17, INK, Typeface.DEFAULT);
        streak.setPadding(0, dp(30), 0, 0);
        root.addView(streak);
        String prog = Fasting.progress(this);
        if (!prog.isEmpty()) {
            TextView p = text(prog, 14, MUTED, Typeface.DEFAULT);
            p.setPadding(0, dp(6), 0, 0);
            root.addView(p);
        }

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1f));

        Button back = button("Back to home", accent, true);
        back.setOnClickListener(v -> goHome());
        root.addView(back);
        Button anyway = button("Open anyway", INK, false);
        anyway.setOnClickListener(v -> {
            if (!armed) {
                armed = true;
                anyway.setText("Tap again: this ends today’s fast");
                return;
            }
            Fasting.breakFast(this);
            Intent launch = getPackageManager().getLaunchIntentForPackage(pkg == null ? "" : pkg);
            finish();
            if (launch != null) startActivity(launch);
        });
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) anyway.getLayoutParams();
        lp.topMargin = dp(10);
        root.addView(anyway);
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
        left.setText((m / 60) + "h " + String.format(java.util.Locale.US, "%02d", m % 60) + "m");
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
            b.setTextColor(MUTED);
        }
        b.setBackground(bg);
        b.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
        return b;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
