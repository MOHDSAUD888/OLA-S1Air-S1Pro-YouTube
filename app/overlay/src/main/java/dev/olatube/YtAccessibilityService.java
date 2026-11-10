package dev.olatube;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * Does two things that a plain overlay app cannot:
 *
 *  1. Reports which app is in the foreground (via window-state changes) so
 *     {@link OverlayService} can swap between the dash launch zone and the
 *     SmartTube floating controls.
 *
 *  2. Performs BACK on the foreground app for the floating controls — the only
 *     no-root way to send a key to another app. CLOSE is just two BACKs: exit
 *     the player, then exit SmartTube, which drops back to the dash.
 *
 * The user must enable this once in Settings > Accessibility ("YT Zone").
 * Without it the centre launch zone still works, but back/close and the
 * auto-hide of the zone over the video do not.
 */
public class YtAccessibilityService extends AccessibilityService {

    static YtAccessibilityService INSTANCE;

    private final Handler h = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
        classify();  // catch SmartTube already open (fullscreen or PIP) on connect
    }

    /**
     * On any window change, decide what SmartTube is doing and tell the overlay:
     *   - a large SmartTube window  -> fullscreen  -> show back/close controls
     *   - a small SmartTube window  -> PIP player  -> show the maximize tap zone
     *   - no SmartTube window        -> leave the dash to the CLOSE button
     *     (the Kanzi dash fires no event, and we must not strip controls on the
     *      dash's own IME/dialog events).
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int t = event.getEventType();
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || t == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            classify();
        }
    }

    private void classify() {
        List<AccessibilityWindowInfo> ws;
        try {
            ws = getWindows();
        } catch (Exception e) {
            return;
        }
        if (ws == null) return;
        Rect r = new Rect();
        Rect pip = null;
        boolean full = false;
        for (AccessibilityWindowInfo w : ws) {
            if (w == null) continue;
            String pkg = null;
            try {
                AccessibilityNodeInfo root = w.getRoot();
                if (root != null && root.getPackageName() != null) {
                    pkg = root.getPackageName().toString();
                }
            } catch (Exception ignored) { }
            if (!OverlayService.YT_PACKAGE.equals(pkg)) continue;
            w.getBoundsInScreen(r);
            if (r.width() > 0 && r.width() <= 500) pip = new Rect(r);  // mini player
            else if (r.width() > 500) full = true;                    // fullscreen
        }
        // SmartTube present (fullscreen OR PIP) -> keep the back/close controls
        // visible. Only when no SmartTube window remains do we return to dash.
        if (pip != null || full) OverlayService.showControls();
        else OverlayService.backToDash();
    }

    /** Normal back within SmartTube (video -> browse, sub-screen -> parent). */
    void doBack() {
        performGlobalAction(GLOBAL_ACTION_BACK);
    }

    /**
     * Close = go to the dash. First BACK (player -> browse) to STOP playback, so
     * the following HOME cannot trigger SmartTube's PIP; then HOME (browse ->
     * dash). Restore the dash overlay and reclaim SmartTube's RAM.
     */
    void doClose() {
        performGlobalAction(GLOBAL_ACTION_BACK);              // stop playback
        OverlayService.backToDash();                         // restore dash overlay
        h.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_HOME), 300);  // -> dash
        h.postDelayed(() -> {
            try {
                ActivityManager am =
                        (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) am.killBackgroundProcesses(OverlayService.YT_PACKAGE);
            } catch (Exception ignored) { }
        }, 700);
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        if (INSTANCE == this) INSTANCE = null;
        super.onDestroy();
    }
}
