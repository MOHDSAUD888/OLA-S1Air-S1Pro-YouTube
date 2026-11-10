package dev.olatool.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Advisory battery thermal/health risk assessment for a single pack.
 *
 * <p>This is a <em>read-only diagnostic aid</em>: it turns the raw battery
 * telemetry the tool already reads (pack/cell temperature, cell-voltage spread,
 * state-of-health, the BMS's own over-temperature flags and any active thermal
 * faults) into one risk verdict with plain-language reasons, so an engineer can
 * spot an at-risk unit without eyeballing twenty signals.
 *
 * <p>It <strong>never</strong> acts on the vehicle. It does not and must not
 * command, disable, cut off or "kill" a pack. The safe response to a flagged
 * unit is human escalation: contact the owner, pull the unit in for service, or
 * hand it to the BMS team — the vehicle's own BMS owns any real-time thermal
 * cutoff, not this tool.
 *
 * <p>The thresholds below are conservative, lithium-ion rule-of-thumb values
 * kept as named constants so a battery engineer can tune them in one place.
 * They are advisory, not a safety certification.
 */
public final class BatteryHealth {

    private BatteryHealth() {
    }

    /** Risk bands, least to most severe. {@link #UNKNOWN} means "not enough data". */
    public enum Risk {
        UNKNOWN, OK, WATCH, AT_RISK, CRITICAL;

        /** The more severe of two bands, treating UNKNOWN as the least. */
        Risk max(Risk other) {
            return other.ordinal() > this.ordinal() ? other : this;
        }
    }

    // ---- thresholds (tune here) -------------------------------------------

    /** Pack/cell temperature (°C) at which to start watching. */
    public static final double TEMP_WATCH_C = 45;
    /** Temperature (°C) treated as an at-risk discharge condition. */
    public static final double TEMP_AT_RISK_C = 55;
    /** Temperature (°C) treated as critical (thermal-runaway risk rises). */
    public static final double TEMP_CRITICAL_C = 60;
    /** While charging, cells should stay cooler; watch from here. */
    public static final double TEMP_CHARGING_WATCH_C = 42;

    /** Cell-voltage spread (V) that warrants watching. */
    public static final double SPREAD_WATCH_V = 0.10;
    /** Cell-voltage spread (V) treated as at-risk imbalance. */
    public static final double SPREAD_AT_RISK_V = 0.20;

    /** State-of-health (%) below which the pack counts as degraded. */
    public static final double SOH_WATCH_PCT = 80;
    /** State-of-health (%) below which degradation is pronounced. */
    public static final double SOH_AT_RISK_PCT = 70;

    // ---- inputs / result --------------------------------------------------

    /** Raw, already-decoded telemetry for one pack. NaN = not reported. */
    public static final class Inputs {
        public double maxTempC = Double.NaN;     // hottest reported sensor/cell
        public double avgTempC = Double.NaN;     // effective/average pack temp
        public double cellSpreadV = Double.NaN;  // max cell V − min cell V
        public double sohPct = Double.NaN;
        public double socPct = Double.NaN;
        public boolean charging;
        public boolean bmsOverTempWarning;       // BMS's own over-temp warning flag
        public boolean bmsOverTempError;         // BMS's own over-temp error flag
        public int activeThermalFaults;          // active faults that look thermal

        /** True when nothing usable was reported, so a verdict would be noise. */
        boolean noData() {
            return Double.isNaN(maxTempC) && Double.isNaN(avgTempC)
                    && Double.isNaN(cellSpreadV) && Double.isNaN(sohPct)
                    && !bmsOverTempWarning && !bmsOverTempError
                    && activeThermalFaults == 0;
        }
    }

    /** A verdict: a band, a one-line headline, and the reasons behind it. */
    public static final class Result {
        public final Risk risk;
        public final String headline;
        public final List<String> reasons;

        Result(Risk risk, String headline, List<String> reasons) {
            this.risk = risk;
            this.headline = headline;
            this.reasons = reasons;
        }

        public boolean flagged() {
            return risk == Risk.AT_RISK || risk == Risk.CRITICAL;
        }
    }

    // ---- assessment -------------------------------------------------------

    /** Pure risk assessment. Never throws; order of inputs does not matter. */
    public static Result assess(Inputs in) {
        if (in == null || in.noData()) {
            return new Result(Risk.UNKNOWN, "No battery data", new ArrayList<>());
        }

        Risk risk = Risk.OK;
        List<String> reasons = new ArrayList<>();

        // The BMS's own flags are the most trustworthy signal we have.
        if (in.bmsOverTempError) {
            risk = risk.max(Risk.CRITICAL);
            reasons.add("BMS over-temperature ERROR flag is set");
        }
        if (in.bmsOverTempWarning) {
            risk = risk.max(Risk.AT_RISK);
            reasons.add("BMS over-temperature warning flag is set");
        }

        // Temperature.
        double t = maxPresent(in.maxTempC, in.avgTempC);
        if (!Double.isNaN(t)) {
            if (t >= TEMP_CRITICAL_C) {
                risk = risk.max(Risk.CRITICAL);
                reasons.add(fmt("Battery temperature %.0f°C ≥ %.0f°C (critical)", t, TEMP_CRITICAL_C));
            } else if (t >= TEMP_AT_RISK_C) {
                risk = risk.max(Risk.AT_RISK);
                reasons.add(fmt("Battery temperature %.0f°C ≥ %.0f°C (at-risk)", t, TEMP_AT_RISK_C));
            } else if (in.charging && t >= TEMP_CHARGING_WATCH_C) {
                risk = risk.max(Risk.WATCH);
                reasons.add(fmt("Charging at %.0f°C ≥ %.0f°C (cells should stay cooler while charging)",
                        t, TEMP_CHARGING_WATCH_C));
            } else if (t >= TEMP_WATCH_C) {
                risk = risk.max(Risk.WATCH);
                reasons.add(fmt("Battery temperature %.0f°C ≥ %.0f°C (watch)", t, TEMP_WATCH_C));
            }
        }

        // Cell imbalance — a widening spread can precede a weak/failing cell.
        if (!Double.isNaN(in.cellSpreadV)) {
            double mv = in.cellSpreadV * 1000.0;
            if (in.cellSpreadV >= SPREAD_AT_RISK_V) {
                risk = risk.max(Risk.AT_RISK);
                reasons.add(fmt("Cell imbalance %.0f mV ≥ %.0f mV (at-risk)", mv, SPREAD_AT_RISK_V * 1000));
            } else if (in.cellSpreadV >= SPREAD_WATCH_V) {
                risk = risk.max(Risk.WATCH);
                reasons.add(fmt("Cell imbalance %.0f mV ≥ %.0f mV (watch)", mv, SPREAD_WATCH_V * 1000));
            }
        }

        // State of health — degradation is a service signal, not a fire signal,
        // so it escalates to WATCH at most on its own.
        if (!Double.isNaN(in.sohPct)) {
            if (in.sohPct <= SOH_AT_RISK_PCT) {
                risk = risk.max(Risk.WATCH);
                reasons.add(fmt("State of health %.0f%% ≤ %.0f%% (pronounced degradation)",
                        in.sohPct, SOH_AT_RISK_PCT));
            } else if (in.sohPct <= SOH_WATCH_PCT) {
                risk = risk.max(Risk.WATCH);
                reasons.add(fmt("State of health %.0f%% ≤ %.0f%% (degraded)", in.sohPct, SOH_WATCH_PCT));
            }
        }

        // Any BMS/thermal fault already latched by the fault table.
        if (in.activeThermalFaults > 0) {
            risk = risk.max(Risk.AT_RISK);
            reasons.add(in.activeThermalFaults + " active thermal/BMS fault"
                    + (in.activeThermalFaults == 1 ? "" : "s"));
        }

        if (reasons.isEmpty()) {
            reasons.add("All monitored battery signals within normal range");
        }
        return new Result(risk, headline(risk), reasons);
    }

    /**
     * Convenience: build {@link Inputs} from a live snapshot's raw signals and
     * assess. Uses the hottest of the reported temperature sensors and the
     * BMS's own over-temp flags. Read-only; touches nothing on the vehicle.
     */
    public static Result from(Live live) {
        if (live == null) {
            return new Result(Risk.UNKNOWN, "No battery data", new ArrayList<>());
        }
        Inputs in = new Inputs();
        in.maxTempC = maxPresent(
                live.value("AFE_Max_Temp"),
                live.value("Temperature_Sensing_Max"),
                live.value("AI_TEMP_SENSOR_MEASURE_MAX"),
                live.value("Effective_Battery_Temperature_00"));
        in.avgTempC = maxPresent(
                live.value("Effective_Battery_Temperature_00"),
                live.value("Temperature_Sensing"),
                live.value("AI_TEMP_SENSOR_MEASURE_AVG"));
        in.cellSpreadV = firstPresent(
                live.value("Delta_Voltage_00"),
                minus(live.value("BMS0_Cell_Max_Voltage"), live.value("BMS0_Cell_Min_Voltage")));
        in.sohPct = firstPresent(live.value("SOH_Disp"), live.value("Pack_SoH"));
        in.socPct = live.value("Display_SoC");
        in.charging = live.value("Charger_Status_Signal") >= 1;
        in.bmsOverTempWarning = live.value("BMS_over_temp_drive_warning_BSW_signal") >= 1;
        in.bmsOverTempError = live.value("BMS_over_temp_drive_error_BSW_signal") >= 1;
        in.activeThermalFaults = countThermalFaults(live.activeFaults());
        return assess(in);
    }

    private static int countThermalFaults(List<Faults.Fault> active) {
        int n = 0;
        if (active != null) {
            for (Faults.Fault f : active) {
                if (f == null) {
                    continue;
                }
                if (looksThermal(f.id) || looksThermal(f.title) || looksThermal(f.component)) {
                    n++;
                }
            }
        }
        return n;
    }

    private static boolean looksThermal(String s) {
        if (s == null) {
            return false;
        }
        String l = s.toLowerCase();
        return l.contains("temp") || l.contains("thermal") || l.contains("overheat");
    }

    // ---- small numeric helpers -------------------------------------------

    /** Largest of the reported (non-NaN) values, or NaN if none reported. */
    public static double maxPresent(double... xs) {
        double out = Double.NaN;
        for (double x : xs) {
            if (!Double.isNaN(x)) {
                out = Double.isNaN(out) ? x : Math.max(out, x);
            }
        }
        return out;
    }

    /** First reported (non-NaN) value, or NaN if none reported. */
    static double firstPresent(double... xs) {
        for (double x : xs) {
            if (!Double.isNaN(x)) {
                return x;
            }
        }
        return Double.NaN;
    }

    private static double minus(double a, double b) {
        return (Double.isNaN(a) || Double.isNaN(b)) ? Double.NaN : a - b;
    }

    private static String headline(Risk risk) {
        switch (risk) {
            case CRITICAL: return "CRITICAL — pull this unit now, escalate to BMS team";
            case AT_RISK:  return "At risk — schedule service / contact owner";
            case WATCH:    return "Watch — keep monitoring";
            case OK:       return "OK — within normal range";
            default:       return "No battery data";
        }
    }

    private static String fmt(String f, Object... a) {
        return String.format(java.util.Locale.US, f, a);
    }
}
