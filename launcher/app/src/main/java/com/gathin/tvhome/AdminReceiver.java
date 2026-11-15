package com.gathin.tvhome;

import android.app.admin.DeviceAdminReceiver;

/**
 * Device admin with the single "force lock" policy, so the launcher can put the TV to standby (lockNow): the
 * accessibility lock-screen action does nothing on a TV without a lock screen. Activated over adb with
 *   dpm set-active-admin com.gathin.tvhome/.AdminReceiver
 * and removed with dpm remove-active-admin (needed before the app can be uninstalled).
 */
public class AdminReceiver extends DeviceAdminReceiver {
}
