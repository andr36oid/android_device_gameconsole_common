#include "Buttons.h"

#include <cctype>

// Older kernel headers (e.g. the host sysroot) predate the d-pad button codes.
#ifndef BTN_DPAD_UP
#define BTN_DPAD_UP 0x220
#define BTN_DPAD_DOWN 0x221
#define BTN_DPAD_LEFT 0x222
#define BTN_DPAD_RIGHT 0x223
#endif

namespace joymouse {

namespace {

constexpr const char* kButtonNames[kButtonCount] = {
        "A",  "B",  "X",  "Y",      "L1",    "R1",   "L2", "R2",   "L3",
        "R3", "SELECT", "START", "MODE", "UP", "DOWN", "LEFT", "RIGHT",
};

constexpr const char* kActionNames[] = {
        "pass", "none", "left", "right", "middle", "back", "forward", "smart", "precision", "scroll",
};

struct Synonym {
    const char* name;
    Button button;
};

constexpr Synonym kSynonyms[] = {
        {"LB", Button::L1}, {"RB", Button::R1}, {"LT", Button::L2}, {"RT", Button::R2},
        {"LS", Button::L3}, {"RS", Button::R3}, {"FN", Button::Mode},
};

struct Alias {
    int code;
    Button button;
};

constexpr Alias kAliases[] = {
        {BTN_A, Button::A},
        {BTN_B, Button::B},
        {BTN_X, Button::X},
        {BTN_Y, Button::Y},
        {BTN_TL, Button::L1},
        {BTN_TR, Button::R1},
        {BTN_TL2, Button::L2},
        {BTN_TR2, Button::R2},
        {BTN_THUMBL, Button::L3},
        {BTN_THUMBR, Button::R3},
        {BTN_SELECT, Button::Select},
        {BTN_START, Button::Start},
        {BTN_MODE, Button::Mode},
        {KEY_UP, Button::Up},
        {BTN_DPAD_UP, Button::Up},
        {KEY_DOWN, Button::Down},
        {BTN_DPAD_DOWN, Button::Down},
        {KEY_LEFT, Button::Left},
        {BTN_DPAD_LEFT, Button::Left},
        {KEY_RIGHT, Button::Right},
        {BTN_DPAD_RIGHT, Button::Right},
};

// The odroidgo2-style joypad driver reports the stick clicks as KEY_BACK and
// KEY_LEFTMETA (see Vendor_484b_Product_1100.kl). Only honour those codes when
// the pad lacks the real thumb button codes.
constexpr Alias kQuirkAliases[] = {
        {KEY_BACK, Button::L3},
        {KEY_LEFTMETA, Button::R3},
};

bool equalsIgnoreCase(std::string_view a, std::string_view b) {
    if (a.size() != b.size()) return false;
    for (size_t i = 0; i < a.size(); ++i) {
        if (std::toupper(static_cast<unsigned char>(a[i])) !=
            std::toupper(static_cast<unsigned char>(b[i]))) {
            return false;
        }
    }
    return true;
}

}  // namespace

const char* buttonName(Button b) {
    return kButtonNames[index(b)];
}

std::optional<Button> parseButton(std::string_view name) {
    for (size_t i = 0; i < kButtonCount; ++i) {
        if (equalsIgnoreCase(name, kButtonNames[i])) return static_cast<Button>(i);
    }
    for (const Synonym& s : kSynonyms) {
        if (equalsIgnoreCase(name, s.name)) return s.button;
    }
    return std::nullopt;
}

const char* actionName(Action a) {
    return kActionNames[static_cast<size_t>(a)];
}

std::optional<Action> parseAction(std::string_view name) {
    for (size_t i = 0; i < sizeof(kActionNames) / sizeof(kActionNames[0]); ++i) {
        if (equalsIgnoreCase(name, kActionNames[i])) return static_cast<Action>(i);
    }
    return std::nullopt;
}

Bindings defaultBindings() {
    Bindings b;
    b.fill(Action::Pass);
    b[index(Button::A)] = Action::Smart;
    b[index(Button::R1)] = Action::Left;
    b[index(Button::L1)] = Action::Right;
    b[index(Button::R2)] = Action::Precision;
    b[index(Button::L2)] = Action::Scroll;
    b[index(Button::R3)] = Action::Left;
    b[index(Button::L3)] = Action::None;
    return b;
}

ButtonMap::ButtonMap() {
    buttonForCode_.fill(-1);
}

ButtonMap::ButtonMap(const KeyBits& supported) : ButtonMap() {
    for (const Alias& a : kAliases) {
        if (supported.test(static_cast<size_t>(a.code))) add(a.code, a.button);
    }
    for (const Alias& a : kQuirkAliases) {
        if (!has(a.button) && supported.test(static_cast<size_t>(a.code))) add(a.code, a.button);
    }
}

void ButtonMap::add(int code, Button b) {
    buttonForCode_[static_cast<size_t>(code)] = static_cast<int8_t>(b);
    present_[index(b)] = true;
}

std::optional<Button> ButtonMap::button(int code) const {
    if (code < 0 || code >= KEY_CNT) return std::nullopt;
    const int8_t b = buttonForCode_[static_cast<size_t>(code)];
    if (b < 0) return std::nullopt;
    return static_cast<Button>(b);
}

std::vector<Button> defaultToggleChord(const ButtonMap& map) {
    if (map.has(Button::L3) && map.has(Button::R3)) return {Button::L3, Button::R3};
    if (map.has(Button::Select) && map.has(Button::Start)) return {Button::Select, Button::Start};
    return {};
}

}  // namespace joymouse
