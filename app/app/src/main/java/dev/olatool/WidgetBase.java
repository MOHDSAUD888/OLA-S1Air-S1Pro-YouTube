package dev.olatool;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Base for the Pegasus widgets.
 *
 * <p>A widget only shows what {@link WidgetData} last saved; it never talks to
 * the scooter. The app calls {@link #refreshAll} after saving a new reading.
 */
public abstract class WidgetBase extends AppWidgetProvider {

    protected abstract int layoutResId();
    protected abstract void fill(RemoteViews rv, WidgetData.Snapshot s);

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        push(context, manager, ids);
    }

    /** Redraws every placed widget from the saved data. Cheap when none are placed. */
    public static void refreshAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        WidgetBase[] all = {new BatteryWidget(), new SpeedWidget(), new FleetWidget()};
        for (WidgetBase w : all) {
            int[] ids = manager.getAppWidgetIds(new ComponentName(context, w.getClass()));
            if (ids.length > 0) {
                w.push(context, manager, ids);
            }
        }
    }

    private void push(Context context, AppWidgetManager manager, int[] ids) {
        WidgetData.Snapshot s = WidgetData.read(context);
        RemoteViews rv = new RemoteViews(context.getPackageName(), layoutResId());
        fill(rv, s);
        rv.setTextViewText(R.id.widget_footer, s.updatedAt > 0
                ? "as of " + new SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
                        .format(new Date(s.updatedAt))
                : "Open Pegasus to load data");
        Intent open = new Intent(context, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        rv.setOnClickPendingIntent(R.id.widget_root, PendingUtils.open(context, open));
        manager.updateAppWidget(ids, rv);
    }

    protected static String fmt(float value) {
        if (Float.isNaN(value)) return "—";
        return String.format(Locale.US, "%.0f", value);
    }
}
