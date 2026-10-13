package com.svndev.svnagent;

import android.content.pm.PackageManager;
import android.util.Log;

import rikka.shizuku.Shizuku;

/**
 * Thin wrapper over the Shizuku API.
 *
 * Shizuku lets an app run commands as the Android shell user (UID 2000)
 * without root, once the Shizuku service is running and the user has granted
 * this app the API permission. We use it to stage and launch the opencode
 * engine outside the app sandbox (the shell user cannot read our private
 * /data/data/&lt;pkg&gt;/files dir, so the engine must live in /data/local/tmp).
 */
public final class ShizukuHelper {

    private static final String TAG = "ShizukuHelper";
    public static final int REQUEST_CODE = 8421;

    private ShizukuHelper() {}

    /** True when the Shizuku service is installed and its binder is alive. */
    public static boolean binderAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            Log.w(TAG, "pingBinder failed: " + t.getMessage());
            return false;
        }
    }

    /** True once this app has been granted the Shizuku API permission. */
    public static boolean hasPermission() {
        try {
            return binderAvailable()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean shouldShowRationale() {
        try {
            return binderAvailable() && Shizuku.shouldShowRequestPermissionRationale();
        } catch (Throwable t) {
            return false;
        }
    }

    public static void requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE);
        } catch (Throwable t) {
            Log.w(TAG, "requestPermission failed: " + t.getMessage());
        }
    }

    /** Effective UID the Shizuku server runs as (2000 shell, 0 root, -1 unknown). */
    public static int uid() {
        try {
            return Shizuku.getUid();
        } catch (Throwable t) {
            return -1;
        }
    }
}
