package dev.olatool;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * A circular battery gauge: a 270° ring with a tinted progress arc, a big
 * percentage in the middle, and a small caption below it (range, charging …).
 *
 * <p>Pure {@link Canvas} drawing — no AndroidX, no XML. The value animates
 * smoothly toward its target so a refresh doesn't snap.
 */
public final class Gauge extends View {

    private static final float START_ANGLE = 135f;
    private static final float SWEEP = 270f;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint big = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unit = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cap = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();

    private float target = Float.NaN;   // 0..100, NaN = unknown
    private float shown = 0f;            // animated value
    private int accent = Ui.ACCENT;
    private String caption = "";
    private boolean animating;

    public Gauge(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;

        track.setStyle(Paint.Style.STROKE);
        track.setStrokeCap(Paint.Cap.ROUND);
        track.setStrokeWidth(12 * d);
        track.setColor(Ui.LINE);

        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setStrokeWidth(12 * d);
        arc.setColor(accent);

        big.setColor(Ui.TEXT);
        big.setTextAlign(Paint.Align.CENTER);
        big.setTextSize(44 * d);
        big.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        unit.setColor(Ui.DIM);
        unit.setTextAlign(Paint.Align.CENTER);
        unit.setTextSize(16 * d);

        cap.setColor(Ui.DIM);
        cap.setTextAlign(Paint.Align.CENTER);
        cap.setTextSize(13 * d);
    }

    /** Battery percent, or null/NaN when unknown. */
    public void setValue(Double v) {
        float t = (v == null || v.isNaN()) ? Float.NaN : (float) Math.max(0, Math.min(100, v));
        if (Float.compare(t, target) == 0) {
            return;
        }
        target = t;
        if (Float.isNaN(target)) {
            shown = 0f;
            invalidate();
        } else {
            startAnim();
        }
    }

    public void setAccent(int color) {
        if (accent != color) {
            accent = color;
            arc.setColor(color);
            invalidate();
        }
    }

    public void setCaption(String s) {
        String t = s == null ? "" : s;
        if (!t.equals(caption)) {
            caption = t;
            invalidate();
        }
    }

    private void startAnim() {
        if (animating) {
            return;
        }
        animating = true;
        post(new Runnable() {
            @Override
            public void run() {
                float goal = Float.isNaN(target) ? 0f : target;
                shown += (goal - shown) * 0.2f;
                if (Math.abs(goal - shown) < 0.3f) {
                    shown = goal;
                    animating = false;
                } else {
                    postDelayed(this, 16);
                }
                invalidate();
            }
        });
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = (int) (getResources().getDisplayMetrics().density * 190);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        float pad = 18 * d;
        float side = Math.min(getWidth(), getHeight()) - pad * 2;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        oval.set(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f);

        canvas.drawArc(oval, START_ANGLE, SWEEP, false, track);
        if (!Float.isNaN(target)) {
            canvas.drawArc(oval, START_ANGLE, SWEEP * (shown / 100f), false, arc);
        }

        String value = Float.isNaN(target) ? Ui.DASH : String.valueOf(Math.round(shown));
        big.setColor(Float.isNaN(target) ? Ui.DIM : Ui.TEXT);
        canvas.drawText(value, cx, cy + big.getTextSize() * 0.33f, big);
        if (!Float.isNaN(target)) {
            canvas.drawText("%", cx + big.measureText(value) / 2f + 14 * d,
                    cy + big.getTextSize() * 0.33f, unit);
        }
        if (!caption.isEmpty()) {
            cap.setColor(accent);
            canvas.drawText(caption, cx, cy + side / 2f - 2 * d, cap);
        }
    }
}
