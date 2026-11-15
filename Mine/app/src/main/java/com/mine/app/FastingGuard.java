package com.mine.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;

/**
 * Watches only which app comes to the front (never screen content). During a fast, opening a
 * blocked app sends you home and shows the "You're fasting" screen instead.
 */
public class FastingGuard extends AccessibilityService {
    private String lastPkg = "";
    private long lastAt = 0;

    static boolean enabled(Context c) {
        String on = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return on != null && on.contains(c.getPackageName() + "/");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        if (pkg.equals(getPackageName()) || !Fasting.blocks(this, pkg) || !Fasting.active(this)) return;
        long now = System.currentTimeMillis();
        if (pkg.equals(lastPkg) && now - lastAt < 1500) return;
        lastPkg = pkg;
        lastAt = now;
        performGlobalAction(GLOBAL_ACTION_HOME);
        Intent i = new Intent(this, FastingBlockActivity.class)
                .putExtra("pkg", pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(i);
    }

    @Override
    public void onInterrupt() { }
}
