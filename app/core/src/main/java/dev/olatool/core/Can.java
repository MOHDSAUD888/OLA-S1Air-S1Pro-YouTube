package dev.olatool.core;

/**
 * CAN frame layout and the battery/motor signals this app shows.
 *
 * <p>Formulas are the ones proven against live captures and the vehicle
 * parser's own code; see docs/05-can-bus.md. Nothing here is guessed except
 * where it is marked.
 *
 * <p>Wire layout of {@code CANPacket.can_frame} is 16 bytes: CAN id big-endian
 * in [0:4], DLC in [4], padding in [5:8], data in [8:16]. A little-endian id is
 * silently dropped by the daemon, which is why {@link #frame} writes it BE.
 */
public final class Can {

    // Battery
    public static final int ID_SOC = 0x120;
    public static final int ID_CURRENT = 0x140;
    public static final int ID_VOLTAGE = 0x150;
    public static final int ID_TEMP = 0x160;
    public static final int ID_CELL_LIMITS = 0x170;
    public static final int ID_PACK_TEMPS = 0x1A4;
    public static final int ID_CELLS_1_4 = 0x1A5;
    public static final int ID_CELLS_5_8 = 0x1A6;
    public static final int ID_PACK_TEMPS_5_6 = 0x1A7;
    public static final int ID_AUX = 0x1A8;
    public static final int ID_CELLS_9_12 = 0x1A9;
    public static final int ID_CELLS_13_16 = 0x1AA;

    // Motor and controller
    public static final int ID_MOTOR = 0x313;

    // Dashboard and body
    public static final int ID_DISPLAY = 0x352;
    public static final int ID_ODOMETER = 0x356;
    public static final int ID_SAFETY = 0x683;

    /** Everything the battery screen subscribes to. */
    public static final int[] BATTERY_IDS = {
            ID_SOC, ID_CURRENT, ID_VOLTAGE, ID_TEMP, ID_CELL_LIMITS,
            ID_PACK_TEMPS, ID_CELLS_1_4, ID_CELLS_5_8, ID_PACK_TEMPS_5_6,
            ID_AUX, ID_CELLS_9_12, ID_CELLS_13_16,
            ID_MOTOR, ID_DISPLAY, ID_ODOMETER, ID_SAFETY
    };

    private Can() {
    }

    // --------------------------------------------------------------- frames

    public static final class Frame {
        public final int id;
        public final int dlc;
        public final byte[] data;

        public Frame(int id, int dlc, byte[] data) {
            this.id = id;
            this.dlc = dlc;
            this.data = data;
        }

        public static Frame parse(byte[] raw) {
            if (raw == null || raw.length < 16) {
                return null;
            }
            int be = ((raw[0] & 0xFF) << 24) | ((raw[1] & 0xFF) << 16)
                    | ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);
            int le = ((raw[3] & 0xFF) << 24) | ((raw[2] & 0xFF) << 16)
                    | ((raw[1] & 0xFF) << 8) | (raw[0] & 0xFF);
            int id = (be >= 0 && be <= 0x1FFFFFFF) ? be : le;
            int dlc = raw[4] & 0xFF;
            if (dlc < 0 || dlc > 8) {
                dlc = 8;
            }
            byte[] data = new byte[8];
            System.arraycopy(raw, 8, data, 0, Math.min(8, Math.max(0, raw.length - 8)));
            return new Frame(id, dlc, data);
        }

        public String dataHex() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < dlc; i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(String.format("%02X", data[i] & 0xFF));
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return String.format("0x%03X  dlc=%d  %s", id, dlc, dataHex());
        }
    }

    /** Builds a 16-byte writable frame. CAN id is big-endian on purpose. */
    public static byte[] frame(int canId, byte[] data) {
        byte[] out = new byte[16];
        out[0] = (byte) (canId >>> 24);
        out[1] = (byte) (canId >>> 16);
        out[2] = (byte) (canId >>> 8);
        out[3] = (byte) canId;
        int n = data == null ? 0 : Math.min(8, data.length);
        out[4] = (byte) n;
        if (n > 0) {
            System.arraycopy(data, 0, out, 8, n);
        }
        return out;
    }

    // ------------------------------------------------------------- decoding

    static int u8(byte[] d, int i) {
        return d[i] & 0xFF;
    }

    static int le16(byte[] d, int i) {
        return (d[i] & 0xFF) | ((d[i + 1] & 0xFF) << 8);
    }

    static long le32(byte[] d, int i) {
        return (d[i] & 0xFFL) | ((d[i + 1] & 0xFFL) << 8)
                | ((d[i + 2] & 0xFFL) << 16) | ((d[i + 3] & 0xFFL) << 24);
    }

    /**
     * Everything the battery screen knows. Feed it frames with {@link #apply};
     * a value stays {@link Double#NaN} until a frame carrying it arrives, which
     * is how the screen can tell "0 %" from "not reported yet".
     */
    public static final class Snapshot {
        public double soc = Double.NaN;
        public double displaySoc = Double.NaN;
        public double soh = Double.NaN;
        public double volts = Double.NaN;
        public double amps = Double.NaN;
        public double packTemp = Double.NaN;
        public double tempMin = Double.NaN;
        public double tempMax = Double.NaN;
        public double cellMinMv = Double.NaN;
        public double cellMaxMv = Double.NaN;
        public double auxVolts = Double.NaN;
        public double pduTemp = Double.NaN;
        public double afeTemp = Double.NaN;
        public double rpm = Double.NaN;
        public double mcuTemp = Double.NaN;
        public double motorTemp = Double.NaN;
        public double odoMeters = Double.NaN;
        public final double[] packTemps = new double[6];
        public final double[] cells = new double[16];
        public int fallDetection = -1;
        public int towTheft = -1;
        public int regen = -1;
        public int hillHold = -1;
        public boolean tamperWake;
        public int frames;

        public Snapshot() {
            for (int i = 0; i < packTemps.length; i++) {
                packTemps[i] = Double.NaN;
            }
            for (int i = 0; i < cells.length; i++) {
                cells[i] = Double.NaN;
            }
        }

        public void apply(Frame f) {
            if (f == null) {
                return;
            }
            frames++;
            byte[] d = f.data;
            switch (f.id) {
                case ID_SOC:
                    soc = le16(d, 2) * 0.01;
                    break;
                case ID_CURRENT:
                    amps = le16(d, 4) * 0.01 - 300.0;
                    break;
                case ID_VOLTAGE:
                    volts = le16(d, 0) * 0.001;
                    soh = u8(d, 5);
                    break;
                case ID_TEMP:
                    packTemp = le16(d, 1) * 0.1 - 50.0;
                    tempMin = le16(d, 3) * 0.1 - 50.0;
                    tempMax = le16(d, 5) * 0.1 - 50.0;
                    break;
                case ID_CELL_LIMITS:
                    cellMinMv = le16(d, 2);
                    cellMaxMv = le16(d, 4);
                    break;
                case ID_PACK_TEMPS:
                    for (int i = 0; i < 4; i++) {
                        packTemps[i] = le16(d, i * 2) * 0.1 - 50.0;
                    }
                    break;
                case ID_PACK_TEMPS_5_6:
                    packTemps[4] = le16(d, 0) * 0.1 - 50.0;
                    packTemps[5] = le16(d, 2) * 0.1 - 50.0;
                    break;
                case ID_AUX:
                    pduTemp = le16(d, 0) * 0.1 - 50.0;
                    afeTemp = le16(d, 2) * 0.1 - 50.0;
                    auxVolts = le16(d, 4) * 0.001;
                    break;
                case ID_CELLS_1_4:
                    cellsInto(d, 0);
                    break;
                case ID_CELLS_5_8:
                    cellsInto(d, 4);
                    break;
                case ID_CELLS_9_12:
                    cellsInto(d, 8);
                    break;
                case ID_CELLS_13_16:
                    cellsInto(d, 12);
                    break;
                case ID_MOTOR:
                    rpm = le16(d, 0);
                    mcuTemp = le16(d, 2) * 0.01;
                    motorTemp = le16(d, 4) * 0.01;
                    break;
                case ID_DISPLAY:
                    displaySoc = u8(d, 0);
                    break;
                case ID_ODOMETER:
                    odoMeters = le32(d, 0);
                    break;
                case ID_SAFETY:
                    fallDetection = u8(d, 0) & 0x0F;
                    towTheft = (u8(d, 0) >> 4) & 0x0F;
                    regen = u8(d, 1) & 0x0F;
                    hillHold = (u8(d, 1) >> 4) & 0x0F;
                    tamperWake = (u8(d, 2) & 0x02) != 0;
                    break;
                default:
                    break;
            }
        }

        private void cellsInto(byte[] d, int base) {
            for (int i = 0; i < 4; i++) {
                cells[base + i] = le16(d, i * 2);
            }
        }

        /** Watts, positive when the pack is being drained. */
        public double powerW() {
            if (Double.isNaN(volts) || Double.isNaN(amps)) {
                return Double.NaN;
            }
            return volts * amps;
        }

        /** max − min cell voltage in mV, the number that finds a weak cell. */
        public double cellSpreadMv() {
            double min = Double.NaN;
            double max = Double.NaN;
            for (double mv : cells) {
                if (Double.isNaN(mv) || mv <= 0) {
                    continue;
                }
                if (Double.isNaN(min) || mv < min) {
                    min = mv;
                }
                if (Double.isNaN(max) || mv > max) {
                    max = mv;
                }
            }
            if (!Double.isNaN(min) && !Double.isNaN(max)) {
                return max - min;
            }
            if (!Double.isNaN(cellMinMv) && !Double.isNaN(cellMaxMv)) {
                return cellMaxMv - cellMinMv;
            }
            return Double.NaN;
        }

        /** Cells that actually reported a value. The pack is 14S. */
        public int cellCount() {
            int n = 0;
            for (double mv : cells) {
                if (!Double.isNaN(mv) && mv > 0) {
                    n++;
                }
            }
            return n;
        }

        public String cellSpreadVerdict() {
            double spread = cellSpreadMv();
            if (Double.isNaN(spread)) {
                return "no cell data";
            }
            if (spread <= 30) {
                return "healthy";
            }
            if (spread <= 50) {
                return "watch";
            }
            return "weak cell";
        }

        public String sohVerdict() {
            if (Double.isNaN(soh)) {
                return "no SOH data";
            }
            if (soh >= 90) {
                return "healthy";
            }
            if (soh >= 80) {
                return "watch";
            }
            return "degraded";
        }

        public String fallName() {
            switch (fallDetection) {
                case 1: return "active";
                case 2: return "inactive";
                case 3: return "recovered";
                case 0: return "off (SNA)";
                default: return "unknown";
            }
        }

        public String towName() {
            switch (towTheft) {
                case 1: return "active";
                case 2: return "inactive";
                case 0: return "off (SNA)";
                default: return "unknown";
            }
        }

        public String regenName() {
            switch (regen) {
                case 1: return "available";
                case 2: return "throttle limited";
                case 0: return "not available";
                default: return "unknown";
            }
        }

        public String hillHoldName() {
            switch (hillHold) {
                case 1: return "unavailable";
                case 2: return "active";
                case 3: return "not active";
                case 6: return "activated";
                case 0: return "unknown (SNA)";
                default: return "state " + hillHold;
            }
        }
    }
}
