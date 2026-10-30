package dev.olatool.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the app learns from the bus, kept in one place.
 *
 * <p>Feed it frames with {@link #onFrame}; read it with {@link #snapshot}.
 * Values stay {@link Double#NaN} until a frame carrying them arrives, so "not
 * reported yet" and "zero" never look the same.
 *
 * <p>This is the Java equivalent of Axiom's parser + interpreter + stream
 * manager, minus the Android and coroutine parts.
 */
public final class Live {

    /** How many frames the log keeps. Enough to scroll, small enough to be cheap. */
    public static final int LOG_MAX = 500;

    /** One logged frame. */
    public static final class Entry {
        public final long at;
        public final int id;
        public final String name;
        public final String hex;

        Entry(long at, int id, String name, String hex) {
            this.at = at;
            this.id = id;
            this.name = name;
            this.hex = hex;
        }
    }

    /** A consistent read of the current state. */
    public static final class Snapshot {
        public long frames;
        public int pps;
        public int messagesSeen;
        public int signalsSeen;
        public long lastFrameMs;
        public long firstFrameMs;

        public double soc = Double.NaN;
        public double soh = Double.NaN;
        public double speedKph = Double.NaN;
        public double rangeKm = Double.NaN;
        public double rangeEcoKm = Double.NaN;
        public double rangeSportKm = Double.NaN;
        public double rangeHyperKm = Double.NaN;
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
        public double cycles = Double.NaN;
        public int charging = -1;
        public int plugged = -1;
        public int sideStandUp = -1;
        public int seatUnlocked = -1;
        public int unlocked = -1;
        public int fallState = -1;
        public int towState = -1;
        public int regenState = -1;
        public int hillState = -1;
        public String driveMode = "";
        public final double[] cells = new double[14];
        public List<Faults.Fault> activeFaults = Collections.emptyList();

        Snapshot() {
            for (int i = 0; i < cells.length; i++) {
                cells[i] = Double.NaN;
            }
        }

        public double powerW() {
            if (Double.isNaN(packV) || Double.isNaN(packA)) {
                return Double.NaN;
            }
            return packV * packA;
        }

        public double cellSpreadV() {
            if (Double.isNaN(cellMinV) || Double.isNaN(cellMaxV)) {
                return Double.NaN;
            }
            return cellMaxV - cellMinV;
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

        public boolean empty() {
            return frames == 0;
        }

        // ---------------------------------------------------------- safety

        public String fallName() {
            switch (fallState) {
                case 1: return "active";
                case 2: return "inactive";
                case 3: return "recovered";
                case 0: return "off (SNA)";
                default: return "unknown";
            }
        }

        public String towName() {
            switch (towState) {
                case 1: return "active";
                case 2: return "inactive";
                case 0: return "off (SNA)";
                default: return "unknown";
            }
        }

        public String regenName() {
            switch (regenState) {
                case 1: return "available";
                case 2: return "throttle limited";
                case 0: return "not available";
                default: return "unknown";
            }
        }

        public String hillName() {
            switch (hillState) {
                case 1: return "unavailable";
                case 2: return "active";
                case 3: return "not active";
                case 6: return "activated";
                case 0: return "unknown (SNA)";
                default: return "state " + hillState;
            }
        }
    }

    private final Dbc dbc;
    private final Faults faults;

    private final Map<String, Double> values = new LinkedHashMap<>();
    private final Map<Integer, Long> lastSeen = new LinkedHashMap<>();
    private final Map<Integer, Integer> counts = new LinkedHashMap<>();
    private final Map<String, Faults.Fault> latched = new LinkedHashMap<>();
    private final ArrayDeque<Entry> log = new ArrayDeque<>(LOG_MAX);

    private long frames;
    private long firstFrameMs;
    private long lastFrameMs;
    private long windowStartMs;
    private int windowFrames;
    private int pps;

    public Live(Dbc dbc, Faults faults) {
        this.dbc = dbc;
        this.faults = faults;
    }

    public Dbc dbc() {
        return dbc;
    }

    public Faults faults() {
        return faults;
    }

    /** Decodes one frame and merges it into the state. Cheap; call per frame. */
    public synchronized void onFrame(Can.Frame frame) {
        if (frame == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Dbc.Frame decoded = dbc.decodeFrame(frame);

        values.putAll(decoded.signals);
        frames++;
        if (firstFrameMs == 0) {
            firstFrameMs = now;
            windowStartMs = now;
        }
        lastFrameMs = now;
        lastSeen.put(frame.id, now);
        Integer c = counts.get(frame.id);
        counts.put(frame.id, c == null ? 1 : c + 1);

        for (Map.Entry<String, Double> e : decoded.signals.entrySet()) {
            double v = e.getValue();
            if (v >= 1.0) {
                Faults.Fault f = faults.forSignal(e.getKey());
                if (f != null) {
                    latched.put(f.id, f);
                }
            }
        }

        windowFrames++;
        long elapsed = now - windowStartMs;
        if (elapsed >= 1000) {
            pps = (int) ((windowFrames * 1000L) / elapsed);
            windowFrames = 0;
            windowStartMs = now;
        }

        String hex = "";
        if (frame.data != null) {
            StringBuilder sb = new StringBuilder(frame.dlc * 3);
            for (int i = 0; i < frame.dlc && i < frame.data.length; i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(String.format("%02X", frame.data[i] & 0xFF));
            }
            hex = sb.toString();
        }
        log.addFirst(new Entry(now, frame.id, decoded.name, hex));
        while (log.size() > LOG_MAX) {
            log.removeLast();
        }
    }

    /** Forgets everything, e.g. after a reconnect. */
    public synchronized void reset() {
        values.clear();
        lastSeen.clear();
        counts.clear();
        latched.clear();
        log.clear();
        frames = 0;
        firstFrameMs = 0;
        lastFrameMs = 0;
        windowStartMs = 0;
        windowFrames = 0;
        pps = 0;
    }

    public synchronized boolean hasData() {
        return frames > 0;
    }

    /** All signal values, latest frame wins. */
    public synchronized Map<String, Double> values() {
        return new LinkedHashMap<>(values);
    }

    public synchronized double value(String signal) {
        Double v = values.get(signal);
        return v == null ? Double.NaN : v;
    }

    /** Frame counts per CAN id, for the "what is on the bus" view. */
    public synchronized Map<Integer, Integer> counts() {
        return new LinkedHashMap<>(counts);
    }

    public synchronized long lastSeenMs(int canId) {
        Long t = lastSeen.get(canId);
        return t == null ? 0 : t;
    }

    /** Newest first. */
    public synchronized List<Entry> log() {
        return new ArrayList<>(log);
    }

    /** Faults that have ever been seen. The table is not cleared by a 0. */
    public synchronized List<Faults.Fault> latchedFaults() {
        return new ArrayList<>(latched.values());
    }

    /** Faults whose signal is 1 or more in the latest value of that signal. */
    public synchronized List<Faults.Fault> activeFaults() {
        Map<String, Faults.Fault> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : values.entrySet()) {
            if (e.getValue() < 1.0) {
                continue;
            }
            Faults.Fault f = faults.forSignal(e.getKey());
            if (f != null) {
                out.put(f.id, f);
            }
        }
        return new ArrayList<>(out.values());
    }

    public synchronized Snapshot snapshot() {
        Snapshot s = new Snapshot();
        s.frames = frames;
        s.pps = pps;
        s.messagesSeen = counts.size();
        s.signalsSeen = values.size();
        s.firstFrameMs = firstFrameMs;
        s.lastFrameMs = lastFrameMs;

        s.soc = value("Display_SoC");
        s.soh = value("SOH_Disp");
        if (Double.isNaN(s.soh)) {
            s.soh = value("Pack_SoH");
        }
        s.speedKph = value("Vehicle_speed_in_kmph");
        s.rangeKm = value("Vehicle_Range");
        s.rangeEcoKm = value("Vehicle_Range_Limp");
        s.rangeSportKm = value("Vehicle_Range_Sport");
        s.rangeHyperKm = value("Vehicle_Range_Hyper");
        s.odoKm = value("Odometer");
        s.packV = value("Bus_Voltage_00");
        s.packA = value("MCU_DC_Current");
        s.mcuV = value("MCU_Voltage");
        s.imbalanceV = value("Delta_Voltage_00");
        s.cellMinV = value("BMS0_Cell_Min_Voltage");
        s.cellMaxV = value("BMS0_Cell_Max_Voltage");
        s.packC = value("Effective_Battery_Temperature_00");
        s.mcuC = value("MCU_Temperatute");
        s.motorC = value("Motor_Temprature");
        s.rpm = value("Motor_RPM");
        s.throttleV = value("Throttle_Value1");
        s.chargeRate = value("Charging_Rate");
        s.cycles = value("Cycle_Number");

        for (int i = 0; i < s.cells.length; i++) {
            s.cells[i] = value("Cell_Voltage_Cell_" + (i + 1));
        }

        s.charging = flag("Charger_Status_Signal");
        s.plugged = pluggedIn();
        s.sideStandUp = zeroIsTrue("Side_Stand_Status");
        s.seatUnlocked = zeroIsTrue("Seat_Lock_Status");
        s.unlocked = oneIsTrue("Vehicle_state_BCM_ULock");
        s.fallState = state("Fall_Detection_State");
        s.towState = state("Tow_Theft_Detection");
        s.regenState = state("Regen_Availability");
        s.hillState = state("Hill_Hold_Availability");
        s.driveMode = driveMode();
        s.activeFaults = activeFaults();
        return s;
    }

    // -------------------------------------------------------------- helpers

    private int oneIsTrue(String signal) {
        double v = value(signal);
        return Double.isNaN(v) ? -1 : (v != 0 ? 1 : 0);
    }

    private int zeroIsTrue(String signal) {
        double v = value(signal);
        return Double.isNaN(v) ? -1 : (v == 0 ? 1 : 0);
    }

    private int flag(String signal) {
        double v = value(signal);
        if (Double.isNaN(v)) {
            return -1;
        }
        return v >= 1 ? 1 : 0;
    }

    /** A small state word (4-bit enums on 0x683); -1 when not reported. */
    private int state(String signal) {
        double v = value(signal);
        return Double.isNaN(v) ? -1 : (int) v;
    }

    private int pluggedIn() {
        double plugged = value("Charger_Plugged_In");
        double connected = value("Charger_Connected");
        if (Double.isNaN(plugged) && Double.isNaN(connected)) {
            return -1;
        }
        boolean on = (!Double.isNaN(plugged) && plugged >= 1)
                || (!Double.isNaN(connected) && connected >= 1);
        return on ? 1 : 0;
    }

    /** The dash's own mode word, decoded the way Axiom reads it. */
    public synchronized String driveMode() {
        double v = value("Vehicle_Discharge_Mode");
        if (Double.isNaN(v)) {
            return "";
        }
        switch ((int) v) {
            case 0: return "Parked";
            case 2: return "Normal";
            case 3: return "Sport";
            case 4: return "Custom";
            case 5: return "Reverse";
            case 6: return "Eco";
            case 7: return "Hyper";
            case 10: return "Reverse";
            case 13: return "Reverse";
            default: return "Mode " + (int) v;
        }
    }

    /** Signals that are 1 or more and have a fault row, keyed by signal name. */
    public synchronized Map<String, Faults.Fault> activeBySignal() {
        Map<String, Faults.Fault> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : values.entrySet()) {
            if (e.getValue() < 1.0) {
                continue;
            }
            Faults.Fault f = faults.forSignal(e.getKey());
            if (f != null) {
                out.put(e.getKey(), f);
            }
        }
        return out;
    }

    /** Clears the latched set without touching the live values. */
    public synchronized void clearLatched() {
        latched.clear();
    }

    /** Busiest message ids first, useful for the scan view. */
    public synchronized List<Map.Entry<Integer, Integer>> busiest() {
        List<Map.Entry<Integer, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        return list;
    }

    /** Kept for callers that want an empty map rather than null. */
    public static Map<String, Double> emptyValues() {
        return new HashMap<>();
    }
}
