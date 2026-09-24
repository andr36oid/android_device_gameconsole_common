#pragma once

namespace joymouse {

struct PointerParams {
    float topSpeed = 720.0f;          // on-screen px/s at full deflection
    float minSpeedFraction = 0.02f;   // speed just outside the deadzone, relative to topSpeed
    float curve = 2.2f;               // response exponent
    float attackSec = 0.040f;         // smoothing time constant while speeding up
    float releaseSec = 0.015f;        // ... and while slowing down
    bool compensateAndroidAccel = true;
};

// Rate control: stick deflection sets a pointer velocity, which is integrated
// into whole-pixel deltas. Fractional pixels carry over between steps, so slow
// movements stay smooth instead of being rounded away or up.
class PointerModel {
public:
    struct Delta {
        int dx = 0;
        int dy = 0;
        bool isZero() const { return dx == 0 && dy == 0; }
    };

    void setParams(const PointerParams& params) { params_ = params; }
    const PointerParams& params() const { return params_; }

    // (x, y): stick deflection, magnitude in [0, 1]. gain scales the speed.
    void setTarget(float x, float y, float gain);
    Delta step(double dtSec);
    bool active() const;
    // Halt immediately and drop the sub-pixel remainder.
    void stop();

    // On-screen speed in px/s for a deflection magnitude in [0, 1].
    float speedFor(float magnitude) const;

    // Android's cursor mapper speeds up fast movements (VelocityControl). These
    // mirror its default curve so the configured speeds are what ends up on
    // screen.
    static float androidAccel(float emittedSpeed);
    static float androidAccelInverse(float onScreenSpeed);

private:
    PointerParams params_;
    float targetX_ = 0.0f, targetY_ = 0.0f;  // px/s
    float velX_ = 0.0f, velY_ = 0.0f;        // smoothed, px/s
    double remX_ = 0.0, remY_ = 0.0;         // sub-pixel remainder
};

}  // namespace joymouse
