#include "Controller.h"
#include "Keyboard.h"
#include "Protocols.h"
#include "Services.h"
#include "Flipper.h"

#include <algorithm>
#include <cerrno>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <iostream>
#include <poll.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <linux/aio_abi.h>
#include <time.h>
#include <unistd.h>
#include <linux/usb/functionfs.h>
#include <string>
#ifdef __ANDROID__
#include <sys/system_properties.h>
#include <android/log.h>
#endif

namespace {
using namespace controller;
constexpr const char kPrefix[] = "sys.andr36oid.usbdrive.";
volatile sig_atomic_t stopped = 0;
void stop(int) { stopped = 1; }
void tick(int) { }
#ifdef CONTROLLER_TEST_PROPERTIES
std::map<std::string,std::string> testProperties;
#endif
std::string property(const std::string& key) {
#ifdef __ANDROID__
    char value[PROP_VALUE_MAX]{};
    __system_property_get(key.c_str(), value);
    return value;
#elif defined(CONTROLLER_TEST_PROPERTIES)
    return testProperties[key];
#else
    (void)key;
    return {};
#endif
}
void set(const char* key, const char* value) {
#ifdef __ANDROID__
    __system_property_set((std::string(kPrefix) + key).c_str(), value);
#else
    (void)key; (void)value;
#endif
}
void log(const char* message) {
#ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_INFO, "andr36oid-controller", "%s: %s", message, strerror(errno));
#else
    fprintf(stderr, "%s: %s\n", message, strerror(errno));
#endif
}
int64_t now() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000LL + ts.tv_nsec / 1000000;
}
std::string readText(const std::string& path) {
    char text[512]{};
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return {};
    ssize_t n = read(fd, text, sizeof(text) - 1);
    close(fd);
    return n > 0 ? std::string(text, n) : std::string();
}
bool process(const std::string& pid, const std::string& start) {
    if (pid.empty() || pid.find_first_not_of("0123456789") != std::string::npos || start.empty()) return false;
    std::string stat = readText("/proc/" + pid + "/stat");
    size_t pos = stat.rfind(')');
    if (pos == std::string::npos) return false;
    stat.erase(0, pos + 2);
    for (int field = 3; field < 22; ++field) {
        pos = stat.find(' ');
        if (pos == std::string::npos) return false;
        stat.erase(0, pos + 1);
    }
    return stat.substr(0, stat.find(' ')) == start;
}
constexpr size_t kBits = sizeof(unsigned long) * 8;
template <size_t N> bool bit(const std::array<unsigned long, N>& bits, unsigned code) {
    return code / kBits < N && (bits[code / kBits] & (1UL << (code % kBits)));
}
struct Input {
    int fd;
    std::array<unsigned long, (KEY_CNT + kBits - 1) / kBits> supported{};
    std::array<unsigned long, (KEY_CNT + kBits - 1) / kBits> keys{};
    std::array<input_absinfo, ABS_CNT> axes{};
    std::array<bool, ABS_CNT> hasAxis{};
    bool dropped = false;
    bool calibrated = false;
    void snapshot() {
        ioctl(fd, EVIOCGKEY(sizeof(keys)), keys.data());
        for (unsigned code = 0; code < ABS_CNT; ++code)
            if (hasAxis[code]) ioctl(fd, EVIOCGABS(code), &axes[code]);
    }
};
class Inputs {
public:
    std::vector<Input> devices;
    ~Inputs() {
        for (auto& d : devices) { ioctl(d.fd, EVIOCGRAB, 0); close(d.fd); }
    }
    bool openAll() {
        DIR* dir = opendir("/dev/input");
        if (!dir) return false;
        bool pad = false;
        while (dirent* e = readdir(dir)) {
            if (strncmp(e->d_name, "event", 5)) continue;
            const std::string path = std::string("/dev/input/") + e->d_name;
            int fd = open(path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
            if (fd < 0) continue;
            input_id id{};
            char name[256]{};
            Input d{}; d.fd = fd;
            ioctl(fd, EVIOCGID, &id);
            ioctl(fd, EVIOCGNAME(sizeof(name)), name);
            ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(d.supported)), d.supported.data());
            const bool builtIn = id.bustype != BUS_USB && id.bustype != BUS_BLUETOOTH &&
                    (id.vendor == 0x484b || strstr(name, "joypad") || strstr(name, "Gamepad"));
            const bool keys = id.bustype != BUS_USB && id.bustype != BUS_BLUETOOTH &&
                    (strstr(name, "gpio") || strstr(name, "rk8")) &&
                    (bit(d.supported, KEY_POWER) || bit(d.supported, KEY_HOMEPAGE) ||
                     bit(d.supported, KEY_VOLUMEUP));
            if (!builtIn && !keys) { close(fd); continue; }
            // Exclude uinput devices even when they copy the real pad's identity.
            char resolved[4096]{};
            const std::string sys = std::string("/sys/class/input/") + e->d_name;
            if (!realpath(sys.c_str(), resolved) || strstr(resolved, "/virtual/")) { close(fd); continue; }
            if (ioctl(fd, EVIOCGRAB, 1) < 0) { close(fd); closedir(dir); return false; }
            pad |= builtIn && (bit(d.supported, BTN_A) || bit(d.supported, BTN_START));
            d.calibrated = id.vendor == 0x484b;
            for (unsigned code = 0; code < ABS_CNT; ++code)
                d.hasAxis[code] = ioctl(fd, EVIOCGABS(code), &d.axes[code]) >= 0;
            d.snapshot();
            devices.push_back(d);
        }
        closedir(dir);
        return pad;
    }
    bool grab(bool enabled) {
        for (auto& d : devices) if (ioctl(d.fd,EVIOCGRAB,enabled ? 1 : 0) < 0) return false;
        return true;
    }
    bool power = false;
    bool readEvents() {
        power = false;
        for (auto& d : devices) {
            input_event ev{};
            ssize_t n;
            while ((n = read(d.fd, &ev, sizeof(ev))) == sizeof(ev)) {
                if (ev.type == EV_SYN && ev.code == SYN_DROPPED) d.dropped = true;
                if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
                    d.snapshot(); d.dropped = false;
                }
                if (!d.dropped && ev.type == EV_KEY && ev.code == KEY_POWER && ev.value == 1) power = true;
                if (!d.dropped && property(std::string(kPrefix) + "companion") != "1" && property(std::string(kPrefix) + "screen") != "dark" && ev.type == EV_KEY && ev.value &&
                        (ev.code == KEY_VOLUMEUP || ev.code == KEY_VOLUMEDOWN)) {
                    int brightness = 15;
                    const std::string saved = property(std::string(kPrefix) + "brightness");
                    if (!saved.empty()) brightness = atoi(saved.c_str());
                    brightness = std::clamp(brightness + (ev.code == KEY_VOLUMEUP ? 5 : -5), 2, 100);
                    set("brightness", std::to_string(brightness).c_str());
                }
            }
            if (n == 0 || (n < 0 && errno != EAGAIN && errno != EINTR)) return false;
        }
        return true;
    }
    State state() const {
        State s;
        for (const auto& d : devices) {
            for (unsigned code = 0; code < KEY_CNT; ++code) {
                int b = button(code);
                if (b >= 0) s.buttons[b] = s.buttons[b] || bit(d.keys, code);
            }
            const unsigned codes[] = {ABS_X,ABS_Y,ABS_RX,ABS_RY};
            for (unsigned i = 0; i < 4; ++i) if (d.hasAxis[codes[i]]) {
                const auto& a = d.axes[codes[i]];
                // The handheld driver has already applied the stored radial dead zone.
                s.sticks[i] = scale(a.value, a.minimum, a.maximum, d.calibrated ? 0 : a.flat);
            }
            if (d.hasAxis[ABS_HAT0X]) {
                s.buttons[Left] = s.buttons[Left] || d.axes[ABS_HAT0X].value < 0;
                s.buttons[Right] = s.buttons[Right] || d.axes[ABS_HAT0X].value > 0;
            }
            if (d.hasAxis[ABS_HAT0Y]) {
                s.buttons[Up] = s.buttons[Up] || d.axes[ABS_HAT0Y].value < 0;
                s.buttons[Down] = s.buttons[Down] || d.axes[ABS_HAT0Y].value > 0;
            }
        }
        // Always available on consoles whose Fn button is missing or remapped in Android.
        s.buttons[Guide] = s.buttons[Guide] || (s.buttons[Back] && s.buttons[LB]);
        return s;
    }
};
bool send(int fd, const void* data, size_t size) {
    ssize_t n = write(fd, data, size);
    return n == static_cast<ssize_t>(size) || (n < 0 && (errno == EAGAIN || errno == EINTR ||
            errno == ESHUTDOWN || errno == ENODEV));
}
// FunctionFS synchronous read/write can wait even with O_NONBLOCK. Keep one
// asynchronous request per endpoint so a silent host cannot stall the input loop.
struct AioOps {
    long (*setup)(unsigned, aio_context_t*);
    long (*destroy)(aio_context_t);
    long (*events)(aio_context_t, long, long, io_event*, timespec*);
    long (*submit)(aio_context_t, long, iocb**);
};
const AioOps kKernelAio = {
    [](unsigned n, aio_context_t* c) { return syscall(SYS_io_setup, n, c); },
    [](aio_context_t c) { return syscall(SYS_io_destroy, c); },
    [](aio_context_t c, long min, long max, io_event* e, timespec* t) {
        return syscall(SYS_io_getevents, c, min, max, e, t);
    },
    [](aio_context_t c, long n, iocb** r) { return syscall(SYS_io_submit, c, n, r); },
};
class XInputIo {
public:
    explicit XInputIo(const AioOps& ops = kKernelAio) : ops_(ops) { }
    XInputIo(const XInputIo&) = delete;
    XInputIo& operator=(const XInputIo&) = delete;
    ~XInputIo() { cancel(); }
    bool start(int in, int out, unsigned function = XInput) {
        function_ = function;
        inFd_ = in; outFd_ = out;
        return ops_.setup(2, &context_) == 0;
    }
    void cancel() {
        if (context_) ops_.destroy(context_);
        context_ = 0;
    }
    bool pump(const State& state, bool enabled) {
        io_event events[2]{};
        timespec timeout{};
        long count = ops_.events(context_, 0, 2, events, &timeout);
        if (count < 0) return errno == EINTR;
        for (long i = 0; i < count; ++i) {
            const bool input = events[i].data == 1;
            if (input) inBusy_ = false; else {
                outBusy_ = false;
                if (events[i].res > 0) control.output(function_,output_.data(),events[i].res);
            }
            const int64_t result = events[i].res;
            if (result < 0 && result != -ESHUTDOWN && result != -ENODEV && result != -ECANCELED && result != -EINTR)
                return false;
            if (input && result >= 0 && result != static_cast<int64_t>(input_.size())) return false;
        }
        if (!enabled) { control.reset(); return true; }
        if (!outBusy_ && !submit(false)) return false;
        if (!inBusy_ && control.ready(function_)) {
            input_ = padReport(function_,state);
            if (!submit(true)) return false;
        }
        return true;
    }
    PadControl control;
private:
    bool submit(bool input) {
        iocb& request = input ? inRequest_ : outRequest_;
        request = {};
        request.aio_data = input ? 1 : 2;
        request.aio_fildes = input ? inFd_ : outFd_;
        request.aio_lio_opcode = input ? IOCB_CMD_PWRITE : IOCB_CMD_PREAD;
        request.aio_buf = reinterpret_cast<uintptr_t>(input ? input_.data() : output_.data());
        request.aio_nbytes = input ? input_.size() : output_.size();
        iocb* requests[] = {&request};
        const long result = ops_.submit(context_, 1, requests);
        if (result == 1) { if (input) inBusy_ = true; else outBusy_ = true; return true; }
        return result < 0 && (errno == EAGAIN || errno == EINTR || errno == ESHUTDOWN || errno == ENODEV);
    }
    const AioOps& ops_;
    aio_context_t context_ = 0;
    int inFd_ = -1, outFd_ = -1;
    bool inBusy_ = false, outBusy_ = false;
    iocb inRequest_{}, outRequest_{};
    unsigned function_ = XInput;
    Bytes input_ = Bytes(20);
    std::array<uint8_t, 64> output_{};
};

int hidNode(const char* function = "hid.andr36oid") {
    const std::string dev = readText(std::string("/config/usb_gadget/g1/functions/") + function + "/dev");
    unsigned major, minor;
    if (sscanf(dev.c_str(), "%u:%u", &major, &minor) != 2 || minor > 255) return -1;
    const std::string path = "/dev/hidg" + std::to_string(minor);
    return open(path.c_str(), O_RDWR | O_NONBLOCK | O_CLOEXEC);
}
void setup(int ep0, const usb_ctrlrequest& request, const State& state,
           unsigned function = XInput, PadControl* handler = nullptr) {
    // composite.c serves the OS descriptor before forwarding other requests.
    const auto* bytes = reinterpret_cast<const uint8_t*>(&request);
    const auto le = [&](unsigned pos) { return bytes[pos] | (bytes[pos + 1] << 8); };
    PadControl local;
    if (!handler) handler = &local;
    const auto reply = handler->setup(function,request.bRequestType,request.bRequest,le(2),le(4),le(6),state);
    uint8_t ignored[64]{};
    if (reply.action == ControlAction::Reply) write(ep0, reply.data.data(), reply.data.size());
    else if (reply.action == ControlAction::Receive) {
        ssize_t n = read(ep0,ignored,reply.length);
        if (n >= 0) handler->received(function,le(2),Bytes(ignored,ignored + n));
    }
    else if (request.bRequestType & USB_DIR_IN) read(ep0, ignored, 0);
    else write(ep0, ignored, 0);
}
bool keyboardConnected() {
    return readText("/sys/class/udc/ff300000.usb/state").find("configured") == 0;
}
bool writeText(const std::string& path, const std::string& value) {
    int fd = open(path.c_str(), O_WRONLY | O_TRUNC | O_CLOEXEC);
    if (fd < 0) return false;
    const bool ok = write(fd, value.data(), value.size()) == static_cast<ssize_t>(value.size());
    close(fd); return ok;
}
class Backlight {
public:
    Backlight(std::string sys = "/sys/class/backlight", std::string state = "/data/misc/andr36oid-usb-drive")
            : sys_(std::move(sys)), state_(std::move(state)) { }
    ~Backlight() { restore(); }
    bool dark(bool dark) {
        if (!dark) return restore();
        DIR* dir = opendir(sys_.c_str());
        if (!dir) return false;
        bool found = false, ok = true;
        while (dirent* entry = readdir(dir)) {
            if (entry->d_name[0] == '.') continue;
            const std::string path = sys_ + "/" + entry->d_name + "/brightness";
            const std::string journal = state_ + "/backlight-" + entry->d_name;
            if (readText(journal).empty()) {
                const std::string level = readText(path);
                if (level.empty() || !writeTextNew(journal, level)) { ok = false; continue; }
                saved_.push_back({path,journal});
            }
            found = true; ok = writeText(path, "0") && ok;
        }
        closedir(dir); return found && ok;
    }
    bool restore() {
        bool ok = true;
        for (auto it = saved_.begin(); it != saved_.end();) {
            if (writeText(it->first, readText(it->second))) { unlink(it->second.c_str()); it = saved_.erase(it); }
            else { ok = false; ++it; }
        }
        return ok;
    }
private:
    bool writeTextNew(const std::string& path, const std::string& value) {
        int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
        if (fd < 0) return false;
        bool ok = write(fd, value.data(), value.size()) == static_cast<ssize_t>(value.size());
        close(fd); return ok;
    }
    std::string sys_, state_;
    std::vector<std::pair<std::string,std::string>> saved_;
};

}

namespace controller {
KeyboardChannel::~KeyboardChannel() {
    if (client_ >= 0) close(client_);
    if (server_ >= 0) close(server_);
}
bool KeyboardChannel::listen(const char* name) {
    server_ = socket(AF_UNIX, SOCK_SEQPACKET | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (server_ < 0 || strlen(name) >= sizeof(sockaddr_un::sun_path) - 1) return false;
    sockaddr_un address{};
    address.sun_family = AF_UNIX;
    memcpy(address.sun_path + 1, name, strlen(name));
    return bind(server_, reinterpret_cast<sockaddr*>(&address),
                offsetof(sockaddr_un, sun_path) + 1 + strlen(name)) == 0 && ::listen(server_, 1) == 0;
}
void KeyboardChannel::release() {
    if (client_ >= 0) close(client_);
    client_ = -1; menuWait_ = menuWait_ || menu_; menu_ = false; received_ = false; buttons_ = 0; scrolling_ = false; wheel_ = 0;
    keys_ = {1}; lastFrame_ = -1;
    pending_.clear();
    pending_.push_back(Bytes(keys_.begin(), keys_.end()));
    pending_.push_back({2,0,0,0,0});
    if (consumer_) { pending_.push_back({3,1,0}); consumer_ = 0; }
}
bool KeyboardChannel::pump(const State& state, uint8_t leds, bool connected, int64_t time,
                           const std::string& owner, const std::string& start, bool abort) {
    bool neutral = true;
    for (bool pressed : state.buttons) neutral &= !pressed;
    for (int axis : state.sticks) neutral &= std::abs(axis) < 3277;
    if (!menu_ && neutral) menuWait_ = false;
    if (connected_ != connected) { release(); connected_ = connected; }
    if (client_ < 0) {
        int candidate = accept4(server_, nullptr, nullptr, SOCK_NONBLOCK | SOCK_CLOEXEC);
        if (candidate >= 0) {
            ucred peer{}; socklen_t size = sizeof(peer);
            if (getsockopt(candidate, SOL_SOCKET, SO_PEERCRED, &peer, &size) == 0 &&
                    peer.uid == 1000 && std::to_string(peer.pid) == owner && process(owner, start)) {
                client_ = candidate; lastReply_ = time; lastFrame_ = -1;
            } else close(candidate);
        }
    }
    if (client_ < 0) return true;
    if (time - lastReply_ > 500) { release(); return true; }
    // Bound work if a broken client floods packets, as well as queue memory.
    for (int i = 0; i < 16; ++i) {
        KeyboardPacket packet{};
        ssize_t n = recv(client_, packet.data(), packet.size(), MSG_DONTWAIT | MSG_TRUNC);
        if (n < 0 && (errno == EAGAIN || errno == EINTR)) break;
        if (n != static_cast<ssize_t>(packet.size()) || packet[0] != 1 || packet[8] > 7 ||
                packet[9] > 1 || packet[11] > 1 || packet[12] > 1 || packet[13] > 63 || packet[14] > 1 || packet[15] || (packet[10] != 0 && packet[10] != 1 && packet[10] != 255)) {
            release(); return true;
        }
        if (packet[12]) {
            pending_.clear(); keys_ = {1}; buttons_ = 0; scrolling_ = false; wheel_ = 0;
            pending_.push_back(Bytes(keys_.begin(),keys_.end())); pending_.push_back({2,0,0,0,0});
            if (consumer_) { pending_.push_back({3,1,0}); consumer_ = 0; }
            packet.fill(0); packet[0] = 1;
        }
        std::array<uint8_t, 9> keys{1,packet[1],0};
        for (unsigned j = 0; j < 6; ++j) {
            if (packet[2 + j] > 0x73) { release(); return true; }
            keys[3 + j] = packet[2 + j];
        }
        menu_ = connected && packet[14] == 1;
        if (menu_) menuWait_ = true;
        else if (neutral) menuWait_ = false;
        if (!connected || menu_ || menuWait_ || packet[11] == 0) { keys = {1}; packet[8] = packet[9] = packet[10] = packet[13] = 0; }
        if (keys != keys_) {
            keys_ = keys;
            pending_.push_back(Bytes(keys.begin(), keys.end()));
        }
        if (buttons_ != packet[8]) {
            buttons_ = packet[8];
            pending_.push_back({2,buttons_,0,0,0});
        }
        if (consumer_ != packet[13]) { consumer_ = packet[13]; pending_.push_back({3,1,consumer_}); }
        scrolling_ = packet[9]; wheel_ = static_cast<int8_t>(packet[10]);
        received_ = packet[11] == 1; lastReply_ = time;
        if (pending_.size() > 64) { release(); return true; }
    }
    if (lastFrame_ < 0 || time - lastFrame_ >= 8) {
        KeyboardPacket packet{1};
        for (unsigned i = 0; i < Count; ++i) if (state.buttons[i]) packet[1 + i / 8] |= 1 << (i % 8);
        for (unsigned i = 0; i < 4; ++i) {
            packet[4 + i * 2] = state.sticks[i] & 255;
            packet[5 + i * 2] = static_cast<uint16_t>(state.sticks[i]) >> 8;
        }
        packet[12] = leds; packet[13] = connected; packet[14] = abort;
        ssize_t n = ::send(client_, packet.data(), packet.size(), MSG_DONTWAIT | MSG_NOSIGNAL);
        if (n == static_cast<ssize_t>(packet.size())) lastFrame_ = time;
        else if (n >= 0 || (errno != EAGAIN && errno != EINTR)) release();
    }
    return true;
}
}  // namespace controller

namespace {
// Firmware can poll only the boot keyboard. A stalled mouse must not block it.
bool sendKeyboard(KeyboardChannel& channel, int keyboard, int mouse, bool pointing, int media = -1) {
    for (uint8_t tag : {1,2,3}) {
        const Bytes* tagged = channel.next(tag);
        if (!tagged) continue;
        const int fd = tag == 1 ? keyboard : tag == 2 ? mouse : media;
        if (fd < 0) {
            if ((tag == 2 && !pointing) || (tag == 3 && media < 0)) channel.sent(tag);
            continue;
        }
        const Bytes report = wireReport(*tagged);
        ssize_t n = write(fd, report.data(), report.size());
        if (n == static_cast<ssize_t>(report.size())) channel.sent(tag);
        else if (n < 0 && (errno == ESHUTDOWN || errno == ENODEV)) { channel.release(); return true; }
        else if (n >= 0 || (errno != EAGAIN && errno != EINTR)) return false;
    }
    return true;
}

#include "FlipperRuntime.inc"

int run(unsigned functions) {
    Inputs inputs;
    if (!inputs.openAll()) { log("Cannot grab built-in controls"); return 1; }
    const unsigned padFunction = functions & kControllers;
    const bool xbox = functions & (XInput | Ps3 | GameCube | Xbox | Ps4);
    const bool typing = functions & Keyboard;
    const bool pointing = functions & Mouse;
    const bool pad = functions & (Gamepad | Switch);
    int ep0 = -1, in = -1, out = -1;
    bool enabled = !xbox;
    XInputIo transfers;
    transfers.control.identity(readText("/config/usb_gadget/g1/strings/0x409/serialnumber"));
    if (xbox) {
        ep0 = open("/dev/usb-ffs/xinput/ep0", O_RDWR | O_CLOEXEC);
        const auto desc = functionDescriptors(padFunction); const auto str = strings();
        if (ep0 < 0 || write(ep0, desc.data(), desc.size()) != static_cast<ssize_t>(desc.size()) ||
                write(ep0, str.data(), str.size()) != static_cast<ssize_t>(str.size())) {
            log("Cannot register FunctionFS descriptors"); if (ep0 >= 0) close(ep0); return 1;
        }
        fcntl(ep0, F_SETFL, O_NONBLOCK);
        in = open("/dev/usb-ffs/xinput/ep1", O_RDWR | O_NONBLOCK | O_CLOEXEC);
        out = open("/dev/usb-ffs/xinput/ep2", O_RDWR | O_NONBLOCK | O_CLOEXEC);
        if (in < 0 || out < 0) { close(ep0); if (in >= 0) close(in); if (out >= 0) close(out); return 1; }
        if (!transfers.start(in, out, padFunction)) { close(in); close(out); close(ep0); return 1; }
    }
    set("brightness", "15");
    set("ready", "1");
    const std::string helper = property(std::string(kPrefix) + "helper");
    const std::string helperStart = property(std::string(kPrefix) + "helper_start");
    const std::string owner = property(std::string(kPrefix) + "owner");
    const std::string ownerStart = property(std::string(kPrefix) + "owner_start");
    const bool macro = typing && property(std::string(kPrefix) + "macro") == "1";
    const bool disc = (functions & Storage) && property(std::string(kPrefix) + "target") == "file" &&
            property(std::string(kPrefix) + "ro") == "1";
    SessionControls controls(functions,macro,disc);
    Jiggler jiggler;
    Backlight backlight;
    int result = 0;
    int64_t lastLease = 0, missingSince = now();
    KeyboardChannel channel;
    if (!channel.listen()) { log("Cannot open keyboard channel"); return 1; }
    KeyboardPointer pointerModel;
    uint8_t leds = 0;
    int64_t lastPointer = now(), lastConnection = 0;
    bool connected = false;
    int keyFd = -1, mouseFd = -1;
    std::string lastScreen, lastRouting;
    int64_t disconnected = -1, lastBacklight = -100;
    bool wasDark = false;
    Services services(functions,[](const char* key, const std::string& value) { set(key,value.c_str()); },
            [&](int fd) {
                ucred peer{}; socklen_t size = sizeof(peer);
                return getsockopt(fd,SOL_SOCKET,SO_PEERCRED,&peer,&size) == 0 && peer.uid == 1000 &&
                        std::to_string(peer.pid) == owner && process(owner,ownerStart);
            },property(std::string(kPrefix) + "type_root"));
    MidiPads midi;
    int midiFd = -1, mediaFd = -1;
    std::array<Bytes,2> lastMedia;
    std::deque<Bytes> mediaQueue;
    int64_t lastPads = -100;
    bool midiConnected = false, mediaConnected = false, companion = false;
    while (!stopped) {
        const int64_t time = now();
        if (time - lastLease >= 100) {
            lastLease = time;
            if (property(std::string(kPrefix) + "request") != "start" || !process(owner, ownerStart)) break;
            if (!process(helper, helperStart)) {
                // Recover the shared journal even if the shell helper was killed.
                set("request", "stop");
#ifdef __ANDROID__
                __system_property_set("ctl.start", "andr36oid-usb-drive");
#endif
                break;
            }
        }
        const bool delegate = (functions & (Network | Rndis)) && !(functions & ~(Network | Rndis | Adb)) &&
                property(std::string(kPrefix) + "companion") == "1";
        if (delegate != companion) {
            if (!inputs.grab(!delegate)) { result = 1; break; }
            companion = delegate;
        }
        if (!inputs.readEvents()) { log("Input device disappeared"); result = 1; break; }
        State raw = inputs.state();
        raw.timestamp = static_cast<uint16_t>(time * 3000 / 16); raw.sequence = (time / 4) & 63;
        if (padFunction == Ps4) {
            for (const char* battery : {"battery","rk817-battery"}) {
                const std::string root = std::string("/sys/class/power_supply/") + battery;
                auto capacity = readText(root + "/capacity");
                capacity.erase(capacity.find_last_not_of("\r\n ") + 1);
                if (!capacity.empty() && capacity.find_first_not_of("0123456789") == std::string::npos) {
                    raw.battery = std::min(100,atoi(capacity.c_str()));
                    auto status = readText(root + "/status"); status.erase(status.find_last_not_of("\r\n ") + 1); raw.charging = status == "Charging" || status == "Full"; break;
                }
            }
        }
        State controlState = raw;
        if (companion) { controlState = {}; controlState.buttons[Back] = raw.buttons[Back]; controlState.buttons[Start] = raw.buttons[Start]; }
        controls.update(controlState, time, !companion && property(std::string(kPrefix) + "screen_request") == "dark", !companion && inputs.power,
                property(std::string(kPrefix) + "hint_ready") == "1");
        if (controls.exit()) break;
        const std::string screen = controls.dark() ? "dark" : controls.hint() ? "hint" : "on";
        const std::string routing = typing && controls.keyboardScreen() ? "keyboard" : "gamepad";
        if (controls.dark() != wasDark || time - lastBacklight >= 100) {
            lastBacklight = time; wasDark = controls.dark();
            if (!backlight.dark(wasDark)) { log("Cannot set backlight"); result = 1; break; }
        }
        if (screen != lastScreen) { set("screen", screen.c_str()); lastScreen = screen; }
        if (routing != lastRouting) { channel.release(); set("routing", routing.c_str()); lastRouting = routing; }
        const State routed = controls.gamepadState(raw);
        const State s = channel.menu() ? State{} : routed;
        if (time - lastConnection >= 100) {
            lastConnection = time; connected = keyboardConnected();
            if (!connected) { leds = 0; if (disconnected < 0) disconnected = time; }
            else disconnected = -1;
        }
        // Allow firmware reboot and installer hand-off; never leave a dark orphan session.
        if (disconnected >= 0 && time - disconnected >= 300000) break;
        if (xbox) {
            pollfd p{ep0,POLLIN,0};
            if (poll(&p, 1, 0) > 0 && (p.revents & POLLIN)) {
                usb_functionfs_event events[8]{};
                ssize_t bytes = read(ep0, events, sizeof(events));
                if (bytes < 0 && errno != EAGAIN && errno != EINTR) { result = 1; break; }
                for (ssize_t i = 0; i < bytes / static_cast<ssize_t>(sizeof(events[0])); ++i) {
                    if (events[i].type == FUNCTIONFS_ENABLE) enabled = true;
                    if (events[i].type == FUNCTIONFS_DISABLE || events[i].type == FUNCTIONFS_UNBIND) enabled = false;
                    if (events[i].type == FUNCTIONFS_SETUP) setup(ep0,events[i].u.setup,s,padFunction,&transfers.control);
                }
            }
            if (!transfers.pump(s, enabled)) { log("FunctionFS transfer failed"); result = 1; break; }
        }
        if (pad) {
            if (in < 0) in = hidNode("hid.andr36oid");
            if (in >= 0) {
                const auto report = padReport(padFunction,s);
                if (!send(in, report.data(), report.size())) { result = 1; break; }
            }
        }
        if (!services.pump(time)) { log("USB service failed"); result = 1; break; }
        if (functions & Midi) {
            if (midiFd < 0) midiFd = services.midiNode();
            if (connected != midiConnected) { midi.release(); midiConnected = connected; }
            midi.update(connected ? s : State{});
            for (unsigned i = 0; i < 16 && midiFd >= 0 && !midi.pending().empty(); ++i) {
                const auto& message = midi.pending().front();
                ssize_t n = write(midiFd,message.data(),message.size());
                if (n == static_cast<ssize_t>(message.size())) midi.sent();
                else if (n < 0 && (errno == EAGAIN || errno == EINTR)) break;
                else { close(midiFd); midiFd = -1; midi.release(); }
            }
        }
        if (functions & Media) {
            if (mediaFd < 0) mediaFd = hidNode("hid.media");
            if (mediaConnected != connected) { mediaQueue.clear(); lastMedia = {}; mediaConnected = connected; }
            const auto reports = mediaReports(!macro && connected ? s : State{});
            for (unsigned i = 0; i < 2; ++i) if (reports[i] != lastMedia[i]) {
                mediaQueue.push_back(reports[i]); lastMedia[i] = reports[i];
            }
            if (mediaQueue.size() > 64) { mediaQueue.clear(); for (auto r : mediaReports(State{})) mediaQueue.push_back(r); }
            if (mediaFd >= 0 && !mediaQueue.empty()) {
                const auto& r = mediaQueue.front(); ssize_t n = write(mediaFd,r.data(),r.size());
                if (n == static_cast<ssize_t>(r.size())) mediaQueue.pop_front();
                else if (n >= 0 || (errno != EAGAIN && errno != EINTR && errno != ESHUTDOWN && errno != ENODEV)) { result = 1; break; }
            }
        }
        if ((functions & (Midi | Media | Soundboard)) && time - lastPads >= 50) {
            lastPads = time;
            unsigned bits = 0; for (unsigned i = 0; i < Count; ++i) if (s.buttons[i]) bits |= 1 << i;
            set("pads",std::to_string(bits).c_str()); set("octave",std::to_string(midi.octave()).c_str());
        }
        if (typing && keyFd < 0) keyFd = hidNode("hid.keyboard");
        if (pointing && mouseFd < 0) mouseFd = hidNode("hid.mouse");
        {
            const State keys = typing ? controls.keyboardState(raw) : controls.suppress() || controls.hint() ? State{} : raw;
            channel.pump(keys, leds, connected && (controls.keyboardScreen() || !(functions & Keyboard)) && (!controls.dark() || macro || (functions & Soundboard))
                    && !controls.hint() && !controls.suppress(), time, owner, ownerStart,
                    raw.buttons[Back] && raw.buttons[Start]);
            if (!sendKeyboard(channel, keyFd, mouseFd, pointing, macro ? mediaFd : -1)) { result = 1; break; }
            uint8_t output[64]{};
            if (keyFd >= 0 && read(keyFd, output, sizeof(output)) >= 1) leds = output[0] & 31;
        }
        if (pointing && mouseFd >= 0 && (!typing || !channel.next(2))) {
            const KeyboardPointer before = pointerModel;
            const State motion = typing ? channel.active() && connected && !channel.menu() ? controls.keyboardState(raw) : State{}
                    : controls.suppress() || controls.hint() || channel.menu() ? State{} : raw;
            const uint8_t buttons = typing ? channel.buttons() :
                    mouseButtons(motion);
            const auto tagged = pointerModel.report(motion, buttons, typing && channel.scrolling(),
                    typing ? channel.wheel() : 0, (time - lastPointer) / 1000.0);
            Bytes report = wireReport(Bytes(tagged.begin(), tagged.end()));
            const auto interval = property(std::string(kPrefix) + "jiggle");
            const unsigned seconds = interval.empty() ? 0 : static_cast<unsigned>(atoi(interval.c_str()));
            const int dx = jiggler.delta(time,seconds,connected);
            const bool jiggle = dx && report[0] == 0 && report[1] == 0 && report[2] == 0 && report[3] == 0;
            if (jiggle) report[1] = static_cast<uint8_t>(dx);
            ssize_t n = write(mouseFd, report.data(), report.size());
            if (n == static_cast<ssize_t>(report.size())) {
                lastPointer = time; if (jiggle) jiggler.sent(time);
            }
            else {
                pointerModel = before;
                if (n < 0 && (errno == ESHUTDOWN || errno == ENODEV)) channel.release();
                else if (n >= 0 || (errno != EAGAIN && errno != EINTR)) { result = 1; break; }
            }
        }
        if (result) break;
        if (((pad && in < 0) || (typing && keyFd < 0) || (pointing && mouseFd < 0) ||
                ((functions & Midi) && midiFd < 0) || ((functions & Media) && mediaFd < 0) || !services.ready()) &&
                property(std::string(kPrefix) + "state") == "sharing" && time - missingSince > 5000) {
            result = 1; break;
        }
        timespec pause{0,xbox ? 1000000 : 4000000}; nanosleep(&pause, nullptr);
    }
    midi.release();
    const int64_t midiDeadline = now() + 100;
    while (midiFd >= 0 && !midi.pending().empty() && now() < midiDeadline) {
        const auto& message = midi.pending().front();
        if (write(midiFd,message.data(),message.size()) == static_cast<ssize_t>(message.size())) midi.sent();
        else { timespec pause{0,1000000}; nanosleep(&pause,nullptr); }
    }
    if (midiFd >= 0) close(midiFd);
    if (mediaFd >= 0) {
        for (const auto& r : mediaReports(State{})) send(mediaFd,r.data(),r.size());
        close(mediaFd); mediaFd = -1;
    }
    transfers.cancel();
    if (in >= 0) {
        if (!xbox && pad) { const auto r = padReport(padFunction,State{}); send(in, r.data(), r.size()); }
        close(in);
    }
    channel.release();
    const int64_t deadline = now() + 100;
    while (!channel.pending().empty() && now() < deadline) {
        if (!sendKeyboard(channel, keyFd, mouseFd, pointing, macro ? mediaFd : -1)) break;
        if (keyFd < 0 && channel.next(1)) channel.sent(1);
        if (mouseFd < 0 && channel.next(2)) channel.sent(2);
        if (!channel.pending().empty()) { timespec pause{0,1000000}; nanosleep(&pause, nullptr); }
    }
    if (keyFd >= 0) close(keyFd);
    if (mouseFd >= 0) { const uint8_t neutral[4]{}; send(mouseFd, neutral, sizeof(neutral)); close(mouseFd); }
    if (out >= 0) close(out);
    if (ep0 >= 0) close(ep0);
    set("routing", "");
    return result;
}
}

int main(int argc, char** argv) {
    using namespace controller;
    if (argc == 2 && std::string(argv[1]) == "--reasons") {
        std::string line;
        while (std::getline(std::cin, line)) {
            if (line.empty() || line.size() > 6 || line.find_first_not_of("0123456789") != std::string::npos) return 2;
            const unsigned mask = parseFunctions(line);
            const auto p = policy(mask);
            if (printf("%u %s %u %u %u\n", mask, p.reason, p.in, p.out, p.product) < 0) return 1;
        }
        return std::cin.eof() && fflush(stdout) == 0 ? 0 : 1;
    }
    if (argc == 3 && (std::string(argv[1]) == "--identity" || std::string(argv[1]) == "--policy")) {
        const auto p = policy(parseFunctions(argv[2]));
        if (std::string(p.reason) != "ok") return 2;
        printf("0x%04x 0x%04x\n", p.vendor, p.product);
        return 0;
    }
    if (argc == 3 && std::string(argv[1]) == "--descriptor") {
        const std::string type = argv[2];
        if (type != "gamepad" && type != "keyboard" && type != "mouse" && type != "boot-mouse"
                && type != "switch" && type != "ps4" && type != "ps3" && type != "gamecube" && type != "media") return 2;
        const auto desc = type == "ps4" ? ps4Descriptor() : type == "switch" ? switchDescriptor() : type == "ps3" ? ps3Descriptor()
                : type == "gamecube" ? cubeDescriptor() : type == "media" ? mediaDescriptor() : type == "mouse" || type == "boot-mouse" ? mouseDescriptor(type == "mouse")
                : hidDescriptor(type == "keyboard");
        return fwrite(desc.data(), 1, desc.size(), stdout) == desc.size() ? 0 : 1;
    }
    if (argc != 2 || std::string(policy(parseFunctions(argv[1])).reason) != "ok") return 2;
    struct sigaction action{};
    sigemptyset(&action.sa_mask);
    action.sa_handler = stop;
    sigaction(SIGTERM, &action, nullptr); sigaction(SIGINT, &action, nullptr); sigaction(SIGHUP, &action, nullptr);
    action.sa_handler = tick;
    sigaction(SIGALRM, &action, nullptr);
    signal(SIGPIPE, SIG_IGN);
    // Bound synchronous ep0 control waits even if a host stops completing them.
    itimerval timer{{0,100000},{0,100000}};
    if (setitimer(ITIMER_REAL, &timer, nullptr) < 0) return 1;
    set("ready", "0");
    const unsigned functions = parseFunctions(argv[1]);
    const int result = functions == Flipper ? runFlipper() : run(functions);
    if (result) set("error", "controller_daemon");
    if (functions != Flipper) set("request", "stop");
    set("ready", "0");
    return result;
}
