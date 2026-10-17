/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2020.
 */

package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.android.billingclient.api.ProductDetails;

import java.util.Objects;

public class BuildVars {

    public static boolean DEBUG_VERSION = BuildConfig.DEBUG_VERSION;
    public static boolean LOGS_ENABLED = BuildConfig.DEBUG_VERSION;
    public static boolean DEBUG_PRIVATE_VERSION = BuildConfig.DEBUG_PRIVATE_VERSION;
    // DrGram Fix 10: cloud string packs are the OFFICIAL Telegram strings and would
    // overwrite the DrGram rebrand after a language change or string refresh
    // DrGram Fix 13: cloud language packs are SAFE to enable now —
    // LocaleController.getStringV2() resolves brand-protected keys from the
    // packaged resources only (see isBrandProtectedKey), so the full official
    // language list (incl. a complete Persian pack) works without ever
    // re-introducing Telegram branding.
    public static boolean USE_CLOUD_STRINGS = true;
    // DrGram Fix 10: never prompt/check updates pointing at the official Telegram app
    public static boolean CHECK_UPDATES = false;
    public static boolean NO_SCOPED_STORAGE = Build.VERSION.SDK_INT <= 29;
    public static String BUILD_VERSION_STRING = BuildConfig.BUILD_VERSION_STRING;

    // DrGram Fix 64 — ACCOUNT-SAFETY CRITICAL ================================
    // This pair (2040 / b18441a1…) is Telegram DESKTOP's official public
    // credential. Running an Android fork on a public/leaked pair is the
    // #1 trigger of Telegram's anti-abuse ban waves: the server sees Android
    // deviceModel/appVersion patterns on a desktop api_id and flags every
    // account that logs in from such a client (historically the Persian-mod
    // mass "account deleted" events). The ONLY durable fix is a dedicated
    // api_id registered for DrGram:
    //   1. my.telegram.org → API development tools → create an app
    //   2. put the new api_id into APP_ID and the hash into APP_HASH
    //   3. flip CREDENTIAL_IS_PUBLIC_PLACEHOLDER to false
    // Until then isLeakedCredentialPair() drives the in-app Tools warning.
    public static int APP_ID = 2040; // TODO(DrGram): replace with the registered DrGram api_id

    public static String APP_HASH = "b18441a1ff607e10a989891a5462e627"; // TODO(DrGram): replace with the registered DrGram api_hash

    /** DrGram Fix 64: true while the shipped pair is the known-public placeholder. */
    public static boolean CREDENTIAL_IS_PUBLIC_PLACEHOLDER = true;

    /**
     * DrGram Fix 64: honest self-knowledge for the Tools → Account Safety card.
     * Purely local (no network): true iff the app still runs on the known
     * public pair. Accounts logging in from this state carry the highest
     * client-detection ban risk and the UI says so.
     */
    public static boolean isLeakedCredentialPair() {
        return CREDENTIAL_IS_PUBLIC_PLACEHOLDER
                && APP_ID == 2040
                && "b18441a1ff607e10a989891a5462e627".equals(APP_HASH);
    }
    // ======================== end DrGram Fix 64 block =======================

    // DrGram Fix 10: SafetyNet DISABLED. Its Google attestation is bound to the
    // official Telegram package+signature and ALWAYS fails for forks, which triggered
    // the "Google Play security error" dialog loop during login. Empty key makes the
    // login flow request a plain SMS/call code with no attestation at all.
    public static String SAFETYNET_KEY = "";
    public static String PLAYSTORE_APP_URL = "https://play.google.com/store/apps/details?id=org.telegram.messenger";
    public static String HUAWEI_STORE_URL = "https://appgallery.huawei.com/app/C101184875";
    public static String GOOGLE_AUTH_CLIENT_ID = "760348033671-81kmi3pi84p11ub8hp9a1funsv0rn2p9.apps.googleusercontent.com";

    public static String HUAWEI_APP_ID = "101184875";

    // You can use this flag to disable Google Play Billing (If you're making fork and want it to be in Google Play)
    public static boolean IS_BILLING_UNAVAILABLE = false;

    // DrGram Fix 10: works only on official app ids, upstream comment says disable on forks
    public static boolean SUPPORTS_PASSKEYS = false;

    static {
        if (ApplicationLoader.applicationContext != null) {
            SharedPreferences sharedPreferences = ApplicationLoader.applicationContext.getSharedPreferences("systemConfig", Context.MODE_PRIVATE);
            LOGS_ENABLED = DEBUG_VERSION || sharedPreferences.getBoolean("logsEnabled", DEBUG_VERSION);
            if (LOGS_ENABLED) {
                final Thread.UncaughtExceptionHandler pastHandler = Thread.getDefaultUncaughtExceptionHandler();
                Thread.setDefaultUncaughtExceptionHandler((thread, exception) -> {
                    FileLog.fatal(exception, false);
                    if (pastHandler != null) {
                        pastHandler.uncaughtException(thread, exception);
                    }
                });
            }
        }
    }

    public static boolean useInvoiceBilling() {
        return BillingController.billingClientEmpty || DEBUG_VERSION && false || ApplicationLoader.isStandaloneBuild() || isBetaApp() && false || isHuaweiStoreApp() || hasDirectCurrency();
    }

    private static boolean hasDirectCurrency() {
        if (!BillingController.getInstance().isReady() || BillingController.PREMIUM_PRODUCT_DETAILS == null) {
            return false;
        }
        for (ProductDetails.SubscriptionOfferDetails offerDetails : BillingController.PREMIUM_PRODUCT_DETAILS.getSubscriptionOfferDetails()) {
            for (ProductDetails.PricingPhase phase : offerDetails.getPricingPhases().getPricingPhaseList()) {
                for (String cur : MessagesController.getInstance(UserConfig.selectedAccount).directPaymentsCurrency) {
                    if (Objects.equals(phase.getPriceCurrencyCode(), cur)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Boolean betaApp;
    public static boolean isBetaApp() {
        if (betaApp == null) {
            betaApp = ApplicationLoader.applicationContext != null && "org.telegram.messenger.beta".equals(ApplicationLoader.applicationContext.getPackageName());
        }
        return betaApp;
    }


    public static boolean isHuaweiStoreApp() {
        return ApplicationLoader.isHuaweiStoreBuild();
    }

    public static String getSmsHash() {
        return ApplicationLoader.isStandaloneBuild() ? "w0lkcmTZkKh" : (DEBUG_VERSION ? "O2P2z+/jBpJ" : "oLeq9AcOZkT");
    }
}
