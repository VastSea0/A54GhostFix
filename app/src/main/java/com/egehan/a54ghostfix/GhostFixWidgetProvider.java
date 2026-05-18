package com.egehan.a54ghostfix;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

public class GhostFixWidgetProvider extends AppWidgetProvider {
    protected int getLayoutRes() {
        return R.layout.ghost_fix_widget;
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) {
            appWidgetManager.updateAppWidget(appWidgetId, buildViews(context));
        }
    }

    static void updateAll(Context context) {
        updateProvider(context, GhostFixWidgetProvider.class, new GhostFixWidgetProvider());
        updateProvider(context, GhostFixWidgetCompactProvider.class, new GhostFixWidgetCompactProvider());
        updateProvider(context, GhostFixWidgetLargeProvider.class, new GhostFixWidgetLargeProvider());
    }

    private static void updateProvider(
            Context context,
            Class<? extends GhostFixWidgetProvider> providerClass,
            GhostFixWidgetProvider widgetProvider
    ) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName provider = new ComponentName(context, providerClass);
        manager.updateAppWidget(provider, widgetProvider.buildViews(context));
    }

    protected RemoteViews buildViews(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), getLayoutRes());
        Intent intent = new Intent(context, GhostFixReceiver.class);
        intent.setAction(GhostFixReceiver.ACTION_FIX);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context,
                getLayoutRes(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widget_root, pendingIntent);
        return views;
    }
}
