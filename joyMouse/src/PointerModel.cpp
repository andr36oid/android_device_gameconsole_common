#include "PointerModel.h"

#include <algorithm>
#include <cmath>

namespace joymouse {

namespace {

// Default pointer VelocityControlParameters of the Android 11 input reader:
// no change below 500 px/s, ramping linearly to 3x at 3000 px/s.
constexpr float kAccelLow = 500.0f;
constexpr float kAccelHigh = 3000.0f;
constexpr float kAccelMax = 3.0f;

}  // namespace

float PointerModel::androidAccel(float v) {
    if (v <= kAccelLow) return v;
    if (v >= kAccelHigh) return v * kAccelMax;
    return v * (1.0f + (v - kAccelLow) / (kAccelHigh - kAccelLow) * (kAccelMax - 1.0f));
}

float PointerModel::androidAccelInverse(float target) {
    if (target <= kAccelLow) return target;
    if (target >= kAccelHigh * kAccelMax) return target / kAccelMax;
    // Solve v * (1 + k * (v - low)) = target for v.
    const float k = (kAccelMax - 1.0f) / (kAccelHigh - kAccelLow);
    const float b = 1.0f - k * kAccelLow;
    return (-b + std::sqrt(b * b + 4.0f * k * target)) / (2.0f * k);
}

float PointerModel::speedFor(float magnitude) const {
    if (magnitude <= 0.0f) return 0.0f;
    const float m = std::min(magnitude, 1.0f);
    const float minFraction = std::clamp(params_.minSpeedFraction, 0.0f, 1.0f);
    return params_.topSpeed * (minFraction + (1.0f - minFraction) * std::pow(m, params_.curve));
}

void PointerModel::setTarget(float x, float y, float gain) {
    const float m = std::hypot(x, y);
    if (m <= 0.0f || gain <= 0.0f) {
        targetX_ = targetY_ = 0.0f;
        return;
    }
    const float speed = speedFor(m) * gain;
    targetX_ = x / m * speed;
    targetY_ = y / m * speed;
}

PointerModel::Delta PointerModel::step(double dtSec) {
    if (targetX_ == 0.0f && targetY_ == 0.0f) {
        // Letting go of the stick stops the pointer dead: no glide, no overshoot.
        stop();
        return {};
    }

    const float dt = static_cast<float>(std::max(dtSec, 0.0));
    const bool speedingUp = std::hypot(targetX_, targetY_) > std::hypot(velX_, velY_);
    const float tau = std::max(speedingUp ? params_.attackSec : params_.releaseSec, 1e-4f);
    const float a = 1.0f - std::exp(-dt / tau);
    velX_ += a * (targetX_ - velX_);
    velY_ += a * (targetY_ - velY_);

    const float speed = std::hypot(velX_, velY_);
    float emitScale = 1.0f;
    if (params_.compensateAndroidAccel && speed > 0.0f) {
        emitScale = androidAccelInverse(speed) / speed;
    }

    remX_ += static_cast<double>(velX_ * emitScale) * dtSec;
    remY_ += static_cast<double>(velY_ * emitScale) * dtSec;
    Delta d;
    d.dx = static_cast<int>(std::trunc(remX_));
    d.dy = static_cast<int>(std::trunc(remY_));
    remX_ -= d.dx;
    remY_ -= d.dy;
    return d;
}

bool PointerModel::active() const {
    return targetX_ != 0.0f || targetY_ != 0.0f || velX_ != 0.0f || velY_ != 0.0f;
}

void PointerModel::stop() {
    targetX_ = targetY_ = 0.0f;
    velX_ = velY_ = 0.0f;
    remX_ = remY_ = 0.0;
}

}  // namespace joymouse
