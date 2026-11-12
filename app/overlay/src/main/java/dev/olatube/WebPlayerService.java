package dev.olatube;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A self-owned floating YouTube player: a WebView in a system-overlay window we
 * fully control, so unlike SmartTube's native PIP we can move it, resize it,
 * toggle fullscreen and close it on demand — and the control buttons never
 * disappear, because they are our own views inside the window.
 *
 * Drag the title bar to move; drag the bottom-right grip to resize; ⛶ toggles
 * fullscreen; ✕ closes. YouTube itself is the mobile site (no GMS needed).
 */
public class WebPlayerService extends Service {

    static WebPlayerService INSTANCE;
    private static final String CH = "yt_player";
    private static final String URL = "https://m.youtube.com";
    private static final int BAR  = 44;   // control-bar height, px
    private static final int GRIP = 44;   // resize handle, px
    private static final int MINW = 220, MINH = 160;

    private WindowManager wm;
    private FrameLayout root;
    private WebView web;
    private WindowManager.LayoutParams lp;

    // Floating geometry, remembered so fullscreen can be undone.
    private int floatW = 560, floatH = 340, floatX = 120, floatY = 60;
    private boolean fullscreen = false;

    static void start(Context c) {
        Intent i = new Intent(c, WebPlayerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
        else c.startService(i);
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, WebPlayerService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        goForeground();
        try {
            build();
        } catch (Throwable t) {
            // This dash ships no Android System WebView provider, so WebView
            // cannot be created. Fail softly instead of crashing the whole app.
            android.widget.Toast.makeText(this,
                    "No WebView on this device - player unavailable",
                    android.widget.Toast.LENGTH_LONG).show();
            stopSelf();
        }
    }

    private void goForeground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(
                CH, "YouTube player", NotificationManager.IMPORTANCE_MIN));
        startForeground(2, new Notification.Builder(this, CH)
                .setContentTitle("YouTube player")
                .setContentText("Floating player active")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build());
    }

    private int overlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void build() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // --- WebView (fills below the bar) ---
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);  // allow tap-to-play / autoplay
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.setWebViewClient(new WebViewClient());      // keep navigation inside the WebView
        web.setWebChromeClient(new WebChromeClient());  // HTML5 video + progress
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        wlp.topMargin = BAR;
        root.addView(web, wlp);
        web.loadUrl(URL);

        // --- Control bar (top) ---
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        barBackgroundColor(Ui.PLAYER_BAR);

        TextView title = new TextView(this);
        title.setText("  YouTube  ☰ drag");
        title.setTextColor(Color.WHITE);
        title.setTextSize(13);
        title.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(title, new LinearLayout.LayoutParams(0, BAR, 1f));

        TextView full  = barButton("⛶");  // ⛶ fullscreen toggle
        TextView close = barButton("✕");  // ✕ close
        bar.addView(full,  new LinearLayout.LayoutParams(BAR + 12, BAR));
        bar.addView(close, new LinearLayout.LayoutParams(BAR + 12, BAR));

        root.addView(bar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, BAR));

        // --- Resize grip (bottom-right) ---
        TextView grip = barButton("◢");   // ◢
        gripBackgroundColor(Ui.PLAYER_GRIP);
        root.addView(grip, new FrameLayout.LayoutParams(
                GRIP, GRIP, Gravity.BOTTOM | Gravity.END));

        // --- Window ---
        lp = new WindowManager.LayoutParams(
                floatW, floatH, overlayType(),
                // focusable (WebView needs input) but NOT_TOUCH_MODAL so taps
                // outside the player still reach the dash below.
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = floatX;
        lp.y = floatY;
        wm.addView(root, lp);

        // --- Interactions ---
        title.setOnTouchListener(dragMover());
        grip.setOnTouchListener(resizer());
        full.setOnClickListener(v -> toggleFullscreen());
        close.setOnClickListener(v -> stopSelf());
    }

    private TextView barButton(String t) {
        TextView b = new TextView(this);
        b.setText(t);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setGravity(Gravity.CENTER);
        bBackgroundColor(Ui.PLAYER_CAP);
        return b;
    }

    /** Drag the title bar to move the window. */
    private View.OnTouchListener dragMover() {
        return new View.OnTouchListener() {
            float downX, downY; int origX, origY;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        origX = lp.x; origY = lp.y; return true;
                    case MotionEvent.ACTION_MOVE:
                        if (fullscreen) return true;
                        lp.x = origX + (int) (e.getRawX() - downX);
                        lp.y = origY + (int) (e.getRawY() - downY);
                        wm.updateViewLayout(root, lp); return true;
                }
                return false;
            }
        };
    }

    /** Drag the bottom-right grip to resize. */
    private View.OnTouchListener resizer() {
        return new View.OnTouchListener() {
            float downX, downY; int origW, origH;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        origW = lp.width; origH = lp.height; return true;
                    case MotionEvent.ACTION_MOVE:
                        if (fullscreen) return true;
                        lp.width  = Math.max(MINW, origW + (int) (e.getRawX() - downX));
                        lp.height = Math.max(MINH, origH + (int) (e.getRawY() - downY));
                        wm.updateViewLayout(root, lp); return true;
                }
                return false;
            }
        };
    }

    private void toggleFullscreen() {
        if (!fullscreen) {
            floatW = lp.width; floatH = lp.height; floatX = lp.x; floatY = lp.y;
            lp.width = WindowManager.LayoutParams.MATCH_PARENT;
            lp.height = WindowManager.LayoutParams.MATCH_PARENT;
            lp.x = 0; lp.y = 0;
            fullscreen = true;
        } else {
            lp.width = floatW; lp.height = floatH; lp.x = floatX; lp.y = floatY;
            fullscreen = false;
        }
        wm.updateViewLayout(root, lp);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;  // do not auto-relaunch the player
    }

    @Override
    public void onDestroy() {
        if (root != null && wm != null) wm.removeView(root);
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        if (INSTANCE == this) INSTANCE = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
