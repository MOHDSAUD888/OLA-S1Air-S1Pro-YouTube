package dev.olatool.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One flat read of every signal the two dashboard panels draw.
 *
 * <p>The panels used to have to know which CAN signal fed which row. Keeping
 * that mapping here means the screens stay layout code, and the same object can
 * be filled by hand when the UI is rendered without a scooter (see the debug
 * preview screen).
 *
 * <p>Values stay {@link Double#NaN} until the vehicle reports them, exactly
 * like {@link Live}. Nothing here writes to the bus.
 */
public final class PanelData {

    /** Signals the panels need beyond the fields already on Snapshot. */
    private static final String[] EXTRA = {
            "Vehicle_Range_Normal",
            "Vehicle_Range_Limp",
            "Vehicle_Range_Sport",
            "Vehicle_Range_Hyper",

            "Motor_Current",
            "Motor_Torque",
            "Motor_Frequency",
            "Position_Counter",
            "Rotor_Angle",

            "Front_Brake_Press",
            "Rear_Brake_Press",

            "High_Low_beam_short_press",
            "Horn",
            "Left_Indicator",
            "Right_Indicator",
            "Drive_Mode",
            "Cruise_mode",
            "Accept_Conf_short_press",
            "Reject_Cancel_short_press",
            "Play_Pause_short_press",

            "Charger_Status_Signal",
            "Charger_Plugged_In",
            "Charger_Connected",
            "Time_to_chargeSC_80",
            "Time_to_Full_chargeSC_00",
            "Time_to_chargeFC_80",
            "Time_to_chargeFC_100",
            "Total_Charge_Time",
            "Total_Kwhr_Charged_in_the_Mode",
            "Factored_Charge_Amphr",

            "Delta_Voltage_00",
            "BMS0_Cell_Min_Voltage",
            "BMS0_Cell_Max_Voltage",

            "Balancing_Status_Battery_Cell_1",
            "Balancing_Status_Battery_Cell_2",
            "Balancing_Status_Battery_Cell_3",
            "Balancing_Status_Battery_Cell_4",
            "Balancing_Status_Battery_Cell_5",
            "Balancing_Status_Battery_Cell_6",
            "Balancing_Status_Battery_Cell_7",
            "Balancing_Status_Battery_Cell_8",
            "Balancing_Status_Battery_Cell_9",
            "Balancing_Status_Battery_Cell_10",
            "Balancing_Status_Battery_Cell_11",
            "Balancing_Status_Battery_Cell_12",
            "Balancing_Status_Battery_Cell_13",
            "Balancing_Status_Battery_Cell_14",
    };

    public long frames;
    public long lastFrameMs;
    public long vehicleAt;
    public long sohAt;
    public int pps;
    public int activeFaults;

    public double soc = Double.NaN;
    public double soh = Double.NaN;
    public double speedKph = Double.NaN;
    public double rangeKm = Double.NaN;
    public double odoKm = Double.NaN;
    public double packV = Double.NaN;
    public double packA = Double.NaN;
    public double mcuV = Double.NaN;
    public double imbalanceV = Double.NaN;
    public double cellMinV = Double.NaN;
    public double cellMaxV = Double.NaN;
    public double packC = Double.NaN;
    public double mcuC = Double.NaN;
    public double motorC = Double.NaN;
    public double rpm = Double.NaN;
    public double throttleV = Double.NaN;
    public double chargeRate = Double.NaN;

    /** -1 means "not reported", 0 false, 1 true. */
    public int charging = -1;
    public int plugged = -1;
    public int sideStandUp = -1;
    public int seatUnlocked = -1;
    public int unlocked = -1;

    public String driveMode = "";

    public final double[] cells = new double[14];

    /** Extra signal values, keyed by the exact name in can_database.json. */
    public final Map<String, Double> signals = new LinkedHashMap<>();

    public PanelData() {
        for (int i = 0; i < cells.length; i++) {
            cells[i] = Double.NaN;
        }
    }

    /** Copies a live snapshot and looks up the extra signals once. */
    public static PanelData from(Live.Snapshot s, Live live) {
        PanelData d = new PanelData();
        if (s != null) {
            d.frames = s.frames;
            d.lastFrameMs = s.lastFrameMs;
            d.vehicleAt = s.vehicleAt;
            d.sohAt = s.sohAt;
            d.pps = s.pps;
            d.activeFaults = s.activeFaults == null ? 0 : s.activeFaults.size();
            d.soc = s.soc;
            d.soh = s.soh;
            d.speedKph = s.speedKph;
            d.rangeKm = s.rangeKm;
            d.odoKm = s.odoKm;
            d.packV = s.packV;
            d.packA = s.packA;
            d.mcuV = s.mcuV;
            d.imbalanceV = s.imbalanceV;
            d.cellMinV = s.cellMinV;
            d.cellMaxV = s.cellMaxV;
            d.packC = s.packC;
            d.mcuC = s.mcuC;
            d.motorC = s.motorC;
            d.rpm = s.rpm;
            d.throttleV = s.throttleV;
            d.chargeRate = s.chargeRate;
            d.charging = s.charging;
            d.plugged = s.plugged;
            d.sideStandUp = s.sideStandUp;
            d.seatUnlocked = s.seatUnlocked;
            d.unlocked = s.unlocked;
            d.driveMode = s.driveMode == null ? "" : s.driveMode;
            for (int i = 0; i < d.cells.length; i++) {
                d.cells[i] = s.cells[i];
            }
        }
        if (live != null) {
            for (String name : EXTRA) {
                d.signals.put(name, live.value(name));
            }
        }
        return d;
    }

    /** NaN when the signal has never been seen. */
    public double value(String name) {
        Double v = signals.get(name);
        return v == null ? Double.NaN : v;
    }

    /** -1 not reported, else 1 when the signal is 1 or more. */
    public int flag(String name) {
        double v = value(name);
        return Double.isNaN(v) ? -1 : (v >= 1 ? 1 : 0);
    }

    /** -1 not reported, else 1 when the signal is zero. */
    public int zeroTrue(String name) {
        double v = value(name);
        return Double.isNaN(v) ? -1 : (v == 0 ? 1 : 0);
    }

    public boolean reported(String name) {
        return !Double.isNaN(value(name));
    }

    public boolean hasFrames() {
        return frames > 0;
    }

    /**
     * True when only the telematics sleep trickle is arriving. Those frames
     * keep {@link #lastFrameMs} fresh without carrying any vehicle data.
     */
    public boolean vehicleStale(long nowMs, long ms) {
        return frames > 0 && (vehicleAt == 0 || nowMs - vehicleAt > ms);
    }

    /** True when SOH was seen once but has not been refreshed since. */
    public boolean sohStale(long nowMs, long ms) {
        return sohAt > 0 && nowMs - sohAt > ms;
    }

    public int cellCount() {
        int n = 0;
        for (double v : cells) {
            if (!Double.isNaN(v) && v > 0) {
                n++;
            }
        }
        return n;
    }

    public double cellSpreadV() {
        if (Double.isNaN(cellMinV) || Double.isNaN(cellMaxV)) {
            return Double.NaN;
        }
        return cellMaxV - cellMinV;
    }
}
