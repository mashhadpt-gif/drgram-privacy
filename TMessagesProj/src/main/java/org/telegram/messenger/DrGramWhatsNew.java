/*
 * DrGram Fix 80 — "What's New" popup.
 * The owner wants every update's full details shown to the user as a popup
 * on the FIRST app open after the update ("هر آپدیتی ک لحاظ میشه جزئیات کاملش
 * میخوام پس از اولین باز شدن اپ بعد از آپدیت به صورت پاپ آپ بیاد").
 * Persists the last shown version in prefs; shows once per version; the
 * Tools page row re-opens it on demand. Everything guarded — never crashes.
 */
package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;

import org.telegram.ui.ActionBar.AlertDialog;

public class DrGramWhatsNew {

    private static final String PREFS = "drgram_whatsnew";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    /** Persian changelog body for the given versionName (null = nothing to show). */
    private static String entryFor(String version) {
        if ("5.49".equals(version)) {
            return "• امنیت دستورات هوشمند: اجرا فقط توسط خودتان — حالت «فقط خودم» پیش‌فرض شد و می‌توانید از تنظیمات دستورات هوشمند آن را به «همه» تغییر دهید\n"
                + "• قفل دومرحله‌ای گفتگو: پین + اثر انگشت، با قفل مجدد خودکار هنگام خروج از برنامه\n"
                + "• کارت ویزیت QR زنده در پروفایل\n"
                + "• ویجت‌های صفحه اصلی: چت سریع + رادار مخاطبین آنلاین\n"
                + "• خلاصه‌سازی هوشمند گفتگو با هوش مصنوعی (منوی سه‌نقطه)\n"
                + "• بخش ارور شیدر از ابزارها حذف شد\n"
                + "• تکرار متن، تبدیل اعداد و سوییچ سریع حذف شدند (ریسک ریپورت و کاربرد کم)\n"
                + "• هدایت پیشرفته ساخ شد: حالا همه پیام‌های انتخابی ارسال می‌شوند و پیام‌های متنی بعد از ارسال هم قابل ویرایش می‌مانند (سبک تلگراف)\n"
                + "• ترجمه خودکار گفتگو: از منوی سه‌نقطه هر گفتگو\n"
                + "• بسته برنامه به com.luxgram.messenger تغییر کرد — نصب این نسخه به‌صورت نصب تازه انجام می‌شود و سشن قبلی منتقل نمی‌شود";
        }
        if ("5.48".equals(version)) {
            return "• بخش جدید «ابزارهای سریع»: سوییچ زبان، تکرار متن، تبدیل اعداد، قرعه‌کشی، قفل اسکرین‌شات و کارت شناسایی\n"
                + "• قفل اسکرین‌شات: جلوگیری از اسکرین‌شات و ضبط صفحه در کل برنامه";
        }
        return null;
    }

    /** Called from LaunchActivity — shows the popup once per new version. */
    public static void showIfNew(Activity activity) {
        try {
            if (activity == null || activity.isFinishing()) {
                return;
            }
            String version = BuildVars.BUILD_VERSION_STRING;
            if (version == null || version.length() == 0) {
                return;
            }
            String seen = prefs().getString("seen", null);
            if (version.equals(seen)) {
                return; // already shown for this version
            }
            String body = entryFor(version);
            prefs().edit().putString("seen", version).apply();
            if (body == null) {
                return;
            }
            present(activity, version, body);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** Tools row — always show the current version's changelog. */
    public static void show(Activity activity) {
        try {
            if (activity == null || activity.isFinishing()) {
                return;
            }
            String version = BuildVars.BUILD_VERSION_STRING;
            String body = entryFor(version);
            if (body == null) {
                body = "در این نسخه تغییرات جزئی و رفع اشکال انجام شده است.";
            }
            present(activity, version, body);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void present(Activity activity, String version, String body) {
        AlertDialog dialog = new AlertDialog(activity, AlertDialog.ALERT_TYPE_MESSAGE);
        dialog.setTitle(LocaleController.getString(R.string.DrGramWhatsNewTitle) + " — v" + version);
        dialog.setMessage(body);
        dialog.setPositiveButton(LocaleController.getString(R.string.DrGramWhatsNewGotIt), null);
        dialog.show();
    }
}
