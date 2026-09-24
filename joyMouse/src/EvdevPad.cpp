#include "EvdevPad.h"

#include <dirent.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <algorithm>
#include <cctype>
#include <cerrno>
#include <climits>
#include <cstdlib>
#include <cstring>

#include "Log.h"
#include "Uinput.h"

namespace joymouse {

namespace {

constexpr const char kInputDir[] = "/dev/input";
constexpr size_t kLongBits = sizeof(unsigned long) * CHAR_BIT;

constexpr size_t longsFor(size_t bits) {
    return (bits + kLongBits - 1) / kLongBits;
}

bool testBit(const unsigned long* bits, size_t bit) {
    return ((bits[bit / kLongBits] >> (bit % kLongBits)) & 1UL) != 0;
}

template <size_t N>
bool readBits(int fd, unsigned int type, unsigned long (&bits)[N]) {
    return ioctl(fd, EVIOCGBIT(type, sizeof(bits)), bits) >= 0;
}

bool containsIgnoreCase(const std::string& haystack, const std::string& needle) {
    auto it = std::search(haystack.begin(), haystack.end(), needle.begin(), needle.end(),
                          [](char a, char b) {
                              return std::tolower(static_cast<unsigned char>(a)) ==
                                     std::tolower(static_cast<unsigned char>(b));
                          });
    return it != haystack.end();
}

// Devices created through uinput live under /sys/devices/virtual.
bool isVirtualDevice(const std::string& node) {
    const std::string sysPath = "/sys/class/input/" + node.substr(node.find_last_of('/') + 1);
    char resolved[PATH_MAX];
    if (realpath(sysPath.c_str(), resolved) == nullptr) return false;
    return std::strstr(resolved, "/virtual/") != nullptr;
}

bool readInfo(int fd, PadInfo* info, PadExtras* extras) {
    char text[256] = {};
    if (ioctl(fd, EVIOCGNAME(sizeof(text) - 1), text) >= 0) info->name = text;
    std::memset(text, 0, sizeof(text));
    if (ioctl(fd, EVIOCGPHYS(sizeof(text) - 1), text) >= 0) extras->phys = text;
    if (ioctl(fd, EVIOCGID, &info->id) < 0) return false;

    unsigned long evBits[longsFor(EV_CNT)] = {};
    if (!readBits(fd, 0, evBits)) return false;

    if (testBit(evBits, EV_KEY)) {
        unsigned long keyBits[longsFor(KEY_CNT)] = {};
        if (readBits(fd, EV_KEY, keyBits)) {
            for (size_t code = 0; code < KEY_CNT; ++code) {
                if (testBit(keyBits, code)) info->keys.set(code);
            }
        }
    }
    if (testBit(evBits, EV_ABS)) {
        unsigned long absBits[longsFor(ABS_CNT)] = {};
        if (readBits(fd, EV_ABS, absBits)) {
            for (size_t code = 0; code < ABS_CNT; ++code) {
                input_absinfo abs{};
                if (testBit(absBits, code) &&
                    ioctl(fd, EVIOCGABS(static_cast<unsigned int>(code)), &abs) >= 0) {
                    info->abs[code] = abs;
                }
            }
        }
    }
    if (testBit(evBits, EV_MSC)) {
        unsigned long mscBits[longsFor(MSC_CNT)] = {};
        if (readBits(fd, EV_MSC, mscBits)) {
            for (size_t code = 0; code < MSC_CNT; ++code) {
                if (testBit(mscBits, code)) extras->msc.push_back(static_cast<uint16_t>(code));
            }
        }
    }
    unsigned long propBits[longsFor(INPUT_PROP_CNT)] = {};
    if (ioctl(fd, EVIOCGPROP(sizeof(propBits)), propBits) >= 0) {
        for (size_t prop = 0; prop < INPUT_PROP_CNT; ++prop) {
            if (testBit(propBits, prop)) extras->props.push_back(static_cast<uint16_t>(prop));
        }
    }
    return true;
}

// How much a device looks like the handheld's built-in pad; negative if not a pad.
int score(const PadInfo& info) {
    const bool left = info.hasAbs(ABS_X) && info.hasAbs(ABS_Y);
    const bool right = info.hasAbs(ABS_RX) && info.hasAbs(ABS_RY);
    if (!left && !right) return -1;
    const ButtonMap buttons(info.keys);
    if (!buttons.has(Button::A) && !buttons.has(Button::B) && !buttons.has(Button::Start)) return -1;

    int s = left && right ? 100 : 50;
    if (info.id.vendor == 0x484b) s += 20;  // Hardkernel joypad driver used by the RK3326 handhelds
    if (info.id.bustype == BUS_HOST) s += 10;
    if (info.id.bustype == BUS_USB || info.id.bustype == BUS_BLUETOOTH) s -= 40;
    return s;
}

std::vector<std::string> eventNodes() {
    std::vector<std::pair<int, std::string>> nodes;
    if (DIR* dir = opendir(kInputDir)) {
        while (const dirent* entry = readdir(dir)) {
            if (std::strncmp(entry->d_name, "event", 5) != 0) continue;
            char* end = nullptr;
            const long n = std::strtol(entry->d_name + 5, &end, 10);
            if (end == entry->d_name + 5 || *end != '\0') continue;
            nodes.emplace_back(static_cast<int>(n), std::string(kInputDir) + "/" + entry->d_name);
        }
        closedir(dir);
    }
    std::sort(nodes.begin(), nodes.end());
    std::vector<std::string> paths;
    for (auto& node : nodes) paths.push_back(std::move(node.second));
    return paths;
}

}  // namespace

EvdevPad::EvdevPad(int fd, std::string path, PadInfo info, PadExtras extras)
    : fd_(fd), path_(std::move(path)), info_(std::move(info)), extras_(std::move(extras)) {}

EvdevPad::~EvdevPad() {
    if (grabbed_) ioctl(fd_, EVIOCGRAB, 0);
    close(fd_);
}

std::unique_ptr<EvdevPad> EvdevPad::find(const std::string& hint) {
    std::unique_ptr<EvdevPad> best;
    int bestScore = -1;
    for (const std::string& path : eventNodes()) {
        const int fd = open(path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC);
        if (fd < 0) continue;
        PadInfo info;
        PadExtras extras;
        int s = -1;
        // Never pick one of our own virtual devices.
        if (readInfo(fd, &info, &extras) && extras.phys.rfind(kOwnPhysPrefix, 0) != 0) {
            if (!hint.empty()) {
                // An explicit choice may even be another virtual device.
                if (path == hint || containsIgnoreCase(info.name, hint)) s = score(info);
            } else if (!isVirtualDevice(path)) {
                s = score(info);
            }
        }
        if (s > bestScore) {
            bestScore = s;
            best.reset(new EvdevPad(fd, path, std::move(info), std::move(extras)));
        } else {
            close(fd);
        }
    }
    if (best) {
        LOGI("using %s \"%s\" (%04x:%04x)", best->path_.c_str(), best->info_.name.c_str(),
             best->info_.id.vendor, best->info_.id.product);
    }
    return best;
}

EvdevPad::ReadResult EvdevPad::read(std::vector<InputEvent>* out) {
    input_event buf[64];
    for (int round = 0; round < 16; ++round) {
        const ssize_t n = ::read(fd_, buf, sizeof(buf));
        if (n < 0) {
            if (errno == EINTR) continue;
            if (errno == EAGAIN) return ReadResult::Ok;
            LOGW("reading %s failed: %s", path_.c_str(), strerror(errno));
            return ReadResult::Gone;
        }
        const size_t count = static_cast<size_t>(n) / sizeof(input_event);
        for (size_t i = 0; i < count; ++i) out->push_back({buf[i].type, buf[i].code, buf[i].value});
        if (count < sizeof(buf) / sizeof(buf[0])) return ReadResult::Ok;
    }
    return ReadResult::Ok;
}

bool EvdevPad::setGrabbed(bool grabbed) {
    if (grabbed == grabbed_) return true;
    if (ioctl(fd_, EVIOCGRAB, grabbed ? 1 : 0) < 0) {
        LOGW("%s %s failed: %s", grabbed ? "grabbing" : "releasing", path_.c_str(), strerror(errno));
        return false;
    }
    grabbed_ = grabbed;
    return true;
}

void EvdevPad::setAbsReporting(bool enabled) {
#ifdef EVIOCSMASK
    unsigned long types[longsFor(EV_CNT)];
    std::memset(types, 0xff, sizeof(types));
    if (!enabled) types[EV_ABS / kLongBits] &= ~(1UL << (EV_ABS % kLongBits));
    input_mask mask{};
    mask.type = 0;  // mask of event types
    mask.codes_size = sizeof(types);
    mask.codes_ptr = static_cast<__u64>(reinterpret_cast<uintptr_t>(types));
    if (ioctl(fd_, EVIOCSMASK, &mask) < 0) {
        LOGD("EVIOCSMASK unsupported: %s", strerror(errno));
    }
#else
    (void)enabled;
#endif
}

bool EvdevPad::readState(KeyBits* keys, AbsValues* abs) {
    unsigned long keyBits[longsFor(KEY_CNT)] = {};
    if (ioctl(fd_, EVIOCGKEY(sizeof(keyBits)), keyBits) < 0) return false;
    keys->reset();
    for (size_t code = 0; code < KEY_CNT; ++code) {
        if (testBit(keyBits, code)) keys->set(code);
    }
    for (int code = 0; code < ABS_CNT; ++code) {
        input_absinfo info{};
        if (info_.hasAbs(code) && ioctl(fd_, EVIOCGABS(static_cast<unsigned int>(code)), &info) >= 0) {
            (*abs)[static_cast<size_t>(code)] = info.value;
        }
    }
    return true;
}

}  // namespace joymouse
