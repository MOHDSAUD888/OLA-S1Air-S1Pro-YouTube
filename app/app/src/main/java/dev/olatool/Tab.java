package dev.olatool;

import android.view.View;

/** One of the five screens. */
public interface Tab {
    View view();

    /** Called when the tab becomes visible. Start timers here. */
    void onShow();

    /** Called when the tab is replaced. Stop timers here. */
    void onHide();
}
