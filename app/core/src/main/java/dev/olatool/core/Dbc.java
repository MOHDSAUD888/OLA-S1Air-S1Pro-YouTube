package dev.olatool.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The CAN signal table: 226 messages, 1260 signals, decoded from a JSON file
 * shaped like the one Axiom ships.
 *
 * <p>Every signal in that file is Intel (little-endian), so the Motorola branch
 * is kept only so an edited table does not silently decode as garbage.
 *
 * <p>Bit numbering is the DBC one: bit 0 is the least significant bit of byte 0,
 * and an Intel signal runs upward from {@code startbit}. Values are
 * {@code raw * scaling + offset}.
 */
public final class Dbc {

    /** One signal inside a message. */
    public static final class Signal {
        public final String name;
        public final int startBit;
        public final int bitLength;
        public final boolean intel;
        public final boolean signed;
        public final double scaling;
        public final double offset;
        public final String units;

        Signal(String name, int startBit, int bitLength, boolean intel,
               boolean signed, double scaling, double offset, String units) {
            this.name = name;
            this.startBit = startBit;
            this.bitLength = bitLength;
            this.intel = intel;
            this.signed = signed;
            this.scaling = scaling;
            this.offset = offset;
            this.units = units == null ? "" : units;
        }

        /** The physical value of this signal inside an 8-byte payload. */
        public double value(byte[] payload) {
            long raw = raw(payload);
            return raw * scaling + offset;
        }

        /** The un-scaled integer, which is what the raw view shows. */
        public long raw(byte[] payload) {
            if (payload == null || payload.length == 0
                    || bitLength <= 0 || bitLength > 64) {
                return 0;
            }
            long value = 0;
            if (intel) {
                for (int i = 0; i < bitLength; i++) {
                    int bit = startBit + i;
                    int index = bit >> 3;
                    if (index >= payload.length) {
                        break;
                    }
                    long one = (payload[index] >> (bit & 7)) & 1L;
                    value |= one << i;
                }
            } else {
                // DBC sawtooth numbering: the start bit is the MSB, bit 7 of a
                // byte is 7, bit 0 is 0, and after bit 0 comes bit 15 (the MSB
                // of the next byte). Axiom's reader shifts the other way; the
                // shipped table has no Motorola rows, so this follows the DBC
                // spec rather than that code.
                int pos = startBit;
                for (int i = 0; i < bitLength; i++) {
                    int index = pos >> 3;
                    int bit = pos & 7;
                    if (index < payload.length) {
                        long one = (payload[index] >> bit) & 1L;
                        value = (value << 1) | one;
                    }
                    pos = bit == 0 ? pos + 15 : pos - 1;
                }
            }
            if (signed && bitLength < 64) {
                long sign = 1L << (bitLength - 1);
                if ((value & sign) != 0) {
                    long mask = (1L << bitLength) - 1;
                    value |= ~mask;
                }
            }
            return value;
        }

        @Override
        public String toString() {
            return name + (units.isEmpty() ? "" : " [" + units + "]");
        }
    }

    /** One CAN message and the signals it carries. */
    public static final class Message {
        public final int id;
        public final String name;
        public final int dlc;
        public final Signal[] signals;
        private final Map<String, Signal> byName = new LinkedHashMap<>();

        Message(int id, String name, int dlc, List<Signal> signals) {
            this.id = id;
            this.name = name;
            this.dlc = dlc;
            this.signals = signals.toArray(new Signal[0]);
            for (Signal s : this.signals) {
                byName.put(s.name, s);
            }
        }

        public Signal signal(String name) {
            return byName.get(name);
        }

        public String hexId() {
            return String.format("0x%03X", id);
        }
    }

    /** A decoded message: the raw bytes plus every physical value. */
    public static final class Frame {
        public final int id;
        public final String name;
        public final byte[] payload;
        public final Map<String, Double> signals;
        public final boolean known;

        Frame(int id, String name, byte[] payload,
              Map<String, Double> signals, boolean known) {
            this.id = id;
            this.name = name;
            this.payload = payload;
            this.signals = signals;
            this.known = known;
        }

        public String hexId() {
            return String.format("0x%03X", id);
        }
    }

    private final Map<Integer, Message> messages = new LinkedHashMap<>();

    private Dbc() {
    }

    /** Parses the {@code {"messages": [...]}} document. */
    public static Dbc parse(String json) {
        Dbc dbc = new Dbc();
        Map<String, Object> root = Json.object(json);
        for (Object o : Json.array(root, "messages")) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<String, Object> m = Json.cast(o);
            int id = (int) Json.number(m, "id", -1);
            if (id < 0) {
                continue;
            }
            String name = Json.string(m, "name", "Message_" + id);
            int dlc = (int) Json.number(m, "dlc", 8);
            List<Signal> signals = new ArrayList<>();
            for (Object so : Json.array(m, "signals")) {
                if (!(so instanceof Map)) {
                    continue;
                }
                Map<String, Object> s = Json.cast(so);
                String sigName = Json.string(s, "name", "");
                if (sigName.isEmpty()) {
                    continue;
                }
                String endian = Json.string(s, "endianess", "intel");
                signals.add(new Signal(
                        sigName,
                        (int) Json.number(s, "startbit", 0),
                        (int) Json.number(s, "bitlength", 0),
                        !"motorola".equalsIgnoreCase(endian),
                        Json.flag(s, "signed"),
                        Json.number(s, "scaling", 1.0),
                        Json.number(s, "offset", 0.0),
                        Json.string(s, "units", "")));
            }
            dbc.messages.put(id, new Message(id, name, dlc, signals));
        }
        return dbc;
    }

    public int size() {
        return messages.size();
    }

    public int signalCount() {
        int n = 0;
        for (Message m : messages.values()) {
            n += m.signals.length;
        }
        return n;
    }

    public Collection<Message> messages() {
        return Collections.unmodifiableCollection(messages.values());
    }

    public Message message(int id) {
        return messages.get(id);
    }

    /** Every signal value in the payload, keyed by signal name. */
    public Map<String, Double> decode(int canId, byte[] payload) {
        Message m = messages.get(canId);
        if (m == null) {
            return Collections.emptyMap();
        }
        Map<String, Double> out = new LinkedHashMap<>(m.signals.length * 2);
        for (Signal s : m.signals) {
            out.put(s.name, s.value(payload));
        }
        return out;
    }

    /** Decodes a whole frame; unknown ids come back with an empty signal map. */
    public Frame decodeFrame(Can.Frame f) {
        Message m = messages.get(f.id);
        Map<String, Double> values = m == null
                ? Collections.<String, Double>emptyMap()
                : decode(f.id, f.data);
        String name = m == null ? "Unknown" : m.name;
        return new Frame(f.id, name, f.data, values, m != null);
    }
}
