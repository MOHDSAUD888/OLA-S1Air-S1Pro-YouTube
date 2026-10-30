package dev.olatool;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Small helpers so the screens do not repeat styling code. */
public final class Ui {

    public static final int BG = 0xFF0E1116;
    public static final int CARD = 0xFF161C23;
    public static final int LINE = 0xFF28313B;
    public static final int TEXT = 0xFFE6EDF3;
    public static final int DIM = 0xFF8B98A5;
    public static final int ACCENT = 0xFF2FD8A8;
    public static final int WARN = 0xFFFFB020;
    public static final int BAD = 0xFFE5484D;

    private Ui() {
    }

    public static int dp(Context c, int value) {
        return (int) (value * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static GradientDrawable round(Context c, int fill, int radiusDp, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        if (stroke != 0) {
            d.setStroke(dp(c, 1), stroke);
        }
        return d;
    }

    public static TextView text(Context c, CharSequence s, float sizeSp, int color) {
        TextView v = new TextView(c);
        v.setText(s);
        v.setTextSize(sizeSp);
        v.setTextColor(color);
        v.setLineSpacing(dp(c, 2), 1f);
        return v;
    }

    public static TextView heading(Context c, String s) {
        TextView v = text(c, s, 13, DIM);
        v.setAllCaps(true);
        v.setLetterSpacing(0.08f);
        v.setPadding(0, dp(c, 4), 0, dp(c, 8));
        return v;
    }

    public static TextView note(Context c, String s) {
        return text(c, s, 12, DIM);
    }

    public static Button button(Context c, String label, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(TEXT);
        b.setBackground(round(c, CARD, 10, LINE));
        b.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8));
        b.setMinHeight(dp(c, 44));
        b.setOnClickListener(l);
        return b;
    }

    public static Button accent(Context c, String label, View.OnClickListener l) {
        Button b = button(c, label, l);
        b.setTextColor(0xFF06231C);
        b.setBackground(round(c, ACCENT, 10, 0));
        return b;
    }

    public static Button danger(Context c, String label, View.OnClickListener l) {
        Button b = button(c, label, l);
        b.setTextColor(0xFFFFD9DA);
        b.setBackground(round(c, 0xFF3A1C1F, 10, 0xFF5A2A2E));
        return b;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        l.setBackground(round(c, CARD, 12, LINE));
        return l;
    }

    /** A scrollable page with the standard padding, for one tab. */
    public static ScrollView page(Context c) {
        ScrollView s = new ScrollView(c);
        s.setFillViewport(true);
        s.setBackgroundColor(BG);
        LinearLayout body = column(c);
        body.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 20));
        s.addView(body, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        s.setTag(body);
        return s;
    }

    public static LinearLayout body(ScrollView page) {
        return (LinearLayout) page.getTag();
    }

    /** "-" for a value the vehicle has not reported yet. */
    public static String fmt(double value, int decimals) {
        if (Double.isNaN(value)) {
            return "-";
        }
        return String.format("%." + decimals + "f", value);
    }

    public static String pct(double value) {
        if (Double.isNaN(value)) {
            return "-";
        }
        return String.format("%.1f %%", value);
    }

    /** Label on the left, value on the right. */
    public static final class Kv extends LinearLayout {
        public final TextView value;

        public Kv(Context c, String label) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(0, dp(c, 6), 0, dp(c, 6));
            TextView key = text(c, label, 13, DIM);
            addView(key, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            value = text(c, "-", 13, TEXT);
            value.setGravity(Gravity.END);
            addView(value, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        }

        public Kv set(String s) {
            value.setText(s);
            value.setTextColor(TEXT);
            return this;
        }

        public Kv set(String s, int color) {
            value.setText(s);
            value.setTextColor(color);
            return this;
        }
    }
}
