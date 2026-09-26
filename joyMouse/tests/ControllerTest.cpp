#include <algorithm>
#include <memory>
#include <vector>

#include <gtest/gtest.h>

#include "Controller.h"

namespace joymouse {
namespace {

// The odroidgo2-style joypad of the R36S: stick clicks on KEY_BACK/KEY_LEFTMETA.
constexpr int kL3 = KEY_BACK;
constexpr int kR3 = KEY_LEFTMETA;
constexpr int kStickMax = 1800;

PadInfo r36sPad() {
    PadInfo info;
    info.name = "odroidgo2_joypad";
    info.id = {BUS_HOST, 0x484b, 0x1100, 0x0100};
    for (int code : {KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT, BTN_A, BTN_B, BTN_X, BTN_Y, BTN_TL,
                     BTN_TR, BTN_TL2, BTN_TR2, BTN_SELECT, BTN_START, BTN_MODE, kL3, kR3,
                     KEY_VOLUMEDOWN, KEY_VOLUMEUP}) {
        info.keys.set(static_cast<size_t>(code));
    }
    for (int code : {ABS_X, ABS_Y, ABS_RX, ABS_RY}) {
        input_absinfo abs{};
        abs.minimum = -kStickMax;
        abs.maximum = kStickMax;
        abs.fuzz = 32;
        abs.flat = 32;
        info.abs[static_cast<size_t>(code)] = abs;
    }
    return info;
}

struct FakePad : PadPort {
    bool grabbed = false;
    bool failGrab = false;
    bool absReporting = true;
    int grabCount = 0;
    KeyBits keys;
    AbsValues abs{};

    bool setGrabbed(bool g) override {
        if (g && failGrab) return false;
        grabbed = g;
        if (g) ++grabCount;
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
    int openCount = 0;
    std::vector<InputEvent> events;

    bool open() override {
        opened = true;
        ++openCount;
        return true;
    }
    void close() override { opened = false; }
    bool isOpen() const override { return opened; }
    bool send(const std::vector<InputEvent>& frame) override {
        EXPECT_TRUE(opened);
        EXPECT_FALSE(frame.empty());
        EXPECT_EQ(frame.back().type, EV_SYN);
        events.insert(events.end(), frame.begin(), frame.end());
        return true;
    }

    int sum(uint16_t type, uint16_t code) const {
        int total = 0;
        for (const InputEvent& e : events) {
            if (e.type == type && e.code == code) total += e.value;
        }
        return total;
    }
    int count(uint16_t type, uint16_t code) const {
        return static_cast<int>(std::count_if(events.begin(), events.end(), [&](const InputEvent& e) {
            return e.type == type && e.code == code;
        }));
    }
    // (code, value) of every key event, in order.
    std::vector<std::pair<int, int>> keys() const {
        std::vector<std::pair<int, int>> out;
        for (const InputEvent& e : events) {
            if (e.type == EV_KEY) out.emplace_back(e.code, e.value);
        }
        return out;
    }
    void clear() { events.clear(); }
};

struct FakeListener : ModeListener {
    std::vector<bool> changes;
    bool fullscreen = false;
    void onMouseModeChanged(bool active) override { changes.push_back(active); }
    bool inFullscreenApp() override { return fullscreen; }
};

using Keys = std::vector<std::pair<int, int>>;

class ControllerTest : public ::testing::Test {
protected:
    void SetUp() override {
        env.productDevice = "r36s";
        create(r36sPad());
    }

    void create(PadInfo info) {
        pad.keys.reset();
        pad.abs.fill(0);
        controller = std::make_unique<Controller>(std::move(info), pad, mouse, pass, &listener);
        controller->configure(config, env, now);
    }

    void reconfigure() { controller->configure(config, env, now); }

    void send(uint16_t type, int code, int value) {
        controller->onInput({type, static_cast<uint16_t>(code), value}, now);
    }
    void syn() { send(EV_SYN, SYN_REPORT, 0); }

    void key(int code, int value) {
        pad.keys.set(static_cast<size_t>(code), value != 0);
        send(EV_KEY, code, value);
        syn();
    }
    void tap(int code, Nanos hold = msToNanos(80)) {
        key(code, 1);
        advance(hold);
        key(code, 0);
    }
    void abs(int code, int value) {
        pad.abs[static_cast<size_t>(code)] = value;
        send(EV_ABS, code, value);
        syn();
    }

    // Runs every timer due until now + d, like the daemon's loop would.
    void advance(Nanos d) {
        const Nanos end = now + d;
        for (int guard = 0; guard < 1000000; ++guard) {
            const auto next = controller->nextDeadline();
            if (!next || *next > end) break;
            now = std::max(now, *next);
            controller->onTimeout(now);
        }
        now = end;
        controller->onTimeout(now);
    }

    void holdToggle() {
        key(kL3, 1);
        key(kR3, 1);
        advance(msToNanos(config.toggleMs + 50));
        key(kL3, 0);
        key(kR3, 0);
    }

    void enterMouseMode() {
        holdToggle();
        advance(msToNanos(400));
        ASSERT_EQ(controller->mode(), Mode::On);
        mouse.clear();
        pass.clear();
    }

    Config config;
    Environment env;
    FakePad pad;
    FakeOutput mouse;
    FakeOutput pass;
    FakeListener listener;
    std::unique_ptr<Controller> controller;
    Nanos now = kNanosPerSec;
};

// --- Switching ------------------------------------------------------------

TEST_F(ControllerTest, StartsOffWithStickEventsFiltered) {
    EXPECT_EQ(controller->mode(), Mode::Off);
    EXPECT_FALSE(pad.absReporting);
    EXPECT_FALSE(mouse.opened);
}

TEST_F(ControllerTest, ToggleChordTurnsMouseModeOn) {
    key(kL3, 1);
    key(kR3, 1);
    advance(msToNanos(900));
    EXPECT_EQ(controller->mode(), Mode::Off);  // not yet: 1 s hold
    advance(msToNanos(150));
    EXPECT_EQ(controller->mode(), Mode::Arming);
    EXPECT_EQ(listener.changes, std::vector<bool>{true});
    EXPECT_TRUE(mouse.opened);
    EXPECT_TRUE(pass.opened);
    // The pad is only taken away from Android once it's idle.
    EXPECT_FALSE(pad.grabbed);
    key(kL3, 0);
    key(kR3, 0);
    advance(msToNanos(300));
    EXPECT_TRUE(pad.grabbed);
    EXPECT_EQ(controller->mode(), Mode::On);
    // Pressing the chord never clicked.
    EXPECT_EQ(mouse.count(EV_KEY, BTN_LEFT), 0);
}

TEST_F(ControllerTest, ShortChordDoesNothing) {
    key(kL3, 1);
    key(kR3, 1);
    advance(msToNanos(500));
    key(kR3, 0);
    advance(msToNanos(2000));
    EXPECT_EQ(controller->mode(), Mode::Off);
}

TEST_F(ControllerTest, ChordWithOtherButtonsDoesNothingInFullscreenApps) {
    listener.fullscreen = true;
    key(BTN_TR2, 1);  // e.g. accelerating in a racing game
    key(kL3, 1);
    key(kR3, 1);
    advance(msToNanos(3000));
    EXPECT_EQ(controller->mode(), Mode::Off);
}

TEST_F(ControllerTest, ChordWhileSteeringDoesNothingInFullscreenApps) {
    listener.fullscreen = true;
    key(kL3, 1);
    key(kR3, 1);
    // Stick events are watched during the hold...
    EXPECT_TRUE(pad.absReporting);
    abs(ABS_X, kStickMax);  // ...and pushing a stick means it's gameplay.
    advance(msToNanos(3000));
    EXPECT_EQ(controller->mode(), Mode::Off);
    EXPECT_FALSE(pad.absReporting);
    // It stays ignored until the buttons are let go.
    abs(ABS_X, 0);
    advance(msToNanos(3000));
    EXPECT_EQ(controller->mode(), Mode::Off);
}

TEST_F(ControllerTest, StickAlreadyPushedVetoesChordInFullscreenApps) {
    listener.fullscreen = true;
    pad.abs[ABS_RY] = -kStickMax;  // not reported while off, but read when the chord starts
    key(kL3, 1);
    key(kR3, 1);
    advance(msToNanos(3000));
    EXPECT_EQ(controller->mode(), Mode::Off);
}

TEST_F(ControllerTest, ChordIgnoresOtherButtonsOutsideFullscreenApps) {
    key(BTN_TR2, 1);
    key(kL3, 1);
    key(kR3, 1);
    advance(msToNanos(1100));
    EXPECT_NE(controller->mode(), Mode::Off);
}

TEST_F(ControllerTest, ChordIgnoresSteeringOutsideFullscreenApps) {
    key(kL3, 1);
    key(kR3, 1);
    abs(ABS_X, kStickMax);
    advance(msToNanos(1100));
    EXPECT_NE(controller->mode(), Mode::Off);
}

TEST_F(ControllerTest, SticksTiltedByPressingThemDontVetoInFullscreenApps) {
    listener.fullscreen = true;
    key(kL3, 1);
    key(kR3, 1);
    abs(ABS_X, kStickMax * 7 / 10);
    abs(ABS_RY, -kStickMax * 7 / 10);
    advance(msToNanos(1100));
    EXPECT_NE(controller->mode(), Mode::Off);
}

TEST_F(ControllerTest, ToggleCanBeDisabled) {
    config.toggleEnabled = false;
    reconfigure();
    key(kL3, 1);
    key(kR3, 1);
    advance(msToNanos(3000));
    EXPECT_EQ(controller->mode(), Mode::Off);
    EXPECT_FALSE(controller->chord().enabled());
}

TEST_F(ControllerTest, ChordTurnsMouseModeOffAndCleansUp) {
    enterMouseMode();
    key(BTN_B, 1);
    key(BTN_B, 0);
    holdToggle();
    EXPECT_EQ(controller->mode(), Mode::Off);
    EXPECT_FALSE(pad.grabbed);
    EXPECT_FALSE(mouse.opened);
    EXPECT_FALSE(pass.opened);
    EXPECT_FALSE(pad.absReporting);
    EXPECT_EQ(listener.changes, (std::vector<bool>{true, false}));
    EXPECT_EQ(mouse.count(EV_KEY, BTN_LEFT), 0);
}

TEST_F(ControllerTest, ExitChordWhilePointingDoesNothingInFullscreenApps) {
    listener.fullscreen = true;
    enterMouseMode();
    key(kL3, 1);
    key(kR3, 1);
    abs(ABS_RX, kStickMax);
    advance(msToNanos(3000));
    EXPECT_EQ(controller->mode(), Mode::On);
}

TEST_F(ControllerTest, RequestedOnAndOff) {
    controller->setActive(true, now);
    advance(msToNanos(300));
    EXPECT_EQ(controller->mode(), Mode::On);
    key(BTN_B, 1);  // held while switching off: released cleanly
    controller->setActive(false, now);
    EXPECT_EQ(controller->mode(), Mode::Off);
    EXPECT_EQ(pass.keys(), (Keys{{BTN_B, 1}, {BTN_B, 0}}));
}

TEST_F(ControllerTest, GrabWaitsForIdlePad) {
    key(BTN_B, 1);
    controller->setActive(true, now);
    advance(msToNanos(1000));
    EXPECT_EQ(controller->mode(), Mode::Arming);
    EXPECT_FALSE(pad.grabbed);
    key(BTN_B, 0);
    EXPECT_TRUE(pad.grabbed);

    controller->setActive(false, now);
    abs(ABS_X, kStickMax);  // left stick pushed
    controller->setActive(true, now);
    advance(msToNanos(1000));
    EXPECT_FALSE(pad.grabbed);
    abs(ABS_X, 0);
    EXPECT_TRUE(pad.grabbed);
}

TEST_F(ControllerTest, GrabRetriesWhenBusy) {
    pad.failGrab = true;
    controller->setActive(true, now);
    advance(msToNanos(1000));
    EXPECT_EQ(controller->mode(), Mode::Arming);
    pad.failGrab = false;
    advance(msToNanos(300));
    EXPECT_EQ(controller->mode(), Mode::On);
}

TEST_F(ControllerTest, PointerIsRevealedAfterEntering) {
    controller->setActive(true, now);
    advance(msToNanos(1000));
    EXPECT_EQ(mouse.sum(EV_REL, REL_X), 0);  // nudged and back
    EXPECT_EQ(mouse.count(EV_REL, REL_X), 2);
}

// --- Pointer --------------------------------------------------------------

TEST_F(ControllerTest, FullDeflectionSpeed) {
    enterMouseMode();
    abs(ABS_RX, kStickMax);
    advance(msToNanos(1000));
    // 640x480: 0.9 diagonals/s = 720 px/s on screen, less what Android adds.
    const float emitted = PointerModel::androidAccelInverse(720.0f);
    EXPECT_NEAR(mouse.sum(EV_REL, REL_X), emitted, emitted * 0.06f);
    EXPECT_EQ(mouse.sum(EV_REL, REL_Y), 0);
}

TEST_F(ControllerTest, SmallDeflectionIsSlow) {
    enterMouseMode();
    abs(ABS_RX, kStickMax * 20 / 100);  // 20% of the range
    advance(msToNanos(1000));
    const int moved = mouse.sum(EV_REL, REL_X);
    EXPECT_GT(moved, 5);
    EXPECT_LT(moved, 80);
}

TEST_F(ControllerTest, LettingGoStopsThePointer) {
    enterMouseMode();
    abs(ABS_RX, kStickMax);
    advance(msToNanos(300));
    abs(ABS_RX, 0);
    advance(msToNanos(5));
    const int stopped = mouse.sum(EV_REL, REL_X);
    advance(msToNanos(500));
    EXPECT_EQ(mouse.sum(EV_REL, REL_X), stopped);
    EXPECT_FALSE(controller->nextDeadline().has_value());  // idle: no timers
}

TEST_F(ControllerTest, RightStickYDefaults) {
    enterMouseMode();
    abs(ABS_RY, kStickMax);  // R36S: positive is down
    advance(msToNanos(200));
    EXPECT_GT(mouse.sum(EV_REL, REL_Y), 0);

    env.productDevice = "rg351p";  // everything else reports it reversed
    reconfigure();
    mouse.clear();
    abs(ABS_RY, 0);
    abs(ABS_RY, kStickMax);
    advance(msToNanos(200));
    EXPECT_LT(mouse.sum(EV_REL, REL_Y), 0);
}

TEST_F(ControllerTest, PointerInversionSetting) {
    config.pointerInvertX = true;
    reconfigure();
    enterMouseMode();
    abs(ABS_RX, kStickMax);
    advance(msToNanos(200));
    EXPECT_LT(mouse.sum(EV_REL, REL_X), 0);
}

TEST_F(ControllerTest, PrecisionSlowsDown) {
    enterMouseMode();
    abs(ABS_RX, kStickMax);
    advance(msToNanos(500));
    mouse.clear();
    advance(msToNanos(500));
    const int normal = mouse.sum(EV_REL, REL_X);
    key(BTN_TR2, 1);
    advance(msToNanos(300));
    mouse.clear();
    advance(msToNanos(500));
    const int precise = mouse.sum(EV_REL, REL_X);
    EXPECT_LT(precise, normal * 0.45);
    EXPECT_GT(precise, normal * 0.2);
}

TEST_F(ControllerTest, SwappedSticks) {
    config.pointerStick = StickSide::Left;
    reconfigure();
    enterMouseMode();
    abs(ABS_X, kStickMax);
    abs(ABS_RY, -kStickMax);
    advance(msToNanos(300));
    EXPECT_GT(mouse.sum(EV_REL, REL_X), 0);
    EXPECT_GT(mouse.sum(EV_REL, REL_WHEEL), 0);
}

// --- Buttons --------------------------------------------------------------

TEST_F(ControllerTest, ShoulderClicks) {
    enterMouseMode();
    tap(BTN_TR);
    tap(BTN_TL);
    EXPECT_EQ(mouse.keys(), (Keys{{BTN_LEFT, 1}, {BTN_LEFT, 0}, {BTN_RIGHT, 1}, {BTN_RIGHT, 0}}));
    EXPECT_TRUE(pass.events.empty());
}

TEST_F(ControllerTest, UnmappedButtonsPassThrough) {
    enterMouseMode();
    tap(BTN_B);
    tap(KEY_UP);
    EXPECT_EQ(pass.keys(), (Keys{{BTN_B, 1}, {BTN_B, 0}, {KEY_UP, 1}, {KEY_UP, 0}}));
    EXPECT_TRUE(mouse.keys().empty());
}

TEST_F(ControllerTest, RemappedButtonsActLikeTheirTarget) {
    // R1 and B swapped in Settings > Button mapping, X disabled.
    config.buttonRemap = "311:BUTTON_B, 305:BUTTON_R1, 307:NONE";
    reconfigure();
    enterMouseMode();
    tap(BTN_B);   // acts as R1: left click
    tap(BTN_TR);  // acts as B: Android gets the key and remaps it
    tap(BTN_X);   // disabled: left to Android, which drops it
    EXPECT_EQ(mouse.keys(), (Keys{{BTN_LEFT, 1}, {BTN_LEFT, 0}}));
    EXPECT_EQ(pass.keys(), (Keys{{BTN_TR, 1}, {BTN_TR, 0}, {BTN_X, 1}, {BTN_X, 0}}));
}

TEST_F(ControllerTest, ClassicModeLeavesThePadToAndroid) {
    config.classic = true;
    reconfigure();
    holdToggle();
    EXPECT_EQ(controller->mode(), Mode::On);
    EXPECT_FALSE(pad.grabbed);
    EXPECT_FALSE(pass.opened);
    EXPECT_TRUE(mouse.opened);
    EXPECT_FALSE(controller->scrollStick().has_value());
    // Only the right stick click also clicks; everything reaches Android directly.
    tap(BTN_TR);
    tap(BTN_A);
    tap(kR3);
    EXPECT_EQ(mouse.keys(), (Keys{{BTN_LEFT, 1}, {BTN_LEFT, 0}}));
    EXPECT_TRUE(pass.events.empty());
    // The right stick moves the pointer, the left one does nothing here.
    abs(ABS_X, kStickMax);
    advance(msToNanos(300));
    EXPECT_EQ(mouse.count(EV_REL, REL_WHEEL), 0);
    abs(ABS_RX, kStickMax);
    advance(msToNanos(300));
    EXPECT_GT(mouse.sum(EV_REL, REL_X), 0);
    holdToggle();
    EXPECT_EQ(controller->mode(), Mode::Off);
    EXPECT_FALSE(mouse.opened);
}

TEST_F(ControllerTest, SmartButtonFollowsThePointer) {
    enterMouseMode();
    // Pointer just shown: A clicks.
    tap(BTN_A);
    EXPECT_EQ(mouse.keys(), (Keys{{BTN_LEFT, 1}, {BTN_LEFT, 0}}));
    // Navigating with the d-pad hides the pointer: A confirms instead.
    tap(KEY_DOWN);
    tap(BTN_A);
    EXPECT_EQ(pass.keys(), (Keys{{KEY_DOWN, 1}, {KEY_DOWN, 0}, {BTN_A, 1}, {BTN_A, 0}}));
    // Moving the pointer brings clicking back.
    abs(ABS_RX, kStickMax);
    advance(msToNanos(100));
    abs(ABS_RX, 0);
    mouse.clear();
    tap(BTN_A);
    EXPECT_EQ(mouse.keys(), (Keys{{BTN_LEFT, 1}, {BTN_LEFT, 0}}));
}

TEST_F(ControllerTest, SmartButtonReleasesWhereItPressed) {
    enterMouseMode();
    key(BTN_A, 1);  // pointer active: left button down
    tap(KEY_DOWN);  // pointer no longer "in use"...
    key(BTN_A, 0);  // ...but the release still goes to the mouse
    EXPECT_EQ(mouse.keys(), (Keys{{BTN_LEFT, 1}, {BTN_LEFT, 0}}));
    EXPECT_EQ(pass.keys(), (Keys{{KEY_DOWN, 1}, {KEY_DOWN, 0}}));
}

TEST_F(ControllerTest, StickClickTapClicksWithoutMoving) {
    enterMouseMode();
    key(kR3, 1);
    abs(ABS_RX, kStickMax / 3);  // pressing the stick nudges it
    advance(msToNanos(100));
    EXPECT_TRUE(mouse.events.empty());  // nothing yet: tap or hold?
    abs(ABS_RX, 0);
    key(kR3, 0);
    advance(msToNanos(100));
    EXPECT_EQ(mouse.keys(), (Keys{{BTN_LEFT, 1}, {BTN_LEFT, 0}}));
    EXPECT_EQ(mouse.sum(EV_REL, REL_X), 0);
}

TEST_F(ControllerTest, StickClickHoldDrags) {
    enterMouseMode();
    key(kR3, 1);
    advance(msToNanos(300));
    EXPECT_EQ(mouse.keys(), (Keys{{BTN_LEFT, 1}}));
    abs(ABS_RX, kStickMax);
    advance(msToNanos(300));
    EXPECT_GT(mouse.sum(EV_REL, REL_X), 50);
    abs(ABS_RX, 0);
    key(kR3, 0);
    EXPECT_EQ(mouse.keys(), (Keys{{BTN_LEFT, 1}, {BTN_LEFT, 0}}));
}

TEST_F(ControllerTest, ClickFreezeKeepsClicksStill) {
    enterMouseMode();
    abs(ABS_RX, kStickMax / 3);
    advance(msToNanos(200));
    key(BTN_TR, 1);
    mouse.clear();
    advance(msToNanos(config.clickFreezeMs - 5));
    EXPECT_EQ(mouse.sum(EV_REL, REL_X), 0);
    advance(msToNanos(200));
    EXPECT_GT(mouse.sum(EV_REL, REL_X), 0);  // then dragging works
    key(BTN_TR, 0);
}

TEST_F(ControllerTest, OneMouseButtonFromTwoKeys) {
    enterMouseMode();
    key(BTN_TR, 1);
    key(BTN_A, 1);
    key(BTN_TR, 0);
    EXPECT_EQ(mouse.keys(), (Keys{{BTN_LEFT, 1}}));  // still held by A
    key(BTN_A, 0);
    EXPECT_EQ(mouse.keys(), (Keys{{BTN_LEFT, 1}, {BTN_LEFT, 0}}));
}

TEST_F(ControllerTest, Remapping) {
    config.buttons[index(Button::R1)] = Action::Pass;
    config.buttons[index(Button::X)] = Action::Middle;
    config.buttons[index(Button::B)] = Action::None;
    reconfigure();
    enterMouseMode();
    tap(BTN_TR);
    tap(BTN_X);
    tap(BTN_B);
    EXPECT_EQ(pass.keys(), (Keys{{BTN_TR, 1}, {BTN_TR, 0}}));
    EXPECT_EQ(mouse.keys(), (Keys{{BTN_MIDDLE, 1}, {BTN_MIDDLE, 0}}));
}

TEST_F(ControllerTest, OtherToggleChord) {
    config.toggle = std::vector<Button>{Button::Select, Button::Start};
    reconfigure();
    key(BTN_SELECT, 1);
    key(BTN_START, 1);
    advance(msToNanos(1100));
    key(BTN_SELECT, 0);
    key(BTN_START, 0);
    advance(msToNanos(400));
    EXPECT_EQ(controller->mode(), Mode::On);
    // Now L3/R3 are ordinary buttons again: R3 clicks right away.
    key(kR3, 1);
    EXPECT_EQ(mouse.keys(), (Keys{{BTN_LEFT, 1}}));
    key(kR3, 0);
    // And tapping a chord button alone still does its own job.
    pass.clear();
    tap(BTN_START);
    advance(msToNanos(100));
    EXPECT_EQ(pass.keys(), (Keys{{BTN_START, 1}, {BTN_START, 0}}));
}

// --- Scrolling ----------------------------------------------------------

TEST_F(ControllerTest, LeftStickScrolls) {
    enterMouseMode();
    abs(ABS_Y, -kStickMax);  // up
    advance(msToNanos(5));
    EXPECT_EQ(mouse.sum(EV_REL, REL_WHEEL), 1);  // first notch right away
    advance(msToNanos(1000));
    EXPECT_NEAR(mouse.sum(EV_REL, REL_WHEEL), 15, 2);
    EXPECT_EQ(mouse.sum(EV_REL, REL_X), 0);
    EXPECT_TRUE(pass.events.empty());  // Android doesn't see the stick
    abs(ABS_Y, 0);
    abs(ABS_X, kStickMax);
    advance(msToNanos(500));
    EXPECT_GT(mouse.sum(EV_REL, REL_HWHEEL), 0);
}

TEST_F(ControllerTest, NaturalScrolling) {
    config.naturalScroll = true;
    reconfigure();
    enterMouseMode();
    abs(ABS_Y, -kStickMax);
    advance(msToNanos(300));
    EXPECT_LT(mouse.sum(EV_REL, REL_WHEEL), 0);
}

TEST_F(ControllerTest, ScrollButtonTurnsPointerStickIntoWheel) {
    enterMouseMode();
    key(BTN_TL2, 1);
    abs(ABS_RY, kStickMax);  // down
    advance(msToNanos(500));
    EXPECT_LT(mouse.sum(EV_REL, REL_WHEEL), 0);
    EXPECT_EQ(mouse.sum(EV_REL, REL_Y), 0);
    key(BTN_TL2, 0);
    advance(msToNanos(300));
    EXPECT_GT(mouse.sum(EV_REL, REL_Y), 0);
}

TEST_F(ControllerTest, NoScrollingBeforeTheGrab) {
    controller->setActive(true, now);
    abs(ABS_Y, -kStickMax);  // Android still sees this stick
    advance(msToNanos(150));
    EXPECT_EQ(mouse.count(EV_REL, REL_WHEEL), 0);
}

// --- Robustness -----------------------------------------------------------

TEST_F(ControllerTest, ResyncAfterDroppedEvents) {
    enterMouseMode();
    pad.keys.set(BTN_B);  // pressed while the buffer overflowed
    send(EV_SYN, SYN_DROPPED, 0);
    send(EV_KEY, BTN_Y, 1);  // garbage until the next report
    syn();
    EXPECT_EQ(pass.keys(), (Keys{{BTN_B, 1}}));
}

TEST_F(ControllerTest, SingleStickPad) {
    PadInfo info = r36sPad();
    info.abs[ABS_RX].reset();
    info.abs[ABS_RY].reset();
    info.keys.reset(kR3);
    create(std::move(info));
    EXPECT_EQ(controller->pointerStick(), StickSide::Left);
    EXPECT_FALSE(controller->scrollStick().has_value());
    // No R3: the default toggle becomes Select + Start.
    EXPECT_EQ(controller->chord().members(), (std::vector<Button>{Button::Select, Button::Start}));
    controller->setActive(true, now);
    advance(msToNanos(400));
    abs(ABS_X, kStickMax);
    advance(msToNanos(300));
    EXPECT_GT(mouse.sum(EV_REL, REL_X), 50);
}

TEST_F(ControllerTest, AutorepeatOnlyForPassThrough) {
    enterMouseMode();
    key(BTN_B, 1);
    send(EV_KEY, BTN_B, 2);
    syn();
    key(BTN_B, 0);
    key(BTN_TR, 1);
    send(EV_KEY, BTN_TR, 2);
    syn();
    key(BTN_TR, 0);
    EXPECT_EQ(pass.keys(), (Keys{{BTN_B, 1}, {BTN_B, 2}, {BTN_B, 0}}));
    EXPECT_EQ(mouse.keys(), (Keys{{BTN_LEFT, 1}, {BTN_LEFT, 0}}));
}

}  // namespace
}  // namespace joymouse
