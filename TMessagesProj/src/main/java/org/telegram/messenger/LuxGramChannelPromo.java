/*
 * This is the source code of LuxGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * LuxGram Fix 68 — official channel invitation (@DeRaCKTeam).
 *
 * What the user asked for, exactly: "میخوام هرکس اپ و نصب کرد توی کانال
 * تلگرام @DeRaCKTeam با اجازه کاربر عضو بشه (مثلا بنویسه جهت دریافت آپدیت
 * و جزئیات آپدیت اخیر به کانال ما مراجعه کنید)".
 *
 * Consent model: a themed dialog is shown AFTER the user is logged in (a
 * channel join is impossible before that anyway). Joining happens ONLY on
 * the explicit "عضویت در کانال" tap — never silently. Declining keeps the
 * dialog away for 3 days; after a successful join it never returns.
 */
package org.telegram.messenger;

import android.app.Activity;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.messenger.ChatObject;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

public class LuxGramChannelPromo {

    public static final String CHANNEL_USERNAME = "DeRaCKTeam";
    public static final String CHANNEL_URL = "https://t.me/" + CHANNEL_USERNAME;
    private static final String PREFS = "luxgram_promo";
    private static final long ASK_COOLDOWN_MS = 3L * 24 * 60 * 60 * 1000; // declined -> wait 3 days

    private static volatile boolean dialogShowing;

    /** Call from the UI thread (LaunchActivity, a few seconds after open). */
    public static void maybeShow(Activity activity) {
        try {
            if (dialogShowing) {
                return;
            }
            if (!UserConfig.getInstance(UserConfig.selectedAccount).isClientActivated()) {
                return; // not logged in yet — the join request would be useless
            }
            android.content.SharedPreferences p = prefs();
            if (p.getBoolean("joined", false)) {
                return; // already a member — never bother again
            }
            if (System.currentTimeMillis() - p.getLong("last_asked", 0L) < ASK_COOLDOWN_MS) {
                return; // declined recently — respect that for 3 days
            }
            BaseFragment lastFragment = null;
            if (activity instanceof org.telegram.ui.LaunchActivity) {
                lastFragment = ((org.telegram.ui.LaunchActivity) activity).getLastFragment();
            }
            // single assignment keeps the reference effectively final for the
            // button lambdas below
            final BaseFragment fragment = lastFragment;
            if (fragment == null || fragment.getParentActivity() == null) {
                return; // no surface to show the dialog on — retry next launch
            }
            dialogShowing = true;
            p.edit().putLong("last_asked", System.currentTimeMillis()).apply();

            AlertDialog dialog = new AlertDialog(fragment.getParentActivity(), AlertDialog.ALERT_TYPE_MESSAGE);
            dialog.setTitle(LocaleController.getString(R.string.LuxGramChannelPromoTitle));
            dialog.setMessage(LocaleController.getString(R.string.LuxGramChannelPromoText));
            dialog.setPositiveButton(LocaleController.getString(R.string.LuxGramChannelPromoJoin), (d, w) -> {
                dialogShowing = false;
                join(fragment);
            });
            dialog.setNegativeButton(LocaleController.getString(R.string.LuxGramChannelPromoLater), (d, w) -> dialogShowing = false);
            dialog.show();
        } catch (Throwable t) {
            dialogShowing = false;
            FileLog.e(t);
        }
    }

    /**
     * Explicit-consent join: resolve @DeRaCKTeam, then joinChannel. Falls back
     * to opening the public t.me preview (whose own Join button is again an
     * explicit user action) when the resolve cannot complete.
     */
    private static void join(BaseFragment fragment) {
        final int account = fragment.getCurrentAccount();
        try {
            TLRPC.TL_contacts_resolveUsername resolve = new TLRPC.TL_contacts_resolveUsername();
            resolve.username = CHANNEL_USERNAME;
            ConnectionsManager.getInstance(account).sendRequest(resolve, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                try {
                    TLRPC.Chat chat = null;
                    if (response instanceof TLRPC.TL_contacts_resolvedPeer) {
                        TLRPC.TL_contacts_resolvedPeer resolved = (TLRPC.TL_contacts_resolvedPeer) response;
                        MessagesController.getInstance(account).putUsers(resolved.users, false);
                        MessagesController.getInstance(account).putChats(resolved.chats, false);
                        for (int a = 0; a < resolved.chats.size(); a++) {
                            TLRPC.Chat c = resolved.chats.get(a);
                            if (c != null && ChatObject.isChannelAndNotMegaGroup(c)) {
                                chat = c;
                            }
                        }
                    }
                    if (chat != null) {
                        TLRPC.TL_channels_joinChannel join = new TLRPC.TL_channels_joinChannel();
                        join.channel = MessagesController.getInputChannel(chat);
                        ConnectionsManager.getInstance(account).sendRequest(join, (joinResponse, joinError) -> AndroidUtilities.runOnUIThread(() -> {
                            try {
                                if (joinError == null) {
                                    prefs().edit().putBoolean("joined", true).apply();
                                    if (joinResponse instanceof TLRPC.Updates) {
                                        MessagesController.getInstance(account).processUpdates((TLRPC.Updates) joinResponse, false);
                                    }
                                    toast(fragment, R.string.LuxGramChannelPromoJoined);
                                } else if ("USER_ALREADY_PARTICIPANT".equals(joinError.text)) {
                                    prefs().edit().putBoolean("joined", true).apply();
                                    toast(fragment, R.string.LuxGramChannelPromoJoined);
                                } else {
                                    toast(fragment, R.string.LuxGramChannelPromoFail);
                                }
                            } catch (Throwable t) {
                                FileLog.e(t);
                            }
                        }), ConnectionsManager.RequestFlagFailOnServerErrors);
                    } else {
                        // resolve gave nothing usable — hand the user the public preview
                        openChannelPreview(fragment);
                    }
                } catch (Throwable t) {
                    FileLog.e(t);
                    openChannelPreview(fragment);
                }
            }), ConnectionsManager.RequestFlagFailOnServerErrors);
        } catch (Throwable t) {
            FileLog.e(t);
            openChannelPreview(fragment);
        }
    }

    /** Last resort: the t.me preview — joining there is still a manual tap. */
    private static void openChannelPreview(BaseFragment fragment) {
        try {
            org.telegram.messenger.browser.Browser.openUrl(fragment.getParentActivity(), CHANNEL_URL);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void toast(BaseFragment fragment, int res) {
        try {
            android.widget.Toast.makeText(fragment.getParentActivity(),
                    LocaleController.getString(res), android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignore) {}
    }

    private static android.content.SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }
}
