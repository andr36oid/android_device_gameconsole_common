#include "TouchProfile.h"

#include <algorithm>
#include <cmath>
#include <cstdio>

#include "Json.h"

namespace joymouse {

namespace {

constexpr const char* kControlTypeNames[] = {"tap", "hold", "joystick", "camera", "swipe"};
constexpr const char* kStickSourceNames[] = {"left", "right", "dpad"};
constexpr size_t kMaxControls = 64;

bool isDpad(Button b) {
    return b == Button::Up || b == Button::Down || b == Button::Left || b == Button::Right;
}

std::string describe(size_t i, const char* what) {
    char buf[128];
    std::snprintf(buf, sizeof(buf), "control %zu: %s, left out", i + 1, what);
    return buf;
}

std::optional<float> number(const JsonValue& obj, const char* key, float min, float max) {
    const JsonValue* v = obj.get(key);
    if (!v) return std::nullopt;
    const auto n = v->asNumber();
    if (!n || !std::isfinite(*n)) return std::nullopt;
    return std::clamp(static_cast<float>(*n), min, max);
}

std::optional<std::string> text(const JsonValue& obj, const char* key) {
    const JsonValue* v = obj.get(key);
    return v ? v->asString() : std::nullopt;
}

}  // namespace

const char* controlTypeName(ControlType t) {
    return kControlTypeNames[static_cast<size_t>(t)];
}

std::optional<ControlType> parseControlType(std::string_view s) {
    for (size_t i = 0; i < std::size(kControlTypeNames); ++i) {
        if (s == kControlTypeNames[i]) return static_cast<ControlType>(i);
    }
    return std::nullopt;
}

const char* stickSourceName(StickSource s) {
    return kStickSourceNames[static_cast<size_t>(s)];
}

std::optional<StickSource> parseStickSource(std::string_view s) {
    for (size_t i = 0; i < std::size(kStickSourceNames); ++i) {
        if (s == kStickSourceNames[i]) return static_cast<StickSource>(i);
    }
    return std::nullopt;
}

const TouchControl* TouchProfile::forButton(Button b, int layer) const {
    for (int l = layer; l >= 0; --l) {
        for (const TouchControl& c : controls) {
            if (c.layer == l && !c.usesStick() && c.button == b) return &c;
        }
    }
    return nullptr;
}

const TouchControl* TouchProfile::forStick(StickSource s, int layer) const {
    for (int l = layer; l >= 0; --l) {
        for (const TouchControl& c : controls) {
            if (c.layer == l && c.usesStick() && c.stick == s) return &c;
        }
    }
    return nullptr;
}

bool TouchProfile::usesButton(Button b) const {
    return forButton(b, 1) != nullptr || forButton(b, 0) != nullptr;
}

bool TouchProfile::usesStick(StickSource s) const {
    return forStick(s, 1) != nullptr || forStick(s, 0) != nullptr;
}

std::optional<TouchProfile> parseTouchProfile(std::string_view json, std::vector<std::string>* warnings,
                                              std::string* error) {
    auto warn = [&](std::string w) {
        if (warnings) warnings->push_back(std::move(w));
    };
    std::string parseError;
    const auto doc = JsonValue::parse(json, &parseError);
    if (!doc) {
        if (error) *error = "not valid JSON: " + parseError;
        return std::nullopt;
    }
    if (!doc->isObject()) {
        if (error) *error = "not a JSON object";
        return std::nullopt;
    }

    TouchProfile p;
    if (const JsonValue* v = doc->get("enabled")) p.enabled = v->asBool().value_or(true);
    if (const JsonValue* v = doc->get("hints")) p.hints = v->asBool().value_or(true);
    if (const auto d = number(*doc, "deadzone", 0.0f, 90.0f)) p.deadzone = *d / 100.0f;
    if (const auto s = text(*doc, "shift")) {
        if (!s->empty() && *s != "none") {
            const auto b = parseButton(*s);
            if (!b || *b == Button::Mode) {
                warn("unknown shift button \"" + *s + "\", no shift");
            } else {
                p.shift = b;
            }
        }
    }

    const JsonValue* list = doc->get("controls");
    if (list && !list->isArray()) {
        if (error) *error = "\"controls\" is not a list";
        return std::nullopt;
    }
    if (!list) return p;

    // Which buttons and sticks each layer already uses.
    bool buttonUsed[2][kButtonCount] = {};
    bool stickUsed[2][kStickSourceCount] = {};
    for (size_t i = 0; i < list->items().size(); ++i) {
        const JsonValue& item = list->items()[i];
        if (p.controls.size() >= kMaxControls) {
            warn(describe(i, "too many controls"));
            break;
        }
        if (!item.isObject()) {
            warn(describe(i, "not an object"));
            continue;
        }
        TouchControl c;
        const auto typeName = text(item, "type");
        const auto type = typeName ? parseControlType(*typeName) : std::nullopt;
        if (!type) {
            warn(describe(i, "unknown type"));
            continue;
        }
        c.type = *type;
        const auto x = number(item, "x", 0.0f, 1.0f);
        const auto y = number(item, "y", 0.0f, 1.0f);
        if (!x || !y) {
            warn(describe(i, "no position"));
            continue;
        }
        c.x = *x;
        c.y = *y;
        if (const auto layer = number(item, "layer", 0.0f, 1.0f)) c.layer = *layer >= 0.5f ? 1 : 0;
        if (const auto r = number(item, "radius", 0.02f, 1.0f)) c.radius = *r;
        if (const auto s = number(item, "speed", 0.1f, 10.0f)) c.speed = *s;
        if (const auto a = number(item, "angle", -3600.0f, 3600.0f)) {
            c.angle = std::fmod(*a, 360.0f);
            if (c.angle < 0.0f) c.angle += 360.0f;
        }
        if (const auto l = number(item, "length", 0.02f, 1.5f)) c.length = *l;

        if (c.usesStick()) {
            const auto name = text(item, "stick");
            const auto stick = name ? parseStickSource(*name) : std::nullopt;
            if (!stick) {
                warn(describe(i, "no stick"));
                continue;
            }
            if (stickUsed[c.layer][static_cast<size_t>(*stick)]) {
                warn(describe(i, "stick already used on this layer"));
                continue;
            }
            stickUsed[c.layer][static_cast<size_t>(*stick)] = true;
            c.stick = stick;
        } else {
            const auto name = text(item, "button");
            const auto button = name ? parseButton(*name) : std::nullopt;
            if (!button || *button == Button::Mode) {
                warn(describe(i, "no button"));
                continue;
            }
            if (button == p.shift) {
                warn(describe(i, "the shift button can't also be a control"));
                continue;
            }
            if (buttonUsed[c.layer][index(*button)]) {
                warn(describe(i, "button already used on this layer"));
                continue;
            }
            buttonUsed[c.layer][index(*button)] = true;
            c.button = button;
        }
        p.controls.push_back(c);
    }

    // The d-pad as a joystick takes all four directions on its layer.
    for (int layer = 0; layer < 2; ++layer) {
        if (!stickUsed[layer][static_cast<size_t>(StickSource::Dpad)]) continue;
        const size_t before = p.controls.size();
        p.controls.erase(std::remove_if(p.controls.begin(), p.controls.end(),
                                        [&](const TouchControl& c) {
                                            return c.layer == layer && c.button && isDpad(*c.button);
                                        }),
                         p.controls.end());
        if (p.controls.size() != before) warn("d-pad buttons left out: the d-pad is a joystick on that layer");
    }
    return p;
}

bool isValidPackageName(std::string_view name) {
    if (name.empty() || name.size() > 200) return false;
    if (name.front() == '.' || name.back() == '.') return false;
    for (char c : name) {
        const bool ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
                        c == '.' || c == '_';
        if (!ok) return false;
    }
    return name.find("..") == std::string_view::npos;
}

}  // namespace joymouse
