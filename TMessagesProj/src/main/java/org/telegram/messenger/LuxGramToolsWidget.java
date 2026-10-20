/*
 * This is the source code of LuxGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * LuxGram Fix 36: home-screen widget — two keys on the onyx glass pill:
 *   • منشی  — toggles the offline auto-responder
 *   • گنجینه — opens the hidden-chats vault through LaunchActivity
 */
package org.telegram.messenger;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public class LuxGramToolsWidget extends AppWidgetProvider {

    public static final String ACTION_TOGGLE_SECRETARY = "org.telegram.messenger.LUXGRAM_TOGGLE_SECRETARY";
    public static final String ACTION_OPEN_VAULT = "org.telegram.messenger.LUXGRAM_OPEN_VAULT";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            manager.updateAppWidget(id, buildViews(context));
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_TOGGLE_SECRETARY.equals(action)) {
            try {
                int account = UserConfig.selectedAccount;
                LuxGramSecretary secretary = LuxGramSecretary.getInstance(account);
                secretary.setEnabled(!secretary.isEnabled());
            } catch (Throwable ignore) {
            }
        } else if (ACTION_OPEN_VAULT.equals(action)) {
            try {
                Intent launch = new Intent(context, org.telegram.ui.LaunchActivity.class);
                launch.putExtra("luxgram_open_vault", true);
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                context.startActivity(launch);
            } catch (Throwable ignore) {
            }
        }
        super.onReceive(context, intent);
        refreshAll(context);
    }

    public static void notifyStateChanged(Context context) {
        refreshAll(context);
    }

    private static void refreshAll(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, LuxGramToolsWidget.class));
            for (int id : ids) {
                manager.updateAppWidget(id, buildViews(context));
            }
        } catch (Throwable ignore) {
        }
    }

    private static RemoteViews buildViews(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.luxgram_widget_layout);

        Intent toggle = new Intent(context, LuxGramToolsWidget.class);
        toggle.setAction(ACTION_TOGGLE_SECRETARY);
        views.setOnClickPendingIntent(R.id.luxgram_widget_secretary, PendingIntent.getBroadcast(
                context, 11, toggle, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        Intent openVault = new Intent(context, LuxGramToolsWidget.class);
        openVault.setAction(ACTION_OPEN_VAULT);
        views.setOnClickPendingIntent(R.id.luxgram_widget_vault, PendingIntent.getBroadcast(
                context, 12, openVault, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        try {
            boolean on = LuxGramSecretary.getInstance(UserConfig.selectedAccount).isEnabled();
            views.setTextViewText(R.id.luxgram_widget_secretary,
                    on ? "منشی  ✓" : "منشی");
            views.setTextColor(R.id.luxgram_widget_secretary, on ? 0xFFF4D27A : 0xFFF1EADA);
        } catch (Throwable ignore) {
        }
        return views;
    }
}
