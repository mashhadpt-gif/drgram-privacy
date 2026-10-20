/*
 * This is the source code of LuxGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * LuxGram Fix 32: LuxGramStyleGalleryActivity — the "style gallery".
 * Replaces the plain ThemeActivity shortcut with a real LuxGram gallery:
 *   - LuxGram signature themes: the two branded palettes as large cards with
 *     an Active badge; tapping returns the brand look (and leaves free mode).
 *   - Curated collection: elegant community themes bundled with the app
 *     (AMOLED Night, Indigo, Soft Dark, Sky Blue, Dracula). Each card renders
 *     a mini chat preview from the theme's real palette, parsed at runtime
 *     from the bundled .attheme asset. Applying one switches the app into
 *     "free theme" mode; the brand self-healing respects that choice.
 * Design language follows the luxury control center (dark gradient, gold
 * cards, ornament divider). No framework scrollbars — Fix 31 lesson.
 */
package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LuxGramThemeInstaller;
import org.telegram.messenger.LuxGramThemeTuner;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LuxGramGlassKit;
import org.telegram.ui.Components.LuxGramLuxDecor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Switch;
import org.telegram.ui.LauncherIconController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LuxGramStyleGalleryActivity extends BaseFragment {

    private static final String[][] CURATED = {
            // asset, internal theme name, localization key, fallback bg, fallback accent
            {"luxgram_amoled.attheme", "LuxGram AMOLED", "LuxGramThemeNameAmoled", "0xFF101114", "0xFF4A9CD8"},
            {"luxgram_indigo.attheme", "LuxGram Indigo", "LuxGramThemeNameIndigo", "0xFFFAFAFA", "0xFF3F51B5"},
            {"luxgram_softdark.attheme", "LuxGram Soft Dark", "LuxGramThemeNameSoftDark", "0xFF222B35", "0xFF54677D"},
            {"luxgram_skyblue.attheme", "LuxGram Sky Blue", "LuxGramThemeNameSkyBlue", "0xFF1D2C3B", "0xFF4E7A9E"},
            {"luxgram_dracula.attheme", "LuxGram Dracula", "LuxGramThemeNameDracula", "0xFF282A36", "0xFFBD93F9"},
    };

    private LinearLayout contentView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.LuxGramStyleGallery));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        contentView = new LinearLayout(context);
        contentView.setOrientation(LinearLayout.VERTICAL);
        contentView.setPadding(0, 0, 0, AndroidUtilities.dp(28));
        contentView.setBackground(LuxGramGlassKit.pageGradient()); // LuxGram Fix 36: glass canvas

        // Fix 31 lesson: never setScrollbarFadingEnabled on custom screens; the
        // framework scrollbars stay disabled so the page is draw-proof.
        android.widget.ScrollView scroller = new android.widget.ScrollView(context);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(contentView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fragmentView = scroller;

        try {
            TextView hero = new TextView(context);
            hero.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            hero.setTextColor(0xFFFFC94A);
            hero.setGravity(Gravity.CENTER);
            hero.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(22), AndroidUtilities.dp(24), AndroidUtilities.dp(6));
            hero.setText(LocaleController.getString(R.string.LuxGramGalleryBrandHint));
            contentView.addView(hero);
            contentView.addView(LuxGramLuxDecor.ornamentDivider(context, LuxGramThemeInstaller.isLoveMode()));
        } catch (Throwable t) {
            FileLog.e(t);
        }

        buildBrandSection(context);
        buildCuratedSection(context);
        buildBackToBrandRow(context);
        buildPersonalizeSection(context);

        return fragmentView;
    }

    //region sections

    private void buildBrandSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.LuxGramGalleryBrand), null);
        boolean loveMode = LuxGramThemeInstaller.isLoveMode();
        boolean freeMode = LuxGramThemeInstaller.isFreeThemeMode();

        LinearLayout cardsRow = new LinearLayout(context);
        cardsRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(AndroidUtilities.dp(14), AndroidUtilities.dp(6), AndroidUtilities.dp(14), AndroidUtilities.dp(6));
        contentView.addView(cardsRow, rowParams);

        LinearLayout goldCard = makeBrandCard(context,
                LocaleController.getString(R.string.LuxGramSetThemeGold),
                LocaleController.getString(R.string.LuxGramSetThemeGoldDesc),
                LuxGramThemeTuner.previewAccent(false, LuxGramThemeInstaller.getGoldness()),
                0xFF1A1408, 0xFF3A2C0E, !loveMode && !freeMode);
        LinearLayout redCard = makeBrandCard(context,
                LocaleController.getString(R.string.LuxGramSetThemeRed),
                LocaleController.getString(R.string.LuxGramSetThemeRedDesc),
                LuxGramThemeTuner.previewAccent(true, LuxGramThemeInstaller.getRedness()),
                0xFF1C0B11, 0xFF2A1018, loveMode && !freeMode);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cardParams.setMargins(AndroidUtilities.dp(2), 0, AndroidUtilities.dp(2), 0);
        cardsRow.addView(goldCard, cardParams);
        cardsRow.addView(redCard, cardParams);

        goldCard.setOnClickListener(v -> {
            if (applyBrandMode(false)) {
                rebuildEverything();
            }
        });
        redCard.setOnClickListener(v -> {
            if (applyBrandMode(true)) {
                rebuildEverything();
            }
        });
    }

    private void buildCuratedSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.LuxGramGalleryCurated),
                LocaleController.getString(R.string.LuxGramGalleryCuratedHint));

        String activeKey = LuxGramThemeInstaller.isFreeThemeMode()
                ? LuxGramThemeInstaller.freeThemeName() : null;
        for (String[] entry : CURATED) {
            String asset = entry[0];
            String themeName = entry[1];
            int fallbackBg = parseColor(entry[3]);
            int fallbackAccent = parseColor(entry[4]);
            Map<String, Integer> colors = parseThemeAssetColors(asset);
            int bg = colors.containsKey("windowBackgroundWhite") ? colors.get("windowBackgroundWhite") : fallbackBg;
            int inBubble = colors.containsKey("chat_inBubble") ? colors.get("chat_inBubble") : 0xFF2A2F38;
            int outBubble = colors.containsKey("chat_outBubble") ? colors.get("chat_outBubble") : fallbackAccent;
            int accent = colors.containsKey("windowBackgroundChecked") ? colors.get("windowBackgroundChecked") : fallbackAccent;

            String localName = themeName;
            int nameRes = resId(entry[2]);
            if (nameRes != 0) {
                try {
                    localName = LocaleController.getString(nameRes);
                } catch (Throwable ignore) {
                }
            }
            boolean active = themeName.equals(activeKey);
            View card = makeCuratedCard(context, localName, themeName, bg, inBubble, outBubble, accent, active);
            contentView.addView(card);
            card.setOnClickListener(v -> {
                if (LuxGramThemeInstaller.applyCuratedTheme(asset, themeName)) {
                    try {
                        Toast.makeText(getParentActivity(),
                                LocaleController.getString(R.string.LuxGramGalleryApplied),
                                Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignore) {
                    }
                    rebuildEverything();
                } else {
                    try {
                        Toast.makeText(getParentActivity(),
                                LocaleController.getString(R.string.LuxGramGalleryApplyFailed),
                                Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignore) {
                    }
                }
            });
        }
    }

    private void buildBackToBrandRow(Context context) {
        if (!LuxGramThemeInstaller.isFreeThemeMode()) {
            return;
        }
        addHeader(context, LocaleController.getString(R.string.LuxGramGalleryBackToBrand),
                LocaleController.getString(R.string.LuxGramGalleryBackHint));
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackgroundDrawable(goldCard(context));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(4));
        row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(13), AndroidUtilities.dp(16), AndroidUtilities.dp(13));
        row.setLayoutParams(rowParams);
        TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        title.setTextColor(0xFFFFF6DF);
        title.setText(LocaleController.getString(R.string.LuxGramGalleryBackToBrand));
        row.addView(title);
        row.setOnClickListener(v -> {
            LuxGramThemeInstaller.clearFreeThemeMode();
            if (LuxGramThemeInstaller.setLoveMode(LuxGramThemeInstaller.isLoveMode())) {
                rebuildEverything();
            }
        });
        contentView.addView(row);
    }

    //endregion

    //region Fix 33 personalization — the features the user asked back into the gallery

    // LuxGram Fix 33: tiny local callbacks — java.util.function.* needs API 24 and the
    // app's minSdk is 21 (the Fix 24 crash lesson), so plain interfaces it is.
    private interface SliderFormatter {
        String format(int value);
    }

    private interface SliderCommitter {
        void commit(int value);
    }

    private interface ToggleApplier {
        boolean apply();
    }

    private final List<FrameLayout> iconFrames = new ArrayList<>();

    /**
     * LuxGram Fix 33: the personalization section — font size, Vazir font, message
     * bubble roundness, the app icon picker and the display effect toggles. All of
     * these were scattered or missing before; the user asked for them back, all in
     * one gallery.
     */
    private void buildPersonalizeSection(Context context) {
        addHeader(context, LocaleController.getString(R.string.LuxGramGalleryPersonalize),
                LocaleController.getString(R.string.LuxGramGalleryPersonalizeHint));

        // --- font size (سایز فونت) ---
        contentView.addView(makeSliderCard(context,
                LocaleController.getString(R.string.LuxGramGalleryFontSize), 0xFFF0BE3F,
                SharedConfig.fontSize, 12, 30,
                value -> value + " pt",
                value -> {
                    try {
                        SharedConfig.fontSize = value;
                        SharedConfig.fontSizeIsDefault = false;
                        android.content.SharedPreferences preferences = ApplicationLoader.applicationContext
                                .getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
                        if (preferences != null) {
                            preferences.edit().putInt("fons_size", value).commit();
                        }
                        Theme.createCommonMessageResources();
                        rebuildEverything();
                    } catch (Throwable t) {
                        FileLog.e(t);
                    }
                }));
        addCardHint(context, LocaleController.getString(R.string.LuxGramGalleryFontSizeHint));

        // --- Vazir font ---
        contentView.addView(makeToggleCard(context,
                LocaleController.getString(R.string.LuxGramVazirFont),
                LocaleController.getString(R.string.LuxGramVazirFontInfo),
                SharedConfig.luxgramVazirFont,
                () -> {
                    SharedConfig.toggleLuxGramVazirFont();
                    return SharedConfig.luxgramVazirFont;
                }));

        // --- message bubble roundness (حباب پیام) ---
        contentView.addView(makeSliderCard(context,
                LocaleController.getString(R.string.LuxGramGalleryBubbleRadius), 0xFFF0BE3F,
                SharedConfig.bubbleRadius, 0, 18,
                value -> value + " dp",
                value -> {
                    try {
                        SharedConfig.bubbleRadius = value;
                        MessagesController.getGlobalMainSettings()
                                .edit().putInt("bubbleRadius", value).commit();
                        rebuildEverything();
                    } catch (Throwable t) {
                        FileLog.e(t);
                    }
                }));
        addCardHint(context, LocaleController.getString(R.string.LuxGramGalleryBubbleRadiusHint));

        // --- app icon picker (انتخاب آیکن برنامه) ---
        addHeader(context, LocaleController.getString(R.string.AppIcon),
                LocaleController.getString(R.string.LuxGramGalleryAppIconHint));
        contentView.addView(makeIconGrid(context));

        // --- display effect toggles ---
        addHeader(context, LocaleController.getString(R.string.LuxGramGalleryDisplay),
                LocaleController.getString(R.string.LuxGramGalleryDisplayHint));
        contentView.addView(makeToggleCard(context,
                LocaleController.getString(R.string.LuxGramSetGoldChats), null,
                SharedConfig.luxgramGoldChat,
                () -> {
                    SharedConfig.toggleLuxGramGoldChat();
                    return SharedConfig.luxgramGoldChat;
                }));
        contentView.addView(makeToggleCard(context,
                LocaleController.getString(R.string.LuxGramSetGlass), null,
                SharedConfig.luxgramGlassUI,
                () -> {
                    SharedConfig.toggleLuxGramGlassUI();
                    return SharedConfig.luxgramGlassUI;
                }));
        contentView.addView(makeToggleCard(context,
                LocaleController.getString(R.string.LuxGramSetSparks), null,
                SharedConfig.luxgramSendSparks,
                () -> {
                    SharedConfig.toggleLuxGramSendSparks();
                    return SharedConfig.luxgramSendSparks;
                }));
        contentView.addView(makeToggleCard(context,
                LocaleController.getString(R.string.LuxGramSetRain), null,
                SharedConfig.luxgramReactionRain,
                () -> {
                    SharedConfig.toggleLuxGramReactionRain();
                    return SharedConfig.luxgramReactionRain;
                }));
        contentView.addView(makeToggleCard(context,
                LocaleController.getString(R.string.LuxGramSetMembersBar), null,
                SharedConfig.luxgramMembersBar,
                () -> {
                    SharedConfig.toggleLuxGramMembersBar();
                    return SharedConfig.luxgramMembersBar;
                }));
        contentView.addView(makeToggleCard(context,
                LocaleController.getString(R.string.LuxGramSetJalaliDates), null,
                SharedConfig.luxgramJalaliDates,
                () -> {
                    SharedConfig.toggleLuxGramJalaliDates();
                    return SharedConfig.luxgramJalaliDates;
                }));
    }

    /**
     * A gold card with a title, a live value label and a Lux-styled SeekBar.
     * The slider reports continuous changes (label + preview) and commits
     * through onCommit when the user releases the thumb.
     */
    private LinearLayout makeSliderCard(Context context, String title, int accent,
                                        int currentValue, int min, int max,
                                        SliderFormatter format,
                                        SliderCommitter onCommit) {
        final int[] committed = {currentValue};
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundDrawable(goldCard(context));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(4));
        card.setPadding(AndroidUtilities.dp(17), AndroidUtilities.dp(13), AndroidUtilities.dp(17), AndroidUtilities.dp(13));
        card.setLayoutParams(cardParams);

        LinearLayout titleRow = new LinearLayout(context);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        // Fix 37 bugfix: the row must span the card — as a WRAP_CONTENT child the
        // value chip hugged the title text ("سایز فونت16 pt"); MATCH_PARENT pushes
        // the value to the opposite edge, and the side margins give it air.
        titleRow.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(0xFFFFF6DF);
        titleView.setText(title);
        titleRow.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView valueView = new TextView(context);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        valueView.setTypeface(AndroidUtilities.bold());
        valueView.setTextColor(accent);
        valueView.setText(format.format(currentValue));
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueParams.setMargins(AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8), 0);
        titleRow.addView(valueView, valueParams);
        card.addView(titleRow);

        SeekBar slider = makeLuxSlider(context, currentValue - min, max - min, accent);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                valueView.setText(format.format(min + progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int value = min + seekBar.getProgress();
                if (value != committed[0]) {
                    committed[0] = value;
                    onCommit.commit(value);
                }
            }
        });
        card.addView(slider);
        return card;
    }

    private SeekBar makeLuxSlider(Context context, int value, int max, int accent) {
        SeekBar slider = new SeekBar(context);
        slider.setMax(Math.max(1, max));
        slider.setProgress(Math.max(0, Math.min(max, value)));
        slider.setSplitTrack(false);
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(AndroidUtilities.dp(3));
        track.setColor(0x2EFFFFFF);
        android.graphics.drawable.ClipDrawable progress = new android.graphics.drawable.ClipDrawable(
                track, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(accent);
        thumb.setStroke(AndroidUtilities.dp(2), 0x73FFFFFF);
        thumb.setSize(AndroidUtilities.dp(22), AndroidUtilities.dp(22));
        slider.setProgressDrawable(progress);
        slider.setThumb(thumb);
        slider.setThumbOffset(AndroidUtilities.dp(3));
        slider.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(6), AndroidUtilities.dp(14), AndroidUtilities.dp(6));
        slider.setBackground(null);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = AndroidUtilities.dp(4);
        slider.setLayoutParams(params);
        return slider;
    }

    /**
     * A gold card with a title, optional subtitle and a Lux Switch.
     * The row (and the switch) toggle the backing setting through the provider.
     */
    private LinearLayout makeToggleCard(Context context, String title, String subtitle,
                                        boolean checked, ToggleApplier onToggle) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundDrawable(LuxGramGlassKit.rowRipple()); // LuxGram Fix 36: flat glass row
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(4));
        card.setPadding(AndroidUtilities.dp(17), AndroidUtilities.dp(13), AndroidUtilities.dp(13), AndroidUtilities.dp(13));
        card.setLayoutParams(cardParams);
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout textCol = new LinearLayout(context);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(0xFFFFF6DF);
        titleView.setText(title);
        textCol.addView(titleView);
        if (subtitle != null) {
            TextView subView = new TextView(context);
            subView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            subView.setTextColor(0x80E4D2A5);
            subView.setText(subtitle);
            subView.setPadding(0, AndroidUtilities.dp(2), 0, 0);
            textCol.addView(subView);
        }
        card.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(context);
        sw.setChecked(checked, false);
        card.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.setOnClickListener(v -> {
            try {
                org.telegram.messenger.LuxGramHaptics.tick(v);
                boolean newState = onToggle.apply();
                sw.setChecked(newState, true);
                // every display effect changes what the app draws — rebuild surfaces
                rebuildEverything();
            } catch (Throwable t) {
                FileLog.e(t);
            }
        });
        return card;
    }

    /**
     * LuxGram Fix 33: 3x3 launcher icon grid. Tapping an icon applies it through
     * LauncherIconController (the launcher refreshes within seconds) and the gold
     * ring follows the active icon instantly.
     */
    private LinearLayout makeIconGrid(Context context) {
        iconFrames.clear();
        LauncherIconController.LauncherIcon[] icons = LauncherIconController.LauncherIcon.values();
        LinearLayout grid = new LinearLayout(context);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gridParams.setMargins(AndroidUtilities.dp(12), AndroidUtilities.dp(2), AndroidUtilities.dp(12), AndroidUtilities.dp(4));
        grid.setLayoutParams(gridParams);

        int perRow = 3;
        for (int row = 0; row * perRow < icons.length; row++) {
            LinearLayout rowLayout = new LinearLayout(context);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            for (int col = 0; col < perRow; col++) {
                int index = row * perRow + col;
                LinearLayout cell = new LinearLayout(context);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(8), AndroidUtilities.dp(6), AndroidUtilities.dp(8));
                if (index < icons.length) {
                    LauncherIconController.LauncherIcon icon = icons[index];
                    FrameLayout frame = new FrameLayout(context);
                    frame.setPadding(AndroidUtilities.dp(5), AndroidUtilities.dp(5), AndroidUtilities.dp(5), AndroidUtilities.dp(5));
                    ImageView iconView = new ImageView(context);
                    iconView.setImageResource(icon.background);
                    iconView.setLayoutParams(new FrameLayout.LayoutParams(
                            AndroidUtilities.dp(52), AndroidUtilities.dp(52)));
                    frame.addView(iconView);
                    cell.addView(frame, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                    TextView label = new TextView(context);
                    label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
                    label.setTextColor(0x99E4D2A5);
                    label.setGravity(Gravity.CENTER);
                    try {
                        label.setText(LocaleController.getString(icon.title));
                    } catch (Throwable ignore) {
                        label.setText(icon.key);
                    }
                    label.setPadding(0, AndroidUtilities.dp(5), 0, 0);
                    cell.addView(label, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                    cell.setOnClickListener(v -> {
                        try {
                            if (!LauncherIconController.isEnabled(icon)) {
                                LauncherIconController.setIcon(icon);
                                if (getParentActivity() != null) {
                                    Toast.makeText(getParentActivity(),
                                            LocaleController.formatString(R.string.AppIconChangedTo,
                                                    LocaleController.getString(icon.title)),
                                            Toast.LENGTH_SHORT).show();
                                }
                            }
                            refreshIconRings(icons);
                        } catch (Throwable t) {
                            FileLog.e(t);
                        }
                    });
                    iconFrames.add(frame);
                }
                rowLayout.addView(cell, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            }
            grid.addView(rowLayout, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        refreshIconRings(icons);
        return grid;
    }

    private void refreshIconRings(LauncherIconController.LauncherIcon[] icons) {
        for (int i = 0; i < iconFrames.size() && i < icons.length; i++) {
            boolean active = LauncherIconController.isEnabled(icons[i]);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(AndroidUtilities.dp(16));
            bg.setColor(active ? 0x2EFFF6DF : 0x14FFFFFF);
            bg.setStroke(AndroidUtilities.dp(active ? 2 : 1), active ? 0xFFFFC94A : 0x33FFFFFF);
            iconFrames.get(i).setBackgroundDrawable(bg);
        }
    }

    private void addCardHint(Context context, String text) {
        TextView hint = new TextView(context);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        hint.setTextColor(0x80E4D2A5);
        hint.setText(text);
        hint.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(2), AndroidUtilities.dp(24), AndroidUtilities.dp(4));
        contentView.addView(hint);
    }

    //endregion

    //region card factories

    private LinearLayout makeBrandCard(Context context, String title, String subtitle, int accent,
                                       int gradStart, int gradEnd, boolean active) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.START);
        card.setPadding(AndroidUtilities.dp(15), AndroidUtilities.dp(13), AndroidUtilities.dp(13), AndroidUtilities.dp(13));
        // LuxGram Fix 36: glass brand card — the brand accent lives in the stroke and preview
        GradientDrawable bg = LuxGramGlassKit.panel(context, 17);
        bg.setStroke(AndroidUtilities.dp(active ? 2 : 1), active ? accent : 0x33FFFFFF);
        card.setBackgroundDrawable(bg);

        card.addView(makePreviewMock(context, 0xFF151109, 0xFF241D0E, accent, accent));

        TextView titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(accent);
        titleView.setText(title);
        titleView.setPadding(0, AndroidUtilities.dp(10), 0, 0);
        card.addView(titleView);

        TextView subView = new TextView(context);
        subView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        subView.setTextColor(0x99E4D2A5);
        subView.setText(subtitle);
        subView.setPadding(0, AndroidUtilities.dp(2), 0, 0);
        card.addView(subView);

        if (active) {
            card.addView(makeActiveBadge(context, accent), makeBadgeParams());
        }
        return card;
    }

    private View makeCuratedCard(Context context, String localName, String themeName,
                                 int bg, int inBubble, int outBubble, int accent, boolean active) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(AndroidUtilities.dp(15), AndroidUtilities.dp(13), AndroidUtilities.dp(13), AndroidUtilities.dp(13));
        // LuxGram Fix 36: glass curated card
        GradientDrawable cardBg = LuxGramGlassKit.panel(context, 17);
        cardBg.setStroke(AndroidUtilities.dp(active ? 2 : 1), active ? 0x66F4D27A : 0x1FFFFFFF);
        card.setBackgroundDrawable(cardBg);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(4));
        card.setLayoutParams(cardParams);

        LinearLayout previewRow = new LinearLayout(context);
        previewRow.setOrientation(LinearLayout.HORIZONTAL);
        previewRow.addView(makePreviewMock(context, bg, inBubble, outBubble, accent),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout nameCol = new LinearLayout(context);
        nameCol.setOrientation(LinearLayout.VERTICAL);
        nameCol.setGravity(Gravity.CENTER_VERTICAL);
        nameCol.setPadding(AndroidUtilities.dp(12), 0, 0, 0);
        TextView nameView = new TextView(context);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        nameView.setTypeface(AndroidUtilities.bold());
        nameView.setTextColor(0xFFFFF6DF);
        nameView.setText(localName);
        nameCol.addView(nameView);
        TextView subView = new TextView(context);
        subView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        subView.setTextColor(0x80E4D2A5);
        subView.setText(themeName);
        subView.setPadding(0, AndroidUtilities.dp(2), 0, 0);
        nameCol.addView(subView);
        if (active) {
            nameCol.addView(makeActiveBadge(context, 0xFFFFC94A), makeBadgeParams());
        }
        previewRow.addView(nameCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.2f));
        card.addView(previewRow);
        return card;
    }

    /**
     * A tiny chat mockup (background + incoming bubble + outgoing bubble)
     * painted with the theme's real colors.
     */
    private View makePreviewMock(Context context, int bg, int inBubble, int outBubble, int accent) {
        LinearLayout mock = new LinearLayout(context);
        mock.setOrientation(LinearLayout.VERTICAL);
        int width = AndroidUtilities.displaySize.x - AndroidUtilities.dp(120);
        mock.setLayoutParams(new LinearLayout.LayoutParams(
                Math.max(AndroidUtilities.dp(86), width / 2), AndroidUtilities.dp(76)));
        GradientDrawable mockBg = new GradientDrawable();
        mockBg.setCornerRadius(AndroidUtilities.dp(12));
        mockBg.setColor(bg);
        mockBg.setStroke(AndroidUtilities.dp(1), 0x24FFFFFF);
        mock.setBackgroundDrawable(mockBg);
        mock.setPadding(AndroidUtilities.dp(9), AndroidUtilities.dp(9), AndroidUtilities.dp(9), AndroidUtilities.dp(9));

        View inView = new View(context);
        GradientDrawable inBg = new GradientDrawable();
        // LuxGram Fix 38: preview bubbles follow the real گردی حباب پیام setting
        // (was hardcoded dp(10), so the roundness slider seemed to do nothing)
        inBg.setCornerRadius(AndroidUtilities.dp(org.telegram.messenger.SharedConfig.bubbleRadius));
        inBg.setColor(inBubble);
        inView.setBackgroundDrawable(inBg);
        LinearLayout.LayoutParams inParams = new LinearLayout.LayoutParams(
                AndroidUtilities.dp(52), AndroidUtilities.dp(18));
        inParams.gravity = Gravity.START;
        mock.addView(inView, inParams);

        LinearLayout bottomRow = new LinearLayout(context);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bottomParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomParams.topMargin = AndroidUtilities.dp(7);
        bottomRow.setLayoutParams(bottomParams);
        View outView = new View(context);
        GradientDrawable outBg = new GradientDrawable();
        // LuxGram Fix 38: follow SharedConfig.bubbleRadius (see in-bubble note above)
        outBg.setCornerRadius(AndroidUtilities.dp(org.telegram.messenger.SharedConfig.bubbleRadius));
        outBg.setColor(outBubble);
        outView.setBackgroundDrawable(outBg);
        bottomRow.addView(outView, new LinearLayout.LayoutParams(
                AndroidUtilities.dp(52), AndroidUtilities.dp(18)));
        View dotView = new View(context);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(accent);
        dotView.setBackgroundDrawable(dotBg);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(
                AndroidUtilities.dp(12), AndroidUtilities.dp(12));
        dotParams.gravity = Gravity.CENTER_VERTICAL;
        dotParams.leftMargin = AndroidUtilities.dp(8);
        bottomRow.addView(dotView, dotParams);
        mock.addView(bottomRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return mock;
    }

    private TextView makeActiveBadge(Context context, int accent) {
        TextView badge = new TextView(context);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
        badge.setTypeface(AndroidUtilities.bold());
        badge.setTextColor(0xFF0C0A05);
        badge.setGravity(Gravity.CENTER);
        badge.setText(LocaleController.getString(R.string.LuxGramGalleryActive).toUpperCase());
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setCornerRadius(AndroidUtilities.dp(11));
        badgeBg.setColor(accent);
        badge.setBackgroundDrawable(badgeBg);
        badge.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(4), AndroidUtilities.dp(10), AndroidUtilities.dp(4));
        return badge;
    }

    private LinearLayout.LayoutParams makeBadgeParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = AndroidUtilities.dp(9);
        return params;
    }

    private GradientDrawable goldCard(Context context) {
        // LuxGram Fix 36: translucent glass panel with a hairline accent stroke
        return LuxGramGlassKit.panel(context, 18);
    }

    //endregion

    //region helpers

    private int parseColor(String hex) {
        try {
            return (int) (0xFF000000L | Long.parseLong(hex.startsWith("0x") ? hex.substring(2) : hex, 16));
        } catch (Throwable t) {
            return 0xFF222222;
        }
    }

    private int resId(String key) {
        try {
            Context ctx = ApplicationLoader.applicationContext;
            return ctx.getResources().getIdentifier(key, "string", ctx.getPackageName());
        } catch (Throwable t) {
            return 0;
        }
    }

    private boolean applyBrandMode(boolean loveMode) {
        LuxGramThemeInstaller.clearFreeThemeMode();
        boolean applied = LuxGramThemeInstaller.setLoveMode(loveMode);
        if (!applied && getParentActivity() != null) {
            Toast.makeText(getParentActivity(),
                    LocaleController.getString(R.string.LuxGramSetThemeApplyFailed),
                    Toast.LENGTH_LONG).show();
        }
        return applied;
    }

    private void rebuildEverything() {
        if (parentLayout != null) {
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    parentLayout.rebuildAllFragmentViews(true, true);
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            });
        }
    }

    /**
     * Reads the most useful preview colors straight from the bundled asset —
     * the gallery always shows what the theme really looks like.
     */
    private Map<String, Integer> parseThemeAssetColors(String asset) {
        Map<String, Integer> result = new HashMap<>();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                    new InputStreamReader(ApplicationLoader.applicationContext.getAssets().open(asset), "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq).trim();
                if (!"windowBackgroundWhite".equals(key) && !"chat_inBubble".equals(key)
                        && !"chat_outBubble".equals(key) && !"windowBackgroundChecked".equals(key)) {
                    continue;
                }
                try {
                    result.put(key, Integer.parseInt(line.substring(eq + 1).trim()));
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (Throwable ignore) {
            }
        }
        return result;
    }

    private void addHeader(Context context, String title, String subtitle) {
        // LuxGram Fix 36: the same typographic rhythm as the Tools page
        TextView header = new TextView(context);
        header.setTypeface(AndroidUtilities.bold());
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        header.setLetterSpacing(0.04f);
        header.setTextColor(LuxGramGlassKit.accent());
        header.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(24), AndroidUtilities.dp(21), AndroidUtilities.dp(3));
        header.setText("❖  " + title);
        contentView.addView(header);
        if (subtitle != null) {
            TextView sub = new TextView(context);
            sub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11.5f);
            sub.setTextColor(LuxGramGlassKit.hint());
            sub.setLineSpacing(AndroidUtilities.dp(2), 1f);
            sub.setPadding(AndroidUtilities.dp(21), 0, AndroidUtilities.dp(21), AndroidUtilities.dp(12));
            sub.setText(subtitle);
            contentView.addView(sub);
        }
    }

    //endregion

    // Fix 13 pattern: self-managed edge-to-edge so the last rows clear the navbar
    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        super.onInsets(left, top, right, bottom);
        if (fragmentView instanceof ViewGroup) {
            ViewGroup root = (ViewGroup) fragmentView;
            ViewGroup target = root;
            if (root instanceof android.widget.ScrollView
                    && root.getChildCount() > 0
                    && root.getChildAt(0) instanceof ViewGroup) {
                target = (ViewGroup) root.getChildAt(0);
            }
            int newBottom = Math.max(target.getPaddingBottom(), bottom + AndroidUtilities.dp(16));
            target.setPadding(target.getPaddingLeft(), target.getPaddingTop(), target.getPaddingRight(), newBottom);
            target.setClipToPadding(false);
        }
    }
}
