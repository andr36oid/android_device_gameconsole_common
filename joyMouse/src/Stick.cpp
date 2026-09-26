#include "Stick.h"

#include <algorithm>
#include <cmath>

namespace joymouse {

float StickVector::magnitude() const {
    return std::hypot(x, y);
}

void Stick::configure(AxisRange x, AxisRange y, bool invertX, bool invertY) {
    range_ = {x, y};
    invert_ = {invertX, invertY};
    raw_ = {x.center(), y.center()};
    for (auto& axis : extent_) axis = {kInitialExtent, kInitialExtent};
}

void Stick::setDeadzone(float deadzone) {
    deadzone_ = std::clamp(deadzone, 0.0f, kOuterDeadzone - 0.05f);
}

void Stick::setRaw(int axis, int32_t value) {
    if (axis < 0 || axis > 1) return;
    raw_[static_cast<size_t>(axis)] = value;
    const float n = normalized(axis);
    float& extent = extent_[static_cast<size_t>(axis)][n >= 0.0f ? 1 : 0];
    extent = std::max(extent, std::min(1.0f, std::fabs(n)));
}

float Stick::normalized(int axis) const {
    const size_t i = static_cast<size_t>(axis);
    const AxisRange& r = range_[i];
    if (!r.valid()) return 0.0f;
    const double center = (static_cast<double>(r.min) + r.max) / 2.0;
    const double half = (static_cast<double>(r.max) - r.min) / 2.0;
    const double n = std::clamp((raw_[i] - center) / half, -1.0, 1.0);
    return static_cast<float>(invert_[i] ? -n : n);
}

StickVector Stick::scaled() const {
    StickVector v{normalized(0), normalized(1)};
    v.x /= extent_[0][v.x >= 0.0f ? 1 : 0];
    v.y /= extent_[1][v.y >= 0.0f ? 1 : 0];
    const float m = v.magnitude();
    if (m > 1.0f) {
        v.x /= m;
        v.y /= m;
    }
    return v;
}

float Stick::rawMagnitude() const {
    return scaled().magnitude();
}

float Stick::travel() const {
    return std::min(1.0f, StickVector{normalized(0), normalized(1)}.magnitude());
}

StickVector Stick::output() const {
    const StickVector v = scaled();
    const float m = v.magnitude();
    if (m <= deadzone_) return {};
    const float out = std::min(1.0f, (m - deadzone_) / (kOuterDeadzone - deadzone_));
    return {v.x / m * out, v.y / m * out};
}

}  // namespace joymouse
