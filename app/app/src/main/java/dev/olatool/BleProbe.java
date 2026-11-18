package dev.olatool;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.util.Collections;

/**
 * One-shot, read-only BLE presence probe for the scooter.
 *
 * <p>Listens for the S1 advertised service UUID ({@link BtTab#S1_ADV_UUID}) and
 * reports whether it was seen and how strong the signal was. No GATT connect,
 * no characteristic write, no Seed-Auth — purely passive advertisement scanning.
 * It is a reachability hint shown when the Wi-Fi / gRPC link cannot be made: it
 * can locate the scooter but can never control it (control is Wi-Fi only; see
 * RESEARCH.md §8/§10).
 */
final class BleProbe {

    /** Where the probe lands. Fires exactly once, on the main thread. */
    interface Result {
        /**
         * @param found  the scooter advertisement was seen
         * @param rssi   best RSSI seen in dBm, or 0 when not found
         * @param reason why the probe could not run, or null on a clean result
         */
        void done(boolean found, int rssi, String reason);
    }

    private static final long SCAN_MS = 5_000L;

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private BluetoothLeScanner scanner;
    private ScanCallback cb;
    private volatile boolean finished;
    private volatile boolean seen;
    private volatile int bestRssi = Integer.MIN_VALUE;

    BleProbe(Context ctx) {
        this.ctx = ctx;
    }

    /** Runs the probe; {@code result} is delivered once after at most ~5 s. */
    void start(Result result) {
        BluetoothManager bm =
                (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter =
                bm != null ? bm.getAdapter() : BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            finish(result, false, 0, "this phone has no Bluetooth");
            return;
        }
        if (!adapter.isEnabled()) {
            finish(result, false, 0, "Bluetooth is off");
            return;
        }
        if (!hasPermissions()) {
            finish(result, false, 0,
                    "Bluetooth scan permission needed (open the Bluetooth tab once)");
            return;
        }
        try {
            scanner = adapter.getBluetoothLeScanner();
        } catch (Exception e) {
            scanner = null;
        }
        if (scanner == null) {
            finish(result, false, 0, "BLE scanner unavailable");
            return;
        }

        cb = new ScanCallback() {
            @Override
            public void onScanResult(int type, ScanResult r) {
                record(r);
            }

            @Override
            public void onScanFailed(int code) {
                finish(result, false, 0, "scan failed (code " + code + ")");
            }
        };
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(new ParcelUuid(BtTab.S1_ADV_UUID))
                .build();
        try {
            scanner.startScan(Collections.singletonList(filter), settings, cb);
        } catch (SecurityException e) {
            finish(result, false, 0, "Bluetooth permission denied");
            return;
        } catch (Exception e) {
            finish(result, false, 0, "could not start scan: " + e.getMessage());
            return;
        }
        ui.postDelayed(() -> finish(result, seen, seen ? bestRssi : 0, null), SCAN_MS);
    }

    private void record(ScanResult r) {
        seen = true;
        int rssi = r.getRssi();
        if (rssi > bestRssi) {
            bestRssi = rssi;
        }
    }

    private synchronized void finish(Result result, boolean found, int rssi, String reason) {
        if (finished) {
            return;
        }
        finished = true;
        stop();
        ui.post(() -> result.done(found, rssi, reason));
    }

    private void stop() {
        try {
            if (scanner != null && cb != null) {
                scanner.stopScan(cb);
            }
        } catch (Exception ignored) {
            // stopping a scan that never started, or without permission, is fine.
        }
    }

    private boolean hasPermissions() {
        String[] perms = Build.VERSION.SDK_INT >= 31
                ? new String[]{Manifest.permission.BLUETOOTH_SCAN}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        for (String p : perms) {
            if (ctx.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /** Human proximity bucket from an RSSI in dBm. */
    static String proximity(int rssi) {
        if (rssi >= -60) {
            return "very close";
        }
        if (rssi >= -80) {
            return "nearby";
        }
        return "far / weak signal";
    }
}
