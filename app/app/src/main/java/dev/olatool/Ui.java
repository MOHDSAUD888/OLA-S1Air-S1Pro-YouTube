package dev.olatool;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

/** Small helpers so the screens do not repeat styling code. */
public final class Ui {

    // Premium dark palette (Tokyo-Night family): layered surfaces, one calm
    // accent, brighter body text. BG < CARD < ELEV gives real depth.
    public static final int BG = 0xFF15161E;
    public static final int CARD = 0xFF1A1B26;
    public static final int ELEV = 0xFF2A2D44;
    public static final int CODE_BG = 0xFF101019;
    public static final int LINE = 0xFF3C4268;
    public static final int TEXT = 0xFFC8D2F0;
    public static final int DIM = 0xFFAAB2D8;
    public static final int ACCENT = 0xFF7AA2F7;
    public static final int INFO = 0xFF7DCFFF;
    public static final int WARN = 0xFFE0AF68;
    public static final int BAD = 0xFFF7768E;

    /** Readable text on top of an ACCENT fill (buttons, pills). */
    public static final int ON_ACCENT = 0xFF0B1020;

    /** A neutral grey for ordinary cell glyphs and muted icons. */
    public static final int CELL_NORMAL = 0xFF6B7684;

    /** A soft lilac for cast / companion devices. */
    public static final int LILAC = 0xFFB98BFF;

    // Danger palette — used only for the one destructive action on a screen.
    public static final int DANGER_TEXT = 0xFFFFD9DA;
    public static final int DANGER_BG = 0xFF3A1C1F;
    public static final int DANGER_EDGE = 0xFF5A2A2E;
    public static final int DANGER_RIPPLE = 0xFFE5484D;

    // Map view — the map draws its own world, so it keeps its own palette
    // rather than borrowing the dashboard's.
    public static final int MAP_BG = 0xFFE8E4DC;
    public static final int MAP_TRAIL = 0xFF1E88E5;
    public static final int MAP_TRAIL_HALO = 0x99FFFFFF;
    public static final int MAP_PIN = 0xFF0B8F6B;
    public static final int MAP_PIN_EDGE = 0xFFFFFFFF;
    public static final int MAP_INK = 0xFF111418;
    public static final int MAP_ARROW = 0xFF0B8F6B;
    public static final int MAP_ME_FILL = 0xFF1E88E5;
    public static final int MAP_ME_EDGE = 0xFFFFFFFF;
    public static final int MAP_BTN_FILL = 0xF2FFFFFF;
    public static final int MAP_BTN_DOT = 0xFF0B8F6B;
    public static final int MAP_BTN_EDGE = 0x33000000;
    public static final int MAP_BTN_INK = 0xFF333B44;
    public static final int MAP_FOLLOW = 0xFF0B8F6B;
    public static final int MAP_ATTR = 0xFF333333;
    public static final int MAP_ATTR_BG = 0xB3FFFFFF;
    public static final int MAP_HINT = 0xFF555555;

    // Overlay / web player — floating UI over video, so it keeps its own
    // darks and clear whites rather than the dashboard's.
    public static final int OVERLAY_PLATE = 0xCC202124;
    public static final int OVERLAY_RED = 0xFFFF0000;
    public static final int OVERLAY_BLACK = 0xAA000000;
    public static final int PLAYER_BAR = 0xDD111111;
    public static final int PLAYER_GRIP = 0x55FFFFFF;
    public static final int PLAYER_CAP = 0x22FFFFFF;

    /** Corner radii, in dp. Two sizes only, so every surface matches. */
    public static final int RADIUS_CARD = 16;
    public static final int RADIUS_CONTROL = 12;

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

    /**
     * A rounded fill that shows a Material ripple on touch, so every tap gets
     * visual feedback within 100 ms. The mask clips the ripple to the corners.
     */
    public static android.graphics.drawable.Drawable pressable(
            Context c, int fill, int radiusDp, int stroke, int ripple) {
        GradientDrawable base = round(c, fill, radiusDp, stroke);
        GradientDrawable mask = round(c, 0xFFFFFFFF, radiusDp, 0);
        return new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(ripple), base, mask);
    }

    public static TextView text(Context c, CharSequence s, float sizeSp, int color) {
        TextView v = new TextView(c);
        v.setText(s);
        v.setTextSize(sizeSp);
        v.setTextColor(color);
        v.setLineSpacing(dp(c, 2), 1f);
        return v;
    }

    /**
     * Styles a value that changes live: medium weight and tabular digits, so
     * the text does not jitter sideways as the number ticks.
     */
    public static TextView number(TextView v) {
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setFontFeatureSettings("tnum");
        return v;
    }

    /** A 3dp accent bar along the bottom edge: the selected-tab indicator. */
    public static android.graphics.drawable.Drawable underline(Context c, int color) {
        android.graphics.drawable.LayerDrawable d = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{round(c, color, 2, 0)});
        d.setLayerGravity(0, Gravity.BOTTOM);
        d.setLayerHeight(0, dp(c, 3));
        d.setLayerInset(0, dp(c, 12), 0, dp(c, 12), 0);
        return d;
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

    /**
     * A page header: a title and an optional dim subtitle, so every screen
     * opens the same way. {@code accent} is kept for callers; it is not drawn.
     */
    public static View pageTitle(Context c, String title, String subtitle) {
        return pageTitle(c, title, subtitle, ACCENT);
    }

    public static View pageTitle(Context c, String title, String subtitle, int accent) {
        LinearLayout wrap = column(c);
        wrap.setPadding(0, dp(c, 4), 0, dp(c, 14));

        TextView t = text(c, title, 22, TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        wrap.addView(t);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = text(c, subtitle, 13, DIM);
            s.setPadding(0, dp(c, 4), 0, 0);
            wrap.addView(s);
        }
        return wrap;
    }

    /** Styles a {@link TextView} as a carded monospace code block. */
    public static TextView codeBlock(TextView v) {
        Context c = v.getContext();
        v.setTypeface(Typeface.MONOSPACE);
        v.setBackground(round(c, CODE_BG, 10, LINE));
        v.setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        v.setLineSpacing(dp(c, 2), 1f);
        return v;
    }

    public static Button button(Context c, String label, View.OnClickListener l) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(TEXT);
        b.setBackground(pressable(c, ELEV, RADIUS_CONTROL, LINE, alpha(0xFFFFFFFF, 0x30)));
        b.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        b.setStateListAnimator(null);   // flat: no Material lift shadow
        // Material: 48dp minimum touch target on Android.
        b.setMinHeight(dp(c, 48));
        b.setMinWidth(dp(c, 48));
        // Every button is built here, and accent()/danger() delegate to it, so
        // wiring once gives the whole app the same haptic tick + 3D press feel.
        wire(b, l);
        return b;
    }

    /**
     * Gives a hand-rolled {@link Button} the same behaviour as one from
     * {@link #button}: a haptic tick on click and a springy 3D press animation.
     * Call it for buttons built by hand — the tab bar, the PIN pad, the
     * segmented control — so they match the rest of the app.
     */
    public static void wire(Button b, View.OnClickListener l) {
        b.setHapticFeedbackEnabled(true);
        pressFeel(b);
        b.setOnClickListener(haptic(l));
    }

    /**
     * A quiet press: the view eases to 97% and dims slightly, then eases back
     * on release. No bounce and no shadow, which read as toy-like on a dark
     * flat surface. The touch listener does not consume the event, so the
     * ripple and the click still run.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    public static void pressFeel(View v) {
        v.setOnTouchListener((view, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.97f).scaleY(0.97f).alpha(0.85f)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .setDuration(80).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .setDuration(140).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    /**
     * Wraps a click listener so the view ticks (a short haptic) before running
     * the action. {@link #button} applies this to every button it builds; call
     * it directly when wiring a hand-rolled {@link Button} — the tab bar, the
     * PIN pad, the segmented control — so those tick too. FLAG_IGNORE_GLOBAL_SETTING
     * fires the tick even when the system's touch-vibration toggle is off.
     */
    public static View.OnClickListener haptic(View.OnClickListener l) {
        return v -> {
            v.setHapticFeedbackEnabled(true);
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            if (l != null) {
                l.onClick(v);
            }
        };
    }

    public static Button accent(Context c, String label, View.OnClickListener l) {
        Button b = button(c, label, l);
        b.setTextColor(ON_ACCENT);
        b.setTypeface(b.getTypeface(), Typeface.BOLD);
        b.setBackground(pressable(c, ACCENT, RADIUS_CONTROL, 0, alpha(0xFF000000, 0x33)));
        return b;
    }

    public static Button danger(Context c, String label, View.OnClickListener l) {
        Button b = button(c, label, l);
        b.setTextColor(DANGER_TEXT);
        b.setBackground(pressable(c, DANGER_BG, RADIUS_CONTROL, DANGER_EDGE, alpha(DANGER_RIPPLE, 0x44)));
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

    /**
     * Makes the children of a button row share its width equally, each on one
     * line. Without this a row of three buttons overflows on a narrow phone and
     * the last label breaks mid-word.
     */
    public static LinearLayout spread(LinearLayout row) {
        Context c = row.getContext();
        for (int i = 0; i < row.getChildCount(); i++) {
            View v = row.getChildAt(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.leftMargin = i == 0 ? 0 : dp(c, 8);
            v.setLayoutParams(lp);
            if (v instanceof Button) {
                Button b = (Button) v;
                b.setSingleLine(true);
                b.setEllipsize(android.text.TextUtils.TruncateAt.END);
                b.setMinWidth(0);
                b.setMinimumWidth(0);
                b.setPadding(dp(c, 8), b.getPaddingTop(), dp(c, 8), b.getPaddingBottom());
            }
        }
        return row;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        // ELEV: one step lighter than a section, so nested cards have depth.
        l.setBackground(round(c, ELEV, RADIUS_CARD, LINE));
        return l;
    }

    /** A scrollable page with the standard padding, for one tab. */
    public static ScrollView page(Context c) {
        ScrollView s = new ScrollView(c);
        s.setFillViewport(true);
        s.setBackgroundColor(BG);
        LinearLayout body = column(c);
        body.setPadding(dp(c, 16), dp(c, 12), dp(c, 16), dp(c, 24));
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
        return String.format(Locale.US, "%." + decimals + "f", value);
    }

    public static String pct(double value) {
        if (Double.isNaN(value)) {
            return "-";
        }
        return String.format(Locale.US, "%.1f %%", value);
    }

    /** "just now", "5 min ago", "2 h ago", "3 d ago" from an epoch-millis time. */
    public static String ago(long epochMillis) {
        if (epochMillis <= 0) {
            return DASH;
        }
        long s = (System.currentTimeMillis() - epochMillis) / 1000;
        if (s < 0) {
            return "just now";
        }
        if (s < 45) {
            return "just now";
        }
        if (s < 3600) {
            return (s / 60) + " min ago";
        }
        if (s < 86400) {
            return (s / 3600) + " h ago";
        }
        return (s / 86400) + " d ago";
    }

    /** Great-circle distance in metres between two lat/lon points (haversine). */
    public static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** "420 m" under a km, "3.4 km" above. */
    public static String distanceLabel(double metres) {
        if (Double.isNaN(metres)) {
            return DASH;
        }
        if (metres < 1000) {
            return Math.round(metres) + " m";
        }
        return String.format(Locale.ROOT, "%.1f km", metres / 1000.0);
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
        private boolean dimmed = true;

        public Stat(Context c, String label) {
            super(c);
            setOrientation(VERTICAL);
            setPadding(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
            setBackground(round(c, CARD, RADIUS_CARD, LINE));

            TextView key = text(c, label.toUpperCase(Locale.ROOT), 10, DIM);
            key.setLetterSpacing(0.06f);
            addView(key);

            LinearLayout line = row(c);
            line.setPadding(0, dp(c, 3), 0, 0);
            value = number(text(c, DASH, 22, DIM));
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
            // An empty tile reads as "no data", not as a value: dim the dash.
            boolean empty = DASH.equals(text) || "-".equals(text);
            if (empty != dimmed) {
                dimmed = empty;
                value.setTextColor(empty ? DIM : TEXT);
            }
            return this;
        }

        public Stat set(String text, int color) {
            set(text);
            dimmed = false;
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
        card.setBackground(round(c, CARD, RADIUS_CARD, LINE));

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
