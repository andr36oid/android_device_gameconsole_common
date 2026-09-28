package org.andr36oid.wifitransfer;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.HashMap;
import java.util.Map;

/** Black on white QR code, like Settings' Wi-Fi sharing code (external/zxing). */
final class QrCode {

    private QrCode() {
    }

    static Bitmap encode(String text, int size) {
        final Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.MARGIN, 1);
        final BitMatrix bits;
        try {
            bits = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
        } catch (WriterException | IllegalArgumentException e) {
            return null;
        }
        final int w = bits.getWidth();
        final int h = bits.getHeight();
        final int[] pixels = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                pixels[y * w + x] = bits.get(x, y) ? Color.BLACK : Color.WHITE;
            }
        }
        final Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
        return bitmap;
    }
}
