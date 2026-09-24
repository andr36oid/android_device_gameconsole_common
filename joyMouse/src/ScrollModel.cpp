#include "ScrollModel.h"

#include <algorithm>
#include <cmath>

namespace joymouse {

namespace {

// Scrolling is nearly always meant to be straight up/down or sideways, so a
// clearly dominant axis suppresses the other one.
constexpr float kAxisLockRatio = 1.5f;

}  // namespace

float ScrollModel::rateFor(float magnitude) const {
    if (magnitude <= 0.0f) return 0.0f;
    const float m = std::min(magnitude, 1.0f);
    return params_.minRate + (params_.maxRate - params_.minRate) * std::pow(m, params_.curve);
}

void ScrollModel::setTarget(float x, float y, float gain) {
    if (std::fabs(y) >= kAxisLockRatio * std::fabs(x)) {
        x = 0.0f;
    } else if (std::fabs(x) >= kAxisLockRatio * std::fabs(y)) {
        y = 0.0f;
    }
    const float g = std::max(gain, 0.0f);
    x_.rate = std::copysign(rateFor(std::fabs(x)) * g, x);
    y_.rate = std::copysign(rateFor(std::fabs(y)) * g, y);
}

int ScrollModel::Axis::step(double dtSec) {
    if (rate == 0.0f) {
        acc = 0.0;
        dir = 0;
        return 0;
    }
    const int want = rate > 0.0f ? 1 : -1;
    if (dir != want) {
        // Just started, or reversed: one detent right away.
        dir = want;
        acc = want;
    } else {
        acc += static_cast<double>(rate) * dtSec;
    }
    const int out = static_cast<int>(std::trunc(acc));
    acc -= out;
    return out;
}

ScrollModel::Detents ScrollModel::step(double dtSec) {
    Detents d;
    d.x = x_.step(dtSec);
    d.y = y_.step(dtSec);
    return d;
}

bool ScrollModel::active() const {
    return x_.rate != 0.0f || y_.rate != 0.0f;
}

void ScrollModel::stop() {
    x_ = Axis{};
    y_ = Axis{};
}

}  // namespace joymouse
