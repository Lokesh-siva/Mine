package com.mine.app;

import android.content.Context;
import android.graphics.Color;

/** Accent colour picked in Settings, shared with the alarm screen, notifications and widget. */
final class Theme {
    static final int DEFAULT_ACCENT = 0xFF3DDC84;
    private static final String PREFS = "mine_theme";

    private Theme() { }

    static int accent(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("accent", DEFAULT_ACCENT);
    }

    static void setAccent(Context c, String hex) {
        int color;
        try {
            color = Color.parseColor(hex);
        } catch (Exception e) {
            color = DEFAULT_ACCENT;
        }
        if (color == accent(c)) return;
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("accent", color).apply();
        ExpenseWidget.refresh(c);
    }

    /** Text colour that reads well on top of the accent. */
    static int ink(int color) {
        double l = 0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color);
        return l > 140 ? 0xFF04140A : Color.WHITE;
    }
}
