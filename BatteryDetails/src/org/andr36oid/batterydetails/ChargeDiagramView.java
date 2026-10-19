package org.andr36oid.batterydetails;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.BatteryManager;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

/**
 * The charging path as boxes and arrows, lit in the accent colour where something sees the
 * charger:
 *
 *                    +-> detect pin -+
 *   USB-C port ------+-> power chip -+-> driver -+-> Android
 *       ^            +-> USB 5 V    -+           +-> battery
 *   5 V boost
 *
 * Red marks the 5 V boost running with no USB device on the port: a charger can take the
 * port for a power source then and keep its 5 V off.
 */
public class ChargeDiagramView extends View {

    private static final int DIM = 0, LIT = 1, WARN = 2, NONE = 3;
    private static final int WARN_COLOR = 0xFFFF5252;

    private final int mAccent;
    private final int mTextColor;
    private final int mSecondary;
    private final Paint mBox = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTitle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mValue = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final RectF mRect = new RectF();

    private ChargeDiag mDiag;
    private int mPlugged;

    public ChargeDiagramView(Context context, AttributeSet attrs) {
        super(context, attrs);
        final TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.colorAccent, value, true);
        mAccent = value.data;
        context.getTheme().resolveAttribute(android.R.attr.textColorPrimary, value, true);
        mTextColor = context.getColorStateList(value.resourceId).getDefaultColor();
        context.getTheme().resolveAttribute(android.R.attr.textColorSecondary, value, true);
        mSecondary = context.getColorStateList(value.resourceId).getDefaultColor();
        mLine.setStyle(Paint.Style.STROKE);
        mLine.setStrokeJoin(Paint.Join.ROUND);
        mTitle.setTextSize(sp(11));
        mTitle.setFakeBoldText(true);
        mValue.setTextSize(sp(10));
    }

    void set(ChargeDiag diag, int plugged) {
        mDiag = diag;
        mPlugged = plugged;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final ChargeDiag d = mDiag;
        if (d == null) return;
        final Context c = getContext();

        // What each part sees
        final int pinValue = d.get("dc_det_pin");
        final int pin = pinValue == Gauge.UNKNOWN ? DIM : pinValue < 0 ? NONE
                : pinValue == 1 ? LIT : DIM;
        final int pmic = d.is("plug_in") ? LIT : DIM;
        final int vbus = d.vbus == 1 ? LIT : DIM;
        final boolean powerIn = pin == LIT || pmic == LIT || vbus == LIT;
        final boolean otg = d.is("otg5v");
        final boolean boostOn = d.boostOn();
        final int boost = !boostOn ? DIM : otg ? LIT : WARN;
        final int port = boost == WARN ? WARN : powerIn || otg ? LIT : DIM;
        final int driver = d.driverSeesCharger() ? LIT : DIM;
        final int android = mPlugged != 0 ? LIT : DIM;
        final int current = d.currentMa();
        final boolean charging = current != Gauge.UNKNOWN && current >= 50;
        final int battery = charging ? LIT : DIM;

        // Four columns: port, sensors, driver, Android/battery; three rows.
        final float w = getWidth(), h = getHeight();
        final float gapX = w * 0.06f, gapY = dp(10);
        final float boxH = (h - 2 * gapY) / 3;
        final float[] colX = {0, w * 0.24f, w * 0.55f, w * 0.79f};
        final float[] colW = {w * 0.24f - gapX, w * 0.31f - gapX, w * 0.24f - gapX, w * 0.21f};
        final float[] rowY = {0, boxH + gapY, 2 * (boxH + gapY)};

        // Arrows first, under the boxes. They share segments, so the dim ones go first and
        // the lit ones over them.
        final float portR = colX[0] + colW[0], sensorL = colX[1], sensorR = colX[1] + colW[1];
        final float driverL = colX[2], driverR = colX[2] + colW[2], lastL = colX[3];
        for (boolean litPass : new boolean[] {false, true}) {
            for (int row = 0; row < 3; row++) {
                final int sensor = row == 0 ? pin : row == 1 ? pmic : vbus;
                if (sensor == NONE || (sensor == LIT) != litPass) continue;
                arrow(canvas, portR, mid(rowY[1], boxH), sensorL, mid(rowY[row], boxH), litPass);
                arrow(canvas, sensorR, mid(rowY[row], boxH), driverL, mid(rowY[1], boxH),
                        litPass);
            }
            if ((android == LIT) == litPass) {
                arrow(canvas, driverR, mid(rowY[1], boxH), lastL, mid(rowY[0], boxH), litPass);
            }
            if (charging == litPass) {
                arrow(canvas, driverR, mid(rowY[1], boxH), lastL, mid(rowY[2], boxH), litPass);
            }
        }
        // Boost feeds the port from below
        final float boostX = colX[0] + colW[0] / 2;
        arrowUp(canvas, boostX, rowY[2], rowY[1] + boxH, boost);

        box(canvas, colX[0], rowY[1], colW[0], boxH, port, c.getString(R.string.dia_port),
                c.getString(port == WARN ? R.string.dia_gives_5v : powerIn ? R.string.dia_power
                        : otg ? R.string.dia_gives_5v : R.string.dia_nothing));
        box(canvas, colX[0], rowY[2], colW[0], boxH, boost, c.getString(R.string.dia_boost),
                c.getString(boostOn ? R.string.dia_on : R.string.dia_off));
        box(canvas, colX[1], rowY[0], colW[1], boxH, pin, c.getString(R.string.dia_pin),
                pinValue == Gauge.UNKNOWN ? "?" : c.getString(pin == NONE ? R.string.dia_no_pin
                        : pin == LIT ? R.string.dia_power : R.string.dia_nothing));
        box(canvas, colX[1], rowY[1], colW[1], boxH, pmic, c.getString(R.string.dia_pmic),
                d.get("plug_in") == Gauge.UNKNOWN ? "?"
                        : c.getString(pmic == LIT ? R.string.dia_power : R.string.dia_nothing));
        box(canvas, colX[1], rowY[2], colW[1], boxH, vbus, c.getString(R.string.dia_vbus),
                d.vbus == Gauge.UNKNOWN ? "?"
                        : c.getString(vbus == LIT ? R.string.dia_power : R.string.dia_nothing));
        box(canvas, colX[2], rowY[1], colW[2], boxH, driver, c.getString(R.string.dia_driver),
                d.get("dc_in") == Gauge.UNKNOWN ? "?" : d.is("dc_in") ? "DC"
                        : d.is("usb_in") ? "USB" : c.getString(R.string.dia_nothing));
        box(canvas, colX[3], rowY[0], colW[3], boxH, android, c.getString(R.string.dia_android),
                mPlugged == 0 ? c.getString(R.string.dia_nothing)
                        : mPlugged == BatteryManager.BATTERY_PLUGGED_AC ? "AC" : "USB");
        box(canvas, colX[3], rowY[2], colW[3], boxH, battery, c.getString(R.string.dia_battery),
                current == Gauge.UNKNOWN ? "?" : (current > 0 ? "+" : "") + current + " mA");
    }

    private void box(Canvas canvas, float x, float y, float w, float h, int state,
            String title, String value) {
        final int color = state == LIT ? mAccent : state == WARN ? WARN_COLOR : mSecondary;
        mRect.set(x, y, x + w, y + h);
        mBox.setStyle(Paint.Style.FILL);
        mBox.setColor(state == DIM || state == NONE ? 0x14FFFFFF : withAlpha(color, 0x33));
        canvas.drawRoundRect(mRect, dp(6), dp(6), mBox);
        mBox.setStyle(Paint.Style.STROKE);
        mBox.setStrokeWidth(dp(state == LIT || state == WARN ? 2 : 1));
        mBox.setColor(state == DIM || state == NONE ? 0x44FFFFFF : color);
        canvas.drawRoundRect(mRect, dp(6), dp(6), mBox);

        final float pad = dp(4);
        mTitle.setColor(state == NONE ? mSecondary : mTextColor);
        mValue.setColor(state == DIM || state == NONE ? mSecondary : color);
        final float textH = mTitle.getTextSize() + mValue.getTextSize() + dp(2);
        final float top = y + (h - textH) / 2;
        text(canvas, title, x + w / 2, top + mTitle.getTextSize(), w - 2 * pad, mTitle, 11);
        text(canvas, value, x + w / 2, top + textH, w - 2 * pad, mValue, 10);
    }

    /** Centred, made smaller until it fits (long translations). */
    private void text(Canvas canvas, String text, float centerX, float baseline, float maxW,
            Paint paint, int sizeSp) {
        paint.setTextSize(sp(sizeSp));
        while (paint.measureText(text) > maxW && paint.getTextSize() > sp(7)) {
            paint.setTextSize(paint.getTextSize() - 1);
        }
        canvas.drawText(text, centerX - paint.measureText(text) / 2, baseline, paint);
        paint.setTextSize(sp(sizeSp));
    }

    /** Right-angled arrow: across, up or down at the middle, across, then a head. */
    private void arrow(Canvas canvas, float x1, float y1, float x2, float y2, boolean lit) {
        line(lit);
        final float head = dp(5);
        final float midX = (x1 + x2) / 2;
        mPath.reset();
        mPath.moveTo(x1, y1);
        mPath.lineTo(midX, y1);
        mPath.lineTo(midX, y2);
        mPath.lineTo(x2 - 1, y2);
        canvas.drawPath(mPath, mLine);
        mPath.reset();
        mPath.moveTo(x2 - head, y2 - head * 0.7f);
        mPath.lineTo(x2 - 1, y2);
        mPath.lineTo(x2 - head, y2 + head * 0.7f);
        canvas.drawPath(mPath, mLine);
    }

    private void arrowUp(Canvas canvas, float x, float y1, float y2, int state) {
        line(state != DIM);
        if (state == WARN) mLine.setColor(WARN_COLOR);
        final float head = dp(5);
        canvas.drawLine(x, y1, x, y2 + 1, mLine);
        mPath.reset();
        mPath.moveTo(x - head * 0.7f, y2 + head);
        mPath.lineTo(x, y2 + 1);
        mPath.lineTo(x + head * 0.7f, y2 + head);
        canvas.drawPath(mPath, mLine);
    }

    private void line(boolean lit) {
        mLine.setColor(lit ? mAccent : 0x33FFFFFF);
        mLine.setStrokeWidth(dp(lit ? 2 : 1));
    }

    private static float mid(float top, float height) {
        return top + height / 2;
    }

    private static int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value,
                getResources().getDisplayMetrics());
    }
}
