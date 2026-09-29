#pragma once

#include <sys/types.h>

#include <cstdint>
#include <optional>
#include <string>
#include <vector>

#include "Time.h"

namespace joymouse {

// What the companion app needs to explain mouse mode in its toast.
struct Announcement {
    bool active = false;
    std::string pointerStick;  // "right", "left"
    std::string scrollStick;   // "left", "right" or "none"
    std::string toggle;        // "L3+R3" or "none"
    int holdMs = 0;
    std::string bindings;      // "R1=left,L1=right,..." (buttons that do something)
};

// Tells the JoyMouse app about mode changes so it can show a toast. Goes
// through `cmd activity broadcast`, which waits for the receiver, so every
// change gets its own command right away instead of queueing behind a slow
// one. Each carries a sequence number; the app drops any that arrive late.
//
// The app isn't persistent (that cost ~5 MB for good), so warmUp() starts it
// when the toggle chord begins: the hold (1 s by default) is longer than an
// app start, and the toast then shows as quickly as before.
class Announcer {
public:
    static constexpr const char kPackage[] = "com.gameconsole.joymouse";
    static constexpr const char kAction[] = "com.gameconsole.joymouse.action.MODE_CHANGED";
    static constexpr const char kWarmUpAction[] = "com.gameconsole.joymouse.action.WARM_UP";
    // A cached app stays around for a while; don't send one per chord press.
    static constexpr Nanos kWarmUpInterval = msToNanos(20000);

    void announce(Announcement a);
    void warmUp(Nanos now);
    // Call on SIGCHLD.
    void reap();

private:
    void spawn(const Announcement& a);
    void run(std::vector<std::string> args);

    std::optional<Nanos> lastWarmUp_;
};

}  // namespace joymouse
