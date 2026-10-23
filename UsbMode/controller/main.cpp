#include "Controller.h"
#include "Keyboard.h"

#include <algorithm>
#include <cerrno>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
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
std::string property(const std::string& key) {
#ifdef __ANDROID__
    char value[PROP_VALUE_MAX]{};
    __system_property_get(key.c_str(), value);
    return value;
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
    bool readEvents() {
        for (auto& d : devices) {
            input_event ev{};
            ssize_t n;
            while ((n = read(d.fd, &ev, sizeof(ev))) == sizeof(ev)) {
                if (ev.type == EV_SYN && ev.code == SYN_DROPPED) d.dropped = true;
                if (ev.type == EV_SYN && ev.code == SYN_REPORT) {
                    d.snapshot(); d.dropped = false;
                }
                if (!d.dropped && ev.type == EV_KEY && ev.code == KEY_POWER && ev.value == 1) stopped = 1;
                if (!d.dropped && ev.type == EV_KEY && ev.value &&
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
    bool start(int in, int out) {
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
            if (input) inBusy_ = false; else outBusy_ = false;
            const int64_t result = events[i].res;
            if (result < 0 && result != -ESHUTDOWN && result != -ENODEV && result != -ECANCELED && result != -EINTR)
                return false;
            if (input && result >= 0 && result != static_cast<int64_t>(input_.size())) return false;
        }
        if (!enabled) return true;
        if (!outBusy_ && !submit(false)) return false;
        if (!inBusy_) {
            input_ = xinput(state);
            if (!submit(true)) return false;
        }
        return true;
    }
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
    std::array<uint8_t, 20> input_{};
    std::array<uint8_t, 64> output_{};
};

int hidNode() {
    const std::string dev = readText("/config/usb_gadget/g1/functions/hid.andr36oid/dev");
    unsigned major, minor;
    if (sscanf(dev.c_str(), "%u:%u", &major, &minor) != 2 || minor > 255) return -1;
    const std::string path = "/dev/hidg" + std::to_string(minor);
    return open(path.c_str(), O_RDWR | O_NONBLOCK | O_CLOEXEC);
}
void setup(int ep0, const usb_ctrlrequest& request, const State& state) {
    // composite.c serves the OS descriptor before forwarding other requests.
    const auto* bytes = reinterpret_cast<const uint8_t*>(&request);
    const auto le = [&](unsigned pos) { return bytes[pos] | (bytes[pos + 1] << 8); };
    const auto reply = control(request.bRequestType, request.bRequest, le(2), le(4), le(6), state);
    uint8_t ignored[32]{};
    if (reply.action == ControlAction::Reply) write(ep0, reply.data.data(), reply.data.size());
    else if (reply.action == ControlAction::Receive) read(ep0, ignored, reply.length);
    else if (request.bRequestType & USB_DIR_IN) read(ep0, ignored, 0);
    else write(ep0, ignored, 0);
}
bool keyboardConnected() {
    return readText("/sys/class/udc/ff300000.usb/state").find("configured") == 0;
}
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
    client_ = -1; received_ = false; buttons_ = 0; scrolling_ = false; wheel_ = 0;
    keys_ = {1}; lastFrame_ = -1;
    pending_.clear();
    pending_.push_back(Bytes(keys_.begin(), keys_.end()));
    pending_.push_back({2,0,0,0,0});
}
bool KeyboardChannel::pump(const State& state, uint8_t leds, bool connected, int64_t time,
                           const std::string& owner, const std::string& start) {
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
                packet[9] > 1 || packet[11] > 1 || (packet[10] != 0 && packet[10] != 1 && packet[10] != 255)) {
            release(); return true;
        }
        std::array<uint8_t, 9> keys{1,packet[1],0};
        for (unsigned j = 0; j < 6; ++j) {
            if (packet[2 + j] > 0x65) { release(); return true; }
            keys[3 + j] = packet[2 + j];
        }
        if (!connected || packet[11] == 0) { keys = {1}; packet[8] = packet[9] = packet[10] = 0; }
        if (keys != keys_) {
            keys_ = keys;
            pending_.push_back(Bytes(keys.begin(), keys.end()));
        }
        if (buttons_ != packet[8]) {
            buttons_ = packet[8];
            pending_.push_back({2,buttons_,0,0,0});
        }
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
        packet[12] = leds; packet[13] = connected;
        ssize_t n = ::send(client_, packet.data(), packet.size(), MSG_DONTWAIT | MSG_NOSIGNAL);
        if (n == static_cast<ssize_t>(packet.size())) lastFrame_ = time;
        else if (n >= 0 || (errno != EAGAIN && errno != EINTR)) release();
    }
    return true;
}
}  // namespace controller

namespace {
int run(const std::string& mode) {
    Inputs inputs;
    if (!inputs.openAll()) { log("Cannot grab built-in controls"); return 1; }
    const bool xbox = mode == "xinput";
    int ep0 = -1, in = -1, out = -1;
    bool enabled = !xbox;
    XInputIo transfers;
    if (xbox) {
        ep0 = open("/dev/usb-ffs/xinput/ep0", O_RDWR | O_CLOEXEC);
        const auto desc = descriptors(); const auto str = strings();
        if (ep0 < 0 || write(ep0, desc.data(), desc.size()) != static_cast<ssize_t>(desc.size()) ||
                write(ep0, str.data(), str.size()) != static_cast<ssize_t>(str.size())) {
            log("Cannot register FunctionFS descriptors"); if (ep0 >= 0) close(ep0); return 1;
        }
        fcntl(ep0, F_SETFL, O_NONBLOCK);
        in = open("/dev/usb-ffs/xinput/ep1", O_RDWR | O_NONBLOCK | O_CLOEXEC);
        out = open("/dev/usb-ffs/xinput/ep2", O_RDWR | O_NONBLOCK | O_CLOEXEC);
        if (in < 0 || out < 0) { close(ep0); if (in >= 0) close(in); if (out >= 0) close(out); return 1; }
        if (!transfers.start(in, out)) { close(in); close(out); close(ep0); return 1; }
    }
    set("brightness", "15");
    set("ready", "1");
    const std::string helper = property(std::string(kPrefix) + "helper");
    const std::string helperStart = property(std::string(kPrefix) + "helper_start");
    const std::string owner = property(std::string(kPrefix) + "owner");
    const std::string ownerStart = property(std::string(kPrefix) + "owner_start");
    ExitHold hold;
    int result = 0;
    int64_t lastLease = 0, missingSince = now();
    KeyboardChannel channel;
    if (mode == "keyboard" && !channel.listen()) { log("Cannot open keyboard channel"); return 1; }
    KeyboardPointer pointerModel;
    uint8_t leds = 0;
    int64_t lastPointer = now(), lastConnection = 0;
    bool connected = false;
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
        if (!inputs.readEvents()) { log("Input device disappeared"); result = 1; break; }
        const State s = inputs.state();
        if (hold.update(s.buttons[Back] && s.buttons[Start], time)) break;
        if (xbox) {
            pollfd p{ep0,POLLIN,0};
            if (poll(&p, 1, 0) > 0 && (p.revents & POLLIN)) {
                usb_functionfs_event events[8]{};
                ssize_t bytes = read(ep0, events, sizeof(events));
                if (bytes < 0 && errno != EAGAIN && errno != EINTR) { result = 1; break; }
                for (ssize_t i = 0; i < bytes / static_cast<ssize_t>(sizeof(events[0])); ++i) {
                    if (events[i].type == FUNCTIONFS_ENABLE) enabled = true;
                    if (events[i].type == FUNCTIONFS_DISABLE || events[i].type == FUNCTIONFS_UNBIND) enabled = false;
                    if (events[i].type == FUNCTIONFS_SETUP) setup(ep0, events[i].u.setup, s);
                }
            }
            if (!transfers.pump(s, enabled)) { log("FunctionFS transfer failed"); result = 1; break; }
        } else {
            if (in < 0) in = hidNode();
            if (in >= 0) {
                if (mode == "gamepad") {
                    const auto report = gamepad(s);
                    if (!send(in, report.data(), report.size())) { result = 1; break; }
                } else {
                    if (time - lastConnection >= 100) {
                        lastConnection = time; connected = keyboardConnected();
                        if (!connected) leds = 0;
                    }
                    channel.pump(s, leds, connected, time, owner, ownerStart);
                    const KeyboardPointer before = pointerModel;
                    const bool queued = !channel.pending().empty();
                    const State motion = channel.active() && connected ? s : State{};
                    const auto pointer = pointerModel.report(motion, channel.buttons(), channel.scrolling(),
                            channel.wheel(), (time - lastPointer) / 1000.0);
                    const Bytes report = queued ? channel.pending().front() : Bytes(pointer.begin(), pointer.end());
                    ssize_t n = write(in, report.data(), report.size());
                    if (n == static_cast<ssize_t>(report.size())) {
                        if (queued) { channel.sent(); pointerModel = before; }
                        else lastPointer = time;
                    } else {
                        pointerModel = before;
                        if (n < 0 && (errno == ESHUTDOWN || errno == ENODEV)) {
                            channel.release(); connected = false; leds = 0;
                            pointerModel = KeyboardPointer(); lastPointer = time;
                        }
                    }
                    if ((n >= 0 && n != static_cast<ssize_t>(report.size())) ||
                            (n < 0 && errno != EAGAIN && errno != EINTR && errno != ESHUTDOWN && errno != ENODEV)) {
                        result = 1; break;
                    }
                }
                uint8_t output[64]{};
                const ssize_t bytes = read(in, output, sizeof(output));
                if (mode == "keyboard" && bytes >= 2 && output[0] == 1) leds = output[1] & 31;

            } else if (property(std::string(kPrefix) + "state") == "sharing" && time - missingSince > 5000) {
                result = 1; break;
            }
        }
        timespec pause{0,xbox ? 1000000 : 4000000}; nanosleep(&pause, nullptr);
    }
    transfers.cancel();
    if (in >= 0) {
        const State neutral;
        if (!xbox && mode == "gamepad") { const auto r = gamepad(neutral); send(in, r.data(), r.size()); }
        if (!xbox && mode == "keyboard") {
            channel.release();
            const int64_t deadline = now() + 100;
            while (!channel.pending().empty() && now() < deadline) {
                const Bytes& r = channel.pending().front();
                if (write(in, r.data(), r.size()) == static_cast<ssize_t>(r.size())) channel.sent();
                else { timespec pause{0,1000000}; nanosleep(&pause, nullptr); }
            }
        }
        close(in);
    }
    if (out >= 0) close(out);
    if (ep0 >= 0) close(ep0);
    return result;
}
}

int main(int argc, char** argv) {
    using namespace controller;
    if (argc == 3 && std::string(argv[1]) == "--descriptor" &&
            (std::string(argv[2]) == "gamepad" || std::string(argv[2]) == "keyboard")) {
        const auto desc = hidDescriptor(std::string(argv[2]) == "keyboard");
        return fwrite(desc.data(), 1, desc.size(), stdout) == desc.size() ? 0 : 1;
    }
    if (argc == 3 && std::string(argv[1]) == "--identity") {
        uint16_t product = 0;
        if (std::string(argv[2]) == "xinput") product = kXInputProduct;
        if (std::string(argv[2]) == "gamepad") product = kGamepadProduct;
        if (std::string(argv[2]) == "keyboard") product = kKeyboardProduct;
        if (!product) return 2;
        printf("0x%04x 0x%04x\n", kVendor, product);
        return 0;
    }
    if (argc != 2 || (std::string(argv[1]) != "xinput" && std::string(argv[1]) != "gamepad" &&
                     std::string(argv[1]) != "keyboard")) return 2;
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
    const int result = run(argv[1]);
    if (result) set("error", "controller_daemon");
    set("request", "stop"); set("ready", "0");
    return result;
}
