/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Zabanavis (زیانویس — Fix 38): message annotation with beautiful,
 * Persian-safe styles. The old-school "fancy font" tricks use Unicode
 * combining strikethrough characters (U+0335/U+0336) which render as broken
 * double lines over RTL glyphs, and Mathematical Alphanumeric blocks which
 * have no Persian mapping at all. This implementation avoids both:
 *
 *   • entity styles  → Telegram-native formatting (bold/italic/strike/
 *     underline/code/spoiler) — shaped perfectly by the platform text stack
 *   • ornament styles → decorative glyph frames (✦ ﴿ ﴾ ★ ✿ ❥ ⋆ ┊ ılılı)
 *     wrapped around the untouched original text
 *
 * Own messages are edited in place; anyone else's message is transformed to
 * the clipboard so it can be restyled-forwarded.
 */
package org.telegram.messenger;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;

public class DrGramZabanavis {

    public static final int STYLE_NORMAL = 0;      // حالت معمولی — strip entities
    public static final int STYLE_BOLD = 1;        // متن برجسته
    public static final int STYLE_ITALIC = 2;      // متن کج
    public static final int STYLE_STRIKE = 3;      // متن خط‌خورده
    public static final int STYLE_UNDERLINE = 4;   // متن زیرخط
    public static final int STYLE_ORN_1 = 5;       // ✦ متن ✦
    public static final int STYLE_ORN_2 = 6;       // ﴿ متن ﴾
    public static final int STYLE_ORN_3 = 7;       // ★·.·´¯`·.·★
    public static final int STYLE_ORN_4 = 8;       // ✿ متن ✿
    public static final int STYLE_BOLD_ITALIC = 9; // برجسته + کج
    public static final int STYLE_BOLD_UNDER = 10; // برجسته + زیرخط
    public static final int STYLE_MONO = 11;       // کد / مونو
    public static final int STYLE_STRIKE_BOLD = 12;// خط‌خورده + برجسته
    public static final int STYLE_SPOILER = 13;    // محو

    private static final String ORN_1_A = "✦ ", ORN_1_B = " ✦";
    private static final String ORN_2_A = "﴿ ", ORN_2_B = " ﴾";
    private static final String ORN_3 = "★·.·´¯`·.·★ ";
    private static final String ORN_4_A = "✿ ", ORN_4_B = " ✿";

    private static int[] styleIds() {
        return new int[]{
                STYLE_NORMAL, STYLE_BOLD, STYLE_ITALIC, STYLE_STRIKE, STYLE_UNDERLINE,
                STYLE_ORN_1, STYLE_ORN_2, STYLE_ORN_3, STYLE_ORN_4,
                STYLE_BOLD_ITALIC, STYLE_BOLD_UNDER, STYLE_MONO, STYLE_STRIKE_BOLD, STYLE_SPOILER
        };
    }

    public static String[] styleNames() {
        int[] ids = styleIds();
        String[] names = new String[ids.length];
        for (int i = 0; i < ids.length; i++) {
            names[i] = styleName(ids[i]);
        }
        return names;
    }

    private static String styleName(int style) {
        switch (style) {
            case STYLE_NORMAL: return LocaleController.getString(R.string.DrGramZabNormal);
            case STYLE_BOLD: return LocaleController.getString(R.string.DrGramZabBold);
            case STYLE_ITALIC: return LocaleController.getString(R.string.DrGramZabItalic);
            case STYLE_STRIKE: return LocaleController.getString(R.string.DrGramZabStrike);
            case STYLE_UNDERLINE: return LocaleController.getString(R.string.DrGramZabUnderline);
            case STYLE_ORN_1: return LocaleController.getString(R.string.DrGramZabOne);
            case STYLE_ORN_2: return LocaleController.getString(R.string.DrGramZabTwo);
            case STYLE_ORN_3: return LocaleController.getString(R.string.DrGramZabThree);
            case STYLE_ORN_4: return LocaleController.getString(R.string.DrGramZabFour);
            case STYLE_BOLD_ITALIC: return LocaleController.getString(R.string.DrGramZabFive);
            case STYLE_BOLD_UNDER: return LocaleController.getString(R.string.DrGramZabSix);
            case STYLE_MONO: return LocaleController.getString(R.string.DrGramZabSeven);
            case STYLE_STRIKE_BOLD: return LocaleController.getString(R.string.DrGramZabEight);
            case STYLE_SPOILER: return LocaleController.getString(R.string.DrGramZabNine);
            default: return LocaleController.getString(R.string.DrGramZabNormal);
        }
    }

    /** Shows the style picker — every row renders a LIVE PREVIEW of the style. */
    public static void showPicker(BaseFragment fragment, final int styleInUse, final Listener listener) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        Context ctx = fragment.getParentActivity();
        String[] names = styleNames();
        final int[] ids = styleIds();

        // Fix 70: the old single-RadioButton rows concatenated the name line and
        // the preview with "+", which silently dropped BOTH the small/gray spans
        // of the name AND made every row look like two identical big lines —
        // users could not tell the styles apart. Each row is now a real two-line
        // layout: a small gray label above a live, unmistakable preview.
        android.widget.ScrollView scroll = new android.widget.ScrollView(ctx);
        android.widget.LinearLayout list = new android.widget.LinearLayout(ctx);
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        scroll.addView(list);
        final int[] pickedHolder = {-1};
        int accent = accentColor();
        final java.util.ArrayList<android.widget.RadioButton> radios = new java.util.ArrayList<>();
        for (int i = 0; i < ids.length; i++) {
            final int style = ids[i];

            android.widget.LinearLayout row = new android.widget.LinearLayout(ctx);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(9), AndroidUtilities.dp(16), AndroidUtilities.dp(9));
            row.setBackground(Theme.getSelectorDrawable(false));

            android.widget.RadioButton rb = new android.widget.RadioButton(ctx);
            rb.setChecked(ids[i] == styleInUse);
            rb.setHighlightColor(0x00000000);
            try {
                android.content.res.ColorStateList csl = new android.content.res.ColorStateList(
                        new int[][]{{android.R.attr.state_checked}, {}},
                        new int[]{accent, 0x66888888});
                rb.setButtonTintList(csl);
            } catch (Throwable ignore) {}
            row.addView(rb, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));

            android.widget.LinearLayout col = new android.widget.LinearLayout(ctx);
            col.setOrientation(android.widget.LinearLayout.VERTICAL);

            android.widget.TextView nameView = new android.widget.TextView(ctx);
            nameView.setText(names[i]);
            nameView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
            nameView.setTextColor(nameColor(fragment.getResourceProvider()));
            nameView.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            col.addView(nameView);

            android.widget.TextView previewView = new android.widget.TextView(ctx);
            previewView.setText(previewText(style, fragment.getResourceProvider()));
            previewView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 17);
            previewView.setTextColor(textColor(fragment.getResourceProvider()));
            previewView.setLineSpacing(AndroidUtilities.dp(1), 1f);
            col.addView(previewView);

            row.addView(col, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            radios.add(rb);
            row.setOnClickListener(v -> {
                DrGramHaptics.tick(v);
                for (int r = 0; r < radios.size(); r++) {
                    radios.get(r).setChecked(radios.get(r) == rb);
                }
                pickedHolder[0] = style;
            });
            list.addView(row, new android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        AlertDialog dialog = new AlertDialog.Builder(ctx, fragment.getResourceProvider())
                .setTitle(LocaleController.getString(R.string.DrGramZabanavisTitle))
                .setView(scroll)
                .setPositiveButton(LocaleController.getString(R.string.OK), (d, w) -> {
                    if (listener != null && pickedHolder[0] >= 0) {
                        listener.onStylePicked(pickedHolder[0]);
                    }
                })
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .create();
        dialog.show();
    }

    /** Fix 70: small gray label color for the style name. */
    private static int nameColor(Theme.ResourcesProvider provider) {
        try {
            return Theme.getColor(Theme.key_dialogTextGray2, provider);
        } catch (Throwable ignore) {}
        return 0xFF8A8A8A;
    }

    /** Fix 70: strong text color for the preview line. */
    private static int textColor(Theme.ResourcesProvider provider) {
        try {
            return Theme.getColor(Theme.key_dialogTextBlack, provider);
        } catch (Throwable ignore) {}
        return 0xFF222222;
    }

    /** Fix 70: bulletproof visual preview — no span-dropping concatenation. */
    private static CharSequence previewText(int style, Theme.ResourcesProvider provider) {
        String sample = LocaleController.getString(R.string.DrGramZabSample);
        int accent = accentColor();
        android.text.SpannableString sp;
        switch (style) {
            case STYLE_BOLD:
                sp = wrap(sample, new android.text.style.StyleSpan(android.graphics.Typeface.BOLD));
                break;
            case STYLE_ITALIC:
                // Persian has no true italic face — force a visible slant.
                sp = wrap(sample, new SkewSpan());
                break;
            case STYLE_STRIKE:
                sp = wrap(sample, new android.text.style.StrikethroughSpan());
                break;
            case STYLE_UNDERLINE:
                sp = wrap(sample, new android.text.style.UnderlineSpan());
                break;
            case STYLE_MONO:
                sp = wrap(sample, new android.text.style.TypefaceSpan("monospace"));
                sp.setSpan(new android.text.style.BackgroundColorSpan(0x33888888), 0, sp.length(), 0);
                break;
            case STYLE_BOLD_ITALIC:
                sp = wrap(wrap(sample, new android.text.style.StyleSpan(android.graphics.Typeface.BOLD)), new SkewSpan());
                break;
            case STYLE_BOLD_UNDER:
                sp = wrap(wrap(sample, new android.text.style.StyleSpan(android.graphics.Typeface.BOLD)), new android.text.style.UnderlineSpan());
                break;
            case STYLE_STRIKE_BOLD:
                sp = wrap(wrap(sample, new android.text.style.StyleSpan(android.graphics.Typeface.BOLD)), new android.text.style.StrikethroughSpan());
                break;
            case STYLE_SPOILER:
                sp = new android.text.SpannableString("\u25CF\u25CF\u25CF\u25CF\u25CF\u25CF\u25CF\u25CF\u25CF");
                sp.setSpan(new android.text.style.ForegroundColorSpan(0xFF8A8A8A), 0, sp.length(), 0);
                break;
            case STYLE_ORN_1:
                sp = ornamentPreview(ORN_1_A, ORN_1_B, sample, accent);
                break;
            case STYLE_ORN_2:
                sp = ornamentPreview(ORN_2_A, ORN_2_B, sample, accent);
                break;
            case STYLE_ORN_3:
                sp = new android.text.SpannableString(ORN_3 + sample + "\n" + ORN_3);
                sp.setSpan(new android.text.style.ForegroundColorSpan(accent), 0, ORN_3.length(), 0);
                sp.setSpan(new android.text.style.ForegroundColorSpan(accent), sp.length() - ORN_3.length(), sp.length(), 0);
                break;
            case STYLE_ORN_4:
                sp = ornamentPreview(ORN_4_A, ORN_4_B, sample, accent);
                break;
            default:
                sp = android.text.SpannableString.valueOf(sample);
                break;
        }
        return sp;
    }

    /** Fix 70: ornament style preview with the golden ornaments colored. */
    private static android.text.SpannableString ornamentPreview(String a, String b, String sample, int accent) {
        android.text.SpannableString sp = new android.text.SpannableString(a + sample + b);
        sp.setSpan(new android.text.style.ForegroundColorSpan(accent), 0, a.length(), 0);
        sp.setSpan(new android.text.style.ForegroundColorSpan(accent), sp.length() - b.length(), sp.length(), 0);
        return sp;
    }

    /** Fix 70: forces a real slant so the italic style is visible for Persian. */
    private static class SkewSpan extends android.text.style.MetricAffectingSpan {
        @Override
        public void updateMeasureState(android.text.TextPaint p) {
            p.setTextSkewX(-0.25f);
        }

        @Override
        public void updateDrawState(android.text.TextPaint p) {
            p.setTextSkewX(-0.25f);
        }
    }

    private static android.text.SpannableString wrap(CharSequence text, Object span) {
        android.text.SpannableString s = android.text.SpannableString.valueOf(text);
        s.setSpan(span, 0, s.length(), 0);
        return s;
    }

    public interface Listener {
        void onStylePicked(int style);
    }

    /**
     * Applies a style to the raw message text.
     *
     * @param raw      the plain message text (messageOwner.message)
     * @param entities the message's current entities (may be null)
     * @return a Result with the new text and the replacement entity list
     *         (null entities = send as plain text)
     */
    public static Result apply(int style, String raw, ArrayList<TLRPC.MessageEntity> entities) {
        Result r = new Result();
        if (raw == null) {
            raw = "";
        }
        String text = raw;
        ArrayList<TLRPC.MessageEntity> out = null;
        switch (style) {
            case STYLE_NORMAL:
                out = null; // plain text, no entities
                break;
            case STYLE_BOLD:
                out = single(new TLRPC.TL_messageEntityBold(), text.length());
                break;
            case STYLE_ITALIC:
                out = single(new TLRPC.TL_messageEntityItalic(), text.length());
                break;
            case STYLE_STRIKE:
                out = single(new TLRPC.TL_messageEntityStrike(), text.length());
                break;
            case STYLE_UNDERLINE:
                out = single(new TLRPC.TL_messageEntityUnderline(), text.length());
                break;
            case STYLE_MONO:
                out = single(new TLRPC.TL_messageEntityCode(), text.length());
                break;
            case STYLE_SPOILER:
                out = single(new TLRPC.TL_messageEntitySpoiler(), text.length());
                break;
            case STYLE_BOLD_ITALIC:
                out = pair(new TLRPC.TL_messageEntityBold(), new TLRPC.TL_messageEntityItalic(), text.length());
                break;
            case STYLE_BOLD_UNDER:
                out = pair(new TLRPC.TL_messageEntityBold(), new TLRPC.TL_messageEntityUnderline(), text.length());
                break;
            case STYLE_STRIKE_BOLD:
                out = pair(new TLRPC.TL_messageEntityStrike(), new TLRPC.TL_messageEntityBold(), text.length());
                break;
            case STYLE_ORN_1:
                text = ORN_1_A + raw.trim() + ORN_1_B;
                break;
            case STYLE_ORN_2:
                text = ORN_2_A + raw.trim() + ORN_2_B;
                break;
            case STYLE_ORN_3:
                text = ORN_3 + raw.trim() + "\n" + ORN_3;
                break;
            case STYLE_ORN_4:
                text = ORN_4_A + raw.trim() + ORN_4_B;
                break;
            default:
                break;
        }
        r.text = text;
        r.entities = out;
        return r;
    }

    public static class Result {
        public String text;
        public ArrayList<TLRPC.MessageEntity> entities; // null = plain
    }

    private static ArrayList<TLRPC.MessageEntity> single(TLRPC.MessageEntity e, int len) {
        e.offset = 0;
        e.length = Math.max(0, len);
        ArrayList<TLRPC.MessageEntity> list = new ArrayList<>();
        list.add(e);
        return list;
    }

    private static ArrayList<TLRPC.MessageEntity> pair(TLRPC.MessageEntity a, TLRPC.MessageEntity b, int len) {
        a.offset = 0;
        a.length = Math.max(0, len);
        b.offset = 0;
        b.length = Math.max(0, len);
        ArrayList<TLRPC.MessageEntity> list = new ArrayList<>();
        list.add(a);
        list.add(b);
        return list;
    }

    /**
     * Convenience: transform + copy a foreign message so it can be restyled-forwarded.
     * Returns the transformed text, or null when clipboard is unavailable.
     */
    public static String copyStyled(Context ctx, int style, String raw, ArrayList<TLRPC.MessageEntity> entities) {
        Result r = apply(style, raw, entities);
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("message", r.text));
            return r.text;
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    /** Theme-aware accent for the picker preview chips, kept simple. */
    public static int accentColor() {
        return Theme.getColor(Theme.key_chats_actionBackground);
    }
}
