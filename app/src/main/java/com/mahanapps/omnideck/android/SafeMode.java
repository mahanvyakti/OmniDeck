package com.mahanapps.omnideck.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

/**
 * Monitors app stability. If consecutive rapid crashes occur on startup,
 * SafeMode activates to prevent boot/crash loops on sensitive TV hardware.
 */
public final class SafeMode {

    private static final String PREF_NAME = "omnideck_safemode";
    private static final String KEY_CRASH_COUNT = "crash_count";
    private static final String KEY_SAFE_ACTIVE = "safe_active";
    private static final int THRESHOLD = 2;

    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean isSafeMode = false;

    public SafeMode(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        init();
    }

    private void init() {
        int crashes = prefs.getInt(KEY_CRASH_COUNT, 0);
        if (crashes >= THRESHOLD || prefs.getBoolean(KEY_SAFE_ACTIVE, false)) {
            isSafeMode = true;
        }

        // Increment launch marker
        prefs.edit().putInt(KEY_CRASH_COUNT, crashes + 1).apply();

        // If app survives for 20 seconds cleanly, reset counter
        handler.postDelayed(() -> {
            prefs.edit().putInt(KEY_CRASH_COUNT, 0).putBoolean(KEY_SAFE_ACTIVE, false).apply();
            isSafeMode = false;
        }, 20000);
    }

    public boolean isSafeModeActive() {
        return isSafeMode;
    }

    public void resetSafeMode() {
        isSafeMode = false;
        prefs.edit().putInt(KEY_CRASH_COUNT, 0).putBoolean(KEY_SAFE_ACTIVE, false).apply();
    }

    public void forceSafeMode() {
        isSafeMode = true;
        prefs.edit().putBoolean(KEY_SAFE_ACTIVE, true).apply();
    }
}
