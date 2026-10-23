package org.andr36oid.usbmode;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

final class PadLegend extends View {
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final KeyboardModel mModel;
    private final Soundboard mBoard;
    PadLegend(Context context, KeyboardModel model, Soundboard board) { super(context); mModel = model; mBoard = board; }
    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xff101417);
        final float width = getWidth() / 3f, height = getHeight() / 4f;
        final int pressed = mModel.macro == null ? 0 : mModel.macro.pressed;
        for (int i = 0; i < 12; i++) {
            float x = i % 3 * width, y = i / 3 * height;
            mPaint.setColor((pressed & (1 << MacroProfiles.BUTTONS[i])) != 0 ? 0xff007f78 : 0xff273238);
            canvas.drawRoundRect(x + 3,y + 3,x + width - 3,y + height - 3,7,7,mPaint);
            mPaint.setColor(0xffeeeeee); mPaint.setTextSize(Math.max(12,Math.min(width / 13,height / 3)));
            canvas.drawText(MacroProfiles.CAPS[i],x + 10,y + height * 0.35f,mPaint);
            String label = mBoard != null ? mBoard.labels[i] : MacroSettings.label(getContext(),mModel.macro.profile.buttons[i]);
            while (label.length() > 1 && mPaint.measureText(label) > width - 20) label = label.substring(0,label.length() - 1);
            canvas.drawText(label,x + 10,y + height * 0.75f,mPaint);
        }
    }
}
