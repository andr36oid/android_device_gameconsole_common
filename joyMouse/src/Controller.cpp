#include "Controller.h"

#include <algorithm>
#include <cmath>

#include "Log.h"

namespace joymouse {

namespace {

constexpr uint16_t kMouseCodes[] = {BTN_LEFT, BTN_RIGHT, BTN_MIDDLE, BTN_SIDE, BTN_EXTRA};

StickSide opposite(StickSide s) {
    return s == StickSide::Left ? StickSide::Right : StickSide::Left;
}

bool isHat(int code) {
    return code >= ABS_HAT0X && code <= ABS_HAT3Y;
}

}  // namespace

bool PadInfo::hasAbs(int code) const {
    return code >= 0 && code < ABS_CNT && abs[static_cast<size_t>(code)].has_value();
}

AxisRange PadInfo::range(int code) const {
    if (!hasAbs(code)) return {};
    const input_absinfo& info = *abs[static_cast<size_t>(code)];
    return {info.minimum, info.maximum};
}

int32_t PadInfo::center(int code) const {
    return range(code).center();
}

Controller::Controller(PadInfo pad, PadPort& padPort, OutputPort& mouse, OutputPort& passthrough,
                       ModeListener* listener)
    : pad_(std::move(pad)),
      padPort_(padPort),
      mouse_(mouse),
      passthrough_(passthrough),
      listener_(listener),
      buttonMap_(pad_.keys) {
    for (int code = 0; code < ABS_CNT; ++code) {
        if (!pad_.hasAbs(code)) continue;
        abs_[static_cast<size_t>(code)] = pad_.abs[static_cast<size_t>(code)]->value;
        passthroughAbs_[static_cast<size_t>(code)] = pad_.center(code);
    }
}

void Controller::configure(const Config& config, const Environment& env, Nanos now) {
    pointer_.stop();
    scroll_.stop();
    ticking_ = false;

    bindings_ = config.bindings();

    std::vector<Button> chord;
    if (config.toggleEnabled) chord = config.toggle ? *config.toggle : defaultToggleChord(buttonMap_);
    for (Button b : chord) {
        if (!buttonMap_.has(b)) {
            LOGW("toggle button %s is missing on this pad, toggle chord disabled", buttonName(b));
            chord.clear();
            break;
        }
    }
    const Nanos hold = msToNanos(config.toggleMs);
    if (chord != chord_.members() || hold != chord_.holdTime()) {
        for (Button b : chord_.members()) cancelMember(members_[index(b)], now);
        members_.fill(Member{});
        chord_.configure(chord, hold);
        refreshChord(now);
    }

    const bool hasLeft = pad_.hasAbs(ABS_X) && pad_.hasAbs(ABS_Y);
    const bool hasRight = pad_.hasAbs(ABS_RX) && pad_.hasAbs(ABS_RY);
    auto has = [&](StickSide s) { return s == StickSide::Left ? hasLeft : hasRight; };
    const StickSide preferred = config.pointerStick.value_or(StickSide::Right);
    pointerSide_.reset();
    scrollSide_.reset();
    if (has(preferred)) {
        pointerSide_ = preferred;
    } else if (has(opposite(preferred))) {
        pointerSide_ = opposite(preferred);
    }
    if (pointerSide_ && has(opposite(*pointerSide_))) scrollSide_ = opposite(*pointerSide_);
    pointerClick_.reset();
    if (pointerSide_) {
        const Button click = *pointerSide_ == StickSide::Right ? Button::R3 : Button::L3;
        if (buttonMap_.has(click)) pointerClick_ = click;
    }

    auto invert = [&](StickAxis axis, bool fallback) { return config.invert[axis].value_or(fallback); };
    // Carried over from the original joyMouse: the right stick's Y axis is
    // reversed on every supported handheld except the R36S.
    const bool rightYDefault = env.productDevice != "r36s";
    left_.configure(pad_.range(ABS_X), pad_.range(ABS_Y), invert(kLeftX, false), invert(kLeftY, false));
    right_.configure(pad_.range(ABS_RX), pad_.range(ABS_RY), invert(kRightX, false),
                     invert(kRightY, rightYDefault));
    left_.setRaw(0, abs_[ABS_X]);
    left_.setRaw(1, abs_[ABS_Y]);
    right_.setRaw(0, abs_[ABS_RX]);
    right_.setRaw(1, abs_[ABS_RY]);
    if (Stick* s = stickFor(pointerSide_)) s->setDeadzone(config.deadzone);
    if (Stick* s = stickFor(scrollSide_)) s->setDeadzone(config.scrollDeadzone);

    const double diagonal = std::hypot(std::max(env.displayWidth, 1), std::max(env.displayHeight, 1));
    PointerParams pp;
    pp.topSpeed = static_cast<float>(kTopSpeedPerDiagonal * diagonal * config.speed);
    pp.curve = config.curve;
    pp.compensateAndroidAccel = config.accelCompensation;
    pointer_.setParams(pp);

    ScrollParams sp;
    sp.minRate *= config.scrollSpeed;
    sp.maxRate *= config.scrollSpeed;
    scroll_.setParams(sp);

    precisionGain_ = config.precision;
    pointerInvertX_ = config.pointerInvertX;
    pointerInvertY_ = config.pointerInvertY;
    naturalScroll_ = config.naturalScroll;
    tickPeriod_ = kNanosPerSec / std::max(config.rateHz, 1);
    clickFreeze_ = msToNanos(config.clickFreezeMs);
    if (mode_ == Mode::Off) setAbsReporting(chord_.armed());

    std::string chordText;
    for (Button b : chord_.members()) {
        if (!chordText.empty()) chordText += "+";
        chordText += buttonName(b);
    }
    LOGI("pointer stick: %s, scroll stick: %s, toggle: %s, top speed %.0f px/s",
         pointerSide_ ? (*pointerSide_ == StickSide::Right ? "right" : "left") : "none",
         scrollSide_ ? (*scrollSide_ == StickSide::Right ? "right" : "left") : "none",
         chordText.empty() ? "disabled" : chordText.c_str(), static_cast<double>(pp.topSpeed));

    updateMotion(now);
}

// --- Input --------------------------------------------------------------

void Controller::onInput(const InputEvent& ev, Nanos now) {
    if (dropping_) {
        // The kernel buffer overflowed: skip to the next report and re-read the
        // real state instead of trusting a partial stream.
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
            if (mode_ == Mode::On) queuePassthrough(ev.type, ev.code, ev.value);
            break;
        default:
            break;
    }
}

void Controller::handleKey(int code, int value, Nanos now) {
    if (code < 0 || code >= KEY_CNT) return;
    const size_t c = static_cast<size_t>(code);
    const std::optional<Button> button = buttonMap_.button(code);
    if (value != 2) {
        const bool down = value != 0;
        if (keysDown_.test(c) == down) return;
        keysDown_.set(c, down);
        keysDownCount_ += down ? 1 : -1;
        if (button) buttonDownCount_[index(*button)] += down ? 1 : -1;
        refreshChord(now);
    }

    switch (mode_) {
        case Mode::Off:
            return;
        case Mode::Arming:
            if (value == 0) {
                if (button && button == pointerClick_) {
                    freezeUntil_ = std::max(freezeUntil_, now + kStickSettle);
                }
                maybeGrab(now);
            }
            updateMotion(now);
            return;
        case Mode::On:
            handleKeyOn(code, value, button, now);
            return;
    }
}

void Controller::handleKeyOn(int code, int value, std::optional<Button> button, Nanos now) {
    KeyRoute& route = routes_[static_cast<size_t>(code)];
    if (value == 2) {
        if (route.route == Route::Passthrough) queuePassthrough(EV_KEY, static_cast<uint16_t>(code), 2);
        return;
    }
    const bool down = value != 0;
    if (!down && route.route != Route::Unset) {
        const KeyRoute r = route;
        route = KeyRoute{};
        release(code, r, now);
        return;
    }
    if (button && chord_.isMember(*button)) {
        handleMember(*button, code, down, now);
        return;
    }
    if (down) {
        route = routeFor(button ? bindings_[index(*button)] : Action::Pass, now);
        press(code, route, now);
    }
}

void Controller::handleMember(Button b, int code, bool down, Nanos now) {
    Member& m = members_[index(b)];
    if (down) {
        m.down = true;
        m.code = code;
        bool otherDown = false;
        for (Button o : chord_.members()) otherDown = otherDown || (o != b && members_[index(o)].down);
        if (otherDown) {
            // Several chord buttons at once is the mode toggle, not a click.
            for (Button o : chord_.members()) cancelMember(members_[index(o)], now);
        } else {
            m.pending = true;
            m.since = now;
            m.route = routeFor(bindings_[index(b)], now);
        }
    } else {
        m.down = false;
        if (m.pending) {
            m.pending = false;
            tap(code, m.route, now);
        } else if (m.held) {
            m.held = false;
            release(code, m.route, now);
        }
        m.route = KeyRoute{};
        if (pointerClick_ == b) freezeUntil_ = std::max(freezeUntil_, now + kStickSettle);
    }
    updateMotion(now);
}

void Controller::handleAbs(int code, int value, Nanos now) {
    if (code < 0 || code >= ABS_CNT) return;
    abs_[static_cast<size_t>(code)] = value;
    const auto axis = stickAxis(code);
    if (axis) {
        stickFor(axis->first)->setRaw(axis->second, value);
        checkSteering();
    }
    if (mode_ == Mode::Off) return;

    if (axis) {
        if (mode_ == Mode::Arming) maybeGrab(now);
        updateMotion(now);
        return;
    }
    if (mode_ == Mode::On && pad_.hasAbs(code)) {
        passthroughAbs_[static_cast<size_t>(code)] = value;
        queuePassthrough(EV_ABS, static_cast<uint16_t>(code), value);
    }
}

void Controller::resync(Nanos now) {
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

void Controller::refreshAbs() {
    KeyBits keys;
    AbsValues abs{};
    if (!padPort_.readState(&keys, &abs)) return;
    for (int code = 0; code < ABS_CNT; ++code) {
        const size_t c = static_cast<size_t>(code);
        if (!pad_.hasAbs(code)) continue;
        abs_[c] = abs[c];
        if (const auto axis = stickAxis(code)) stickFor(axis->first)->setRaw(axis->second, abs[c]);
    }
}

void Controller::refreshChord(Nanos now) {
    std::array<bool, kButtonCount> down{};
    int memberKeys = 0;
    for (size_t i = 0; i < kButtonCount; ++i) {
        down[i] = buttonDownCount_[i] > 0;
        if (chord_.isMember(static_cast<Button>(i))) memberKeys += buttonDownCount_[i];
    }
    const bool wasArmed = chord_.armed();
    chord_.update(down, keysDownCount_ - memberKeys, now);
    if (mode_ == Mode::Off && chord_.armed() != wasArmed) {
        // Stick events are filtered while off; watch the sticks for the
        // duration of the hold so steering can veto it.
        setAbsReporting(chord_.armed());
        if (chord_.armed()) refreshAbs();
    }
    checkSteering();
}

void Controller::checkSteering() {
    if (!chord_.armed()) return;
    const bool steering = (left_.valid() && left_.rawMagnitude() > kSteerThreshold) ||
                          (right_.valid() && right_.rawMagnitude() > kSteerThreshold);
    if (steering) {
        LOGD("toggle chord ignored: a stick is being pushed");
        chord_.veto();
        if (mode_ == Mode::Off) setAbsReporting(false);
    }
}

void Controller::setAbsReporting(bool enabled) {
    if (absReporting_ == enabled) return;
    absReporting_ = enabled;
    padPort_.setAbsReporting(enabled);
}

// --- Mode changes -------------------------------------------------------

void Controller::setActive(bool active, Nanos now) {
    if (active) {
        enter(now, "requested");
    } else {
        leave(now, "requested");
    }
}

void Controller::shutdown(Nanos now) {
    leave(now, "shutting down");
}

void Controller::enter(Nanos now, const char* why) {
    if (mode_ != Mode::Off) return;
    bool ok = pointerSide_.has_value();
    if (!ok) {
        LOGW("the pad has no analog stick, mouse mode unavailable");
    } else if (!mouse_.open()) {
        LOGE("could not create the virtual mouse");
        ok = false;
    } else if (!passthrough_.open()) {
        LOGE("could not create the pass-through pad");
        mouse_.close();
        ok = false;
    }
    if (!ok) {
        if (listener_) listener_->onMouseModeChanged(false);
        return;
    }

    setAbsReporting(true);
    mode_ = Mode::Arming;
    grabNotBefore_ = now + kGrabDelay;
    grabCheckPending_ = true;
    revealPhase_ = 1;
    revealAt_ = now + kRevealDelay;
    movedSinceEnter_ = false;
    pointerActive_ = true;
    lastPointerUse_ = now;
    passthroughKeys_.reset();
    for (int code = 0; code < ABS_CNT; ++code) {
        if (pad_.hasAbs(code)) passthroughAbs_[static_cast<size_t>(code)] = pad_.center(code);
    }
    // Stick events were filtered while off; pick up where the sticks are now.
    resync(now);

    LOGI("mouse mode on (%s)", why);
    if (listener_) listener_->onMouseModeChanged(true);
    updateMotion(now);
}

void Controller::maybeGrab(Nanos now) {
    if (mode_ != Mode::Arming || now < grabNotBefore_) return;
    // Taking the pad away from Android while something is held would leave
    // that input stuck on Android's side, so wait until the pad is idle.
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
    members_.fill(Member{});
    mode_ = Mode::On;
    LOGD("pad grabbed");
    updateMotion(now);
}

void Controller::leave(Nanos now, const char* why) {
    if (mode_ == Mode::Off) return;

    // Settle every button while the virtual devices still exist.
    std::vector<DelayedRelease> delayed;
    delayed.swap(delayedReleases_);
    for (const DelayedRelease& d : delayed) release(d.code, d.route, now);
    for (Button b : chord_.members()) cancelMember(members_[index(b)], now);
    members_.fill(Member{});
    for (size_t code = 0; code < KEY_CNT; ++code) {
        if (routes_[code].route == Route::Unset) continue;
        const KeyRoute r = routes_[code];
        routes_[code] = KeyRoute{};
        release(static_cast<int>(code), r, now);
    }
    for (int code = 0; code < ABS_CNT; ++code) {
        const size_t c = static_cast<size_t>(code);
        if (pad_.hasAbs(code) && passthroughAbs_[c] != pad_.center(code)) {
            passthroughAbs_[c] = pad_.center(code);
            queuePassthrough(EV_ABS, static_cast<uint16_t>(code), passthroughAbs_[c]);
        }
    }
    flushPassthrough();

    pointer_.stop();
    scroll_.stop();
    ticking_ = false;
    freezeUntil_ = 0;
    precisionHeld_ = 0;
    scrollHeld_ = 0;
    mouseButtonCount_.fill(0);
    revealPhase_ = 0;
    grabCheckPending_ = false;
    pointerActive_ = false;

    if (grabbed_) {
        padPort_.setGrabbed(false);
        grabbed_ = false;
    }
    passthrough_.close();
    mouse_.close();
    mode_ = Mode::Off;
    setAbsReporting(chord_.armed());

    LOGI("mouse mode off (%s)", why);
    if (listener_) listener_->onMouseModeChanged(false);
}

// --- Routing ------------------------------------------------------------

Controller::KeyRoute Controller::routeFor(Action action, Nanos now) const {
    if (action == Action::Smart) action = pointerInUse(now) ? Action::Left : Action::Pass;
    switch (action) {
        case Action::Pass: return {Route::Passthrough, 0};
        case Action::None: return {Route::Swallow, 0};
        case Action::Left: return {Route::Mouse, kLeftButton};
        case Action::Right: return {Route::Mouse, kRightButton};
        case Action::Middle: return {Route::Mouse, kMiddleButton};
        case Action::Back: return {Route::Mouse, kSideButton};
        case Action::Forward: return {Route::Mouse, kExtraButton};
        case Action::Precision: return {Route::Precision, 0};
        case Action::Scroll: return {Route::Scroll, 0};
        case Action::Smart: break;
    }
    return {Route::Swallow, 0};
}

void Controller::press(int code, const KeyRoute& r, Nanos now) {
    const size_t c = static_cast<size_t>(code);
    switch (r.route) {
        case Route::Passthrough:
            queuePassthrough(EV_KEY, static_cast<uint16_t>(code), 1);
            passthroughKeys_.set(c);
            // Android hides the pointer on any key press; follow it, so the
            // smart button goes back to confirming the focused item.
            pointerActive_ = false;
            break;
        case Route::Mouse:
            setMouseButton(r.mouseButton, true, now);
            break;
        case Route::Precision:
            ++precisionHeld_;
            updateMotion(now);
            break;
        case Route::Scroll:
            ++scrollHeld_;
            updateMotion(now);
            break;
        case Route::Unset:
        case Route::Swallow:
            break;
    }
}

void Controller::release(int code, const KeyRoute& r, Nanos now) {
    const size_t c = static_cast<size_t>(code);
    switch (r.route) {
        case Route::Passthrough:
            if (passthroughKeys_.test(c)) {
                queuePassthrough(EV_KEY, static_cast<uint16_t>(code), 0);
                passthroughKeys_.reset(c);
            }
            break;
        case Route::Mouse:
            setMouseButton(r.mouseButton, false, now);
            break;
        case Route::Precision:
            precisionHeld_ = std::max(precisionHeld_ - 1, 0);
            updateMotion(now);
            break;
        case Route::Scroll:
            scrollHeld_ = std::max(scrollHeld_ - 1, 0);
            updateMotion(now);
            break;
        case Route::Unset:
        case Route::Swallow:
            break;
    }
}

void Controller::tap(int code, const KeyRoute& r, Nanos now) {
    if (r.route != Route::Passthrough && r.route != Route::Mouse) return;
    press(code, r, now);
    flushPassthrough();
    delayedReleases_.push_back({now + kTapLength, code, r});
}

void Controller::cancelMember(Member& m, Nanos now) {
    if (m.held) release(m.code, m.route, now);
    m.pending = false;
    m.held = false;
    m.route = KeyRoute{};
}

// --- Output -------------------------------------------------------------

void Controller::setMouseButton(uint8_t button, bool down, Nanos now) {
    int& count = mouseButtonCount_[button];
    if (down) {
        advanceMotion(now);  // click exactly where the pointer is now
        if (count++ == 0) {
            sendMouse({{EV_KEY, kMouseCodes[button], 1}});
        }
        // Clicking tends to nudge the stick; hold the pointer still for a
        // moment so a click doesn't become a tiny drag.
        freezeUntil_ = std::max(freezeUntil_, now + clickFreeze_);
        markPointerUsed(now);
        movedSinceEnter_ = true;
        updateMotion(now);
    } else if (count > 0 && --count == 0) {
        advanceMotion(now);
        sendMouse({{EV_KEY, kMouseCodes[button], 0}});
    }
}

void Controller::sendMouse(std::vector<InputEvent> events) {
    if (!mouse_.isOpen()) return;
    events.push_back({EV_SYN, SYN_REPORT, 0});
    mouse_.send(events);
}

void Controller::queuePassthrough(uint16_t type, uint16_t code, int32_t value) {
    if (passthrough_.isOpen()) passthroughFrame_.push_back({type, code, value});
}

void Controller::flushPassthrough() {
    if (passthroughFrame_.empty()) return;
    passthroughFrame_.push_back({EV_SYN, SYN_REPORT, 0});
    passthrough_.send(passthroughFrame_);
    passthroughFrame_.clear();
}

void Controller::reveal(Nanos now) {
    // A new mouse starts out hidden in the middle of the screen. Nudge it so
    // the pointer shows up as soon as mouse mode is on; the way back is sent a
    // bit later so Android's pointer acceleration can't make it a net move.
    if (movedSinceEnter_) {
        revealPhase_ = 0;
        return;
    }
    if (revealPhase_ == 1) {
        sendMouse({{EV_REL, REL_X, 1}});
        revealPhase_ = 2;
        revealAt_ = now + kRevealReturn;
    } else {
        sendMouse({{EV_REL, REL_X, -1}});
        revealPhase_ = 0;
    }
}

// --- Motion -------------------------------------------------------------

Stick* Controller::stickFor(std::optional<StickSide> side) {
    if (!side) return nullptr;
    return *side == StickSide::Left ? &left_ : &right_;
}

std::optional<std::pair<StickSide, int>> Controller::stickAxis(int absCode) const {
    auto used = [&](StickSide s) { return pointerSide_ == s || scrollSide_ == s; };
    switch (absCode) {
        case ABS_X: if (used(StickSide::Left)) return std::make_pair(StickSide::Left, 0); break;
        case ABS_Y: if (used(StickSide::Left)) return std::make_pair(StickSide::Left, 1); break;
        case ABS_RX: if (used(StickSide::Right)) return std::make_pair(StickSide::Right, 0); break;
        case ABS_RY: if (used(StickSide::Right)) return std::make_pair(StickSide::Right, 1); break;
        default: break;
    }
    return std::nullopt;
}

bool Controller::sticksAtRest() const {
    if (pointerSide_ && (*pointerSide_ == StickSide::Left ? left_ : right_).rawMagnitude() >= kRestThreshold) {
        return false;
    }
    if (scrollSide_ && (*scrollSide_ == StickSide::Left ? left_ : right_).rawMagnitude() >= kRestThreshold) {
        return false;
    }
    for (int code = ABS_HAT0X; code <= ABS_HAT3Y; ++code) {
        if (pad_.hasAbs(code) && isHat(code) && abs_[static_cast<size_t>(code)] != pad_.center(code)) return false;
    }
    return true;
}

bool Controller::pointerFrozen(Nanos now) const {
    if (now < freezeUntil_) return true;
    if (!pointerClick_) return false;
    if (mode_ == Mode::Arming) return buttonDownCount_[index(*pointerClick_)] > 0;
    if (mode_ == Mode::On && chord_.isMember(*pointerClick_)) {
        // Pressing the stick down moves it; ignore that until the press turns
        // out to be a hold (drag).
        const Member& m = members_[index(*pointerClick_)];
        return m.down && !m.held;
    }
    return false;
}

bool Controller::pointerInUse(Nanos now) const {
    return pointerActive_ && now - lastPointerUse_ < kPointerIdle;
}

void Controller::markPointerUsed(Nanos now) {
    pointerActive_ = true;
    lastPointerUse_ = now;
}

void Controller::computeTargets(Nanos now) {
    StickVector p;
    if (Stick* s = stickFor(pointerSide_)) p = s->output();
    if (pointerInvertX_) p.x = -p.x;
    if (pointerInvertY_) p.y = -p.y;
    StickVector sc;
    if (mode_ == Mode::On) {
        if (Stick* s = stickFor(scrollSide_)) sc = s->output();
        if (scrollHeld_ > 0) {
            sc.x += p.x;
            sc.y += p.y;
            const float m = sc.magnitude();
            if (m > 1.0f) {
                sc.x /= m;
                sc.y /= m;
            }
            p = {};
        }
    }
    if (pointerFrozen(now)) p = {};
    const float gain = mode_ == Mode::On && precisionHeld_ > 0 ? precisionGain_ : 1.0f;
    pointer_.setTarget(p.x, p.y, gain);
    scroll_.setTarget(sc.x, sc.y, gain);
}

void Controller::updateMotion(Nanos now) {
    if (mode_ == Mode::Off) return;
    computeTargets(now);
    if (!ticking_ && (pointer_.active() || scroll_.active())) {
        ticking_ = true;
        lastTick_ = now;
        nextTick_ = now + tickPeriod_;
    }
}

void Controller::advanceMotion(Nanos now) {
    if (ticking_ && now > lastTick_) stepMotion(now);
}

void Controller::stepMotion(Nanos now) {
    const double dt = std::min(nanosToSec(now - lastTick_), 0.05);
    lastTick_ = now;
    computeTargets(now);
    const PointerModel::Delta d = pointer_.step(dt);
    const ScrollModel::Detents s = scroll_.step(dt);

    std::vector<InputEvent> frame;
    if (d.dx != 0) frame.push_back({EV_REL, REL_X, d.dx});
    if (d.dy != 0) frame.push_back({EV_REL, REL_Y, d.dy});
    // REL_WHEEL is positive for "scroll up", the stick is positive downwards.
    if (s.y != 0) frame.push_back({EV_REL, REL_WHEEL, naturalScroll_ ? s.y : -s.y});
    if (s.x != 0) frame.push_back({EV_REL, REL_HWHEEL, naturalScroll_ ? -s.x : s.x});
    if (frame.empty()) return;
    sendMouse(std::move(frame));
    markPointerUsed(now);
    movedSinceEnter_ = true;
}

void Controller::tick(Nanos now) {
    stepMotion(now);
    if (pointer_.active() || scroll_.active()) {
        nextTick_ += tickPeriod_;
        if (nextTick_ <= now) nextTick_ = now + tickPeriod_;
    } else {
        ticking_ = false;
    }
}

// --- Timing -------------------------------------------------------------

void Controller::onTimeout(Nanos now) {
    if (chord_.fire(now)) {
        if (mode_ == Mode::Off) {
            enter(now, "toggle chord");
        } else {
            leave(now, "toggle chord");
        }
    }
    if (mode_ == Mode::Off) return;

    if (mode_ == Mode::On && memberHoldAllowed()) {
        for (Button b : chord_.members()) {
            Member& m = members_[index(b)];
            if (!m.pending || now < m.since + kHoldDecision) continue;
            m.pending = false;
            m.held = true;
            press(m.code, m.route, now);
            updateMotion(now);
        }
    }
    for (size_t i = 0; i < delayedReleases_.size();) {
        if (now < delayedReleases_[i].at) {
            ++i;
            continue;
        }
        const DelayedRelease d = delayedReleases_[i];
        delayedReleases_.erase(delayedReleases_.begin() + static_cast<std::ptrdiff_t>(i));
        release(d.code, d.route, now);
    }
    flushPassthrough();

    if (mode_ == Mode::Arming && grabCheckPending_ && now >= grabNotBefore_) {
        grabCheckPending_ = false;
        maybeGrab(now);
    }
    if (revealPhase_ != 0 && now >= revealAt_) reveal(now);
    if (freezeUntil_ != 0 && now >= freezeUntil_) {
        freezeUntil_ = 0;
        updateMotion(now);
    }
    if (ticking_ && now >= nextTick_) tick(now);
}

std::optional<Nanos> Controller::nextDeadline() const {
    std::optional<Nanos> next;
    auto consider = [&next](Nanos t) {
        if (!next || t < *next) next = t;
    };
    if (const auto d = chord_.deadline()) consider(*d);
    if (mode_ == Mode::Off) return next;

    if (ticking_) consider(nextTick_);
    if (mode_ == Mode::On && memberHoldAllowed()) {
        for (Button b : chord_.members()) {
            const Member& m = members_[index(b)];
            if (m.pending) consider(m.since + kHoldDecision);
        }
    }
    for (const DelayedRelease& d : delayedReleases_) consider(d.at);
    if (mode_ == Mode::Arming && grabCheckPending_) consider(grabNotBefore_);
    if (revealPhase_ != 0) consider(revealAt_);
    if (freezeUntil_ != 0) consider(freezeUntil_);
    return next;
}

}  // namespace joymouse
