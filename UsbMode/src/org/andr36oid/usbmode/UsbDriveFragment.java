package org.andr36oid.usbmode;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Storage choices use the same settings list and D-pad navigation as USB mode. */
public class UsbDriveFragment extends PreferenceFragment {
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private UsbMode mUsbMode;
    private SwitchPreference mReadOnly;
    private UsbDrive.Offer mSelected;
    private AlertDialog mDialog;
    private boolean mRo, mResumed;
    private String mShown = "", mName = "", mSelectedKey = "", mFocusKey = "";

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
        mUsbMode = new UsbMode(getActivity());
        if (state != null) {
            mRo = state.getBoolean("ro");
            mName = state.getString("name", "");
            mSelectedKey = state.getString("selected", "");
            mFocusKey = state.getString("focus", "");
        }
        setPreferenceScreen(getPreferenceManager().createPreferenceScreen(getActivity()));
    }

    @Override
    public void onViewCreated(View view, Bundle state) {
        super.onViewCreated(view, state);
        final ListView list = view.findViewById(android.R.id.list);
        list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View row, int position, long id) {
                final Object item = parent.getItemAtPosition(position);
                if (item instanceof Preference) mFocusKey = ((Preference) item).getKey();
                if (item instanceof DrivePreference) select(((DrivePreference) item).offer);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    @Override
    public void onSaveInstanceState(Bundle state) {
        state.putBoolean("ro", mRo);
        state.putString("name", mName);
        state.putString("selected", mSelectedKey);
        state.putString("focus", mFocusKey);
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

    private void refresh() {
        if (getActivity() == null || getView() == null) return;
        final String state = UsbDriveControl.state();
        final boolean busy = UsbDriveControl.busy();
        final List<UsbDrive.Offer> offers = UsbDriveControl.scan(getActivity());
        final String target = android.os.SystemProperties.get("sys.andr36oid.usbdrive.target");
        final StringBuilder signature = new StringBuilder(state).append(busy).append(target);
        for (UsbDrive.Offer o : offers) signature.append(o.identity()).append(o.reason)
                .append(o.label).append(o.type).append(o.bytes).append(o.forcedRo);
        if (signature.toString().equals(mShown)) return;
        mShown = signature.toString();
        final String focus = mFocusKey;
        final PreferenceScreen root = getPreferenceScreen();
        root.removeAll();
        mReadOnly = null;
        mSelected = null;
        if (busy) {
            for (UsbDrive.Offer o : offers) if (o.node.equals(target)) mName = name(o);
            final Preference summary = row("status", R.string.usb_drive_title);
            summary.setSummary("sharing".equals(state) ? getString(R.string.usb_drive_sharing, mName)
                    : getString(R.string.usb_drive_working));
            root.addPreference(summary);
            final Preference stop = row("stop", R.string.usb_drive_stop);
            stop.setSummary(R.string.usb_drive_eject);
            stop.setOnPreferenceClickListener(p -> { UsbDriveControl.stop(); refresh(); return true; });
            root.addPreference(stop);
        } else {
            if ("error".equals(state)) {
                final Preference error = row("status", R.string.usb_drive_title);
                error.setSummary(R.string.usb_drive_failed);
                root.addPreference(error);
            }
            for (UsbDrive.Offer o : offers) {
                root.addPreference(new DrivePreference(o));
                if (o.identity().equals(mSelectedKey) || (mSelected == null && o.enabled())) {
                    mSelected = o;
                }
            }
            if (offers.isEmpty()) {
                final Preference empty = row("empty", R.string.usb_drive_title);
                empty.setSummary(R.string.usb_drive_empty);
                root.addPreference(empty);
            }
            mReadOnly = new SwitchPreference(getActivity()) {
                @Override
                protected void onBindView(View view) {
                    super.onBindView(view);
                    // Keep the row in D-pad navigation when the switch is fixed.
                    final View toggle = view.findViewById(android.R.id.switch_widget);
                    if (toggle != null) toggle.setEnabled(!readOnlyFixed());
                }
            };
            mReadOnly.setKey("ro");
            mReadOnly.setPersistent(false);
            mReadOnly.setTitle(R.string.usb_drive_ro);
            mReadOnly.setOnPreferenceChangeListener((p, value) -> {
                if (readOnlyFixed()) {
                    details(getString(R.string.usb_drive_ro), readOnlyReason());
                    return false;
                }
                mRo = (Boolean) value;
                return true;
            });
            root.addPreference(mReadOnly);
            if (mSelected != null) select(mSelected);
            else {
                mReadOnly.setChecked(mRo);
                mReadOnly.setSummary(readOnlyReason());
            }
        }
        final Preference footer = row("about", R.string.usb_drive_title);
        footer.setTitle(null);
        footer.setSummary(getString(R.string.usb_drive_intro) + "\n\n" + getString(R.string.usb_drive_cable));
        root.addPreference(footer);
        final String fallback = busy ? "stop"
                : mSelected == null ? "about" : "drive:" + mSelected.identity();
        restoreFocus(focus, fallback);
    }

    private Preference row(String key, int title) {
        final Preference row = new Preference(getActivity()) {
            @Override
            protected void onBindView(View view) {
                super.onBindView(view);
                final TextView summary = view.findViewById(android.R.id.summary);
                if (summary != null) {
                    summary.setSingleLine(false);
                    summary.setEllipsize(null);
                }
            }
        };
        row.setKey(key);
        row.setTitle(title);
        row.setOnPreferenceClickListener(p -> { details(p.getTitle(), p.getSummary()); return true; });
        return row;
    }

    private void restoreFocus(String key, String fallback) {
        final ListView list = getView().findViewById(android.R.id.list);
        list.post(() -> {
            if (!mResumed || getView() == null) return;
            int chosen = -1;
            for (int i = 0; i < list.getCount(); i++) {
                final Preference p = (Preference) list.getItemAtPosition(i);
                if (!p.isEnabled()) continue;
                if (fallback.equals(p.getKey())) chosen = i;
                if (key.equals(p.getKey())) { chosen = i; break; }
            }
            if (chosen >= 0) list.setSelection(chosen);
            list.requestFocus();
        });
    }

    private void select(UsbDrive.Offer offer) {
        mSelected = offer;
        mSelectedKey = offer.identity();
        if (mReadOnly == null) return;
        mReadOnly.setChecked(offer.forcedRo || mRo);
        mReadOnly.setSummary(readOnlyReason());
    }

    private boolean readOnlyFixed() {
        return mSelected == null || !mSelected.enabled() || mSelected.forcedRo;
    }

    private String readOnlyReason() {
        if (mSelected == null) return getString(R.string.usb_drive_empty);
        if (mSelected.forcedRo) return getString(R.string.usb_drive_system_ro);
        if (!mSelected.enabled()) return explanation(mSelected);
        return getString(R.string.usb_drive_ro_hint);
    }

    private void details(CharSequence title, CharSequence message) {
        if (mDialog != null) return;
        mDialog = new AlertDialog.Builder(getActivity(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(title == null ? getString(R.string.usb_drive_title) : title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        mDialog.setOnDismissListener(d -> mDialog = null);
        mDialog.show();
    }

    private final class DrivePreference extends Preference {
        final UsbDrive.Offer offer;

        DrivePreference(UsbDrive.Offer offer) {
            super(getActivity());
            this.offer = offer;
            setKey("drive:" + offer.identity());
            setPersistent(false);
            final String type = offer.kind == UsbDrive.Kind.EMMC ? getString(R.string.usb_drive_emmc)
                    : offer.type.isEmpty() ? getString(R.string.usb_drive_sd) : offer.type;
            setTitle(name(offer) + " · " + Formatter.formatFileSize(getActivity(), offer.bytes)
                    + " · " + type);
            setSummary(explanation(offer));
            // Unavailable drives stay in D-pad navigation so their reason can be read.
            setOnPreferenceClickListener(p -> {
                select(offer);
                if (offer.enabled()) ask(offer);
                else details(name(offer), explanation(offer));
                return true;
            });
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            final TextView title = view.findViewById(android.R.id.title);
            final TextView summary = view.findViewById(android.R.id.summary);
            if (title != null) title.setEnabled(offer.enabled());
            if (summary != null) {
                summary.setSingleLine(true);
                summary.setEllipsize(TextUtils.TruncateAt.END);
                summary.setEnabled(offer.enabled());
            }
        }
    }

    private void ask(UsbDrive.Offer offer) {
        if (mDialog != null || UsbDriveControl.busy()) return;
        if (!mUsbMode.isSupported()) {
            Toast.makeText(getActivity(), R.string.usb_mode_unsupported, Toast.LENGTH_LONG).show();
            return;
        }
        if (mUsbMode.chargingBlocksDevice()) {
            Toast.makeText(getActivity(), R.string.charging_refused, Toast.LENGTH_LONG).show();
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
        final AlertDialog dialog = new AlertDialog.Builder(getActivity(),
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
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
            Toast.makeText(getActivity(), R.string.charging_refused, Toast.LENGTH_LONG).show();
            return;
        }
        // Recheck after the dialog: a removed card or changed layout is a new offer.
        UsbDrive.Offer fresh = null;
        for (UsbDrive.Offer o : UsbDriveControl.scan(getActivity())) {
            if (o.enabled() && o.identity().equals(offer.identity())) fresh = o;
        }
        if (fresh == null || UsbDriveControl.state().isEmpty() || UsbDriveControl.busy()) {
            Toast.makeText(getActivity(), R.string.usb_drive_failed, Toast.LENGTH_LONG).show();
            return;
        }
        mUsbMode.setConfirmedThisBoot();
        mName = name(fresh);
        UsbDriveControl.start(getActivity(), fresh, ro);
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
}
