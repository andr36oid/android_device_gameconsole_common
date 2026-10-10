package org.andr36oid.consoleart;

import org.andr36oid.consoleart.R36s.Control;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Host test of the drawing: checks the layout (everything on the body or sticking out of an
 * edge, nothing overlapping, a 4:3 screen) and, given a folder, paints the console and a stick
 * into PNGs with Java2D through the same Painter calls the apps make.
 *
 *   tests/run-tests.sh [png folder]
 */
public class ConsoleArtTest {

    private static int sFailures;

    public static void main(String[] args) throws Exception {
        checkLayout();
        if (args.length > 0) {
            previews(new File(args[0]));
        }
        if (sFailures > 0) {
            System.out.println(sFailures + " failed");
            System.exit(1);
        }
        System.out.println("ok");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            System.out.println("FAIL " + what);
            sFailures++;
        }
    }

    private static void checkLayout() {
        final float[] b = R36s.BODY;
        final float[] v = R36s.VIEWPORT;
        final float[] s = R36s.SCREEN;
        check(Math.abs((s[2] - s[0]) / (s[3] - s[1]) - 4f / 3f) < 0.01f, "screen is 4:3");
        check(inside(s, R36s.BEZEL, 0) && inside(R36s.BEZEL, b, 0), "screen in bezel in body");
        for (Control k : Control.values()) {
            final float[] r = {k.left, k.top, k.right, k.bottom};
            // Room for the focus ring and badge in the viewport
            check(inside(r, v, 3), k + " in the viewport");
            if (k.shape == R36s.SHAPE_TAB) {
                check(k.bottom < b[1] || k.right < b[0] || k.left > b[2], k + " outside the body");
            } else {
                check(inside(r, b, 2), k + " on the body");
                check(!overlaps(r, R36s.BEZEL, 2), k + " clear of the screen");
                check(!overlaps(r, R36s.SPEAKER, 1), k + " clear of the speaker");
            }
            for (Control o : Control.values()) {
                if (o.ordinal() <= k.ordinal()
                        || (k.shape == R36s.SHAPE_DPAD && o.shape == R36s.SHAPE_DPAD)) {
                    continue;
                }
                final boolean round = k.shape != R36s.SHAPE_TAB && k.shape != R36s.SHAPE_DPAD
                        && o.shape != R36s.SHAPE_TAB && o.shape != R36s.SHAPE_DPAD;
                check(round ? Math.hypot(k.centerX() - o.centerX(), k.centerY() - o.centerY())
                        > (k.width() + o.width()) / 2 + 1
                        : !overlaps(r, new float[] {o.left, o.top, o.right, o.bottom}, 1),
                        k + " clear of " + o);
            }
        }
        // The top edge in the console's order, from the front; power left, volume right
        final Control[] top = {Control.L2, Control.L1, Control.R2, Control.R1};
        check(Control.POWER.right < b[0], "power on the left side");
        check(Control.VOLUME_UP.left > b[2] && Control.VOLUME_DOWN.left > b[2]
                && Control.VOLUME_UP.bottom < Control.VOLUME_DOWN.top, "volume right, + above -");
        for (int i = 1; i < top.length; i++) {
            check(top[i - 1].right < top[i].left, top[i - 1] + " left of " + top[i]);
        }
        check(Control.X.centerY() < Control.B.centerY()
                && Control.Y.centerX() < Control.A.centerX(), "X top, B bottom, Y left, A right");
        check(Control.SELECT.centerX() < Control.FN.centerX()
                && Control.FN.centerX() < Control.START.centerX()
                && Control.FN.centerY() < Control.SELECT.centerY(), "FN above Select and Start");
    }

    private static boolean inside(float[] a, float[] b, float margin) {
        return a[0] >= b[0] + margin && a[1] >= b[1] + margin && a[2] <= b[2] - margin
                && a[3] <= b[3] - margin;
    }

    private static boolean overlaps(float[] a, float[] b, float margin) {
        return a[0] < b[2] + margin && b[0] < a[2] + margin && a[1] < b[3] + margin
                && b[1] < a[3] + margin;
    }

    // Previews

    private static void previews(File dir) throws Exception {
        dir.mkdirs();
        final ConsoleDrawing.Colors dark = new ConsoleDrawing.Colors();
        dark.body = 0xff1d1e20;
        dark.outline = 0x73ffffff;
        dark.button = 0x24ffffff;
        dark.label = 0xffffffff;
        dark.caption = 0xb3ffffff;
        dark.bezel = 0xff101114;
        dark.screen = 0xff202124;
        dark.accent = 0xff80cbc4;
        dark.onAccent = 0xff202124;
        dark.background = 0xff121212;
        final ConsoleDrawing drawing = new ConsoleDrawing();

        console(dir, "console-plain.png", dark, drawing, null);
        // The button mapper: A selected and focused, two buttons changed, Vol+ missing
        console(dir, "console-mapper.png", dark, drawing, (k, look) -> {
            if (k == Control.A) {
                look.fill = dark.accent;
                look.outline = dark.accent;
                look.content = dark.onAccent;
                look.focus = true;
            }
            if (k == Control.DPAD_LEFT) {
                look.fill = dark.accent;
                look.outline = dark.accent;
                look.content = dark.onAccent;
            }
            look.badge = k == Control.L2 || k == Control.SELECT || k == Control.R3;
            if (k == Control.VOLUME_UP) {
                look.fill &= 0x4dffffff;
                look.outline &= 0x4dffffff;
                look.content &= 0x4dffffff;
            }
        });
        // The controller test: some pressed, a stick tilted
        console(dir, "console-test.png", dark, drawing, (k, look) -> {
            if (k == Control.B || k == Control.L1 || k == Control.START) {
                look.fill = 0xfffb8c00;
                look.outline = 0xfffb8c00;
                look.content = 0xff0e1116;
            } else if (k == Control.X || k == Control.DPAD_UP) {
                look.outline = 0xff2ea043;
            }
            if (k == Control.L3) {
                look.stickX = 0.8f;
                look.stickY = -0.6f;
            }
        });

        final StickDrawing.Colors sc = new StickDrawing.Colors();
        sc.fill = 0x0fffffff;
        sc.ring = 0xb3ffffff;
        sc.guide = 0x40ffffff;
        sc.dot = 0xffffffff;
        sc.pressed = dark.accent;
        sc.deadzone = 0x4080cbc4;
        sc.trail = dark.accent;
        sc.label = 0xb3ffffff;
        final BufferedImage im = new BufferedImage(480, 260, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = start(im, 0xff121212);
        final AwtPainter p = new AwtPainter(g, 1, 0, 0);
        final StickDrawing stick = new StickDrawing();
        final float[] trail = new float[120];
        for (int i = 0; i < 60; i++) {
            final double a = i * Math.PI * 2 / 59;
            trail[i * 2] = (float) Math.cos(a) * 0.97f;
            trail[i * 2 + 1] = (float) Math.sin(a) * 0.97f;
        }
        stick.draw(p, sc, 120, 115, 90, 0.4f, -0.3f, false, 0.12f, trail, 0, 60, "Left stick");
        stick.draw(p, sc, 360, 115, 90, -1, 1, true, 0, null, 0, 0, "Right stick");
        ImageIO.write(im, "png", new File(dir, "sticks.png"));
    }

    private static void console(File dir, String name, ConsoleDrawing.Colors c,
            ConsoleDrawing drawing, ConsoleDrawing.Styler styler) throws Exception {
        final float[] v = R36s.VIEWPORT;
        final float scale = 3;
        final BufferedImage im = new BufferedImage(Math.round((v[2] - v[0]) * scale),
                Math.round((v[3] - v[1]) * scale), BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = start(im, c.background);
        drawing.draw(new AwtPainter(g, scale, -v[0] * scale, -v[1] * scale), c, styler);
        ImageIO.write(im, "png", new File(dir, name));
    }

    private static Graphics2D start(BufferedImage im, int background) {
        final Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(new Color(background, true));
        g.fillRect(0, 0, im.getWidth(), im.getHeight());
        return g;
    }

    /** Painter on Java2D, mapping units to pixels like CanvasPainter. */
    static final class AwtPainter implements Painter {
        private final Graphics2D mG;
        private final float mScale;
        private final float mX;
        private final float mY;

        AwtPainter(Graphics2D g, float scale, float x, float y) {
            mG = g;
            mScale = scale;
            mX = x;
            mY = y;
        }

        private float x(float u) {
            return mX + u * mScale;
        }

        private float y(float u) {
            return mY + u * mScale;
        }

        private void paint(java.awt.Shape shape, int fill, int stroke, float width) {
            if (fill != 0) {
                mG.setColor(new Color(fill, true));
                mG.fill(shape);
            }
            if (stroke != 0 && width > 0) {
                mG.setColor(new Color(stroke, true));
                mG.setStroke(new BasicStroke(width * mScale, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                mG.draw(shape);
            }
        }

        @Override
        public void rect(float l, float t, float r, float b, float radius, int fill, int stroke,
                float width) {
            paint(new RoundRectangle2D.Float(x(l), y(t), (r - l) * mScale, (b - t) * mScale,
                    radius * 2 * mScale, radius * 2 * mScale), fill, stroke, width);
        }

        @Override
        public void circle(float cx, float cy, float r, int fill, int stroke, float width) {
            paint(new Ellipse2D.Float(x(cx - r), y(cy - r), r * 2 * mScale, r * 2 * mScale),
                    fill, stroke, width);
        }

        @Override
        public void poly(float[] xy, int n, boolean closed, int fill, int stroke, float width) {
            final Path2D.Float path = new Path2D.Float();
            for (int i = 0; i < n; i++) {
                if (i == 0) {
                    path.moveTo(x(xy[0]), y(xy[1]));
                } else {
                    path.lineTo(x(xy[i * 2]), y(xy[i * 2 + 1]));
                }
            }
            if (closed) {
                path.closePath();
            }
            paint(path, closed ? fill : 0, stroke, width);
        }

        @Override
        public void arc(float cx, float cy, float r, float start, float sweep, int stroke,
                float width) {
            // Java2D angles run counterclockwise
            paint(new Arc2D.Float(x(cx - r), y(cy - r), r * 2 * mScale, r * 2 * mScale, -start,
                    -sweep, Arc2D.OPEN), 0, stroke, width);
        }

        @Override
        public void text(String text, float cx, float cy, float size, boolean bold, int color) {
            mG.setFont(new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN,
                    Math.max(1, Math.round(size * mScale))));
            final FontMetrics m = mG.getFontMetrics();
            mG.setColor(new Color(color, true));
            mG.drawString(text, x(cx) - m.stringWidth(text) / 2f,
                    y(cy) + (m.getAscent() - m.getDescent()) / 2f);
        }
    }
}
