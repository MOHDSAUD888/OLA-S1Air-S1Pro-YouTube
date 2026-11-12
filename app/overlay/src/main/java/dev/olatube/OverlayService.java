package dev.olatube;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Two jobs:
 *
 *  1. On the dash (Kanzi) home/drive screen, show an invisible tap zone placed
 *     over the clock at the bottom-right. Seven fast taps launch SmartTube.
 *
 *  2. While SmartTube is in the foreground, show two faint, semi-transparent
 *     controls that float on top of the video: BACK (top-left) and CLOSE
 *     (top-right). They act through {@link YtAccessibilityService}
 *     (performGlobalAction), the only no-root way to send BACK to another app.
 *
 * The accessibility service also tells us which app is in front, so the centre
 * launch zone is removed while SmartTube plays (otherwise it would eat taps in
 * the middle of the video) and re-added when the dash returns.
 *
 * Why an overlay and not a Kanzi launcher tile: the home screen is a compiled
 * .kzb UI with no Android views and no startActivity action, so a tile cannot
 * start an app. A system overlay window is the only route.
 */
public class OverlayService extends Service {

    // ---- Tunables ----------------------------------------------------------
    /** SmartTube Next package — the YouTube client, since the dash has no GMS. */
    static final String YT_PACKAGE = "org.smarttube.stable";
    /** Explicit entry point, used if no launcher intent is registered. */
    private static final String YT_ACTIVITY =
            "com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity";

    /** Launch zone size in px (dash panel is 800x480). */
    private static final int LAUNCH_ZONE_PX = 120;
    /** The zone sits over the clock at the bottom-right of the dash. With
     *  gravity BOTTOM|END these are margins from that corner: X_MARGIN = px in
     *  from the right edge, Y_MARGIN = px up from the bottom edge. Tune them
     *  after seeing the zone land on the time. */
    private static final int LAUNCH_X_MARGIN = 0;
    private static final int LAUNCH_Y_MARGIN = 0;

    /** Taps needed to launch, and the max span for the whole run. */
    private static final int  TAPS_TO_LAUNCH = 7;
    private static final long TAP_WINDOW_MS  = 2500;

    /** How long the YouTube logo flashes after the launch tap, then hides. */
    private static final long LOGO_FLASH_MS = 1400;

    /** SmartTube control buttons: half the old size, placed side-by-side at the
     *  screen centre with a small gap between them. */
    private static final int CTRL_PX  = 48;
    private static final int CTRL_GAP = 16;
    /** Distance of the control row from the top edge, in px. */
    private static final int CTRL_TOP = 8;

    private static final String CHANNEL_ID = "yt_zone";

    static OverlayService INSTANCE;

    /** Prefs file + key for the 7-tap lock, shared with {@link SetupActivity}. */
    static final String PREFS = "yt";
    static final String KEY_LOCKED = "locked";

    private WindowManager wm;
    private TileView zone;                 // launch zone (dash only)
    private ControlView backBtn, closeBtn; // fullscreen SmartTube controls
    private boolean zoneAdded, controlsAdded;

    /** When true, the 7-tap gesture is disabled (Pegasus "Lock 7-tap"). The tap
     *  is still consumed so it never leaks to Kanzi, but YouTube is not launched.
     *  Persisted in {@link #PREFS} so it survives a reboot / service restart. */
    private volatile boolean locked;

    private final android.os.Handler ui =
            new android.os.Handler(android.os.Looper.getMainLooper());

    // Rolling record of the last up-to-N tap times.
    private final long[] taps = new long[TAPS_TO_LAUNCH];
    private int tapCount = 0;

    static void start(Context ctx) {
        Intent i = new Intent(ctx, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    /** Backup signal from {@link YtAccessibilityService} on window changes. */
    static void notifyForeground(String pkg) {
        OverlayService s = INSTANCE;
        if (s != null) s.applyForeground(pkg);
    }

    /** Deterministic switch back to the dash (called by CLOSE). The Kanzi dash
     *  fires no accessibility event when it returns, so we cannot wait for one. */
    static void backToDash() {
        OverlayService s = INSTANCE;
        if (s != null) s.enterDashState();
    }

    /** Lock or unlock the 7-tap launch. Persists the flag (so it survives a
     *  reboot) and, if the service is running, applies it immediately. Called by
     *  {@link SetupActivity} from the {@code -e feature lock|unlock} command. */
    static void applyLock(Context ctx, boolean lock) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_LOCKED, lock).apply();
        OverlayService s = INSTANCE;
        if (s != null) s.locked = lock;
    }

    /** Show the controls whenever SmartTube is in front, however it was opened
     *  (called by the a11y service on a SmartTube window event). Safe: only
     *  SmartTube events reach here, so the dash's IME/dialogs never remove them. */
    static void showControls() {
        OverlayService s = INSTANCE;
        if (s != null) s.enterSmartTubeState();
    }


    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        // Restore the lock state set before the last reboot / service restart.
        locked = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_LOCKED, false);
        goForeground();
        // Assume the dash is in front at start; the a11y service corrects this.
        addZone();
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private WindowManager.LayoutParams baseLp(int w, int h) {
        // NOT_FOCUSABLE (mirrors the known-working launch zone): the window
        // still receives real finger touches inside its own small rect, and
        // touches elsewhere reach the app below since the window only covers
        // that rect. (Injected `input tap` events do NOT reach it on this ROM,
        // so these controls can only be exercised by a real finger.)
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        return lp;
    }

    // ---- Centre launch zone (dash only) ------------------------------------

    private void addZone() {
        if (zoneAdded) return;
        if (zone == null) {
            zone = new TileView(this);
            zone.setOnTouchListener(this::onZoneTouch);
        }
        WindowManager.LayoutParams lp = baseLp(LAUNCH_ZONE_PX, LAUNCH_ZONE_PX);
        lp.gravity = Gravity.BOTTOM | Gravity.END;   // over the clock
        lp.x = LAUNCH_X_MARGIN;
        lp.y = LAUNCH_Y_MARGIN;
        wm.addView(zone, lp);
        zoneAdded = true;
    }

    private void removeZone() {
        if (!zoneAdded) return;
        wm.removeView(zone);
        zoneAdded = false;
        tapCount = 0;
    }

    private boolean onZoneTouch(View v, MotionEvent e) {
        if (e.getAction() != MotionEvent.ACTION_DOWN) return false;

        long now = SystemClock.uptimeMillis();
        // Reset the run if the previous tap is stale.
        if (tapCount > 0 && now - taps[tapCount - 1] > TAP_WINDOW_MS) {
            tapCount = 0;
        }
        taps[tapCount] = now;
        tapCount++;

        if (tapCount == TAPS_TO_LAUNCH) {
            boolean inWindow = now - taps[0] <= TAP_WINDOW_MS;
            tapCount = 0;
            if (inWindow) {
                if (locked) return true;      // 7-tap locked: swallow, do not launch
                zone.setShowLogo(true);       // brief confirmation flash
                launchYouTube();
                ui.removeCallbacksAndMessages(null);
                ui.postDelayed(() -> { if (zone != null) zone.setShowLogo(false); },
                        LOGO_FLASH_MS);
            }
        }
        return true; // consume, so taps never leak to Kanzi below
    }

    private void launchYouTube() {
        Intent i = getPackageManager().getLaunchIntentForPackage(YT_PACKAGE);
        if (i == null) {
            i = new Intent(Intent.ACTION_MAIN);
            i.setClassName(YT_PACKAGE, YT_ACTIVITY);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
            // Deterministic: we launched SmartTube, so switch to controls once
            // it is up (do not wait for an a11y event that the dash never sends).
            ui.postDelayed(this::enterSmartTubeState, 900);
        } catch (Exception ex) {
            Toast.makeText(this, "SmartTube not installed", Toast.LENGTH_SHORT).show();
        }
    }

    // ---- SmartTube floating controls ---------------------------------------

    private void addControls() {
        if (controlsAdded) return;
        if (backBtn == null) {
            backBtn = new ControlView(this, ControlView.BACK);
            backBtn.setOnTouchListener((v, e) -> {
                if (e.getAction() != MotionEvent.ACTION_DOWN) return false;
                YtAccessibilityService a = YtAccessibilityService.INSTANCE;
                if (a != null) a.doBack(); else toastEnableA11y();
                return true;
            });
        }
        if (closeBtn == null) {
            closeBtn = new ControlView(this, ControlView.CLOSE);
            closeBtn.setOnTouchListener((v, e) -> {
                if (e.getAction() != MotionEvent.ACTION_DOWN) return false;
                YtAccessibilityService a = YtAccessibilityService.INSTANCE;
                if (a != null) a.doClose(); else toastEnableA11y();
                return true;
            });
        }
        // Both at the TOP-MIDDLE, side by side: BACK just left of centre,
        // CLOSE just right, separated by CTRL_GAP.
        int off = CTRL_PX / 2 + CTRL_GAP / 2;

        WindowManager.LayoutParams lb = baseLp(CTRL_PX, CTRL_PX);
        lb.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lb.x = -off; lb.y = CTRL_TOP;   // back: left of top-centre

        WindowManager.LayoutParams lc = baseLp(CTRL_PX, CTRL_PX);
        lc.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lc.x = off; lc.y = CTRL_TOP;    // close: right of top-centre

        wm.addView(backBtn, lb);
        wm.addView(closeBtn, lc);
        controlsAdded = true;
    }

    private void removeControls() {
        if (!controlsAdded) return;
        wm.removeView(backBtn);
        wm.removeView(closeBtn);
        controlsAdded = false;
    }

    private void toastEnableA11y() {
        Toast.makeText(this,
                "Enable 'YT Zone' in Settings > Accessibility for back/close",
                Toast.LENGTH_LONG).show();
    }

    /** Backup only: a11y foreground signal (unreliable for the Kanzi dash). */
    void applyForeground(final String pkg) {
        if (YT_PACKAGE.equals(pkg)) enterSmartTubeState();
        else enterDashState();
    }

    /** SmartTube fullscreen: hide the launch zone, show the floating controls. */
    void enterSmartTubeState() {
        ui.post(() -> { removeZone(); addControls(); });
    }

    /** Back on the dash: hide the controls, restore the launch zone. */
    void enterDashState() {
        ui.post(() -> { removeControls(); addZone(); });
    }

    // ---- Service plumbing ---------------------------------------------------

    private void goForeground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "YouTube zone", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("YouTube zone active")
                .setContentText("Seven-tap the centre to launch")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build();
        startForeground(1, n);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        removeZone();
        removeControls();
        if (INSTANCE == this) INSTANCE = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- Views (drawn in code, no PNG assets) ------------------------------

    /**
     * The centre launch zone. Invisible by default (draws nothing) but still
     * receives touches; flashes the YouTube logo for a moment after launch.
     */
    private static final class TileView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path triangle = new Path();
        private final RectF rect = new RectF();
        private boolean showLogo = false;

        TileView(Context c) { super(c); }

        void setShowLogo(boolean show) {
            if (showLogo != show) { showLogo = show; invalidate(); }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (!showLogo) return;   // invisible tap zone
            float w = getWidth(), h = getHeight();
            float pad = w * 0.10f;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Ui.OVERLAY_PLATE);
            rect.set(pad * 0.3f, pad * 0.3f, w - pad * 0.3f, h - pad * 0.3f);
            canvas.drawRoundRect(rect, w * 0.22f, w * 0.22f, paint);

            paint.setColor(Ui.OVERLAY_RED);
            float ry = h * 0.30f;
            rect.set(pad, ry, w - pad, h - ry);
            float r = (h - 2 * ry) * 0.35f;
            canvas.drawRoundRect(rect, r, r, paint);

            paint.setColor(0xFFFFFFFF);
            float cx = w / 2f, cy = h / 2f;
            float tw = (w - 2 * pad) * 0.18f;
            float th = (h - 2 * ry) * 0.26f;
            triangle.reset();
            triangle.moveTo(cx - tw, cy - th);
            triangle.lineTo(cx - tw, cy + th);
            triangle.lineTo(cx + tw * 1.4f, cy);
            triangle.close();
            canvas.drawPath(triangle, paint);
        }
    }

    /**
     * A faint, semi-transparent control that floats over SmartTube: either a
     * BACK chevron or a CLOSE cross. Low alpha so it marks the spot without
     * blocking the video behind it.
     */
    private static final class ControlView extends View {
        static final int BACK = 0, CLOSE = 1;
        private final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ink   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final int kind;

        ControlView(Context c, int kind) {
            super(c);
            this.kind = kind;
            setClickable(true);
            plate.setStyle(Paint.Style.FILL);
            plate.setColor(Ui.OVERLAY_BLACK);          // ~67% black backing (clearly visible)
            ink.setStyle(Paint.Style.STROKE);
            ink.setColor(0xFFFFFFFF);            // solid white icon
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            float cx = w / 2f, cy = h / 2f;
            float r = Math.min(w, h) / 2f;
            canvas.drawCircle(cx, cy, r * 0.92f, plate);

            ink.setStrokeWidth(r * 0.14f);
            float d = r * 0.40f;
            if (kind == CLOSE) {
                canvas.drawLine(cx - d, cy - d, cx + d, cy + d, ink);
                canvas.drawLine(cx - d, cy + d, cx + d, cy - d, ink);
            } else { // BACK chevron
                path.reset();
                path.moveTo(cx + d * 0.6f, cy - d);
                path.lineTo(cx - d * 0.6f, cy);
                path.lineTo(cx + d * 0.6f, cy + d);
                canvas.drawPath(path, ink);
            }
        }
    }
}
