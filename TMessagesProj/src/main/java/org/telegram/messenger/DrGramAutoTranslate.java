/*
 * DrGram Fix 80 — per-chat auto-translate unlock.
 * The user asked: "ترجمه خودکار هم بزار برای پیام ها یا کل پیام های یک گفتگو".
 * Telegram gates the whole-chat translate panel behind premium; this class
 * stores a per-dialog flag set from the chat three-dot menu and the
 * TranslateController / ChatActivity gates consult it, so the native
 * translate panel works for every chat without premium.
 */
package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

public class DrGramAutoTranslate {

    private static final String PREFS = "drgram_autotranslate";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    public static boolean isEnabled(long dialogId) {
        try {
            return prefs().getBoolean("d_" + dialogId, false);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean setEnabled(long dialogId, boolean value) {
        try {
            prefs().edit().putBoolean("d_" + dialogId, value).apply();
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return value;
    }

    public static boolean toggle(long dialogId) {
        return setEnabled(dialogId, !isEnabled(dialogId));
    }

    /** True when the user turned auto-translate on for at least one chat. */
    public static boolean isFeatureUnlocked() {
        try {
            return !prefs().getAll().isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }
}
