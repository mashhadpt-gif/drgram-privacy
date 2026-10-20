/*
 * This is the source code of LuxGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * LuxGram Anti-Delete viewer (Fix 36) — the "separate bag": every message the
 * other side deleted lands here. Glass design, master switch, clear-all.
 */
package org.telegram.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.LuxGramAntiDelete;
import org.telegram.messenger.LuxGramThemeInstaller;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LuxGramGlassKit;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Switch;

import java.util.ArrayList;

public class LuxGramAntiDeleteActivity extends BaseFragment {

    private LinearLayout listContainer;
    private LinearLayout card;
    private TextView countView;
    private Switch switchView;

    @Override
    public View createView(Context context) {
        try {
            return luxgramCreateViewInternal(context);
        } catch (Throwable t) {
            FileLog.e(t);
            LinearLayout fb = new LinearLayout(context);
            fb.setBackgroundColor(0xFF0B0A07);
            return fb;
        }
    }

    private View luxgramCreateViewInternal(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.LuxGramAntiDeleteTitle));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        LinearLayout contentView = new LinearLayout(context);
        contentView.setOrientation(LinearLayout.VERTICAL);
        contentView.setPadding(0, 0, 0, AndroidUtilities.dp(28));
        contentView.setBackground(LuxGramGlassKit.pageGradient());

        ScrollView scroller = new ScrollView(context);
        scroller.setFillViewport(true);
        scroller.setClipToPadding(false);
        scroller.addView(contentView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        fragmentView = scroller;

        try {
            LinearLayout hero = hero(context);
            contentView.addView(hero, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            ((LinearLayout.LayoutParams) hero.getLayoutParams()).setMargins(
                    AndroidUtilities.dp(14), AndroidUtilities.dp(18), AndroidUtilities.dp(14), AndroidUtilities.dp(4));
        } catch (Throwable t) {
            FileLog.e(t);
        }

        LuxGramAntiDelete engine = LuxGramAntiDelete.getInstance(currentAccount);

        contentView.addView(LuxGramGlassKit.header(context,
                LocaleController.getString(R.string.LuxGramAntiDeleteModule), 0));
        contentView.addView(LuxGramGlassKit.hint(context,
                LocaleController.getString(R.string.LuxGramAntiDeleteHint)));

        card = glassCard(context);
        switchView = new Switch(context);
        switchView.setChecked(engine.isEnabled(), false);
        LinearLayout toggleRow = makeValueRow(context,
                LocaleController.getString(R.string.LuxGramAntiDeleteEnable), switchView);
        toggleRow.setOnClickListener(v -> {
            boolean newState = !engine.isEnabled();
            engine.setEnabled(newState);
            switchView.setChecked(newState, false);
        });
        card.addView(toggleRow);
        countView = new TextView(context);
        countView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        countView.setTypeface(AndroidUtilities.bold());
        countView.setTextColor(LuxGramThemeInstaller.isLoveMode() ? 0xFFF5A9B8 : 0xFFF4D27A);
        countView.setText("×  " + engine.getSavedCount());
        LinearLayout countRow = makeValueRow(context,
                LocaleController.getString(R.string.LuxGramAntiDeleteCount), countView);
        card.addView(countRow);
        contentView.addView(card, cardParams());

        contentView.addView(LuxGramGlassKit.header(context,
                LocaleController.getString(R.string.LuxGramAntiDeleteBag), 0));
        listContainer = glassCard(context);
        contentView.addView(listContainer, cardParams());

        LinearLayout clearCard = glassCard(context);
        clearCard.addView(makeActionRow(context, LocaleController.getString(R.string.LuxGramAntiDeleteClear), v -> {
            if (getParentActivity() == null) {
                return;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), getResourceProvider());
            builder.setTitle(LocaleController.getString(R.string.LuxGramAntiDeleteClear));
            builder.setMessage(LocaleController.getString(R.string.LuxGramAntiDeleteClearConfirm));
            builder.setPositiveButton(LocaleController.getString(R.string.LuxGramSave), (d, w) -> {
                LuxGramAntiDelete.getInstance(currentAccount).clearBag();
                rebuildList();
                refreshCount();
            });
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            builder.show();
        }));
        contentView.addView(clearCard, cardParams());

        rebuildList();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        rebuildList();
        refreshCount();
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        super.onInsets(left, top, right, bottom);
        try {
            if (fragmentView instanceof ViewGroup) {
                ViewGroup root = (ViewGroup) fragmentView;
                if (root instanceof ScrollView && root.getChildCount() > 0
                        && root.getChildAt(0) instanceof ViewGroup) {
                    ViewGroup target = (ViewGroup) root.getChildAt(0);
                    target.setPadding(target.getPaddingLeft(), target.getPaddingTop(),
                            target.getPaddingRight(), Math.max(target.getPaddingBottom(), bottom + AndroidUtilities.dp(16)));
                    target.setClipToPadding(false);
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private LinearLayout hero(Context context) {
        LinearLayout hero = new LinearLayout(context);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER);
        hero.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(26), AndroidUtilities.dp(24), AndroidUtilities.dp(24));
        hero.setBackground(LuxGramGlassKit.heroPanel(context));

        TextView emblem = new TextView(context);
        emblem.setText("✦  ◈  ✦");
        emblem.setTextColor(0xFFFFE6A0);
        emblem.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        emblem.setGravity(Gravity.CENTER);
        emblem.setLetterSpacing(0.14f);
        hero.addView(emblem, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        TextView brand = new TextView(context);
        brand.setText("ANTI  DELETE");
        brand.setTextColor(0xFFFFE8AE);
        brand.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 21);
        brand.setTypeface(AndroidUtilities.bold());
        brand.setGravity(Gravity.CENTER);
        brand.setLetterSpacing(0.14f);
        LinearLayout.LayoutParams bp = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
        bp.topMargin = AndroidUtilities.dp(8);
        hero.addView(brand, bp);
        return hero;
    }

    private void contentViewAddHero(Context context, LinearLayout hero, LinearLayout.LayoutParams hp) {
        // no longer used — hero is added directly by the caller
    }

    private LinearLayout glassCard(Context context) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(LuxGramGlassKit.panel(context, 20));
        LuxGramGlassKit.applyDividers(panel);
        return panel;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14), AndroidUtilities.dp(10));
        return params;
    }

    private LinearLayout makeValueRow(Context context, String label, View value) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundDrawable(LuxGramGlassKit.rowRipple());
        row.setPadding(AndroidUtilities.dp(17), AndroidUtilities.dp(13), AndroidUtilities.dp(17), AndroidUtilities.dp(13));
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView labelView = new TextView(context);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        labelView.setTextColor(LuxGramGlassKit.ROW_TITLE);
        labelView.setText(label);
        row.addView(labelView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueParams.setMargins(AndroidUtilities.dp(8), 0, 0, 0);
        value.setLayoutParams(valueParams);
        row.addView(value);
        return row;
    }

    private LinearLayout makeActionRow(Context context, String title, View.OnClickListener listener) {
        LinearLayout row = makeValueRow(context, title, new TextView(context));
        row.setOnClickListener(listener);
        return row;
    }

    private void refreshCount() {
        if (countView != null) {
            countView.setText("×  " + LuxGramAntiDelete.getInstance(currentAccount).getSavedCount());
        }
        if (switchView != null) {
            switchView.setChecked(LuxGramAntiDelete.getInstance(currentAccount).isEnabled(), false);
        }
    }

    private void rebuildList() {
        if (listContainer == null) {
            return;
        }
        listContainer.removeAllViews();
        ArrayList<String[]> bag = LuxGramAntiDelete.getInstance(currentAccount).getBag();
        if (bag.isEmpty()) {
            TextView empty = new TextView(listContainer.getContext());
            empty.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            empty.setTextColor(LuxGramGlassKit.hint());
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(AndroidUtilities.dp(17), AndroidUtilities.dp(18), AndroidUtilities.dp(17), AndroidUtilities.dp(18));
            empty.setText(LocaleController.getString(R.string.LuxGramAntiDeleteEmpty));
            listContainer.addView(empty);
            return;
        }
        for (int i = 0; i < bag.size(); i++) {
            listContainer.addView(makeBagRow(listContainer.getContext(), bag.get(i)));
        }
    }

    private View makeBagRow(Context context, String[] record) {
        // record = {dialogId, senderName, time, text, mediaFlag}
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(AndroidUtilities.dp(17), AndroidUtilities.dp(11), AndroidUtilities.dp(17), AndroidUtilities.dp(11));
        row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView sender = new TextView(context);
        sender.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        sender.setTypeface(AndroidUtilities.bold());
        sender.setTextColor(0xFFF1EADA);
        sender.setText(record[1]);
        head.addView(sender, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        TextView time = new TextView(context);
        time.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f);
        time.setTextColor(LuxGramGlassKit.hint());
        time.setText(record[2]);
        head.addView(time);

        row.addView(head);

        StringBuilder body = new StringBuilder();
        if ("1".equals(record[4])) {
            body.append("📎 ");
        }
        if (record[3].length() > 0) {
            body.append(record[3]);
        }
        if (body.length() > 0) {
            TextView text = new TextView(context);
            text.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            text.setTextColor(0xD9F1EADA);
            text.setMaxLines(6);
            text.setEllipsize(android.text.TextUtils.TruncateAt.END);
            text.setText(body.toString());
            LinearLayout.LayoutParams textParams = LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
            textParams.topMargin = AndroidUtilities.dp(4);
            row.addView(text, textParams);
        }

        GradientDrawable underline = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0x00000000, LuxGramThemeInstaller.isLoveMode() ? 0x40F06A82 : 0x40F4D27A});
        View line = new View(context);
        line.setBackgroundDrawable(underline);
        row.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));
        return row;
    }
}
