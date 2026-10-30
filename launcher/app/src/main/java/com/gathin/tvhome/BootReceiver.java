package com.gathin.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Restarts the Home redirect after a reboot, if it was on. */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "HomeRedirect";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        boolean on = HomeRedirectService.isEnabled(ctx);
        Log.i(TAG, "boot broadcast " + intent.getAction() + ", redirect on=" + on);
        if (on) ctx.startService(new Intent(ctx, HomeRedirectService.class));
    }
}
