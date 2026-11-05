package com.mine.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.os.Build;
import android.widget.RemoteViews;

/** 2x1 home screen widget: this month's spending and a + button that opens "Add expense". */
public class ExpenseWidget extends AppWidgetProvider {
    static final String ACTION_ADD_EXPENSE = "com.mine.app.ADD_EXPENSE";
    private static final String PREFS = "mine_widget";

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) m.updateAppWidget(id, build(c));
    }

    /** Called from the app whenever the month's total changes. */
    static void save(Context c, String label, String amount) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("label", label).putString("amount", amount).apply();
        refresh(c);
    }

    static void refresh(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, ExpenseWidget.class));
        if (ids.length > 0) m.updateAppWidget(ids, build(c));
    }

    private static RemoteViews build(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean locked = c.getSharedPreferences("mine_security", Context.MODE_PRIVATE).getBoolean("lock", false);
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_expense);
        if (locked) {
            // Don't show spending on the home screen when App lock is on
            v.setTextViewText(R.id.widget_label, "MINE");
            v.setTextViewText(R.id.widget_amount, "Add expense");
        } else {
            v.setTextViewText(R.id.widget_label, p.getString("label", "SPENT"));
            v.setTextViewText(R.id.widget_amount, p.getString("amount", "₹0"));
        }
        if (Build.VERSION.SDK_INT >= 31) {
            int accent = Theme.accent(c);
            v.setColorStateList(R.id.widget_add, "setBackgroundTintList", ColorStateList.valueOf(accent));
            v.setColorStateList(R.id.widget_add, "setImageTintList", ColorStateList.valueOf(Theme.ink(accent)));
        }
        Intent i = new Intent(c, MainActivity.class).setAction(ACTION_ADD_EXPENSE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 7, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        v.setOnClickPendingIntent(R.id.widget_root, pi);
        v.setOnClickPendingIntent(R.id.widget_add, pi);
        return v;
    }
}
