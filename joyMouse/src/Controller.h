#pragma once

#include <array>
#include <cstdint>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include <linux/input.h>

#include "Buttons.h"
#include "Chord.h"
#include "Config.h"
#include "PointerModel.h"
#include "ScrollModel.h"
#include "Stick.h"
#include "Time.h"

namespace joymouse {

struct InputEvent {
    uint16_t type = 0;
    uint16_t code = 0;
    int32_t value = 0;
};

using AbsValues = std::array<int32_t, ABS_CNT>;

// Static description of the physical pad.
struct PadInfo {
    std::string name;
    input_id id{};
    KeyBits keys;
    std::array<std::optional<input_absinfo>, ABS_CNT> abs;

    bool hasAbs(int code) const;
    AxisRange range(int code) const;
    int32_t center(int code) const;
};

// The physical pad.
class PadPort {
public:
    virtual ~PadPort() = default;
    // While grabbed, Android no longer receives anything from the pad.
    virtual bool setGrabbed(bool grabbed) = 0;
    // Stick events are only needed in mouse mode; filtering them in the kernel
    // otherwise saves a wakeup per stick update while gaming.
    virtual void setAbsReporting(bool enabled) = 0;
    virtual bool readState(KeyBits* keys, AbsValues* abs) = 0;
};

// A virtual input device.
class OutputPort {
public:
    virtual ~OutputPort() = default;
    virtual bool open() = 0;
    virtual void close() = 0;
    virtual bool isOpen() const = 0;
    // `events` ends with SYN_REPORT.
    virtual bool send(const std::vector<InputEvent>& events) = 0;
};

class ModeListener {
public:
    virtual ~ModeListener() = default;
    virtual void onMouseModeChanged(bool active) = 0;
    // Whether a fullscreen app, usually a game, is on screen. The toggle chord
    // is stricter there.
    virtual bool inFullscreenApp() { return false; }
};

struct Environment {
    int displayWidth = 640;
    int displayHeight = 480;
    std::string productDevice;  // ro.product.device
};

enum class Mode {
    Off,     // the pad goes straight to Android
    Arming,  // virtual devices exist, waiting for the pad to be idle to grab it
    On,      // pad grabbed: sticks and mapped buttons drive the mouse, the rest passes through
};

// Mouse mode state machine. Owns no file descriptors: the physical pad and
// the virtual devices are reached through the ports, and time is passed in,
// so everything here runs the same under test.
//
// Mouse mode brings up two virtual devices: a mouse, and a copy of the pad
// that carries every input mouse mode doesn't use. Grabbing the real pad and
// replaying the rest through the copy keeps Android from seeing both a click
// and a button press, or a scroll and a d-pad move, for the same input.
// Removing the mouse again is also what makes Android hide its pointer.
class Controller {
public:
    static constexpr Nanos kGrabDelay = msToNanos(200);     // let Android open the copy first
    static constexpr Nanos kGrabRetry = msToNanos(250);
    static constexpr Nanos kRevealDelay = msToNanos(300);
    static constexpr Nanos kRevealReturn = msToNanos(50);
    static constexpr Nanos kHoldDecision = msToNanos(250);  // stick click: tap or hold
    static constexpr Nanos kTapLength = msToNanos(40);
    static constexpr Nanos kStickSettle = msToNanos(150);   // spring back after a stick click
    static constexpr Nanos kPointerIdle = msToNanos(15000); // Android fades the pointer then
    static constexpr float kRestThreshold = 0.35f;
    // In a fullscreen app, a stick pushed this far while the toggle chord is
    // held means the buttons are part of gameplay, not a deliberate toggle.
    // Kept high: the R36S sticks are small and tilt when pressed.
    static constexpr float kSteerThreshold = 0.9f;
    // Top speed at 100 %: this many screen diagonals per second.
    static constexpr float kTopSpeedPerDiagonal = 0.9f;

    Controller(PadInfo pad, PadPort& padPort, OutputPort& mouse, OutputPort& passthrough,
               ModeListener* listener);

    void configure(const Config& config, const Environment& env, Nanos now);

    void onInput(const InputEvent& ev, Nanos now);
    void setActive(bool active, Nanos now);
    void onTimeout(Nanos now);
    std::optional<Nanos> nextDeadline() const;
    // Leaves mouse mode and releases everything; call before tearing down.
    void shutdown(Nanos now);

    Mode mode() const { return mode_; }
    bool active() const { return mode_ != Mode::Off; }
    std::optional<StickSide> pointerStick() const { return pointerSide_; }
    std::optional<StickSide> scrollStick() const { return scrollSide_; }
    const Chord& chord() const { return chord_; }
    const PointerModel& pointerModel() const { return pointer_; }

private:
    enum class Route : uint8_t { Unset, Passthrough, Swallow, Mouse, Precision, Scroll };
    enum MouseButton : uint8_t { kLeftButton, kRightButton, kMiddleButton, kSideButton, kExtraButton,
                                 kMouseButtonCount };

    struct KeyRoute {
        Route route = Route::Unset;
        uint8_t mouseButton = 0;
    };

    // Chord members act on release (tap) or after kHoldDecision (hold), so
    // pressing the chord never clicks.
    struct Member {
        bool down = false;
        bool pending = false;
        bool held = false;
        Nanos since = 0;
        int code = -1;
        KeyRoute route;
    };

    struct DelayedRelease {
        Nanos at = 0;
        int code = -1;
        KeyRoute route;
    };

    void enter(Nanos now, const char* why);
    void leave(Nanos now, const char* why);
    void maybeGrab(Nanos now);

    void handleKey(int code, int value, Nanos now);
    void handleKeyOn(int code, int value, std::optional<Button> button, Nanos now);
    void handleMember(Button b, int code, bool down, Nanos now);
    void handleAbs(int code, int value, Nanos now);
    void resync(Nanos now);
    void refreshAbs();
    void refreshChord(Nanos now);
    void checkSteering();
    void setAbsReporting(bool enabled);
    bool memberHoldAllowed() const { return chord_.members().size() > 1; }

    KeyRoute routeFor(Action action, Nanos now) const;
    void press(int code, const KeyRoute& r, Nanos now);
    void release(int code, const KeyRoute& r, Nanos now);
    void tap(int code, const KeyRoute& r, Nanos now);
    void cancelMember(Member& m, Nanos now);

    void setMouseButton(uint8_t button, bool down, Nanos now);
    void sendMouse(std::vector<InputEvent> events);
    void queuePassthrough(uint16_t type, uint16_t code, int32_t value);
    void flushPassthrough();
    void reveal(Nanos now);

    Stick* stickFor(std::optional<StickSide> side);
    std::optional<std::pair<StickSide, int>> stickAxis(int absCode) const;
    bool sticksAtRest() const;
    bool pointerFrozen(Nanos now) const;
    bool pointerInUse(Nanos now) const;
    void markPointerUsed(Nanos now);
    void computeTargets(Nanos now);
    void updateMotion(Nanos now);
    void advanceMotion(Nanos now);
    void stepMotion(Nanos now);
    void tick(Nanos now);

    PadInfo pad_;
    PadPort& padPort_;
    OutputPort& mouse_;
    OutputPort& passthrough_;
    // What each physical key code acts as after the hardware button remap.
    static constexpr int8_t kNotRemapped = -1;
    static constexpr int8_t kNotAButton = -2;  // disabled, or not a gamepad button
    Action bindingFor(int code, std::optional<Button> button) const;
    std::array<int8_t, KEY_CNT> remap_;

    ModeListener* listener_;
    // The chord is checked strictly (other keys and steering cancel it) while
    // a fullscreen app is on screen. Looked up when a hold starts.
    bool strictChord_ = false;
    ButtonMap buttonMap_;

    Bindings bindings_ = defaultBindings();
    Chord chord_;
    Mode mode_ = Mode::Off;
    bool grabbed_ = false;
    bool absReporting_ = true;

    // Physical pad state.
    KeyBits keysDown_;
    int keysDownCount_ = 0;
    std::array<int, kButtonCount> buttonDownCount_{};
    AbsValues abs_{};
    bool dropping_ = false;

    // Sticks and motion.
    Stick left_, right_;
    std::optional<StickSide> pointerSide_, scrollSide_;
    std::optional<Button> pointerClick_;
    PointerModel pointer_;
    ScrollModel scroll_;
    float precisionGain_ = 0.35f;
    bool pointerInvertX_ = false;
    bool pointerInvertY_ = false;
    bool naturalScroll_ = false;
    Nanos tickPeriod_ = msToNanos(4);
    Nanos clickFreeze_ = msToNanos(60);
    bool ticking_ = false;
    Nanos lastTick_ = 0;
    Nanos nextTick_ = 0;
    Nanos freezeUntil_ = 0;

    // Routing while grabbed.
    std::array<KeyRoute, KEY_CNT> routes_{};
    std::array<Member, kButtonCount> members_{};
    std::vector<DelayedRelease> delayedReleases_;
    std::array<int, kMouseButtonCount> mouseButtonCount_{};
    int precisionHeld_ = 0;
    int scrollHeld_ = 0;
    bool pointerActive_ = false;
    Nanos lastPointerUse_ = 0;

    // What the pass-through copy currently reports.
    std::vector<InputEvent> passthroughFrame_;
    KeyBits passthroughKeys_;
    AbsValues passthroughAbs_{};

    Nanos grabNotBefore_ = 0;
    bool grabCheckPending_ = false;
    int revealPhase_ = 0;
    Nanos revealAt_ = 0;
    bool movedSinceEnter_ = false;
};

}  // namespace joymouse
