#include "Announcer.h"

#include <signal.h>
#include <spawn.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cstring>
#include <string>
#include <vector>

#include "Log.h"
#include "Time.h"

extern char** environ;

namespace joymouse {

namespace {

constexpr const char kCmd[] = "/system/bin/cmd";

}  // namespace

void Announcer::announce(Announcement a) {
    spawn(a);
}

void Announcer::reap() {
    int status = 0;
    while (waitpid(-1, &status, WNOHANG) > 0) {
    }
}

void Announcer::spawn(const Announcement& a) {
    // Monotonic time orders the toasts, even across daemon restarts.
    const std::string seq = std::to_string(monotonicNow() / kNanosPerMs);
    std::vector<std::string> args = {
            kCmd, "activity", "broadcast",
            "-a", kAction,
            "-n", std::string(kPackage) + "/.ModeToastReceiver",
            "--receiver-foreground",
            "--include-stopped-packages",
            "--ez", "active", a.active ? "true" : "false",
            "--es", "pointer", a.pointerStick,
            "--es", "scroll", a.scrollStick,
            "--es", "toggle", a.toggle,
            "--ei", "hold_ms", std::to_string(a.holdMs),
            "--es", "bindings", a.bindings.empty() ? "none" : a.bindings,
            "--el", "seq", seq,
    };
    std::vector<char*> argv;
    for (std::string& arg : args) argv.push_back(arg.data());
    argv.push_back(nullptr);

    // Undo the daemon's blocked/ignored signals for the child.
    posix_spawnattr_t attr;
    posix_spawnattr_init(&attr);
    sigset_t none;
    sigemptyset(&none);
    posix_spawnattr_setsigmask(&attr, &none);
    sigset_t defaults;
    sigemptyset(&defaults);
    sigaddset(&defaults, SIGPIPE);
    sigaddset(&defaults, SIGCHLD);
    posix_spawnattr_setsigdefault(&attr, &defaults);
    posix_spawnattr_setflags(&attr, POSIX_SPAWN_SETSIGMASK | POSIX_SPAWN_SETSIGDEF);

    pid_t pid = -1;
    const int rc = posix_spawn(&pid, kCmd, nullptr, &attr, argv.data(), environ);
    posix_spawnattr_destroy(&attr);
    if (rc != 0) LOGW("cannot run %s: %s", kCmd, strerror(rc));
}

}  // namespace joymouse
