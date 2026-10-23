#include "Keyboard.h"

#include <algorithm>
#include <cmath>

namespace controller {
namespace {
std::array<float, 2> radial(int16_t x, int16_t y, float deadzone) {
    const float fx = x / 32768.0f, fy = y / 32768.0f;
    const float magnitude = std::hypot(fx, fy);
    if (magnitude <= deadzone) return {};
    const float gain = std::min(1.0f, (magnitude - deadzone) / (1 - deadzone)) / magnitude;
    return {fx * gain, fy * gain};
}
}

KeyboardPointer::KeyboardPointer() {
    joymouse::PointerParams params;
    // The PC applies its own pointer acceleration, rather than Android's.
    params.compensateAndroidAccel = false;
    pointer_.setParams(params);
}

std::array<uint8_t, 5> KeyboardPointer::report(const State& s, uint8_t buttons, bool scroll,
                                             int wheel, double seconds) {
    seconds = std::clamp(seconds, 0.0, 0.03);
    const auto p = radial(s.sticks[0], s.sticks[1], 0.10f);
    pointer_.setTarget(scroll ? 0 : p[0], scroll ? 0 : p[1], 1);
    const auto delta = pointer_.step(seconds);
    const auto v = radial(scroll ? s.sticks[0] : s.sticks[2],
                          scroll ? s.sticks[1] : s.sticks[3], 0.20f);
    scroll_.setTarget(0, wheel ? static_cast<float>(wheel) : -v[1], 1);
    const int detents = scroll_.step(seconds).y;
    auto byte = [](int n) { return static_cast<uint8_t>(static_cast<int8_t>(std::clamp(n, -127, 127))); };
    return {2,static_cast<uint8_t>(buttons & 7),byte(delta.dx),byte(delta.dy),byte(detents)};
}

}  // namespace controller
