#pragma once

#include <array>
#include <cstdint>
#include <utility>
#include <vector>

#include "Controller.h"

namespace joymouse {

// The screen as touches see it.
struct TouchGeometry {
    // Size in the display's natural orientation (Surface.ROTATION_0). This is
    // the raw range of the virtual touchscreen; Android rotates its touches
    // along with the display.
    int naturalWidth = 640;
    int naturalHeight = 480;
    int rotation = 0;  // 0-3, quarter turns like Surface.ROTATION_*

    int visibleWidth() const { return rotation % 2 == 0 ? naturalWidth : naturalHeight; }
    int visibleHeight() const { return rotation % 2 == 0 ? naturalHeight : naturalWidth; }
    int shorterSide() const { return naturalWidth < naturalHeight ? naturalWidth : naturalHeight; }
    // A point on the screen as the app shows it (pixels) -> raw touchscreen
    // coordinates, undoing what Android's TouchInputMapper does for the rotation.
    std::pair<int32_t, int32_t> toRaw(float x, float y) const;

    bool operator==(const TouchGeometry& o) const {
        return naturalWidth == o.naturalWidth && naturalHeight == o.naturalHeight && rotation == o.rotation;
    }
    bool operator!=(const TouchGeometry& o) const { return !(*this == o); }
};

// Fingers on the virtual multi-touch screen (protocol B: slots and tracking
// IDs). Changes are collected and go out as one frame on flush().
class TouchSurface {
public:
    static constexpr int kSlots = 10;
    static constexpr int32_t kMaxTrackingId = 0xffff;

    explicit TouchSurface(OutputPort& out) : out_(out) {}

    void setGeometry(const TouchGeometry& g) { geometry_ = g; }
    const TouchGeometry& geometry() const { return geometry_; }

    // Puts a finger down at a point (visible pixels). Returns its slot, or -1
    // if all fingers are in use.
    int down(float x, float y);
    void move(int slot, float x, float y);
    void up(int slot);
    void releaseAll();
    // Sends what changed since the last flush.
    void flush();

    bool isDown(int slot) const;
    int activeCount() const;
    // Raw position of a finger, for tests.
    std::pair<int32_t, int32_t> rawPosition(int slot) const;

private:
    struct Slot {
        bool down = false;       // as far as the owner is concerned
        bool reported = false;   // as far as Android knows
        bool upPending = false;  // lifted, not sent yet
        bool newFinger = false;  // went down, not sent yet
        bool moved = false;
        bool usedThisFrame = false;  // lifted this frame: Android must see the lift first
        int32_t trackingId = -1;
        int32_t x = 0, y = 0;
        int32_t sentX = -1, sentY = -1;
    };

    void frame(std::vector<InputEvent>* events, bool liftsOnly);

    OutputPort& out_;
    TouchGeometry geometry_;
    std::array<Slot, kSlots> slots_{};
    int32_t nextTrackingId_ = 0;
    bool touchReported_ = false;
};

}  // namespace joymouse
