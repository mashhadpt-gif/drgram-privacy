/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Fix 69 — دوستیابی (People Nearby revival) with a modern card design.
 *
 * What the user asked for, exactly: "خود تلگرام داشت قابلیتشو بر اساس
 * لوکشین اطرافیان خودتو میتونستی ببینی اما با دیزاین کارتی شیک بیارشون —
 * اسم، تصویر، سن اگر مشخص بود بر اساس تاریخ، آخرین بازدید، بیوگرافی،
 * همشون نشون بده خیلی زیبا".
 *
 * Privacy model: nothing happens without two explicit taps — the location
 * permission dialog AND an in-app consent card explaining that the position
 * is shared with Telegram for a short window (the server-side expires value).
 * The view answers contacts.getLocated (revived in TLRPC for Fix 69) and
 * renders every nearby peer as a polished card: avatar, name, age (from the
 * profile birthday when the user published one), distance, last-seen, bio
 * and a direct message button.
 */
package org.telegram.ui;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DrGramThemeInstaller;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;

public class DrGramDatingActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private static final int REQ_PERMISSIONS = 69691;
    private static final int GOLD = 0xFFF5B90D;
    private static final int GOLD_DEEP = 0xFFB77C0B;
    private static final int RED = 0xFFE63B52;
    private static final int RED_DEEP = 0xFFB01E33;
    private static final int DARK_ON_GOLD = 0xFF2A1F04;

    private static class NearbyItem {
        TLRPC.User user;
        int distance;
    }

    private LinearLayout content;
    private boolean consentAccepted;
    private boolean loading;
    private String statusText;
    private final ArrayList<NearbyItem> items = new ArrayList<>();
    private final HashMap<Long, TLRPC.UserFull> fullUsers = new HashMap<>();
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        consentAccepted = promoPrefs().getBoolean("dating_consent", false);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.userInfoDidLoad);
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.userInfoDidLoad);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.DrGramDatingTitle));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE); // proper RTL/LTR
        content.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(24));
        scrollView.addView(content, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        render();
        return root;
    }

    private void render() {
        if (content == null) {
            return;
        }
        content.removeAllViews();
        Context context = content.getContext();

        if (!hasLocationPermission()) {
            content.addView(makeCard(makeInfoRow(LocaleController.getString(R.string.DrGramDatingPerm))));
            content.addView(makeCard(makeActionButton(LocaleController.getString(R.string.DrGramDatingPermBtn), v -> requestLocationPermission())));
            return;
        }
        if (!consentAccepted) {
            content.addView(makeCard(makeInfoRow(LocaleController.getString(R.string.DrGramDatingConsent))));
            content.addView(makeCard(makeActionButton(LocaleController.getString(R.string.DrGramDatingOpen), v -> {
                consentAccepted = true;
                promoPrefs().edit().putBoolean("dating_consent", true).apply();
                loading = true;
                render();
                fetchNearby();
            })));
            return;
        }
        if (loading) {
            content.addView(makeCard(makeInfoRow(LocaleController.getString(R.string.DrGramDatingLoading))));
            return;
        }
        if (statusText != null) {
            content.addView(makeCard(makeInfoRow(statusText)));
            content.addView(makeCard(makeActionButton(LocaleController.getString(R.string.DrGramDatingRetry), v -> {
                loading = true;
                render();
                fetchNearby();
            })));
            return;
        }
        if (items.isEmpty()) {
            content.addView(makeCard(makeInfoRow(LocaleController.getString(R.string.DrGramDatingEmpty))));
            content.addView(makeCard(makeActionButton(LocaleController.getString(R.string.DrGramDatingRetry), v -> {
                loading = true;
                render();
                fetchNearby();
            })));
            return;
        }
        content.addView(makeHeader(LocaleController.formatString(R.string.DrGramDatingFound, items.size())));
        for (int i = 0; i < items.size(); i++) {
            content.addView(buildCard(items.get(i)));
        }
    }

    // ===================== data =====================

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(ApplicationLoader.applicationContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(ApplicationLoader.applicationContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocationPermission() {
        if (getParentActivity() == null) {
            return;
        }
        getParentActivity().requestPermissions(new String[]{
                Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, REQ_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResultFragment(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_PERMISSIONS) {
            render();
        }
    }

    private void fetchNearby() {
        try {
            Location location = lastKnownLocation();
            if (location == null) {
                loading = false;
                statusText = LocaleController.getString(R.string.DrGramDatingNoLocation);
                render();
                return;
            }
            TLRPC.TL_contacts_getLocated req = new TLRPC.TL_contacts_getLocated();
            double dLat = AndroidUtilities.fixLocationCoord(location.getLatitude());
            double dLon = AndroidUtilities.fixLocationCoord(location.getLongitude());
            // DrGram Fix 72: reject null-island / non-finite points — the server
            // answers GEO_POINT_INVALID for them
            if (Double.isNaN(dLat) || Double.isNaN(dLon) || Double.isInfinite(dLat) || Double.isInfinite(dLon)
                    || (Math.abs(dLat) < 0.001 && Math.abs(dLon) < 0.001)) {
                loading = false;
                statusText = LocaleController.getString(R.string.DrGramDatingNoLocation);
                render();
                return;
            }
            TLRPC.TL_inputGeoPoint geo = new TLRPC.TL_inputGeoPoint();
            geo.lat = dLat;
            geo._long = dLon;
            // exactly what the official People Nearby sends —
            // accuracy radius present whenever the fix has one
            if (location.hasAccuracy()) {
                geo.accuracy_radius = Math.max(1, Math.min(1000, (int) location.getAccuracy()));
                geo.flags |= 1;
            }
            req.geo_point = geo;
            // DrGram Fix 74: the CURRENT revision of contacts.getLocated carries
            // self_expires (flags.0) — publishing our own position for one hour,
            // exactly like the official People Nearby does when opened
            // explicitly. Without it the server never lists nearby peers.
            req.flags |= 1;
            req.self_expires = 60 * 60;
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                loading = false;
                items.clear();
                if (response instanceof TLRPC.Updates) {
                    TLRPC.Updates updates = (TLRPC.Updates) response;
                    MessagesController.getInstance(currentAccount).putUsers(updates.users, false);
                    MessagesController.getInstance(currentAccount).putChats(updates.chats, false);
                    HashMap<Long, Integer> distances = new HashMap<>();
                    for (int a = 0; a < updates.updates.size(); a++) {
                        TLRPC.Update u = updates.updates.get(a);
                        if (u instanceof org.telegram.tgnet.tl.TL_update.TL_updatePeerLocated) {
                            ArrayList<TLRPC.PeerLocated> peers = ((org.telegram.tgnet.tl.TL_update.TL_updatePeerLocated) u).peers;
                            for (int b = 0; b < peers.size(); b++) {
                                TLRPC.PeerLocated pl = peers.get(b);
                                if (pl instanceof TLRPC.TL_peerLocated) {
                                    TLRPC.TL_peerLocated loc = (TLRPC.TL_peerLocated) pl;
                                    if (loc.peer instanceof TLRPC.TL_peerUser) {
                                        distances.put(((TLRPC.TL_peerUser) loc.peer).user_id, loc.distance);
                                    }
                                }
                            }
                        }
                    }
                    for (int a = 0; a < updates.users.size(); a++) {
                        TLRPC.User user = updates.users.get(a);
                        if (user == null || user.self || user.id == getUserConfig().getClientUserId()) {
                            continue;
                        }
                        Integer dist = distances.get(user.id);
                        if (dist == null) {
                            continue;
                        }
                        NearbyItem item = new NearbyItem();
                        item.user = user;
                        item.distance = dist;
                        items.add(item);
                        loadFullUser(user);
                    }
                    if (items.isEmpty()) {
                        statusText = LocaleController.getString(R.string.DrGramDatingEmpty);
                    }
                } else if (error != null) {
                    String drgramErr = error.text == null ? "" : error.text;
                    // DrGram Fix 73: map the OFFICIAL documented errors of
                    // contacts.getLocated (core.telegram.org) to human guidance.
                    // The official list is: GEO_POINT_INVALID,
                    // USERPIC_UPLOAD_REQUIRED, USERPIC_PRIVACY_REQUIRED,
                    // BUSINESS_ADDRESS_ACTIVE. The raw code is always appended
                    // so any further report is instantly diagnosable.
                    if (drgramErr.startsWith("USERPIC_UPLOAD_REQUIRED")) {
                        statusText = LocaleController.getString(R.string.DrGramDatingNeedPhoto) + " (" + drgramErr + ")";
                    } else if (drgramErr.startsWith("USERPIC_PRIVACY_REQUIRED")) {
                        statusText = LocaleController.getString(R.string.DrGramDatingPhotoPrivacy) + " (" + drgramErr + ")";
                    } else if (drgramErr.startsWith("INPUT_METHOD_INVALID") || drgramErr.startsWith("POINT_INVALID")
                            || drgramErr.startsWith("LOCATION_INVALID") || drgramErr.startsWith("GEO_POINT_INVALID")) {
                        // DrGram Fix 72: human explanation instead of the raw code
                        statusText = LocaleController.getString(R.string.DrGramDatingRejected) + " (" + drgramErr + ")";
                    } else {
                        statusText = LocaleController.getString(R.string.DrGramDatingFail) + " (" + drgramErr + ")";
                    }
                }
                render();
            }), ConnectionsManager.RequestFlagFailOnServerErrors);
        } catch (Throwable t) {
            FileLog.e(t);
            loading = false;
            statusText = LocaleController.getString(R.string.DrGramDatingFail);
            render();
        }
    }

    private Location lastKnownLocation() {
        try {
            LocationManager lm = (LocationManager) ApplicationLoader.applicationContext.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) {
                return null;
            }
            Location best = null;
            String[] providers = {LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER};
            for (String provider : providers) {
                try {
                    Location l = lm.getLastKnownLocation(provider);
                    if (l != null && (best == null || l.getTime() > best.getTime())) {
                        best = l;
                    }
                } catch (SecurityException ignore) {}
            }
            return best;
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    private void loadFullUser(TLRPC.User user) {
        if (fullUsers.containsKey(user.id)) {
            return;
        }
        TLRPC.UserFull cached = getMessagesController().getUserFull(user.id);
        if (cached != null) {
            fullUsers.put(user.id, cached);
            return;
        }
        TLRPC.TL_users_getFullUser req = new TLRPC.TL_users_getFullUser();
        TLRPC.TL_inputUser inputUser = new TLRPC.TL_inputUser();
        inputUser.user_id = user.id;
        inputUser.access_hash = user.access_hash;
        req.id = inputUser;
        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (response instanceof TLRPC.TL_users_userFull) {
                TLRPC.TL_users_userFull res = (TLRPC.TL_users_userFull) response;
                MessagesController.getInstance(currentAccount).putUsers(res.users, false);
                MessagesController.getInstance(currentAccount).putChats(res.chats, false);
                if (res.full_user != null) {
                    fullUsers.put(user.id, res.full_user);
                    render();
                }
            }
        }));
    }

    // ===================== cards =====================

    private LinearLayout makeCard(View... children) {
        LinearLayout card = new LinearLayout(content.getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(18), AndroidUtilities.dp(18), AndroidUtilities.dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(26));
        bg.setColor(Theme.getColor(Theme.key_graySection));
        card.setBackground(bg);
        card.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((LinearLayout.LayoutParams) card.getLayoutParams()).topMargin = AndroidUtilities.dp(10);
        for (View child : children) {
            card.addView(child);
        }
        return card;
    }

    private TextView makeInfoRow(String text) {
        TextView tv = new TextView(content.getContext());
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        tv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        tv.setLineSpacing(AndroidUtilities.dp(2), 1f);
        return tv;
    }

    private TextView makeHeader(String text) {
        TextView tv = new TextView(content.getContext());
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        tv.setTypeface(AndroidUtilities.bold());
        tv.setTextColor(GOLD_DEEP);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, AndroidUtilities.dp(12), 0, 0);
        return tv;
    }

    private TextView makeActionButton(String text, View.OnClickListener listener) {
        TextView btn = new TextView(content.getContext());
        btn.setText(text);
        btn.setGravity(Gravity.CENTER);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        btn.setTypeface(AndroidUtilities.bold());
        btn.setTextColor(0xFFFFFFFF);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{DrGramThemeInstaller.isLoveMode() ? RED : GOLD, DrGramThemeInstaller.isLoveMode() ? RED_DEEP : GOLD_DEEP});
        bg.setCornerRadius(AndroidUtilities.dp(18));
        btn.setBackground(bg);
        btn.setPadding(0, AndroidUtilities.dp(13), 0, AndroidUtilities.dp(13));
        btn.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = AndroidUtilities.dp(12);
        btn.setLayoutParams(lp);
        return btn;
    }

    private View buildCard(NearbyItem item) {
        TLRPC.User user = item.user;
        Context context = content.getContext();
        boolean love = DrGramThemeInstaller.isLoveMode();
        int accent = love ? RED : GOLD;

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(AndroidUtilities.dp(18), AndroidUtilities.dp(18), AndroidUtilities.dp(18), AndroidUtilities.dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(28));
        bg.setColor(Theme.getColor(Theme.key_graySection));
        bg.setStroke(AndroidUtilities.dp(1), (accent & 0x00FFFFFF) | 0x33000000);
        card.setBackground(bg);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = AndroidUtilities.dp(12);
        card.setLayoutParams(clp);

        // header: avatar + name + distance
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        BackupImageView avatar = new BackupImageView(context);
        avatar.setRoundRadius(AndroidUtilities.dp(48));
        avatar.setImage(ImageLocation.getForUserOrChat(user, ImageLocation.TYPE_SMALL), "120_120", avatarDrawable, user);
        avatarDrawable.setInfo(currentAccount, user);
        header.addView(avatar, LayoutHelper.createLinear(96, 96, 0, 0, 14, 0));

        LinearLayout nameCol = new LinearLayout(context);
        nameCol.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(context);
        name.setText(UserObject.getUserName(user));
        name.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 19);
        name.setTypeface(AndroidUtilities.bold());
        name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        nameCol.addView(name);

        TextView distance = new TextView(context);
        distance.setText("📍 " + LocaleController.formatDistance(item.distance, 1));
        distance.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        distance.setTextColor(accent);
        nameCol.addView(distance);

        header.addView(nameCol, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        card.addView(header);

        // age — only when the user published a birth date
        TLRPC.UserFull full = fullUsers.get(user.id);
        if (full != null && full.birthday != null && full.birthday.year > 0) {
            int age = computeAge(full.birthday);
            if (age > 0) {
                card.addView(makeMetaRow(LocaleController.getString(R.string.DrGramDatingAge),
                        LocaleController.formatString(R.string.DrGramDatingYears, age)));
            }
        }

        // last seen
        card.addView(makeMetaRow(LocaleController.getString(R.string.DrGramDatingSeen),
                LocaleController.formatUserStatus(currentAccount, user)));

        // bio
        if (full != null && !TextUtils.isEmpty(full.about)) {
            card.addView(makeMetaRow(LocaleController.getString(R.string.DrGramDatingBio), full.about));
        } else if (full == null) {
            loadFullUser(user);
        }

        // message button
        TextView message = new TextView(context);
        message.setText(LocaleController.getString(R.string.DrGramDatingMessage));
        message.setGravity(Gravity.CENTER);
        message.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        message.setTypeface(AndroidUtilities.bold());
        message.setTextColor(DARK_ON_GOLD);
        GradientDrawable mBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{love ? RED : GOLD, love ? RED_DEEP : GOLD_DEEP});
        mBg.setCornerRadius(AndroidUtilities.dp(16));
        message.setBackground(mBg);
        if (love) {
            message.setTextColor(0xFFFFFFFF);
        }
        message.setPadding(0, AndroidUtilities.dp(11), 0, AndroidUtilities.dp(11));
        message.setOnClickListener(v -> {
            Bundle args = new Bundle();
            args.putLong("user_id", user.id);
            presentFragment(new ChatActivity(args));
        });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = AndroidUtilities.dp(14);
        message.setLayoutParams(mlp);
        card.addView(message);

        // card tap → profile
        card.setOnClickListener(v -> presentFragment(ProfileActivity.of(user.id)));

        return card;
    }

    private LinearLayout makeMetaRow(String label, String value) {
        LinearLayout row = new LinearLayout(content.getContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, AndroidUtilities.dp(12), 0, 0);

        TextView labelView = new TextView(content.getContext());
        labelView.setText(label);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        labelView.setTypeface(AndroidUtilities.bold());
        labelView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        row.addView(labelView);

        TextView valueView = new TextView(content.getContext());
        valueView.setText(value);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        row.addView(valueView);
        return row;
    }

    private static int computeAge(TL_account.TL_birthday birthday) {
        try {
            Calendar now = Calendar.getInstance();
            int age = now.get(Calendar.YEAR) - birthday.year;
            if (now.get(Calendar.MONTH) + 1 < birthday.month
                    || (now.get(Calendar.MONTH) + 1 == birthday.month && now.get(Calendar.DAY_OF_MONTH) < birthday.day)) {
                age--;
            }
            return age;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static SharedPreferences promoPrefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("drgram_promo", Context.MODE_PRIVATE);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.userInfoDidLoad) {
            boolean changed = false;
            for (int i = 0; i < items.size(); i++) {
                TLRPC.User u = getMessagesController().getUser(items.get(i).user.id);
                if (u != null && u != items.get(i).user) {
                    items.get(i).user = u;
                    changed = true;
                }
            }
            if (changed) {
                render();
            }
        }
    }
}
