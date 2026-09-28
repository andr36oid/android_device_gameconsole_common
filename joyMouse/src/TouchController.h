#pragma once

#include <array>
#include <optional>
#include <vector>

#include "Config.h"
#include "Controller.h"
#include "Stick.h"
#include "Time.h"
#include "TouchProfile.h"
#include "TouchSurface.h"

namespace joymouse {

// Touch mode: while the app in front has a touch controls profile, the pad is
// grabbed and buttons and sticks become touches on a virtual touchscreen.
// Everything the profile doesn't use goes on to Android through a copy of the
// pad, like in mouse mode. FN, and any button pressed while FN is held, always
// goes through, so FN (Home) and the FN shortcuts keep working. Volume and
// power are separate input devices and never grabbed.
//
// Owns no file descriptors, like Controller. Mouse mode wins: while it is on,
// touch mode is suspended, so the two never drive the pad at the same time.
class TouchController {
public:
    enum class State { Off, Arming, On };

    static constexpr Nanos kGrabDelay = msToNanos(200);   // let Android add the devices first
    static constexpr Nanos kGrabRetry = msToNanos(250);
    static constexpr Nanos kTickPeriod = msToNanos(8);    // 125 Hz while a finger moves
    static constexpr Nanos kTapLength = msToNanos(50);    // long enough for a 20 fps game to see it
    static constexpr Nanos kAnchorDelay = msToNanos(24);  // finger rests on the joystick before moving
    static constexpr Nanos kSwipeTime = msToNanos(120);
    static constexpr Nanos kCameraIdle = msToNanos(150);  // stick at rest this long: lift the camera finger
    static constexpr float kRestThreshold = 0.35f;
    // Camera speed 1 moves the finger this many shorter screen sides per second at full tilt.
    static constexpr float kCameraBaseSpeed = 1.0f;
    static constexpr float kCameraCurve = 1.6f;

    TouchController(PadInfo pad, PadPort& padPort, OutputPort& touch, OutputPort& passthrough);

    // Stick axis correction comes from the joyMouse config, so both modes agree.
    void configure(const Config& config, const Environment& env, const TouchGeometry& geometry, Nanos now);
    // nullopt: no profile, touch mode off.
    void setProfile(std::optional<TouchProfile> profile, Nanos now);
    // Mouse mode on: touch mode steps back, and comes back when mouse mode ends.
    void setSuspended(bool suspended, Nanos now);

    void onInput(const InputEvent& ev, Nanos now);
    void onTimeout(Nanos now);
    std::optional<Nanos> nextDeadline() const;
    // Lifts every finger and gives the pad back; call before tearing down.
    void shutdown(Nanos now);

    State state() const { return state_; }
    bool active() const { return state_ != State::Off; }
    int layer() const { return layer_; }
    const TouchSurface& surface() const { return surface_; }
    const std::optional<TouchProfile>& profile() const { return profile_; }

private:
    enum class Route : uint8_t { Unset, Passthrough, Swallow, Shift, Control };

    struct KeyRoute {
        Route route = Route::Unset;
        const TouchControl* control = nullptr;
        int slot = -1;  // Hold: its finger
    };

    struct TimedFinger {  // taps and swipes
        int slot = -1;
        Nanos start = 0;
        Nanos end = 0;
        float fromX = 0, fromY = 0, toX = 0, toY = 0;
        bool swipe = false;
    };

    enum class StickPhase : uint8_t { Idle, Anchored, Moving, Lifting };

    struct StickState {
        const TouchControl* control = nullptr;
        StickPhase phase = StickPhase::Idle;
        int slot = -1;
        Nanos since = 0;       // phase start
        Nanos lastActive = 0;  // camera: last time the stick was out of the dead zone
        Nanos lastStep = 0;
        float x = 0, y = 0;    // camera: finger position
    };

    bool wanted() const;
    void update(Nanos now);
    void enter(Nanos now);
    void leave(Nanos now, const char* why);
    void maybeGrab(Nanos now);
    void releaseGestures(Nanos now);

    void handleKey(int code, int value, Nanos now);
    void handleKeyOn(int code, int value, Nanos now);
    void handleAbs(int code, int value, Nanos now);
    void resync(Nanos now);
    void setAbsReporting(bool enabled);

    void pressControl(int code, const TouchControl& c, Nanos now);
    void releaseControl(KeyRoute& r);
    void refreshLayer(Nanos now);

    StickVector vectorFor(StickSource s) const;
    bool sticksAtRest() const;
    void stepSticks(Nanos now);
    void stepStick(StickSource s, StickState& st, Nanos now);
    void endStick(StickState& st);
    void stepTimed(Nanos now);
    void syncStickPassthrough();
    bool stickMapped(StickSource s) const;

    std::pair<float, float> point(const TouchControl& c) const;
    float shortSide() const;
    bool needsTick() const;

    void queuePassthrough(uint16_t type, uint16_t code, int32_t value);
    void flushPassthrough();
    void flush();

    PadInfo pad_;
    PadPort& padPort_;
    OutputPort& touchOut_;
    OutputPort& passthrough_;
    ButtonMap buttonMap_;
    TouchSurface surface_;

    std::optional<TouchProfile> profile_;
    bool suspended_ = false;
    State state_ = State::Off;
    bool grabbed_ = false;
    bool absReporting_ = true;
    bool dropping_ = false;
    Nanos grabNotBefore_ = 0;
    bool grabCheckPending_ = false;

    KeyBits keysDown_;
    int keysDownCount_ = 0;
    AbsValues abs_{};
    Stick left_, right_;

    std::array<KeyRoute, KEY_CNT> routes_{};
    int shiftHeld_ = 0;
    int layer_ = 0;
    std::vector<TimedFinger> timed_;
    std::array<StickState, kStickSourceCount> sticks_{};
    bool ticking_ = false;
    Nanos nextTick_ = 0;

    std::vector<InputEvent> passthroughFrame_;
    KeyBits passthroughKeys_;
    AbsValues passthroughAbs_{};
};

}  // namespace joymouse
