package dev.olatool;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * A 3×3 "connect the dots" pattern pad, drawn by hand. The user presses on a
 * dot and drags across neighbours; releasing reports the ordered list of dot
 * indices (0..8, row-major) through {@link Listener#onComplete(int[])}.
 *
 * <p>The host owns the title/subtitle and the decision about a wrong pattern —
 * this view only draws the grid and captures the gesture. It stays transparent
 * so the host paints the background (the full-screen gate, or a dialog panel).
 *
 * <p>Two small touches match what people expect from a stock Android pattern:
 * a dot already used can't be picked again, and dragging straight across a
 * skipped middle dot auto-includes it (so 0→2 also passes through 1).
 */
public final class PatternView extends View {

    public interface Listener {
        /** The ordered dot indices (0..8) once the finger lifts. */
        void onComplete(int[] nodes);
    }

    private Listener listener;

    private final List<Integer> seq = new ArrayList<>();
    private final boolean[] used = new boolean[9];
    private float curX, curY;
    private boolean drawing;
    private boolean error;

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint link = new Paint(Paint.ANTI_ALIAS_FLAG);

    public PatternView(Context c, Listener l) {
        super(c);
        this.listener = l;

        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Ui.dp(c, 2));
        ring.setColor(Ui.LINE);

        fill.setStyle(Paint.Style.FILL);

        link.setStyle(Paint.Style.STROKE);
        link.setStrokeWidth(Ui.dp(c, 6));
        link.setStrokeCap(Paint.Cap.ROUND);
        link.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** Wipes the drawn pattern back to an empty grid. */
    public void clearPattern() {
        seq.clear();
        for (int i = 0; i < used.length; i++) {
            used[i] = false;
        }
        drawing = false;
        error = false;
        invalidate();
    }

    /** Flashes the current pattern red, then clears it a moment later. */
    public void failAndClear() {
        error = true;
        drawing = false;
        invalidate();
        postDelayed(this::clearPattern, 260);
    }

    // --------------------------------------------------------------- geometry

    // Recomputed each draw/touch from the current size so the pad stays a
    // centred square whether it fills the screen or sits in a fixed dialog box.
    private float cell, originX, originY, dotR, hitR;

    private void geom() {
        int w = getWidth();
        int h = getHeight();
        float size = Math.min(w, h);
        cell = size / 3f;
        originX = (w - size) / 2f;
        originY = (h - size) / 2f;
        dotR = cell * 0.10f;
        hitR = cell * 0.34f;
    }

    private float cx(int node) {
        return originX + ((node % 3) + 0.5f) * cell;
    }

    private float cy(int node) {
        return originY + ((node / 3) + 0.5f) * cell;
    }

    // ------------------------------------------------------------------- draw

    @Override
    protected void onDraw(Canvas canvas) {
        geom();
        int color = error ? Ui.BAD : Ui.ACCENT;
        link.setColor(color);

        // Connect the chosen dots in order, then trail to the finger.
        for (int i = 1; i < seq.size(); i++) {
            int a = seq.get(i - 1);
            int b = seq.get(i);
            canvas.drawLine(cx(a), cy(a), cx(b), cy(b), link);
        }
        if (drawing && !seq.isEmpty()) {
            int last = seq.get(seq.size() - 1);
            canvas.drawLine(cx(last), cy(last), curX, curY, link);
        }

        for (int node = 0; node < 9; node++) {
            float x = cx(node);
            float y = cy(node);
            canvas.drawCircle(x, y, dotR * 1.8f, ring);
            if (used[node]) {
                fill.setColor(color);
                canvas.drawCircle(x, y, dotR, fill);
            }
        }
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                clearPattern();
                drawing = true;
                curX = e.getX();
                curY = e.getY();
                hit(curX, curY);
                invalidate();
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!drawing) {
                    return true;
                }
                curX = e.getX();
                curY = e.getY();
                hit(curX, curY);
                invalidate();
                return true;

            case MotionEvent.ACTION_UP:
                drawing = false;
                invalidate();
                // Report only on a real lift. Removing this overlay from inside
                // the listener makes Android deliver an ACTION_CANCEL to the
                // detaching view; firing onComplete there too would recurse.
                if (!seq.isEmpty() && listener != null) {
                    listener.onComplete(toArray());
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                // Gesture interrupted (e.g. the view is being detached): just
                // stop drawing, never treat it as a completed pattern.
                drawing = false;
                invalidate();
                return true;

            default:
                return super.onTouchEvent(e);
        }
    }

    /** Selects the dot under (x,y) if any, inserting a skipped middle dot. */
    private void hit(float x, float y) {
        for (int node = 0; node < 9; node++) {
            if (used[node]) {
                continue;
            }
            float dx = x - cx(node);
            float dy = y - cy(node);
            if (dx * dx + dy * dy <= hitR * hitR) {
                if (!seq.isEmpty()) {
                    int prev = seq.get(seq.size() - 1);
                    int mid = between(prev, node);
                    if (mid >= 0 && !used[mid]) {
                        used[mid] = true;
                        seq.add(mid);
                    }
                }
                used[node] = true;
                seq.add(node);
                return;
            }
        }
    }

    /** The dot exactly between a and b on the grid, or -1 if none. */
    private static int between(int a, int b) {
        int ar = a / 3, ac = a % 3;
        int br = b / 3, bc = b % 3;
        if ((ar + br) % 2 == 0 && (ac + bc) % 2 == 0) {
            int mr = (ar + br) / 2;
            int mc = (ac + bc) / 2;
            int mid = mr * 3 + mc;
            return mid == a || mid == b ? -1 : mid;
        }
        return -1;
    }

    private int[] toArray() {
        int[] out = new int[seq.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = seq.get(i);
        }
        return out;
    }
}
