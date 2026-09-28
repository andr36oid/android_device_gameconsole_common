package org.andr36oid.ethernet;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.EthernetManager;
import android.net.InetAddresses;
import android.net.IpConfiguration;
import android.net.IpConfiguration.IpAssignment;
import android.net.IpConfiguration.ProxySettings;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.StaticIpConfiguration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One category per wired interface EthernetService tracks (eth* adapters,
 * usb* phones), with its state and a DHCP or static IP choice.
 */
public class EthernetFragment extends PreferenceFragment {
    private static final String TAG = "EthernetSettings";

    private static final String MODE_DHCP = "dhcp";
    private static final String MODE_STATIC = "static";
    private static final int DEFAULT_PREFIX_LENGTH = 24;
    private static final long REFRESH_DELAY_MS = 200;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    // Interfaces switched to static that don't have an address yet
    private final Set<String> mStaticPending = new HashSet<>();
    private final Runnable mRefresh = this::refresh;

    private EthernetManager mEthernet;
    private ConnectivityManager mConnectivity;
    private boolean mListening;
    // What the screen shows, to only rebuild it when something changed
    private String mShownState;

    private final EthernetManager.Listener mEthernetListener =
            (iface, isAvailable) -> scheduleRefresh();

    private final ConnectivityManager.NetworkCallback mNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    scheduleRefresh();
                }

                @Override
                public void onLost(Network network) {
                    scheduleRefresh();
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities nc) {
                    scheduleRefresh();
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                    scheduleRefresh();
                }
            };

    private static final class IfaceInfo {
        String iface;
        LinkProperties lp; // null while there is no network on it
        boolean validated;
        boolean carrier;
        IpConfiguration config;
        String mac;
        int speed;

        String describe() {
            return iface + '|' + lp + '|' + validated + '|' + carrier + '|' + config + '|'
                    + mac + '|' + speed;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.ethernet_settings);
        final Context context = getActivity();
        // null when EthernetService isn't running
        mEthernet = (EthernetManager) context.getSystemService(Context.ETHERNET_SERVICE);
        mConnectivity = context.getSystemService(ConnectivityManager.class);
    }

    @Override
    public void onResume() {
        super.onResume();
        mListening = true;
        if (mEthernet != null) {
            mEthernet.addListener(mEthernetListener);
        }
        final NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build();
        mConnectivity.registerNetworkCallback(request, mNetworkCallback);
        mShownState = null;
        refresh();
    }

    @Override
    public void onPause() {
        super.onPause();
        mListening = false;
        mHandler.removeCallbacks(mRefresh);
        if (mEthernet != null) {
            mEthernet.removeListener(mEthernetListener);
        }
        try {
            mConnectivity.unregisterNetworkCallback(mNetworkCallback);
        } catch (IllegalArgumentException e) {
            // was not registered
        }
    }

    private void scheduleRefresh() {
        mHandler.removeCallbacks(mRefresh);
        mHandler.postDelayed(mRefresh, REFRESH_DELAY_MS);
    }

    private void refresh() {
        if (!mListening || getActivity() == null) {
            return;
        }
        final PreferenceScreen screen = getPreferenceScreen();
        if (mEthernet == null) {
            if (mShownState == null) {
                mShownState = "unavailable";
                screen.removeAll();
                addMessage(screen, R.string.ethernet_unavailable, 0);
            }
            return;
        }

        String[] ifaces;
        try {
            ifaces = mEthernet.getAvailableInterfaces();
        } catch (RuntimeException e) {
            Log.e(TAG, "Couldn't list interfaces", e);
            ifaces = new String[0];
        }
        Arrays.sort(ifaces);
        final List<IfaceInfo> infos = new ArrayList<>();
        final StringBuilder state = new StringBuilder();
        for (String iface : ifaces) {
            final IfaceInfo info = readInfo(iface);
            infos.add(info);
            state.append(info.describe()).append('\n');
        }
        state.append(mStaticPending);
        final String newState = state.toString();
        if (newState.equals(mShownState)) {
            return;
        }
        mShownState = newState;

        screen.removeAll();
        if (infos.isEmpty()) {
            addMessage(screen, R.string.ethernet_none_title, R.string.ethernet_none_summary);
            return;
        }
        for (IfaceInfo info : infos) {
            addInterface(screen, info);
        }
    }

    private IfaceInfo readInfo(String iface) {
        final IfaceInfo info = new IfaceInfo();
        info.iface = iface;
        for (Network network : mConnectivity.getAllNetworks()) {
            final LinkProperties lp = mConnectivity.getLinkProperties(network);
            if (lp == null || !iface.equals(lp.getInterfaceName())) {
                continue;
            }
            info.lp = lp;
            final NetworkCapabilities nc = mConnectivity.getNetworkCapabilities(network);
            info.validated = nc != null
                    && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            break;
        }
        info.carrier = "1".equals(readSysfs(iface, "carrier"));
        info.mac = readSysfs(iface, "address");
        try {
            info.speed = Integer.parseInt(readSysfs(iface, "speed"));
        } catch (NumberFormatException e) {
            info.speed = -1; // USB tethering has no link speed
        }
        try {
            info.config = mEthernet.getConfiguration(iface);
        } catch (RuntimeException e) {
            Log.e(TAG, "Couldn't read the IP configuration of " + iface, e);
        }
        return info;
    }

    private static String readSysfs(String iface, String name) {
        try (BufferedReader reader = new BufferedReader(
                new FileReader("/sys/class/net/" + iface + "/" + name))) {
            final String line = reader.readLine();
            return line != null ? line.trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private void addMessage(PreferenceScreen screen, int title, int summary) {
        final Preference pref = new Preference(getActivity());
        pref.setTitle(title);
        if (summary != 0) {
            pref.setSummary(summary);
        }
        pref.setSelectable(false);
        screen.addPreference(pref);
    }

    private void addInfo(PreferenceCategory category, int title, String summary) {
        final Preference pref = new Preference(getActivity());
        pref.setTitle(title);
        pref.setSummary(summary);
        pref.setSelectable(false);
        category.addPreference(pref);
    }

    private void addInterface(PreferenceScreen screen, IfaceInfo info) {
        final Context context = getActivity();
        final String iface = info.iface;

        final PreferenceCategory category = new PreferenceCategory(context);
        category.setKey("iface_" + iface);
        category.setTitle(getString(iface.startsWith("usb") ? R.string.ethernet_iface_phone
                : iface.startsWith("wwan") ? R.string.ethernet_iface_modem
                : R.string.ethernet_iface_adapter, iface));
        screen.addPreference(category);

        addInfo(category, R.string.ethernet_status, statusText(info));
        addInfo(category, R.string.ethernet_ip_address, joinOrUnknown(addresses(info)));
        addInfo(category, R.string.ethernet_gateway, joinOrUnknown(gateways(info)));
        addInfo(category, R.string.ethernet_dns, joinOrUnknown(dnsServers(info)));
        addInfo(category, R.string.ethernet_mac, TextUtils.isEmpty(info.mac)
                ? getString(R.string.ethernet_unknown) : info.mac);
        if (info.speed > 0) {
            addInfo(category, R.string.ethernet_speed,
                    getString(R.string.ethernet_speed_value, info.speed));
        }

        final boolean isStatic = isStatic(info.config) || mStaticPending.contains(iface);
        final ListPreference mode = new ListPreference(context);
        mode.setPersistent(false);
        mode.setKey("mode_" + iface);
        mode.setTitle(R.string.ethernet_ip_settings);
        mode.setDialogTitle(R.string.ethernet_ip_settings);
        mode.setEntries(new CharSequence[] {
                getString(R.string.ethernet_ip_dhcp), getString(R.string.ethernet_ip_static) });
        mode.setEntryValues(new CharSequence[] { MODE_DHCP, MODE_STATIC });
        mode.setValue(isStatic ? MODE_STATIC : MODE_DHCP);
        mode.setSummary("%s");
        mode.setOnPreferenceChangeListener((pref, value) -> {
            onModeChanged(info, MODE_STATIC.equals(value));
            return true;
        });
        category.addPreference(mode);

        if (!isStatic) {
            return;
        }
        final StaticIpConfiguration current = isStatic(info.config)
                ? info.config.getStaticIpConfiguration() : null;
        final LinkAddress currentIp = current != null ? current.getIpAddress() : null;

        final EditTextPreference ip = addEdit(category, "ip_" + iface,
                R.string.ethernet_static_ip, R.string.ethernet_static_ip_hint,
                currentIp != null ? currentIp.toString() : "");
        ip.setOnPreferenceChangeListener(
                (pref, value) -> onStaticIpChanged(iface, (String) value));

        final InetAddress currentGateway = current != null ? current.getGateway() : null;
        final EditTextPreference gateway = addEdit(category, "gateway_" + iface,
                R.string.ethernet_static_gateway, R.string.ethernet_static_gateway_hint,
                currentGateway != null ? currentGateway.getHostAddress() : "");
        gateway.setOnPreferenceChangeListener(
                (pref, value) -> onStaticGatewayChanged(iface, (String) value));

        final List<String> currentDns = new ArrayList<>();
        if (current != null) {
            for (InetAddress dns : current.getDnsServers()) {
                currentDns.add(dns.getHostAddress());
            }
        }
        final EditTextPreference dns = addEdit(category, "dns_" + iface,
                R.string.ethernet_static_dns, R.string.ethernet_static_dns_hint,
                TextUtils.join(", ", currentDns));
        dns.setOnPreferenceChangeListener(
                (pref, value) -> onStaticDnsChanged(iface, (String) value));

        if (currentIp == null) {
            gateway.setEnabled(false);
            gateway.setSummary(R.string.ethernet_static_needs_ip);
            dns.setEnabled(false);
            dns.setSummary(R.string.ethernet_static_needs_ip);
        }
    }

    private EditTextPreference addEdit(PreferenceCategory category, String key, int title,
            int hint, String value) {
        final EditTextPreference pref = new EditTextPreference(getActivity());
        pref.setPersistent(false);
        pref.setKey(key);
        pref.setTitle(title);
        pref.setDialogTitle(title);
        pref.setDialogMessage(hint);
        pref.setText(value);
        pref.setSummary(TextUtils.isEmpty(value) ? getString(R.string.ethernet_not_set) : value);
        pref.getEditText().setInputType(
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        pref.getEditText().setSingleLine(true);
        category.addPreference(pref);
        return pref;
    }

    private String statusText(IfaceInfo info) {
        if (info.lp != null) {
            return getString(info.validated
                    ? R.string.ethernet_status_connected : R.string.ethernet_status_no_internet);
        }
        if (info.carrier) {
            return getString(R.string.ethernet_status_connecting);
        }
        return getString(info.iface.startsWith("usb") ? R.string.ethernet_status_phone_waiting
                : info.iface.startsWith("wwan") ? R.string.ethernet_status_modem_waiting
                : R.string.ethernet_status_unplugged);
    }

    private String joinOrUnknown(List<String> items) {
        return items.isEmpty() ? getString(R.string.ethernet_unknown) : TextUtils.join("\n", items);
    }

    /** IPv4 addresses first. */
    private static List<String> addresses(IfaceInfo info) {
        final List<String> v4 = new ArrayList<>();
        final List<String> v6 = new ArrayList<>();
        if (info.lp != null) {
            for (LinkAddress address : info.lp.getLinkAddresses()) {
                (address.getAddress() instanceof Inet4Address ? v4 : v6).add(address.toString());
            }
        }
        v4.addAll(v6);
        return v4;
    }

    private static List<String> gateways(IfaceInfo info) {
        final List<String> result = new ArrayList<>();
        if (info.lp != null) {
            for (RouteInfo route : info.lp.getRoutes()) {
                if (route.isDefaultRoute() && route.hasGateway()) {
                    result.add(route.getGateway().getHostAddress());
                }
            }
        }
        return result;
    }

    private static List<String> dnsServers(IfaceInfo info) {
        final List<String> result = new ArrayList<>();
        if (info.lp != null) {
            for (InetAddress dns : info.lp.getDnsServers()) {
                result.add(dns.getHostAddress());
            }
        }
        return result;
    }

    private static boolean isStatic(IpConfiguration config) {
        return config != null && config.getIpAssignment() == IpAssignment.STATIC
                && config.getStaticIpConfiguration() != null;
    }

    /** The static configuration EthernetService has now, or null. */
    private StaticIpConfiguration currentStatic(String iface) {
        try {
            final IpConfiguration config = mEthernet.getConfiguration(iface);
            return isStatic(config) ? config.getStaticIpConfiguration() : null;
        } catch (RuntimeException e) {
            Log.e(TAG, "Couldn't read the IP configuration of " + iface, e);
            return null;
        }
    }

    private void onModeChanged(IfaceInfo info, boolean toStatic) {
        final String iface = info.iface;
        if (!toStatic) {
            mStaticPending.remove(iface);
            final IpConfiguration config = new IpConfiguration();
            config.setIpAssignment(IpAssignment.DHCP);
            config.setProxySettings(ProxySettings.NONE);
            setConfiguration(iface, config);
        } else if (!isStatic(info.config)) {
            // Start from what DHCP handed out, so the address stays the same.
            LinkAddress address = null;
            if (info.lp != null) {
                for (LinkAddress candidate : info.lp.getLinkAddresses()) {
                    if (candidate.getAddress() instanceof Inet4Address) {
                        address = candidate;
                        break;
                    }
                }
            }
            if (address != null) {
                InetAddress gateway = null;
                for (RouteInfo route : info.lp.getRoutes()) {
                    if (route.isDefaultRoute() && route.hasGateway()
                            && route.getGateway() instanceof Inet4Address) {
                        gateway = route.getGateway();
                        break;
                    }
                }
                applyStatic(iface, address, gateway, info.lp.getDnsServers());
            } else {
                mStaticPending.add(iface);
            }
        }
        mShownState = null;
        scheduleRefresh();
    }

    private boolean onStaticIpChanged(String iface, String text) {
        final LinkAddress address = parseIpv4Address(text);
        if (address == null) {
            toast(R.string.ethernet_invalid_ip);
            return false;
        }
        final StaticIpConfiguration current = currentStatic(iface);
        applyStatic(iface, address, current != null ? current.getGateway() : null,
                current != null ? current.getDnsServers() : new ArrayList<InetAddress>());
        mStaticPending.remove(iface);
        mShownState = null;
        scheduleRefresh();
        return true;
    }

    private boolean onStaticGatewayChanged(String iface, String text) {
        final StaticIpConfiguration current = currentStatic(iface);
        if (current == null || current.getIpAddress() == null) {
            toast(R.string.ethernet_static_needs_ip);
            return false;
        }
        InetAddress gateway = null;
        if (!TextUtils.isEmpty(text.trim())) {
            gateway = parseAddress(text.trim());
            if (!(gateway instanceof Inet4Address)) {
                toast(R.string.ethernet_invalid_gateway);
                return false;
            }
        }
        applyStatic(iface, current.getIpAddress(), gateway, current.getDnsServers());
        mShownState = null;
        scheduleRefresh();
        return true;
    }

    private boolean onStaticDnsChanged(String iface, String text) {
        final StaticIpConfiguration current = currentStatic(iface);
        if (current == null || current.getIpAddress() == null) {
            toast(R.string.ethernet_static_needs_ip);
            return false;
        }
        final List<InetAddress> servers = new ArrayList<>();
        for (String part : text.trim().split("[,;\\s]+")) {
            if (part.isEmpty()) {
                continue;
            }
            final InetAddress server = parseAddress(part);
            if (server == null) {
                toast(R.string.ethernet_invalid_dns);
                return false;
            }
            servers.add(server);
        }
        applyStatic(iface, current.getIpAddress(), current.getGateway(), servers);
        mShownState = null;
        scheduleRefresh();
        return true;
    }

    private static InetAddress parseAddress(String text) {
        try {
            return InetAddresses.parseNumericAddress(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** "192.168.1.50" or "192.168.1.50/24", null if invalid. */
    private static LinkAddress parseIpv4Address(String text) {
        final String trimmed = text.trim();
        final int slash = trimmed.indexOf('/');
        final String host = slash < 0 ? trimmed : trimmed.substring(0, slash);
        int prefixLength = DEFAULT_PREFIX_LENGTH;
        if (slash >= 0) {
            try {
                prefixLength = Integer.parseInt(trimmed.substring(slash + 1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (prefixLength < 1 || prefixLength > 32) {
            return null;
        }
        final InetAddress address = parseAddress(host);
        if (!(address instanceof Inet4Address)) {
            return null;
        }
        try {
            return new LinkAddress(address.getHostAddress() + "/" + prefixLength);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void applyStatic(String iface, LinkAddress address, InetAddress gateway,
            List<InetAddress> dnsServers) {
        final StaticIpConfiguration staticConfig = new StaticIpConfiguration.Builder()
                .setIpAddress(address)
                .setGateway(gateway)
                .setDnsServers(dnsServers)
                .build();
        final IpConfiguration config = new IpConfiguration();
        config.setIpAssignment(IpAssignment.STATIC);
        config.setProxySettings(ProxySettings.NONE);
        config.setStaticIpConfiguration(staticConfig);
        setConfiguration(iface, config);
    }

    private void setConfiguration(String iface, IpConfiguration config) {
        try {
            mEthernet.setConfiguration(iface, config);
        } catch (RuntimeException e) {
            Log.e(TAG, "Couldn't set the IP configuration of " + iface, e);
            toast(R.string.ethernet_save_failed);
        }
    }

    private void toast(int text) {
        Toast.makeText(getActivity(), text, Toast.LENGTH_LONG).show();
    }
}
