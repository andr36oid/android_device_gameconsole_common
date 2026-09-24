#include <cmath>
#include <map>
#include <string>

#include <gtest/gtest.h>

#include "Buttons.h"
#include "Chord.h"
#include "Config.h"
#include "PointerModel.h"
#include "ScrollModel.h"
#include "Stick.h"

namespace joymouse {
namespace {

constexpr AxisRange kRange{-1800, 1800};

// --- Stick ---------------------------------------------------------------

Stick makeStick(float deadzone = 0.1f) {
    Stick s;
    s.configure(kRange, kRange, false, false);
    s.setDeadzone(deadzone);
    return s;
}

TEST(StickTest, RestIsZero) {
    Stick s = makeStick();
    EXPECT_TRUE(s.output().isZero());
    EXPECT_FLOAT_EQ(s.rawMagnitude(), 0.0f);
}

TEST(StickTest, DeadzoneSwallowsSmallDeflection) {
    Stick s = makeStick(0.1f);
    s.setRaw(0, 100);  // 5.5% of the range, 8.5% of the initial travel
    EXPECT_TRUE(s.output().isZero());
    s.setRaw(0, 400);
    EXPECT_GT(s.output().x, 0.0f);
}

TEST(StickTest, InitialTravelReachesFullDeflection) {
    Stick s = makeStick();
    s.setRaw(0, static_cast<int32_t>(1800 * Stick::kInitialExtent));
    EXPECT_NEAR(s.output().x, 1.0f, 1e-4);
}

TEST(StickTest, LearnsLongerTravel) {
    Stick s = makeStick();
    s.setRaw(0, 1800);
    EXPECT_NEAR(s.output().x, 1.0f, 1e-4);
    // After seeing the full range, the old "full" position is only part way.
    s.setRaw(0, static_cast<int32_t>(1800 * Stick::kInitialExtent));
    EXPECT_LT(s.output().x, 0.7f);
    // The negative direction learns separately.
    s.setRaw(0, static_cast<int32_t>(-1800 * Stick::kInitialExtent));
    EXPECT_NEAR(s.output().x, -1.0f, 1e-4);
}

TEST(StickTest, DeadzoneIsRadial) {
    Stick s = makeStick(0.2f);
    // 15% on both axes: inside a square deadzone, outside a round one... but
    // the magnitude (21%) is what counts.
    const int32_t v = static_cast<int32_t>(1800 * Stick::kInitialExtent * 0.15f);
    s.setRaw(0, v);
    s.setRaw(1, v);
    const StickVector out = s.output();
    EXPECT_GT(out.magnitude(), 0.0f);
    EXPECT_NEAR(out.x, out.y, 1e-5);  // direction preserved: no snapping to an axis
}

TEST(StickTest, DiagonalIsClampedToUnitCircle) {
    Stick s = makeStick();
    s.setRaw(0, 1800);
    s.setRaw(1, 1800);
    EXPECT_NEAR(s.output().magnitude(), 1.0f, 1e-4);
    EXPECT_NEAR(s.rawMagnitude(), 1.0f, 1e-4);
}

TEST(StickTest, Inversion) {
    Stick s;
    s.configure(kRange, kRange, true, false);
    s.setRaw(0, 1000);
    EXPECT_LT(s.output().x, 0.0f);
}

TEST(StickTest, UnsignedRange) {
    Stick s;
    s.configure({0, 4095}, {0, 4095}, false, false);
    s.setRaw(0, 2047);
    s.setRaw(1, 2048);
    EXPECT_TRUE(s.output().isZero());
    s.setRaw(0, 4095);
    EXPECT_NEAR(s.output().x, 1.0f, 1e-3);
}

// --- PointerModel -------------------------------------------------------

TEST(PointerModelTest, SpeedCurve) {
    PointerModel p;
    PointerParams params;
    params.topSpeed = 1000.0f;
    params.minSpeedFraction = 0.02f;
    params.curve = 2.0f;
    p.setParams(params);
    EXPECT_FLOAT_EQ(p.speedFor(0.0f), 0.0f);
    EXPECT_NEAR(p.speedFor(1e-6f), 20.0f, 0.1f);
    EXPECT_NEAR(p.speedFor(0.5f), 20.0f + 980.0f * 0.25f, 0.1f);
    EXPECT_NEAR(p.speedFor(1.0f), 1000.0f, 0.1f);
    EXPECT_NEAR(p.speedFor(2.0f), 1000.0f, 0.1f);
    float last = 0.0f;
    for (float m = 0.01f; m <= 1.0f; m += 0.01f) {
        EXPECT_GT(p.speedFor(m), last);
        last = p.speedFor(m);
    }
}

TEST(PointerModelTest, AndroidAccelInverseRoundTrips) {
    for (float v = 0.0f; v < 12000.0f; v += 37.0f) {
        EXPECT_NEAR(PointerModel::androidAccel(PointerModel::androidAccelInverse(v)), v, v * 1e-4f + 1e-3f);
    }
    EXPECT_FLOAT_EQ(PointerModel::androidAccelInverse(400.0f), 400.0f);
}

// Integrates `seconds` of motion at 250 Hz and returns the distance moved.
std::pair<int, int> run(PointerModel& p, double seconds) {
    int x = 0;
    int y = 0;
    const int steps = static_cast<int>(std::lround(seconds * 250.0));
    for (int i = 0; i < steps; ++i) {
        const PointerModel::Delta d = p.step(0.004);
        x += d.dx;
        y += d.dy;
    }
    return {x, y};
}

TEST(PointerModelTest, ReachesTopSpeed) {
    PointerModel p;
    PointerParams params;
    params.topSpeed = 720.0f;
    params.compensateAndroidAccel = false;
    p.setParams(params);
    p.setTarget(1.0f, 0.0f, 1.0f);
    run(p, 0.5);  // past the attack ramp
    const auto [x, y] = run(p, 1.0);
    EXPECT_NEAR(x, 720, 2);
    EXPECT_EQ(y, 0);
}

TEST(PointerModelTest, CompensatesAndroidAcceleration) {
    PointerModel p;
    PointerParams params;
    params.topSpeed = 1500.0f;
    p.setParams(params);
    p.setTarget(0.0f, -1.0f, 1.0f);
    run(p, 0.5);
    const auto [x, y] = run(p, 1.0);
    EXPECT_EQ(x, 0);
    // What we emit, sped up by Android, lands on the configured speed.
    EXPECT_NEAR(PointerModel::androidAccel(static_cast<float>(-y)), 1500.0f, 5.0f);
}

TEST(PointerModelTest, SlowSpeedsAccumulateSubPixels) {
    PointerModel p;
    PointerParams params;
    params.topSpeed = 700.0f;
    params.minSpeedFraction = 0.0f;
    params.curve = 2.0f;
    p.setParams(params);
    p.setTarget(0.1f, 0.0f, 1.0f);  // 7 px/s: less than a pixel per step
    run(p, 0.5);
    const auto [x, y] = run(p, 2.0);
    EXPECT_NEAR(x, 14, 1);
    EXPECT_EQ(y, 0);
}

TEST(PointerModelTest, ReleaseStopsImmediately) {
    PointerModel p;
    p.setTarget(1.0f, 1.0f, 1.0f);
    run(p, 0.3);
    p.setTarget(0.0f, 0.0f, 1.0f);
    const PointerModel::Delta d = p.step(0.004);
    EXPECT_TRUE(d.isZero());
    EXPECT_FALSE(p.active());
}

TEST(PointerModelTest, GainScalesSpeed) {
    PointerModel full;
    PointerModel slow;
    PointerParams params;
    params.compensateAndroidAccel = false;
    full.setParams(params);
    slow.setParams(params);
    full.setTarget(0.8f, 0.0f, 1.0f);
    slow.setTarget(0.8f, 0.0f, 0.35f);
    run(full, 0.5);
    run(slow, 0.5);
    const int fullX = run(full, 1.0).first;
    const int slowX = run(slow, 1.0).first;
    EXPECT_NEAR(static_cast<double>(slowX) / fullX, 0.35, 0.02);
}

TEST(PointerModelTest, AttackSmoothsFlicks) {
    PointerModel p;
    PointerParams params;
    params.compensateAndroidAccel = false;
    p.setParams(params);
    p.setTarget(1.0f, 0.0f, 1.0f);
    // A 20 ms flick moves noticeably less than 20 ms at full speed.
    const int x = run(p, 0.02).first;
    EXPECT_LT(x, static_cast<int>(params.topSpeed * 0.02f * 0.5f));
}

// --- ScrollModel --------------------------------------------------------

TEST(ScrollModelTest, FirstDetentIsImmediate) {
    ScrollModel s;
    s.setTarget(0.0f, 0.3f, 1.0f);
    EXPECT_EQ(s.step(0.004).y, 1);
    EXPECT_EQ(s.step(0.004).y, 0);
}

TEST(ScrollModelTest, RateFollowsDeflection) {
    ScrollModel s;
    s.setTarget(0.0f, -1.0f, 1.0f);
    int total = 0;
    for (int i = 0; i < 250; ++i) total += s.step(0.004).y;
    EXPECT_NEAR(total, -(1 + s.params().maxRate), 1.0);
}

TEST(ScrollModelTest, DominantAxisWins) {
    ScrollModel s;
    s.setTarget(0.3f, 0.9f, 1.0f);
    int x = 0;
    for (int i = 0; i < 100; ++i) x += s.step(0.004).x;
    EXPECT_EQ(x, 0);
}

TEST(ScrollModelTest, ReversalIsImmediate) {
    ScrollModel s;
    s.setTarget(0.0f, 1.0f, 1.0f);
    s.step(0.004);
    s.step(0.004);
    s.setTarget(0.0f, -1.0f, 1.0f);
    EXPECT_EQ(s.step(0.004).y, -1);
}

TEST(ScrollModelTest, StopsWhenCentered) {
    ScrollModel s;
    s.setTarget(0.0f, 1.0f, 1.0f);
    s.step(0.004);
    s.setTarget(0.0f, 0.0f, 1.0f);
    EXPECT_TRUE(s.step(0.004).isZero());
    EXPECT_FALSE(s.active());
}

// --- Buttons ------------------------------------------------------------

TEST(ButtonsTest, ParseNames) {
    EXPECT_EQ(parseButton("r1"), Button::R1);
    EXPECT_EQ(parseButton("Select"), Button::Select);
    EXPECT_EQ(parseButton("RB"), Button::R1);
    EXPECT_EQ(parseButton("fn"), Button::Mode);
    EXPECT_FALSE(parseButton("Z9").has_value());
    EXPECT_EQ(parseAction("Precision"), Action::Precision);
    EXPECT_FALSE(parseAction("jump").has_value());
    for (size_t i = 0; i < kButtonCount; ++i) {
        const Button b = static_cast<Button>(i);
        EXPECT_EQ(parseButton(buttonName(b)), b);
    }
}

TEST(ButtonsTest, OdroidStickClickQuirk) {
    KeyBits keys;
    keys.set(BTN_A);
    keys.set(KEY_BACK);
    keys.set(KEY_LEFTMETA);
    const ButtonMap odroid(keys);
    EXPECT_EQ(odroid.button(KEY_BACK), Button::L3);
    EXPECT_EQ(odroid.button(KEY_LEFTMETA), Button::R3);

    // A pad with real thumb buttons keeps KEY_BACK as whatever it is.
    keys.set(BTN_THUMBL);
    keys.set(BTN_THUMBR);
    const ButtonMap standard(keys);
    EXPECT_EQ(standard.button(BTN_THUMBL), Button::L3);
    EXPECT_FALSE(standard.button(KEY_BACK).has_value());
}

TEST(ButtonsTest, DefaultChordNeedsBothSticks) {
    KeyBits keys;
    keys.set(BTN_SELECT);
    keys.set(BTN_START);
    EXPECT_EQ(defaultToggleChord(ButtonMap(keys)), (std::vector<Button>{Button::Select, Button::Start}));
    keys.set(BTN_THUMBL);
    keys.set(BTN_THUMBR);
    EXPECT_EQ(defaultToggleChord(ButtonMap(keys)), (std::vector<Button>{Button::L3, Button::R3}));
}

// --- Chord --------------------------------------------------------------

struct ChordState {
    std::array<bool, kButtonCount> down{};
    void set(Button b, bool d) { down[index(b)] = d; }
};

TEST(ChordTest, FiresAfterHold) {
    Chord c;
    c.configure({Button::L3, Button::R3}, msToNanos(1000));
    ChordState s;
    s.set(Button::L3, true);
    c.update(s.down, 0, 0);
    EXPECT_FALSE(c.deadline().has_value());
    s.set(Button::R3, true);
    c.update(s.down, 0, msToNanos(100));
    EXPECT_EQ(c.deadline(), msToNanos(1100));
    EXPECT_FALSE(c.fire(msToNanos(1099)));
    EXPECT_TRUE(c.fire(msToNanos(1100)));
    EXPECT_FALSE(c.fire(msToNanos(5000)));  // once per hold
}

TEST(ChordTest, OtherKeysBlock) {
    Chord c;
    c.configure({Button::L3, Button::R3}, msToNanos(1000));
    ChordState s;
    s.set(Button::L3, true);
    s.set(Button::R3, true);
    c.update(s.down, 1, 0);
    EXPECT_FALSE(c.engaged());
    EXPECT_FALSE(c.fire(msToNanos(2000)));
}

TEST(ChordTest, RearmsOnlyAfterFullRelease) {
    Chord c;
    c.configure({Button::L3, Button::R3}, msToNanos(1000));
    ChordState s;
    s.set(Button::L3, true);
    s.set(Button::R3, true);
    c.update(s.down, 0, 0);
    EXPECT_TRUE(c.fire(msToNanos(1000)));
    // Letting go of one and pressing it again is not a new toggle.
    s.set(Button::R3, false);
    c.update(s.down, 0, msToNanos(1100));
    s.set(Button::R3, true);
    c.update(s.down, 0, msToNanos(1200));
    EXPECT_FALSE(c.fire(msToNanos(5000)));
    // Releasing everything re-arms it.
    s.set(Button::L3, false);
    s.set(Button::R3, false);
    c.update(s.down, 0, msToNanos(5100));
    s.set(Button::L3, true);
    s.set(Button::R3, true);
    c.update(s.down, 0, msToNanos(5200));
    EXPECT_TRUE(c.fire(msToNanos(6200)));
}

TEST(ChordTest, VetoLatches) {
    Chord c;
    c.configure({Button::L3, Button::R3}, msToNanos(1000));
    ChordState s;
    s.set(Button::L3, true);
    s.set(Button::R3, true);
    c.update(s.down, 0, 0);
    c.veto();
    EXPECT_FALSE(c.armed());
    EXPECT_FALSE(c.fire(msToNanos(2000)));
}

// --- Config -------------------------------------------------------------

TEST(ConfigTest, Parsers) {
    EXPECT_EQ(parsePercent("150", 10, 500), 1.5f);
    EXPECT_EQ(parsePercent(" 80% ", 10, 500), 0.8f);
    EXPECT_FALSE(parsePercent("5", 10, 500).has_value());
    EXPECT_FALSE(parsePercent("fast", 10, 500).has_value());
    EXPECT_EQ(parseFloat("2.5", 1, 4), 2.5f);
    EXPECT_FALSE(parseFloat("nan", 1, 4).has_value());
    EXPECT_EQ(parseInt("600", 300, 5000), 600);
    EXPECT_FALSE(parseInt("600.5", 300, 5000).has_value());
    EXPECT_EQ(parseBool("On"), true);
    EXPECT_EQ(parseBool("0"), false);
    EXPECT_FALSE(parseBool("maybe").has_value());
    EXPECT_EQ(parseSize("640x480"), std::make_pair(640, 480));
    EXPECT_FALSE(parseSize("640").has_value());
}

TEST(ConfigTest, ChordNeedsTwoDistinctButtons) {
    EXPECT_EQ(parseChord("L3+R3"), (std::vector<Button>{Button::L3, Button::R3}));
    EXPECT_EQ(parseChord("select + start + r1"),
              (std::vector<Button>{Button::Select, Button::Start, Button::R1}));
    EXPECT_EQ(parseChord("none"), std::vector<Button>{});
    EXPECT_FALSE(parseChord("R3").has_value());
    EXPECT_FALSE(parseChord("R3+R3").has_value());
    EXPECT_FALSE(parseChord("R3+Q").has_value());
}

TEST(ConfigTest, LoadsProperties) {
    const std::map<std::string, std::string> props = {
            {"persist.sys.joymouse.speed", "150"},
            {"persist.sys.joymouse.curve", "3"},
            {"persist.sys.joymouse.pointer_stick", "left"},
            {"persist.sys.joymouse.natural_scroll", "1"},
            {"persist.sys.joymouse.toggle", "SELECT+START"},
            {"persist.sys.joymouse.toggle_ms", "1500"},
            {"persist.sys.joymouse.btn_a", "pass"},
            {"persist.sys.joymouse.btn_x", "right"},
            {"persist.sys.joymouse.deadzone", "banana"},
            {"persist.sys.joymouse.btn_y", "jump"},
    };
    std::vector<std::string> warnings;
    const Config c = loadConfig(
            [&](const char* name) {
                const auto it = props.find(name);
                return it == props.end() ? std::string() : it->second;
            },
            &warnings);
    EXPECT_FLOAT_EQ(c.speed, 1.5f);
    EXPECT_FLOAT_EQ(c.curve, 3.0f);
    EXPECT_EQ(c.pointerStick, StickSide::Left);
    EXPECT_TRUE(c.naturalScroll);
    EXPECT_EQ(c.toggle, (std::vector<Button>{Button::Select, Button::Start}));
    EXPECT_EQ(c.toggleMs, 1500);
    EXPECT_FLOAT_EQ(c.deadzone, Config().deadzone);
    EXPECT_EQ(warnings.size(), 2u);

    const Bindings b = c.bindings();
    EXPECT_EQ(b[index(Button::A)], Action::Pass);
    EXPECT_EQ(b[index(Button::X)], Action::Right);
    EXPECT_EQ(b[index(Button::Y)], Action::Pass);
    EXPECT_EQ(b[index(Button::R1)], Action::Left);
}

TEST(ConfigTest, ButtonPropertyNames) {
    EXPECT_EQ(buttonProperty(Button::R1), "persist.sys.joymouse.btn_r1");
    EXPECT_EQ(buttonProperty(Button::Select), "persist.sys.joymouse.btn_select");
}

TEST(ConfigTest, Equality) {
    Config a;
    Config b;
    EXPECT_EQ(a, b);
    b.buttons[index(Button::B)] = Action::Back;
    EXPECT_NE(a, b);
}

}  // namespace
}  // namespace joymouse
