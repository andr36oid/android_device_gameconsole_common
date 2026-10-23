package org.andr36oid.usbmode;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemProperties;
import android.view.View;

final class FunctionView extends View {
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean mMidi;
    FunctionView(Context context, boolean midi) { super(context); mMidi = midi; }
    private void text(Canvas c, String text, float x, float y, float size, int color) {
        mPaint.setColor(color); mPaint.setTextSize(size); c.drawText(text,x,y,mPaint);
    }
    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xff15181d); canvas.save();
        final float scale = Math.min(getWidth() / 640f,getHeight() / 260f);
        canvas.translate((getWidth() - 640 * scale) / 2,(getHeight() - 260 * scale) / 2); canvas.scale(scale,scale);
        int buttons = SystemProperties.getInt(UsbDeviceControl.PREFIX + "pads",0);
        final String[] names = getContext().getString(mMidi ? R.string.usb_device_midi_keys
                : R.string.usb_device_remote_keys).split("\\|");
        final int[] bits = mMidi ? new int[] {0,3,1,2,12,11,14,13} : new int[] {12,2,3,0,1,14,8,9,13};
        final String[] labels = mMidi ? new String[] {"↑","→","↓","←","A","B","X","Y"}
                : new String[] {"A","←","→","↑","↓","X","L1","R1","Y"};
        final int columns = mMidi ? 4 : 3;
        for (int i = 0; i < names.length; i++) {
            float width = 624f / columns, x = 8 + (i % columns) * width, y = 30 + (i / columns) * (mMidi ? 72 : 48);
            boolean down = (buttons & (1 << bits[i])) != 0;
            mPaint.setColor(down ? 0xff80cbc4 : 0xff292e36);
            canvas.drawRoundRect(x,y,x + width - 6,y + (mMidi ? 64 : 42),5,5,mPaint);
            text(canvas,labels[i] + " · " + names[i],x + 8,y + 26,mMidi ? 15 : 14,down ? 0xff101619 : 0xffeeeeee);
        }
        if (mMidi) text(canvas,getContext().getString(R.string.usb_device_midi_status,
                SystemProperties.getInt(UsbDeviceControl.PREFIX + "octave",0)),8,19,14,0xff80cbc4);
        String[] legend = getContext().getString(mMidi ? R.string.usb_device_midi_legend : R.string.usb_device_remote_legend).split("\n");
        for (int i = 0; i < legend.length; i++) text(canvas,legend[i],8,225 + i * 19,12,0xffc0c5ce);
        canvas.restore();
    }
}
