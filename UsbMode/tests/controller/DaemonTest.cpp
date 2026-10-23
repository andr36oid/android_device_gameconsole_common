#define main controllerEntry
#include "../../controller/main.cpp"
#undef main

#include <cassert>

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
    puts("Daemon lease identity, split inputs, calibration, Guide and fd cleanup passed");
}
