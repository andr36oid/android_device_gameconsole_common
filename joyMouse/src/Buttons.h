#pragma once

#include <array>
#include <bitset>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <string_view>
#include <vector>

#include <linux/input.h>

namespace joymouse {

using KeyBits = std::bitset<KEY_CNT>;

// Logical pad buttons, independent of the key codes a particular driver uses.
enum class Button : uint8_t {
    A, B, X, Y,
    L1, R1, L2, R2, L3, R3,
    Select, Start, Mode,
    Up, Down, Left, Right,
};
constexpr size_t kButtonCount = 17;

constexpr size_t index(Button b) {
    return static_cast<size_t>(b);
}

const char* buttonName(Button b);
// Case-insensitive; also accepts LB/RB/LT/RT/LS/RS/FN.
std::optional<Button> parseButton(std::string_view name);

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

// Translates the key codes one particular pad reports into logical buttons.
class ButtonMap {
public:
    ButtonMap();
    explicit ButtonMap(const KeyBits& supported);

    std::optional<Button> button(int code) const;
    bool has(Button b) const { return present_[index(b)]; }

private:
    void add(int code, Button b);

    std::array<int8_t, KEY_CNT> buttonForCode_;
    std::array<bool, kButtonCount> present_{};
};

// Button combination that toggles mouse mode when nothing is configured.
std::vector<Button> defaultToggleChord(const ButtonMap& map);

}  // namespace joymouse
