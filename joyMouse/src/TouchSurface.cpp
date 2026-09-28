#include "TouchSurface.h"

#include <algorithm>
#include <cmath>

namespace joymouse {

namespace {

float unit(float v, int size) {
    if (size <= 1) return 0.0f;
    return std::clamp(v / static_cast<float>(size - 1), 0.0f, 1.0f);
}

int32_t scale(float n, int size) {
    return static_cast<int32_t>(std::lround(n * static_cast<float>(std::max(size - 1, 0))));
}

}  // namespace

std::pair<int32_t, int32_t> TouchGeometry::toRaw(float x, float y) const {
    const float u = unit(x, visibleWidth());
    const float v = unit(y, visibleHeight());
    float nx = u;
    float ny = v;
    switch (rotation & 3) {
        case 1:  // Android shows raw y as x, and max x - raw x as y
            nx = 1.0f - v;
            ny = u;
            break;
        case 2:
            nx = 1.0f - u;
            ny = 1.0f - v;
            break;
        case 3:  // x = max y - raw y, y = raw x
            nx = v;
            ny = 1.0f - u;
            break;
        default:
            break;
    }
    return {scale(nx, naturalWidth), scale(ny, naturalHeight)};
}

int TouchSurface::down(float x, float y) {
    for (int i = 0; i < kSlots; ++i) {
        Slot& s = slots_[static_cast<size_t>(i)];
        // A slot lifted in this frame waits for the next one, or Android would
        // see a jump instead of a new finger.
        if (s.down || s.reported || s.usedThisFrame) continue;
        s = Slot{};
        s.down = true;
        s.newFinger = true;
        s.usedThisFrame = true;
        s.trackingId = nextTrackingId_;
        nextTrackingId_ = nextTrackingId_ >= kMaxTrackingId ? 0 : nextTrackingId_ + 1;
        const auto raw = geometry_.toRaw(x, y);
        s.x = raw.first;
        s.y = raw.second;
        return i;
    }
    return -1;
}

void TouchSurface::move(int slot, float x, float y) {
    if (slot < 0 || slot >= kSlots) return;
    Slot& s = slots_[static_cast<size_t>(slot)];
    if (!s.down) return;
    const auto raw = geometry_.toRaw(x, y);
    if (raw.first == s.x && raw.second == s.y) return;
    s.x = raw.first;
    s.y = raw.second;
    s.moved = true;
}

void TouchSurface::up(int slot) {
    if (slot < 0 || slot >= kSlots) return;
    Slot& s = slots_[static_cast<size_t>(slot)];
    if (!s.down) return;
    s.down = false;
    s.upPending = true;
    s.usedThisFrame = true;
}

void TouchSurface::releaseAll() {
    for (int i = 0; i < kSlots; ++i) up(i);
}

bool TouchSurface::isDown(int slot) const {
    return slot >= 0 && slot < kSlots && slots_[static_cast<size_t>(slot)].down;
}

int TouchSurface::activeCount() const {
    return static_cast<int>(std::count_if(slots_.begin(), slots_.end(), [](const Slot& s) { return s.down; }));
}

std::pair<int32_t, int32_t> TouchSurface::rawPosition(int slot) const {
    if (slot < 0 || slot >= kSlots) return {-1, -1};
    const Slot& s = slots_[static_cast<size_t>(slot)];
    return {s.x, s.y};
}

void TouchSurface::frame(std::vector<InputEvent>* events, bool liftsOnly) {
    for (int i = 0; i < kSlots; ++i) {
        Slot& s = slots_[static_cast<size_t>(i)];
        const bool lift = s.upPending && s.reported && !s.newFinger;
        const bool show = !liftsOnly && s.newFinger;
        const bool moveIt = !liftsOnly && !s.newFinger && s.reported && s.moved &&
                            (s.x != s.sentX || s.y != s.sentY);
        if (!lift && !show && !moveIt) continue;
        events->push_back({EV_ABS, ABS_MT_SLOT, i});
        if (lift) {
            events->push_back({EV_ABS, ABS_MT_TRACKING_ID, -1});
            s.reported = false;
            s.upPending = false;
            continue;
        }
        if (show) {
            events->push_back({EV_ABS, ABS_MT_TRACKING_ID, s.trackingId});
            s.newFinger = false;
            s.reported = true;
        }
        if (show || s.x != s.sentX) events->push_back({EV_ABS, ABS_MT_POSITION_X, s.x});
        if (show || s.y != s.sentY) events->push_back({EV_ABS, ABS_MT_POSITION_Y, s.y});
        s.sentX = s.x;
        s.sentY = s.y;
        s.moved = false;
    }
    const bool touching = std::any_of(slots_.begin(), slots_.end(), [](const Slot& s) { return s.reported; });
    if (touching != touchReported_) {
        events->push_back({EV_KEY, BTN_TOUCH, touching ? 1 : 0});
        touchReported_ = touching;
    }
}

void TouchSurface::flush() {
    std::vector<InputEvent> events;
    frame(&events, false);
    if (!events.empty()) {
        events.push_back({EV_SYN, SYN_REPORT, 0});
        if (out_.isOpen()) out_.send(events);
    }
    // Fingers that went down and up again before anything was sent: Android has
    // seen them down now, the lift goes in a frame of its own.
    events.clear();
    frame(&events, true);
    if (!events.empty()) {
        events.push_back({EV_SYN, SYN_REPORT, 0});
        if (out_.isOpen()) out_.send(events);
    }
    for (Slot& s : slots_) s.usedThisFrame = false;
}

}  // namespace joymouse
