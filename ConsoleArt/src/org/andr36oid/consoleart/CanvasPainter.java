package org.andr36oid.consoleart;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;

/**
 * Painter on an Android Canvas. Maps drawing units to pixels: fit() scales a box of units
 * into an area of the view, identity() draws in pixels.
 */
public final class CanvasPainter implements Painter {

    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final RectF mRect = new RectF();
    private Canvas mCanvas;
    private float mScale = 1;
    private float mX;
    private float mY;
    /** Corner radius of the next rect(), in pixels */
    private float mRadius;

    public CanvasPainter() {
        mText.setTextAlign(Paint.Align.CENTER);
    }

    public CanvasPainter on(Canvas canvas) {
        mCanvas = canvas;
        return this;
    }

    public CanvasPainter identity() {
        mScale = 1;
        mX = 0;
        mY = 0;
        return this;
    }

    /** Scales the units box {l, t, r, b} to fit the area, centred. Returns the scale. */
    public float fit(float[] box, float left, float top, float width, float height) {
        mScale = Math.max(0, Math.min(width / (box[2] - box[0]), height / (box[3] - box[1])));
        mX = left + (width - (box[2] - box[0]) * mScale) / 2 - box[0] * mScale;
        mY = top + (height - (box[3] - box[1]) * mScale) / 2 - box[1] * mScale;
        return mScale;
    }

    public float scale() {
        return mScale;
    }

    public float x(float units) {
        return mX + units * mScale;
    }

    public float y(float units) {
        return mY + units * mScale;
    }

    public float px(float units) {
        return units * mScale;
    }

    public float unitsX(float px) {
        return mScale > 0 ? (px - mX) / mScale : 0;
    }

    public float unitsY(float px) {
        return mScale > 0 ? (px - mY) / mScale : 0;
    }

    /** A units box {l, t, r, b} in pixels. */
    public RectF map(float[] box, RectF out) {
        out.set(x(box[0]), y(box[1]), x(box[2]), y(box[3]));
        return out;
    }

    private void paint(int fill, int stroke, float width, boolean isPath) {
        if (fill != 0) {
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(fill);
            draw(isPath);
        }
        if (stroke != 0 && width > 0) {
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(px(width));
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeJoin(Paint.Join.ROUND);
            mPaint.setColor(stroke);
            draw(isPath);
        }
    }

    private void draw(boolean isPath) {
        if (isPath) {
            mCanvas.drawPath(mPath, mPaint);
        } else {
            mCanvas.drawRoundRect(mRect, mRadius, mRadius, mPaint);
        }
    }

    @Override
    public void rect(float left, float top, float right, float bottom, float radius, int fill,
            int stroke, float strokeWidth) {
        mRect.set(x(left), y(top), x(right), y(bottom));
        mRadius = px(radius);
        paint(fill, stroke, strokeWidth, false);
    }

    @Override
    public void circle(float cx, float cy, float r, int fill, int stroke, float strokeWidth) {
        mRect.set(x(cx - r), y(cy - r), x(cx + r), y(cy + r));
        mRadius = px(r);
        paint(fill, stroke, strokeWidth, false);
    }

    @Override
    public void poly(float[] xy, int n, boolean closed, int fill, int stroke,
            float strokeWidth) {
        mPath.reset();
        for (int i = 0; i < n; i++) {
            if (i == 0) {
                mPath.moveTo(x(xy[0]), y(xy[1]));
            } else {
                mPath.lineTo(x(xy[i * 2]), y(xy[i * 2 + 1]));
            }
        }
        if (closed) {
            mPath.close();
        }
        paint(closed ? fill : 0, stroke, strokeWidth, true);
    }

    @Override
    public void arc(float cx, float cy, float r, float startDegrees, float sweepDegrees,
            int stroke, float strokeWidth) {
        mPath.reset();
        mPath.addArc(x(cx - r), y(cy - r), x(cx + r), y(cy + r), startDegrees, sweepDegrees);
        paint(0, stroke, strokeWidth, true);
    }

    @Override
    public void text(String text, float cx, float cy, float size, boolean bold, int color) {
        mText.setColor(color);
        mText.setTextSize(px(size));
        mText.setTypeface(bold ? MEDIUM : Typeface.DEFAULT);
        final Paint.FontMetrics m = mText.getFontMetrics();
        mCanvas.drawText(text, x(cx), y(cy) - (m.ascent + m.descent) / 2, mText);
    }
}
