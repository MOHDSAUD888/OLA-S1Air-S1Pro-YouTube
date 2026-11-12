package dev.olatool;

import android.widget.RemoteViews;

/**
 * Speed Widget — speed, RPM, and the three temperatures.
 */
public final class SpeedWidget extends WidgetBase {

    @Override protected int layoutResId() { return R.layout.widget_speed; }

    @Override
    protected void fill(RemoteViews rv, WidgetData.Snapshot s) {
        rv.setTextViewText(R.id.speed_value, fmt(s.speedKph));
        rv.setTextViewText(R.id.rpm_value, fmt(s.rpm) + " rpm");
        rv.setTextViewText(R.id.pack_temp, fmt(s.packC) + "°");
        rv.setTextViewText(R.id.motor_temp, fmt(s.motorC) + "°");
        rv.setTextViewText(R.id.mcu_temp, fmt(s.mcuC) + "°");
        String mode = (s.driveMode == null || s.driveMode.isEmpty()) ? "—" : s.driveMode;
        rv.setTextViewText(R.id.drive_mode, mode);
    }
}
