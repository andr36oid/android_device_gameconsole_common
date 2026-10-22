package org.andr36oid.usbmode;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.andr36oid.hints.HintBar;

import java.util.List;

/** D-pad storage rows: focus chooses the read-only rule, A asks to share. */
public class UsbDriveActivity extends Activity {
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private UsbMode mUsbMode;
    private LinearLayout mBody;
    private CheckBox mReadOnly;
    private TextView mReason;
    private UsbDrive.Offer mSelected;
    private AlertDialog mDialog;
    private boolean mRo, mResumed;
    private String mShown = "", mName = "";

    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            refresh();
            if (mResumed) mHandler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        mUsbMode = new UsbMode(this);
        if (state != null) {
            mRo = state.getBoolean("ro");
            mName = state.getString("name", "");
        }
        final ScrollView scroll = new ScrollView(this);
        mBody = new LinearLayout(this);
        mBody.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(12);
        mBody.setPadding(pad, pad, pad, pad);
        scroll.addView(mBody);
        setContentView(scroll);
        HintBar.attachDefault(this);
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("ro", mRo);
        state.putString("name", mName);
        super.onSaveInstanceState(state);
    }

    @Override
    public void onResume() {
        super.onResume();
        mResumed = true;
        mHandler.post(mPoll);
    }

    @Override
    public void onPause() {
        mResumed = false;
        mHandler.removeCallbacks(mPoll);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        if (mDialog != null) mDialog.dismiss();
        super.onDestroy();
    }

    @Override
    public boolean onNavigateUp() { finish(); return true; }

    private void refresh() {
        final String state = UsbDriveControl.state();
        final boolean busy = UsbDriveControl.busy();
        final List<UsbDrive.Offer> offers = UsbDriveControl.scan(this);
        final StringBuilder signature = new StringBuilder(state).append(busy);
        for (UsbDrive.Offer o : offers) signature.append(o.identity()).append(o.reason)
                .append(o.label).append(o.type).append(o.bytes).append(o.forcedRo);
        if (signature.toString().equals(mShown)) return;
        mShown = signature.toString();
        mReadOnly = null;
        mReason = null;
        mSelected = null;
        mBody.removeAllViews();
        if (busy) {
            if (mName.isEmpty()) for (UsbDrive.Offer o : offers) {
                if (o.node.equals(android.os.SystemProperties.get("sys.andr36oid.usbdrive.target"))) {
                    mName = name(o);
                }
            }
            text("sharing".equals(state) ? getString(R.string.usb_drive_sharing, mName)
                    : getString(R.string.usb_drive_working));
            text(getString(R.string.usb_drive_eject));
            final Button stop = new Button(this);
            stop.setText(R.string.usb_drive_stop);
            stop.setOnClickListener(v -> { UsbDriveControl.stop(); refresh(); });
            mBody.addView(stop);
            stop.requestFocus();
            return;
        }
        text(getString(R.string.usb_drive_intro));
        text(getString(R.string.usb_drive_cable));
        if ("error".equals(state)) text(getString(R.string.usb_drive_failed));
        View first = null;
        for (UsbDrive.Offer o : offers) {
            final LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            final LinearLayout title = new LinearLayout(this);
            final TextView label = new TextView(this);
            label.setText(name(o));
            label.setTextSize(18);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            title.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
            final TextView size = new TextView(this);
            size.setText(android.text.format.Formatter.formatFileSize(this, o.bytes) + "  "
                    + (o.kind == UsbDrive.Kind.EMMC ? getString(R.string.usb_drive_emmc)
                    : o.type.isEmpty() ? getString(R.string.usb_drive_sd) : o.type));
            size.setGravity(Gravity.END);
            title.addView(size);
            row.addView(title);
            final TextView explanation = new TextView(this);
            explanation.setText(explanation(o));
            explanation.setSingleLine(true);
            explanation.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(explanation);
            row.setEnabled(o.enabled());
            row.setFocusable(o.enabled());
            row.setClickable(o.enabled());
            row.setAlpha(o.enabled() ? 1f : 0.4f);
            if (o.enabled()) {
                row.setOnFocusChangeListener((v, focused) -> { if (focused) select(o); });
                row.setOnClickListener(v -> { select(o); ask(o); });
                if (first == null) { first = row; mSelected = o; }
            }
            mBody.addView(row);
        }
        if (offers.isEmpty()) text(getString(R.string.usb_drive_empty));
        mReadOnly = new CheckBox(this);
        mReadOnly.setText(R.string.usb_drive_ro);
        mBody.addView(mReadOnly);
        mReason = text("");
        mReadOnly.setOnClickListener(v -> mRo = mReadOnly.isChecked());
        if (mSelected != null) select(mSelected);
        else mReadOnly.setEnabled(false);
        if (first != null) first.requestFocus();
    }

    private void select(UsbDrive.Offer offer) {
        mSelected = offer;
        if (mReadOnly == null || mReason == null) return;
        mReadOnly.setChecked(offer.forcedRo || mRo);
        mReadOnly.setEnabled(!offer.forcedRo);
        mReason.setText(offer.forcedRo ? R.string.usb_drive_system_ro : R.string.usb_drive_ro_hint);
    }

    private void ask(UsbDrive.Offer offer) {
        if (mDialog != null || UsbDriveControl.busy()) return;
        if (!mUsbMode.isSupported()) {
            Toast.makeText(this, R.string.usb_mode_unsupported, Toast.LENGTH_LONG).show();
            return;
        }
        if (mUsbMode.chargingBlocksDevice()) {
            Toast.makeText(this, R.string.charging_refused, Toast.LENGTH_LONG).show();
            return;
        }
        final boolean ro = mRo || offer.forcedRo;
        final boolean scary = offer.kind == UsbDrive.Kind.EMMC && !ro;
        final StringBuilder message = new StringBuilder();
        if (scary) message.append(getString(R.string.usb_drive_emmc_warning)).append("\n\n");
        final String wifi = mUsbMode.getWifiOnOtgPort();
        if (wifi != null) message.append(getString(R.string.warning_wifi_found, wifi)).append("\n\n");
        message.append(getString(R.string.warning_wifi)).append("\n\n")
                .append(getString(R.string.charging_warning)).append("\n\n")
                .append(getString(R.string.usb_drive_apps));
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(scary ? R.string.usb_drive_emmc_title : R.string.usb_drive_confirm)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(scary ? R.string.usb_drive_sure : R.string.usb_drive_share,
                        (d, which) -> share(offer, ro))
                .create();
        mDialog = dialog;
        dialog.setOnDismissListener(d -> mDialog = null);
        dialog.setOnShowListener(d -> {
            final Button cancel = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
            cancel.setFocusable(true);
            cancel.requestFocus();
            if (!scary) return;
            final Button confirm = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            final long until = SystemClock.elapsedRealtime() + 5000;
            confirm.setEnabled(false);
            final Runnable countdown = new Runnable() {
                @Override
                public void run() {
                    if (!dialog.isShowing()) return;
                    final int left = (int) Math.max(0, (until - SystemClock.elapsedRealtime() + 999) / 1000);
                    if (left == 0) {
                        confirm.setText(R.string.usb_drive_sure);
                        confirm.setEnabled(true);
                    } else {
                        confirm.setText(getString(R.string.usb_drive_countdown, left));
                        mHandler.postDelayed(this, 200);
                    }
                }
            };
            countdown.run();
        });
        dialog.show();
    }

    private void share(UsbDrive.Offer offer, boolean ro) {
        if (mUsbMode.chargingBlocksDevice()) {
            Toast.makeText(this, R.string.charging_refused, Toast.LENGTH_LONG).show();
            return;
        }
        // Recheck after the dialog: a removed card or changed layout is a new offer.
        UsbDrive.Offer fresh = null;
        for (UsbDrive.Offer o : UsbDriveControl.scan(this)) {
            if (o.enabled() && o.identity().equals(offer.identity())) fresh = o;
        }
        if (fresh == null || UsbDriveControl.state().isEmpty() || UsbDriveControl.busy()) {
            Toast.makeText(this, R.string.usb_drive_failed, Toast.LENGTH_LONG).show();
            return;
        }
        mUsbMode.setConfirmedThisBoot();
        mName = name(fresh);
        UsbDriveControl.start(this, fresh, ro);
        refresh();
    }

    private String name(UsbDrive.Offer o) {
        if (o.kind == UsbDrive.Kind.EMMC) return getString(R.string.usb_drive_emmc);
        if (o.kind == UsbDrive.Kind.EASYROMS) return getString(R.string.usb_drive_easyroms, o.slot);
        return o.label.isEmpty() ? getString(R.string.usb_drive_slot, o.slot)
                : getString(R.string.usb_drive_card, o.slot, o.label);
    }

    private String explanation(UsbDrive.Offer o) {
        switch (o.reason) {
            case INTERNAL: return getString(R.string.usb_drive_internal);
            case SYSTEM: return getString(R.string.usb_drive_system_card);
            case ANDROID: return getString(R.string.usb_drive_android_card);
            case UNKNOWN: return getString(R.string.usb_drive_unknown);
            case BUSY: return getString(R.string.usb_drive_busy);
            case UNUSABLE: return getString(R.string.usb_drive_unusable);
            default: break;
        }
        if (o.kind == UsbDrive.Kind.EASYROMS) return getString(R.string.usb_drive_games);
        if (o.kind == UsbDrive.Kind.CARD) return getString(R.string.usb_drive_whole);
        return getString(o.forcedRo ? R.string.usb_drive_system_ro : R.string.usb_drive_chip);
    }

    private TextView text(String message) {
        final TextView view = new TextView(this);
        view.setText(message);
        view.setPadding(0, dp(4), 0, dp(8));
        mBody.addView(view);
        return view;
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
}
