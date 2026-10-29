package dev.olatool.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Just enough protobuf to talk to the wifican daemon, by hand.
 *
 * <p>No protoc, no generated classes. The daemon is weakly typed and every
 * message this app needs is one or two fields, so a writer and a reader are
 * all it takes. Field numbers come from proto/wifican.proto in this repo,
 * which was recovered from the device's own FileDescriptorProto.
 */
public final class Proto {

    public static final int WIRE_VARINT = 0;
    public static final int WIRE_64BIT = 1;
    public static final int WIRE_LENGTH = 2;
    public static final int WIRE_32BIT = 5;

    private Proto() {
    }

    // ---------------------------------------------------------------- varint

    public static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(10);
        long v = value;
        while (true) {
            int b = (int) (v & 0x7F);
            v >>>= 7;
            if (v != 0) {
                out.write(b | 0x80);
            } else {
                out.write(b);
                return out.toByteArray();
            }
        }
    }

    // ---------------------------------------------------------------- writer

    /** Builds one message. proto3 rules: a zero value is simply not written. */
    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(64);

        private Writer tag(int field, int wire) {
            out.writeBytes(varint(((long) field << 3) | wire));
            return this;
        }

        public Writer uint(int field, long value) {
            if (value == 0) {
                return this;
            }
            tag(field, WIRE_VARINT);
            out.writeBytes(varint(value));
            return this;
        }

        public Writer bytes(int field, byte[] value) {
            if (value == null || value.length == 0) {
                return this;
            }
            tag(field, WIRE_LENGTH);
            out.writeBytes(varint(value.length));
            out.writeBytes(value);
            return this;
        }

        public Writer string(int field, String value) {
            if (value == null || value.isEmpty()) {
                return this;
            }
            return bytes(field, value.getBytes(StandardCharsets.UTF_8));
        }

        /** A repeated numeric field, sent packed (the proto3 default). */
        public Writer packedUints(int field, int[] values) {
            if (values == null || values.length == 0) {
                return this;
            }
            ByteArrayOutputStream packed = new ByteArrayOutputStream(values.length * 2);
            for (int v : values) {
                packed.writeBytes(varint(v));
            }
            return bytes(field, packed.toByteArray());
        }

        public byte[] toByteArray() {
            return out.toByteArray();
        }
    }

    // ---------------------------------------------------------------- reader

    /**
     * Reads one message. Callers loop with {@link #hasMore()}, then read the
     * tag and decide. Unknown fields are skipped with {@link #skip(int)} so a
     * server that adds a field later cannot break the app.
     */
    public static final class Reader {
        private final byte[] buf;
        private int pos;
        private int wire;

        public Reader(byte[] buf) {
            this.buf = buf == null ? new byte[0] : buf;
        }

        public boolean hasMore() {
            return pos < buf.length;
        }

        /** Reads the tag and remembers the wire type for {@link #wire()}. */
        public int tag() {
            long key = varint();
            wire = (int) (key & 7);
            return (int) (key >>> 3);
        }

        public int wire() {
            return wire;
        }

        public long varint() {
            long value = 0;
            int shift = 0;
            while (true) {
                if (pos >= buf.length) {
                    throw new IllegalArgumentException("truncated varint");
                }
                int b = buf[pos++] & 0xFF;
                value |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
                shift += 7;
                if (shift > 63) {
                    throw new IllegalArgumentException("varint too long");
                }
            }
        }

        public byte[] bytes() {
            int len = (int) varint();
            if (len < 0 || pos + len > buf.length) {
                throw new IllegalArgumentException("truncated field");
            }
            byte[] out = new byte[len];
            System.arraycopy(buf, pos, out, 0, len);
            pos += len;
            return out;
        }

        public String string() {
            return new String(bytes(), StandardCharsets.UTF_8);
        }

        public void skip(int wireType) {
            switch (wireType) {
                case WIRE_VARINT:
                    varint();
                    break;
                case WIRE_64BIT:
                    pos += 8;
                    break;
                case WIRE_LENGTH:
                    pos += (int) varint();
                    break;
                case WIRE_32BIT:
                    pos += 4;
                    break;
                default:
                    throw new IllegalArgumentException("bad wire type " + wireType);
            }
            if (pos > buf.length) {
                throw new IllegalArgumentException("truncated message");
            }
        }
    }
}
