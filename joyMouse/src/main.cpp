#include <poll.h>
#include <signal.h>
#include <sys/inotify.h>
#include <sys/signalfd.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <memory>
#include <optional>
#include <string>
#include <vector>

#include "Announcer.h"
#include "Config.h"
#include "Controller.h"
#include "Display.h"
#include "EvdevPad.h"
#include "Log.h"
#include "Properties.h"
#include "Time.h"
#include "Uinput.h"

namespace joymouse {

namespace {

constexpr Nanos kRescanInterval = msToNanos(2000);

const char* sideName(std::optional<StickSide> side) {
    if (!side) return "none";
    return *side == StickSide::Left ? "left" : "right";
}

class Daemon : public ModeListener {
public:
    Daemon();
    ~Daemon() override;
    int run(bool verbose);
    void onMouseModeChanged(bool active) override;
    bool inFullscreenApp() override;

private:
    Config readConfig();
    void applyDisplaySize();
    void loadSettings(Nanos now);
    void syncActiveProperty(Nanos now);
    void openPad(Nanos now);
    void closePad(Nanos now);
    void readPad(Nanos now);
    bool handleSignals(Nanos now);
    void drainInotify();
    Announcement describe(bool active) const;

    Config config_;
    std::vector<std::string> lastWarnings_;
    Environment env_;
    bool verbose_ = false;
    std::unique_ptr<EvdevPad> pad_;
    std::unique_ptr<VirtualMouse> mouse_;
    std::unique_ptr<VirtualPad> passthrough_;
    std::unique_ptr<Controller> controller_;
    Announcer announcer_;
    PropertyWatcher watcher_;
    int signalFd_ = -1;
    int inotifyFd_ = -1;
    bool wantActive_ = false;
    bool quiet_ = false;  // mode changes caused by the daemon itself (pad lost, shutdown)
    bool warnedNoPad_ = false;
    Nanos nextRescan_ = 0;
};

Daemon::Daemon() = default;

Daemon::~Daemon() {
    controller_.reset();
    passthrough_.reset();
    mouse_.reset();
    pad_.reset();
    if (inotifyFd_ >= 0) close(inotifyFd_);
    if (signalFd_ >= 0) close(signalFd_);
}

// The framework writes the active hardware button remap to a file, it doesn't
// fit in a property. Missing file: nothing is remapped.
std::string readButtonRemap() {
    std::string remap;
    if (FILE* f = std::fopen(kButtonRemapFile, "re")) {
        char buf[1024];
        const size_t n = std::fread(buf, 1, sizeof(buf), f);
        std::fclose(f);
        remap.assign(buf, n);
    }
    return remap;
}

Config Daemon::readConfig() {
    std::vector<std::string> warnings;
    Config c = loadConfig([](const char* name) { return getProperty(name); }, &warnings);
    c.buttonRemap = readButtonRemap();
    // Properties change all the time system-wide; only repeat news.
    if (warnings != lastWarnings_) {
        for (const std::string& w : warnings) LOGW("%s", w.c_str());
        lastWarnings_ = warnings;
    }
    return c;
}

void Daemon::applyDisplaySize() {
    const auto size = config_.displaySize ? *config_.displaySize : detectDisplaySize();
    env_.displayWidth = size.first;
    env_.displayHeight = size.second;
    LOGI("display %dx%d", env_.displayWidth, env_.displayHeight);
}

void Daemon::loadSettings(Nanos now) {
    const Config next = readConfig();
    if (next != config_) {
        const bool deviceChanged = next.device != config_.device;
        const bool displayChanged = next.displaySize != config_.displaySize;
        config_ = next;
        setDebugLogging(verbose_ || config_.debug);
        if (displayChanged) applyDisplaySize();
        if (deviceChanged) {
            closePad(now);
            openPad(now);
        } else if (controller_) {
            controller_->configure(config_, env_, now);
        }
    }
    syncActiveProperty(now);
}

void Daemon::syncActiveProperty(Nanos now) {
    const std::string value = getProperty(kActiveProperty);
    if (value != "0" && value != "1") return;
    wantActive_ = value == "1";
    if (controller_ && controller_->active() != wantActive_) controller_->setActive(wantActive_, now);
}

void Daemon::openPad(Nanos now) {
    nextRescan_ = now + kRescanInterval;
    pad_ = EvdevPad::find(config_.device);
    if (!pad_) {
        if (!warnedNoPad_) LOGW("no game pad with an analog stick found, waiting for one");
        warnedNoPad_ = true;
        return;
    }
    warnedNoPad_ = false;
    mouse_ = std::make_unique<VirtualMouse>();
    passthrough_ = std::make_unique<VirtualPad>(pad_->info(), pad_->extras());
    controller_ = std::make_unique<Controller>(pad_->info(), *pad_, *mouse_, *passthrough_, this);
    controller_->configure(config_, env_, now);
    if (wantActive_) controller_->setActive(true, now);
}

void Daemon::closePad(Nanos now) {
    if (controller_) {
        // Keep sys.joymouse.active as it is so the mode comes back with the pad.
        quiet_ = true;
        controller_->shutdown(now);
        quiet_ = false;
    }
    controller_.reset();
    passthrough_.reset();
    mouse_.reset();
    pad_.reset();
}

void Daemon::readPad(Nanos now) {
    std::vector<InputEvent> events;
    const EvdevPad::ReadResult result = pad_->read(&events);
    for (const InputEvent& ev : events) controller_->onInput(ev, now);
    if (result == EvdevPad::ReadResult::Gone) {
        LOGW("%s went away", pad_->path().c_str());
        closePad(now);
        openPad(now);
    }
}

bool Daemon::handleSignals(Nanos now) {
    signalfd_siginfo info;
    bool keepRunning = true;
    while (read(signalFd_, &info, sizeof(info)) == static_cast<ssize_t>(sizeof(info))) {
        switch (info.ssi_signo) {
            case SIGCHLD:
                announcer_.reap();
                break;
            case SIGHUP:
                loadSettings(now);
                break;
            default:
                LOGI("stopping");
                quiet_ = true;
                if (controller_) controller_->shutdown(now);
                keepRunning = false;
                break;
        }
    }
    return keepRunning;
}

void Daemon::drainInotify() {
    alignas(inotify_event) char buf[4096];
    while (read(inotifyFd_, buf, sizeof(buf)) > 0) {
    }
}

Announcement Daemon::describe(bool active) const {
    Announcement a;
    a.active = active;
    a.pointerStick = sideName(controller_ ? controller_->pointerStick() : std::nullopt);
    a.scrollStick = sideName(controller_ ? controller_->scrollStick() : std::nullopt);
    a.holdMs = config_.toggleMs;
    a.toggle = "none";
    if (controller_ && controller_->chord().enabled()) {
        a.toggle.clear();
        for (Button b : controller_->chord().members()) {
            if (!a.toggle.empty()) a.toggle += "+";
            a.toggle += buttonName(b);
        }
    }
    if (controller_ && controller_->classic()) {
        if (const auto click = controller_->pointerClick()) {
            a.bindings = std::string(buttonName(*click)) + "=left";
        }
    } else if (pad_) {
        const ButtonMap buttons(pad_->info().keys);
        const Bindings bindings = config_.bindings();
        for (size_t i = 0; i < kButtonCount; ++i) {
            const Button b = static_cast<Button>(i);
            if (!buttons.has(b) || bindings[i] == Action::Pass || bindings[i] == Action::None) continue;
            if (!a.bindings.empty()) a.bindings += ",";
            a.bindings += std::string(buttonName(b)) + "=" + actionName(bindings[i]);
        }
    }
    return a;
}

bool Daemon::inFullscreenApp() {
    return getProperty(kFullscreenProperty) == "1";
}

void Daemon::onMouseModeChanged(bool active) {
    if (quiet_) return;
    wantActive_ = active;
    setProperty(kActiveProperty, active ? "1" : "0");
    if (!config_.toast || getProperty("sys.boot_completed") != "1") return;
    announcer_.announce(describe(active));
}

int Daemon::run(bool verbose) {
    verbose_ = verbose;
    sigset_t mask;
    sigemptyset(&mask);
    sigaddset(&mask, SIGTERM);
    sigaddset(&mask, SIGINT);
    sigaddset(&mask, SIGHUP);
    sigaddset(&mask, SIGCHLD);
    sigprocmask(SIG_BLOCK, &mask, nullptr);
    signal(SIGPIPE, SIG_IGN);
    signalFd_ = signalfd(-1, &mask, SFD_NONBLOCK | SFD_CLOEXEC);
    if (signalFd_ < 0) {
        LOGE("signalfd failed: %s", strerror(errno));
        return 1;
    }
    inotifyFd_ = inotify_init1(IN_NONBLOCK | IN_CLOEXEC);
    if (inotifyFd_ >= 0 && inotify_add_watch(inotifyFd_, "/dev/input", IN_CREATE | IN_ATTRIB) < 0) {
        close(inotifyFd_);
        inotifyFd_ = -1;
    }

    Nanos now = monotonicNow();
    config_ = readConfig();
    setDebugLogging(verbose_ || config_.debug);
    env_.productDevice = getProperty("ro.product.device");
    applyDisplaySize();
    const std::string active = getProperty(kActiveProperty);
    wantActive_ = active == "1" || (active.empty() && config_.startActive);
    LOGI("started on %s", env_.productDevice.empty() ? "unknown device" : env_.productDevice.c_str());
    openPad(now);

    while (true) {
        std::vector<pollfd> fds;
        fds.push_back({signalFd_, POLLIN, 0});
        if (inotifyFd_ >= 0) fds.push_back({inotifyFd_, POLLIN, 0});
        if (watcher_.fd() >= 0) fds.push_back({watcher_.fd(), POLLIN, 0});
        if (pad_) fds.push_back({pad_->fd(), POLLIN, 0});

        Nanos deadline = -1;
        if (controller_) deadline = controller_->nextDeadline().value_or(-1);
        if (!pad_ && inotifyFd_ < 0 && (deadline < 0 || nextRescan_ < deadline)) deadline = nextRescan_;
        timespec ts{};
        timespec* timeout = nullptr;
        if (deadline >= 0) {
            const Nanos wait = std::max<Nanos>(deadline - monotonicNow(), 0);
            ts.tv_sec = static_cast<time_t>(wait / kNanosPerSec);
            ts.tv_nsec = static_cast<long>(wait % kNanosPerSec);
            timeout = &ts;
        }
        if (ppoll(fds.data(), fds.size(), timeout, nullptr) < 0 && errno != EINTR) {
            LOGE("ppoll failed: %s", strerror(errno));
            return 1;
        }

        now = monotonicNow();
        for (const pollfd& p : fds) {
            if ((p.revents & (POLLIN | POLLERR | POLLHUP)) == 0) continue;
            if (p.fd == signalFd_) {
                if (!handleSignals(now)) return 0;
            } else if (p.fd == inotifyFd_) {
                drainInotify();
                if (!pad_) openPad(now);
            } else if (p.fd == watcher_.fd()) {
                watcher_.drain();
                loadSettings(now);
            } else if (pad_ && p.fd == pad_->fd()) {
                readPad(now);
            }
        }
        if (!pad_ && inotifyFd_ < 0 && now >= nextRescan_) openPad(now);
        if (controller_) controller_->onTimeout(monotonicNow());
    }
}

}  // namespace

}  // namespace joymouse

int main(int argc, char** argv) {
    bool verbose = false;
    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i];
        if (arg == "-v" || arg == "--verbose") {
            verbose = true;
        } else {
            fprintf(stderr, "usage: %s [-v]\n", argv[0]);
            return 2;
        }
    }
    joymouse::Daemon daemon;
    return daemon.run(verbose);
}
