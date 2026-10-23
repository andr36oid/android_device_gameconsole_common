#define main controllerEntry
#include "../../controller/main.cpp"
#undef main

#include <cassert>
#include <set>

int main() {
    std::set<uint16_t> products;
    int valid = 0;
    for (unsigned mask = 1; mask < 64; ++mask) {
        const auto p = policy(mask);
        const bool twoControllers = (mask & 6) == 6;
        assert((std::string(p.reason) == "ok") == !twoControllers);
        if (twoControllers) continue;
        ++valid;
        assert(products.insert(p.product).second);
        unsigned in = 0, out = 0;
        // Independently count the interfaces that consume a USB endpoint.
        for (unsigned bit = 1; bit <= 32; bit <<= 1) if (mask & bit) ++in;
        for (unsigned bit : {1u,2u,32u}) if (mask & bit) ++out;
        assert(p.in == in && p.out == out && in <= 5 && out <= 3);
        assert(p.product >= 0xd401 && p.product <= 0xd43f);
    }
    assert(valid == 47 && std::string(policy(0).reason) == "empty");
    assert(std::string(policy(kAllFunctions + 1).reason) != "ok");
    assert(parseFunctions(";reboot") == 0 && parseFunctions("64") == 64);
    assert(policy(25).product != policy(57).product);
    assert(wireReport({1,0x81,0,4,5,6,7,8,9}) == (Bytes{0x81,0,4,5,6,7,8,9}));
    assert(wireReport({2,7,127,129,255}) == (Bytes{7,127,129,255}));

    SessionControls setup(Storage | Keyboard | Mouse);
    setup.update(State{}, 0, true, false);
    assert(!setup.hint() && !setup.dark() && !setup.canDark());
    State combo; combo.buttons[Back] = combo.buttons[Start] = true;
    setup.update(combo, 1, false, false);
    assert(!setup.exit()); setup.update(combo, 2001, false, false); assert(setup.exit());

    SessionControls session(Storage | Gamepad | Keyboard | Mouse);
    State action; action.buttons[A] = true; action.sticks[0] = 32767;
    assert(session.gamepadState(action).buttons[A] == false);
    assert(session.keyboardState(action).buttons[A]);
    State route; route.buttons[Back] = route.buttons[LB] = true;
    session.update(route, 0, false, false);
    assert(!session.keyboardScreen() && session.canDark() && session.suppress());
    assert(!session.gamepadState(route).buttons[LB]);
    assert(!session.keyboardState(action).buttons[A]);
    session.update(State{}, 1, false, false);
    assert(session.gamepadState(action).buttons[A]);
    session.update(State{}, 2, true, false);
    assert(session.hint() && !session.dark());
    session.update(State{}, 2001, true, false); assert(!session.dark());
    session.update(State{}, 2002, true, false); assert(session.dark());
    State select; select.buttons[Back] = true;
    assert(!session.gamepadState(select).buttons[Back]);
    session.update(combo, 2100, true, false);
    assert(!session.dark() && !session.exit() && session.suppress());
    session.update(combo, 10100, true, false);
    assert(!session.exit() && !session.gamepadState(combo).buttons[Start]);
    session.update(State{}, 10101, false, false);
    session.update(combo, 10102, false, false);
    session.update(combo, 12102, false, false); assert(session.exit());

    SessionControls power(Gamepad);
    power.update(State{}, 0, true, false); power.update(State{}, 2000, true, false);
    assert(power.dark()); power.update(State{}, 2001, true, true);
    assert(!power.dark() && !power.exit());
    power.update(State{}, 2002, false, false); power.update(State{}, 2003, false, true); assert(power.exit());
    SessionControls shortcut(Storage);
    State off; off.buttons[Back] = off.buttons[RB] = true;
    shortcut.update(off, 0, false, false); assert(shortcut.hint());
    shortcut.update(off, 2000, false, false); assert(shortcut.dark());

    SessionControls waiting(Storage);
    waiting.update(State{}, 0, true, false, false);
    waiting.update(State{}, 60000, true, false, false);
    assert(waiting.hint() && !waiting.dark());
    waiting.update(State{}, 60001, true, false, true);
    waiting.update(State{}, 61999, true, false, true); assert(!waiting.dark());
    waiting.update(State{}, 62000, true, false, true); assert(waiting.dark());

    // Real nonblocking sockets model two HID slots with independently stalled hosts.
    int key[2], mouse[2];
    assert(socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_NONBLOCK, 0, key) == 0);
    assert(socketpair(AF_UNIX, SOCK_SEQPACKET | SOCK_NONBLOCK, 0, mouse) == 0);
    auto fill = [](int fd) {
        const uint8_t data[32]{};
        while (write(fd, data, sizeof(data)) == sizeof(data)) { }
        assert(errno == EAGAIN);
    };
    auto drain = [](int fd) { uint8_t data[64]; while (read(fd, data, sizeof(data)) > 0) { } };
    uint8_t received[16]{};
    KeyboardChannel queued;
    queued.release(); fill(mouse[0]);
    assert(sendKeyboard(queued, key[0], mouse[0], true));
    assert(read(key[1], received, sizeof(received)) == 8);
    assert(!queued.next(1) && queued.next(2));
    drain(mouse[1]);
    assert(sendKeyboard(queued, key[0], mouse[0], true));
    assert(read(mouse[1], received, sizeof(received)) == 4 && queued.pending().empty());
    queued.release(); fill(key[0]);
    assert(sendKeyboard(queued, key[0], mouse[0], true));
    assert(read(mouse[1], received, sizeof(received)) == 4);
    assert(queued.next(1) && !queued.next(2));
    drain(key[1]);
    assert(sendKeyboard(queued, key[0], mouse[0], true));
    assert(read(key[1], received, sizeof(received)) == 8 && queued.pending().empty());
    for (int fd : {key[0],key[1],mouse[0],mouse[1]}) close(fd);
    puts("Independently stalled keyboard/mouse endpoints preserve the other boot function and both neutral releases");

    const std::string root = std::string(getenv("TMPDIR")) + "/backlight";
    assert(mkdir(root.c_str(), 0700) == 0);
    assert(mkdir((root + "/sys").c_str(), 0700) == 0);
    assert(mkdir((root + "/sys/lcd").c_str(), 0700) == 0);
    assert(mkdir((root + "/state").c_str(), 0700) == 0);
    const std::string level = root + "/sys/lcd/brightness";
    int fd = open(level.c_str(), O_WRONLY | O_CREAT, 0600); assert(fd >= 0); close(fd);
    assert(writeText(level, "173\n"));
    {
        Backlight light(root + "/sys", root + "/state");
        assert(light.dark(true));
        assert(light.dark(true));
        assert(readText(level) == "0");
        assert(readText(root + "/state/backlight-lcd") == "173\n");
        assert(light.restore()); assert(readText(level) == "173\n");
    }
    unlink(level.c_str()); rmdir((root + "/sys/lcd").c_str()); rmdir((root + "/sys").c_str()); rmdir((root + "/state").c_str()); rmdir(root.c_str());
    puts("47 USB function sets: shared budget, unique PIDs, wire packing, exclusive routing, hint, dark wake suppression, exit and brightness journal passed");
}
