package dev.olatool;

import android.content.Context;
import android.content.Intent;

/**
 * Helpers for building the pending intents a widget needs.
 */
public final class PendingUtils {
    private PendingUtils() {}

    public static android.app.PendingIntent open(Context c, Intent in) {
        return android.app.PendingIntent.getActivity(c, 0, in,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
                        | android.app.PendingIntent.FLAG_IMMUTABLE);
    }
}
