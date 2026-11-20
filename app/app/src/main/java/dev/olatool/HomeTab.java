package dev.olatool;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

import dev.olatool.core.Live;

/**
 * Home dashboard — the scooter at a glance, modelled on the S1Mods landing: a
 * live vehicle-stats card, quick Lock / Unlock / Locate actions, and a one-tap
 * jump to the mod installer. It reuses the shared data {@link Bus} for stats and
 * the Ola cloud for lock/unlock, so it adds a face, not a new backend.
 */
public final class HomeTab implements Tab {

    private final MainActivity activity;
    private final Conn conn;
    private final Bus bus;
    private final ScrollView page;

    private final ProgressBar socBar;
    private final TextView socPct;
    private final Ui.Stat speedStat;
    private final Ui.Stat rangeStat;
    private final Ui.Stat odoStat;

    private final android.os.Handler handler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 3000);
        }
    };

    public HomeTab(MainActivity activity, Conn conn, Bus bus) {
        this.activity = activity;
        this.conn = conn;
        this.bus = bus;
        this.page = Ui.page(activity);
        LinearLayout body = Ui.body(page);

        body.addView(Ui.pageTitle(activity, "Home", "Your scooter at a glance."));

        // --- Vehicle stats --------------------------------------------------
        Ui.Section veh = Ui.section(activity, "Vehicle", null);
        TextView chargeKey = Ui.text(activity, "STATE OF CHARGE", 10, Ui.DIM);
        chargeKey.setLetterSpacing(0.06f);
        veh.body.addView(chargeKey);

        LinearLayout socRow = Ui.row(activity);
        socRow.setGravity(Gravity.CENTER_VERTICAL);
        socRow.setPadding(0, Ui.dp(activity, 6), 0, Ui.dp(activity, 2));
        socPct = Ui.number(Ui.text(activity, "—", 30, Ui.TEXT));
        socRow.addView(socPct);
        veh.body.addView(socRow);

        socBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        socBar.setMax(100);
        socBar.setProgress(0);
        socBar.getProgressDrawable().setColorFilter(
                Ui.ACCENT, android.graphics.PorterDuff.Mode.SRC_IN);
        veh.body.addView(socBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 10)));

        LinearLayout tiles = Ui.row(activity);
        tiles.setPadding(0, Ui.dp(activity, 12), 0, 0);
        speedStat = new Ui.Stat(activity, "Speed");
        rangeStat = new Ui.Stat(activity, "Range");
        odoStat = new Ui.Stat(activity, "Odometer");
        tiles.addView(speedStat, weight());
        tiles.addView(Ui.space(activity, 0), fixed(8));
        tiles.addView(rangeStat, weight());
        tiles.addView(Ui.space(activity, 0), fixed(8));
        tiles.addView(odoStat, weight());
        veh.body.addView(tiles);
        body.addView(veh.card);
        body.addView(Ui.space(activity, 10));

        // --- Quick actions --------------------------------------------------
        Ui.Section act = Ui.section(activity, "Quick actions", null, Ui.INFO);
        act.body.addView(Ui.note(activity,
                "Lock / Unlock go over the Ola cloud (set the token in the Cloud tab)."));
        LinearLayout actRow = Ui.row(activity);
        actRow.setPadding(0, Ui.dp(activity, 10), 0, 0);
        actRow.addView(tint("🔒 Lock", Ui.WARN, v -> cloudCmd("Lock", "LOCK_COMMAND")), weight());
        actRow.addView(Ui.space(activity, 0), fixed(8));
        actRow.addView(tint("🔓 Unlock", Ui.ACCENT, v -> cloudCmd("Unlock", "UNLOCK_COMMAND")), weight());
        actRow.addView(Ui.space(activity, 0), fixed(8));
        actRow.addView(Ui.button(activity, "📍 Locate", v -> activity.openTrack()), weight());
        act.body.addView(actRow);
        body.addView(act.card);
        body.addView(Ui.space(activity, 10));

        // --- More mods ------------------------------------------------------
        Ui.Section mods = Ui.section(activity, "More mods", null, Ui.LILAC);
        mods.body.addView(Ui.note(activity,
                "Install the dash mods — Android Auto, CarPlay, YouTube and S1 Nav — "
                        + "in one place."));
        mods.body.addView(Ui.accent(activity, "⬇  Open mod installer", v -> activity.openInstaller()),
                topMargin(10));
        body.addView(mods.card);

        refresh();
    }

    private Button tint(String label, int color, View.OnClickListener l) {
        Button b = Ui.button(activity, label, l);
        b.setTextColor(color);
        b.setBackground(Ui.round(activity, Ui.alpha(color, 0x18), Ui.RADIUS_CONTROL,
                Ui.alpha(color, 0x55)));
        return b;
    }

    private void cloudCmd(String name, String type) {
        if (!Access.require(activity, Access.ADMIN)) {
            return;
        }
        if (!OlaCloud.hasToken(activity)) {
            Toast.makeText(activity, "Set the Ola cloud token first (Cloud tab).",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(activity, name + "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            OlaCloud.CommandResult r = OlaCloud.issueCommand(activity, type);
            activity.runOnUiThread(() -> Toast.makeText(activity,
                    name + ": " + (r.ok ? "sent ✓" : r.message), Toast.LENGTH_LONG).show());
        }, "home-cmd").start();
    }

    private void refresh() {
        Live.Snapshot s = bus != null ? bus.snapshot() : null;
        if (s == null) {
            return;
        }
        if (!Double.isNaN(s.soc)) {
            int pct = (int) Math.round(s.soc);
            socPct.setText(pct + "%");
            socBar.setProgress(Math.max(0, Math.min(100, pct)));
        }
        speedStat.value.setText(fmt(s.speedKph, 0));
        rangeStat.value.setText(fmt(s.rangeKm, 0));
        odoStat.value.setText(fmt(s.odoKm, 0));
    }

    private static String fmt(double v, int decimals) {
        if (Double.isNaN(v)) {
            return "—";
        }
        return decimals <= 0
                ? String.valueOf(Math.round(v))
                : String.format(Locale.US, "%." + decimals + "f", v);
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams fixed(int dp) {
        return new LinearLayout.LayoutParams(Ui.dp(activity, dp),
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams topMargin(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(activity, dp);
        return lp;
    }

    @Override
    public View view() {
        return page;
    }

    @Override
    public void onShow() {
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    @Override
    public void onHide() {
        handler.removeCallbacks(tick);
    }
}
