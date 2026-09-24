#pragma once

#include <array>
#include <cstdint>

namespace joymouse {

struct AxisRange {
    int32_t min = 0;
    int32_t max = 0;

    bool valid() const { return max > min; }
    int32_t center() const { return static_cast<int32_t>((static_cast<int64_t>(min) + max) / 2); }
};

struct StickVector {
    float x = 0.0f;
    float y = 0.0f;

    float magnitude() const;
    bool isZero() const { return x == 0.0f && y == 0.0f; }
};

// Turns the raw readings of one analog stick into a deflection vector.
//
// Cheap handheld sticks rarely reach the end of the range the driver
// advertises and differ per direction, so the usable travel is learned per
// axis and direction: it starts at kInitialExtent of the advertised range and
// grows the first time the stick is pushed further. The deadzone is radial, so
// diagonals are as smooth as the cardinal directions.
class Stick {
public:
    static constexpr float kInitialExtent = 0.65f;
    static constexpr float kOuterDeadzone = 0.95f;

    void configure(AxisRange x, AxisRange y, bool invertX, bool invertY);
    void setDeadzone(float deadzone);
    void setRaw(int axis, int32_t value);

    bool valid() const { return range_[0].valid() && range_[1].valid(); }

    // Deflection after the deadzone: direction * magnitude, magnitude in [0, 1].
    // +x is right, +y is down.
    StickVector output() const;

    // Deflection before the deadzone, 0 at rest and 1 at the learned edge.
    float rawMagnitude() const;

private:
    float normalized(int axis) const;
    StickVector scaled() const;

    std::array<AxisRange, 2> range_{};
    std::array<bool, 2> invert_{};
    std::array<int32_t, 2> raw_{};
    // [axis][0] negative direction, [axis][1] positive direction.
    std::array<std::array<float, 2>, 2> extent_{{{kInitialExtent, kInitialExtent},
                                                  {kInitialExtent, kInitialExtent}}};
    float deadzone_ = 0.1f;
};

}  // namespace joymouse
