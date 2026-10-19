/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Fix 76 — دوستیابی = the REAL Telegram "People Nearby" radar, revived.
 *
 * What the user asked for, exactly: "تلگرام قبلا رادار داشت طبق لوکیشن
 * ادمای اطرافتو نشون میداد — میخواستم اینو دوست یابیش کنی نه هرکسی که
 * لوکس گرام و نصب کرد و بیاره" — make the dating screen the old Telegram
 * radar of people around your position, from ANY Telegram app, not a
 * generic list of app users.
 *
 * Server side: contacts.getLocated#d348bc44 (the CURRENT schema revision,
 * verified against core.telegram.org today) — the very same endpoint the
 * official People Nearby used before Telegram removed the UI in Sep 2024.
 * The data is location-based by definition: every listed peer comes with a
 * server-computed distance from our position. Nothing here reads the
 * contact list, so "everyone who installed the app" is impossible.
 *
 * What Fix 76 adds on top of Fix 69–74:
 *  1) An animated radar view: sweeping beam, distance rings (1/10/100 km,
 *     logarithmic), our avatar in the center and every nearby peer as a
 *     tappable avatar blip placed by real distance (stable golden-angle
 *     bearing). Tapping a blip opens the profile.
 *  2) Fresh GPS lock-on: up to 10 s of GPS+NETWORK updates with an
 *     accuracy gate, so the request no longer runs on a stale passive fix
 *     from hours ago (that was why results "looked like random users").
 *  3) Radius filter chips (1/5/25/100 km / all) applied to both the radar
 *     and the cards, so the user sees ONLY people inside the chosen range.
 *  4) Auto-scan on open, two-phase status (lock on position → search
 *     nearby), honest empty/error states.
 *
 * Privacy model (unchanged): two explicit taps — the OS location permission
 * AND an in-app consent card. Our own position is published for one hour
 * (self_expires = 3600) exactly like the official People Nearby did.
 */
package org.telegram.ui;

import android.Manifest;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
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
import org.telegram.tgnet.tl.TL_update;
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
    private static final long MAX_LOCK_MS = 10_000;
    private static final int MAX_ACCEPT_ACCURACY = 1000;   // meters
    private static final long FRESH_NS = 300_000_000_000L; // 5 minutes in ns
    private static final double LOG_RANGE = Math.log(150_000.0 / 50.0);

    private static class NearbyItem {
        TLRPC.User user;
        int distance;      // meters, computed by the server
        float angleRad;    // stable radar bearing (golden-angle hash)
        float radiusFrac;  // 0..1, logarithmic distance mapping
    }

    private LinearLayout content;
    private boolean consentAccepted;
    private boolean loading;
    private int loadingPhase; // 0 = locking position, 1 = searching nearby
    private String statusText;
    private int radiusFilter; // meters, 0 = everyone

    private final ArrayList<NearbyItem> allItems = new ArrayList<>();
    private final HashMap<Long, TLRPC.UserFull> fullUsers = new HashMap<>();
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private boolean scannedOnce;

    // fresh location machinery
    private boolean locating;
    private Location bestFix;
    private LocationManager locationManagerRef;
    private FreshLocationCallback pendingLocationCallback;

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
        abortLocationLock();
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
        content.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
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
                fetchNearby();
            })));
            return;
        }

        // results area — the radar is always on screen, states flow under it
        TextView hint = makeInfoRow(LocaleController.getString(R.string.DrGramDatingRadarHint));
        hint.setAlpha(0.8f);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        content.addView(hint);

        DrGramRadarView radar = new DrGramRadarView(context, accentColor(), avatarDrawable, currentAccount, user ->
                presentFragment(ProfileActivity.of(user.id)));
        radar.setSelf(UserConfig.getInstance(currentAccount).getCurrentUser());
        int side = Math.min(AndroidUtilities.displaySize.x - AndroidUtilities.dp(40), AndroidUtilities.dp(340));
        FrameLayout radarHolder = new FrameLayout(context);
        radarHolder.addView(radar, LayoutHelper.createFrame(side, side, Gravity.CENTER));
        content.addView(radarHolder, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 0, 14, 0, 6));

        TextView statusLabel = new TextView(context);
        statusLabel.setGravity(Gravity.CENTER);
        statusLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        statusLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        statusLabel.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(2));
        content.addView(statusLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (loading) {
            radar.setItems(new ArrayList<>());
            statusLabel.setText(LocaleController.getString(loadingPhase == 0
                    ? R.string.DrGramDatingLocating
                    : R.string.DrGramDatingLoading));
            return;
        }
        if (statusText != null) {
            radar.setItems(new ArrayList<>());
            content.addView(makeCard(makeInfoRow(statusText)));
            content.addView(makeCard(makeActionButton(LocaleController.getString(R.string.DrGramDatingRetry), v -> fetchNearby())));
            return;
        }
        if (allItems.isEmpty()) {
            radar.setItems(new ArrayList<>());
            content.addView(makeCard(makeInfoRow(LocaleController.getString(R.string.DrGramDatingEmpty))));
            content.addView(makeCard(makeActionButton(LocaleController.getString(R.string.DrGramDatingRetry), v -> fetchNearby())));
            if (!scannedOnce) {
                scannedOnce = true;
                fetchNearby();
            }
            return;
        }

        ArrayList<NearbyItem> visible = filteredItems();
        radar.setItems(visible);

        content.addView(buildChips(context));
        if (!visible.isEmpty()) {
            content.addView(makeHeader(LocaleController.formatString(R.string.DrGramDatingFound, visible.size())));
            TextView visibleUntil = makeInfoRow(LocaleController.getString(R.string.DrGramDatingVisibleUntil));
            visibleUntil.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            visibleUntil.setGravity(Gravity.CENTER);
            visibleUntil.setAlpha(0.65f);
            content.addView(visibleUntil);
        }

        if (visible.isEmpty()) {
            content.addView(makeCard(makeInfoRow(LocaleController.getString(R.string.DrGramDatingEmptyFiltered))));
            return;
        }
        for (int i = 0; i < visible.size(); i++) {
            content.addView(buildCard(visible.get(i)));
        }
    }

    // ===================== data =====================

    private ArrayList<NearbyItem> filteredItems() {
        ArrayList<NearbyItem> out = new ArrayList<>();
        for (int i = 0; i < allItems.size(); i++) {
            NearbyItem it = allItems.get(i);
            if (radiusFilter == 0 || it.distance <= radiusFilter) {
                out.add(it);
            }
        }
        return out;
    }

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
        if (locating) {
            return;
        }
        scannedOnce = true;
        locating = true;
        loading = true;
        loadingPhase = 0;
        statusText = null;
        bestFix = null;
        render();
        getFreshLocation((location, providersOff) -> AndroidUtilities.runOnUIThread(() -> {
            locating = false;
            if (location == null) {
                loading = false;
                statusText = LocaleController.getString(providersOff
                        ? R.string.DrGramDatingGpsOff
                        : R.string.DrGramDatingWeakFix);
                render();
                return;
            }
            loadingPhase = 1;
            render();
            requestLocated(location);
        }));
    }

    private interface FreshLocationCallback {
        void onResult(Location location, boolean providersOff);
    }

    /**
     * Fix 76: never trust a stale passive fix again. Fresh lock-on:
     * a recent (<5 min) cached fix is accepted instantly, otherwise GPS and
     * NETWORK updates run in parallel for up to 10 s with an accuracy gate.
     */
    private void getFreshLocation(FreshLocationCallback callback) {
        LocationManager lm = (LocationManager) ApplicationLoader.applicationContext.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) {
            callback.onResult(null, true);
            return;
        }
        boolean gpsOn = isProviderEnabledSafe(lm, LocationManager.GPS_PROVIDER);
        boolean netOn = isProviderEnabledSafe(lm, LocationManager.NETWORK_PROVIDER);
        if (!gpsOn && !netOn) {
            callback.onResult(null, true);
            return;
        }
        Location last = bestLastKnown(lm);
        if (last != null && isFreshAndValid(last)) {
            callback.onResult(last, false);
            return;
        }
        bestFix = null;
        locationManagerRef = lm;
        pendingLocationCallback = callback;
        try {
            if (gpsOn) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, freshListener, ApplicationLoader.applicationHandler.getLooper());
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        try {
            if (netOn) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000, 0, freshListener, ApplicationLoader.applicationHandler.getLooper());
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        AndroidUtilities.runOnUIThread(freshTimeoutRunnable, MAX_LOCK_MS);
    }

    private final LocationListener freshListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if (location == null) {
                return;
            }
            if (bestFix == null || (location.hasAccuracy() && (!bestFix.hasAccuracy() || location.getAccuracy() < bestFix.getAccuracy()))) {
                bestFix = location;
            }
            if (bestFix != null && bestFix.hasAccuracy() && bestFix.getAccuracy() <= 100) {
                finishLocationLock();
            }
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}

        @Override
        public void onProviderEnabled(String provider) {}

        @Override
        public void onProviderDisabled(String provider) {
            finishLocationLock();
        }
    };

    private final Runnable freshTimeoutRunnable = this::finishLocationLock;

    private void finishLocationLock() {
        LocationManager lm = locationManagerRef;
        FreshLocationCallback callback = pendingLocationCallback;
        locationManagerRef = null;
        pendingLocationCallback = null;
        AndroidUtilities.cancelRunOnUIThread(freshTimeoutRunnable);
        if (lm != null) {
            try {
                lm.removeUpdates(freshListener);
            } catch (Throwable ignore) {}
        }
        if (callback == null) {
            return;
        }
        Location fix = bestFix;
        if (fix != null && fix.hasAccuracy() && fix.getAccuracy() <= MAX_ACCEPT_ACCURACY && !isNullIsland(fix)) {
            callback.onResult(fix, false);
        } else {
            callback.onResult(null, false);
        }
    }

    private void abortLocationLock() {
        LocationManager lm = locationManagerRef;
        locationManagerRef = null;
        pendingLocationCallback = null;
        AndroidUtilities.cancelRunOnUIThread(freshTimeoutRunnable);
        if (lm != null) {
            try {
                lm.removeUpdates(freshListener);
            } catch (Throwable ignore) {}
        }
    }

    private boolean isProviderEnabledSafe(LocationManager lm, String provider) {
        try {
            return lm.isProviderEnabled(provider);
        } catch (Throwable t) {
            return false;
        }
    }

    private Location bestLastKnown(LocationManager lm) {
        Location best = null;
        String[] providers = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER};
        for (String provider : providers) {
            try {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) {
                    best = l;
                }
            } catch (SecurityException ignore) {}
        }
        return best;
    }

    private boolean isFreshAndValid(Location location) {
        if (isNullIsland(location)) {
            return false;
        }
        if (location.hasAccuracy() && location.getAccuracy() > MAX_ACCEPT_ACCURACY) {
            return false;
        }
        return SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos() < FRESH_NS;
    }

    private boolean isNullIsland(Location location) {
        double lat = AndroidUtilities.fixLocationCoord(location.getLatitude());
        double lon = AndroidUtilities.fixLocationCoord(location.getLongitude());
        return Double.isNaN(lat) || Double.isNaN(lon) || Double.isInfinite(lat) || Double.isInfinite(lon)
                || (Math.abs(lat) < 0.001 && Math.abs(lon) < 0.001);
    }

    private void requestLocated(Location location) {
        try {
            TLRPC.TL_contacts_getLocated req = new TLRPC.TL_contacts_getLocated();
            double dLat = AndroidUtilities.fixLocationCoord(location.getLatitude());
            double dLon = AndroidUtilities.fixLocationCoord(location.getLongitude());
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
                allItems.clear();
                if (response instanceof TLRPC.Updates) {
                    TLRPC.Updates updates = (TLRPC.Updates) response;
                    MessagesController.getInstance(currentAccount).putUsers(updates.users, false);
                    MessagesController.getInstance(currentAccount).putChats(updates.chats, false);
                    HashMap<Long, Integer> distances = new HashMap<>();
                    for (int a = 0; a < updates.updates.size(); a++) {
                        TLRPC.Update u = updates.updates.get(a);
                        if (u instanceof TL_update.TL_updatePeerLocated) {
                            ArrayList<TLRPC.PeerLocated> peers = ((TL_update.TL_updatePeerLocated) u).peers;
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
                        item.angleRad = bearingFor(user.id);
                        item.radiusFrac = radiusFraction(dist);
                        allItems.add(item);
                        loadFullUser(user);
                    }
                    if (allItems.isEmpty()) {
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

    private static float bearingFor(long userId) {
        long h = Math.abs(userId * 0x9E3779B97F4A7C15L);
        return (float) ((h % 3600) / 3600.0 * 2.0 * Math.PI);
    }

    private static float radiusFraction(int distanceMeters) {
        double d = Math.max(distanceMeters, 60.0);
        double frac = Math.log(d / 50.0) / LOG_RANGE;
        return (float) Math.max(0.07, Math.min(0.985, frac));
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

    // ===================== radius chips =====================

    private View buildChips(Context context) {
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(10), AndroidUtilities.dp(4), AndroidUtilities.dp(2));
        scroll.addView(box, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        int[][] options = {
                {0, R.string.DrGramDatingRadiusAll},
                {1000, R.string.DrGramDatingRadiusKm1},
                {5000, R.string.DrGramDatingRadiusKm5},
                {25000, R.string.DrGramDatingRadiusKm25},
                {100000, R.string.DrGramDatingRadiusKm100},
        };
        boolean love = DrGramThemeInstaller.isLoveMode();
        int accent = love ? RED : GOLD;
        for (int i = 0; i < options.length; i++) {
            final int value = options[i][0];
            boolean selected = radiusFilter == value;
            TextView chip = new TextView(context);
            chip.setText(LocaleController.getString(options[i][1]));
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            chip.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(AndroidUtilities.dp(20));
            if (selected) {
                bg.setOrientation(GradientDrawable.Orientation.TL_BR);
                bg.setColors(new int[]{accent, love ? RED_DEEP : GOLD_DEEP});
                chip.setTextColor(love ? 0xFFFFFFFF : DARK_ON_GOLD);
                chip.setTypeface(AndroidUtilities.bold());
            } else {
                bg.setColor(Theme.getColor(Theme.key_graySection));
                bg.setStroke(AndroidUtilities.dp(1), (accent & 0x00FFFFFF) | 0x22000000);
                chip.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            }
            chip.setBackground(bg);
            chip.setOnClickListener(v -> {
                if (radiusFilter == value) {
                    return;
                }
                radiusFilter = value;
                render();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = AndroidUtilities.dp(8);
            box.addView(chip, lp);
        }
        return scroll;
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

    private int accentColor() {
        return DrGramThemeInstaller.isLoveMode() ? RED : GOLD;
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
        distance.setText("📡 " + LocaleController.formatDistance(item.distance, 1));
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
            for (int i = 0; i < allItems.size(); i++) {
                TLRPC.User u = getMessagesController().getUser(allItems.get(i).user.id);
                if (u != null && u != allItems.get(i).user) {
                    allItems.get(i).user = u;
                    changed = true;
                }
            }
            if (changed) {
                render();
            }
        }
    }

    // ===================== radar view =====================

    /**
     * Fix 76: the radar the user remembers from the old Telegram People
     * Nearby. Sweeping beam + logarithmic distance rings (1/10/100 km) +
     * every nearby peer as a tappable avatar placed by REAL server distance
     * and a stable golden-angle bearing (the API only exposes distance, not
     * direction, so the bearing is cosmetic — distances are honest).
     */
    private static class DrGramRadarView extends FrameLayout {

        interface ProfileOpener {
            void open(TLRPC.User user);
        }

        private static class Blip {
            TLRPC.User user;
            BackupImageView view;
            float angleRad;
            float radiusFrac;
            float phase;
            float x, y;
        }

        private final int accent;
        private final int account;
        private final AvatarDrawable avatarDrawable;
        private final ProfileOpener opener;
        private final Paint platePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint crossPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint sweepPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pulsePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final BackupImageView selfView;
        private final ArrayList<Blip> blips = new ArrayList<>();
        private ValueAnimator sweepAnimator;
        private float sweepDeg;
        private boolean plateShaderReady;
        private float plateR = -1;

        DrGramRadarView(Context context, int accentColor, AvatarDrawable avatarDrawable, int account, ProfileOpener opener) {
            super(context);
            this.accent = accentColor;
            this.account = account;
            this.avatarDrawable = avatarDrawable;
            this.opener = opener;
            setWillNotDraw(false);

            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeWidth(AndroidUtilities.dp(1));
            ringPaint.setColor((accent & 0x00FFFFFF) | 0x5A000000);

            crossPaint.setStyle(Paint.Style.STROKE);
            crossPaint.setStrokeWidth(AndroidUtilities.dp(1));
            crossPaint.setColor((accent & 0x00FFFFFF) | 0x2E000000);

            pulsePaint.setStyle(Paint.Style.STROKE);
            pulsePaint.setStrokeWidth(AndroidUtilities.dp(1.5f));

            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setColor(0x99FFFFFF);
            textPaint.setTextSize(AndroidUtilities.dp(13));

            selfView = new BackupImageView(context);
            selfView.setRoundRadius(AndroidUtilities.dp(24));
            addView(selfView, LayoutHelper.createFrame(48, 48, Gravity.CENTER));
        }

        void setSelf(TLRPC.User user) {
            if (user != null) {
                avatarDrawable.setInfo(account, user);
                selfView.setImage(ImageLocation.getForUserOrChat(user, ImageLocation.TYPE_SMALL), "100_100", avatarDrawable, user);
            }
        }

        void setItems(ArrayList<NearbyItem> items) {
            for (int i = 0; i < blips.size(); i++) {
                removeView(blips.get(i).view);
            }
            blips.clear();
            if (items != null) {
                for (int i = 0; i < items.size(); i++) {
                    NearbyItem it = items.get(i);
                    Blip blip = new Blip();
                    blip.user = it.user;
                    blip.angleRad = it.angleRad;
                    blip.radiusFrac = it.radiusFrac;
                    blip.phase = (i * 0.17f) % 1f;
                    BackupImageView view = new BackupImageView(getContext());
                    view.setRoundRadius(AndroidUtilities.dp(17));
                    avatarDrawable.setInfo(account, it.user);
                    view.setImage(ImageLocation.getForUserOrChat(it.user, ImageLocation.TYPE_SMALL), "80_80", avatarDrawable, it.user);
                    view.setOnClickListener(v -> {
                        if (opener != null) {
                            opener.open(it.user);
                        }
                    });
                    blip.view = view;
                    blips.add(blip);
                    addView(view, LayoutHelper.createFrame(34, 34, Gravity.TOP | Gravity.START));
                }
            }
            layoutBlips();
            invalidate();
        }

        private void layoutBlips() {
            int w = getWidth();
            if (w == 0 || blips.isEmpty()) {
                return;
            }
            float cx = w / 2f, cy = getHeight() / 2f;
            float radius = w / 2f - AndroidUtilities.dp(18);
            for (int i = 0; i < blips.size(); i++) {
                Blip blip = blips.get(i);
                blip.x = cx + radius * blip.radiusFrac * (float) Math.sin(blip.angleRad);
                blip.y = cy - radius * blip.radiusFrac * (float) Math.cos(blip.angleRad);
                LayoutParams lp = (LayoutParams) blip.view.getLayoutParams();
                lp.leftMargin = (int) (blip.x - AndroidUtilities.dp(17));
                lp.topMargin = (int) (blip.y - AndroidUtilities.dp(17));
                blip.view.setLayoutParams(lp);
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            layoutBlips();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            sweepAnimator = ValueAnimator.ofFloat(0, 360);
            sweepAnimator.setDuration(4200);
            sweepAnimator.setRepeatCount(ValueAnimator.INFINITE);
            sweepAnimator.setRepeatMode(ValueAnimator.RESTART);
            sweepAnimator.setInterpolator(new LinearInterpolator());
            sweepAnimator.addUpdateListener(animation -> {
                sweepDeg = (float) animation.getAnimatedValue();
                invalidate();
            });
            sweepAnimator.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            if (sweepAnimator != null) {
                sweepAnimator.cancel();
                sweepAnimator = null;
            }
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            if (w == 0) {
                return;
            }
            float cx = w / 2f, cy = h / 2f;
            float radius = w / 2f - AndroidUtilities.dp(18);

            if (!plateShaderReady || plateR != radius) {
                platePaint.setShader(new RadialGradient(cx, cy, radius,
                        new int[]{0xFF1B1710, 0xFF100E09, 0xFF0A0906},
                        new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
                sweepPaint.setShader(new SweepGradient(cx, cy,
                        new int[]{0x00000000, (accent & 0x00FFFFFF) | 0x0A000000, (accent & 0x00FFFFFF) | 0x46000000, (accent & 0x00FFFFFF) | 0xC8000000, 0x00000000},
                        new float[]{0f, 0.55f, 0.86f, 0.985f, 1f}));
                plateShaderReady = true;
                plateR = radius;
            }

            // plate
            canvas.drawCircle(cx, cy, radius, platePaint);
            // crosshair
            canvas.drawLine(cx - radius, cy, cx + radius, cy, crossPaint);
            canvas.drawLine(cx, cy - radius, cx, cy + radius, crossPaint);
            // distance rings: 1 / 10 / 100 km on the log scale
            float[] rings = {0.374f, 0.662f, 0.949f};
            for (int i = 0; i < rings.length; i++) {
                canvas.drawCircle(cx, cy, radius * rings[i], ringPaint);
            }
            // rim
            ringPaint.setStrokeWidth(AndroidUtilities.dp(1.5f));
            canvas.drawCircle(cx, cy, radius, ringPaint);
            ringPaint.setStrokeWidth(AndroidUtilities.dp(1));

            // rotating sweep beam
            canvas.save();
            canvas.rotate(sweepDeg, cx, cy);
            canvas.drawCircle(cx, cy, radius, sweepPaint);
            canvas.restore();

            // pulsing target rings behind every blip
            long now = SystemClock.elapsedRealtime();
            for (int i = 0; i < blips.size(); i++) {
                Blip blip = blips.get(i);
                if (blip.x == 0 && blip.y == 0) {
                    continue;
                }
                float t = ((now % 2400) / 2400f + blip.phase) % 1f;
                pulsePaint.setColor((accent & 0x00FFFFFF) | (int) ((1f - t) * 70) << 24);
                canvas.drawCircle(blip.x, blip.y, AndroidUtilities.dp(18) + t * AndroidUtilities.dp(12), pulsePaint);
            }

            if (blips.isEmpty()) {
                canvas.drawText(LocaleController.getString(R.string.DrGramDatingRadarEmpty), cx, cy - AndroidUtilities.dp(64), textPaint);
            }
        }
    }
}
