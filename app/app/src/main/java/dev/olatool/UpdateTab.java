package dev.olatool;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.HttpsURLConnection;

/**
 * Self-update: keep Pegasus itself current with the CI build on GitHub.
 *
 * <p>While this screen is open it asks the {@code latest-debug} release every
 * {@value #POLL_MS} ms whether the APK changed. The request is conditional
 * ({@code If-None-Match}), so an unchanged release comes back {@code 304},
 * which GitHub does <em>not</em> count against the unauthenticated rate limit —
 * that is what makes a ten-second poll affordable. Only a genuinely new build
 * returns {@code 200}.
 *
 * <p>"New" means the release asset is newer than this app's own install time
 * ({@code lastUpdateTime}), so it needs no stored state and correctly spots a
 * phone that is already behind. When a new build is found and auto-install is
 * on, the APK streams straight into a {@link PackageInstaller} session and the
 * system install prompt opens. The phone is not rooted, so that one confirm tap
 * cannot be skipped — there is no silent install without root or device-owner.
 */
public final class UpdateTab implements Tab {

    private static final long POLL_MS = 10_000L;
    private static final String ASSET = "Pegasus-debug.apk";
    private static final String API =
            "https://api.github.com/repos/MOHDSAUD888/OLA-S1Air-S1Pro-YouTube"
                    + "/releases/tags/latest-debug";
    private static final String INSTALL_ACTION = "dev.olatool.UPDATE_INSTALL";

    private final MainActivity activity;
    private final ScrollView page;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ola-update");
        t.setDaemon(true);
        return t;
    });

    private final TextView versionLabel;
    private final TextView status;
    private final CheckBox autoBox;

    private volatile boolean polling;
    private volatile boolean checking;
    private volatile String etag;

    // Latest release asset the last check saw, for the manual Install button.
    private volatile String latestUrl;
    private volatile long latestSize;
    private volatile String latestMarker;
    // The asset we have already started installing, so a repeated 10 s check
    // does not re-launch the install prompt over and over.
    private volatile String installingMarker;

    private BroadcastReceiver installReceiver;

    public UpdateTab(MainActivity activity) {
        this.activity = activity;
        this.page = Ui.page(activity);
        LinearLayout body = Ui.body(page);

        body.addView(Ui.pageTitle(activity, "App update",
                "Keeps Pegasus current with the cloud build. While this screen is open "
                        + "it checks GitHub every 10 seconds; a new build installs with one "
                        + "confirm tap (the phone is not rooted, so a silent install is not "
                        + "possible)."));

        LinearLayout card = Ui.card(activity);
        card.addView(Ui.heading(activity, "Status"));
        versionLabel = Ui.text(activity, "", 12, Ui.DIM);
        card.addView(versionLabel);

        autoBox = new CheckBox(activity);
        autoBox.setText("Auto-install a new build when found");
        autoBox.setTextColor(Ui.TEXT);
        autoBox.setChecked(true);
        card.addView(autoBox, topMargin(8));

        LinearLayout row = Ui.row(activity);
        row.addView(Ui.button(activity, "Check now", v -> checkNow(false)), weight());
        row.addView(Ui.accent(activity, "Install latest", v -> installLatest()), weightGap());
        card.addView(row, topMargin(8));
        body.addView(card);

        status = Ui.codeBlock(Ui.text(activity, "Open checks run every 10 s.", 12, Ui.TEXT));
        status.setTextIsSelectable(true);
        body.addView(status, topMargin(10));
    }

    // --------------------------------------------------------------- checking

    private void checkNow(boolean auto) {
        if (checking) {
            return;
        }
        checking = true;
        io.execute(() -> {
            try {
                HttpsURLConnection c = (HttpsURLConnection) new URL(API).openConnection();
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "Pegasus-Updater");
                if (etag != null) {
                    c.setRequestProperty("If-None-Match", etag);
                }
                c.setConnectTimeout(8000);
                c.setReadTimeout(12000);
                int code = c.getResponseCode();
                if (code == 304) {
                    post("Up to date (checked " + now() + ").");
                    return;
                }
                if (code == 403) {
                    post("GitHub rate limit hit — will try again.");
                    return;
                }
                if (code != 200) {
                    post("Check failed: HTTP " + code);
                    return;
                }
                String tag = c.getHeaderField("ETag");
                if (tag != null) {
                    etag = tag;
                }
                JSONObject root = new JSONObject(readBody(c));
                JSONArray assets = root.optJSONArray("assets");
                String url = null;
                long size = 0;
                String updatedAt = null;
                if (assets != null) {
                    for (int k = 0; k < assets.length(); k++) {
                        JSONObject a = assets.getJSONObject(k);
                        if (ASSET.equals(a.optString("name"))) {
                            url = a.optString("browser_download_url", null);
                            size = a.optLong("size", 0);
                            updatedAt = a.optString("updated_at", null);
                            break;
                        }
                    }
                }
                if (url == null || updatedAt == null) {
                    post("No " + ASSET + " in the release yet.");
                    return;
                }
                long assetMs = Instant.parse(updatedAt).toEpochMilli();
                long installedMs = activity.getPackageManager()
                        .getPackageInfo(activity.getPackageName(), 0).lastUpdateTime;
                latestUrl = url;
                latestSize = size;
                latestMarker = updatedAt;
                final boolean available = assetMs > installedMs;
                final String built = updatedAt;
                final boolean autoNow = auto;
                activity.runOnUiThread(() -> {
                    if (available) {
                        log("Update available — built " + built + ".");
                        if (autoNow && autoBox.isChecked() && !built.equals(installingMarker)) {
                            installFrom(latestUrl, latestSize, latestMarker);
                        }
                    } else {
                        log("Up to date (latest build " + built + ").");
                    }
                });
            } catch (Exception e) {
                post("Check failed: " + msg(e));
            } finally {
                checking = false;
            }
        });
    }

    private void installLatest() {
        if (latestUrl == null || latestMarker == null) {
            log("Tap \"Check now\" first.");
            return;
        }
        installFrom(latestUrl, latestSize, latestMarker);
    }

    // --------------------------------------------------------------- install

    private void installFrom(String url, long size, String marker) {
        installingMarker = marker;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            askInstallPermission();
            return;
        }
        log("Downloading update…");
        io.execute(() -> {
            try {
                HttpsURLConnection dc = (HttpsURLConnection) new URL(url).openConnection();
                dc.setConnectTimeout(8000);
                dc.setReadTimeout(60000);
                int code = dc.getResponseCode();
                if (code != 200) {
                    post("Download failed: HTTP " + code);
                    return;
                }
                PackageInstaller pi = activity.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                if (size > 0) {
                    params.setSize(size);
                }
                int sid = pi.createSession(params);
                PackageInstaller.Session session = pi.openSession(sid);
                try (InputStream in = dc.getInputStream();
                     OutputStream out = session.openWrite("pegasus", 0, size > 0 ? size : -1)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        total += n;
                        if (size > 0) {
                            final int pct = (int) (total * 100 / size);
                            post("Downloading update… " + pct + "%");
                        }
                    }
                    session.fsync(out);
                }
                registerInstallReceiver();
                int flags = PendingIntent.FLAG_UPDATE_CURRENT
                        | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent pending = PendingIntent.getBroadcast(activity, sid,
                        new Intent(INSTALL_ACTION).setPackage(activity.getPackageName()), flags);
                session.commit(pending.getIntentSender());
                session.close();
                post("Downloaded — confirm the install prompt.");
            } catch (Exception e) {
                post("Install failed: " + msg(e));
            }
        });
    }

    private void askInstallPermission() {
        log("Allow Pegasus to install apps, then tap \"Install latest\".");
        try {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (Exception ignored) {
            // Some ROMs lack the per-app screen; the global one is the fallback.
        }
    }

    private void registerInstallReceiver() {
        if (installReceiver != null) {
            return;
        }
        installReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
                if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(confirm);
                    }
                    return;   // not terminal; keep the receiver for the result
                }
                if (st == PackageInstaller.STATUS_SUCCESS) {
                    log("Updated — the new version is installed.");
                } else if (st == PackageInstaller.STATUS_FAILURE_ABORTED) {
                    log("Install cancelled.");
                } else {
                    log("Install failed: " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
                }
                unregisterInstallReceiver();
            }
        };
        IntentFilter f = new IntentFilter(INSTALL_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.registerReceiver(installReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            activity.registerReceiver(installReceiver, f);
        }
    }

    private void unregisterInstallReceiver() {
        if (installReceiver != null) {
            try {
                activity.unregisterReceiver(installReceiver);
            } catch (Exception ignored) {
                // Already gone; nothing to do.
            }
            installReceiver = null;
        }
    }

    // --------------------------------------------------------------- helpers

    private static String readBody(HttpsURLConnection c) throws Exception {
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                b.write(buf, 0, n);
            }
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void refreshVersion() {
        try {
            android.content.pm.PackageInfo pi = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            versionLabel.setText("Installed: " + pi.versionName + " (code " + pi.versionCode + ")");
        } catch (Exception e) {
            versionLabel.setText("Installed: unknown");
        }
    }

    private void post(String m) {
        activity.runOnUiThread(() -> log(m));
    }

    private void log(String m) {
        status.setText(now() + "  " + m);
    }

    private static String now() {
        return String.format(java.util.Locale.ROOT, "%tT", System.currentTimeMillis());
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams weightGap() {
        LinearLayout.LayoutParams lp = weight();
        lp.leftMargin = Ui.dp(activity, 8);
        return lp;
    }

    private LinearLayout.LayoutParams topMargin(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(activity, dp);
        return lp;
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    public View view() {
        return page;
    }

    @Override
    public void onShow() {
        polling = true;
        refreshVersion();
        ui.removeCallbacks(poll);
        ui.post(poll);
    }

    @Override
    public void onHide() {
        polling = false;
        ui.removeCallbacks(poll);
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!polling) {
                return;
            }
            checkNow(true);
            ui.postDelayed(this, POLL_MS);
        }
    };
}
