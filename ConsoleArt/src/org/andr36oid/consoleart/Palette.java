package org.andr36oid.consoleart;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Color;

/** Colours for the drawings from the app's theme, light or dark. */
public final class Palette {

    public final ConsoleDrawing.Colors console = new ConsoleDrawing.Colors();
    public final StickDrawing.Colors stick = new StickDrawing.Colors();

    public Palette(Context context) {
        final TypedArray a = context.obtainStyledAttributes(new int[] {
                android.R.attr.textColorPrimary, android.R.attr.textColorSecondary,
                android.R.attr.colorAccent, android.R.attr.colorBackground});
        final int text = a.getColor(0, Color.WHITE);
        final int secondary = a.getColor(1, Color.GRAY);
        final int accent = a.getColor(2, 0xff80cbc4);
        final int background = a.getColor(3, Color.BLACK);
        a.recycle();

        console.body = blend(background, text, 0.06f);
        console.outline = alpha(text, 0.45f);
        console.button = alpha(text, 0.14f);
        console.label = text;
        console.caption = secondary;
        console.bezel = 0xff101114;
        console.screen = 0xff202124;
        console.accent = accent;
        // Whichever of white and near black stands out more on the accent colour
        console.onAccent = Color.luminance(accent) > 0.21f ? 0xff202124 : Color.WHITE;
        console.background = background;

        stick.fill = alpha(text, 0.06f);
        stick.ring = secondary;
        stick.guide = alpha(text, 0.25f);
        stick.dot = text;
        stick.pressed = accent;
        stick.deadzone = alpha(accent, 0.25f);
        stick.trail = accent;
        stick.label = secondary;
    }

    public static int alpha(int color, float factor) {
        return (color & 0x00ffffff) | (Math.round(Color.alpha(color) * factor) << 24);
    }

    private static int blend(int under, int over, float amount) {
        return Color.rgb(
                Math.round(Color.red(under) + (Color.red(over) - Color.red(under)) * amount),
                Math.round(Color.green(under) + (Color.green(over) - Color.green(under)) * amount),
                Math.round(Color.blue(under) + (Color.blue(over) - Color.blue(under)) * amount));
    }
}
