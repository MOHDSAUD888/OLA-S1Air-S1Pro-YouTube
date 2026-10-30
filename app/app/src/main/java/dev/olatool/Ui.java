package dev.olatool;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** Small helpers so the screens do not repeat styling code. */
public final class Ui {

    public static final int BG = 0xFF0E1116;
    public static final int CARD = 0xFF161C23;
    public static final int LINE = 0xFF28313B;
    public static final int TEXT = 0xFFE6EDF3;
    public static final int DIM = 0xFF8B98A5;
    public static final int ACCENT = 0xFF2FD8A8;
    public static final int INFO = 0xFF5AA9FF;
    public static final int WARN = 0xFFFFB020;
    public static final int BAD = 0xFFE5484D;

    /** Shown where the vehicle has not reported a value yet. */
    public static final String DASH = "\u2014";

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

    // ------------------------------------------------------------ dashboard

    /** A colour at a given alpha, for the tinted pills and bars. */
    public static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | ((a & 0xFF) << 24);
    }

    /** setText, but only when the string actually changed. */
    public static void set(TextView v, String s) {
        if (v == null) {
            return;
        }
        CharSequence old = v.getText();
        if (old == null || !s.contentEquals(old)) {
            v.setText(s);
        }
    }

    public static View space(Context c, int heightDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, heightDp)));
        return v;
    }

    public static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 1)));
        return v;
    }

    /** A rounded status chip: text and a tinted fill, never colour alone. */
    public static TextView pill(Context c, String label, int color) {
        TextView v = text(c, label, 11, color);
        v.setTypeface(v.getTypeface(), Typeface.BOLD);
        v.setPadding(dp(c, 9), dp(c, 4), dp(c, 9), dp(c, 4));
        v.setBackground(round(c, alpha(color, 0x1F), 20, alpha(color, 0x55)));
        v.setTag(color);
        return v;
    }

    /** Updates a pill in place, skipping work when nothing changed. */
    public static void pill(TextView v, String label, int color) {
        if (v == null) {
            return;
        }
        boolean sameColor = v.getTag() instanceof Integer && ((Integer) v.getTag()) == color;
        if (sameColor && label.contentEquals(v.getText())) {
            return;
        }
        v.setText(label);
        v.setTextColor(color);
        v.setBackground(round(v.getContext(), alpha(color, 0x1F), 20, alpha(color, 0x55)));
        v.setTag(color);
    }

    /** A horizontal meter; call {@link #bar(ProgressBar, double)} to move it. */
    public static ProgressBar bar(Context c, int color) {
        ProgressBar p = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        p.setMax(1000);
        p.setProgressTintList(ColorStateList.valueOf(color));
        p.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
        p.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 5)));
        return p;
    }

    /** Fraction is clamped to 0..1; NaN parks the bar at zero. */
    public static void bar(ProgressBar p, double fraction) {
        if (p == null) {
            return;
        }
        if (Double.isNaN(fraction)) {
            fraction = 0;
        }
        p.setProgress((int) Math.round(Math.max(0, Math.min(1, fraction)) * 1000));
    }

    /** One compact metric tile: small label, big value, small unit. */
    public static final class Stat extends LinearLayout {
        public final TextView value;
        private final TextView unit;

        public Stat(Context c, String label) {
            super(c);
            setOrientation(VERTICAL);
            setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
            setBackground(round(c, CARD, 12, LINE));

            TextView key = text(c, label.toUpperCase(Locale.ROOT), 10, DIM);
            key.setLetterSpacing(0.06f);
            addView(key);

            LinearLayout line = row(c);
            line.setPadding(0, dp(c, 3), 0, 0);
            value = text(c, DASH, 19, TEXT);
            value.setTypeface(value.getTypeface(), Typeface.BOLD);
            line.addView(value);

            unit = text(c, "", 11, DIM);
            LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            up.leftMargin = dp(c, 4);
            line.addView(unit, up);
            addView(line);
        }

        public Stat set(String text) {
            Ui.set(value, text);
            return this;
        }

        public Stat set(String text, int color) {
            set(text);
            if (value.getCurrentTextColor() != color) {
                value.setTextColor(color);
            }
            return this;
        }

        public Stat unit(String text) {
            Ui.set(unit, text);
            return this;
        }
    }

    /** Two equal tiles side by side; pass null for a single full-width tile. */
    public static LinearLayout statRow(Context c, View left, View right) {
        LinearLayout r = row(c);
        r.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        r.addView(left, lp);
        if (right != null) {
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            rp.leftMargin = dp(c, 8);
            r.addView(right, rp);
        }
        return r;
    }

    /** A card with a titled header and an empty body the caller fills. */
    public static final class Section {
        public final LinearLayout card;
        public final LinearLayout body;

        Section(LinearLayout card, LinearLayout body) {
            this.card = card;
            this.body = body;
        }
    }

    public static Section section(Context c, String title, String subtitle) {
        return section(c, title, subtitle, ACCENT);
    }

    public static Section section(Context c, String title, String subtitle, int accent) {
        LinearLayout card = column(c);
        card.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        card.setBackground(round(c, CARD, 14, LINE));

        LinearLayout head = row(c);
        View stripe = new View(c);
        stripe.setBackground(round(c, accent, 2, 0));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(dp(c, 3), dp(c, 28));
        sp.rightMargin = dp(c, 10);
        head.addView(stripe, sp);

        LinearLayout titles = column(c);
        TextView t = text(c, title, 15, TEXT);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        titles.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = text(c, subtitle, 11, DIM);
            s.setPadding(0, dp(c, 2), 0, 0);
            titles.addView(s);
        }
        head.addView(titles, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(head);

        LinearLayout body = column(c);
        body.setPadding(0, dp(c, 10), 0, 0);
        card.addView(body);
        return new Section(card, body);
    }

    /** A label/value row with an optional trailing pill. */
    public static LinearLayout valueRow(Context c, String label, TextView value) {
        LinearLayout r = row(c);
        r.setPadding(0, dp(c, 5), 0, dp(c, 5));
        TextView key = text(c, label, 13, DIM);
        r.addView(key, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (value != null) {
            r.addView(value);
        }
        return r;
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
            value = text(c, DASH, 13, TEXT);
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
