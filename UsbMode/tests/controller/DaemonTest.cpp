#define main controllerEntry
#include "../../controller/main.cpp"
#undef main

#include <cassert>

struct FakeAio {
    static inline bool inputComplete = false, destroyed = false;
    static inline int inputs = 0, outputs = 0;
    static inline const uint8_t* queued = nullptr;
    static const AioOps& ops() {
        static const AioOps ops = {
            [](unsigned n, aio_context_t* ctx) { assert(n == 2); *ctx = 1; return 0L; },
            [](aio_context_t ctx) { assert(ctx == 1); destroyed = true; return 0L; },
            [](aio_context_t, long min, long max, io_event* events, timespec* timeout) {
                assert(min == 0 && max == 2 && timeout->tv_sec == 0 && timeout->tv_nsec == 0);
                if (!inputComplete) return 0L;
                inputComplete = false; events[0].data = 1; events[0].res = 20; return 1L;
            },
            [](aio_context_t, long n, iocb** requests) {
                assert(n == 1);
                const auto& r = *requests[0];
                if (r.aio_data == 1) {
                    assert(r.aio_lio_opcode == IOCB_CMD_PWRITE && r.aio_nbytes == 20);
                    ++inputs; queued = reinterpret_cast<const uint8_t*>(r.aio_buf);
                } else {
                    assert(r.aio_lio_opcode == IOCB_CMD_PREAD && r.aio_nbytes == 64);
                    ++outputs;
                }
                return 1L;
            },
        };
        return ops;
    }
};

int main() {
    const std::string pid = std::to_string(getpid());
    std::string stat = readText("/proc/" + pid + "/stat");
    stat.erase(0, stat.rfind(')') + 2);
    for (int field = 3; field < 22; ++field) stat.erase(0, stat.find(' ') + 1);
    const std::string start = stat.substr(0, stat.find(' '));
    assert(process(pid, start));
    assert(!process(pid, "0")); assert(!process("../1", start)); assert(!process("", start));
    Inputs inputs;
    Input d{}; d.fd = -1; d.calibrated = true;
    d.keys[BTN_B / kBits] |= 1UL << (BTN_B % kBits);
    d.keys[BTN_SELECT / kBits] |= 1UL << (BTN_SELECT % kBits);
    d.keys[BTN_TL / kBits] |= 1UL << (BTN_TL % kBits);
    d.axes[ABS_X] = {1800,-1800,1800,0,500,0}; d.hasAxis[ABS_X] = true;
    inputs.devices.push_back(d);
    State s = inputs.state();
    assert(s.buttons[A] && s.buttons[Guide] && s.sticks[0] == 32767);
    inputs.devices[0].axes[ABS_X].value = 100;
    assert(inputs.state().sticks[0] > 0);  // no second flat dead zone on calibrated evdev
    inputs.devices[0].calibrated = false;
    assert(inputs.state().sticks[0] == 0);
    Input gpio{}; gpio.fd = -1;
    gpio.keys[KEY_HOMEPAGE / kBits] |= 1UL << (KEY_HOMEPAGE % kBits);
    inputs.devices.push_back(gpio);
    inputs.devices[0].keys.fill(0);
    assert(inputs.state().buttons[Guide]);
    // A grab owner releases its fds on every return, including partial discovery.
    int pipefd[2]; assert(pipe(pipefd) == 0);
    {
        Inputs owned;
        Input input{}; input.fd = pipefd[0]; owned.devices.push_back(input);
    }
    assert(fcntl(pipefd[0], F_GETFD) == -1 && errno == EBADF);
    close(pipefd[1]);
    {
        XInputIo io(FakeAio::ops());
        assert(io.start(100, 101));
        State report;
        assert(io.pump(report, false)); assert(FakeAio::inputs == 0);
        assert(io.pump(report, true));
        report.buttons[A] = true;
        for (int i = 0; i < 100; ++i) assert(io.pump(report, true));
        assert(FakeAio::outputs == 1 && FakeAio::inputs == 1);
        assert(FakeAio::queued[3] == 0);  // pending IN buffer stays alive and unchanged
        FakeAio::inputComplete = true;
        assert(io.pump(report, true));
        assert(FakeAio::inputs == 2 && FakeAio::queued[3] == 0x10);
        assert(FakeAio::outputs == 1);  // a silent OUT never delays IN
    }
    assert(FakeAio::destroyed);
    const char* temporary = getenv("TMPDIR");
    assert(temporary);
    std::string path = std::string(temporary) + "/controller-aio-XXXXXX";
    std::vector<char> name(path.begin(), path.end()); name.push_back(0);
    int file = mkstemp(name.data()); assert(file >= 0); unlink(name.data());
    assert(ftruncate(file, 64) == 0);
    {
        XInputIo real;
        assert(real.start(file, file));
        State report; report.buttons[Y] = true;
        assert(real.pump(report, true));
        real.cancel();
        uint8_t bytes[20]{};
        assert(pread(file, bytes, sizeof(bytes), 0) == 20);
        assert(bytes[1] == 20 && bytes[3] == 0x80);
    }
    close(file);
    puts("AIO: silent OUT, pending buffer lifetime, IN completions, cancellation and real Linux syscalls passed");
    puts("Daemon lease identity, split inputs, calibration, Guide and fd cleanup passed");
}
