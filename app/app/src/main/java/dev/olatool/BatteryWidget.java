package dev.olatool;

import android.widget.RemoteViews;

/**
 * Battery Widget — SoC, SoH, range, and charging status.
 */
public final class BatteryWidget extends WidgetBase {

    @Override protected int layoutResId() { return R.layout.widget_battery; }

    @Override
    protected void fill(RemoteViews rv, WidgetData.Snapshot s) {
        rv.setTextViewText(R.id.soc_value, fmt(s.soc));
        rv.setTextViewText(R.id.soh_value, fmt(s.soh));
        rv.setTextViewText(R.id.range_value, fmt(s.rangeKm) + " km");

        if (s.isCharging()) {
            rv.setTextViewText(R.id.charge_status, "Charging");
            rv.setTextColor(R.id.charge_status, Ui.ACCENT);
        } else if (s.isPlugged()) {
            rv.setTextViewText(R.id.charge_status, "Plugged");
            rv.setTextColor(R.id.charge_status, Ui.WARN);
        } else {
            rv.setTextViewText(R.id.charge_status, "Not Charging");
            rv.setTextColor(R.id.charge_status, Ui.DIM);
        }
    }
}
