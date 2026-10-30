package dev.olatool.core;

/**
 * The handful of wifican messages this app actually uses, encoded and decoded
 * by hand from proto/wifican.proto.
 */
public final class Msg {

    private Msg() {
    }

    // -------------------------------------------------------------- requests

    /** {@code Command{string cmd_msg = 1}} */
    public static byte[] command(String text) {
        return new Proto.Writer().string(1, text).toByteArray();
    }

    /** {@code Register{repeated uint32 can_ids = 1}} */
    public static byte[] register(int[] canIds) {
        return new Proto.Writer().packedUints(1, canIds).toByteArray();
    }

    /** {@code CANPacket{bytes can_frame = 1}} */
    public static byte[] canPacket(byte[] frame) {
        return new Proto.Writer().bytes(1, frame).toByteArray();
    }

    /** Reads {@code can_frame} back out of a {@code CANPacket}. */
    public static byte[] canFrame(byte[] raw) {
        Proto.Reader r = new Proto.Reader(raw);
        while (r.hasMore()) {
            int field = r.tag();
            if (field == 1 && r.wire() == Proto.WIRE_LENGTH) {
                return r.bytes();
            }
            r.skip(r.wire());
        }
        return null;
    }

    /** {@code ConfigType{string type = 1}} */
    public static byte[] configType(String type) {
        return new Proto.Writer().string(1, type).toByteArray();
    }

    /**
     * {@code VehicleIdentifiers{string manifest_json = 1}}.
     *
     * <p>The daemon answers with one JSON blob. It is returned as text rather
     * than parsed here, because the app only shows it.
     */
    public static String vehicleIdentifiers(byte[] raw) {
        Proto.Reader r = new Proto.Reader(raw);
        while (r.hasMore()) {
            int field = r.tag();
            if (field == 1 && r.wire() == Proto.WIRE_LENGTH) {
                return r.string();
            }
            r.skip(r.wire());
        }
        return "";
    }

    // ------------------------------------------------------------- responses

    /** {@code Response{RespStatus status = 1; string status_msg = 2}} */
    public static final class Response {
        public static final int NONE = 0;
        public static final int SUCCESS = 1;
        public static final int FAIL = 2;

        public final int status;
        public final String message;

        public Response(int status, String message) {
            this.status = status;
            this.message = message == null ? "" : message;
        }

        /** The daemon reports NONE for a command that produced no output. */
        public boolean ok() {
            return status == NONE || status == SUCCESS;
        }

        public String statusName() {
            switch (status) {
                case SUCCESS:
                    return "success";
                case FAIL:
                    return "failed";
                default:
                    return "none";
            }
        }

        @Override
        public String toString() {
            return statusName() + (message.isEmpty() ? "" : ": " + message);
        }
    }

    public static Response response(byte[] raw) {
        int status = 0;
        String message = "";
        Proto.Reader r = new Proto.Reader(raw);
        while (r.hasMore()) {
            int field = r.tag();
            switch (field) {
                case 1:
                    status = (int) r.varint();
                    break;
                case 2:
                    message = r.string();
                    break;
                default:
                    r.skip(r.wire());
            }
        }
        return new Response(status, message);
    }

    /** {@code ConfigUpdateResp{ConfigUpdateStatus status = 1; string status_msg = 2}} */
    public static final class ConfigUpdate {
        public static final int IN_PROGRESS = 0;
        public static final int SUCCESS = 1;
        public static final int FAILURE = 2;

        public final int status;
        public final String message;

        public ConfigUpdate(int status, String message) {
            this.status = status;
            this.message = message == null ? "" : message;
        }

        public boolean ok() {
            return status == SUCCESS;
        }

        public String statusName() {
            switch (status) {
                case SUCCESS:
                    return "success";
                case FAILURE:
                    return "failure";
                default:
                    return "in progress";
            }
        }

        @Override
        public String toString() {
            return statusName() + (message.isEmpty() ? "" : ": " + message);
        }
    }

    public static ConfigUpdate configUpdate(byte[] raw) {
        int status = 0;
        String message = "";
        Proto.Reader r = new Proto.Reader(raw);
        while (r.hasMore()) {
            int field = r.tag();
            switch (field) {
                case 1:
                    status = (int) r.varint();
                    break;
                case 2:
                    message = r.string();
                    break;
                default:
                    r.skip(r.wire());
            }
        }
        return new ConfigUpdate(status, message);
    }

    /**
     * {@code VehicleOperationMode{cur_mode = 1; modified_ts = 2;
     * modified_ts_str = 3; modified_by = 4}}
     */
    public static final class OpMode {
        public static final int NONE = 0;
        public static final int MFG = 1;
        public static final int CUSTOMER = 2;
        public static final int SERVICE = 3;
        public static final int DECOMMISSIONED = 4;

        public final int mode;
        public final long modifiedTs;
        public final String modifiedTsStr;
        public final String modifiedBy;

        public OpMode(int mode, long modifiedTs, String modifiedTsStr, String modifiedBy) {
            this.mode = mode;
            this.modifiedTs = modifiedTs;
            this.modifiedTsStr = modifiedTsStr == null ? "" : modifiedTsStr;
            this.modifiedBy = modifiedBy == null ? "" : modifiedBy;
        }

        public String modeName() {
            switch (mode) {
                case MFG:
                    return "manufacturing";
                case CUSTOMER:
                    return "customer";
                case SERVICE:
                    return "service";
                case DECOMMISSIONED:
                    return "decommissioned";
                default:
                    return "none";
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(modeName());
            if (!modifiedBy.isEmpty()) {
                sb.append(" (set by ").append(modifiedBy).append(')');
            }
            if (!modifiedTsStr.isEmpty()) {
                sb.append(" ").append(modifiedTsStr);
            }
            return sb.toString();
        }
    }

    public static OpMode opMode(byte[] raw) {
        int mode = 0;
        long ts = 0;
        String tsStr = "";
        String by = "";
        Proto.Reader r = new Proto.Reader(raw);
        while (r.hasMore()) {
            int field = r.tag();
            switch (field) {
                case 1:
                    mode = (int) r.varint();
                    break;
                case 2:
                    ts = r.varint();
                    break;
                case 3:
                    tsStr = r.string();
                    break;
                case 4:
                    by = r.string();
                    break;
                default:
                    r.skip(r.wire());
            }
        }
        return new OpMode(mode, ts, tsStr, by);
    }
}
