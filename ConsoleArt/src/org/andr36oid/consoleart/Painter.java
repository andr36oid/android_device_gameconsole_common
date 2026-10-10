package org.andr36oid.consoleart;

/**
 * What the drawings need from a canvas. Colours are ARGB; a colour of 0 (fully transparent)
 * skips that fill or stroke. CanvasPainter draws on Android, the host test into a PNG.
 */
public interface Painter {

    void rect(float left, float top, float right, float bottom, float radius, int fill,
            int stroke, float strokeWidth);

    void circle(float cx, float cy, float r, int fill, int stroke, float strokeWidth);

    /** Points as x, y pairs; n is the number of points. */
    void poly(float[] xy, int n, boolean closed, int fill, int stroke, float strokeWidth);

    /** An arc of a circle, degrees clockwise from 3 o'clock, round ends. */
    void arc(float cx, float cy, float r, float startDegrees, float sweepDegrees, int stroke,
            float strokeWidth);

    /** Text centred on (cx, cy). */
    void text(String text, float cx, float cy, float size, boolean bold, int color);
}
