package org.andr36oid.wifitransfer;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The console's own IPv4 addresses, best first: Wi-Fi, then Ethernet (USB adapters), then
 * anything else that is up (hotspot, USB tethering). Read straight from the interfaces, so
 * USB dongles and the hotspot count too.
 */
final class Addresses {

    private Addresses() {
    }

    static List<String> find() {
        final List<String> wifi = new ArrayList<>();
        final List<String> eth = new ArrayList<>();
        final List<String> other = new ArrayList<>();
        final List<NetworkInterface> ifaces;
        try {
            final java.util.Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            if (e == null) return new ArrayList<>();
            ifaces = Collections.list(e);
        } catch (SocketException e) {
            return new ArrayList<>();
        }
        for (NetworkInterface iface : ifaces) {
            try {
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) continue;
            } catch (SocketException e) {
                continue;
            }
            final String name = iface.getName();
            if (name.startsWith("dummy") || name.startsWith("tun") || name.startsWith("rmnet")
                    || name.startsWith("p2p")) {
                continue;
            }
            for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()
                        || addr.isLinkLocalAddress()) {
                    continue;
                }
                final String ip = addr.getHostAddress();
                if (name.startsWith("wlan") || name.startsWith("swlan") || name.startsWith("ap")) {
                    wifi.add(ip);
                } else if (name.startsWith("eth") || name.startsWith("usb")
                        || name.startsWith("enx")) {
                    eth.add(ip);
                } else {
                    other.add(ip);
                }
            }
        }
        final List<String> all = new ArrayList<>(wifi);
        all.addAll(eth);
        all.addAll(other);
        return all;
    }

    static String url(String ip, int port) {
        return port == 80 ? "http://" + ip : "http://" + ip + ":" + port;
    }
}
