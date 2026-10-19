/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Quick Lock (Fix 79) — app-wide screenshot lock.
 *
 * When enabled, FLAG_SECURE is applied to the main window: Android then
 * blocks screenshots and screen recording of the whole app and blurs the
 * preview shown in the system app switcher. A purely local privacy switch:
 * zero network, zero server knowledge, survives account switches because it
 * lives in its own global preferences file instead of SharedConfig.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Window;
import android.view.WindowManager;

public final class DrGramScreenshotLock {

    private static final String PREF_FILE = "drgram_privacy";
    private static final String KEY = "screenshot_lock";

    private DrGramScreenshotLock() {
    }

    public static boolean isEnabled() {
        try {
            SharedPreferences prefs = ApplicationLoader.applicationContext
                    .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
            return prefs.getBoolean(KEY, false);
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    public static void setEnabled(boolean on) {
        try {
            SharedPreferences prefs = ApplicationLoader.applicationContext
                    .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(KEY, on).apply();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** Applies (or lifts) the lock on a window; null-safe and never throws. */
    public static void apply(Window window) {
        if (window == null) {
            return;
        }
        try {
            if (isEnabled()) {
                window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
