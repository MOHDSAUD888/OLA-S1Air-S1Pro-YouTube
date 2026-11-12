package dev.olatool;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

import dev.olatool.core.Live;

/**
 * The per-cell BMS grid: one bar per cell scaled across the live pack min–max,
 * the weakest cell amber, the strongest accent, a balancing cell blue with a
 * "BAL" tag, and a summary line. Shared by the Battery screen and the BMS tab,
 * so both show cells the same way.
 *
 * <p>Read-only: it only renders values the {@link Bus} already decoded.
 */
public final class BmsCellPanel extends LinearLayout {

    private final TextView summary;
    private final CellRow[] rows = new CellRow[14];

    public BmsCellPanel(Context c) {
        super(c);
        setOrientation(VERTICAL);
        summary = Ui.text(c, "-", 12, Ui.DIM);
        summary.setPadding(0, 0, 0, Ui.dp(c, 6));
        addView(summary);
        for (int i = 0; i < rows.length; i++) {
            rows[i] = new CellRow(c, i + 1);
            addView(rows[i]);
        }
    }

    /** {@code live} may be null; it only supplies per-cell balancing flags. */
    public void update(Live.Snapshot s, Live live) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;
        int minI = -1;
        int maxI = -1;
        int n = 0;
        if (s != null) {
            for (int i = 0; i < s.cells.length; i++) {
                double v = s.cells[i];
                if (Double.isNaN(v) || v <= 0) {
                    continue;
                }
                n++;
                if (v < lo) {
                    lo = v;
                    minI = i;
                }
                if (v > hi) {
                    hi = v;
                    maxI = i;
                }
            }
        }
        for (int i = 0; i < rows.length; i++) {
            double v = s == null ? Double.NaN : s.cells[i];
            boolean bal = live != null
                    && live.value("Balancing_Status_Battery_Cell_" + (i + 1)) >= 1.0;
            rows[i].set(v, lo, hi, bal, i == minI, i == maxI);
        }
        if (n == 0) {
            summary.setText("No cell voltages on the bus yet.");
        } else {
            summary.setText(String.format(Locale.ROOT,
                    "%d/14 cells · min C%d %d mV · max C%d %d mV · Δ %d mV",
                    n, minI + 1, (int) (lo * 1000), maxI + 1, (int) (hi * 1000),
                    (int) ((hi - lo) * 1000)));
        }
    }

    /** One cell: number, a bar scaled across the pack range, its mV, a balance tag. */
    private static final class CellRow extends LinearLayout {
        final TextView mv;
        final ProgressBar bar;
        final TextView balance;

        CellRow(Context c, int index) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(0, Ui.dp(c, 3), 0, Ui.dp(c, 3));

            TextView num = Ui.text(c, "C" + index, 12, Ui.DIM);
            addView(num, new LayoutParams(Ui.dp(c, 30), LayoutParams.WRAP_CONTENT));

            bar = Ui.bar(c, Ui.ACCENT);
            LayoutParams bp = new LayoutParams(0, Ui.dp(c, 8), 1f);
            bp.leftMargin = Ui.dp(c, 6);
            bp.rightMargin = Ui.dp(c, 8);
            addView(bar, bp);

            mv = Ui.text(c, Ui.DASH, 12, Ui.TEXT);
            mv.setGravity(Gravity.END);
            mv.setTypeface(Typeface.MONOSPACE);
            addView(mv, new LayoutParams(Ui.dp(c, 58), LayoutParams.WRAP_CONTENT));

            balance = Ui.text(c, "", 10, Ui.INFO);
            balance.setGravity(Gravity.END);
            balance.setTypeface(balance.getTypeface(), Typeface.BOLD);
            addView(balance, new LayoutParams(Ui.dp(c, 28), LayoutParams.WRAP_CONTENT));
        }

        void set(double volt, double lo, double hi, boolean balancing, boolean isMin, boolean isMax) {
            if (Double.isNaN(volt) || volt <= 0) {
                mv.setText(Ui.DASH);
                Ui.bar(bar, 0);
                balance.setText("");
                return;
            }
            mv.setText(String.format(Locale.ROOT, "%d mV", (int) (volt * 1000.0)));
            double span = hi - lo;
            double frac = span < 0.001 ? 1.0 : (volt - lo) / span;
            Ui.bar(bar, Math.max(0.04, Math.min(1, frac)));
            int color = balancing ? Ui.INFO : isMin ? Ui.WARN : isMax ? Ui.ACCENT : Ui.CELL_NORMAL;
            bar.setProgressTintList(ColorStateList.valueOf(color));
            mv.setTextColor(isMin ? Ui.WARN : isMax ? Ui.ACCENT : Ui.TEXT);
            balance.setText(balancing ? "BAL" : "");
        }
    }
}
