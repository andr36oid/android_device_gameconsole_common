package org.andr36oid.touchmapper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The touch controls of one app, as stored in its JSON file. The joyMouse daemon reads the same
 * file (joyMouse/src/TouchProfile.cpp), so names and units here must match it.
 */
final class Profile {

    static final int VERSION = 1;

    static final String TAP = "tap";
    static final String HOLD = "hold";
    static final String JOYSTICK = "joystick";
    static final String CAMERA = "camera";
    static final String SWIPE = "swipe";

    static final String STICK_LEFT = "left";
    static final String STICK_RIGHT = "right";
    static final String STICK_DPAD = "dpad";

    static final float DEFAULT_TAP_RADIUS = 0.06f;
    static final float DEFAULT_JOYSTICK_RADIUS = 0.14f;
    static final float DEFAULT_CAMERA_RADIUS = 0.25f;
    static final float DEFAULT_SWIPE_LENGTH = 0.2f;
    static final int DEFAULT_DEADZONE = 15;

    /** One button or stick and what it touches. */
    static final class Control {
        String type = TAP;
        String button;  // tap, hold, swipe
        String stick;   // joystick, camera
        // Share of the screen's width and height, as the app is shown
        float x = 0.5f;
        float y = 0.5f;
        // Joystick and camera: reach from the point; tap and hold: marker size. Share of the
        // shorter side of the screen.
        float radius = DEFAULT_TAP_RADIUS;
        float speed = 1f;       // camera
        float angle = 270f;     // swipe: 0 right, 90 down, 180 left, 270 up
        float length = DEFAULT_SWIPE_LENGTH;  // swipe, share of the shorter side
        int layer;              // 1: while the shift button is held

        boolean usesStick() {
            return JOYSTICK.equals(type) || CAMERA.equals(type);
        }

        /** What drives it: a button name or a stick name. */
        String input() {
            return usesStick() ? stick : button;
        }

        Control copy() {
            final Control c = new Control();
            c.type = type;
            c.button = button;
            c.stick = stick;
            c.x = x;
            c.y = y;
            c.radius = radius;
            c.speed = speed;
            c.angle = angle;
            c.length = length;
            c.layer = layer;
            return c;
        }

        JSONObject toJson() throws JSONException {
            final JSONObject o = new JSONObject();
            o.put("type", type);
            if (usesStick()) {
                o.put("stick", stick);
            } else {
                o.put("button", button);
            }
            o.put("x", round(x));
            o.put("y", round(y));
            o.put("radius", round(radius));
            if (CAMERA.equals(type)) {
                o.put("speed", round(speed));
            }
            if (SWIPE.equals(type)) {
                o.put("angle", Math.round(angle));
                o.put("length", round(length));
            }
            if (layer != 0) {
                o.put("layer", layer);
            }
            return o;
        }

        static Control fromJson(JSONObject o) {
            final Control c = new Control();
            c.type = o.optString("type", TAP);
            c.button = o.optString("button", null);
            c.stick = o.optString("stick", null);
            c.x = clamp((float) o.optDouble("x", 0.5), 0f, 1f);
            c.y = clamp((float) o.optDouble("y", 0.5), 0f, 1f);
            c.radius = clamp((float) o.optDouble("radius",
                    c.usesStick() ? DEFAULT_JOYSTICK_RADIUS : DEFAULT_TAP_RADIUS), 0.02f, 1f);
            c.speed = clamp((float) o.optDouble("speed", 1.0), 0.1f, 10f);
            c.angle = (float) o.optDouble("angle", 270.0);
            c.length = clamp((float) o.optDouble("length", DEFAULT_SWIPE_LENGTH), 0.02f, 1.5f);
            c.layer = o.optInt("layer", 0) == 1 ? 1 : 0;
            return c;
        }
    }

    final String packageName;
    boolean enabled = true;
    boolean hints = true;
    String shift;  // null: no shift button
    int deadzone = DEFAULT_DEADZONE;
    final List<Control> controls = new ArrayList<>();

    Profile(String packageName) {
        this.packageName = packageName;
    }

    Profile copy() {
        final Profile p = new Profile(packageName);
        p.enabled = enabled;
        p.hints = hints;
        p.shift = shift;
        p.deadzone = deadzone;
        for (Control c : controls) {
            p.controls.add(c.copy());
        }
        return p;
    }

    /** The control on exactly this layer that uses the button or stick, or null. */
    Control find(String input, int layer) {
        for (Control c : controls) {
            if (c.layer == layer && input.equals(c.input())) {
                return c;
            }
        }
        return null;
    }

    boolean hasLayer(int layer) {
        for (Control c : controls) {
            if (c.layer == layer) {
                return true;
            }
        }
        return false;
    }

    String toJson() throws JSONException {
        final JSONObject o = new JSONObject();
        o.put("version", VERSION);
        o.put("package", packageName);
        o.put("enabled", enabled);
        o.put("hints", hints);
        o.put("shift", shift == null ? "none" : shift);
        o.put("deadzone", deadzone);
        final JSONArray list = new JSONArray();
        for (Control c : controls) {
            list.put(c.toJson());
        }
        o.put("controls", list);
        return o.toString(2);
    }

    static Profile fromJson(String packageName, String json) throws JSONException {
        final JSONObject o = new JSONObject(json);
        final Profile p = new Profile(packageName);
        p.enabled = o.optBoolean("enabled", true);
        p.hints = o.optBoolean("hints", true);
        final String shift = o.optString("shift", "none");
        p.shift = "none".equals(shift) || shift.isEmpty() ? null : shift;
        p.deadzone = Math.max(0, Math.min(90, o.optInt("deadzone", DEFAULT_DEADZONE)));
        final JSONArray list = o.optJSONArray("controls");
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                final JSONObject c = list.optJSONObject(i);
                if (c != null) {
                    p.controls.add(Control.fromJson(c));
                }
            }
        }
        return p;
    }

    static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double round(float v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
