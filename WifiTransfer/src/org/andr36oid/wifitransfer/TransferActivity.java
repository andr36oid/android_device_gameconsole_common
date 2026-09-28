package org.andr36oid.wifitransfer;

import android.app.ActionBar;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.List;

/**
 * The Wi-Fi transfer screen: address, QR code and PIN, what is coming in, and the last
 * transfers. Opening it turns the server on. A turns it off and on, B closes the screen and
 * turns it off. Leaving with Home keeps it on (the notification says so).
 */
public class TransferActivity extends Activity implements TransferService.Listener {

    private static final long REFRESH_MS = 2000;
    private static final int RECENT_SHOWN = 6;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private View mOnPanel;
    private ImageView mQr;
    private TextView mUrl;
    private TextView mPin;
    private TextView mOtherUrls;
    private TextView mStatus;
    private ProgressBar mProgress;
    private TextView mTitle;
    private TextView mMessage;
    private TextView mWarning;
    private TextView mRecentTitle;
    private LinearLayout mRecentList;
    private TextView mKeys;

    private String mQrText;
    /** Key pressed down in this screen; the A that opened it lets go here too, ignore that. */
    private int mDownKey = KeyEvent.KEYCODE_UNKNOWN;

    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            // Networks come and go (USB adapters, Wi-Fi reconnects): look again now and then
            update();
            mHandler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.transfer_activity);
        final ActionBar actionBar = getActionBar();
        if (actionBar != null) actionBar.setDisplayHomeAsUpEnabled(true);

        mOnPanel = findViewById(R.id.on_panel);
        mQr = findViewById(R.id.qr);
        mUrl = findViewById(R.id.url);
        mPin = findViewById(R.id.pin);
        mOtherUrls = findViewById(R.id.other_urls);
        mStatus = findViewById(R.id.status);
        mProgress = findViewById(R.id.progress);
        mTitle = findViewById(R.id.title);
        mMessage = findViewById(R.id.message);
        mWarning = findViewById(R.id.warning);
        mRecentTitle = findViewById(R.id.recent_title);
        mRecentList = findViewById(R.id.recent_list);
        mKeys = findViewById(R.id.keys);

        // Opening the screen turns the server on
        if (savedInstanceState == null && TransferService.running() == null) {
            TransferService.start(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        TransferService.addListener(this);
        mHandler.post(mRefresh);
    }

    @Override
    protected void onPause() {
        TransferService.removeListener(this);
        mHandler.removeCallbacks(mRefresh);
        super.onPause();
    }

    @Override
    public void onTransferStateChanged() {
        update();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            mDownKey = code;
        }
        final boolean up = event.getAction() == KeyEvent.ACTION_UP && mDownKey == code
                && !event.isCanceled();
        if (up) mDownKey = KeyEvent.KEYCODE_UNKNOWN;
        switch (code) {
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (up) toggle();
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
                if (up) close();
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
                if (up) openNetworkSettings();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }

    @Override
    public void onBackPressed() {
        close();
    }

    @Override
    public boolean onNavigateUp() {
        close();
        return true;
    }

    private void toggle() {
        if (TransferService.running() != null) {
            TransferService.stop(this);
        } else {
            TransferService.start(this);
        }
    }

    /** B, Back and the arrow in the action bar: turn the server off and leave. */
    private void close() {
        TransferService.stop(this);
        finish();
    }

    private void openNetworkSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void update() {
        final TransferService service = TransferService.running();
        final boolean on = service != null;
        // The screen stays on while the server runs, so transfers don't stop halfway
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        final List<String> ips = Addresses.find();
        final String url = on && service.port() >= 0 && !ips.isEmpty()
                ? Addresses.url(ips.get(0), service.port()) : null;

        if (url != null) {
            showOn(service, url, ips);
        } else {
            mOnPanel.setVisibility(View.GONE);
            mTitle.setVisibility(View.VISIBLE);
            mMessage.setVisibility(View.VISIBLE);
            if (!on) {
                mTitle.setText(R.string.off_title);
                final int reason = TransferService.stopReason();
                mMessage.setText(reason != 0 ? getString(reason) + "\n\n"
                        + getString(R.string.off_message) : getString(R.string.off_message));
            } else if (service.error() != null) {
                mTitle.setText(R.string.error_title);
                mMessage.setText(getString(R.string.error_message, service.error()));
            } else if (service.port() < 0) {
                mTitle.setText(R.string.starting);
                mMessage.setText("");
            } else {
                mTitle.setText(R.string.no_network_title);
                mMessage.setText(R.string.no_network_message);
            }
        }

        final String warning = on ? service.takeWarning() : null;
        if (warning != null) {
            mWarning.setText(warning);
            mWarning.setVisibility(View.VISIBLE);
        } else if (!on) {
            mWarning.setVisibility(View.GONE);
        }

        showRecent(on ? service.recent() : null);

        mKeys.setText(getString(on ? R.string.keys_on : R.string.keys_off)
                + (on && url == null ? "   " + getString(R.string.keys_network) : ""));
    }

    private void showOn(TransferService service, String url, List<String> ips) {
        mOnPanel.setVisibility(View.VISIBLE);
        mTitle.setVisibility(View.GONE);
        mMessage.setVisibility(View.GONE);

        // Scanning the code signs in right away: the PIN rides along after the #, which the
        // browser never sends over the network
        final String qrText = url + "/#pin=" + service.pin();
        if (!qrText.equals(mQrText)) {
            final int size = getResources().getDimensionPixelSize(R.dimen.qr_size);
            final Bitmap bitmap = QrCode.encode(qrText, size);
            mQr.setImageBitmap(bitmap);
            mQrText = bitmap != null ? qrText : null;
        }
        mUrl.setText(url);
        mPin.setText(service.pin());

        if (ips.size() > 1) {
            final StringBuilder sb = new StringBuilder();
            for (int i = 1; i < ips.size(); i++) {
                if (sb.length() > 0) sb.append("  ");
                sb.append(Addresses.url(ips.get(i), service.port()));
            }
            mOtherUrls.setText(getString(R.string.other_urls, sb));
            mOtherUrls.setVisibility(View.VISIBLE);
        } else {
            mOtherUrls.setVisibility(View.GONE);
        }

        final String current = service.currentName();
        if (current != null) {
            final long total = service.currentTotal();
            final long done = service.currentDone();
            final int percent = total > 0 ? (int) (done * 100 / total) : 0;
            mStatus.setText(getString(R.string.status_receiving, current, percent,
                    TransferService.formatBytes(this, done),
                    TransferService.formatBytes(this, total),
                    TransferService.formatBytes(this, service.currentSpeed())));
            mProgress.setVisibility(View.VISIBLE);
            mProgress.setProgress(percent);
        } else {
            mProgress.setVisibility(View.GONE);
            if (service.received() > 0) {
                mStatus.setText(getResources().getQuantityString(R.plurals.status_received,
                        service.received(), service.received(),
                        TransferService.formatBytes(this, service.receivedBytes())));
            } else if (service.clients() > 0) {
                mStatus.setText(R.string.status_connected);
            } else {
                mStatus.setText(R.string.status_waiting);
            }
        }
    }

    private void showRecent(List<TransferService.Transfer> recent) {
        mRecentList.removeAllViews();
        if (recent == null || recent.isEmpty()) {
            mRecentTitle.setVisibility(View.GONE);
            return;
        }
        mRecentTitle.setVisibility(View.VISIBLE);
        final LayoutInflater inflater = getLayoutInflater();
        for (int i = 0; i < recent.size() && i < RECENT_SHOWN; i++) {
            final TransferService.Transfer t = recent.get(i);
            final View row = inflater.inflate(R.layout.transfer_row, mRecentList, false);
            ((TextView) row.findViewById(android.R.id.title)).setText(t.name);
            final String time = DateFormat.getTimeFormat(this).format(t.time);
            ((TextView) row.findViewById(android.R.id.summary)).setText(t.error != null
                    ? getString(R.string.recent_failed, t.error, time)
                    : getString(R.string.recent_done,
                            TransferService.formatBytes(this, t.bytes), t.where, time));
            ((ImageView) row.findViewById(android.R.id.icon)).setImageResource(
                    t.error != null ? R.drawable.ic_failed : R.drawable.ic_done);
            mRecentList.addView(row);
        }
    }
}
