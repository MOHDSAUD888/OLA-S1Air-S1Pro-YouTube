package dev.olatool;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A real street map (OpenStreetMap raster tiles) with the scooter drawn on it.
 * Framework-only: tiles come over HttpURLConnection, are cached in memory and
 * on disk, and are drawn with fractional zoom. Drag to pan, pinch or
 * double-tap to zoom. A new fix glides the scooter marker to its new spot and
 * the camera follows it until the user pans away (the target button re-follows).
 */
public final class MapView extends View {

    private static final String TILE_URL = "https://tile.openstreetmap.org/%d/%d/%d.png";
    private static final String UA = "dev.olatool/1.8 (personal scooter tracker; Android)";
    private static final int MIN_Z = 3;
    private static final int MAX_Z = 19;
    private static final long DISK_TTL_MS = 14L * 24 * 3600 * 1000;

    private final float d;
    private final float tileBase;                      // on-screen px of a 256 tile at integer zoom
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Set<String> pending = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> failed = Collections.synchronizedSet(new HashSet<>());
    private final LruCache<String, Bitmap> mem;
    private final File disk;

    private final Paint tilePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint bg = new Paint();
    private final Paint trailPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trailHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pulse = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pinFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pinEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inkFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arrow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint meFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint meEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnInk = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint attr = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint attrBg = new Paint();
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    // camera: center in normalised world coords (0..1), continuous zoom
    private double cx = 0.5;
    private double cy = 0.5;
    private double zoom = 16;
    private boolean follow = true;
    private boolean hasCamera;

    // scooter marker (displayed position glides toward the latest fix)
    private double mx = Double.NaN;
    private double my = Double.NaN;
    private double fromX;
    private double fromY;
    private double toX;
    private double toY;
    private float bearing = Float.NaN;
    private double accM = Double.NaN;
    private ValueAnimator glide;

    private double meX = Double.NaN;
    private double meY = Double.NaN;
    private double[][] trailNorm = new double[0][];
    private long bornAt = System.currentTimeMillis();
    private boolean live;

    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;

    // in-map buttons (screen rects, computed in onDraw)
    private final RectF btnFollow = new RectF();
    private final RectF btnIn = new RectF();
    private final RectF btnOut = new RectF();

    public MapView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        tileBase = 256f * Math.max(1f, d * 0.75f);
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        mem = new LruCache<String, Bitmap>(Math.max(8 * 1024, maxKb / 8)) {
            @Override
            protected int sizeOf(String k, Bitmap b) {
                return b.getByteCount() / 1024;
            }
        };
        disk = new File(c.getCacheDir(), "tiles");
        //noinspection ResultOfMethodCallIgnored
        disk.mkdirs();

        bg.setColor(0xFFE8E4DC);
        trailPaint.setStyle(Paint.Style.STROKE);
        trailPaint.setStrokeWidth(4f * d);
        trailPaint.setStrokeCap(Paint.Cap.ROUND);
        trailPaint.setStrokeJoin(Paint.Join.ROUND);
        trailPaint.setColor(0xFF1E88E5);
        trailHalo.set(trailPaint);
        trailHalo.setStrokeWidth(7f * d);
        trailHalo.setColor(0x99FFFFFF);
        accFill.setColor(Ui.alpha(Ui.ACCENT, 0x30));
        accEdge.setStyle(Paint.Style.STROKE);
        accEdge.setStrokeWidth(1.5f * d);
        accEdge.setColor(Ui.alpha(Ui.ACCENT, 0x90));
        pulse.setStyle(Paint.Style.STROKE);
        pulse.setStrokeWidth(3f * d);
        pinFill.setColor(0xFFFFFFFF);
        pinEdge.setStyle(Paint.Style.STROKE);
        pinEdge.setStrokeWidth(3f * d);
        pinEdge.setColor(0xFF0B8F6B);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(2.2f * d);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        ink.setColor(0xFF111418);
        inkFill.setColor(0xFF111418);
        arrow.setColor(0xFF0B8F6B);
        meFill.setColor(0xFF1E88E5);
        meEdge.setStyle(Paint.Style.STROKE);
        meEdge.setStrokeWidth(2.5f * d);
        meEdge.setColor(0xFFFFFFFF);
        btnFill.setColor(0xF2FFFFFF);
        btnDot.setColor(0xFF0B8F6B);
        btnEdge.setStyle(Paint.Style.STROKE);
        btnEdge.setStrokeWidth(1f * d);
        btnEdge.setColor(0x33000000);
        btnInk.setColor(0xFF333B44);
        btnInk.setStyle(Paint.Style.STROKE);
        btnInk.setStrokeWidth(2.2f * d);
        btnInk.setStrokeCap(Paint.Cap.ROUND);
        attr.setColor(0xFF333333);
        attr.setTextSize(9.5f * d);
        attrBg.setColor(0xB3FFFFFF);
        hint.setColor(0xFF555555);
        hint.setTextSize(13f * d);
        hint.setTextAlign(Paint.Align.CENTER);

        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector s) {
                zoomAround(Math.log(s.getScaleFactor()) / Math.log(2), s.getFocusX(), s.getFocusY());
                return true;
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                double world = worldPx();
                cx = wrap(cx + dx / world);
                cy = clamp(cy + dy / world, 0, 1);
                follow = false;
                invalidate();
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                float x = e.getX();
                float y = e.getY();
                if (btnFollow.contains(x, y)) {
                    recenter();
                } else if (btnIn.contains(x, y)) {
                    animateZoom(Math.floor(zoom + 1e-6) + 1);
                } else if (btnOut.contains(x, y)) {
                    animateZoom(Math.ceil(zoom - 1e-6) - 1);
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                zoomAround(1, e.getX(), e.getY());
                return true;
            }
        });
    }

    // ------------------------------------------------------------------ public API

    /** Recorded fixes, oldest first, each {lat, lon}. */
    public void setTrail(double[][] pts) {
        double[][] n = new double[pts == null ? 0 : pts.length][];
        for (int i = 0; i < n.length; i++) {
            n[i] = new double[]{nx(pts[i][1]), ny(pts[i][0])};
        }
        trailNorm = n;
        if (Double.isNaN(mx) && n.length > 0) {
            double[] last = n[n.length - 1];
            if (n.length > 1) {
                double[] prev = n[n.length - 2];
                bearing = bearingOf(prev[0], prev[1], last[0], last[1], bearing);
            }
            placeMarker(last[0], last[1], false);
        }
        invalidate();
    }

    /** Moves the scooter to a new fix, gliding there. accuracy may be null. */
    public void setScooter(double lat, double lon, Double accuracyM) {
        accM = accuracyM == null ? Double.NaN : accuracyM;
        double x = nx(lon);
        double y = ny(lat);
        if (!Double.isNaN(mx)) {
            bearing = bearingOf(toX, toY, x, y, bearing);
        }
        placeMarker(x, y, !Double.isNaN(mx));
        bornAt = System.currentTimeMillis();
    }

    /** The phone's own position, drawn as a blue dot (null clears it). */
    public void setMe(Double lat, Double lon) {
        if (lat == null || lon == null) {
            meX = meY = Double.NaN;
        } else {
            meX = nx(lon);
            meY = ny(lat);
        }
        invalidate();
    }

    /** Pulse animation runs only while the map is on screen. */
    public void setLive(boolean on) {
        live = on;
        if (on) {
            postInvalidateOnAnimation();
        }
    }

    public boolean hasScooter() {
        return !Double.isNaN(mx);
    }

    public void recenter() {
        follow = true;
        if (!Double.isNaN(mx)) {
            panTo(toX, toY);
        }
        if (zoom < 15) {
            animateZoom(16);
        }
        invalidate();
    }

    // ------------------------------------------------------------------ camera

    private void placeMarker(double x, double y, boolean animate) {
        if (glide != null) {
            glide.cancel();
        }
        toX = x;
        toY = y;
        if (!hasCamera) {
            cx = x;
            cy = y;
            hasCamera = true;
        }
        if (!animate) {
            mx = x;
            my = y;
            if (follow) {
                cx = x;
                cy = y;
            }
            invalidate();
            return;
        }
        fromX = mx;
        fromY = my;
        final double camFromX = cx;
        final double camFromY = cy;
        glide = ValueAnimator.ofFloat(0f, 1f);
        glide.setDuration(1600);
        glide.setInterpolator(new DecelerateInterpolator());
        glide.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            mx = fromX + (toX - fromX) * t;
            my = fromY + (toY - fromY) * t;
            if (follow) {
                cx = camFromX + (toX - camFromX) * t;
                cy = camFromY + (toY - camFromY) * t;
            }
            invalidate();
        });
        glide.start();
    }

    private void panTo(final double x, final double y) {
        final double sx = cx;
        final double sy = cy;
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(500);
        a.setInterpolator(new DecelerateInterpolator());
        a.addUpdateListener(v -> {
            float t = (float) v.getAnimatedValue();
            cx = sx + (x - sx) * t;
            cy = sy + (y - sy) * t;
            invalidate();
        });
        a.start();
    }

    private void animateZoom(double target) {
        final double from = zoom;
        final double to = clamp(target, MIN_Z, MAX_Z);
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(250);
        a.addUpdateListener(v -> {
            zoom = from + (to - from) * (float) v.getAnimatedValue();
            invalidate();
        });
        a.start();
    }

    private void zoomAround(double dz, float fx, float fy) {
        double nz = clamp(zoom + dz, MIN_Z, MAX_Z);
        if (nz == zoom) {
            return;
        }
        if (follow && !Double.isNaN(mx)) {
            zoom = nz;                          // keep the scooter centred while following
        } else {
            double before = worldPx();
            double wx = cx + (fx - getWidth() / 2.0) / before;
            double wy = cy + (fy - getHeight() / 2.0) / before;
            zoom = nz;
            double after = worldPx();
            cx = wrap(wx - (fx - getWidth() / 2.0) / after);
            cy = clamp(wy - (fy - getHeight() / 2.0) / after, 0, 1);
        }
        invalidate();
    }

    /** Size of the whole world in screen px at the current zoom. */
    private double worldPx() {
        return tileBase * Math.pow(2, zoom);
    }

    private float sx(double x) {
        double dx = x - cx;
        if (dx > 0.5) {
            dx -= 1;
        } else if (dx < -0.5) {
            dx += 1;
        }
        return (float) (getWidth() / 2.0 + dx * worldPx());
    }

    private float sy(double y) {
        return (float) (getHeight() / 2.0 + (y - cy) * worldPx());
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);   // don't let the page scroll
        }
        scaler.onTouchEvent(e);
        if (!scaler.isInProgress()) {
            gestures.onTouchEvent(e);
        }
        return true;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w);
        int height = (int) (420 * d);
        setMeasuredDimension(width, resolveSize(height, h));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        c.drawRect(0, 0, w, h, bg);
        drawTiles(c, w, h);
        drawTrail(c);
        drawMe(c);
        drawScooter(c);
        drawButtons(c, w, h);
        drawAttribution(c, w, h);
        if (Double.isNaN(mx)) {
            c.drawText("Waiting for the scooter's first location fix …", w / 2f, h / 2f, hint);
        }
        if (live && !Double.isNaN(mx)) {
            postInvalidateDelayed(40);
        }
    }

    private void drawTiles(Canvas c, int w, int h) {
        int z = (int) Math.floor(zoom);
        z = Math.max(MIN_Z, Math.min(MAX_Z, z));
        int n = 1 << z;
        float tile = (float) (tileBase * Math.pow(2, zoom - z));
        double ctx = cx * n;
        double cty = cy * n;
        int x0 = (int) Math.floor(ctx - (w / 2.0) / tile);
        int x1 = (int) Math.floor(ctx + (w / 2.0) / tile);
        int y0 = (int) Math.floor(cty - (h / 2.0) / tile);
        int y1 = (int) Math.floor(cty + (h / 2.0) / tile);
        for (int ty = y0; ty <= y1; ty++) {
            if (ty < 0 || ty >= n) {
                continue;
            }
            for (int tx = x0; tx <= x1; tx++) {
                int wx = ((tx % n) + n) % n;
                float left = (float) (w / 2.0 + (tx - ctx) * tile);
                float top = (float) (h / 2.0 + (ty - cty) * tile);
                dst.set(left, top, left + tile + 0.6f, top + tile + 0.6f);
                Bitmap b = tile(z, wx, ty, true);
                if (b != null) {
                    c.drawBitmap(b, null, dst, tilePaint);
                    continue;
                }
                // placeholder: scale up the best ancestor we already hold
                for (int up = 1; up <= 4 && z - up >= 0; up++) {
                    Bitmap p = tile(z - up, wx >> up, ty >> up, false);
                    if (p != null) {
                        int parts = 1 << up;
                        int sz = 256 / parts;
                        int ox = (wx % parts) * sz;
                        int oy = (ty % parts) * sz;
                        src.set(ox, oy, ox + sz, oy + sz);
                        c.drawBitmap(p, src, dst, tilePaint);
                        break;
                    }
                }
            }
        }
    }

    private void drawTrail(Canvas c) {
        double[][] t = trailNorm;
        if (t.length < 2) {
            return;
        }
        path.reset();
        for (int i = 0; i < t.length; i++) {
            float x = sx(t[i][0]);
            float y = sy(t[i][1]);
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        if (!Double.isNaN(mx)) {
            path.lineTo(sx(mx), sy(my));
        }
        c.drawPath(path, trailHalo);
        c.drawPath(path, trailPaint);
    }

    private void drawMe(Canvas c) {
        if (Double.isNaN(meX)) {
            return;
        }
        float x = sx(meX);
        float y = sy(meY);
        c.drawCircle(x, y, 7f * d, meFill);
        c.drawCircle(x, y, 7f * d, meEdge);
    }

    private void drawScooter(Canvas c) {
        if (Double.isNaN(mx)) {
            return;
        }
        float x = sx(mx);
        float y = sy(my);
        float r = 20f * d;

        if (!Double.isNaN(accM) && accM > 0) {
            double mPerPx = 40075016.686 * Math.cos(latOf(my) * Math.PI / 180) / worldPx();
            float ar = (float) (accM / mPerPx);
            if (ar > r) {
                c.drawCircle(x, y, ar, accFill);
                c.drawCircle(x, y, ar, accEdge);
            }
        }

        // "live" pulse ring
        float phase = ((System.currentTimeMillis() - bornAt) % 1800) / 1800f;
        pulse.setColor(Ui.alpha(Ui.ACCENT, (int) (0xB0 * (1 - phase))));
        c.drawCircle(x, y, r + phase * 22f * d, pulse);

        // heading arrow just outside the badge
        if (!Float.isNaN(bearing)) {
            c.save();
            c.rotate(bearing, x, y);
            path.reset();
            path.moveTo(x, y - r - 11f * d);
            path.lineTo(x - 8f * d, y - r + 1f * d);
            path.lineTo(x + 8f * d, y - r + 1f * d);
            path.close();
            c.drawPath(path, arrow);
            c.restore();
        }

        c.drawCircle(x, y, r, pinFill);
        c.drawCircle(x, y, r, pinEdge);
        drawScooterGlyph(c, x, y, r * 0.62f);
    }

    /** A side-on scooter: two wheels, floor deck, rear body and the steering stem. */
    private void drawScooterGlyph(Canvas c, float x, float y, float s) {
        float wr = s * 0.30f;                   // wheel radius
        float wy = y + s * 0.42f;               // wheel centre line
        float rx = x - s * 0.62f;               // rear wheel
        float fx = x + s * 0.66f;               // front wheel
        c.drawCircle(rx, wy, wr, ink);
        c.drawCircle(fx, wy, wr, ink);
        // body: rear hump + seat over the rear wheel, deck to the stem
        path.reset();
        path.moveTo(rx - wr * 1.2f, wy - wr * 0.2f);
        path.lineTo(rx - wr * 0.9f, y - s * 0.20f);
        path.quadTo(x - s * 0.25f, y - s * 0.32f, x - s * 0.05f, y - s * 0.12f);
        path.lineTo(x - s * 0.05f, wy - wr * 0.6f);
        path.lineTo(x + s * 0.32f, wy - wr * 0.6f);
        path.close();
        c.drawPath(path, inkFill);
        // seat
        c.drawLine(x - s * 0.62f, y - s * 0.34f, x - s * 0.12f, y - s * 0.34f, ink);
        // stem + handlebar
        c.drawLine(x + s * 0.32f, wy - wr * 0.6f, fx - s * 0.02f, y - s * 0.62f, ink);
        c.drawLine(fx - s * 0.02f, y - s * 0.62f, fx, wy, ink);
        c.drawLine(fx - s * 0.20f, y - s * 0.66f, fx + s * 0.14f, y - s * 0.62f, ink);
    }

    private void drawButtons(Canvas c, int w, int h) {
        float bs = 40f * d;
        float m = 12f * d;
        float right = w - m;
        float bottom = h - m - 14f * d;
        btnFollow.set(right - bs, bottom - bs, right, bottom);
        btnOut.set(right - bs, btnFollow.top - m - bs, right, btnFollow.top - m);
        btnIn.set(right - bs, btnOut.top - bs, right, btnOut.top);
        float rr = 10f * d;
        c.drawRoundRect(btnFollow, rr, rr, btnFill);
        c.drawRoundRect(btnFollow, rr, rr, btnEdge);
        dst.set(btnIn.left, btnIn.top, btnOut.right, btnOut.bottom);
        c.drawRoundRect(dst, rr, rr, btnFill);
        c.drawRoundRect(dst, rr, rr, btnEdge);
        float k = 8f * d;
        // +
        c.drawLine(btnIn.centerX() - k, btnIn.centerY(), btnIn.centerX() + k, btnIn.centerY(), btnInk);
        c.drawLine(btnIn.centerX(), btnIn.centerY() - k, btnIn.centerX(), btnIn.centerY() + k, btnInk);
        // -
        c.drawLine(btnOut.centerX() - k, btnOut.centerY(), btnOut.centerX() + k, btnOut.centerY(), btnInk);
        // crosshair (filled centre when following)
        float fcx = btnFollow.centerX();
        float fcy = btnFollow.centerY();
        btnInk.setColor(follow ? 0xFF0B8F6B : 0xFF333B44);
        c.drawCircle(fcx, fcy, k * 0.8f, btnInk);
        c.drawLine(fcx, fcy - k * 1.3f, fcx, fcy - k * 0.8f, btnInk);
        c.drawLine(fcx, fcy + k * 0.8f, fcx, fcy + k * 1.3f, btnInk);
        c.drawLine(fcx - k * 1.3f, fcy, fcx - k * 0.8f, fcy, btnInk);
        c.drawLine(fcx + k * 0.8f, fcy, fcx + k * 1.3f, fcy, btnInk);
        if (follow) {
            c.drawCircle(fcx, fcy, k * 0.35f, btnDot);
        }
        btnInk.setColor(0xFF333B44);
    }

    private void drawAttribution(Canvas c, int w, int h) {
        String t = "© OpenStreetMap contributors";
        float tw = attr.measureText(t);
        float pad = 4f * d;
        c.drawRect(w - tw - pad * 2, h - attr.getTextSize() - pad * 2, w, h, attrBg);
        c.drawText(t, w - tw - pad, h - pad - 1f * d, attr);
    }

    // ------------------------------------------------------------------ tiles

    private static String key(int z, int x, int y) {
        return z + "/" + x + "/" + y;
    }

    /** Memory hit, else (if fetch) kick off a disk/network load and return null. */
    private Bitmap tile(int z, int x, int y, boolean fetch) {
        final String k = key(z, x, y);
        Bitmap b = mem.get(k);
        if (b != null || !fetch || failed.contains(k) || !pending.add(k)) {
            return b;
        }
        pool.execute(() -> {
            try {
                Bitmap got = load(z, x, y);
                if (got != null) {
                    mem.put(k, got);
                    ui.post(this::invalidate);
                } else {
                    failed.add(k);
                    ui.postDelayed(() -> failed.remove(k), 15_000);   // retry later
                }
            } finally {
                pending.remove(k);
            }
        });
        return null;
    }

    private Bitmap load(int z, int x, int y) {
        File f = new File(disk, z + "_" + x + "_" + y + ".png");
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < DISK_TTL_MS) {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) {
                return b;
            }
        }
        HttpURLConnection h = null;
        try {
            URL u = new URL(String.format(java.util.Locale.ROOT, TILE_URL, z, x, y));
            h = (HttpURLConnection) u.openConnection();
            h.setConnectTimeout(8000);
            h.setReadTimeout(10000);
            h.setRequestProperty("User-Agent", UA);
            if (h.getResponseCode() != 200) {
                return f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
            try (InputStream in = h.getInputStream()) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                }
            }
            byte[] bytes = out.toByteArray();
            Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (b != null) {
                try (FileOutputStream fo = new FileOutputStream(f)) {
                    fo.write(bytes);
                } catch (Exception ignored) {
                }
            }
            return b;
        } catch (Exception e) {
            return f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
        } finally {
            if (h != null) {
                h.disconnect();
            }
        }
    }

    // ------------------------------------------------------------------ projection

    private static double nx(double lon) {
        return (lon + 180.0) / 360.0;
    }

    private static double ny(double lat) {
        double l = Math.max(-85.05112878, Math.min(85.05112878, lat)) * Math.PI / 180;
        return (1 - Math.log(Math.tan(l) + 1 / Math.cos(l)) / Math.PI) / 2;
    }

    private static double latOf(double y) {
        double n = Math.PI - 2 * Math.PI * y;
        return 180 / Math.PI * Math.atan(Math.sinh(n));
    }

    /** Screen bearing (deg, 0 = up) from a→b; keeps the old one for sub-3 m moves. */
    private static float bearingOf(double ax, double ay, double bx, double by, float keep) {
        double dx = bx - ax;
        double dy = by - ay;
        double metres = Math.hypot(dx, dy) * 40075016.686 * Math.cos(latOf(by) * Math.PI / 180);
        if (metres < 3) {
            return keep;
        }
        return (float) Math.toDegrees(Math.atan2(dx, -dy));
    }

    private static double wrap(double x) {
        return x - Math.floor(x);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
