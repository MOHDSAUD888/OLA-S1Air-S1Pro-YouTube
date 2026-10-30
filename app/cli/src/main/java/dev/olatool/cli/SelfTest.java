package dev.olatool.cli;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Map;

import dev.olatool.core.Can;
import dev.olatool.core.Codec;
import dev.olatool.core.Dbc;
import dev.olatool.core.Faults;
import dev.olatool.core.Json;
import dev.olatool.core.Live;
import dev.olatool.core.Msg;
import dev.olatool.core.Provision;
import dev.olatool.core.VehicleInfo;

/**
 * Offline checks for the parts that do not need a scooter.
 *
 * <p>Run with {@code cli selftest}. It reads the two JSON assets, decodes
 * synthetic frames, and exercises the ADB key format. Any failure prints the
 * assertion and exits non-zero.
 */
public final class SelfTest {

    private static int passed;
    private static int failed;

    private SelfTest() {
    }

    public static void main(String[] args) throws Exception {
        json();
        codec();
        canFrames();
        dbc();
        faults();
        live();
        provision();
        vehicleInfo();

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ json

    private static void json() {
        Map<String, Object> o = Json.object(
                "{\"a\":1,\"b\":[true,false,null],\"c\":{\"d\":\"x\\ny\"}}");
        eq("json number", 1.0, Json.number(o, "a", -1));
        eq("json array size", 3, Json.array(o, "b").size());
        eq("json bool", true, Json.array(o, "b").get(0));
        eq("json nested string", "x\ny", Json.string(Json.map(o, "c"), "d", ""));
        eq("json missing fallback", 7.0, Json.number(o, "zz", 7));
    }

    private static void codec() {
        eq("hex roundtrip", "00ff10", Codec.hex(Codec.unhex("00ff10")));
        eq("base64 hello", "aGVsbG8=", Codec.base64("hello".getBytes(StandardCharsets.UTF_8)));
        eq("base64 roundtrip", "hello",
                new String(Codec.unbase64("aGVsbG8="), StandardCharsets.UTF_8));
        eq("base64 no padding", "hello",
                new String(Codec.unbase64("aGVsbG8"), StandardCharsets.UTF_8));
    }

    private static void canFrames() {
        byte[] raw = Can.frame(0x352, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        eq("frame length", 16, raw.length);
        eq("frame id byte 0", (byte) 0x00, raw[0]);
        eq("frame id byte 1", (byte) 0x00, raw[1]);
        eq("frame id byte 2", (byte) 0x03, raw[2]);
        eq("frame id byte 3", (byte) 0x52, raw[3]);
        eq("frame dlc", (byte) 8, raw[4]);
        Can.Frame parsed = Can.Frame.parse(raw);
        eq("parsed id", 0x352, parsed.id);
        eq("parsed dlc", 8, parsed.dlc);
        eq("parsed byte 7", (byte) 8, parsed.data[7]);
    }

    // ------------------------------------------------------------------- dbc

    private static void dbc() throws Exception {
        Dbc dbc = Dbc.parse(read(asset("can_database.json")));
        eq("dbc messages", 225, dbc.size());
        eq("dbc signals", 1251, dbc.signalCount());

        // 0x352 Display_Info_4: Display_SoC at bit 0 (16 bits), SOH_Disp at bit 16.
        byte[] display = new byte[8];
        display[0] = 87;
        display[1] = 0;
        display[2] = 100;
        Map<String, Double> d = dbc.decode(0x352, display);
        eq("soc", 87.0, d.get("Display_SoC"));
        eq("soh", 100.0, d.get("SOH_Disp"));

        // 0x682 Vehicle_Dynamics: Vehicle_Acceleration at bit 16, 8 bits, signed, 0.1.
        byte[] dynamics = new byte[8];
        dynamics[2] = (byte) 0xFB; // -5
        Map<String, Double> acc = dbc.decode(0x682, dynamics);
        eq("signed accel", -0.5, acc.get("Vehicle_Acceleration"));

        // 0x113 MCU_Data_2: Vehicle_Discharge_Mode at bit 41, 4 bits.
        byte[] mcu = new byte[8];
        mcu[5] = 3 << 1;
        Map<String, Double> mode = dbc.decode(0x113, mcu);
        eq("cross-byte mode", 3.0, mode.get("Vehicle_Discharge_Mode"));

        // Intel 16-bit little-endian and Motorola 8-bit, on a hand-written table.
        Dbc tiny = Dbc.parse("{\"messages\":[{\"id\":1,\"name\":\"T\",\"dlc\":8,"
                + "\"signals\":["
                + "{\"name\":\"le\",\"startbit\":0,\"bitlength\":16,\"endianess\":\"intel\","
                + "\"scaling\":1,\"offset\":0,\"signed\":false,\"units\":\"\"},"
                + "{\"name\":\"be\",\"startbit\":7,\"bitlength\":8,\"endianess\":\"motorola\","
                + "\"scaling\":2,\"offset\":1,\"signed\":false,\"units\":\"\"}]}]}");
        Map<String, Double> t = tiny.decode(1, new byte[]{0x34, 0x12, 0x2A, 0, 0, 0, 0, 0});
        eq("intel le16", 4660.0, t.get("le"));
        eq("motorola scaled", 105.0, t.get("be"));
        eq("unknown id", 0, tiny.decode(999, new byte[8]).size());
    }

    private static void faults() throws Exception {
        Faults table = Faults.parse(read(asset("faults.json")));
        eq("fault count", 194, table.size());
        eq("fixable count", 2, table.fixable().size());
        eq("camel", "bmsPrechargeFail", Faults.camelCase("BMS_Precharge_Fail"));
        eq("camel single", "airplane", Faults.camelCase("airplane"));
        eq("camel empty part", "abcDef", Faults.camelCase("ABC__DEF"));
        eq("lookup by camel", "Precharge Circuit Failure",
                table.forSignal("BMS_Precharge_Fail").title);
        eq("lookup by id", "Precharge Circuit Failure",
                table.forSignal("bmsPrechargeFail").title);
        eq("no match", null, table.forSignal("Display_SoC"));
    }

    // ------------------------------------------------------------------ live

    private static void live() throws Exception {
        Dbc dbc = Dbc.parse(read(asset("can_database.json")));
        Faults faults = Faults.parse(read(asset("faults.json")));
        Live live = new Live(dbc, faults);

        eq("empty at start", true, live.snapshot().empty());

        byte[] display = new byte[8];
        display[0] = 87;
        display[2] = 100;
        live.onFrame(new Can.Frame(0x352, 8, display));

        Live.Snapshot s = live.snapshot();
        eq("live soc", 87.0, s.soc);
        eq("live soh", 100.0, s.soh);
        eq("live frames", 1L, s.frames);
        eq("live messages", 1, s.messagesSeen);

        // 0x66E VCU_Faults bit 0 is BMS_Board_Variant_SW_Mismatch.
        byte[] vcu = new byte[8];
        vcu[0] = 1;
        live.onFrame(new Can.Frame(0x66E, 8, vcu));
        eq("one active fault", 1, live.activeFaults().size());
        eq("active fault id", "bmsBoardVariantSwMismatch",
                live.activeFaults().get(0).id);
        eq("latched fault", 1, live.latchedFaults().size());

        List<Live.Entry> log = live.log();
        eq("log newest first", "VCU_Faults", log.get(0).name);
        eq("log second", "Display_Info_4", log.get(1).name);
        eq("log hex", "01 00 00 00 00 00 00 00", log.get(0).hex);

        live.clearLatched();
        eq("latched cleared", 0, live.latchedFaults().size());
        eq("active survives clear", 1, live.activeFaults().size());

        // 0x683 Vehicle_Safety_State: fall/tow in byte 0, regen/hill in byte 1,
        // four bits each.
        byte[] safety = new byte[8];
        safety[0] = (byte) (1 | (2 << 4)); // fall = 1, tow = 2
        safety[1] = (byte) (1 | (2 << 4)); // regen = 1, hill = 2
        live.onFrame(new Can.Frame(0x683, 8, safety));
        Live.Snapshot safe = live.snapshot();
        eq("fall active", "active", safe.fallName());
        eq("tow inactive", "inactive", safe.towName());
        eq("regen available", "available", safe.regenName());
        eq("hill active", "active", safe.hillName());

        live.reset();
        eq("reset frames", 0L, live.snapshot().frames);
        eq("reset values", 0, live.values().size());
        eq("reset log", 0, live.log().size());
    }

    // ------------------------------------------------------------- provision

    private static void provision() throws Exception {
        KeyPair pair = Provision.newKeyPair();
        RSAPublicKey pub = (RSAPublicKey) pair.getPublic();
        String line = Provision.publicKeyLine(pub, "olatool");
        eq("key line length", 708, line.length());
        eq("key line comment", true, line.endsWith(" olatool"));
        eq("key struct size", 524, Codec.unbase64(line.substring(0, 700)).length);

        String priv = Provision.privateKeyPem(pair.getPrivate());
        String pubPem = Provision.publicKeyPem(pair.getPublic());
        eq("private pem header", true, priv.startsWith("-----BEGIN PRIVATE KEY-----"));
        eq("private pem roundtrip", pair.getPrivate(),
                Provision.parsePrivateKey(priv));
        eq("public pem roundtrip", pub.getModulus(),
                ((RSAPublicKey) Provision.parsePublicKey(pubPem)).getModulus());
        eq("exponent", BigInteger.valueOf(65537), pub.getPublicExponent());

        String script = Provision.provisionScript(line, "serv");
        eq("script has key file", true, script.contains(Provision.KEY_FILE));
        eq("script mounts", true, script.contains("mount --bind"));
        eq("script sets mfg", true, script.contains("scootermode mfg"));
        eq("script records old mode", true, script.contains("OLD='serv'"));

        String undo = Provision.undoScript("serv");
        eq("undo unmounts", true, undo.contains("umount /adb_keys"));
        eq("undo restores", true, undo.contains("scootermode 'serv'"));
        eq("undo default", true, Provision.undoScript(null).contains("scootermode 'cust'"));
    }

    // ---------------------------------------------------------- vehicle info

    private static void vehicleInfo() {
        String vin = "TESTVIN0000000000";
        eq("vin from json", vin,
                VehicleInfo.vinFromManifest("{\"VIN\":\"" + vin + "\"}"));
        eq("vin from regex", vin,
                VehicleInfo.vinFromManifest("not json \"VIN\":\"" + vin + "\""));
        eq("reg indian", "KA 01 AB 1234", VehicleInfo.formatRegistration("ka01ab1234"));
        eq("reg bharat", "22 BH 1234 A", VehicleInfo.formatRegistration("22BH1234A"));
        eq("reg blank", "", VehicleInfo.formatRegistration(""));
        eq("uptime days", "76 hrs",
                VehicleInfo.uptimeHours(" 10:20:30 up 3 days,  4:05,  1 user"));
        eq("uptime hours", "4 hrs",
                VehicleInfo.uptimeHours(" 10:20:30 up 4:05,  1 user"));
        eq("uptime minutes", "0 hrs",
                VehicleInfo.uptimeHours(" 10:20:30 up 12 min,  1 user"));

        VehicleInfo variant = VehicleInfo.fromVariantXml(
                "<string name=\"com.olaelectric.dataanalyser.VEHICLE_VARIANT\">"
                        + "S1_Pro_Gen_2_3.97_kwh</string>");
        eq("variant model", "S1 Pro", variant.model);
        eq("variant generation", "Gen 2", variant.generation);
        eq("variant battery", "3.97 kWh", variant.batteryVariant);

        eq("sw line prefix", "MoveOS 1.2.3",
                VehicleInfo.swLine(new Msg.Response(1, "1.2.3\n")));
        eq("sw line kept", "MoveOS 2.0",
                VehicleInfo.swLine(new Msg.Response(1, "MoveOS 2.0\n")));
    }

    // ---------------------------------------------------------------- helpers

    private static void eq(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            passed++;
        } else {
            failed++;
            System.out.println("FAIL  " + name + ": expected <" + expected + "> got <" + actual + ">");
        }
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static Path asset(String name) throws Exception {
        Path[] candidates = {
                Paths.get("app", "src", "main", "assets", name),
                Paths.get("..", "app", "src", "main", "assets", name),
                Paths.get("ola-s1-rea", "app", "app", "src", "main", "assets", name),
        };
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        throw new IllegalStateException(name + " not found");
    }
}
