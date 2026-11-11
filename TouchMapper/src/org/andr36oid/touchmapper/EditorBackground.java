package org.andr36oid.touchmapper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.WindowManager;

/** One game frame, captured before opening the editor. Kept in memory only. */
final class EditorBackground {
    private static String sPackage;
    private static Bitmap sFrame;

    private EditorBackground() { }

    static void capture(Context context, String packageName) {
        sPackage = packageName;
        sFrame = null;
        try {
            final Display display = context.getSystemService(WindowManager.class).getDefaultDisplay();
            final Point size = new Point();
            display.getRealSize(size);
            // The standard screenshot API excludes secure layers. No capture is saved to disk
            // or passed through Binder, where even a 1024x768 bitmap exceeds the size limit.
            sFrame = SurfaceControl.screenshot(new Rect(0, 0, size.x, size.y),
                    size.x, size.y, display.getRotation());
        } catch (RuntimeException e) {
            Log.w("TouchControls", "Could not capture the game for editing", e);
        }
    }

    static Bitmap take(String packageName) {
        final Bitmap frame = packageName != null && packageName.equals(sPackage) ? sFrame : null;
        sPackage = null;
        sFrame = null;
        return frame;
    }
}
