#include "TouchController.h"

#include <algorithm>
#include <cmath>

#include "Log.h"

#ifndef BTN_DPAD_UP
#define BTN_DPAD_UP 0x220
#define BTN_DPAD_DOWN 0x221
#define BTN_DPAD_LEFT 0x222
#define BTN_DPAD_RIGHT 0x223
#endif

namespace joymouse {

namespace {

// FN on the handhelds: KEY_HOMEPAGE, Home in the key layout. PhoneWindowManager
// runs the FN shortcuts off this scan code.
constexpr int kFnCode = KEY_HOMEPAGE;

bool isDpadButton(Button b) {
    return b == Button::Up || b == Button::Down || b == Button::Left || b == Button::Right;
}

std::optional<StickSource> sourceOfAxis(int code) {
    switch (code) {
        case ABS_X:
        case ABS_Y:
            return StickSource::Left;
        case ABS_RX:
        case ABS_RY:
            return StickSource::Right;
        case ABS_HAT0X:
        case ABS_HAT0Y:
            return StickSource::Dpad;
        default:
            return std::nullopt;
    }
}

constexpr int kSourceAxes[kStickSourceCount][2] = {
        {ABS_X, ABS_Y},
        {ABS_RX, ABS_RY},
        {ABS_HAT0X, ABS_HAT0Y},
};

}  // namespace

TouchController::TouchController(PadInfo pad, PadPort& padPort, OutputPort& touch, OutputPort& passthrough)
    : pad_(std::move(pad)),
      padPort_(padPort),
      touchOut_(touch),
      passthrough_(passthrough),
      buttonMap_(pad_.keys),
      surface_(touch) {
    for (int code = 0; code < ABS_CNT; ++code) {
        if (!pad_.hasAbs(code)) continue;
        abs_[static_cast<size_t>(code)] = pad_.abs[static_cast<size_t>(code)]->value;
        passthroughAbs_[static_cast<size_t>(code)] = pad_.center(code);
    }
}

void TouchController::configure(const Config& config, const Environment& env, const TouchGeometry& geometry,
                                Nanos now) {
    auto invert = [&](StickAxis axis, bool fallback) { return config.invert[axis].value_or(fallback); };
    // Same device default as mouse mode (Controller::configure).
    const bool rightYDefault = env.productDevice != "r36s";
    left_.configure(pad_.range(ABS_X), pad_.range(ABS_Y), invert(kLeftX, false), invert(kLeftY, false));
    right_.configure(pad_.range(ABS_RX), pad_.range(ABS_RY), invert(kRightX, false),
                     invert(kRightY, rightYDefault));
    left_.setRaw(0, abs_[ABS_X]);
    left_.setRaw(1, abs_[ABS_Y]);
    right_.setRaw(0, abs_[ABS_RX]);
    right_.setRaw(1, abs_[ABS_RY]);
    const float deadzone = profile_ ? profile_->deadzone : 0.15f;
    left_.setDeadzone(deadzone);
    right_.setDeadzone(deadzone);

    if (geometry != surface_.geometry()) {
        // Fingers keep their raw spot; better to start them over.
        if (state_ == State::On) releaseGestures(now);
        surface_.setGeometry(geometry);
    }
    if (state_ == State::Off) setAbsReporting(false);
}

void TouchController::setProfile(std::optional<TouchProfile> profile, Nanos now) {
    // The routes point into the old profile.
    if (state_ == State::On) releaseGestures(now);
    routes_.fill(KeyRoute{});
    sticks_.fill(StickState{});
    profile_ = std::move(profile);
    if (profile_) {
        left_.setDeadzone(profile_->deadzone);
        right_.setDeadzone(profile_->deadzone);
    }
    update(now);
    if (state_ == State::On) {
        syncStickPassthrough();
        flushPassthrough();
    }
}

void TouchController::setSuspended(bool suspended, Nanos now) {
    if (suspended_ == suspended) return;
    suspended_ = suspended;
    update(now);
}

bool TouchController::wanted() const {
    return profile_ && profile_->enabled && !profile_->controls.empty() && !suspended_;
}

void TouchController::update(Nanos now) {
    if (wanted() && state_ == State::Off) {
        enter(now);
    } else if (!wanted() && state_ != State::Off) {
        leave(now, suspended_ ? "mouse mode" : "no profile");
    }
}

void TouchController::shutdown(Nanos now) {
    leave(now, "shutting down");
}

// --- Mode changes -------------------------------------------------------

void TouchController::enter(Nanos now) {
    if (!touchOut_.open()) {
        LOGE("could not create the virtual touchscreen");
        return;
    }
    if (!passthrough_.open()) {
        LOGE("could not create the pass-through pad");
        touchOut_.close();
        return;
    }
    setAbsReporting(true);
    state_ = State::Arming;
    grabNotBefore_ = now + kGrabDelay;
    grabCheckPending_ = true;
    passthroughKeys_.reset();
    passthroughFrame_.clear();
    for (int code = 0; code < ABS_CNT; ++code) {
        if (pad_.hasAbs(code)) passthroughAbs_[static_cast<size_t>(code)] = pad_.center(code);
    }
    // Stick events were filtered while off; pick up where everything is now.
    resync(now);
    LOGI("touch controls on");
}

void TouchController::maybeGrab(Nanos now) {
    if (state_ != State::Arming || now < grabNotBefore_) return;
    // Taking the pad away while something is held would leave it stuck on
    // Android's side.
    if (keysDownCount_ > 0 || !sticksAtRest()) return;
    if (!padPort_.setGrabbed(true)) {
        LOGW("could not grab the pad, retrying");
        grabNotBefore_ = now + kGrabRetry;
        grabCheckPending_ = true;
        return;
    }
    grabbed_ = true;
    grabCheckPending_ = false;
    routes_.fill(KeyRoute{});
    sticks_.fill(StickState{});
    shiftHeld_ = 0;
    layer_ = 0;
    state_ = State::On;
    LOGD("pad grabbed for touch controls");
    syncStickPassthrough();
    flushPassthrough();
}

void TouchController::releaseGestures(Nanos now) {
    (void)now;
    for (size_t code = 0; code < KEY_CNT; ++code) {
        KeyRoute& r = routes_[code];
        if (r.route == Route::Passthrough && passthroughKeys_.test(code)) {
            queuePassthrough(EV_KEY, static_cast<uint16_t>(code), 0);
            passthroughKeys_.reset(code);
        }
        r = KeyRoute{};
    }
    timed_.clear();
    for (StickState& st : sticks_) st = StickState{};
    shiftHeld_ = 0;
    layer_ = 0;
    surface_.releaseAll();
    flush();
    ticking_ = false;
}

void TouchController::leave(Nanos now, const char* why) {
    if (state_ == State::Off) return;
    releaseGestures(now);
    for (int code = 0; code < ABS_CNT; ++code) {
        const size_t c = static_cast<size_t>(code);
        if (pad_.hasAbs(code) && passthroughAbs_[c] != pad_.center(code)) {
            passthroughAbs_[c] = pad_.center(code);
            queuePassthrough(EV_ABS, static_cast<uint16_t>(code), passthroughAbs_[c]);
        }
    }
    flushPassthrough();
    if (grabbed_) {
        padPort_.setGrabbed(false);
        grabbed_ = false;
    }
    grabCheckPending_ = false;
    passthrough_.close();
    touchOut_.close();
    state_ = State::Off;
    setAbsReporting(false);
    LOGI("touch controls off (%s)", why);
}

void TouchController::setAbsReporting(bool enabled) {
    if (absReporting_ == enabled) return;
    absReporting_ = enabled;
    padPort_.setAbsReporting(enabled);
}

// --- Input --------------------------------------------------------------

void TouchController::onInput(const InputEvent& ev, Nanos now) {
    if (dropping_) {
        if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
            dropping_ = false;
            resync(now);
        }
        return;
    }
    switch (ev.type) {
        case EV_SYN:
            if (ev.code == SYN_DROPPED) {
                dropping_ = true;
                passthroughFrame_.clear();
            } else if (ev.code == SYN_REPORT) {
                flushPassthrough();
            }
            break;
        case EV_KEY:
            handleKey(ev.code, ev.value, now);
            break;
        case EV_ABS:
            handleAbs(ev.code, ev.value, now);
            break;
        case EV_MSC:
            if (state_ == State::On) queuePassthrough(ev.type, ev.code, ev.value);
            break;
        default:
            break;
    }
}

void TouchController::resync(Nanos now) {
    KeyBits keys;
    AbsValues abs{};
    if (!padPort_.readState(&keys, &abs)) return;
    for (size_t code = 0; code < KEY_CNT; ++code) {
        if (pad_.keys.test(code) && keys.test(code) != keysDown_.test(code)) {
            handleKey(static_cast<int>(code), keys.test(code) ? 1 : 0, now);
        }
    }
    for (int code = 0; code < ABS_CNT; ++code) {
        const size_t c = static_cast<size_t>(code);
        if (pad_.hasAbs(code) && abs[c] != abs_[c]) handleAbs(code, abs[c], now);
    }
    flushPassthrough();
}

void TouchController::handleKey(int code, int value, Nanos now) {
    if (code < 0 || code >= KEY_CNT) return;
    const size_t c = static_cast<size_t>(code);
    if (value != 2) {
        const bool down = value != 0;
        if (keysDown_.test(c) == down) return;
        keysDown_.set(c, down);
        keysDownCount_ += down ? 1 : -1;
    }
    switch (state_) {
        case State::Off:
            return;
        case State::Arming:
            if (value == 0) maybeGrab(now);
            return;
        case State::On:
            handleKeyOn(code, value, now);
            return;
    }
}

void TouchController::handleKeyOn(int code, int value, Nanos now) {
    KeyRoute& route = routes_[static_cast<size_t>(code)];
    if (value == 2) {
        if (route.route == Route::Passthrough) queuePassthrough(EV_KEY, static_cast<uint16_t>(code), 2);
        return;
    }
    const bool down = value != 0;
    if (!down) {
        switch (route.route) {
            case Route::Passthrough:
                if (passthroughKeys_.test(static_cast<size_t>(code))) {
                    queuePassthrough(EV_KEY, static_cast<uint16_t>(code), 0);
                    passthroughKeys_.reset(static_cast<size_t>(code));
                }
                break;
            case Route::Shift:
                shiftHeld_ = std::max(shiftHeld_ - 1, 0);
                refreshLayer(now);
                break;
            case Route::Control:
                releaseControl(route);
                break;
            case Route::Unset:
            case Route::Swallow:
                break;
        }
        route = KeyRoute{};
        return;
    }

    const std::optional<Button> button = buttonMap_.button(code);
    // FN, and everything pressed while FN is held, belongs to the system.
    const bool fnHeld = code == kFnCode || keysDown_.test(kFnCode);
    const TouchControl* control = nullptr;
    if (!fnHeld && button && *button != Button::Mode && profile_) {
        if (profile_->shift == button) {
            route.route = Route::Shift;
            ++shiftHeld_;
            refreshLayer(now);
            return;
        }
        if (isDpadButton(*button) && profile_->forStick(StickSource::Dpad, layer_)) {
            // The d-pad is a joystick; its direction comes from keysDown_.
            route.route = Route::Swallow;
            if (!ticking_) {
                ticking_ = true;
                nextTick_ = now;
            }
            return;
        }
        control = profile_->forButton(*button, layer_);
    }
    if (!control) {
        route.route = Route::Passthrough;
        queuePassthrough(EV_KEY, static_cast<uint16_t>(code), 1);
        passthroughKeys_.set(static_cast<size_t>(code));
        return;
    }
    route.route = Route::Control;
    route.control = control;
    pressControl(code, *control, now);
}

void TouchController::pressControl(int code, const TouchControl& c, Nanos now) {
    KeyRoute& route = routes_[static_cast<size_t>(code)];
    const auto [x, y] = point(c);
    const int slot = surface_.down(x, y);
    if (slot < 0) {
        LOGD("no finger left for %s", controlTypeName(c.type));
        return;
    }
    switch (c.type) {
        case ControlType::Hold:
            route.slot = slot;
            break;
        case ControlType::Tap:
            timed_.push_back({slot, now, now + kTapLength, x, y, x, y, false});
            break;
        case ControlType::Swipe: {
            const float rad = c.angle * static_cast<float>(M_PI) / 180.0f;
            const float len = c.length * shortSide();
            timed_.push_back({slot, now, now + kSwipeTime, x, y, x + std::cos(rad) * len,
                              y + std::sin(rad) * len, true});
            break;
        }
        case ControlType::Joystick:
        case ControlType::Camera:
            break;  // never bound to a button
    }
    flush();
    if (!timed_.empty() && !ticking_) {
        ticking_ = true;
        nextTick_ = now + kTickPeriod;
    }
}

void TouchController::releaseControl(KeyRoute& r) {
    // Taps and swipes finish on their own.
    if (r.control && r.control->type == ControlType::Hold && r.slot >= 0) {
        surface_.up(r.slot);
        flush();
    }
    r.slot = -1;
}

void TouchController::refreshLayer(Nanos now) {
    const int layer = shiftHeld_ > 0 ? 1 : 0;
    if (layer == layer_) return;
    layer_ = layer;
    // Sticks pick up their control for the new layer on the next step.
    syncStickPassthrough();
    flushPassthrough();
    ticking_ = true;
    nextTick_ = now;
}

void TouchController::handleAbs(int code, int value, Nanos now) {
    if (code < 0 || code >= ABS_CNT) return;
    abs_[static_cast<size_t>(code)] = value;
    switch (code) {
        case ABS_X: left_.setRaw(0, value); break;
        case ABS_Y: left_.setRaw(1, value); break;
        case ABS_RX: right_.setRaw(0, value); break;
        case ABS_RY: right_.setRaw(1, value); break;
        default: break;
    }
    if (state_ == State::Arming) {
        maybeGrab(now);
        return;
    }
    if (state_ != State::On || !pad_.hasAbs(code)) return;
    const auto source = sourceOfAxis(code);
    if (source && stickMapped(*source)) {
        if (!ticking_) {
            ticking_ = true;
            nextTick_ = now;
        }
        return;
    }
    passthroughAbs_[static_cast<size_t>(code)] = value;
    queuePassthrough(EV_ABS, static_cast<uint16_t>(code), value);
}

// --- Sticks -------------------------------------------------------------

bool TouchController::stickMapped(StickSource s) const {
    return profile_ && profile_->forStick(s, layer_) != nullptr;
}

void TouchController::syncStickPassthrough() {
    for (size_t s = 0; s < kStickSourceCount; ++s) {
        const bool mapped = stickMapped(static_cast<StickSource>(s));
        for (int code : kSourceAxes[s]) {
            if (!pad_.hasAbs(code)) continue;
            const size_t c = static_cast<size_t>(code);
            const int32_t want = mapped ? pad_.center(code) : abs_[c];
            if (passthroughAbs_[c] == want) continue;
            passthroughAbs_[c] = want;
            queuePassthrough(EV_ABS, static_cast<uint16_t>(code), want);
        }
    }
}

StickVector TouchController::vectorFor(StickSource s) const {
    switch (s) {
        case StickSource::Left:
            return left_.valid() ? left_.output() : StickVector{};
        case StickSource::Right:
            return right_.valid() ? right_.output() : StickVector{};
        case StickSource::Dpad: {
            auto held = [&](int a, int b) { return keysDown_.test(static_cast<size_t>(a)) || keysDown_.test(static_cast<size_t>(b)); };
            float x = (held(KEY_RIGHT, BTN_DPAD_RIGHT) ? 1.0f : 0.0f) - (held(KEY_LEFT, BTN_DPAD_LEFT) ? 1.0f : 0.0f);
            float y = (held(KEY_DOWN, BTN_DPAD_DOWN) ? 1.0f : 0.0f) - (held(KEY_UP, BTN_DPAD_UP) ? 1.0f : 0.0f);
            if (x == 0.0f && pad_.hasAbs(ABS_HAT0X)) {
                const int32_t v = abs_[ABS_HAT0X] - pad_.center(ABS_HAT0X);
                x = v > 0 ? 1.0f : (v < 0 ? -1.0f : 0.0f);
            }
            if (y == 0.0f && pad_.hasAbs(ABS_HAT0Y)) {
                const int32_t v = abs_[ABS_HAT0Y] - pad_.center(ABS_HAT0Y);
                y = v > 0 ? 1.0f : (v < 0 ? -1.0f : 0.0f);
            }
            if (x != 0.0f && y != 0.0f) {
                x *= static_cast<float>(M_SQRT1_2);
                y *= static_cast<float>(M_SQRT1_2);
            }
            return {x, y};
        }
    }
    return {};
}

bool TouchController::sticksAtRest() const {
    if (left_.valid() && left_.rawMagnitude() >= kRestThreshold) return false;
    if (right_.valid() && right_.rawMagnitude() >= kRestThreshold) return false;
    for (int code : {ABS_HAT0X, ABS_HAT0Y}) {
        if (pad_.hasAbs(code) && abs_[static_cast<size_t>(code)] != pad_.center(code)) return false;
    }
    return true;
}

void TouchController::endStick(StickState& st) {
    if (st.slot >= 0) surface_.up(st.slot);
    st.slot = -1;
    st.phase = StickPhase::Idle;
}

void TouchController::stepSticks(Nanos now) {
    for (size_t s = 0; s < kStickSourceCount; ++s) stepStick(static_cast<StickSource>(s), sticks_[s], now);
}

void TouchController::stepStick(StickSource s, StickState& st, Nanos now) {
    const TouchControl* c = profile_ ? profile_->forStick(s, layer_) : nullptr;
    if (c != st.control) {
        endStick(st);
        st = StickState{};
        st.control = c;
    }
    if (!c) return;
    const StickVector v = vectorFor(s);
    const bool pushed = !v.isZero();
    const auto [cx, cy] = point(*c);
    const float radius = c->radius * shortSide();

    if (c->type == ControlType::Joystick) {
        if (!pushed) {
            if (st.phase != StickPhase::Idle) endStick(st);
            return;
        }
        if (st.phase == StickPhase::Idle) {
            // Touch the middle first: many games place their stick where the
            // finger lands.
            st.slot = surface_.down(cx, cy);
            if (st.slot < 0) return;
            st.phase = StickPhase::Anchored;
            st.since = now;
            return;
        }
        if (st.phase == StickPhase::Anchored && now - st.since >= kAnchorDelay) st.phase = StickPhase::Moving;
        if (st.phase == StickPhase::Moving) surface_.move(st.slot, cx + v.x * radius, cy + v.y * radius);
        return;
    }

    // Camera: the stick drags the finger; at the edge of the area it lifts and
    // starts over from the middle, like a thumb running out of screen.
    const double dt = std::clamp(nanosToSec(now - st.lastStep), 0.0, 0.05);
    st.lastStep = now;
    if (pushed) st.lastActive = now;
    switch (st.phase) {
        case StickPhase::Idle:
            if (!pushed) return;
            st.slot = surface_.down(cx, cy);
            if (st.slot < 0) return;
            st.x = cx;
            st.y = cy;
            st.phase = StickPhase::Anchored;
            st.since = now;
            return;
        case StickPhase::Anchored:
            if (now - st.since >= kAnchorDelay) st.phase = StickPhase::Moving;
            return;
        case StickPhase::Moving: {
            if (!pushed) {
                if (now - st.lastActive >= kCameraIdle) endStick(st);
                return;
            }
            const float m = v.magnitude();
            const float gain = std::pow(m, kCameraCurve) / m;
            const float speed = c->speed * kCameraBaseSpeed * shortSide();
            float nx = st.x + v.x * gain * speed * static_cast<float>(dt);
            float ny = st.y + v.y * gain * speed * static_cast<float>(dt);
            const float dx = nx - cx;
            const float dy = ny - cy;
            const float d = std::hypot(dx, dy);
            if (d >= radius) {
                // Go to the edge in this frame and lift in the next, or the
                // last bit of movement would be lost.
                nx = cx + dx / d * radius;
                ny = cy + dy / d * radius;
                st.phase = StickPhase::Lifting;
            }
            st.x = nx;
            st.y = ny;
            surface_.move(st.slot, nx, ny);
            return;
        }
        case StickPhase::Lifting:
            endStick(st);
            return;
    }
}

void TouchController::stepTimed(Nanos now) {
    for (size_t i = 0; i < timed_.size();) {
        TimedFinger& t = timed_[i];
        bool done = false;
        if (!t.swipe) {
            done = now >= t.end;
        } else if (now >= t.end) {
            // Arrived in the previous step: lift now.
            if (t.fromX == t.toX && t.fromY == t.toY) {
                done = true;
            } else {
                surface_.move(t.slot, t.toX, t.toY);
                t.fromX = t.toX;
                t.fromY = t.toY;
            }
        } else {
            const float f = static_cast<float>(now - t.start) / static_cast<float>(t.end - t.start);
            surface_.move(t.slot, t.fromX + (t.toX - t.fromX) * f, t.fromY + (t.toY - t.fromY) * f);
        }
        if (done) {
            surface_.up(t.slot);
            timed_.erase(timed_.begin() + static_cast<std::ptrdiff_t>(i));
        } else {
            ++i;
        }
    }
}

bool TouchController::needsTick() const {
    if (state_ != State::On) return false;
    if (!timed_.empty()) return true;
    for (size_t s = 0; s < kStickSourceCount; ++s) {
        if (sticks_[s].phase != StickPhase::Idle) return true;
        const TouchControl* c = profile_ ? profile_->forStick(static_cast<StickSource>(s), layer_) : nullptr;
        if (c && !vectorFor(static_cast<StickSource>(s)).isZero()) return true;
        // A control changed with the layer: the old finger still has to go.
        if (sticks_[s].control != c) return true;
    }
    return false;
}

// --- Geometry -----------------------------------------------------------

std::pair<float, float> TouchController::point(const TouchControl& c) const {
    const TouchGeometry& g = surface_.geometry();
    return {c.x * static_cast<float>(std::max(g.visibleWidth() - 1, 1)),
            c.y * static_cast<float>(std::max(g.visibleHeight() - 1, 1))};
}

float TouchController::shortSide() const {
    return static_cast<float>(surface_.geometry().shorterSide());
}

// --- Output -------------------------------------------------------------

void TouchController::queuePassthrough(uint16_t type, uint16_t code, int32_t value) {
    if (passthrough_.isOpen()) passthroughFrame_.push_back({type, code, value});
}

void TouchController::flushPassthrough() {
    if (passthroughFrame_.empty()) return;
    passthroughFrame_.push_back({EV_SYN, SYN_REPORT, 0});
    passthrough_.send(passthroughFrame_);
    passthroughFrame_.clear();
}

void TouchController::flush() {
    surface_.flush();
}

// --- Timing -------------------------------------------------------------

void TouchController::onTimeout(Nanos now) {
    if (state_ == State::Arming && grabCheckPending_ && now >= grabNotBefore_) {
        grabCheckPending_ = false;
        maybeGrab(now);
    }
    if (state_ != State::On) return;
    if (!ticking_ && needsTick()) {
        ticking_ = true;
        nextTick_ = now;
    }
    if (ticking_ && now >= nextTick_) {
        stepSticks(now);
        stepTimed(now);
        flush();
        if (needsTick()) {
            nextTick_ += kTickPeriod;
            if (nextTick_ <= now) nextTick_ = now + kTickPeriod;
        } else {
            ticking_ = false;
        }
    }
}

std::optional<Nanos> TouchController::nextDeadline() const {
    if (state_ == State::Arming && grabCheckPending_) return grabNotBefore_;
    if (state_ == State::On && ticking_) return nextTick_;
    return std::nullopt;
}

}  // namespace joymouse
