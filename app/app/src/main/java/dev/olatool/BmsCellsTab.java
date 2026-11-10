package dev.olatool;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import dev.olatool.core.Live;

/**
 * The BMS "Cells" screen: a compact pack summary plus the full per-cell grid,
 * live from the shared {@link Bus}. Everything here is read from the CAN bus the
 * app already taps; nothing is sent to the vehicle.
 */
final class BmsCellsTab implements Tab, Bus.Listener {

    private final Activity activity;
    private final Bus bus;
    private final ScrollView page;
    private final BmsCellPanel cellPanel;

    private final Ui.Stat socStat;
    private final Ui.Stat packVStat;
    private final Ui.Stat packAStat;
    private final Ui.Stat tempStat;
    private final Ui.Stat sohStat;
    private final Ui.Stat spreadStat;

    private boolean visible;

    BmsCellsTab(Activity activity, Bus bus) {
        this.activity = activity;
        this.bus = bus;
        this.page = Ui.page(activity);
        LinearLayout body = Ui.body(page);

        body.addView(Ui.pageTitle(activity, "Cells",
                "Per-cell voltages, balancing and pack health — live from the CAN bus.",
                Ui.INFO));

        socStat = new Ui.Stat(activity, "Charge"); socStat.unit("%");
        sohStat = new Ui.Stat(activity, "Health"); sohStat.unit("%");
        packVStat = new Ui.Stat(activity, "Pack"); packVStat.unit("V");
        packAStat = new Ui.Stat(activity, "Current"); packAStat.unit("A");
        tempStat = new Ui.Stat(activity, "Pack temp"); tempStat.unit("°C");
        spreadStat = new Ui.Stat(activity, "Spread"); spreadStat.unit("mV");
        body.addView(Ui.statRow(activity, socStat, sohStat));
        body.addView(Ui.space(activity, 8));
        body.addView(Ui.statRow(activity, packVStat, packAStat));
        body.addView(Ui.space(activity, 8));
        body.addView(Ui.statRow(activity, tempStat, spreadStat));
        body.addView(Ui.space(activity, 10));

        LinearLayout cells = Ui.card(activity);
        cells.addView(Ui.heading(activity, "Cells (14S)"));
        cellPanel = new BmsCellPanel(activity);
        cells.addView(cellPanel);
        body.addView(cells);
    }

    @Override
    public View view() {
        return page;
    }

    @Override
    public void onShow() {
        visible = true;
        bus.attach(this);
        if (bus.snapshot() != null) {
            render(bus.snapshot());
        }
    }

    @Override
    public void onHide() {
        visible = false;
        bus.detach(this);
    }

    @Override
    public void onTick(Live.Snapshot s) {
        if (visible) {
            render(s);
        }
    }

    private void render(Live.Snapshot s) {
        if (s == null) {
            return;
        }
        socStat.set(num(s.soc, 0));
        sohStat.set(num(s.soh, 0));
        packVStat.set(num(s.packV, 1));
        packAStat.set(num(s.packA, 1));
        tempStat.set(num(s.packC, 1));
        double spreadMv = s.cellSpreadV() * 1000.0;
        int c = Double.isNaN(spreadMv) ? Ui.TEXT
                : spreadMv < 30 ? Ui.ACCENT : spreadMv < 60 ? Ui.WARN : Ui.BAD;
        spreadStat.set(num(spreadMv, 0), c);
        cellPanel.update(s, bus.live());
    }

    private static String num(double v, int dec) {
        return Double.isNaN(v) ? Ui.DASH : Ui.fmt(v, dec);
    }
}
