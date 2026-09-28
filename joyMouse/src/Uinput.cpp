#include "Uinput.h"

#include <fcntl.h>
#include <linux/uinput.h>
#include <sys/ioctl.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstring>

#include "Log.h"
#include "TouchSurface.h"

namespace joymouse {

namespace {

constexpr const char kUinputPath[] = "/dev/uinput";

bool setBits(int fd, unsigned long evRequest, int type, unsigned long bitRequest,
             const std::vector<uint16_t>& codes) {
    if (codes.empty()) return true;
    if (ioctl(fd, evRequest, type) < 0) return false;
    for (uint16_t code : codes) {
        if (ioctl(fd, bitRequest, static_cast<int>(code)) < 0) return false;
    }
    return true;
}

}  // namespace

UinputDevice::~UinputDevice() {
    close();
}

bool UinputDevice::open() {
    if (fd_ >= 0) return true;
    const Setup s = setup();
    const int fd = ::open(kUinputPath, O_WRONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) {
        LOGE("cannot open %s: %s", kUinputPath, strerror(errno));
        return false;
    }
    auto fail = [&](const char* what) {
        LOGE("creating \"%s\" failed at %s: %s", s.name.c_str(), what, strerror(errno));
        ::close(fd);
        return false;
    };

    std::vector<uint16_t> absCodes;
    for (const auto& [code, info] : s.abs) absCodes.push_back(code);
    if (!setBits(fd, UI_SET_EVBIT, EV_KEY, UI_SET_KEYBIT, s.keys)) return fail("keys");
    if (!setBits(fd, UI_SET_EVBIT, EV_REL, UI_SET_RELBIT, s.rels)) return fail("relative axes");
    if (!setBits(fd, UI_SET_EVBIT, EV_ABS, UI_SET_ABSBIT, absCodes)) return fail("absolute axes");
    if (!setBits(fd, UI_SET_EVBIT, EV_MSC, UI_SET_MSCBIT, s.msc)) return fail("misc events");
    for (uint16_t prop : s.props) ioctl(fd, UI_SET_PROPBIT, static_cast<int>(prop));
    if (!s.phys.empty()) ioctl(fd, UI_SET_PHYS, s.phys.c_str());

    bool configured = false;
#ifdef UI_DEV_SETUP
    uinput_setup us{};
    us.id = s.id;
    snprintf(us.name, sizeof(us.name), "%s", s.name.c_str());
    if (ioctl(fd, UI_DEV_SETUP, &us) >= 0) {
        configured = true;
        for (const auto& [code, info] : s.abs) {
            uinput_abs_setup abs{};
            abs.code = code;
            abs.absinfo = info;
            if (ioctl(fd, UI_ABS_SETUP, &abs) < 0) return fail("UI_ABS_SETUP");
        }
    }
#endif
    if (!configured) {
        // Kernels before 4.5 only take the legacy setup struct.
        uinput_user_dev dev{};
        snprintf(dev.name, sizeof(dev.name), "%s", s.name.c_str());
        dev.id = s.id;
        for (const auto& [code, info] : s.abs) {
            dev.absmin[code] = info.minimum;
            dev.absmax[code] = info.maximum;
            dev.absfuzz[code] = info.fuzz;
            dev.absflat[code] = info.flat;
        }
        if (write(fd, &dev, sizeof(dev)) != static_cast<ssize_t>(sizeof(dev))) return fail("setup");
    }
    if (ioctl(fd, UI_DEV_CREATE) < 0) return fail("UI_DEV_CREATE");

    fd_ = fd;
    name_ = s.name;
    writeFailed_ = false;
    LOGD("created \"%s\"", name_.c_str());
    return true;
}

void UinputDevice::close() {
    if (fd_ < 0) return;
    ioctl(fd_, UI_DEV_DESTROY);
    ::close(fd_);
    fd_ = -1;
    LOGD("removed \"%s\"", name_.c_str());
}

bool UinputDevice::send(const std::vector<InputEvent>& events) {
    if (fd_ < 0 || events.empty()) return false;
    // Timestamps stay zero: the kernel stamps injected events itself.
    std::vector<input_event> buf(events.size());
    for (size_t i = 0; i < events.size(); ++i) {
        buf[i].type = events[i].type;
        buf[i].code = events[i].code;
        buf[i].value = events[i].value;
    }
    const size_t bytes = buf.size() * sizeof(input_event);
    ssize_t n;
    do {
        n = write(fd_, buf.data(), bytes);
    } while (n < 0 && errno == EINTR);
    if (n != static_cast<ssize_t>(bytes)) {
        if (!writeFailed_) LOGW("writing to \"%s\" failed: %s", name_.c_str(), strerror(errno));
        writeFailed_ = true;
        return false;
    }
    return true;
}

UinputDevice::Setup VirtualMouse::setup() const {
    Setup s;
    s.name = "joyMouse";
    s.phys = std::string(kOwnPhysPrefix) + "pointer";
    // BUS_VIRTUAL keeps Android from treating it as an external mouse, which
    // would let stick movements wake the screen.
    s.id.bustype = BUS_VIRTUAL;
    s.id.version = 1;
    s.keys = {BTN_LEFT, BTN_RIGHT, BTN_MIDDLE, BTN_SIDE, BTN_EXTRA};
    s.rels = {REL_X, REL_Y, REL_WHEEL, REL_HWHEEL};
    return s;
}

UinputDevice::Setup VirtualPad::setup() const {
    Setup s;
    s.name = info_.name;
    s.phys = std::string(kOwnPhysPrefix) + "passthrough";
    s.id = info_.id;
    for (size_t code = 0; code < KEY_CNT; ++code) {
        if (info_.keys.test(code)) s.keys.push_back(static_cast<uint16_t>(code));
    }
    for (int code = 0; code < ABS_CNT; ++code) {
        if (!info_.hasAbs(code)) continue;
        input_absinfo abs = *info_.abs[static_cast<size_t>(code)];
        abs.value = info_.center(code);
        s.abs.emplace_back(static_cast<uint16_t>(code), abs);
    }
    s.msc = extras_.msc;
    s.props = extras_.props;
    return s;
}

UinputDevice::Setup VirtualTouchscreen::setup() const {
    Setup s;
    s.name = kName;
    s.phys = std::string(kOwnPhysPrefix) + "touch";
    // Vendor and product 0: Android finds the .idc by name. BUS_VIRTUAL keeps
    // it internal, so it maps to the built-in display and doesn't wake it.
    s.id.bustype = BUS_VIRTUAL;
    s.id.version = 1;
    s.keys = {BTN_TOUCH};
    auto axis = [](int32_t max) {
        input_absinfo a{};
        a.minimum = 0;
        a.maximum = max;
        return a;
    };
    s.abs.emplace_back(ABS_MT_SLOT, axis(TouchSurface::kSlots - 1));
    s.abs.emplace_back(ABS_MT_TRACKING_ID, axis(TouchSurface::kMaxTrackingId));
    s.abs.emplace_back(ABS_MT_POSITION_X, axis(std::max(width_ - 1, 1)));
    s.abs.emplace_back(ABS_MT_POSITION_Y, axis(std::max(height_ - 1, 1)));
    s.props = {INPUT_PROP_DIRECT};
    return s;
}

}  // namespace joymouse
