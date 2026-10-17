/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Secretary — offline auto-responder.
 * Watches incoming private messages and answers them with a user-defined
 * text after a per-chat cooldown, so the user never leaves a contact
 * hanging while busy. Groups and broadcast channels are ignored by
 * default; bots and service messages are always ignored.
 *
 * DrGram Fix 64 (account safety): a burst of incoming dialogs used to
 * produce an unbounded flood of automated sends, which is exactly the
 * behaviour Telegram's anti-spam flags accounts for. The secretary is now
 * globally rate-capped (persisted rolling windows): at most 10 auto-replies
 * per hour and 40 per day across ALL dialogs combined. When a cap is hit
 * the feature silently goes quiet until the window rolls over — the user
 * can still answer manually, only the robot stops.
 */
package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;

public class DrGramSecretary {

    private static final long REPLY_COOLDOWN_MS = 5 * 60 * 1000L;

    // DrGram Fix 64: global anti-flood caps (account-safety hardening)
    private static final int MAX_REPLIES_PER_HOUR = 10;
    private static final int MAX_REPLIES_PER_DAY = 40;
    private static final String KEY_HOUR_BUCKET = "safety_hour_bucket";
    private static final String KEY_HOUR_COUNT = "safety_hour_count";
    private static final String KEY_DAY_BUCKET = "safety_day_bucket";
    private static final String KEY_DAY_COUNT = "safety_day_count";

    private static volatile DrGramSecretary[] instances = new DrGramSecretary[UserConfig.MAX_ACCOUNT_COUNT];

    private final int currentAccount;
    private final SharedPreferences preferences;
    private final HashMap<Long, Long> lastReplyTimes = new HashMap<>();

    public static DrGramSecretary getInstance(int account) {
        DrGramSecretary instance = instances[account];
        if (instance == null) {
            synchronized (DrGramSecretary.class) {
                instance = instances[account];
                if (instance == null) {
                    instance = new DrGramSecretary(account);
                    instances[account] = instance;
                }
            }
        }
        return instance;
    }

    private DrGramSecretary(int account) {
        currentAccount = account;
        String prefsName = account == 0 ? "drgram_secretary" : "drgram_secretary_" + account;
        preferences = ApplicationLoader.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
    }

    //region settings

    public boolean isEnabled() {
        return preferences.getBoolean("enabled", false);
    }

    public void setEnabled(boolean value) {
        preferences.edit().putBoolean("enabled", value).apply();
    }

    public String getReplyText() {
        return preferences.getString("reply_text", "");
    }

    public void setReplyText(String text) {
        preferences.edit().putString("reply_text", text == null ? "" : text.trim()).apply();
    }

    public boolean isIncludeGroups() {
        return preferences.getBoolean("include_groups", false);
    }

    public void setIncludeGroups(boolean value) {
        preferences.edit().putBoolean("include_groups", value).apply();
    }

    // DrGram Fix 64: rolling-window counters for the global caps
    private static long hourBucket() {
        return System.currentTimeMillis() / (60 * 60 * 1000L);
    }

    private static long dayBucket() {
        return System.currentTimeMillis() / (24 * 60 * 60 * 1000L);
    }

    /** Auto-replies already sent in the current clock hour (all dialogs). */
    public int getRepliesThisHour() {
        long bucket = hourBucket();
        return preferences.getLong(KEY_HOUR_BUCKET, -1) == bucket
                ? (int) preferences.getLong(KEY_HOUR_COUNT, 0) : 0;
    }

    /** Auto-replies already sent today (all dialogs). */
    public int getRepliesToday() {
        long bucket = dayBucket();
        return preferences.getLong(KEY_DAY_BUCKET, -1) == bucket
                ? (int) preferences.getLong(KEY_DAY_COUNT, 0) : 0;
    }

    /** True when the per-hour or per-day global cap still has room. */
    public boolean hasGlobalQuota() {
        return getRepliesThisHour() < MAX_REPLIES_PER_HOUR
                && getRepliesToday() < MAX_REPLIES_PER_DAY;
    }

    private void chargeQuota() {
        long hour = hourBucket();
        long day = dayBucket();
        long storedHour = preferences.getLong(KEY_HOUR_BUCKET, -1);
        long storedDay = preferences.getLong(KEY_DAY_BUCKET, -1);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putLong(KEY_HOUR_BUCKET, hour);
        editor.putLong(KEY_HOUR_COUNT, storedHour == hour ? preferences.getLong(KEY_HOUR_COUNT, 0) + 1 : 1);
        editor.putLong(KEY_DAY_BUCKET, day);
        editor.putLong(KEY_DAY_COUNT, storedDay == day ? preferences.getLong(KEY_DAY_COUNT, 0) + 1 : 1);
        editor.apply();
    }

    //endregion

    /**
     * Called from NotificationsController.processNewMessages for every batch
     * of freshly delivered messages. Never throws into the caller.
     */
    public void onNewMessages(ArrayList<MessageObject> messageObjects) {
        if (!isEnabled() || messageObjects == null || messageObjects.isEmpty()) {
            return;
        }
        String replyText = getReplyText();
        if (replyText.length() == 0) {
            return;
        }
        for (int i = 0; i < messageObjects.size(); i++) {
            try {
                considerReply(messageObjects.get(i), replyText);
            } catch (Throwable ignore) {
            }
        }
    }

    private void considerReply(MessageObject messageObject, String replyText) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return;
        }
        if (messageObject.isOutOwner() || messageObject.isOut()) {
            return;
        }
        if (messageObject.messageOwner.action != null) {
            return;
        }
        long dialogId = messageObject.getDialogId();
        if (dialogId == 0) {
            return;
        }

        MessagesController messagesController = MessagesController.getInstance(currentAccount);
        TLRPC.User fromUser = messagesController.getUser(messageObject.getSenderId());
        if (fromUser != null && (fromUser.bot || fromUser.id == UserConfig.getInstance(currentAccount).clientUserId)) {
            return;
        }

        if (dialogId < 0) {
            if (!isIncludeGroups()) {
                return;
            }
            if (ChatObject.isChannelAndNotMegaGroup(-dialogId, currentAccount)) {
                return;
            }
        }

        long now = System.currentTimeMillis();
        Long lastReplyTime = lastReplyTimes.get(dialogId);
        if (lastReplyTime != null && now - lastReplyTime < REPLY_COOLDOWN_MS) {
            return;
        }
        // DrGram Fix 64: global anti-flood gate — before ANY automated send.
        // The per-chat timestamp is NOT consumed while quota is exhausted, so
        // the very first in-window dialog still gets answered after rollover.
        if (!hasGlobalQuota()) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("DrGramSecretary: global cap reached, staying quiet");
            }
            return;
        }
        lastReplyTimes.put(dialogId, now);

        chargeQuota();
        SendMessagesHelper.getInstance(currentAccount)
                .sendMessage(SendMessagesHelper.SendMessageParams.of(replyText, dialogId));
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("DrGramSecretary: auto-replied to dialog " + dialogId);
        }
    }
}
