/*
 * This is the source code of LuxGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * LuxGram Chat Lock (Fix 80) — two-layer lock for selected chats.
 *
 * The Vault already hides chats locally; this adds a second, independent
 * layer: chosen conversations demand a PIN (and/or the device fingerprint)
 * every time they are opened, and the unlock does NOT survive the app going
 * to background. The counterpart can never unlock anything from their side.
 *
 * Stored globally in its own preferences file, PIN kept as SHA-256 only.
 * Everything is null-safe and never crashes the chat page.
 */
package org.telegram.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.core.os.CancellationSignal;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.support.fingerprint.FingerprintManagerCompat;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public final class LuxGramChatLock {

    private static final String PREF_FILE = "luxgram_privacy";
    private static final String KEY_PIN = "chat_lock_pin";
    private static final String KEY_DIALOGS = "chat_lock_dialogs";
    private static final HashSet<Long> sessionUnlocked = new HashSet<>();

    private LuxGramChatLock() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    //region PIN + dialog set

    public static boolean hasPin() {
        try {
            return !TextUtils.isEmpty(prefs().getString(KEY_PIN, null));
        } catch (Throwable t) {
            return false;
        }
    }

    public static void setPin(String pin) {
        try {
            prefs().edit().putString(KEY_PIN, sha256(pin)).apply();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public static boolean checkPin(String pin) {
        try {
            String saved = prefs().getString(KEY_PIN, null);
            return saved != null && saved.equals(sha256(pin));
        } catch (Throwable t) {
            return false;
        }
    }

    private static String sha256(String raw) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((raw + ":luxgram").getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            FileLog.e(t);
            return "";
        }
    }

    public static Set<String> lockedDialogIds() {
        try {
            return prefs().getStringSet(KEY_DIALOGS, new HashSet<>());
        } catch (Throwable t) {
            return new HashSet<>();
        }
    }

    public static boolean isLocked(long dialogId) {
        if (!hasPin() || dialogId == 0) {
            return false;
        }
        return lockedDialogIds().contains(String.valueOf(dialogId));
    }

    public static void toggleLocked(long dialogId, boolean lock) {
        try {
            Set<String> set = new HashSet<>(lockedDialogIds());
            String key = String.valueOf(dialogId);
            if (lock) {
                set.add(key);
            } else {
                set.remove(key);
            }
            prefs().edit().putStringSet(KEY_DIALOGS, set).apply();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public static int lockedCount() {
        return lockedDialogIds().size();
    }

    //endregion

    //region session unlock (re-armed when the app goes to background)

    public static void unlockSession(long dialogId) {
        sessionUnlocked.add(dialogId);
    }

    /** LaunchActivity.onPause: every background moment re-arms the lock. */
    public static void onAppPaused() {
        sessionUnlocked.clear();
    }

    //endregion

    //region chat gate

    /**
     * Call from ChatActivity.onResume. If the chat is locked for this session,
     * a non-dismissable gate asks for the PIN (or a fingerprint touch) and
     * "close chat" is the only other way out.
     */
    public static void maybeLock(final BaseFragment fragment, final long dialogId) {
        try {
            if (fragment == null || fragment.getParentActivity() == null) {
                return;
            }
            if (!isLocked(dialogId) || sessionUnlocked.contains(dialogId)) {
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    showGate(fragment, dialogId);
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            }, 350);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void showGate(final BaseFragment fragment, final long dialogId) {
        final Activity context = fragment.getParentActivity();
        AlertDialog.Builder builder = new AlertDialog.Builder(context, fragment.getResourceProvider());
        builder.setTitle(LocaleController.getString(R.string.LuxGramChatLockTitle));
        builder.setMessage(LocaleController.getString(R.string.LuxGramChatLockEnter));

        final EditText input = new EditText(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setMaxLines(1);
        int pad = AndroidUtilities.dp(20);
        input.setPadding(pad, pad / 2, pad, pad / 2);
        input.setTextColor(0xFFF7F0DF);
        input.setHintTextColor(0x99B9AD95);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(12));
        bg.setColor(0x1AFFFFFF);
        bg.setStroke(AndroidUtilities.dp(1), 0x59F4D27A);
        input.setBackground(bg);

        final TextView hint = new TextView(context);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hint.setTextColor(0xFFB9AD95);
        hint.setGravity(Gravity.CENTER);

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.setMargins(0, AndroidUtilities.dp(10), 0, 0);
        box.addView(input, ip);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.setMargins(0, AndroidUtilities.dp(10), 0, 0);
        box.addView(hint, hp);

        FrameLayout container = new FrameLayout(context);
        FrameLayout.LayoutParams gateLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        gateLp.setMargins(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        container.addView(box, gateLp);
        builder.setView(container);

        // Only two ways out: the right PIN / fingerprint, or closing the chat.
        builder.setPositiveButton(LocaleController.getString(R.string.Done), (d, w) -> {
            try {
                String pin = input.getText().toString().trim();
                if (LuxGramChatLock.checkPin(pin)) {
                    unlockSession(dialogId);
                    d.dismiss();
                } else {
                    input.setText("");
                    hint.setText(LocaleController.getString(R.string.LuxGramChatLockWrong));
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.LuxGramChatLockClose), (d, w) -> {
            try {
                fragment.finishFragment();
            } catch (Throwable t) {
                FileLog.e(t);
            }
        });

        final AlertDialog dialog = builder.create();
        dialog.setCancelable(false); // gate: only PIN / fingerprint / close-chat
        dialog.show();
        final CancellationSignal[] signal = new CancellationSignal[1];

        dialog.setOnDismissListener(d -> {
            try {
                if (signal[0] != null) {
                    signal[0].cancel();
                }
            } catch (Throwable ignore) {
            }
        });

        // Fingerprint path — plain client gate, no crypto key ceremony needed.
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                FingerprintManagerCompat fp = FingerprintManagerCompat.from(ApplicationLoader.applicationContext);
                if (fp.isHardwareDetected() && fp.hasEnrolledFingerprints()) {
                    hint.setText(LocaleController.getString(R.string.LuxGramChatLockFpHint));
                    signal[0] = new CancellationSignal();
                    fp.authenticate(null, 0, signal[0], new FingerprintManagerCompat.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(FingerprintManagerCompat.AuthenticationResult result) {
                            AndroidUtilities.runOnUIThread(() -> {
                                unlockSession(dialogId);
                                try {
                                    dialog.dismiss();
                                } catch (Throwable ignore) {
                                }
                            });
                        }

                        @Override
                        public void onAuthenticationError(int errMsgId, CharSequence errString) {
                            AndroidUtilities.runOnUIThread(() -> {
                                try {
                                    hint.setText(errString == null ? "" : errString.toString());
                                } catch (Throwable ignore) {
                                }
                            });
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            AndroidUtilities.runOnUIThread(() -> {
                                try {
                                    hint.setText(LocaleController.getString(R.string.LuxGramChatLockWrong));
                                } catch (Throwable ignore) {
                                }
                            });
                        }
                    }, null);
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }

        // PIN check happens when the user presses the keyboard's action key.
        input.setOnEditorActionListener((v, actionId, event) -> {
            try {
                String pin = input.getText().toString().trim();
                if (LuxGramChatLock.checkPin(pin)) {
                    unlockSession(dialogId);
                    dialog.dismiss();
                } else {
                    input.setText("");
                    hint.setText(LocaleController.getString(R.string.LuxGramChatLockWrong));
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
            return true;
        });
    }

    //endregion
}
