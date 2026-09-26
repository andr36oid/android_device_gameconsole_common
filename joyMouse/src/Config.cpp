#include "Config.h"

#include <cctype>
#include <cerrno>
#include <cmath>
#include <cstdlib>

namespace joymouse {

namespace {

std::string_view trim(std::string_view s) {
    while (!s.empty() && std::isspace(static_cast<unsigned char>(s.front()))) s.remove_prefix(1);
    while (!s.empty() && std::isspace(static_cast<unsigned char>(s.back()))) s.remove_suffix(1);
    return s;
}

std::string lower(std::string_view s) {
    std::string out(s);
    for (char& c : out) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return out;
}

std::optional<double> parseNumber(std::string_view s) {
    const std::string text(trim(s));
    if (text.empty()) return std::nullopt;
    char* end = nullptr;
    errno = 0;
    const double v = std::strtod(text.c_str(), &end);
    if (errno != 0 || end == text.c_str() || *end != '\0' || !std::isfinite(v)) return std::nullopt;
    return v;
}

}  // namespace

Bindings Config::bindings() const {
    Bindings b = defaultBindings();
    for (size_t i = 0; i < kButtonCount; ++i) {
        if (buttons[i]) b[i] = *buttons[i];
    }
    return b;
}

bool operator==(const Config& a, const Config& b) {
    return a.speed == b.speed && a.curve == b.curve && a.deadzone == b.deadzone &&
           a.precision == b.precision && a.accelCompensation == b.accelCompensation &&
           a.pointerStick == b.pointerStick && a.pointerInvertX == b.pointerInvertX &&
           a.pointerInvertY == b.pointerInvertY && a.scrollSpeed == b.scrollSpeed &&
           a.scrollDeadzone == b.scrollDeadzone && a.naturalScroll == b.naturalScroll &&
           a.invert == b.invert && a.toggleEnabled == b.toggleEnabled && a.toggle == b.toggle &&
           a.toggleMs == b.toggleMs &&
           a.startActive == b.startActive && a.toast == b.toast && a.buttons == b.buttons &&
           a.buttonRemap == b.buttonRemap &&
           a.rateHz == b.rateHz && a.clickFreezeMs == b.clickFreezeMs && a.device == b.device &&
           a.displaySize == b.displaySize && a.debug == b.debug;
}

std::string buttonProperty(Button b) {
    return std::string(kPropertyPrefix) + "btn_" + lower(buttonName(b));
}

std::optional<float> parsePercent(std::string_view s, float minPercent, float maxPercent) {
    s = trim(s);
    if (!s.empty() && s.back() == '%') s.remove_suffix(1);
    const auto v = parseNumber(s);
    if (!v || *v < minPercent || *v > maxPercent) return std::nullopt;
    return static_cast<float>(*v / 100.0);
}

std::optional<float> parseFloat(std::string_view s, float min, float max) {
    const auto v = parseNumber(s);
    if (!v || *v < min || *v > max) return std::nullopt;
    return static_cast<float>(*v);
}

std::optional<int> parseInt(std::string_view s, int min, int max) {
    const auto v = parseNumber(s);
    if (!v || *v != std::floor(*v) || *v < min || *v > max) return std::nullopt;
    return static_cast<int>(*v);
}

std::optional<bool> parseBool(std::string_view s) {
    const std::string v = lower(trim(s));
    if (v == "1" || v == "true" || v == "yes" || v == "on") return true;
    if (v == "0" || v == "false" || v == "no" || v == "off") return false;
    return std::nullopt;
}

std::optional<std::vector<Button>> parseChord(std::string_view s) {
    s = trim(s);
    const std::string v = lower(s);
    if (v == "none" || v == "off" || v == "disabled") return std::vector<Button>{};
    std::vector<Button> buttons;
    while (true) {
        const size_t pos = s.find('+');
        const auto b = parseButton(trim(s.substr(0, pos)));
        if (!b) return std::nullopt;
        for (Button existing : buttons) {
            if (existing == *b) return std::nullopt;
        }
        buttons.push_back(*b);
        if (pos == std::string_view::npos) break;
        s.remove_prefix(pos + 1);
    }
    if (buttons.size() < 2) return std::nullopt;
    return buttons;
}

std::optional<std::pair<int, int>> parseSize(std::string_view s) {
    s = trim(s);
    size_t sep = s.find('x');
    if (sep == std::string_view::npos) sep = s.find('X');
    if (sep == std::string_view::npos) return std::nullopt;
    const auto w = parseInt(s.substr(0, sep), 16, 16384);
    const auto h = parseInt(s.substr(sep + 1), 16, 16384);
    if (!w || !h) return std::nullopt;
    return std::make_pair(*w, *h);
}

Config loadConfig(const PropertyReader& read, std::vector<std::string>* warnings) {
    Config c;

    // Returns the trimmed value, or nothing when the property is unset/empty.
    auto get = [&](const std::string& key) -> std::optional<std::string> {
        std::string v(trim(read(key.c_str())));
        if (v.empty()) return std::nullopt;
        return v;
    };
    auto key = [](const char* name) { return std::string(kPropertyPrefix) + name; };
    // Applies a parsed value, or records a warning when parsing failed.
    auto apply = [&](const std::string& k, const std::string& raw, auto parsed, auto& out) {
        if (parsed) {
            out = *parsed;
        } else if (warnings) {
            warnings->push_back(k + ": ignoring invalid value '" + raw + "'");
        }
    };
    auto percent = [&](const char* name, float min, float max, float& out) {
        if (auto v = get(key(name))) apply(key(name), *v, parsePercent(*v, min, max), out);
    };
    auto number = [&](const char* name, float min, float max, float& out) {
        if (auto v = get(key(name))) apply(key(name), *v, parseFloat(*v, min, max), out);
    };
    auto integer = [&](const char* name, int min, int max, int& out) {
        if (auto v = get(key(name))) apply(key(name), *v, parseInt(*v, min, max), out);
    };
    auto boolean = [&](const char* name, bool& out) {
        if (auto v = get(key(name))) apply(key(name), *v, parseBool(*v), out);
    };
    auto optionalBoolean = [&](const char* name, std::optional<bool>& out) {
        if (auto v = get(key(name))) apply(key(name), *v, parseBool(*v), out);
    };

    percent("speed", 10, 500, c.speed);
    number("curve", 1.0f, 4.0f, c.curve);
    percent("deadzone", 0, 50, c.deadzone);
    percent("precision", 5, 100, c.precision);
    boolean("accel_comp", c.accelCompensation);
    if (auto v = get(key("pointer_stick"))) {
        const std::string side = lower(*v);
        std::optional<StickSide> parsed;
        if (side == "left") parsed = StickSide::Left;
        if (side == "right") parsed = StickSide::Right;
        apply(key("pointer_stick"), *v, parsed, c.pointerStick);
    }
    boolean("pointer_invert_x", c.pointerInvertX);
    boolean("pointer_invert_y", c.pointerInvertY);

    percent("scroll_speed", 10, 500, c.scrollSpeed);
    percent("scroll_deadzone", 0, 60, c.scrollDeadzone);
    boolean("natural_scroll", c.naturalScroll);

    optionalBoolean("invert_lx", c.invert[kLeftX]);
    optionalBoolean("invert_ly", c.invert[kLeftY]);
    optionalBoolean("invert_rx", c.invert[kRightX]);
    optionalBoolean("invert_ry", c.invert[kRightY]);

    boolean("toggle_enabled", c.toggleEnabled);
    if (auto v = get(key("toggle"))) apply(key("toggle"), *v, parseChord(*v), c.toggle);
    integer("toggle_ms", 300, 10000, c.toggleMs);
    boolean("start_active", c.startActive);
    boolean("toast", c.toast);

    for (size_t i = 0; i < kButtonCount; ++i) {
        const std::string k = buttonProperty(static_cast<Button>(i));
        if (auto v = get(k)) apply(k, *v, parseAction(*v), c.buttons[i]);
    }

    integer("rate", 60, 1000, c.rateHz);
    integer("click_freeze_ms", 0, 500, c.clickFreezeMs);
    if (auto v = get(key("device"))) c.device = *v;
    if (auto v = get(key("display"))) apply(key("display"), *v, parseSize(*v), c.displaySize);
    boolean("debug", c.debug);
    return c;
}

}  // namespace joymouse
