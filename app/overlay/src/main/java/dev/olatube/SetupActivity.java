package dev.olatube;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Toast;

/**
 * One-shot launcher. The dash shows no normal app drawer, so this activity
 * exists only to be started once (by hand or over adb) to:
 *   1. grant "draw over other apps" if missing,
 *   2. start {@link OverlayService} (the invisible centre tap zone), and
 *   3. point the user at Settings > Accessibility to enable
 *      {@link YtAccessibilityService} (needed for the SmartTube back/close
 *      buttons and for hiding the launch zone while a video plays).
 * It then finishes itself — there is no UI.
 */
public class SetupActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Floating WebView player control: am start ... -e player open|close
        String player = getIntent() != null ? getIntent().getStringExtra("player") : null;
        if (player != null) {
            if ("open".equals(player)) WebPlayerService.start(this);
            else if ("close".equals(player)) WebPlayerService.stop(this);
            finish();
            return;
        }

        // 7-tap launch lock, from Pegasus: am start ... -e feature lock|unlock.
        // Only flips the persisted flag (and the live service, if any) — never
        // spawns the service or shows the permission/accessibility prompts.
        String feature = getIntent() != null ? getIntent().getStringExtra("feature") : null;
        if (feature != null) {
            if ("lock".equals(feature)) OverlayService.applyLock(this, true);
            else if ("unlock".equals(feature)) OverlayService.applyLock(this, false);
            finish();
            return;
        }

        boolean canDraw = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(this);

        if (!canDraw) {
            Toast.makeText(this, "Grant 'Display over other apps', then reopen",
                    Toast.LENGTH_LONG).show();
            startActivity(new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
            return;
        }

        OverlayService.start(this);

        if (!isA11yEnabled()) {
            Toast.makeText(this,
                    "Enable 'YT Zone' in Accessibility for back/close + auto-hide",
                    Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
        } else {
            Toast.makeText(this, "YouTube zone active (centre, seven-tap)",
                    Toast.LENGTH_SHORT).show();
        }
        finish();
    }

    /** True if our accessibility service is in the enabled list. */
    private boolean isA11yEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(flat)) return false;
        String me = getPackageName() + "/" + YtAccessibilityService.class.getName();
        TextUtils.SimpleStringSplitter sp = new TextUtils.SimpleStringSplitter(':');
        sp.setString(flat);
        for (String s : sp) {
            if (s.equalsIgnoreCase(me)) return true;
        }
        return false;
    }
}
