package org.andr36oid.usbmode;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.widget.Button;

/**
 * Asked every time before the OTG port becomes a device for a computer. Consoles with built-in
 * Wi-Fi (such as the R36XX) wire their Wi-Fi to this same port, so device mode cuts it off.
 * There is no reliable way to tell those consoles apart (same device tree as an R36S, and
 * built-in Wi-Fi looks just like a plugged-in adapter), so the user has to confirm.
 * Cancel has the focus, so pressing A by accident does nothing.
 */
final class DeviceModeWarning {

    interface Callback {
        void onConfirmed();

        void onCancelled();
    }

    private DeviceModeWarning() {
    }

    static AlertDialog show(Context context, UsbMode usbMode, final Callback callback) {
        final StringBuilder message = new StringBuilder();
        final String wifi = usbMode.getWifiOnOtgPort();
        if (wifi != null) {
            message.append(context.getString(R.string.warning_wifi_found, wifi)).append("\n\n");
        }
        message.append(context.getString(R.string.warning_wifi));

        final boolean[] confirmed = new boolean[1];
        final AlertDialog dialog = new AlertDialog.Builder(context,
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.warning_title)
                .setMessage(message)
                .setPositiveButton(R.string.warning_confirm, (d, which) -> {
                    confirmed[0] = true;
                    usbMode.setConfirmedThisBoot();
                    callback.onConfirmed();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnDismissListener(d -> {
            if (!confirmed[0]) {
                callback.onCancelled();
            }
        });
        dialog.setOnShowListener(d -> {
            final Button cancel = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
            cancel.setFocusable(true);
            cancel.requestFocus();
        });
        dialog.show();
        return dialog;
    }
}
