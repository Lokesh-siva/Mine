package com.mine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Start and end of the fasting window, and the "I ate" button on its notification. */
public class FastingReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent in) {
        String a = in.getAction();
        if (Fasting.ACT_START.equals(a)) {
            Fasting.refresh(c);
        } else if (Fasting.ACT_END.equals(a)) {
            Fasting.finished(c);
        } else if (Fasting.ACT_BREAK.equals(a)) {
            Fasting.breakFast(c);
        }
        Fasting.schedule(c);
    }
}
