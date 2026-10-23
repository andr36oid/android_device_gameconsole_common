#pragma once

#include <array>
#include <cstdint>
#include <vector>
#include <string>
#include <linux/input.h>

namespace controller {

// Private development IDs, not USB-IF allocations. One PID per interface set.
constexpr uint16_t kVendor = 0x18d1;
constexpr uint16_t kProductBase = 0xd400;
constexpr const char kProduct[] = "andr36oid XInput Device";
enum Function { Storage = 1, XInput = 2, Gamepad = 4, Keyboard = 8, Mouse = 16, Adb = 32, Media = 64, Switch = 128, Midi = 256,
    Serial = 512, Sound = 1024, Network = 2048, Ps3 = 4096, GameCube = 8192, Xbox = 16384, Rndis = 32768, Ps4 = 65536, Soundboard = 131072, Flipper = 262144 };
constexpr unsigned kAllFunctions = 524287;
constexpr unsigned kControllers = XInput | Gamepad | Switch | Ps3 | GameCube | Xbox | Ps4;
constexpr unsigned kConsoleControllers = Switch | Ps3 | GameCube | Xbox | Ps4;
struct FunctionBudget { unsigned bit, in, out, words, notify = 0; };
constexpr FunctionBudget kFunctions[] = {
    {Storage,1,1,128}, {XInput,1,1,8}, {Gamepad,1,0,3},
    {Keyboard,1,0,2}, {Mouse,1,0,1}, {Adb,1,1,128},
    {Media,1,0,3}, {Switch,1,1,16}, {Midi,1,1,128},
    {Serial,2,1,128,3}, {Sound,0,1,0}, {Network,2,1,128,4},
    {Rndis,2,1,128,2}, {Ps3,1,1,16}, {GameCube,1,1,16}, {Xbox,1,1,8}, {Ps4,1,1,16}, {Soundboard,1,0,48},
};
struct Policy { const char* reason; unsigned in, out; uint16_t product; uint16_t vendor = kVendor; };
Policy endpointBudget(const std::vector<FunctionBudget>& functions);
Policy policy(unsigned functions);
unsigned parseFunctions(const std::string& text);

using Bytes = std::vector<uint8_t>;
enum Button { Up, Down, Left, Right, Start, Back, L3, R3, LB, RB, Guide, A, B, X, Y, LT, RT, Count };
struct State {
    std::array<bool, Count> buttons{};
    uint8_t battery = 100, sequence = 0;
    bool charging = false;
    uint16_t timestamp = 0;
    std::array<int16_t, 4> sticks{};  // evdev Y is positive down
};

Bytes descriptors();
Bytes strings();
Bytes hidDescriptor(bool keyboard);
Bytes mouseDescriptor(bool wheel = true);
Bytes wireReport(const Bytes& report);
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

class SessionControls {
public:
    explicit SessionControls(unsigned functions, bool macro = false, bool disc = false) : functions_(functions), keyboard_(functions & Keyboard), macro_(macro), disc_(disc) { }
    void update(const State& state, int64_t time, bool darkRequest, bool power, bool screenReady = true);
    State gamepadState(const State& state) const;
    State keyboardState(const State& state) const;
    bool keyboardScreen() const { return keyboard_; }
    bool dark() const { return dark_; }
    bool hint() const { return hintSince_ >= 0; }
    bool exit() const { return exit_; }
    bool suppress() const { return suppress_; }
    bool canDark() const { return macro_ || !(functions_ & Keyboard) || !keyboard_; }
private:
    unsigned functions_;
    bool keyboard_, macro_, disc_, dark_ = false, exit_ = false, suppress_ = false, routeHeld_ = false;
    bool requestHeld_ = false;
    int64_t hintSince_ = -1, exitSince_ = -1;
};

class Jiggler {
public:
    int delta(int64_t time, unsigned seconds, bool connected);
    void sent(int64_t time);
private:
    int64_t due_ = -1;
    unsigned seconds_ = 0;
    bool back_ = false;
};

class ExitHold {
public:
    bool update(bool held, int64_t now);
private:
    int64_t since_ = -1;
};

}  // namespace controller
