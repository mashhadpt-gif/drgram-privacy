/*
 * This is the source code of DrGram, a Telegram fork.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * DrGram Quick Chat widget (Fix 80) — home-screen shortcuts next to the
 * existing Tools widget: open the app, jump to Quick Replies, jump to
 * Saved Messages. Pure RemoteViews + broadcast extras, same proven pattern
 * as DrGramToolsWidget (Fix 20).
 */
package org.telegram.messenger;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public class DrGramQuickChatWidget extends AppWidgetProvider {

    public static final String ACTION_OPEN_REPLIES = "org.telegram.messenger.DRGRAM_OPEN_REPLIES";
    public static final String ACTION_OPEN_SAVED = "org.telegram.messenger.DRGRAM_OPEN_SAVED";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        for (int id : appWidgetIds) {
            manager.updateAppWidget(id, buildViews(context));
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_OPEN_REPLIES.equals(action) || ACTION_OPEN_SAVED.equals(action)) {
            try {
                Intent launch = new Intent(context, org.telegram.ui.LaunchActivity.class);
                if (ACTION_OPEN_REPLIES.equals(action)) {
                    launch.putExtra("drgram_open_replies", true);
                } else {
                    launch.putExtra("drgram_open_saved", true);
                }
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                context.startActivity(launch);
            } catch (Throwable ignore) {
            }
        }
        super.onReceive(context, intent);
        refreshAll(context);
    }

    private static void refreshAll(Context context) {
        try {
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, DrGramQuickChatWidget.class));
            for (int id : ids) {
                manager.updateAppWidget(id, buildViews(context));
            }
        } catch (Throwable ignore) {
        }
    }

    private static RemoteViews buildViews(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.drgram_widget_quickchat);

        Intent openApp = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (openApp == null) {
            openApp = new Intent(context, org.telegram.ui.LaunchActivity.class);
        }
        openApp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        views.setOnClickPendingIntent(R.id.drgram_qw_open, PendingIntent.getActivity(
                context, 21, openApp, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        Intent replies = new Intent(context, DrGramQuickChatWidget.class);
        replies.setAction(ACTION_OPEN_REPLIES);
        views.setOnClickPendingIntent(R.id.drgram_qw_replies, PendingIntent.getBroadcast(
                context, 22, replies, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        Intent saved = new Intent(context, DrGramQuickChatWidget.class);
        saved.setAction(ACTION_OPEN_SAVED);
        views.setOnClickPendingIntent(R.id.drgram_qw_saved, PendingIntent.getBroadcast(
                context, 23, saved, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        views.setTextViewText(R.id.drgram_qw_title, LocaleController.getString(R.string.DrGramWidgetChatTitle));
        views.setTextViewText(R.id.drgram_qw_open, LocaleController.getString(R.string.DrGramWidgetChatOpen));
        views.setTextViewText(R.id.drgram_qw_replies, LocaleController.getString(R.string.DrGramWidgetReplies));
        views.setTextViewText(R.id.drgram_qw_saved, LocaleController.getString(R.string.DrGramWidgetSaved));
        return views;
    }
}
