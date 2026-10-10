package org.andr36oid.guide;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;

import org.andr36oid.consoleart.CanvasPainter;
import org.andr36oid.consoleart.ConsoleDrawing;
import org.andr36oid.consoleart.Palette;
import org.andr36oid.consoleart.R36s;

/** The console with every button named, the same drawing as in the button mapper. */
public class ConsoleView extends View {

    private final CanvasPainter mPainter = new CanvasPainter();
    private final ConsoleDrawing mDrawing = new ConsoleDrawing();
    private final ConsoleDrawing.Colors mColors;

    public ConsoleView(Context context) {
        super(context);
        mColors = new Palette(context).console;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mPainter.on(canvas).fit(R36s.VIEWPORT, getPaddingLeft(), getPaddingTop(),
                getWidth() - getPaddingLeft() - getPaddingRight(),
                getHeight() - getPaddingTop() - getPaddingBottom()) > 0) {
            mDrawing.draw(mPainter, mColors, null);
        }
    }
}
