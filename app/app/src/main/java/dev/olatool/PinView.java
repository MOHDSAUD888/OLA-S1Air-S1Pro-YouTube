package dev.olatool;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full-screen numeric PIN gate shown over the app while it is locked. Opaque
 * and clickable so nothing behind it is visible or tappable. Auto-submits once
 * the entered length reaches the stored PIN length; the host verifies and calls
 * {@link #fail(String)} on a wrong PIN.
 */
public final class PinView extends LinearLayout {

    public interface Listener {
        /** Called with the full entry once it reaches the expected length. */
        void onComplete(String pin);
    }

    private final StringBuilder entry = new StringBuilder();
    private final int target;
    private final Listener listener;
    private final TextView dots;
    private final TextView subtitle;

    public PinView(Context c, String title, String sub, int targetLength, Listener listener) {
        super(c);
        this.target = targetLength < 1 ? 4 : targetLength;
        this.listener = listener;

        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setBackgroundColor(Ui.BG);
        setClickable(true);
        setFocusable(true);
        int pad = Ui.dp(c, 24);
        setPadding(pad, Ui.dp(c, 64), pad, pad);

        TextView t = Ui.text(c, title, 20, Ui.TEXT);
        t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        addView(t);

        subtitle = Ui.text(c, sub == null ? "" : sub, 13, Ui.DIM);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(0, Ui.dp(c, 6), 0, 0);
        addView(subtitle);

        dots = Ui.text(c, "", 28, Ui.ACCENT);
        dots.setGravity(Gravity.CENTER);
        dots.setLetterSpacing(0.3f);
        dots.setPadding(0, Ui.dp(c, 28), 0, Ui.dp(c, 28));
        dots.setMinHeight(Ui.dp(c, 44));
        addView(dots);

        addView(pad(c));
        renderDots();
    }

    private LinearLayout keyRow(Context c) {
        LinearLayout r = Ui.row(c);
        r.setGravity(Gravity.CENTER);
        r.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6));
        return r;
    }

    private View pad(Context c) {
        LinearLayout grid = Ui.column(c);
        grid.setGravity(Gravity.CENTER_HORIZONTAL);
        int[][] rows = {{1, 2, 3}, {4, 5, 6}, {7, 8, 9}};
        for (int[] row : rows) {
            LinearLayout r = keyRow(c);
            for (int n : row) {
                r.addView(digit(c, n));
            }
            grid.addView(r);
        }
        LinearLayout last = keyRow(c);
        last.addView(spacerKey(c));
        last.addView(digit(c, 0));
        last.addView(keyButton(c, "⌫", v -> backspace()));  // erase
        grid.addView(last);
        return grid;
    }

    private Button digit(Context c, int n) {
        return keyButton(c, String.valueOf(n), v -> press(n));
    }

    private Button keyButton(Context c, String label, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(20);
        b.setTextColor(Ui.TEXT);
        b.setBackground(Ui.round(c, Ui.CARD, 40, Ui.LINE));
        Ui.wire(b, l);
        int size = Ui.dp(c, 68);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMargins(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));
        b.setLayoutParams(lp);
        return b;
    }

    private View spacerKey(Context c) {
        View v = new View(c);
        int size = Ui.dp(c, 68);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMargins(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));
        v.setLayoutParams(lp);
        return v;
    }

    private void press(int n) {
        if (entry.length() >= target) {
            return;
        }
        entry.append(n);
        renderDots();
        if (entry.length() == target) {
            final String pin = entry.toString();
            postDelayed(() -> listener.onComplete(pin), 90);
        }
    }

    private void backspace() {
        if (entry.length() > 0) {
            entry.deleteCharAt(entry.length() - 1);
            renderDots();
        }
    }

    private void renderDots() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < target; i++) {
            s.append(i < entry.length() ? "●" : "○");
            if (i < target - 1) {
                s.append("  ");
            }
        }
        Ui.set(dots, s.toString());
    }

    /** Host calls this on a wrong PIN: clears the entry and shows the message. */
    public void fail(String message) {
        entry.setLength(0);
        renderDots();
        if (subtitle != null) {
            subtitle.setText(message);
            subtitle.setTextColor(Ui.BAD);
        }
    }

    /** Clears the entry without an error (e.g. between confirm steps). */
    public void reset(String sub) {
        entry.setLength(0);
        renderDots();
        if (subtitle != null) {
            subtitle.setText(sub == null ? "" : sub);
            subtitle.setTextColor(Ui.DIM);
        }
    }
}
