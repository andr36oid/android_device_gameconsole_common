package org.andr36oid.usbmode;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.Preference;
import android.preference.ListPreference;
import android.preference.PreferenceManager;
import android.content.SharedPreferences;
import android.content.Intent;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.view.ViewGroup;
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
    private long mNextScan;
    private UsbFunctions mPolicy;
    private SharedPreferences mPrefs;
    private int mFunctions;
    private String mPreset;
    private boolean mCdrom;
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
        getActivity().setTitle(R.string.usb_device_title);
        mPolicy = UsbDeviceControl.policies();
        mPrefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
        mPreset = mPrefs.getString("usb_preset", "custom");
        mFunctions = mPrefs.getInt("usb_functions", UsbFunctions.XINPUT);
        mSelectedKey = mPrefs.getString("usb_storage", "");
        mCdrom = mPrefs.getBoolean("usb_cdrom", true);
        mRo = mPrefs.getBoolean("usb_ro", true);
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
        if (!busy && SystemClock.elapsedRealtime() >= mNextScan) {
            UsbDriveControl.scanImages();
            mNextScan = SystemClock.elapsedRealtime() + 5000;
        }
        final UsbDrive.Images images = UsbDriveControl.images();
        offers.addAll(images.offers);
        if (!UsbDeviceControl.debugging(getActivity())) mFunctions &= ~UsbFunctions.ADB;
        final StringBuilder signature = new StringBuilder(state).append(busy).append(mFunctions)
                .append(mPreset).append(mSelectedKey).append(mCdrom).append(mRo).append(images.mounted)
                .append(UsbDeviceControl.debugging(getActivity()));
        mSelected = null;
        for (UsbDrive.Offer o : offers) {
            signature.append(o.identity()).append(o.reason).append(o.label).append(o.bytes).append(o.forcedRo);
            if (o.identity().equals(mSelectedKey)) mSelected = o;
        }
        if (signature.toString().equals(mShown)) return;
        mShown = signature.toString();
        final String focus = mFocusKey;
        final PreferenceScreen root = getPreferenceScreen();
        root.removeAll();
        if (busy) {
            final Preference session = row("session", R.string.usb_device_session);
            session.setOnPreferenceClickListener(p -> {
                startActivity(new Intent(getActivity(), ControllerSessionActivity.class)); return true;
            });
            root.addPreference(session);
            return;
        }
        if ("error".equals(state)) {
            final Preference error = row("error", R.string.usb_device_failed);
            root.addPreference(error);
        }
        final ListPreference preset = new ListPreference(getActivity());
        preset.setKey("preset"); preset.setPersistent(false); preset.setTitle(R.string.usb_device_preset);
        preset.setEntries(new CharSequence[] {getString(R.string.usb_device_pc),
                getString(R.string.usb_device_game), getString(R.string.usb_drive_title),
                getString(R.string.usb_device_custom)});
        preset.setEntryValues(new String[] {"pc", "game", "drive", "custom"});
        preset.setValue(mPreset); preset.setSummary("%s");
        preset.setOnPreferenceChangeListener((p, value) -> {
            if ("custom".equals(mPreset)) saveCustom();
            mPreset = (String) value;
            if ("custom".equals(mPreset)) {
                mFunctions = mPrefs.getInt("usb_custom_functions", UsbFunctions.XINPUT);
                mSelectedKey = mPrefs.getString("usb_custom_storage", "");
                mCdrom = mPrefs.getBoolean("usb_custom_cdrom", true);
                mRo = mPrefs.getBoolean("usb_custom_ro", true);
            } else {
                mFunctions = "pc".equals(mPreset) ? UsbFunctions.STORAGE | UsbFunctions.KEYBOARD | UsbFunctions.MOUSE
                        : "game".equals(mPreset) ? UsbFunctions.XINPUT : UsbFunctions.STORAGE;
                // Preserve debugging when it was enabled; it fits every preset.
                if (UsbDeviceControl.debugging(getActivity())) mFunctions |= UsbFunctions.ADB;
                if (!"game".equals(mPreset)) {
                    mSelectedKey = "";
                    for (UsbDrive.Offer o : offers) if (o.enabled() &&
                            (!"pc".equals(mPreset) || o.kind == UsbDrive.Kind.IMAGE && o.cdromPossible())) {
                        mSelectedKey = o.identity(); break;
                    }
                    if ("pc".equals(mPreset)) { mCdrom = true; mRo = true; }
                }
            }
            changed(false); return false;
        });
        root.addPreference(preset);
        final int[] bits = {UsbFunctions.STORAGE, UsbFunctions.XINPUT, UsbFunctions.GAMEPAD,
                UsbFunctions.KEYBOARD, UsbFunctions.MOUSE, UsbFunctions.ADB};
        final int[] titles = {R.string.usb_device_storage, R.string.controller_xinput,
                R.string.controller_gamepad, R.string.usb_device_keyboard, R.string.usb_device_mouse,
                R.string.usb_device_adb};
        for (int i = 0; i < bits.length; i++) {
            final int bit = bits[i];
            final boolean checked = (mFunctions & bit) != 0;
            final SwitchPreference toggle = new SwitchPreference(getActivity());
            toggle.setKey("function:" + bit); toggle.setPersistent(false); toggle.setTitle(titles[i]);
            toggle.setChecked(checked);
            String reason = checked ? "ok" : mPolicy.reason(mFunctions | bit);
            if (bit == UsbFunctions.STORAGE && !checked && "ok".equals(reason)) {
                boolean available = false;
                for (UsbDrive.Offer o : offers) available |= o.enabled();
                if (!available) reason = "storage";
            }
            if (bit == UsbFunctions.ADB && !UsbDeviceControl.debugging(getActivity())) reason = "debugging";
            if (!"ok".equals(reason)) { toggle.setEnabled(false); toggle.setSummary(reason(reason)); }
            toggle.setOnPreferenceChangeListener((p, value) -> {
                mFunctions = (Boolean) value ? mFunctions | bit : mFunctions & ~bit;
                changed(true); return false;
            });
            root.addPreference(toggle);
        }
        if ((mFunctions & UsbFunctions.STORAGE) != 0) {
            final Preference storage = row("storage", R.string.usb_device_target);
            storage.setSummary(mSelected == null ? getString(R.string.usb_device_pick_storage)
                    : name(mSelected) + (mSelected.kind == UsbDrive.Kind.IMAGE ? " · " + getString(mCdrom
                    ? R.string.usb_drive_image_cd : R.string.usb_drive_image_usb) : ""));
            storage.setOnPreferenceClickListener(p -> { pickStorage(offers); return true; });
            root.addPreference(storage);
            mReadOnly = new SwitchPreference(getActivity());
            mReadOnly.setKey("ro"); mReadOnly.setPersistent(false); mReadOnly.setTitle(R.string.usb_drive_ro);
            mReadOnly.setChecked(mRo || readOnlyFixed() || mCdrom && mSelected != null
                    && mSelected.kind == UsbDrive.Kind.IMAGE);
            mReadOnly.setSummary(readOnlyReason());
            mReadOnly.setEnabled(!readOnlyFixed() && !(mCdrom && mSelected.kind == UsbDrive.Kind.IMAGE));
            mReadOnly.setOnPreferenceChangeListener((p, value) -> { mRo = (Boolean) value; changed(true); return false; });
            root.addPreference(mReadOnly);
        }
        if ((mFunctions & UsbFunctions.KEYBOARD) != 0) {
            final ListPreference layout = new ListPreference(getActivity());
            layout.setKey("keyboard_layout"); layout.setTitle(R.string.keyboard_layout);
            layout.setEntries(new CharSequence[] {getString(R.string.keyboard_layout_us),
                    getString(R.string.keyboard_layout_de), getString(R.string.keyboard_layout_fr)});
            layout.setEntryValues(KeyboardModel.LAYOUTS);
            layout.setDefaultValue(KeyboardModel.defaultLayout(java.util.Locale.getDefault()));
            layout.setSummary("%s"); root.addPreference(layout);
        }
        final Preference start = row("start", R.string.usb_device_start);
        String reason = mPolicy.reason(mFunctions);
        if ("ok".equals(reason) && (mFunctions & UsbFunctions.STORAGE) != 0 &&
                (mSelected == null || !mSelected.enabled())) reason = "storage";
        if (!"ok".equals(reason)) { start.setEnabled(false); start.setSummary(reason(reason)); }
        start.setOnPreferenceClickListener(p -> {
            if ((mFunctions & UsbFunctions.STORAGE) != 0) ask(mSelected, mSelected.kind == UsbDrive.Kind.IMAGE && mCdrom);
            else askInputs();
            return true;
        });
        root.addPreference(start);
        final Preference footer = row("about", R.string.usb_device_title);
        footer.setSummary(getString(R.string.usb_device_about) + "\n\n" + getString(R.string.usb_device_boot_hint)
                + "\n\n" + getString(R.string.usb_drive_image_hint) + "\n\n" + getString(R.string.usb_drive_cable));
        root.addPreference(footer);
        restoreFocus(focus, "start");
    }

    private void saveCustom() {
        mPrefs.edit().putInt("usb_custom_functions", mFunctions).putString("usb_custom_storage", mSelectedKey)
                .putBoolean("usb_custom_cdrom", mCdrom).putBoolean("usb_custom_ro", mRo).apply();
    }
    private void changed(boolean custom) {
        if (custom) { mPreset = "custom"; saveCustom(); }
        mPrefs.edit().putString("usb_preset", mPreset).putInt("usb_functions", mFunctions)
                .putString("usb_storage", mSelectedKey).putBoolean("usb_cdrom", mCdrom)
                .putBoolean("usb_ro", mRo).apply();
        mShown = ""; refresh();
    }
    private int reason(String reason) {
        switch (reason) {
            case "controllers": return R.string.usb_device_controllers_conflict;
            case "endpoints": return R.string.usb_device_endpoint_limit;
            case "empty": return R.string.usb_device_empty;
            case "storage": return R.string.usb_device_pick_storage;
            case "debugging": return R.string.usb_device_debugging_off;
            default: return R.string.usb_device_policy_unavailable;
        }
    }
    private void pickStorage(List<UsbDrive.Offer> offers) {
        if (mDialog != null) return;
        if (offers.isEmpty()) { details(getString(R.string.usb_device_target), getString(R.string.usb_drive_image_empty)); return; }
        final CharSequence[] names = new CharSequence[offers.size()];
        for (int i = 0; i < names.length; i++) {
            final UsbDrive.Offer o = offers.get(i);
            names[i] = name(o) + " · " + Formatter.formatFileSize(getActivity(), o.bytes)
                    + (o.enabled() ? "" : " · " + explanation(o));
        }
        final ArrayAdapter<CharSequence> adapter = new ArrayAdapter<CharSequence>(getActivity(),
                android.R.layout.simple_list_item_1, names) {
            @Override public boolean areAllItemsEnabled() { return false; }
            @Override public boolean isEnabled(int position) { return offers.get(position).enabled(); }
            @Override public View getView(int position, View view, ViewGroup parent) {
                final View row = super.getView(position, view, parent);
                row.setEnabled(isEnabled(position)); return row;
            }
        };
        mDialog = new AlertDialog.Builder(getActivity()).setTitle(R.string.usb_device_target)
                .setAdapter(adapter, (d, which) -> mHandler.post(() -> {
                    final UsbDrive.Offer offer = offers.get(which);
                    if (!offer.enabled()) { details(name(offer), explanation(offer)); return; }
                    select(offer);
                    if (offer.kind == UsbDrive.Kind.IMAGE) chooseImageMode(offer);
                    else changed(true);
                })).setNegativeButton(android.R.string.cancel, null).create();
        mDialog.setOnDismissListener(d -> mDialog = null); mDialog.show();
    }
    private void askInputs() {
        if (mDialog != null || UsbDriveControl.busy()) return;
        if (mUsbMode.chargingBlocksDevice()) {
            Toast.makeText(getActivity(), R.string.charging_refused, Toast.LENGTH_LONG).show(); return;
        }
        mDialog = DeviceModeWarning.show(getActivity(), mUsbMode, new DeviceModeWarning.Callback() {
            @Override public void onConfirmed() {
                mDialog = null;
                if (!mUsbMode.chargingBlocksDevice()) UsbDeviceControl.start(getActivity(), mFunctions, null, true, false);
            }
            @Override public void onCancelled() { mDialog = null; }
        });
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
        if (mSelected.forcedRo) return getString(mSelected.kind == UsbDrive.Kind.IMAGE
                ? R.string.usb_drive_image_volume_ro : R.string.usb_drive_system_ro);
        if (!mSelected.enabled()) return explanation(mSelected);
        if (mSelected.kind == UsbDrive.Kind.IMAGE) return getString(R.string.usb_drive_image_ro_hint);
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

    private void chooseImageMode(UsbDrive.Offer offer) {
        if (mDialog != null || UsbDriveControl.busy()) return;
        final int[] choice = {offer.defaultCdrom() ? 0 : 1};
        mDialog = new AlertDialog.Builder(getActivity(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(name(offer))
                .setSingleChoiceItems(new CharSequence[] {getString(R.string.usb_drive_image_cd),
                        getString(R.string.usb_drive_image_usb)}, choice[0],
                        (d, which) -> choice[0] = which)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok,
                        (d, which) -> mHandler.post(() -> { mCdrom = choice[0] == 0; changed(true); }))
                .create();
        mDialog.setOnDismissListener(d -> mDialog = null);
        mDialog.show();
    }

    private void ask(UsbDrive.Offer offer, boolean cdrom) {
        if (mDialog != null || UsbDriveControl.busy()) return;
        if (cdrom && !offer.cdromPossible()) {
            details(name(offer), getString(R.string.usb_drive_image_cd_size));
            return;
        }
        if (!mUsbMode.isSupported()) {
            Toast.makeText(getActivity(), R.string.usb_mode_unsupported, Toast.LENGTH_LONG).show();
            return;
        }
        if (mUsbMode.chargingBlocksDevice()) {
            Toast.makeText(getActivity(), R.string.charging_refused, Toast.LENGTH_LONG).show();
            return;
        }
        final boolean ro = cdrom || mRo || offer.forcedRo;
        final boolean scary = offer.kind == UsbDrive.Kind.EMMC && !ro;
        final StringBuilder message = new StringBuilder();
        if (offer.kind == UsbDrive.Kind.IMAGE) message.append(getString(cdrom
                ? R.string.usb_drive_image_cd : R.string.usb_drive_image_usb)).append("\n\n");
        if (scary) message.append(getString(R.string.usb_drive_emmc_warning)).append("\n\n");
        final String wifi = mUsbMode.getWifiOnOtgPort();
        if (wifi != null) message.append(getString(R.string.warning_wifi_found, wifi)).append("\n\n");
        message.append(getString(R.string.warning_wifi)).append("\n\n")
                .append(getString(R.string.charging_warning)).append("\n\n");
        if (offer.kind == UsbDrive.Kind.IMAGE) {
            message.append(getString(R.string.usb_drive_image_in_use));
            if (cdrom) message.append("\n\n").append(getString(R.string.usb_drive_image_cd_ro));
            else if (!ro) message.append("\n\n").append(getString(R.string.usb_drive_image_writable));
        } else message.append(getString(R.string.usb_drive_apps));
        final AlertDialog dialog = new AlertDialog.Builder(getActivity(),
                android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(scary ? R.string.usb_drive_emmc_title : R.string.usb_drive_confirm)
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(scary ? R.string.usb_drive_sure : R.string.usb_drive_share,
                        (d, which) -> share(offer, ro, cdrom))
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

    private void share(UsbDrive.Offer offer, boolean ro, boolean cdrom) {
        if (mUsbMode.chargingBlocksDevice()) {
            Toast.makeText(getActivity(), R.string.charging_refused, Toast.LENGTH_LONG).show();
            return;
        }
        // Recheck after the dialog: a removed card or changed layout is a new offer.
        UsbDrive.Offer fresh = null;
        final List<UsbDrive.Offer> offers = UsbDriveControl.scan(getActivity());
        offers.addAll(UsbDriveControl.images().offers);
        for (UsbDrive.Offer o : offers) {
            if (o.enabled() && o.identity().equals(offer.identity())) fresh = o;
        }
        if (fresh == null || UsbDriveControl.state().isEmpty() || UsbDriveControl.busy()) {
            Toast.makeText(getActivity(), R.string.usb_drive_failed, Toast.LENGTH_LONG).show();
            return;
        }
        mUsbMode.setConfirmedThisBoot();
        mName = name(fresh);
        UsbDeviceControl.start(getActivity(), mFunctions, fresh, ro, cdrom);
        refresh();
    }

    private String name(UsbDrive.Offer o) {
        if (o.kind == UsbDrive.Kind.IMAGE) return o.label;
        if (o.kind == UsbDrive.Kind.EMMC) return getString(R.string.usb_drive_emmc);
        if (o.kind == UsbDrive.Kind.EASYROMS) return getString(R.string.usb_drive_easyroms, o.slot);
        return o.label.isEmpty() ? getString(R.string.usb_drive_slot, o.slot)
                : getString(R.string.usb_drive_card, o.slot, o.label);
    }

    private String explanation(UsbDrive.Offer o) {
        if (o.kind == UsbDrive.Kind.IMAGE) return getString(o.defaultCdrom()
                ? R.string.usb_drive_image_cd : R.string.usb_drive_image_usb);
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
