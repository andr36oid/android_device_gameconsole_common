#pragma once

#include <sys/types.h>

#include <cstdint>
#include <string>

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
class Announcer {
public:
    static constexpr const char kPackage[] = "com.gameconsole.joymouse";
    static constexpr const char kAction[] = "com.gameconsole.joymouse.action.MODE_CHANGED";

    void announce(Announcement a);
    // Call on SIGCHLD.
    void reap();

private:
    void spawn(const Announcement& a);
};

}  // namespace joymouse
