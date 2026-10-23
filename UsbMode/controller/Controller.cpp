#include "Controller.h"

#include <algorithm>
#include <cmath>

namespace controller {
namespace {
void word(Bytes& out, uint32_t n, unsigned count) {
    while (count--) { out.push_back(n & 255); n >>= 8; }
}
void stick(uint8_t* out, int16_t value) {
    out[0] = value & 255;
    out[1] = static_cast<uint16_t>(value) >> 8;
}
int16_t invert(int16_t n) {
    return n == -32768 ? 32767 : n == 32767 ? -32768 : -n;
}
}

Policy policy(unsigned functions) {
    // Keep the old 64-mask identities. Console identities are standalone below;
    // their otherwise unused high bits leave room for the RNDIS variant.
    const unsigned set = (functions & 4095) | (functions & Rndis ? 4096 : 0);
    Policy result{"ok",0,0,static_cast<uint16_t>(functions < 64 ? kProductBase + functions : 0x4000 + set + (functions & Soundboard ? 0x2000 : 0))};
    if (!functions || functions > kAllFunctions) { result.reason = "empty"; return result; }
    const unsigned controllers = functions & kControllers;
    if (controllers && (controllers & (controllers - 1))) {
        result.reason = "controllers"; return result;
    }
    if ((functions & kConsoleControllers) && functions != controllers) {
        result.reason = "standalone"; return result;
    }
    if (functions == Switch) { result.vendor = 0x0f0d; result.product = 0x0092; }
    if (functions == Ps4) { result.vendor = 0x054c; result.product = 0x09cc; }
    if ((functions & Soundboard) && !(functions & Sound)) { result.reason = "sound"; return result; }
    if (functions == Ps3) { result.vendor = 0x054c; result.product = 0x0268; }
    if (functions == GameCube) { result.vendor = 0x057e; result.product = 0x0337; }
    if (functions == Xbox) { result.vendor = 0x045e; result.product = 0x0289; }
    if ((functions & (Network | Rndis)) == (Network | Rndis)) { result.reason = "networks"; return result; }
    if ((functions & Soundboard) && (functions & Keyboard)) { result.reason = "routing"; return result; }
    const unsigned interactive = functions & (kControllers | Media | Midi | Soundboard);
    if (interactive && (interactive & (interactive - 1))) { result.reason = "routing"; return result; }
    // Conservative five-IN envelope; ep0 and RX are reserved separately.
    std::vector<unsigned> fifos{128,128,64,32,16};
    std::vector<unsigned> needs;
    for (const auto& function : kFunctions) if (functions & function.bit) {
        result.in += function.in; result.out += function.out;
        if (function.words) needs.push_back(function.words);
        if (function.notify) needs.push_back(function.notify);
    }
    std::sort(needs.rbegin(), needs.rend());
    for (unsigned words : needs) {
        auto fifo = std::min_element(fifos.begin(), fifos.end(), [words](unsigned a, unsigned b) {
            return (a >= words ? a : 10000) < (b >= words ? b : 10000);
        });
        if (fifo == fifos.end() || *fifo < words) { result.reason = "endpoints"; return result; }
        fifos.erase(fifo);
    }
    // Reserve ep0's OUT status and two largest bulk packets in the RX FIFO.
    const unsigned rxWords = 10 + 2 * (128 + 1) + 2 * (result.out + 1);
    if (result.out > 3 || rxWords > 280) result.reason = "endpoints";
    return result;
}

unsigned parseFunctions(const std::string& text) {
    if (text == "xinput") return XInput;
    if (text == "gamepad") return Gamepad;
    if (text == "keyboard") return Keyboard | Mouse;
    if (text.empty() || text.size() > 6 || text.find_first_not_of("0123456789") != std::string::npos)
        return 0;
    return static_cast<unsigned>(std::stoi(text));
}

// Channel tags stay internal. Each HID interface has an unnumbered report.
Bytes wireReport(const Bytes& report) {
    return report.empty() ? Bytes{} : Bytes(report.begin() + 1, report.end());
}

Bytes descriptors() {
    Bytes out;
    word(out, 3, 4); word(out, 139, 4); word(out, 75, 4);
    word(out, 4, 4); word(out, 4, 4); word(out, 1, 4);
    // Wired 360 gamepad interface; HS intervals use 125 us microframes.
    for (bool high : {false, true}) {
        const Bytes desc = {
            9,4,0,0,2,0xff,0x5d,1,1,
            17,0x21,0,1,1,0x25,0x81,0x14,0,0,0,0,0x13,2,8,0,0,
            7,5,0x81,3,32,0,static_cast<uint8_t>(high ? 6 : 4),
            7,5,2,3,32,0,static_cast<uint8_t>(high ? 7 : 8),
        };
        out.insert(out.end(), desc.begin(), desc.end());
    }
    // FunctionFS uses version 1 and a compact 11-byte header, not the wire header.
    out.push_back(0); word(out, 35, 4); word(out, 1, 2); word(out, 4, 2);
    out.insert(out.end(), {1,0,0,1,'X','U','S','B','1','0',0,0});
    out.insert(out.end(), 14, 0);
    return out;
}

Bytes strings() {
    Bytes out;
    word(out, 2, 4); word(out, 18 + sizeof(kProduct), 4);
    word(out, 1, 4); word(out, 1, 4); word(out, 0x409, 2);
    out.insert(out.end(), kProduct, kProduct + sizeof(kProduct));
    return out;
}

Bytes hidDescriptor(bool keyboard) {
    if (keyboard) return {
        // HID 1.11 Appendix E.6 keyboard, without report IDs.
        0x05,1,0x09,6,0xa1,1,0x05,7,0x19,0xe0,0x29,0xe7,
        0x15,0,0x25,1,0x75,1,0x95,8,0x81,2,
        0x95,1,0x75,8,0x81,1,0x95,5,0x75,1,0x05,8,
        0x19,1,0x29,5,0x91,2,0x95,1,0x75,3,0x91,1,
        0x95,6,0x75,8,0x15,0,0x25,0x73,0x05,7,0x19,0,0x29,0x73,0x81,0,0xc0,
    };
    return {
        0x05,1,0x09,5,0xa1,1,
        0x05,9,0x19,1,0x29,16,0x15,0,0x25,1,0x75,1,0x95,16,0x81,2,
        0x05,1,0x09,0x39,0x15,0,0x25,7,0x35,0,0x46,0x3b,1,
        0x65,0x14,0x75,4,0x95,1,0x81,0x42,
        0x65,0,0x75,4,0x95,1,0x81,1,
        0x09,0x30,0x09,0x31,0x09,0x33,0x09,0x34,
        0x16,0,0x80,0x26,0xff,0x7f,0x36,0,0x80,0x46,0xff,0x7f,
        0x75,16,0x95,4,0x81,2,0xc0,
    };
}

Bytes mouseDescriptor(bool wheel) {
    // Appendix E.10 mouse; report protocol adds a wheel after the boot bytes.
    Bytes desc = {
        0x05,1,0x09,2,0xa1,1,0x09,1,0xa1,0,
        0x05,9,0x19,1,0x29,3,0x15,0,0x25,1,0x95,3,0x75,1,0x81,2,
        0x95,1,0x75,5,0x81,1,0x05,1,0x09,0x30,0x09,0x31,
    };
    if (wheel) desc.insert(desc.end(), {0x09,0x38});
    desc.insert(desc.end(), {0x15,0x81,0x25,0x7f,0x75,8,0x95,
                static_cast<uint8_t>(wheel ? 3 : 2),0x81,6,0xc0,0xc0});
    return desc;
}

ControlReply control(uint8_t type, uint8_t request, uint16_t value, uint16_t index,
                     uint16_t length, const State& state) {
    if (index == 0 && type == 0xa1 && request == 1 && value == 0x100) {
        const auto report = xinput(state);
        Bytes reply(report.begin(), report.end());
        reply.resize(std::min<size_t>(length, reply.size()));
        return {ControlAction::Reply, reply, 0};
    }
    if (index == 0 && type == 0x21 && request == 9 && value == 0x200 && length <= 32)
        return {ControlAction::Receive, {}, length};
    if (index == 0 && type == 0x41 && request == 0 && length == 0)
        return {ControlAction::Receive, {}, 0};
    // Legacy capability probes can use the descriptor's default capabilities.
    if (index == 0 && (type == 0xc1 || type == 0xc0) && request == 1 &&
            (value == 0x100 || value == 0x200))
        return {ControlAction::Reply, {}, 0};
    return {ControlAction::Stall, {}, 0};
}

int16_t scale(int value, int minimum, int maximum, int flat) {
    if (maximum <= minimum) return 0;
    const double center = (static_cast<double>(minimum) + maximum) / 2;
    const double delta = std::clamp(static_cast<double>(value),
                                    static_cast<double>(minimum), static_cast<double>(maximum)) - center;
    const double half = (static_cast<double>(maximum) - minimum) / 2;
    const double dead = std::clamp(static_cast<double>(flat), 0.0, half - 0.5);
    if (std::abs(delta) <= dead) return 0;
    const double scaled = (std::abs(delta) - dead) / (half - dead);
    return static_cast<int16_t>(std::lround(scaled * (delta < 0 ? -32768 : 32767)));
}

int button(unsigned code) {
    // Physical bottom (Nintendo B) is Xbox A; physical left (Y) is Xbox X.
    switch (code) {
    case KEY_UP: case BTN_DPAD_UP: return Up;
    case KEY_DOWN: case BTN_DPAD_DOWN: return Down;
    case KEY_LEFT: case BTN_DPAD_LEFT: return Left;
    case KEY_RIGHT: case BTN_DPAD_RIGHT: return Right;
    case BTN_START: return Start;
    case BTN_SELECT: return Back;
    case BTN_THUMBL: case KEY_BACK: return L3;
    case BTN_THUMBR: case KEY_LEFTMETA: return R3;
    case BTN_TL: return LB;
    case BTN_TR: return RB;
    case BTN_TL2: return LT;
    case BTN_TR2: return RT;
    case BTN_MODE: case KEY_HOMEPAGE: return Guide;
    case BTN_B: return A;
    case BTN_A: return B;
    case BTN_Y: return X;
    case BTN_X: return Y;
    default: return -1;
    }
}

std::array<uint8_t, 20> xinput(const State& s) {
    std::array<uint8_t, 20> out{};
    out[1] = 20;
    for (int i = Up; i <= Guide; ++i) if (s.buttons[i]) out[2 + i / 8] |= 1 << (i % 8);
    for (int i = A; i <= Y; ++i) if (s.buttons[i]) out[3] |= 1 << (4 + i - A);
    out[4] = s.buttons[LT] ? 255 : 0;
    out[5] = s.buttons[RT] ? 255 : 0;
    for (int i = 0; i < 4; ++i) stick(out.data() + 6 + 2 * i,
                                     i % 2 ? invert(s.sticks[i]) : s.sticks[i]);
    return out;
}

std::array<uint8_t, 11> gamepad(const State& s) {
    std::array<uint8_t, 11> out{};
    const Button order[] = {A,B,X,Y,LB,RB,LT,RT,Back,Start,L3,R3,Guide};
    for (unsigned i = 0; i < std::size(order); ++i) if (s.buttons[order[i]]) out[i / 8] |= 1 << (i % 8);
    const int x = static_cast<int>(s.buttons[Right]) - s.buttons[Left];
    const int y = static_cast<int>(s.buttons[Down]) - s.buttons[Up];
    const uint8_t hats[3][3] = {{7,0,1},{6,8,2},{5,4,3}};
    out[2] = hats[y + 1][x + 1];
    for (int i = 0; i < 4; ++i) stick(out.data() + 3 + 2 * i, s.sticks[i]);
    return out;
}

std::array<uint8_t, 9> keyboard(const State& s) {
    std::array<uint8_t, 9> out{};
    out[0] = 1;
    const Button buttons[] = {Up,Down,Left,Right,Start,Back};
    const uint8_t codes[] = {0x52,0x51,0x50,0x4f,0x28,0x29};
    unsigned next = 3;
    for (unsigned i = 0; i < 6; ++i) if (s.buttons[buttons[i]]) out[next++] = codes[i];
    return out;
}

std::array<uint8_t, 5> Pointer::report(const State& s) {
    // Retain subpixel motion so small stick movements still move the pointer.
    auto motion = [&](unsigned axis) {
        if (s.sticks[axis] == 0) { fraction_[axis] = 0; return uint8_t{0}; }
        const double f = s.sticks[axis] / 32768.0;
        fraction_[axis] += f * std::abs(f) * 4;
        const int step = static_cast<int>(std::lround(fraction_[axis]));
        fraction_[axis] -= step;
        return static_cast<uint8_t>(static_cast<int8_t>(step));
    };
    return {2,static_cast<uint8_t>((s.buttons[B] ? 1 : 0) | (s.buttons[A] ? 2 : 0)),
            motion(0),motion(1),0};
}

std::array<uint8_t, 5> mouse(const State& s) {
    Pointer pointer;
    return pointer.report(s);
}

void SessionControls::update(const State& state, int64_t time, bool request, bool power, bool screenReady) {
    request = request || (state.buttons[Back] && state.buttons[RB]);
    const bool combo = state.buttons[Back] && state.buttons[Start];
    const bool route = state.buttons[Back] && state.buttons[LB];
    if (!state.buttons[Back] && !state.buttons[Start] && !state.buttons[LB]) suppress_ = false;
    if (dark_) {
        if (combo || power) { dark_ = false; suppress_ = true; }
        exitSince_ = -1;
    } else if (power) {
        exit_ = true;
    } else if (!suppress_) {
        if (!macro_ && route && !routeHeld_ && (functions_ & Keyboard) && (functions_ & (kControllers | Media | Midi | Soundboard))) {
            keyboard_ = !keyboard_; suppress_ = true; exitSince_ = -1;
        } else if (combo) {
            if (exitSince_ < 0) exitSince_ = time;
            if (time - exitSince_ >= 2000) exit_ = true;
        } else exitSince_ = -1;
    }
    routeHeld_ = route;
    if (request && !requestHeld_ && canDark() && !dark_) { hintSince_ = time; suppress_ = true; }
    requestHeld_ = request;
    if (hintSince_ >= 0 && (!canDark() || combo || power)) hintSince_ = -1;
    if (hintSince_ >= 0 && !screenReady) hintSince_ = time;
    if (hintSince_ >= 0 && time - hintSince_ >= 2000) {
        hintSince_ = -1; dark_ = true; exitSince_ = -1;
    }
}
State SessionControls::gamepadState(const State& state) const {
    if (suppress_ || keyboard_ || hint()) return {};
    State result = state;
    // Reserve both exit buttons before a held combo can reach the PC.
    if ((dark_ && !(functions_ & Soundboard)) || (state.buttons[Back] && state.buttons[Start])) result.buttons[Back] = result.buttons[Start] = false;
    return result;
}
State SessionControls::keyboardState(const State& state) const {
    if (!keyboard_ || (dark_ && !macro_) || hint() || suppress_) return {};
    State result = state;
    if (state.buttons[Back] && state.buttons[Start]) result.buttons[Back] = result.buttons[Start] = false;
    return result;
}

int Jiggler::delta(int64_t time, unsigned seconds, bool connected) {
    if (seconds != 30 && seconds != 60 && seconds != 120 && seconds != 300) seconds = 0;
    if (!connected || !seconds) { due_ = -1; seconds_ = seconds; back_ = false; return 0; }
    if (due_ < 0 || seconds != seconds_) { seconds_ = seconds; due_ = time + seconds * 1000; back_ = false; }
    return time >= due_ ? back_ ? -1 : 1 : 0;
}
void Jiggler::sent(int64_t time) {
    due_ = time + (back_ ? seconds_ * 1000 : 20); back_ = !back_;
}

bool ExitHold::update(bool held, int64_t now) {
    if (!held) since_ = -1;
    else if (since_ < 0) since_ = now;
    return since_ >= 0 && now - since_ >= 2000;
}

}  // namespace controller
