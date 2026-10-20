/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Online Radar widget (Fix 80) — the home-screen face of the Online
 * Guard (Fix 20): shows the watched contacts and whether they are online
 * right now, straight from the cached Telegram users. Refreshes on the
 * system widget schedule (30 min) and every time the widget is touched.
 */
package org.telegram.messenger;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Set;

public class DrGramRadarWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            manager.updateAppWidget(id, buildViews(context));
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        refreshAll(context);
    }

    private static void refreshAll(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, DrGramRadarWidget.class));
            for (int id : ids) {
                manager.updateAppWidget(id, buildViews(context));
            }
        } catch (Throwable ignore) {
        }
    }

    private static RemoteViews buildViews(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.drgram_widget_radar);
        views.setTextViewText(R.id.drgram_rw_title, LocaleController.getString(R.string.DrGramWidgetRadarTitle));

        int[] nameIds = {R.id.drgram_rw_name1, R.id.drgram_rw_name2, R.id.drgram_rw_name3, R.id.drgram_rw_name4};
        int[] statusIds = {R.id.drgram_rw_status1, R.id.drgram_rw_status2, R.id.drgram_rw_status3, R.id.drgram_rw_status4};

        int account = UserConfig.selectedAccount;
        ArrayList<String> names = new ArrayList<>();
        ArrayList<String> statuses = new ArrayList<>();
        try {
            Set<String> watched = DrGramOnlineGuard.getWatchedIds(account);
            if (watched != null && !watched.isEmpty()) {
                for (String raw : watched) {
                    if (names.size() >= 4) {
                        break;
                    }
                    long uid;
                    try {
                        uid = Long.parseLong(raw);
                    } catch (Exception e) {
                        continue;
                    }
                    TLRPC.User user = MessagesController.getInstance(account).getUser(uid);
                    if (user == null) {
                        continue;
                    }
                    names.add(UserObject.getFirstName(user));
                    boolean online = user.status instanceof TLRPC.TL_userStatusOnline;
                    statuses.add(online
                            ? LocaleController.getString(R.string.Online)
                            : LocaleController.formatUserStatus(account, user));
                }
            }
        } catch (Throwable ignore) {
        }

        if (names.isEmpty()) {
            names.add("");
            statuses.add(LocaleController.getString(R.string.DrGramWidgetRadarEmpty));
        }
        for (int i = 0; i < nameIds.length; i++) {
            if (i < names.size()) {
                views.setTextViewText(nameIds[i], names.get(i));
                views.setTextViewText(statusIds[i], statuses.get(i));
                views.setViewVisibility(nameIds[i], android.view.View.VISIBLE);
                views.setViewVisibility(statusIds[i], android.view.View.VISIBLE);
            } else {
                views.setTextViewText(nameIds[i], "");
                views.setTextViewText(statusIds[i], "");
                views.setViewVisibility(nameIds[i], android.view.View.GONE);
                views.setViewVisibility(statusIds[i], android.view.View.GONE);
            }
        }
        return views;
    }
}
