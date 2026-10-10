#pragma once

#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "Buttons.h"

namespace joymouse {

// Touch controls: per-app profiles that turn buttons and sticks into touches
// on a virtual touchscreen. The Touch controls settings app writes one JSON
// file per app to kTouchProfileDir and names the app in front in
// kTouchProfileProperty; see README.md for the format.

constexpr const char kTouchProfileDir[] = "/data/system/andr36oid/touchmap";
// Package whose profile is active, empty for none.
constexpr const char kTouchProfileProperty[] = "sys.touchmap.profile";
// Changes whenever a profile file is saved, so the daemon reads it again.
constexpr const char kTouchSerialProperty[] = "sys.touchmap.serial";
// "WxH@R": the display's size in its natural orientation and the current
// rotation (0-3, Surface.ROTATION_*).
constexpr const char kTouchDisplayProperty[] = "sys.touchmap.display";

enum class ControlType : uint8_t {
    Tap,       // press: a short tap at the point
    Hold,      // the finger stays down while the button is held
    Joystick,  // the finger is dragged around the point, as far as the stick is pushed
    Camera,    // the stick drags the finger across an area, like swiping to look around
    Swipe,     // press: a quick swipe from the point in one direction
};

enum class StickSource : uint8_t { Left, Right, Dpad };
constexpr size_t kStickSourceCount = 3;

// A stick pushed one way, as the trigger of a tap, hold or swipe instead of a
// button, for games with separate buttons for each direction. In the profile
// file it is a "button" named LSTICK_UP, RSTICK_LEFT, ...
enum class StickDir : uint8_t { Up, Down, Left, Right };
constexpr size_t kStickDirCount = 4;
struct StickDirection {
    StickSource stick = StickSource::Left;  // Left or Right
    StickDir dir = StickDir::Up;
    bool operator==(const StickDirection& o) const { return stick == o.stick && dir == o.dir; }
};
// Index into a [2 sticks x 4 directions] table.
constexpr size_t index(StickDirection d) {
    return static_cast<size_t>(d.stick) * kStickDirCount + static_cast<size_t>(d.dir);
}
constexpr size_t kStickDirectionCount = 2 * kStickDirCount;
std::optional<StickDirection> parseStickDirection(std::string_view s);
const char* stickDirectionName(StickDirection d);

const char* controlTypeName(ControlType t);
std::optional<ControlType> parseControlType(std::string_view s);
const char* stickSourceName(StickSource s);
std::optional<StickSource> parseStickSource(std::string_view s);

struct TouchControl {
    ControlType type = ControlType::Tap;
    std::optional<Button> button;      // Tap, Hold, Swipe
    std::optional<StickDirection> direction;  // Tap, Hold, Swipe instead of a button
    std::optional<StickSource> stick;  // Joystick, Camera
    // Position as a share of the screen's width and height, as the app is shown.
    float x = 0.5f;
    float y = 0.5f;
    // Joystick and camera: how far the finger may go from the point, as a share
    // of the screen's shorter side.
    float radius = 0.12f;
    // Camera: speed multiplier, 1 = the whole screen height per second at full tilt.
    float speed = 1.0f;
    // Swipe: direction in degrees (0 right, 90 down, 180 left, 270 up) and
    // length as a share of the shorter side.
    float angle = 270.0f;
    float length = 0.2f;
    // 0: always, 1: while the shift button is held.
    int layer = 0;

    bool usesStick() const { return type == ControlType::Joystick || type == ControlType::Camera; }
};

struct TouchProfile {
    bool enabled = true;
    bool hints = true;
    // Held: the controls of layer 1 apply, the others stay as they are.
    std::optional<Button> shift;
    float deadzone = 0.15f;
    std::vector<TouchControl> controls;

    // The control a button or stick drives on the given layer. Layer 1 falls
    // back to layer 0 for anything it doesn't define.
    const TouchControl* forButton(Button b, int layer) const;
    const TouchControl* forStick(StickSource s, int layer) const;
    const TouchControl* forDirection(StickDirection d, int layer) const;
    // Whether a tap, hold or swipe uses a direction of the stick on the layer
    // (or on layer 0 for layer 1).
    bool stickHasDirections(StickSource s, int layer) const;
    // Whether anything on any layer uses the input.
    bool usesButton(Button b) const;
    bool usesStick(StickSource s) const;
};

// Parses a profile. Unknown members are ignored; controls that can't work
// (no button, a button used twice on one layer, ...) are dropped and described
// in `warnings`. Returns nullopt only if the document itself is unusable.
std::optional<TouchProfile> parseTouchProfile(std::string_view json, std::vector<std::string>* warnings,
                                              std::string* error);

// Android package names only; keeps the profile path inside kTouchProfileDir.
bool isValidPackageName(std::string_view name);

}  // namespace joymouse
