package org.andr36oid.controllers;

import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidHost;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.input.InputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

public class ControllersFragment extends PreferenceFragment
        implements Preference.OnPreferenceChangeListener, InputManager.InputDeviceListener {

    /** Read by gameconsole-emu (retroarch_players); builtin or external. */
    static final String PROP_PLAYER1 = "persist.andr36oid.ra_player1";
    static final String BUILTIN = "builtin";
    static final String EXTERNAL = "external";

    private static final long REFRESH_MS = 15_000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            refreshPads();
            refreshBluetooth();
            mHandler.postDelayed(this, REFRESH_MS);
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshBluetooth();
        }
    };

    private final BluetoothProfile.ServiceListener mHidListener =
            new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    mHid = (BluetoothHidHost) proxy;
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    mHid = null;
                }
            };

    private InputManager mInputManager;
    private BluetoothAdapter mAdapter;
    private BluetoothHidHost mHid;
    private PreferenceCategory mConnected;
    private PreferenceCategory mBluetooth;
    private ListPreference mPlayer1;
    private Preference mPair;
    private final List<Preference> mPaired = new ArrayList<>();
    private AlertDialog mDialog;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.controllers_settings);
        mInputManager = getContext().getSystemService(InputManager.class);
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mConnected = (PreferenceCategory) findPreference("connected");
        mBluetooth = (PreferenceCategory) findPreference("bluetooth");
        mPair = findPreference("pair");
        mPlayer1 = (ListPreference) findPreference("player1");
        mPlayer1.setValue(player1());
        mPlayer1.setOnPreferenceChangeListener(this);
        updatePlayer1Summary(player1());
        mPair.setOnPreferenceClickListener(p -> {
            onPairClicked();
            return true;
        });
        if (mAdapter != null) {
            mAdapter.getProfileProxy(getContext(), mHidListener, BluetoothProfile.HID_HOST);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        mInputManager.registerInputDeviceListener(this, mHandler);
        final IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED);
        getContext().registerReceiver(mReceiver, filter);
        mHandler.post(mRefresh);
    }

    @Override
    public void onPause() {
        mHandler.removeCallbacks(mRefresh);
        mInputManager.unregisterInputDeviceListener(this);
        getContext().unregisterReceiver(mReceiver);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        if (mDialog != null) mDialog.dismiss();
        if (mAdapter != null && mHid != null) {
            mAdapter.closeProfileProxy(BluetoothProfile.HID_HOST, mHid);
        }
        super.onDestroy();
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        refreshPads();
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        refreshPads();
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        refreshPads();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        final String value = (String) newValue;
        // gameconsole-ra-players writes it into retroarch.cfg once RetroArch is closed
        SystemProperties.set(PROP_PLAYER1, value);
        updatePlayer1Summary(value);
        refreshPads();
        return true;
    }

    private static String player1() {
        return EXTERNAL.equals(SystemProperties.get(PROP_PLAYER1)) ? EXTERNAL : BUILTIN;
    }

    private void updatePlayer1Summary(String value) {
        mPlayer1.setSummary(getString(EXTERNAL.equals(value)
                ? R.string.player1_external_summary : R.string.player1_builtin_summary)
                + "\n" + getString(R.string.player1_later));
    }

    private void refreshPads() {
        if (getActivity() == null) return;
        mConnected.removeAll();
        final boolean externalFirst = EXTERNAL.equals(player1());
        final List<Pads.Pad> pads = Pads.list(getContext());
        boolean external = false;
        for (Pads.Pad pad : pads) {
            final Preference pref = new Preference(getContext());
            pref.setSelectable(false);
            pref.setTitle(pad.name);
            pref.setSummary(describe(pad, externalFirst));
            mConnected.addPreference(pref);
            external |= !pad.isBuiltIn();
        }
        if (!external) {
            final Preference none = new Preference(getContext());
            none.setSelectable(false);
            none.setSummary(R.string.connected_none);
            mConnected.addPreference(none);
        }
    }

    private String describe(Pads.Pad pad, boolean externalFirst) {
        final List<String> parts = new ArrayList<>();
        switch (pad.bus) {
            case Pads.BUS_BUILTIN:
                parts.add(getString(R.string.bus_builtin));
                break;
            case Pads.BUS_USB:
                parts.add(getString(R.string.bus_usb));
                break;
            case Pads.BUS_BLUETOOTH:
                parts.add(getString(R.string.bus_bluetooth));
                break;
            default:
                parts.add(getString(R.string.bus_other));
                break;
        }
        if (!pad.isBuiltIn()) {
            String battery;
            if (pad.batteryPercent >= 0) {
                battery = getString(R.string.battery_percent, pad.batteryPercent);
            } else if (pad.batteryLevel != null) {
                battery = getString(R.string.battery_level, pad.batteryLevel.toLowerCase());
            } else {
                battery = pad.hasBattery ? null : getString(R.string.battery_none);
            }
            if (battery != null && pad.charging) {
                battery += ", " + getString(R.string.battery_charging);
            }
            if (battery != null) parts.add(battery);
        }
        if (pad.isBuiltIn()) {
            parts.add(getString(externalFirst ? R.string.player_builtin_2 : R.string.player_builtin_1));
        } else {
            parts.add(getString(externalFirst ? R.string.player_external_1 : R.string.player_external_2));
        }
        return TextUtils.join(getString(R.string.summary_separator), parts);
    }

    private void onPairClicked() {
        if (mAdapter == null) return;
        if (!mAdapter.isEnabled()) {
            mAdapter.enable();
            mPair.setSummary(R.string.bluetooth_turning_on);
            return;
        }
        startActivity(new Intent(getContext(), PairActivity.class));
    }

    private void refreshBluetooth() {
        if (getActivity() == null) return;
        for (Preference pref : mPaired) mBluetooth.removePreference(pref);
        mPaired.clear();
        if (mAdapter == null) {
            mPair.setEnabled(false);
            mPair.setTitle(R.string.bluetooth_missing);
            mPair.setSummary(R.string.bluetooth_missing_summary);
            return;
        }
        final int state = mAdapter.getState();
        if (state != BluetoothAdapter.STATE_ON) {
            mPair.setTitle(R.string.bluetooth_off);
            mPair.setSummary(state == BluetoothAdapter.STATE_TURNING_ON
                    ? R.string.bluetooth_turning_on : R.string.bluetooth_off_summary);
            return;
        }
        mPair.setTitle(R.string.pair_title);
        mPair.setSummary(R.string.pair_summary);
        for (BluetoothDevice device : mAdapter.getBondedDevices()) {
            if (!BtPads.isController(device)) continue;
            final Preference pref = new Preference(getContext());
            pref.setTitle(BtPads.label(device));
            final boolean connected = device.isConnected();
            pref.setSummary(connected ? R.string.paired_connected : R.string.paired_not_connected);
            pref.setOnPreferenceClickListener(p -> {
                showPairedDialog(device);
                return true;
            });
            mPaired.add(pref);
            mBluetooth.addPreference(pref);
        }
    }

    private void showPairedDialog(BluetoothDevice device) {
        if (mDialog != null) mDialog.dismiss();
        mDialog = new AlertDialog.Builder(getContext())
                .setTitle(BtPads.label(device))
                .setPositiveButton(R.string.paired_dialog_connect, (d, w) -> {
                    if (mHid != null) mHid.connect(device);
                })
                .setNeutralButton(R.string.paired_dialog_forget, (d, w) -> confirmForget(device))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        mDialog.show();
    }

    private void confirmForget(BluetoothDevice device) {
        mDialog = new AlertDialog.Builder(getContext())
                .setTitle(getString(R.string.paired_forget_title, BtPads.label(device)))
                .setMessage(R.string.paired_forget_message)
                .setPositiveButton(R.string.paired_dialog_forget, (d, w) -> {
                    device.removeBond();
                    refreshBluetooth();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        mDialog.show();
        // B backs out; the safe choice has the focus
        mDialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus();
    }
}
