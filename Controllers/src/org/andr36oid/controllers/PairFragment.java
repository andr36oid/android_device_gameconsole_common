package org.andr36oid.controllers;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidHost;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.SystemClock;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;

import java.util.HashMap;
import java.util.Map;

/**
 * Finds controllers in pairing mode and pairs the one picked with A. Android's own pairing
 * question (Settings' BluetoothPairingDialog) shows up on top because a search ran in the last
 * minute; after pairing the pad is connected as an input device.
 */
public class PairFragment extends PreferenceFragment {

    /** Searching stops by itself after this long; A on "Search again" restarts it. */
    private static final long SEARCH_MS = 3 * 60_000;

    private BluetoothAdapter mAdapter;
    private BluetoothHidHost mHid;
    private PreferenceCategory mFound;
    private SwitchPreference mShowAll;
    private Preference mStatus;
    private final Map<String, BluetoothDevice> mDevices = new HashMap<>();
    private final Map<String, Preference> mRows = new HashMap<>();
    private BluetoothDevice mPairing;
    private boolean mConnectWhenReady;
    private long mSearchStart;

    private final BluetoothProfile.ServiceListener mHidListener =
            new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    mHid = (BluetoothHidHost) proxy;
                    if (mConnectWhenReady && mPairing != null) {
                        mConnectWhenReady = false;
                        mHid.connect(mPairing);
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    mHid = null;
                }
            };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                if (mAdapter.getState() == BluetoothAdapter.STATE_ON) startSearch();
            } else if (BluetoothDevice.ACTION_FOUND.equals(action)
                    || BluetoothDevice.ACTION_NAME_CHANGED.equals(action)
                    || BluetoothDevice.ACTION_CLASS_CHANGED.equals(action)) {
                if (device != null) {
                    mDevices.put(device.getAddress(), device);
                    showDevices();
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                // keep searching while the page is open, up to SEARCH_MS
                if (mPairing == null
                        && SystemClock.elapsedRealtime() - mSearchStart < SEARCH_MS) {
                    mAdapter.startDiscovery();
                } else {
                    showStatus(true);
                }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                onBondChanged(device, intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                        BluetoothDevice.BOND_NONE), intent.getIntExtra(
                        BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE));
            } else if (BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                if (device != null && mPairing != null
                        && device.getAddress().equals(mPairing.getAddress())
                        && intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                                == BluetoothProfile.STATE_CONNECTED) {
                    setRowSummary(device, R.string.pair_connected);
                    mPairing = null;
                }
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.pair_settings);
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mFound = (PreferenceCategory) findPreference("found");
        mShowAll = (SwitchPreference) findPreference("show_all");
        mShowAll.setOnPreferenceChangeListener((p, v) -> {
            mShowAll.setChecked((Boolean) v);
            showDevices();
            return false;
        });
        mStatus = new Preference(getContext());
        mStatus.setOnPreferenceClickListener(p -> {
            startSearch();
            return true;
        });
        if (mAdapter != null) {
            mAdapter.getProfileProxy(getContext(), mHidListener, BluetoothProfile.HID_HOST);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mAdapter == null) {
            getActivity().finish();
            return;
        }
        final IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_CLASS_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothHidHost.ACTION_CONNECTION_STATE_CHANGED);
        getContext().registerReceiver(mReceiver, filter);
        if (mAdapter.isEnabled()) {
            startSearch();
        } else {
            mAdapter.enable();
            showStatus(false);
        }
    }

    @Override
    public void onPause() {
        getContext().unregisterReceiver(mReceiver);
        if (mAdapter != null) mAdapter.cancelDiscovery();
        super.onPause();
    }

    @Override
    public void onDestroy() {
        if (mAdapter != null && mHid != null) {
            mAdapter.closeProfileProxy(BluetoothProfile.HID_HOST, mHid);
        }
        super.onDestroy();
    }

    private void startSearch() {
        mSearchStart = SystemClock.elapsedRealtime();
        if (!mAdapter.isDiscovering()) mAdapter.startDiscovery();
        showStatus(false);
    }

    /** The last row of the list: "Searching…", or "Search again" once it has stopped. */
    private void showStatus(boolean stopped) {
        mStatus.setSelectable(stopped);
        mStatus.setTitle(stopped ? R.string.pair_search_again : R.string.pair_searching);
        mStatus.setSummary(stopped ? getString(R.string.pair_search_again_summary) : null);
        mFound.removePreference(mStatus);
        mStatus.setOrder(Integer.MAX_VALUE);
        mFound.addPreference(mStatus);
    }

    private void showDevices() {
        if (getActivity() == null) return;
        final boolean all = mShowAll.isChecked();
        for (BluetoothDevice device : mDevices.values()) {
            final String address = device.getAddress();
            final boolean pairing = mPairing != null && address.equals(mPairing.getAddress());
            final boolean show = pairing || (device.getBondState() != BluetoothDevice.BOND_BONDED
                    && (all || BtPads.isController(device)));
            Preference row = mRows.get(address);
            if (!show) {
                if (row != null) {
                    mFound.removePreference(row);
                    mRows.remove(address);
                }
                continue;
            }
            if (row == null) {
                row = new Preference(getContext());
                row.setSummary(R.string.pair_device_summary);
                row.setOnPreferenceClickListener(p -> {
                    pair(device);
                    return true;
                });
                mRows.put(address, row);
                mFound.addPreference(row);
            }
            final String name = device.getAlias();
            row.setTitle(name != null && !name.isEmpty() ? name
                    : getString(R.string.pair_no_name, address));
        }
    }

    private void pair(BluetoothDevice device) {
        mAdapter.cancelDiscovery();
        mPairing = device;
        setRowSummary(device, R.string.pair_pairing);
        if (device.getBondState() == BluetoothDevice.BOND_BONDED) {
            connect(device);
        } else if (!device.createBond()) {
            setRowSummary(device, R.string.pair_failed);
            mPairing = null;
        }
    }

    private void onBondChanged(BluetoothDevice device, int state, int previous) {
        if (device == null || mPairing == null
                || !device.getAddress().equals(mPairing.getAddress())) {
            return;
        }
        if (state == BluetoothDevice.BOND_BONDED) {
            connect(device);
        } else if (state == BluetoothDevice.BOND_NONE
                && previous == BluetoothDevice.BOND_BONDING) {
            setRowSummary(device, R.string.pair_failed);
            mPairing = null;
            // a pad that timed out has to go into pairing mode again: look for it again
            startSearch();
        }
    }

    private void connect(BluetoothDevice device) {
        setRowSummary(device, R.string.pair_connecting);
        if (mHid != null) {
            mHid.connect(device);
        } else {
            mConnectWhenReady = true;
        }
    }

    private void setRowSummary(BluetoothDevice device, int text) {
        final Preference row = mRows.get(device.getAddress());
        if (row != null) row.setSummary(text);
    }
}
