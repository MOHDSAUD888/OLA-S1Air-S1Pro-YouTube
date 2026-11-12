package dev.olatool;

import android.widget.RemoteViews;

/**
 * Fleet Widget — how many scooters are registered and how many are online.
 */
public final class FleetWidget extends WidgetBase {

    @Override protected int layoutResId() { return R.layout.widget_fleet; }

    @Override
    protected void fill(RemoteViews rv, WidgetData.Snapshot s) {
        int total = s.fleetCount;
        int online = s.fleetOnline;
        int offline = Math.max(0, total - online);
        rv.setTextViewText(R.id.total_count, String.valueOf(total));
        rv.setTextViewText(R.id.online_count, online + " online");
        rv.setTextViewText(R.id.offline_count, "(" + offline + " offline)");

        if (total == 0) {
            rv.setTextViewText(R.id.status_text, "No scooters added");
            rv.setTextColor(R.id.status_text, Ui.DIM);
        } else if (online > 0) {
            rv.setTextViewText(R.id.status_text, online + " of " + total + " reachable");
            rv.setTextColor(R.id.status_text, Ui.ACCENT);
        } else {
            rv.setTextViewText(R.id.status_text, "All offline");
            rv.setTextColor(R.id.status_text, Ui.WARN);
        }
        int dotColor = (online > 0) ? Ui.ACCENT : ((total > 0) ? Ui.WARN : Ui.LINE);
        rv.setInt(R.id.online_dot, "setColorFilter", dotColor);
    }
}
