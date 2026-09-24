#pragma once

#include <sys/types.h>

#include <cstdint>
#include <optional>
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
// through `cmd activity broadcast`; one broadcast in flight at a time, and
// only the newest pending state is sent, so toasts never arrive out of order.
class Announcer {
public:
    static constexpr const char kPackage[] = "com.gameconsole.joymouse";
    static constexpr const char kAction[] = "com.gameconsole.joymouse.action.MODE_CHANGED";

    void announce(Announcement a);
    // Call on SIGCHLD.
    void reap();

private:
    void spawn(const Announcement& a);

    pid_t child_ = -1;
    std::optional<Announcement> pending_;
};

}  // namespace joymouse
