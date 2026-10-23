#define main controllerEntry
#include "../../controller/main.cpp"
#undef main

#include <cassert>
#include <sys/wait.h>

std::string startOf(pid_t pid) {
    std::string stat = readText("/proc/" + std::to_string(pid) + "/stat");
    stat.erase(0, stat.rfind(')') + 2);
    for (int field = 3; field < 22; ++field) stat.erase(0, stat.find(' ') + 1);
    return stat.substr(0, stat.find(' '));
}

int main() {
    KeyboardPointer pointer;
    State state;
    state.sticks[0] = 3000;
    for (int i = 0; i < 100; ++i) assert(pointer.report(state, 0, false, 0, 0.008)[2] == 0);
    state.sticks[0] = 32767;
    int total = 0;
    for (int i = 0; i < 125; ++i) total += static_cast<int8_t>(pointer.report(state, 7, false, 0, 0.008)[2]);
    assert(total > 670 && total < 725); // joyMouse's 720 px/s, including its 40 ms attack
    auto r = pointer.report(State{}, 7, false, 0, 0.008);
    assert(r == (std::array<uint8_t, 5>{2,7,0,0,0}));
    KeyboardPointer slow;
    state.sticks[0] = 4000;
    total = 0;
    for (int i = 0; i < 125; ++i) total += static_cast<int8_t>(slow.report(state, 0, false, 0, 0.008)[2]);
    assert(total > 0 && total < 30);
    double last = 0;
    for (int value = 4000; value <= 32000; value += 2000) {
        KeyboardPointer curve;
        state.sticks[0] = value;
        total = 0;
        for (int i = 0; i < 125; ++i) total += static_cast<int8_t>(curve.report(state, 0, false, 0, 0.008)[2]);
        assert(total >= last); last = total;
    }
    state.sticks = {-32768,32767,0,0};
    for (int i = 0; i < 125; ++i) r = pointer.report(state, 1, false, 0, 0.008);
    assert(r[0] == 2 && r[1] == 1 && static_cast<int8_t>(r[2]) < 0 && static_cast<int8_t>(r[3]) > 0);
    state.sticks = {0,0,0,-32768};
    r = pointer.report(state, 2, false, 0, 0.008);
    assert(r[1] == 2 && r[2] == 0 && r[3] == 0 && r[4] == 1);
    assert(pointer.report(State{}, 0, false, 0, 0.008)[4] == 0);
    state.sticks = {0,32767,0,0};
    r = pointer.report(state, 0, true, 0, 0.008);
    assert(r[2] == 0 && r[3] == 0 && r[4] == 255); // Select + left stick scrolls
    pointer.report(State{}, 0, false, 0, 0.008);
    total = 0;
    for (int i = 0; i < 125; ++i) total += static_cast<int8_t>(pointer.report(State{}, 0, false, 1, 0.008)[4]);
    assert(total >= 14 && total <= 15); // selectable wheel key is rate limited
    puts("Keyboard mouse packing, deadzones, shared joyMouse curve, fractions, signs and scroll passed");

    KeyboardChannel channel;
    const std::string name = "controller-keyboard-test-" + std::to_string(getpid());
    assert(channel.listen(name.c_str()));
    int commands[2], replies[2];
    assert(pipe(commands) == 0 && pipe(replies) == 0);
    const pid_t child = fork(); assert(child >= 0);
    if (child == 0) {
        close(commands[1]); close(replies[0]);
        if (getuid() == 0) { assert(setgid(1000) == 0); assert(setuid(1000) == 0); }
        assert(getuid() == 1000);
        int fd = -1;
        auto connect = [&] {
            fd = socket(AF_UNIX, SOCK_SEQPACKET, 0); assert(fd >= 0);
            sockaddr_un address{}; address.sun_family = AF_UNIX;
            memcpy(address.sun_path + 1, name.c_str(), name.size());
            assert(::connect(fd, reinterpret_cast<sockaddr*>(&address),
                    offsetof(sockaddr_un, sun_path) + 1 + name.size()) == 0);
        };
        connect();
        char ack = 'a'; assert(write(replies[1], &ack, 1) == 1);
        char command;
        while (read(commands[0], &command, 1) == 1 && command != 'q') {
            KeyboardPacket packet{1}; packet[11] = 1;
            if (command == 'k') { packet[1] = 5; packet[2] = 0x4c; }
            if (command == '6') { packet[1] = 0xff; for (int i = 0; i < 6; ++i) packet[2+i] = 4+i; }
            if (command == 'b') packet[8] = 1;
            if (command == 'm') packet[8] = 8;
            if (command == 'c') { close(fd); connect(); }
            else if (command == 'x') { close(fd); fd = -1; }
            else if (command == 'f') {
                assert(recv(fd, packet.data(), packet.size(), 0) == 16);
                assert(write(replies[1], packet.data(), packet.size()) == 16);
                continue;
            } else assert(::send(fd, packet.data(), packet.size(), MSG_NOSIGNAL) == 16);
            assert(write(replies[1], &ack, 1) == 1);
        }
        if (fd >= 0) close(fd);
        _exit(0);
    }
    close(commands[0]); close(replies[1]);
    char ack; assert(read(replies[0], &ack, 1) == 1);
    auto command = [&](char value) {
        assert(write(commands[1], &value, 1) == 1);
        assert(read(replies[0], &ack, 1) == 1);
    };
    const std::string owner = std::to_string(child), start = startOf(child);
    auto pump = [&](int time, bool connected = true) {
        assert(channel.pump(state, 3, connected, time, owner, start));
    };
    auto drain = [&] { while (!channel.pending().empty()) channel.sent(); };
    // A system uid alone cannot impersonate another app PID or a recycled lease.
    assert(channel.pump(state, 0, true, 0, std::to_string(getpid()), startOf(getpid())));
    assert(!channel.active());
    command('c');
    assert(channel.pump(state, 0, true, 1, owner, "0")); assert(!channel.active());
    command('c'); pump(2); drain();
    assert(write(commands[1], "f", 1) == 1);
    KeyboardPacket frame{}; assert(read(replies[0], frame.data(), frame.size()) == 16);
    assert(frame[0] == 1 && frame[12] == 3 && frame[13] == 1);
    command('k'); pump(3);
    assert(channel.active()); assert(channel.pending().front() == (Bytes{1,5,0,0x4c,0,0,0,0,0}));
    drain(); command('6'); pump(4);
    assert(channel.pending().front() == (Bytes{1,255,0,4,5,6,7,8,9}));
    drain(); command('u'); pump(5);
    assert(channel.pending().front() == (Bytes{1,0,0,0,0,0,0,0,0}));
    drain(); command('b'); pump(6);
    assert(channel.buttons() == 1 && channel.pending().front() == (Bytes{2,1,0,0,0}));
    drain(); pump(507);
    assert(!channel.active() && channel.buttons() == 0);
    assert(channel.pending().size() == 2 && channel.pending()[1] == (Bytes{2,0,0,0,0}));
    command('c'); pump(508); drain(); command('k'); pump(509); drain();
    pump(510, false);
    assert(!channel.active() && channel.pending()[0] == (Bytes{1,0,0,0,0,0,0,0,0}));
    command('c'); pump(511, false); drain(); command('k'); pump(512, false);
    assert(channel.pending().empty()); // disconnected clients cannot inject keyboard reports
    command('c'); pump(513); drain(); command('b'); pump(514); drain();
    command('x'); pump(515);
    assert(!channel.active() && channel.buttons() == 0 && channel.pending().size() == 2);
    command('c'); pump(516); drain(); command('m'); pump(517);
    assert(!channel.active() && channel.pending().size() == 2);
    assert(write(commands[1], "q", 1) == 1);
    int status; assert(waitpid(child, &status, 0) == child);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    close(commands[1]); close(replies[0]);
    puts("Private socket peer/lease checks, state and LED frames, 6KRO packing, release-all, timeout, disconnect, EOF and invalid packets passed");
}
