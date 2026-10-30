package dev.olatool;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.olatool.core.PanelData;
import dev.olatool.core.VehicleInfo;

/**
 * Debug-only preview of the Vehicle and Diagnostics panels.
 *
 * <p>Everything here is synthetic: it exists so screenshots can be taken on an
 * emulator without a scooter. The class lives in the debug source set and is
 * never part of a release build.
 */
public final class UiPreviewActivity extends Activity {

    private LinearLayout header;
    private LinearLayout buttonRow;
    private View vehicleView;
    private View diagnosticsView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Ui.BG);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(
                    decor.getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

        VehicleTab vehicle = new VehicleTab(this, new Conn(), null);
        DiagnosticsTab diagnostics = new DiagnosticsTab(this, new Conn(), null);
        vehicle.render(demoVehicle());
        vehicle.applyInfo(demoInfo());
        diagnostics.render(demoDiagnostics());

        vehicleView = vehicle.view();
        diagnosticsView = diagnostics.view();

        setContentView(buildRoot());
        select("diagnostics".equals(getIntent().getStringExtra("tab")));
    }

    private View buildRoot() {
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(0, 0, 0, 0);

        header = Ui.column(this);

        TextView note = Ui.text(this,
                "Debug preview - synthetic data, not a live scooter", 11, Ui.DIM);
        note.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), Ui.dp(this, 10));
        header.addView(note);

        root.addView(header);

        FrameLayout content = new FrameLayout(this);
        content.addView(vehicleView, fill());
        content.addView(diagnosticsView, fill());
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private FrameLayout.LayoutParams fill() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private void select(boolean diagnostics) {
        vehicleView.setVisibility(diagnostics ? View.GONE : View.VISIBLE);
        diagnosticsView.setVisibility(diagnostics ? View.VISIBLE : View.GONE);
        if (buttonRow != null) {
            header.removeView(buttonRow);
        }
        buttonRow = buildButtonRow(diagnostics);
        header.addView(buttonRow, 0);
    }

    private LinearLayout buildButtonRow(boolean diagnostics) {
        LinearLayout row = Ui.row(this);
        row.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        row.addView(tabButton("Vehicle", !diagnostics, false), weight(false));
        row.addView(tabButton("Diagnostics", diagnostics, true), weight(true));
        return row;
    }

    private Button tabButton(String label, boolean selected, boolean diagnostics) {
        return selected
                ? Ui.accent(this, label, v -> select(diagnostics))
                : Ui.button(this, label, v -> select(diagnostics));
    }

    private LinearLayout.LayoutParams weight(boolean second) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (!second) {
            lp.rightMargin = Ui.dp(this, 8);
        }
        return lp;
    }

    // ------------------------------------------------------------- demo data

    private PanelData demoVehicle() {
        PanelData d = new PanelData();
        d.frames = 18432;
        d.lastFrameMs = System.currentTimeMillis();
        d.pps = 87;
        d.activeFaults = 0;
        d.soc = 72;
        d.soh = 98;
        d.speedKph = 0;
        d.rangeKm = 84;
        d.odoKm = 4218;
        d.packV = 51.8;
        d.packA = -1.2;
        d.mcuV = 51.6;
        d.imbalanceV = 0.012;
        d.cellMinV = 3.688;
        d.cellMaxV = 3.700;
        d.packC = 31.4;
        d.mcuC = 38.2;
        d.motorC = 36.8;
        d.rpm = 0;
        d.throttleV = 0.82;
        d.chargeRate = 0;
        d.charging = 1;
        d.plugged = 1;
        d.sideStandUp = 1;
        d.seatUnlocked = 0;
        d.unlocked = 1;
        d.driveMode = "Normal";

        double[] cells = {
                3.688, 3.690, 3.692, 3.694, 3.696, 3.698, 3.700,
                3.699, 3.697, 3.695, 3.693, 3.691, 3.689, 3.688
        };
        System.arraycopy(cells, 0, d.cells, 0, cells.length);

        d.signals.put("Vehicle_Range_Normal", 84.0);
        d.signals.put("Vehicle_Range_Limp", 96.0);
        d.signals.put("Vehicle_Range_Sport", 71.0);
        d.signals.put("Vehicle_Range_Hyper", 62.0);
        return d;
    }

    private VehicleInfo demoInfo() {
        VehicleInfo info = new VehicleInfo();
        info.vin = "TESTVIN0000000000";
        info.model = "S1 Pro";
        info.generation = "Gen 2";
        info.batteryVariant = "3.97 kWh";
        info.moveOs = "MoveOS 4.3.2";
        info.uptime = "312 hrs";
        info.registration = "KA 01 AB 1234";
        info.purchaseDate = "2024-03-18";
        info.loaded = true;
        return info;
    }

    private PanelData demoDiagnostics() {
        PanelData d = demoVehicle();

        d.signals.put("Horn", 1.0);
        d.signals.put("High_Low_beam_short_press", 0.0);
        d.signals.put("Left_Indicator", 0.0);
        d.signals.put("Right_Indicator", 0.0);
        d.signals.put("Drive_Mode", 0.0);
        d.signals.put("Cruise_mode", 0.0);
        d.signals.put("Accept_Conf_short_press", 0.0);
        d.signals.put("Reject_Cancel_short_press", 0.0);
        d.signals.put("Play_Pause_short_press", 0.0);

        d.signals.put("Front_Brake_Press", 0.0);
        d.signals.put("Rear_Brake_Press", 0.0);

        d.signals.put("Motor_Current", 0.4);
        d.signals.put("Motor_Torque", 1.2);
        d.signals.put("Motor_Frequency", 0.0);
        d.signals.put("Position_Counter", 128.0);
        d.signals.put("Rotor_Angle", 12.0);

        d.signals.put("Charger_Status_Signal", 1.0);
        d.signals.put("Charger_Plugged_In", 1.0);
        d.signals.put("Charger_Connected", 1.0);
        d.signals.put("Time_to_chargeSC_80", 95.0);
        d.signals.put("Time_to_Full_chargeSC_00", 140.0);
        d.signals.put("Time_to_chargeFC_80", 38.0);
        d.signals.put("Time_to_chargeFC_100", 52.0);
        d.signals.put("Total_Charge_Time", 96.0);
        d.signals.put("Total_Kwhr_Charged_in_the_Mode", 1.84);
        d.signals.put("Factored_Charge_Amphr", 35.6);

        d.signals.put("Delta_Voltage_00", 0.012);
        d.signals.put("BMS0_Cell_Min_Voltage", 3.688);
        d.signals.put("BMS0_Cell_Max_Voltage", 3.700);

        for (int i = 1; i <= 14; i++) {
            d.signals.put("Balancing_Status_Battery_Cell_" + i,
                    (i == 3 || i == 11) ? 1.0 : 0.0);
        }
        return d;
    }
}
