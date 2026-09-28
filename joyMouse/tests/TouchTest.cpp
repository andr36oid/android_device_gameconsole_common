#include <algorithm>
#include <cmath>
#include <map>
#include <memory>
#include <vector>

#include <gtest/gtest.h>

#include "Json.h"
#include "SharedPad.h"
#include "TouchController.h"
#include "TouchProfile.h"
#include "TouchSurface.h"

namespace joymouse {
namespace {

constexpr int kStickMax = 1800;
constexpr int kL3 = KEY_BACK;
constexpr int kR3 = KEY_LEFTMETA;

// --- Fakes --------------------------------------------------------------

PadInfo r36sPad() {
    PadInfo info;
    info.name = "GO-Super Gamepad";
    info.id = {BUS_HOST, 0x484b, 0x1100, 0x0100};
    for (int code : {KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT, BTN_A, BTN_B, BTN_X, BTN_Y, BTN_TL, BTN_TR,
                     BTN_TL2, BTN_TR2, BTN_SELECT, BTN_START, KEY_HOMEPAGE, kL3, kR3}) {
        info.keys.set(static_cast<size_t>(code));
    }
    for (int code : {ABS_X, ABS_Y, ABS_RX, ABS_RY}) {
        input_absinfo abs{};
        abs.minimum = -kStickMax;
        abs.maximum = kStickMax;
        info.abs[static_cast<size_t>(code)] = abs;
    }
    return info;
}

struct FakePad : PadPort {
    bool grabbed = false;
    bool absReporting = true;
    int grabCalls = 0;
    KeyBits keys;
    AbsValues abs{};

    bool setGrabbed(bool g) override {
        ++grabCalls;
        grabbed = g;
        return true;
    }
    void setAbsReporting(bool enabled) override { absReporting = enabled; }
    bool readState(KeyBits* k, AbsValues* a) override {
        *k = keys;
        *a = abs;
        return true;
    }
};

struct FakeOutput : OutputPort {
    bool opened = false;
    std::vector<std::vector<InputEvent>> frames;

    bool open() override {
        opened = true;
        return true;
    }
    void close() override { opened = false; }
    bool isOpen() const override { return opened; }
    bool send(const std::vector<InputEvent>& frame) override {
        EXPECT_TRUE(opened);
        EXPECT_FALSE(frame.empty());
        EXPECT_EQ(frame.back().type, EV_SYN);
        frames.push_back(frame);
        return true;
    }
    std::vector<std::pair<int, int>> keys() const {
        std::vector<std::pair<int, int>> out;
        for (const auto& f : frames) {
            for (const InputEvent& e : f) {
                if (e.type == EV_KEY) out.emplace_back(e.code, e.value);
            }
        }
        return out;
    }
    int lastAbs(uint16_t code, int fallback) const {
        int v = fallback;
        for (const auto& f : frames) {
            for (const InputEvent& e : f) {
                if (e.type == EV_ABS && e.code == code) v = e.value;
            }
        }
        return v;
    }
    void clear() { frames.clear(); }
};

// Replays what the virtual touchscreen sent, like Android's multi-touch accumulator.
struct TouchModel {
    struct Finger {
        int id = -1;
        int x = 0, y = 0;
    };
    std::map<int, Finger> slots;
    int slot = 0;
    bool btnTouch = false;
    int downs = 0, ups = 0;
    // Every finger that went down: tracking id -> positions it went through.
    std::map<int, std::vector<std::pair<int, int>>> paths;

    void apply(const FakeOutput& out, size_t fromFrame = 0) {
        for (size_t i = fromFrame; i < out.frames.size(); ++i) {
            for (const InputEvent& e : out.frames[i]) {
                if (e.type == EV_KEY && e.code == BTN_TOUCH) btnTouch = e.value != 0;
                if (e.type != EV_ABS) continue;
                switch (e.code) {
                    case ABS_MT_SLOT: slot = e.value; break;
                    case ABS_MT_TRACKING_ID:
                        if (e.value < 0) {
                            slots.erase(slot);
                            ++ups;
                        } else {
                            slots[slot] = Finger{e.value, 0, 0};
                            ++downs;
                        }
                        break;
                    case ABS_MT_POSITION_X: slots[slot].x = e.value; break;
                    case ABS_MT_POSITION_Y: slots[slot].y = e.value; break;
                    default: break;
                }
            }
            // A frame is complete: record where each finger is.
            for (const auto& [s, f] : slots) {
                auto& p = paths[f.id];
                if (p.empty() || p.back() != std::make_pair(f.x, f.y)) p.emplace_back(f.x, f.y);
            }
        }
    }
    int count() const { return static_cast<int>(slots.size()); }
    const Finger* only() const { return slots.size() == 1 ? &slots.begin()->second : nullptr; }
};

// --- JSON ---------------------------------------------------------------

TEST(JsonTest, ParsesDocument) {
    const auto v = JsonValue::parse(R"({"a": [1, -2.5e1, true, null, "x\"é"], "b": {"c": false}})");
    ASSERT_TRUE(v);
    ASSERT_TRUE(v->isObject());
    const JsonValue* a = v->get("a");
    ASSERT_TRUE(a && a->isArray());
    ASSERT_EQ(a->items().size(), 5u);
    EXPECT_EQ(a->items()[0].asNumber(), 1.0);
    EXPECT_EQ(a->items()[1].asNumber(), -25.0);
    EXPECT_EQ(a->items()[2].asBool(), true);
    EXPECT_EQ(a->items()[3].type(), JsonValue::Type::Null);
    EXPECT_EQ(a->items()[4].asString(), std::string("x\"\xc3\xa9"));
    EXPECT_EQ(v->get("b")->get("c")->asBool(), false);
    EXPECT_EQ(v->get("missing"), nullptr);
}

TEST(JsonTest, RejectsBrokenDocuments) {
    for (const char* bad : {"", "{", "{\"a\" 1}", "[1,]", "{\"a\":1,}", "tru", "\"open", "{} x", "01x", "-"}) {
        std::string error;
        EXPECT_FALSE(JsonValue::parse(bad, &error)) << bad;
        EXPECT_FALSE(error.empty()) << bad;
    }
    std::string deep(100, '[');
    EXPECT_FALSE(JsonValue::parse(deep));
}

// --- Profiles -----------------------------------------------------------

constexpr const char kProfile[] = R"({
  "version": 1, "enabled": true, "hints": false, "shift": "L2", "deadzone": 20,
  "controls": [
    {"type": "tap", "button": "A", "x": 0.9, "y": 0.8},
    {"type": "hold", "button": "R1", "x": 0.9, "y": 0.5},
    {"type": "joystick", "stick": "left", "x": 0.2, "y": 0.7, "radius": 0.15},
    {"type": "camera", "stick": "right", "x": 0.6, "y": 0.4, "radius": 0.25, "speed": 2},
    {"type": "swipe", "button": "Y", "x": 0.5, "y": 0.5, "angle": -90, "length": 0.3},
    {"type": "tap", "button": "A", "x": 0.1, "y": 0.1, "layer": 1},
    {"type": "tap", "button": "A", "x": 0.5, "y": 0.5},
    {"type": "tap", "button": "L2", "x": 0.5, "y": 0.5},
    {"type": "tap", "button": "FN", "x": 0.5, "y": 0.5},
    {"type": "wiggle", "button": "B", "x": 0.5, "y": 0.5},
    {"type": "tap", "button": "B"},
    {"type": "joystick", "x": 0.5, "y": 0.5},
    {"type": "tap", "button": "B", "x": 7, "y": -3}
  ]
})";

TEST(TouchProfileTest, ParsesAndDropsWhatCantWork) {
    std::vector<std::string> warnings;
    std::string error;
    const auto p = parseTouchProfile(kProfile, &warnings, &error);
    ASSERT_TRUE(p) << error;
    EXPECT_TRUE(p->enabled);
    EXPECT_FALSE(p->hints);
    EXPECT_EQ(p->shift, Button::L2);
    EXPECT_FLOAT_EQ(p->deadzone, 0.2f);
    // Kept: A, R1, left stick, right stick, Y, A on layer 1, B clamped onto the screen.
    ASSERT_EQ(p->controls.size(), 7u);
    // Dropped: second A, L2 (shift), FN, unknown type, no position, no stick.
    EXPECT_EQ(warnings.size(), 6u);

    const TouchControl& swipe = p->controls[4];
    EXPECT_EQ(swipe.type, ControlType::Swipe);
    EXPECT_FLOAT_EQ(swipe.angle, 270.0f);
    EXPECT_FLOAT_EQ(swipe.length, 0.3f);
    const TouchControl& camera = p->controls[3];
    EXPECT_EQ(camera.stick, StickSource::Right);
    EXPECT_FLOAT_EQ(camera.speed, 2.0f);
    EXPECT_FLOAT_EQ(p->controls[6].x, 1.0f);
    EXPECT_FLOAT_EQ(p->controls[6].y, 0.0f);
}

// Exactly what the Touch controls app writes (Profile.toJson(), org.json, indent 2).
constexpr const char kWrittenByApp[] = R"({
  "version": 1,
  "package": "com.example.game",
  "enabled": true,
  "hints": false,
  "shift": "L2",
  "deadzone": 20,
  "controls": [
    {
      "type": "tap",
      "button": "A",
      "x": 0.9,
      "y": 0.8,
      "radius": 0.06
    },
    {
      "type": "joystick",
      "stick": "left",
      "x": 0.2,
      "y": 0.7,
      "radius": 0.14
    },
    {
      "type": "camera",
      "stick": "right",
      "x": 0.6,
      "y": 0.4,
      "radius": 0.25,
      "speed": 1.5
    },
    {
      "type": "swipe",
      "button": "Y",
      "x": 0.5,
      "y": 0.5,
      "radius": 0.06,
      "angle": 225,
      "length": 0.3
    },
    {
      "type": "hold",
      "button": "SELECT",
      "x": 0.1,
      "y": 0.1,
      "radius": 0.06,
      "layer": 1
    },
    {
      "type": "joystick",
      "stick": "dpad",
      "x": 0.3,
      "y": 0.3,
      "radius": 0.06,
      "layer": 1
    }
  ]
})";

TEST(TouchProfileTest, ReadsWhatTheAppWrites) {
    std::vector<std::string> warnings;
    std::string error;
    const auto p = parseTouchProfile(kWrittenByApp, &warnings, &error);
    ASSERT_TRUE(p) << error;
    EXPECT_TRUE(warnings.empty());
    EXPECT_FALSE(p->hints);
    EXPECT_EQ(p->shift, Button::L2);
    EXPECT_FLOAT_EQ(p->deadzone, 0.2f);
    ASSERT_EQ(p->controls.size(), 6u);
    EXPECT_EQ(p->controls[2].type, ControlType::Camera);
    EXPECT_FLOAT_EQ(p->controls[2].speed, 1.5f);
    EXPECT_FLOAT_EQ(p->controls[3].angle, 225.0f);
    EXPECT_EQ(p->controls[4].button, Button::Select);
    EXPECT_EQ(p->controls[4].layer, 1);
    EXPECT_EQ(p->forStick(StickSource::Dpad, 1)->layer, 1);
    EXPECT_EQ(p->forStick(StickSource::Dpad, 0), nullptr);
}

TEST(TouchProfileTest, LayerOneFallsBackToLayerZero) {
    const auto p = parseTouchProfile(kProfile, nullptr, nullptr);
    ASSERT_TRUE(p);
    EXPECT_FLOAT_EQ(p->forButton(Button::A, 0)->x, 0.9f);
    EXPECT_FLOAT_EQ(p->forButton(Button::A, 1)->x, 0.1f);
    EXPECT_EQ(p->forButton(Button::R1, 1), p->forButton(Button::R1, 0));
    EXPECT_EQ(p->forButton(Button::X, 0), nullptr);
    EXPECT_NE(p->forStick(StickSource::Left, 1), nullptr);
    EXPECT_EQ(p->forStick(StickSource::Dpad, 0), nullptr);
    EXPECT_TRUE(p->usesButton(Button::Y));
    EXPECT_FALSE(p->usesButton(Button::Start));
}

TEST(TouchProfileTest, DpadJoystickTakesTheDirections) {
    std::vector<std::string> warnings;
    const auto p = parseTouchProfile(R"({"controls": [
        {"type": "tap", "button": "UP", "x": 0.5, "y": 0.5},
        {"type": "joystick", "stick": "dpad", "x": 0.2, "y": 0.7},
        {"type": "tap", "button": "LEFT", "x": 0.5, "y": 0.5, "layer": 1}]})",
                                     &warnings, nullptr);
    ASSERT_TRUE(p);
    ASSERT_EQ(p->controls.size(), 2u);
    EXPECT_EQ(p->controls[1].button, Button::Left);
    EXPECT_EQ(warnings.size(), 1u);
}

TEST(TouchProfileTest, RejectsNonProfiles) {
    std::string error;
    EXPECT_FALSE(parseTouchProfile("[]", nullptr, &error));
    EXPECT_FALSE(parseTouchProfile("{\"controls\": 3}", nullptr, &error));
    EXPECT_FALSE(parseTouchProfile("{", nullptr, &error));
    const auto empty = parseTouchProfile("{}", nullptr, &error);
    ASSERT_TRUE(empty);
    EXPECT_TRUE(empty->controls.empty());
}

TEST(TouchProfileTest, PackageNames) {
    EXPECT_TRUE(isValidPackageName("com.example.game"));
    EXPECT_TRUE(isValidPackageName("com.Some_Game2"));
    EXPECT_FALSE(isValidPackageName(""));
    EXPECT_FALSE(isValidPackageName("../etc/passwd"));
    EXPECT_FALSE(isValidPackageName("a/b"));
    EXPECT_FALSE(isValidPackageName("com..x"));
    EXPECT_FALSE(isValidPackageName(".hidden"));
}

// --- Geometry and fingers -----------------------------------------------

TEST(TouchGeometryTest, UndoesAndroidRotation) {
    TouchGeometry g;  // 640x480 natural
    EXPECT_EQ(g.toRaw(0, 0), std::make_pair(0, 0));
    EXPECT_EQ(g.toRaw(639, 479), std::make_pair(639, 479));
    EXPECT_EQ(g.toRaw(100, 50), std::make_pair(100, 50));
    EXPECT_EQ(g.toRaw(-5, 900), std::make_pair(0, 479));

    // Rotation 1 (90): shown 480x640. Android: x = raw y, y = max x - raw x.
    g.rotation = 1;
    EXPECT_EQ(g.visibleWidth(), 480);
    auto raw = g.toRaw(0, 0);
    EXPECT_EQ(raw, std::make_pair(639, 0));
    raw = g.toRaw(479, 639);
    EXPECT_EQ(raw, std::make_pair(0, 479));
    // Round trip through Android's formula for a point in the middle.
    raw = g.toRaw(120, 200);
    EXPECT_NEAR(raw.second, 120, 1);
    EXPECT_NEAR(639 - raw.first, 200, 1);

    g.rotation = 2;
    EXPECT_EQ(g.toRaw(0, 0), std::make_pair(639, 479));

    // Rotation 3 (270): x = max y - raw y, y = raw x.
    g.rotation = 3;
    raw = g.toRaw(120, 200);
    EXPECT_NEAR(479 - raw.second, 120, 1);
    EXPECT_NEAR(raw.first, 200, 1);
}

TEST(TouchSurfaceTest, SlotsAndTrackingIds) {
    FakeOutput out;
    out.open();
    TouchSurface s(out);
    const int a = s.down(10, 20);
    const int b = s.down(30, 40);
    EXPECT_NE(a, b);
    s.flush();
    TouchModel m;
    m.apply(out);
    EXPECT_EQ(m.count(), 2);
    EXPECT_TRUE(m.btnTouch);
    const int idA = m.slots[a].id;
    const int idB = m.slots[b].id;
    EXPECT_NE(idA, idB);
    EXPECT_EQ(m.slots[a].x, 10);
    EXPECT_EQ(m.slots[b].y, 40);

    // Moving sends only what changed.
    out.clear();
    s.move(a, 11, 20);
    s.flush();
    ASSERT_EQ(out.frames.size(), 1u);
    EXPECT_EQ(out.frames[0].size(), 3u);  // slot, x, syn

    // Lift one: BTN_TOUCH stays until the last finger is gone.
    s.up(a);
    s.flush();
    m.apply(out);
    EXPECT_EQ(m.count(), 1);
    EXPECT_TRUE(m.btnTouch);
    s.up(b);
    s.flush();
    m.apply(out, out.frames.size() - 1);
    EXPECT_EQ(m.count(), 0);
    EXPECT_FALSE(m.btnTouch);

    // A new finger gets a new tracking id.
    const int c = s.down(1, 1);
    s.flush();
    TouchModel m2;
    m2.apply(out, out.frames.size() - 1);
    ASSERT_EQ(m2.count(), 1);
    EXPECT_NE(m2.slots[c].id, idA);
    EXPECT_NE(m2.slots[c].id, idB);
}

TEST(TouchSurfaceTest, SlotLiftedThisFrameIsNotReused) {
    FakeOutput out;
    out.open();
    TouchSurface s(out);
    const int a = s.down(10, 10);
    s.flush();
    s.up(a);
    const int b = s.down(10, 10);
    EXPECT_NE(a, b);
    s.flush();
    TouchModel m;
    m.apply(out);
    EXPECT_EQ(m.downs, 2);
    EXPECT_EQ(m.ups, 1);
    EXPECT_EQ(m.count(), 1);
}

TEST(TouchSurfaceTest, QuickTapIsSeenBeforeItLifts) {
    FakeOutput out;
    out.open();
    TouchSurface s(out);
    const int a = s.down(5, 5);
    s.up(a);
    s.flush();
    ASSERT_EQ(out.frames.size(), 2u);
    TouchModel m;
    m.apply(out, 0);
    EXPECT_EQ(m.downs, 1);
    EXPECT_EQ(m.ups, 1);
    EXPECT_FALSE(m.btnTouch);
}

TEST(TouchSurfaceTest, TenFingersAtMost) {
    FakeOutput out;
    out.open();
    TouchSurface s(out);
    for (int i = 0; i < TouchSurface::kSlots; ++i) EXPECT_GE(s.down(i, i), 0);
    EXPECT_EQ(s.down(50, 50), -1);
    EXPECT_EQ(s.activeCount(), 10);
    s.releaseAll();
    s.flush();
    EXPECT_EQ(s.activeCount(), 0);
    EXPECT_GE(s.down(1, 1), 0);
}

// --- Touch mode ---------------------------------------------------------

class TouchControllerTest : public ::testing::Test {
protected:
    void SetUp() override {
        env.productDevice = "r36s";
        info = r36sPad();
        shared = std::make_unique<SharedPad>(pad);
        // Mouse mode is off and filters the sticks, as it does in the daemon.
        shared->client(0).setAbsReporting(false);
        touch = std::make_unique<TouchController>(info, shared->client(1), screen, passthrough);
        touch->configure(config, env, geometry, now);
    }

    void load(const char* json) {
        std::string error;
        auto p = parseTouchProfile(json, nullptr, &error);
        ASSERT_TRUE(p) << error;
        touch->setProfile(std::move(p), now);
    }

    // Loads a profile and waits until the pad is grabbed.
    void start(const char* json) {
        load(json);
        advance(300);
        ASSERT_EQ(touch->state(), TouchController::State::On);
        ASSERT_TRUE(pad.grabbed);
        screen.clear();
        passthrough.clear();
    }

    void send(uint16_t type, uint16_t code, int32_t value) {
        if (type == EV_KEY) pad.keys.set(code, value != 0);
        if (type == EV_ABS) pad.abs[code] = value;
        touch->onInput({type, code, value}, now);
        touch->onInput({EV_SYN, SYN_REPORT, 0}, now);
        touch->onTimeout(now);
    }
    void key(int code, bool down) { send(EV_KEY, static_cast<uint16_t>(code), down ? 1 : 0); }
    void stick(int xCode, int yCode, float x, float y) {
        send(EV_ABS, static_cast<uint16_t>(xCode), static_cast<int32_t>(x * kStickMax));
        send(EV_ABS, static_cast<uint16_t>(yCode), static_cast<int32_t>(y * kStickMax));
    }

    // Runs the timers for `ms` milliseconds.
    void advance(int ms) {
        const Nanos end = now + msToNanos(ms);
        while (true) {
            const auto next = touch->nextDeadline();
            if (!next || *next > end) break;
            now = std::max(now, *next);
            touch->onTimeout(now);
        }
        now = end;
        touch->onTimeout(now);
    }

    TouchModel model() const {
        TouchModel m;
        m.apply(screen);
        return m;
    }

    Nanos now = msToNanos(1000);
    Environment env;
    Config config;
    TouchGeometry geometry;
    PadInfo info;
    FakePad pad;
    FakeOutput screen, passthrough;
    std::unique_ptr<SharedPad> shared;
    std::unique_ptr<TouchController> touch;
};

constexpr const char kGame[] = R"({"shift": "L1", "controls": [
    {"type": "tap", "button": "A", "x": 0.5, "y": 0.5},
    {"type": "hold", "button": "B", "x": 0.25, "y": 0.25},
    {"type": "swipe", "button": "Y", "x": 0.5, "y": 0.5, "angle": 0, "length": 0.25},
    {"type": "joystick", "stick": "left", "x": 0.25, "y": 0.75, "radius": 0.125},
    {"type": "camera", "stick": "right", "x": 0.75, "y": 0.5, "radius": 0.25, "speed": 1},
    {"type": "tap", "button": "A", "x": 0.0, "y": 0.0, "layer": 1},
    {"type": "joystick", "stick": "dpad", "x": 0.5, "y": 0.9, "radius": 0.1, "layer": 1}
]})";

TEST_F(TouchControllerTest, StaysOffWithoutProfile) {
    advance(500);
    EXPECT_EQ(touch->state(), TouchController::State::Off);
    EXPECT_FALSE(screen.opened);
    EXPECT_FALSE(pad.grabbed);
    EXPECT_FALSE(pad.absReporting);

    // A disabled profile or one without controls changes nothing.
    load(R"({"enabled": false, "controls": [{"type": "tap", "button": "A", "x": 0.5, "y": 0.5}]})");
    load("{}");
    advance(500);
    EXPECT_EQ(touch->state(), TouchController::State::Off);
}

TEST_F(TouchControllerTest, GrabsOnlyWhenIdle) {
    pad.keys.set(BTN_X);  // held while the game comes up
    load(kGame);
    EXPECT_TRUE(screen.opened);
    EXPECT_TRUE(passthrough.opened);
    EXPECT_EQ(touch->state(), TouchController::State::Arming);
    advance(500);
    EXPECT_FALSE(pad.grabbed);
    key(BTN_X, false);
    EXPECT_TRUE(pad.grabbed);
    EXPECT_EQ(touch->state(), TouchController::State::On);
}

TEST_F(TouchControllerTest, TapGoesDownAndUp) {
    start(kGame);
    key(BTN_A, true);
    TouchModel m = model();
    ASSERT_EQ(m.count(), 1);
    EXPECT_EQ(m.only()->x, 320);  // 0.5 of 0..639, rounded
    EXPECT_EQ(m.only()->y, 240);
    EXPECT_TRUE(m.btnTouch);
    // Up after the tap length, even while A is still held.
    advance(60);
    m = model();
    EXPECT_EQ(m.count(), 0);
    EXPECT_FALSE(m.btnTouch);
    key(BTN_A, false);
    EXPECT_EQ(model().downs, 1);
    // Nothing reached Android as a button.
    EXPECT_TRUE(passthrough.keys().empty());
}

TEST_F(TouchControllerTest, HoldStaysWhileHeld) {
    start(kGame);
    key(BTN_B, true);
    advance(1000);
    TouchModel m = model();
    ASSERT_EQ(m.count(), 1);
    EXPECT_EQ(m.only()->x, 160);
    EXPECT_EQ(m.only()->y, 120);
    key(BTN_B, false);
    EXPECT_EQ(model().count(), 0);
}

TEST_F(TouchControllerTest, SeveralFingersAtOnce) {
    start(kGame);
    key(BTN_B, true);
    stick(ABS_X, ABS_Y, 1.0f, 0.0f);
    advance(100);
    key(BTN_A, true);
    TouchModel m = model();
    EXPECT_EQ(m.count(), 3);
    std::vector<int> ids;
    for (const auto& [slot, f] : m.slots) ids.push_back(f.id);
    std::sort(ids.begin(), ids.end());
    EXPECT_EQ(std::unique(ids.begin(), ids.end()), ids.end());
}

TEST_F(TouchControllerTest, UnmappedButtonsPassThrough) {
    start(kGame);
    key(BTN_X, true);
    key(BTN_X, false);
    key(BTN_START, true);
    key(BTN_START, false);
    const std::vector<std::pair<int, int>> want = {{BTN_X, 1}, {BTN_X, 0}, {BTN_START, 1}, {BTN_START, 0}};
    EXPECT_EQ(passthrough.keys(), want);
    EXPECT_TRUE(screen.frames.empty());
}

TEST_F(TouchControllerTest, FnAndFnShortcutsAlwaysPassThrough) {
    start(kGame);
    key(KEY_HOMEPAGE, true);
    key(BTN_A, true);  // FN + A: a system shortcut, not a tap
    key(BTN_A, false);
    key(KEY_HOMEPAGE, false);
    const std::vector<std::pair<int, int>> want = {
            {KEY_HOMEPAGE, 1}, {BTN_A, 1}, {BTN_A, 0}, {KEY_HOMEPAGE, 0}};
    EXPECT_EQ(passthrough.keys(), want);
    EXPECT_TRUE(screen.frames.empty());
    // FN let go first, then A: A's release still goes where its press went.
    passthrough.clear();
    key(KEY_HOMEPAGE, true);
    key(BTN_A, true);
    key(KEY_HOMEPAGE, false);
    key(BTN_A, false);
    EXPECT_EQ(passthrough.keys().back(), std::make_pair(static_cast<int>(BTN_A), 0));
}

TEST_F(TouchControllerTest, JoystickFollowsTheStick) {
    start(kGame);
    // Center (0.25 * 639, 0.75 * 479) = (160, 359), radius 0.125 * 480 = 60.
    stick(ABS_X, ABS_Y, 1.0f, 0.0f);
    TouchModel m = model();
    advance(8);
    m = model();
    ASSERT_EQ(m.count(), 1);
    // First at the middle...
    EXPECT_EQ(m.only()->x, 160);
    EXPECT_EQ(m.only()->y, 359);
    advance(50);
    m = model();
    ASSERT_EQ(m.count(), 1);
    // ...then pushed all the way right.
    EXPECT_EQ(m.only()->x, 220);
    EXPECT_EQ(m.only()->y, 359);
    // Up-left, full tilt: on the circle.
    stick(ABS_X, ABS_Y, -0.7071f, -0.7071f);
    advance(16);
    m = model();
    ASSERT_EQ(m.count(), 1);
    EXPECT_NEAR(std::hypot(m.only()->x - 160, m.only()->y - 359), 60.0, 3.0);
    EXPECT_LT(m.only()->x, 160);
    EXPECT_LT(m.only()->y, 359);
    // Let go: the finger lifts.
    stick(ABS_X, ABS_Y, 0.0f, 0.0f);
    advance(16);
    EXPECT_EQ(model().count(), 0);
    EXPECT_EQ(model().downs, 1);
    // The game never saw the left stick move.
    EXPECT_EQ(passthrough.lastAbs(ABS_X, 0), 0);
}

TEST_F(TouchControllerTest, CameraDragsAndStartsOverAtTheEdge) {
    start(kGame);
    // Center (0.75 * 639, 0.5 * 479) = (479, 240), radius 120, speed 480 px/s at full tilt.
    stick(ABS_RX, ABS_RY, 1.0f, 0.0f);
    advance(100);
    TouchModel m = model();
    ASSERT_EQ(m.count(), 1);
    EXPECT_GT(m.only()->x, 479);
    EXPECT_EQ(m.only()->y, 240);
    // After a second it has run past the edge several times.
    advance(1000);
    m = model();
    EXPECT_GE(m.downs, 3);
    EXPECT_EQ(m.ups, m.downs - m.count());
    for (const auto& [id, path] : m.paths) {
        EXPECT_EQ(path.front(), std::make_pair(479, 240)) << id;
        for (const auto& p : path) EXPECT_LE(p.first, 599) << id;
        // Every finger but the one still down ran to the edge before it lifted.
        if (id != m.slots.begin()->second.id) {
            EXPECT_EQ(path.back().first, 599) << id;
        }
    }
    // Stick released: the finger stays a moment, then lifts.
    stick(ABS_RX, ABS_RY, 0.0f, 0.0f);
    advance(50);
    EXPECT_EQ(model().count(), 1);
    advance(200);
    EXPECT_EQ(model().count(), 0);
}

TEST_F(TouchControllerTest, CameraSpeedFollowsDeflection) {
    start(kGame);
    stick(ABS_RX, ABS_RY, 0.3f, 0.0f);
    advance(150);
    const int slow = model().only()->x - 479;
    stick(ABS_RX, ABS_RY, 0.0f, 0.0f);
    advance(300);
    ASSERT_EQ(model().count(), 0);
    screen.clear();
    stick(ABS_RX, ABS_RY, 1.0f, 0.0f);
    advance(150);
    const int fast = model().only()->x - 479;
    EXPECT_GT(slow, 0);
    EXPECT_GT(fast, slow * 2);
}

TEST_F(TouchControllerTest, SwipeRunsToItsEnd) {
    start(kGame);
    key(BTN_Y, true);
    key(BTN_Y, false);  // a short press still swipes all the way
    advance(300);
    const TouchModel m = model();
    EXPECT_EQ(m.count(), 0);
    ASSERT_EQ(m.paths.size(), 1u);
    const auto& path = m.paths.begin()->second;
    EXPECT_EQ(path.front(), std::make_pair(320, 240));
    EXPECT_EQ(path.back(), std::make_pair(440, 240));  // 0.25 * 480 to the right
    EXPECT_GE(path.size(), 5u);
    for (size_t i = 1; i < path.size(); ++i) EXPECT_GE(path[i].first, path[i - 1].first);
}

TEST_F(TouchControllerTest, ShiftSwitchesLayer) {
    start(kGame);
    key(BTN_TL, true);
    EXPECT_EQ(touch->layer(), 1);
    EXPECT_TRUE(screen.frames.empty());
    EXPECT_TRUE(passthrough.keys().empty());
    key(BTN_A, true);
    TouchModel m = model();
    ASSERT_EQ(m.count(), 1);
    EXPECT_EQ(m.only()->x, 0);
    EXPECT_EQ(m.only()->y, 0);
    key(BTN_A, false);
    advance(60);
    // B has nothing on layer 1: layer 0's hold.
    key(BTN_B, true);
    m = model();
    ASSERT_EQ(m.count(), 1);
    EXPECT_EQ(m.only()->x, 160);
    key(BTN_B, false);
    // The d-pad is a joystick on layer 1 only.
    key(KEY_UP, true);
    advance(50);
    m = model();
    ASSERT_EQ(m.count(), 1);
    EXPECT_EQ(m.only()->x, 320);
    EXPECT_EQ(m.only()->y, 383);  // 0.9 * 479 = 431, radius 0.1 * 480 = 48 up
    EXPECT_TRUE(passthrough.keys().empty());
    // Letting go of the shift lifts the d-pad finger; the d-pad is a d-pad again.
    key(BTN_TL, false);
    key(KEY_UP, false);
    advance(20);
    EXPECT_EQ(touch->layer(), 0);
    EXPECT_EQ(model().count(), 0);
    key(KEY_DOWN, true);
    key(KEY_DOWN, false);
    const std::vector<std::pair<int, int>> want = {{KEY_DOWN, 1}, {KEY_DOWN, 0}};
    EXPECT_EQ(passthrough.keys(), want);
    // Back on layer 0, A taps the middle again.
    screen.clear();
    key(BTN_A, true);
    EXPECT_EQ(model().only()->x, 320);
}

TEST_F(TouchControllerTest, HeldButtonKeepsItsFingerAcrossLayers) {
    start(kGame);
    key(BTN_B, true);
    key(BTN_TL, true);
    advance(100);
    EXPECT_EQ(model().count(), 1);
    key(BTN_TL, false);
    advance(100);
    EXPECT_EQ(model().count(), 1);
    key(BTN_B, false);
    EXPECT_EQ(model().count(), 0);
}

TEST_F(TouchControllerTest, UnmappedStickPassesThrough) {
    start(R"({"controls": [{"type": "tap", "button": "A", "x": 0.5, "y": 0.5}]})");
    stick(ABS_RX, ABS_RY, 0.5f, 0.0f);
    EXPECT_EQ(passthrough.lastAbs(ABS_RX, 0), static_cast<int>(0.5f * kStickMax));
    EXPECT_TRUE(screen.frames.empty());
}

TEST_F(TouchControllerTest, MouseModeSuspendsTouchMode) {
    start(kGame);
    key(BTN_B, true);
    EXPECT_EQ(model().count(), 1);
    touch->setSuspended(true, now);
    EXPECT_EQ(touch->state(), TouchController::State::Off);
    EXPECT_EQ(model().count(), 0);  // every finger lifted before the device went away
    EXPECT_FALSE(screen.opened);
    EXPECT_FALSE(passthrough.opened);
    EXPECT_FALSE(pad.grabbed);
    key(BTN_B, false);
    touch->setSuspended(false, now);
    advance(300);
    EXPECT_EQ(touch->state(), TouchController::State::On);
    EXPECT_TRUE(pad.grabbed);
}

TEST_F(TouchControllerTest, ProfileSwitchReleasesEverything) {
    start(kGame);
    key(BTN_B, true);
    key(BTN_X, true);  // passed through
    load(R"({"controls": [{"type": "hold", "button": "B", "x": 0.9, "y": 0.9}]})");
    EXPECT_EQ(model().count(), 0);
    EXPECT_EQ(passthrough.keys().back(), std::make_pair(static_cast<int>(BTN_X), 0));
    // Still grabbed, no new arming round.
    EXPECT_EQ(touch->state(), TouchController::State::On);
    key(BTN_B, false);
    key(BTN_X, false);
    key(BTN_B, true);
    EXPECT_EQ(model().only()->x, 575);
    // No profile any more: off, pad back to Android.
    touch->setProfile(std::nullopt, now);
    EXPECT_EQ(touch->state(), TouchController::State::Off);
    EXPECT_FALSE(pad.grabbed);
    EXPECT_EQ(model().count(), 0);
}

TEST_F(TouchControllerTest, RotatedScreen) {
    geometry.rotation = 1;  // shown 480x640
    touch->configure(config, env, geometry, now);
    start(R"({"controls": [{"type": "hold", "button": "A", "x": 0.25, "y": 0.75}]})");
    key(BTN_A, true);
    const TouchModel m = model();
    ASSERT_EQ(m.count(), 1);
    // Shown at (120, 479): Android computes x = raw y, y = 639 - raw x.
    EXPECT_NEAR(m.only()->y, 120, 1);
    EXPECT_NEAR(639 - m.only()->x, 479, 1);
}

// --- Sharing the pad with mouse mode ------------------------------------

// Mouse mode and touch mode wired up like the daemon does: one pad, shared.
struct Both : ModeListener {
    TouchController* touch = nullptr;
    Nanos* now = nullptr;
    void onMouseModeChanged(bool active) override {
        if (touch) touch->setSuspended(active, *now);
    }
};

TEST(SharedPadTest, MouseModeAndTouchModeTakeTurns) {
    FakePad pad;
    SharedPad shared(pad);
    FakeOutput mouse, mousePad, screen, touchPad;
    Both both;
    Nanos now = msToNanos(1000);
    both.now = &now;
    const PadInfo info = r36sPad();
    Environment env;
    env.productDevice = "r36s";
    Controller mouseMode(info, shared.client(0), mouse, mousePad, &both);
    TouchController touch(info, shared.client(1), screen, touchPad);
    both.touch = &touch;
    Config config;
    mouseMode.configure(config, env, now);
    touch.configure(config, env, TouchGeometry{}, now);
    auto run = [&](int ms) {
        const Nanos end = now + msToNanos(ms);
        while (now < end) {
            now += msToNanos(4);
            mouseMode.onTimeout(now);
            touch.onTimeout(now);
        }
    };

    touch.setProfile(parseTouchProfile(kGame, nullptr, nullptr), now);
    run(300);
    ASSERT_EQ(touch.state(), TouchController::State::On);
    EXPECT_TRUE(pad.grabbed);

    // Mouse mode on (FN + X or the property): touch mode steps back first.
    mouseMode.setActive(true, now);
    EXPECT_EQ(touch.state(), TouchController::State::Off);
    EXPECT_FALSE(screen.opened);
    EXPECT_FALSE(touchPad.opened);
    run(300);
    EXPECT_EQ(mouseMode.mode(), Mode::On);
    EXPECT_TRUE(pad.grabbed);

    // Mouse mode off: touch mode comes back and keeps the pad.
    mouseMode.setActive(false, now);
    EXPECT_NE(touch.state(), TouchController::State::Off);
    run(300);
    EXPECT_EQ(touch.state(), TouchController::State::On);
    EXPECT_TRUE(pad.grabbed);
    EXPECT_FALSE(mouse.opened);

    // No profile any more: the pad goes back to Android.
    touch.setProfile(std::nullopt, now);
    EXPECT_FALSE(pad.grabbed);
}

TEST(SharedPadTest, GrabbedWhileEitherWantsIt) {
    FakePad pad;
    SharedPad shared(pad);
    EXPECT_TRUE(shared.client(0).setGrabbed(true));
    EXPECT_TRUE(pad.grabbed);
    EXPECT_TRUE(shared.client(1).setGrabbed(true));
    EXPECT_EQ(pad.grabCalls, 1);  // evdev allows one grab only
    EXPECT_TRUE(shared.client(0).setGrabbed(false));
    EXPECT_TRUE(pad.grabbed);
    EXPECT_TRUE(shared.client(1).setGrabbed(false));
    EXPECT_FALSE(pad.grabbed);

    shared.client(0).setAbsReporting(false);
    EXPECT_TRUE(pad.absReporting);
    shared.client(1).setAbsReporting(false);
    EXPECT_FALSE(pad.absReporting);
    shared.client(1).setAbsReporting(true);
    EXPECT_TRUE(pad.absReporting);
}

}  // namespace
}  // namespace joymouse
