/*
 * This is the source code of LuxGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * LuxGram Account Safety (Fix 64) — a purely local self-audit that surfaces
 * everything in this client which can influence Telegram's anti-abuse
 * decisions about the logged-in account. It makes NO network requests and
 * collects NO data: every finding is derived from build constants and local
 * preferences, so the card can never itself become a tracking surface.
 *
 * Findings modelled here:
 *   1. Credential pair — running a public/leaked api_id pair is the single
 *      strongest client-detection ban signal; warn until a LuxGram-registered
 *      pair ships (BuildVars.isLeakedCredentialPair()).
 *   2. Secretary auto-responder — bounded since Fix 64 (10/hour, 40/day),
 *      so a burst of incoming chats can no longer produce an automated
 *      flood; still reported as info when the feature is on.
 *   3. Smart Reply — command-triggered only, nothing fires on its own.
 *   4. Anti-Delete — passive local cache, zero extra requests; hard-disabled
 *      in Play-distribution builds.
 *   5. Client identity — honest device/version/lang reporting, no spoofing.
 *   6. Bulk tools — the fork ships no mass-forward / member-scrape /
 *      invite-bombing features at all.
 */
package org.telegram.messenger;

import java.util.ArrayList;
import java.util.List;

public final class LuxGramAccountSafety {

    public static final int LEVEL_OK = 0;
    public static final int LEVEL_INFO = 1;
    public static final int LEVEL_WARN = 2;

    public static class Item {
        public final int level;
        public final String title;
        public final String detail;

        Item(int level, String title, String detail) {
            this.level = level;
            this.title = title;
            this.detail = detail;
        }
    }

    private LuxGramAccountSafety() {
    }

    /**
     * Builds the current finding list for the selected account. Never throws.
     */
    public static List<Item> collect(int account) {
        List<Item> items = new ArrayList<>();
        try {
            // 1. credential pair — the decisive one
            if (BuildVars.isLeakedCredentialPair()) {
                items.add(new Item(LEVEL_WARN,
                        LocaleController.getString(R.string.LuxGramSafetyCredTitle),
                        LocaleController.getString(R.string.LuxGramSafetyCredWarn)));
            } else {
                items.add(new Item(LEVEL_OK,
                        LocaleController.getString(R.string.LuxGramSafetyCredTitle),
                        LocaleController.getString(R.string.LuxGramSafetyCredOk)));
            }

            // 2. secretary — flood-capped since Fix 64
            LuxGramSecretary secretary = LuxGramSecretary.getInstance(account);
            if (secretary.isEnabled()) {
                items.add(new Item(LEVEL_INFO,
                        LocaleController.getString(R.string.LuxGramSafetySecretaryTitle),
                        LocaleController.formatString(R.string.LuxGramSafetySecretaryOn,
                                secretary.getRepliesThisHour(), secretary.getRepliesToday())));
            } else {
                items.add(new Item(LEVEL_OK,
                        LocaleController.getString(R.string.LuxGramSafetySecretaryTitle),
                        LocaleController.getString(R.string.LuxGramSafetySecretaryOff)));
            }

            // 3. smart reply — command-triggered only
            items.add(new Item(LEVEL_OK,
                    LocaleController.getString(R.string.LuxGramSafetySmartReplyTitle),
                    LocaleController.getString(R.string.LuxGramSafetySmartReplyDetail)));

            // 4. anti-delete — passive, and off in Play builds
            if (LuxGramAntiDelete.featureAvailable()) {
                items.add(new Item(LEVEL_INFO,
                        LocaleController.getString(R.string.LuxGramSafetyAntiDeleteTitle),
                        LocaleController.getString(R.string.LuxGramSafetyAntiDeleteDetail)));
            } else {
                items.add(new Item(LEVEL_OK,
                        LocaleController.getString(R.string.LuxGramSafetyAntiDeleteTitle),
                        LocaleController.getString(R.string.LuxGramSafetyAntiDeleteOff)));
            }

            // 5. honest client identity
            items.add(new Item(LEVEL_OK,
                    LocaleController.getString(R.string.LuxGramSafetyIdentityTitle),
                    LocaleController.getString(R.string.LuxGramSafetyIdentityDetail)));

            // 6. no bulk/spam tooling shipped
            items.add(new Item(LEVEL_OK,
                    LocaleController.getString(R.string.LuxGramSafetyBulkTitle),
                    LocaleController.getString(R.string.LuxGramSafetyBulkDetail)));
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return items;
    }

    /** Worst level across the list — drives the Tools row subtitle. */
    public static int worstLevel(List<Item> items) {
        int worst = LEVEL_OK;
        for (int i = 0; i < items.size(); i++) {
            worst = Math.max(worst, items.get(i).level);
        }
        return worst;
    }
}
