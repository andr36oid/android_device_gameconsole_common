#include <linux/uinput.h>
#include <linux/input.h>
#include <fcntl.h>
#include <unistd.h>
#include <iostream>
#include <sys/time.h>
#include <cstdlib>
#include <cmath>
#include <thread>
#include <atomic>
#include <chrono>
#include <sys/system_properties.h>

const char* JOY_EVENT = "/dev/input/event3";

const int RX_DEADZONE = 500;
const int RY_DEADZONE = 500;
const float SPEED_SCALE = 0.00001f;
const int POLL_INTERVAL_MS = 10;

const int R3_KEY_CODE = 125;   // change if needed
const int LONGPRESS_MS = 800;

std::atomic<int> rx(0);
std::atomic<int> ry(0);
std::atomic<bool> running(true);
std::atomic<bool> mouseMode(false);
std::atomic<bool> r3Pressed(false);

void emitEvent(int fd, int type, int code, int value) {
    struct input_event ev{};
    gettimeofday(&ev.time, nullptr);
    ev.type = type;
    ev.code = code;
    ev.value = value;
    write(fd, &ev, sizeof(ev));
}

bool detectInvertRY() {
    char device[PROP_VALUE_MAX] = {0};
    __system_property_get("ro.product.device", device);

    return strcmp(device, "r36s") == 0;
}

int computeSpeed(int val, int deadzone) {
    int absVal = std::abs(val);
    if (absVal <= deadzone) return 0;
    int adjusted = absVal - deadzone;
    int sign = (val > 0) ? 1 : -1;
    return static_cast<int>(sign * adjusted * adjusted * SPEED_SCALE);
}

void readJoystick() {
    int joy_fd = open(JOY_EVENT, O_RDONLY);
    if (joy_fd < 0) { perror("Failed to open joystick"); running=false; return; }

    struct input_event ev;
    while (running) {
        if (read(joy_fd, &ev, sizeof(ev)) != sizeof(ev)) continue;

        if (ev.type == EV_ABS) {
            if (ev.code == ABS_RX) rx = ev.value;
            if (ev.code == ABS_RY) ry = ev.value;
        }
        else if (ev.type == EV_KEY && ev.code == R3_KEY_CODE) {
            r3Pressed = ev.value;
        }
    }
    close(joy_fd);
}

int main() {

    std::thread joyThread(readJoystick);

    int uinput_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (uinput_fd < 0) { perror("Failed to open uinput"); running=false; joyThread.join(); return 1; }

    ioctl(uinput_fd, UI_SET_EVBIT, EV_REL);
    ioctl(uinput_fd, UI_SET_RELBIT, REL_X);
    ioctl(uinput_fd, UI_SET_RELBIT, REL_Y);
    ioctl(uinput_fd, UI_SET_EVBIT, EV_KEY);
    ioctl(uinput_fd, UI_SET_KEYBIT, BTN_LEFT);

    struct uinput_user_dev uidev{};
    snprintf(uidev.name, UINPUT_MAX_NAME_SIZE, "joymouse_pointer");
    uidev.id.bustype = BUS_USB;
    uidev.id.vendor  = 0x1234;
    uidev.id.product = 0x5678;
    uidev.id.version = 1;
    write(uinput_fd, &uidev, sizeof(uidev));
    ioctl(uinput_fd, UI_DEV_CREATE, 0);

    bool lastR3State = false;
    bool longpressHandled = false;
    auto pressStart = std::chrono::steady_clock::now();

    while (running) {

        bool currentR3 = r3Pressed.load();

        // Detect press start
        if (currentR3 && !lastR3State) {
            pressStart = std::chrono::steady_clock::now();
            longpressHandled = false;
        }

        // Long press toggle
        if (currentR3 && !longpressHandled) {
            auto now = std::chrono::steady_clock::now();
            auto heldMs = std::chrono::duration_cast<std::chrono::milliseconds>(now - pressStart).count();
            if (heldMs >= LONGPRESS_MS) {
                mouseMode = !mouseMode;
                longpressHandled = true;
            }
        }

        lastR3State = currentR3;

        // Only move cursor if mouse mode is active
        if (mouseMode) {
            int move_y;
            int move_x = computeSpeed(rx.load(), RX_DEADZONE);
            if (detectInvertRY())
            {
                move_y = computeSpeed(ry.load(), RY_DEADZONE); //change to -computeSpeed for r46h when building
            }
            else{
                move_y = -computeSpeed(ry.load(), RY_DEADZONE);
            }

            // If R3 is held, we simulate a "finger slide" (click+drag)
            if (currentR3) {
                emitEvent(uinput_fd, EV_KEY, BTN_LEFT, 1);
            } else {
                emitEvent(uinput_fd, EV_KEY, BTN_LEFT, 0);
            }

            if (move_x || move_y) {
                emitEvent(uinput_fd, EV_REL, REL_X, move_x);
                emitEvent(uinput_fd, EV_REL, REL_Y, move_y);
                emitEvent(uinput_fd, EV_SYN, SYN_REPORT, 0);
            }
        }

        std::this_thread::sleep_for(std::chrono::milliseconds(POLL_INTERVAL_MS));
    }

    ioctl(uinput_fd, UI_DEV_DESTROY, 0);
    close(uinput_fd);
    running = false;
    joyThread.join();
    return 0;
}