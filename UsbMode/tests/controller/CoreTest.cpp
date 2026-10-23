#include "Controller.h"

#include <cassert>
#include <cstdio>
#include <string>

using namespace controller;
static Bytes hex(const char* text) {
    Bytes bytes;
    unsigned value;
    int used;
    while (sscanf(text, "%x%n", &value, &used) == 1) { bytes.push_back(value); text += used; }
    return bytes;
}
int main() {
    const auto d = descriptors();
    // GP2040-CE wired gamepad interface, with iInterface=1 and 4/8 ms periods.
    const auto reference = hex("09 04 00 00 02 ff 5d 01 01 11 21 00 01 01 25 81 14 00 00 00 00 13 02 08 00 00 07 05 81 03 20 00 04 07 05 02 03 20 00 08");
    assert(d.size() == 139 && d[4] == d.size() && d[8] == 75);
    assert(Bytes(d.begin() + 24, d.begin() + 64) == reference);
    auto high = reference; high[32] = 6; high[39] = 7;
    assert(Bytes(d.begin() + 64, d.begin() + 104) == high);
    assert(Bytes(d.begin() + 104, d.end()) == hex("00 23 00 00 00 01 00 04 00 01 00 00 01 58 55 53 42 31 30 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00"));
    const auto str = strings();
    assert(str.size() == str[4] && str[16] == 9 && str[17] == 4);
    assert(std::string(str.begin() + 18, str.end() - 1) == "andr36oid XInput Device");
    State s;
    auto r = xinput(s);
    assert(r[0] == 0 && r[1] == 20);
    for (unsigned i = 2; i < r.size(); ++i) assert(r[i] == 0);
    for (int b = 0; b < Count; ++b) {
        State one; one.buttons[b] = true;
        const auto packed = xinput(one);
        if (b <= Guide) assert(packed[2 + b / 8] == (1 << (b % 8)));
        if (b >= A && b <= Y) assert(packed[3] == (1 << (4 + b - A)));
        if (b == LT) assert(packed[4] == 255);
        if (b == RT) assert(packed[5] == 255);
    }
    s.buttons.fill(true); s.sticks = {-32768,32767,32767,-32768};
    r = xinput(s);
    assert(Bytes(r.begin(), r.end()) == hex("00 14 ff f7 ff ff 00 80 00 80 ff 7f ff 7f 00 00 00 00 00 00"));
    assert(scale(-1000, -1000, 1000) == -32768);
    assert(scale(1000, -1000, 1000) == 32767);
    assert(scale(0, -1000, 1000) == 0);
    assert(scale(512, 0, 1024) == 0);
    assert(scale(90, -1000, 1000, 100) == 0);
    assert(scale(1000, -1000, 1000, 100) == 32767);
    assert(scale(-2000, -1000, 1000) == -32768);
    assert(scale(20, 5, 5) == 0);
    for (int n = -2000; n < 2000; ++n) assert(scale(n, -1800, 1800) <= scale(n+1, -1800, 1800));
    assert(button(BTN_B) == A && button(BTN_A) == B);
    assert(button(BTN_Y) == X && button(BTN_X) == Y);
    assert(button(KEY_BACK) == L3 && button(KEY_LEFTMETA) == R3);
    assert(button(KEY_HOMEPAGE) == Guide && button(KEY_POWER) == -1);
    assert(gamepad(State{})[2] == 8);
    const Button hats[][2] = {{Up,Up},{Up,Right},{Right,Right},{Down,Right},{Down,Down},{Down,Left},{Left,Left},{Up,Left}};
    for (unsigned i = 0; i < 8; ++i) {
        State h; h.buttons[hats[i][0]] = h.buttons[hats[i][1]] = true;
        assert(gamepad(h)[2] == i);
    }
    State keys; keys.buttons[Up] = keys.buttons[Start] = keys.buttons[Back] = true;
    auto k = keyboard(keys);
    assert(k[0] == 1 && k[3] == 0x52 && k[4] == 0x28 && k[5] == 0x29 && k[6] == 0);
    State pointer; pointer.buttons[B] = true; pointer.sticks = {32767,-32768,0,0};
    assert(mouse(pointer) == (std::array<uint8_t,5>{2,1,4,252,0}));
    for (unsigned combination = 0; combination < 16; ++combination) {
        State clicks;
        clicks.buttons[B] = combination & 1; clicks.buttons[L3] = combination & 2;
        clicks.buttons[A] = combination & 4; clicks.buttons[R3] = combination & 8;
        const uint8_t expected = (combination & 3 ? 1 : 0) | (combination & 4 ? 2 : 0) | (combination & 8 ? 4 : 0);
        assert(mouseButtons(clicks) == expected && mouse(clicks)[1] == expected);
    }
    Pointer fine;
    State slow; slow.sticks[0] = 8192;
    int distance = 0;
    for (int i = 0; i < 40; ++i) distance += static_cast<int8_t>(fine.report(slow)[2]);
    assert(distance == 10);
    assert(fine.report(State{})[2] == 0);
    auto reply = control(0xa1, 1, 0x100, 0, 5, s);
    assert(reply.action == ControlAction::Reply && reply.data.size() == 5);
    assert(reply.data[1] == 20);
    assert(control(0xa1, 1, 0x100, 0, 100, s).data.size() == 20);
    assert(control(0x21, 9, 0x200, 0, 8, s).action == ControlAction::Receive);
    assert(control(0x21, 9, 0x200, 0, 33, s).action == ControlAction::Stall);
    assert(control(0x41, 0, 0, 0, 0, s).action == ControlAction::Receive);
    assert(control(0xc1, 1, 0x100, 0, 20, s).action == ControlAction::Reply);
    assert(control(0xc0, 1, 0, 4, 40, s).action == ControlAction::Stall);
    assert(control(0xff, 255, 0, 0, 65535, s).action == ControlAction::Stall);
    assert(control(0xa1, 1, 0x100, 1, 20, s).action == ControlAction::Stall);
    ExitHold hold;
    assert(!hold.update(true, 100)); assert(!hold.update(true, 2099));
    assert(hold.update(true, 2100)); assert(!hold.update(false, 2101));
    assert(!hold.update(true, 2200)); assert(!hold.update(true, 3000));
    assert(hold.update(true, 4200));
    puts("Controller descriptor, mapping, scaling, HID reports and exit tests passed");
}
