package dev.olatool;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * A small canvas plot of recent GPS points — a movement trail. Not a real map
 * (framework-only, no tiles), but it shows the shape of travel and the latest
 * fix; "Open in Maps" handles the real map. Uses an equirectangular projection
 * so the trail keeps roughly the right proportions.
 */
public final class TrailView extends View {

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint last = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private double[][] pts = new double[0][];     // each {lat, lon}

    public TrailView(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.5f * d);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setColor(Ui.INFO);
        dot.setColor(Ui.alpha(Ui.INFO, 0xAA));
        last.setColor(Ui.ACCENT);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2f * d);
        ring.setColor(Ui.alpha(Ui.ACCENT, 0x88));
        grid.setColor(Ui.LINE);
        grid.setStrokeWidth(1f);
        hint.setColor(Ui.DIM);
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setTextSize(13 * d);
    }

    public void setPoints(double[][] latlon) {
        pts = latlon == null ? new double[0][] : latlon;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = (int) (getResources().getDisplayMetrics().density * 180);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        float pad = 16 * d;
        float w = getWidth() - pad * 2;
        float h = getHeight() - pad * 2;

        // frame
        canvas.drawRect(pad, pad, pad + w, pad + h, grid);

        if (pts.length == 0) {
            canvas.drawText("No location yet — refresh", getWidth() / 2f,
                    getHeight() / 2f, hint);
            return;
        }

        // project to x (lon*cos(lat)), y (lat)
        double latMid = pts[pts.length - 1][0];
        double k = Math.cos(Math.toRadians(latMid));
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        float[][] xy = new float[pts.length][2];
        for (int i = 0; i < pts.length; i++) {
            double px = pts[i][1] * k;
            double py = pts[i][0];
            xy[i][0] = (float) px;
            xy[i][1] = (float) py;
            if (px < minX) minX = px;
            if (px > maxX) maxX = px;
            if (py < minY) minY = py;
            if (py > maxY) maxY = py;
        }
        double spanX = Math.max(maxX - minX, 1e-6);
        double spanY = Math.max(maxY - minY, 1e-6);
        double span = Math.max(spanX, spanY);          // uniform scale, keep aspect
        double cx = (minX + maxX) / 2, cy = (minY + maxY) / 2;

        path.reset();
        float lastX = 0, lastY = 0;
        for (int i = 0; i < xy.length; i++) {
            float sx = (float) (pad + w / 2 + (xy[i][0] - cx) / span * (w * 0.8));
            float sy = (float) (pad + h / 2 - (xy[i][1] - cy) / span * (h * 0.8));
            if (i == 0) {
                path.moveTo(sx, sy);
            } else {
                path.lineTo(sx, sy);
            }
            if (i < xy.length - 1) {
                canvas.drawCircle(sx, sy, 2.5f * d, dot);
            }
            lastX = sx;
            lastY = sy;
        }
        if (pts.length > 1) {
            canvas.drawPath(path, line);
        }
        canvas.drawCircle(lastX, lastY, 5f * d, last);
        canvas.drawCircle(lastX, lastY, 10f * d, ring);
    }
}
