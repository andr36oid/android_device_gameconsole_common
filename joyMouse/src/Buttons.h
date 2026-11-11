#pragma once

#include <array>
#include <bitset>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <string_view>
#include <vector>

#include <linux/input.h>
#include "andr36oid-gamepad.h"

namespace joymouse {

using KeyBits = std::bitset<KEY_CNT>;

// Logical pad buttons, independent of the key codes a particular driver uses.
enum class Button : uint8_t {
    A=A36_PAD_A, B=A36_PAD_B, X=A36_PAD_X, Y=A36_PAD_Y,
    L1=A36_PAD_L1, R1=A36_PAD_R1, L2=A36_PAD_L2, R2=A36_PAD_R2,
    L3=A36_PAD_L3, R3=A36_PAD_R3, Select=A36_PAD_SELECT, Start=A36_PAD_START,
    Mode=A36_PAD_MODE, Up=A36_PAD_UP, Down=A36_PAD_DOWN,
    Left=A36_PAD_LEFT, Right=A36_PAD_RIGHT,
};
constexpr size_t kButtonCount = 17;

constexpr size_t index(Button b) {
    return static_cast<size_t>(b);
}

const char* buttonName(Button b);
// Case-insensitive; also accepts LB/RB/LT/RT/LS/RS/FN.
std::optional<Button> parseButton(std::string_view name);

// One entry of the hardware button remap (Settings > Button mapping): the
// physical key code and the gamepad button it now acts as. `button` is unset
// when the key is disabled or mapped to something that isn't a gamepad button.
struct RemapEntry {
    int code;
    std::optional<Button> button;
};
// Parses the framework's "scanCode:KEYCODE,..." format, e.g.
// "304:BUTTON_B,305:BUTTON_A,172:NONE". Invalid entries are skipped.
std::vector<RemapEntry> parseButtonRemap(std::string_view remap);

// What a button does while mouse mode is active.
enum class Action : uint8_t {
    Pass,       // forwarded to Android unchanged
    None,       // swallowed
    Left,       // mouse buttons
    Right,
    Middle,
    Back,
    Forward,
    Smart,      // left click while the pointer is in use, Pass otherwise
    Precision,  // hold: slow pointer and scrolling
    Scroll,     // hold: the pointer stick scrolls instead of moving the pointer
};

const char* actionName(Action a);
std::optional<Action> parseAction(std::string_view name);

using Bindings = std::array<Action, kButtonCount>;
Bindings defaultBindings();

// One flag per button.
using ButtonSet = std::array<bool, kButtonCount>;
// Buttons a console lacks although its pad reports them (every device tree declares all of
// them), from the "fn,l3,r3,l2,r2,vol" list of Settings.Global andr36oid_missing_buttons.
// Only L2, R2, L3 and R3 are pad buttons here; Fn and the volume keys aren't.
ButtonSet parseMissingButtons(std::string_view list);

// Translates the key codes one particular pad reports into logical buttons.
class ButtonMap {
public:
    ButtonMap();
    explicit ButtonMap(const KeyBits& supported);

    std::optional<Button> button(int code) const;
    bool normalized() const { return normalized_; }
    bool has(Button b) const { return present_[index(b)]; }

private:
    void add(int code, Button b);

    bool normalized_ = false;
    std::array<int8_t, KEY_CNT> buttonForCode_;
    std::array<bool, kButtonCount> present_{};
};

// Button combination that toggles mouse mode when nothing is configured: L3 + R3, or
// Select + Start when the pad or the console (`missing`) lacks one of them.
std::vector<Button> defaultToggleChord(const ButtonMap& map, const ButtonSet& missing = {});

}  // namespace joymouse
