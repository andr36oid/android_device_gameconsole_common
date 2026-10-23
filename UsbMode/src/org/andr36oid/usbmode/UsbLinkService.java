package org.andr36oid.usbmode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.IBinder;

/** EthernetService owns DHCP; this request keeps a local-only cable network alive. */
public class UsbLinkService extends Service {
    private final Handler mHandler = new Handler();
    private ConnectivityManager mConnectivity;
    private boolean mRequested, mAlive;
    private String mAddress = "", mInterface = "";
    private int mNetId;
    private android.net.INetd netd() {
        return android.net.INetd.Stub.asInterface(android.os.ServiceManager.getService("netd"));
    }
    private void clearRoute() {
        if (!mInterface.isEmpty()) try {
            netd().networkRemoveLegacyRoute(mNetId,mInterface,"192.168.36.1/32","",10000);
        } catch (android.os.RemoteException | RuntimeException e) { }
        mInterface = "";
    }
    private final ConnectivityManager.NetworkCallback mCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
            String address = "";
            for (android.net.LinkAddress link : properties.getLinkAddresses())
                if ("192.168.36.2".equals(link.getAddress().getHostAddress())) address = "192.168.36.2";
            final String found = address;
            mHandler.post(() -> {
                if (!mAlive) return;
                clearRoute(); mAddress = found;
                if (!found.isEmpty()) try {
                    netd().networkAddLegacyRoute(network.netId,properties.getInterfaceName(),"192.168.36.1/32","",10000);
                    mNetId = network.netId; mInterface = properties.getInterfaceName();
                } catch (android.os.RemoteException | RuntimeException e) { }
                show();
            });
        }
        @Override public void onLost(Network network) { mHandler.post(() -> { if (!mAlive) return; clearRoute(); mAddress = ""; show(); }); }
    };
    private boolean peer() {
        final UsbManager manager = getSystemService(UsbManager.class);
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (UsbPeer.matches(device.getVendorId(),device.getProductId(),device.getManufacturerName(),
                    device.getProductName())) return true;
        }
        return false;
    }
    private String peerInterface() {
        try {
            for (java.net.NetworkInterface iface : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                byte[] address = iface.getHardwareAddress();
                if (address != null && java.util.Arrays.equals(address,new byte[] {2,0x36,0,0,0,2})
                        && iface.getName().matches("(eth|usb)[0-9]+")) return iface.getName();
            }
        } catch (java.net.SocketException e) { }
        return "";
    }
    private final Runnable mPoll = new Runnable() {
        @Override public void run() {
            if (!peer()) { stopSelf(); return; }
            show();
            final String iface = peerInterface();
            if (!mRequested && !iface.isEmpty()) {
                mConnectivity.requestNetwork(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(iface).build(),mCallback);
                mRequested = true;
            }
            mHandler.postDelayed(this,1000);
        }
    };
    @Override public void onCreate() {
        super.onCreate(); mAlive = true; mConnectivity = getSystemService(ConnectivityManager.class);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(
                "usb_console_link",getString(R.string.usb_device_link_host),NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        show(); mHandler.removeCallbacks(mPoll); mHandler.post(mPoll); return START_NOT_STICKY;
    }
    private void show() {
        final String status = mAddress.isEmpty() ? getString(R.string.usb_device_link_wait)
                : getString(R.string.usb_device_link_host_status,mAddress);
        startForeground(3,new Notification.Builder(this,"usb_console_link").setSmallIcon(R.drawable.ic_usb_mode)
                .setContentTitle(getString(R.string.usb_device_link_host)).setContentText(status)
                .setStyle(new Notification.BigTextStyle().bigText(status)).setOngoing(true).setShowWhen(false)
                .setContentIntent(PendingIntent.getActivity(this,3,new Intent(this,UsbModeActivity.class),PendingIntent.FLAG_IMMUTABLE)).build());
    }
    @Override public void onDestroy() {
        mAlive = false; mHandler.removeCallbacksAndMessages(null); clearRoute();
        if (mRequested) mConnectivity.unregisterNetworkCallback(mCallback);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    public static class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            context.startForegroundService(new Intent(context,UsbLinkService.class));
        }
    }
}
