#pragma once

#include <array>
#include <cstdint>
#include <vector>
#include <linux/input.h>

namespace controller {

// Private development IDs, not USB-IF allocations. Keep all mode IDs here.
constexpr uint16_t kVendor = 0x18d1;
constexpr uint16_t kXInputProduct = 0xd360;
constexpr uint16_t kGamepadProduct = 0xd361;
constexpr uint16_t kKeyboardProduct = 0xd362;
constexpr const char kProduct[] = "andr36oid XInput Device";

using Bytes = std::vector<uint8_t>;
enum Button { Up, Down, Left, Right, Start, Back, L3, R3, LB, RB, Guide, A, B, X, Y, LT, RT, Count };
struct State {
    std::array<bool, Count> buttons{};
    std::array<int16_t, 4> sticks{};  // evdev Y is positive down
};

Bytes descriptors();
Bytes strings();
Bytes hidDescriptor(bool keyboard);
enum class ControlAction { Stall, Reply, Receive };
struct ControlReply { ControlAction action; Bytes data; unsigned length; };
ControlReply control(uint8_t type, uint8_t request, uint16_t value, uint16_t index,
                     uint16_t length, const State& state);
std::array<uint8_t, 20> xinput(const State& state);
std::array<uint8_t, 11> gamepad(const State& state);
std::array<uint8_t, 9> keyboard(const State& state);
std::array<uint8_t, 5> mouse(const State& state);
class Pointer {
public:
    std::array<uint8_t, 5> report(const State& state);
private:
    std::array<double, 2> fraction_{};
};
int16_t scale(int value, int minimum, int maximum, int flat = 0);
int button(unsigned code);

class ExitHold {
public:
    bool update(bool held, int64_t now);
private:
    int64_t since_ = -1;
};

}  // namespace controller
