package dev.olatool;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

/**
 * A compact sparkline: a filled line chart of recent values (battery over
 * time). Auto-scales to the data's own min/max with a little headroom, draws a
 * soft gradient fill under the line and a dot on the latest point.
 *
 * <p>Pure {@link Canvas}. Give it {@link #setData} and an accent colour.
 */
public final class Spark extends View {

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Path area = new Path();

    private float[] data = new float[0];
    private int accent = Ui.ACCENT;
    private float lo, hi;

    public Spark(Context c) {
        super(c);
        float d = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.2f * d);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(accent);
        fill.setStyle(Paint.Style.FILL);
        dot.setStyle(Paint.Style.FILL);
        dot.setColor(accent);
        grid.setColor(Ui.LINE);
        grid.setStrokeWidth(1f);
    }

    /** Chronological values (oldest first). Fixed scale optional via setRange. */
    public void setData(float[] values) {
        data = values == null ? new float[0] : values;
        invalidate();
    }

    /** Pin the vertical scale (e.g. 0..100 for battery). lo==hi clears it. */
    public void setRange(float lo, float hi) {
        this.lo = lo;
        this.hi = hi;
        invalidate();
    }

    public void setAccent(int color) {
        accent = color;
        line.setColor(color);
        dot.setColor(color);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = (int) (getResources().getDisplayMetrics().density * 72);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        float padX = 4 * d;
        float padY = 8 * d;
        float w = getWidth() - padX * 2;
        float h = getHeight() - padY * 2;

        // baseline
        canvas.drawLine(padX, getHeight() - padY, getWidth() - padX, getHeight() - padY, grid);

        if (data.length < 2) {
            return;
        }

        float min = hi > lo ? lo : Float.MAX_VALUE;
        float max = hi > lo ? hi : -Float.MAX_VALUE;
        if (!(hi > lo)) {
            for (float v : data) {
                if (v < min) min = v;
                if (v > max) max = v;
            }
            if (max - min < 1f) {
                max = min + 1f;
            }
            float head = (max - min) * 0.15f;
            min -= head;
            max += head;
        }

        float range = max - min;
        float stepX = w / (data.length - 1);

        path.reset();
        area.reset();
        float firstY = getHeight() - padY;
        for (int i = 0; i < data.length; i++) {
            float x = padX + stepX * i;
            float norm = (data[i] - min) / range;
            float y = padY + (1f - Math.max(0, Math.min(1, norm))) * h;
            if (i == 0) {
                path.moveTo(x, y);
                area.moveTo(x, getHeight() - padY);
                area.lineTo(x, y);
                firstY = y;
            } else {
                path.lineTo(x, y);
                area.lineTo(x, y);
            }
        }
        float lastX = padX + stepX * (data.length - 1);
        float lastNorm = (data[data.length - 1] - min) / range;
        float lastY = padY + (1f - Math.max(0, Math.min(1, lastNorm))) * h;
        area.lineTo(lastX, getHeight() - padY);
        area.close();

        fill.setShader(new LinearGradient(0, padY, 0, getHeight() - padY,
                Ui.alpha(accent, 0x55), Ui.alpha(accent, 0x05), Shader.TileMode.CLAMP));
        canvas.drawPath(area, fill);
        canvas.drawPath(path, line);
        canvas.drawCircle(lastX, lastY, 3.5f * d, dot);
    }
}
