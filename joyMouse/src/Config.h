#pragma once

#include <array>
#include <functional>
#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <vector>

#include "Buttons.h"

namespace joymouse {

enum class StickSide : uint8_t { Left, Right };

enum StickAxis : size_t { kLeftX, kLeftY, kRightX, kRightY, kStickAxisCount };

// All settings live in persist.sys.joymouse.* so the settings app (system uid)
// can write them; see README for the list.
constexpr const char kPropertyPrefix[] = "persist.sys.joymouse.";
// Runtime state and on/off switch.
constexpr const char kActiveProperty[] = "sys.joymouse.active";
// "1" while SystemUI hides the status bar for a fullscreen app.
constexpr const char kFullscreenProperty[] = "sys.joymouse.fullscreen";
// The framework keeps the active hardware button remap here, and changes
// kButtonRemapSerialProperty whenever it rewrites the file.
constexpr const char kButtonRemapFile[] = "/data/system/hardware_button_remap";
constexpr const char kButtonRemapSerialProperty[] = "sys.hardware_button_remap.serial";

struct Config {
    // Classic mode: like the original joyMouse, the pad isn't grabbed. The
    // pointer stick moves the pointer and its stick click is the left button,
    // while Android keeps getting every button and the other stick.
    bool classic = false;

    // Pointer
    float speed = 1.0f;       // multiplier of the base top speed
    float curve = 2.2f;       // response exponent, 1 = linear
    float deadzone = 0.10f;   // radial, fraction of stick travel
    float precision = 0.35f;  // speed multiplier while Precision is held
    bool accelCompensation = true;
    std::optional<StickSide> pointerStick;  // unset: right stick when the pad has one
    bool pointerInvertX = false;
    bool pointerInvertY = false;

    // Scrolling
    float scrollSpeed = 1.0f;
    float scrollDeadzone = 0.20f;
    bool naturalScroll = false;

    // Hardware axis correction (LX, LY, RX, RY); unset means the device default.
    std::array<std::optional<bool>, kStickAxisCount> invert;

    // Mode switching. toggle unset: default chord; empty: chord disabled.
    bool toggleEnabled = true;
    std::optional<std::vector<Button>> toggle;
    int toggleMs = 1000;
    bool startActive = false;
    bool toast = true;

    // Per-button actions in mouse mode; unset means defaultBindings().
    std::array<std::optional<Action>, kButtonCount> buttons;
    // Hardware button remap from Settings > Button mapping, "scanCode:KEYCODE,...".
    // In mouse mode a remapped button gets the action of the button it acts as.
    std::string buttonRemap;

    int rateHz = 250;
    int clickFreezeMs = 60;
    std::string device;  // event node path or name substring
    std::optional<std::pair<int, int>> displaySize;
    bool debug = false;

    Bindings bindings() const;
};

bool operator==(const Config& a, const Config& b);
inline bool operator!=(const Config& a, const Config& b) {
    return !(a == b);
}

// Property name of a button's mouse mode action, e.g. persist.sys.joymouse.btn_r1.
std::string buttonProperty(Button b);

using PropertyReader = std::function<std::string(const char* name)>;

// Builds a Config from the given property source. Invalid values are ignored
// and described in `warnings`.
Config loadConfig(const PropertyReader& read, std::vector<std::string>* warnings);

// Individual parsers, exposed for tests. Percent parsers return fractions.
std::optional<float> parsePercent(std::string_view s, float minPercent, float maxPercent);
std::optional<float> parseFloat(std::string_view s, float min, float max);
std::optional<int> parseInt(std::string_view s, int min, int max);
std::optional<bool> parseBool(std::string_view s);
// "L3+R3" -> {L3, R3}; "none" -> {} (disabled). A toggle needs at least two
// different buttons so that it can't be hit by accident.
std::optional<std::vector<Button>> parseChord(std::string_view s);
// "640x480"
std::optional<std::pair<int, int>> parseSize(std::string_view s);

}  // namespace joymouse
