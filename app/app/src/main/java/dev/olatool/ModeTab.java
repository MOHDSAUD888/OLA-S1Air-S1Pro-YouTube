package dev.olatool;

import android.app.AlertDialog;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import dev.olatool.core.Msg;

/**
 * Customer / Service switch.
 *
 * <p>This writes {@code persist.ola.security.scootermode}. That property only
 * feeds two init triggers in {@code /vendor/etc/init/ola_e2.rc}, which add or
 * remove an iptables rule for TCP 50005 and 5555 and restart adbd. It does not
 * touch the CAN path, the motor, or any ride behaviour, and it is a plain
 * {@code persist.} property, so it can be set straight back.
 *
 * <p>The rule is IPv4 only. That is why the switch is disabled when the live
 * link is IPv4: the app would cut its own connection.
 *
 * <p>{@code SetVehicleOperationMode} is deliberately not offered. It writes
 * {@code /data/vendor/ola/v-op-mode.json} and has never been tested here, so it
 * is shown read-only.
 */
public final class ModeTab implements Tab {

    private final MainActivity activity;
    private final Conn conn;
    private final ScrollView page;

    private final TextView networkValue;
    private final TextView networkDetail;
    private final TextView vehicleValue;
    private final TextView result;
    private final TextView ipv4Warning;
    private final View customerButton;
    private final View serviceButton;

    public ModeTab(MainActivity activity, Conn conn) {
        this.activity = activity;
        this.conn = conn;
        this.page = Ui.page(activity);
        LinearLayout body = Ui.body(page);

        body.addView(Ui.text(activity, "Operation mode", 18, Ui.TEXT));

        ipv4Warning = Ui.text(activity, "", 12, Ui.BAD);
        ipv4Warning.setPadding(0, Ui.dp(activity, 6), 0, 0);
        ipv4Warning.setVisibility(View.GONE);
        body.addView(ipv4Warning);

        LinearLayout network = Ui.card(activity);
        network.addView(Ui.heading(activity, "Network mode (persist.ola.security.scootermode)"));
        networkValue = Ui.text(activity, "-", 22, Ui.TEXT);
        network.addView(networkValue);
        networkDetail = Ui.note(activity,
                "cust closes TCP 50005 and 5555 over IPv4 and makes adbd refuse keys. "
                        + "serv opens those ports again. IPv6 is never filtered.");
        networkDetail.setPadding(0, Ui.dp(activity, 6), 0, 0);
        network.addView(networkDetail);
        body.addView(network, topMargin(12));

        LinearLayout buttons = Ui.row(activity);
        customerButton = Ui.accent(activity, "Customer", v -> confirm("cust"));
        serviceButton = Ui.button(activity, "Service", v -> confirm("serv"));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        gap.leftMargin = Ui.dp(activity, 8);
        buttons.addView(customerButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        buttons.addView(serviceButton, gap);
        body.addView(buttons, topMargin(8));

        result = Ui.text(activity, "", 12, Ui.DIM);
        result.setPadding(0, Ui.dp(activity, 8), 0, 0);
        body.addView(result);

        LinearLayout vehicle = Ui.card(activity);
        vehicle.addView(Ui.heading(activity, "Vehicle operation mode (read-only)"));
        vehicleValue = Ui.text(activity, "-", 14, Ui.TEXT);
        vehicle.addView(vehicleValue);
        TextView why = Ui.note(activity,
                "GetVehicleOperationMode is read here and nothing else. The setter "
                        + "writes /data/vendor/ola/v-op-mode.json and has not been tested "
                        + "on this scooter, so it is not exposed.");
        why.setPadding(0, Ui.dp(activity, 6), 0, 0);
        vehicle.addView(why);
        body.addView(vehicle, topMargin(12));

        body.addView(Ui.button(activity, "Refresh", v -> refresh()), topMargin(10));

        TextView footer = Ui.note(activity,
                "Nothing here changes how the scooter rides, charges, or starts. "
                        + "A reboot does not undo it: switch back from this screen.");
        footer.setPadding(0, Ui.dp(activity, 12), 0, 0);
        body.addView(footer);
    }

    private LinearLayout.LayoutParams topMargin(int dp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(activity, dp);
        return lp;
    }

    @Override
    public View view() {
        return page;
    }

    @Override
    public void onShow() {
        refresh();
    }

    @Override
    public void onHide() {
    }

    private void refresh() {
        if (!conn.ready()) {
            networkValue.setText("not connected");
            networkValue.setTextColor(Ui.DIM);
            vehicleValue.setText("-");
            return;
        }

        boolean ipv6 = conn.ipv6();
        customerButton.setEnabled(ipv6);
        serviceButton.setEnabled(ipv6);
        customerButton.setAlpha(ipv6 ? 1f : 0.4f);
        serviceButton.setAlpha(ipv6 ? 1f : 0.4f);
        ipv4Warning.setVisibility(ipv6 ? View.GONE : View.VISIBLE);
        if (!ipv6) {
            ipv4Warning.setText("You are on IPv4. Switching to Customer would close the "
                    + "IPv4 ports and drop this connection, so the switch is disabled. "
                    + "Reconnect so the app uses IPv6.");
        }

        conn.run(rpc -> {
            String mode = rpc.networkMode();
            String vehicle;
            try {
                Msg.OpMode op = rpc.vehicleOperationMode();
                vehicle = op.modeName()
                        + (op.modifiedBy.isEmpty() ? "" : "  ·  set by " + op.modifiedBy)
                        + (op.modifiedTsStr.isEmpty() ? "" : "  ·  " + op.modifiedTsStr);
            } catch (RuntimeException e) {
                vehicle = "unavailable (" + e.getMessage() + ")";
            }
            return new String[]{mode, vehicle};
        }, (value, error) -> {
            if (error != null) {
                networkValue.setText("error");
                networkValue.setTextColor(Ui.BAD);
                result.setText(error.getMessage());
                result.setTextColor(Ui.BAD);
                return;
            }
            showMode(value[0]);
            vehicleValue.setText(value[1]);
        });
    }

    private void showMode(String raw) {
        String mode = raw == null ? "" : raw.trim();
        if ("cust".equals(mode)) {
            networkValue.setText("Customer");
            networkValue.setTextColor(Ui.ACCENT);
            networkDetail.setText("IPv4 TCP 50005 and 5555 are closed. adbd refuses keys. "
                    + "The IPv6 link you are on now keeps working.");
        } else if ("serv".equals(mode)) {
            networkValue.setText("Service");
            networkValue.setTextColor(Ui.WARN);
            networkDetail.setText("IPv4 TCP 50005 and 5555 are open and adbd is running. "
                    + "Useful for adb and scrcpy; switch back when you are done.");
        } else if (mode.isEmpty()) {
            networkValue.setText("not set");
            networkValue.setTextColor(Ui.DIM);
        } else {
            networkValue.setText(mode);
            networkValue.setTextColor(Ui.TEXT);
        }
    }

    private void confirm(String mode) {
        boolean customer = "cust".equals(mode);
        String title = customer ? "Switch to Customer mode?" : "Switch to Service mode?";
        String message = customer
                ? "This sets persist.ola.security.scootermode=cust.\n\n"
                + "• TCP 50005 and 5555 close over IPv4\n"
                + "• adbd stops trusting keys\n"
                + "• the IPv6 link this app uses keeps working\n\n"
                + "Riding, charging and the motor are not affected."
                : "This sets persist.ola.security.scootermode=serv.\n\n"
                + "• TCP 50005 and 5555 open over IPv4\n"
                + "• adbd restarts and reads Ola's service key\n\n"
                + "Switch back to Customer when you are done.";

        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("Switch", (d, w) -> apply(mode))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void apply(String mode) {
        result.setText("Switching ...");
        result.setTextColor(Ui.DIM);
        conn.run(rpc -> {
            Msg.Response response = rpc.setNetworkMode(mode, 20_000);
            Thread.sleep(700);
            return new String[]{response.toString(), rpc.networkMode()};
        }, (value, error) -> {
            if (error != null) {
                result.setText("Failed: " + error.getMessage());
                result.setTextColor(Ui.BAD);
                return;
            }
            showMode(value[1]);
            boolean landed = mode.equals(value[1].trim());
            result.setText(landed ? "Mode is now " + value[1].trim() + "."
                    : "The property did not change: " + value[0]);
            result.setTextColor(landed ? Ui.ACCENT : Ui.WARN);
        });
    }
}
