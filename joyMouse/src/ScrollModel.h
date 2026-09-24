#pragma once

namespace joymouse {

struct ScrollParams {
    float minRate = 3.0f;    // wheel detents per second just outside the deadzone
    float maxRate = 14.0f;   // ... and at full deflection
    float curve = 1.6f;
};

// Turns stick deflection into mouse wheel detents. Android 11 only knows whole
// detents (no hi-res wheel), so the rate of detents is what gets controlled.
// The first detent fires as soon as the stick leaves the deadzone to keep
// scrolling responsive at low rates.
class ScrollModel {
public:
    struct Detents {
        int x = 0;  // + = right
        int y = 0;  // + = down (stick direction; the wheel sign is applied later)
        bool isZero() const { return x == 0 && y == 0; }
    };

    void setParams(const ScrollParams& params) { params_ = params; }
    const ScrollParams& params() const { return params_; }

    // (x, y): stick deflection, magnitude in [0, 1].
    void setTarget(float x, float y, float gain);
    Detents step(double dtSec);
    bool active() const;
    void stop();

    // Detents per second for a deflection magnitude in [0, 1].
    float rateFor(float magnitude) const;

private:
    struct Axis {
        float rate = 0.0f;  // signed detents/s
        double acc = 0.0;
        int dir = 0;

        int step(double dtSec);
    };

    ScrollParams params_;
    Axis x_, y_;
};

}  // namespace joymouse
