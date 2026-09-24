#pragma once

#include <array>
#include <optional>
#include <vector>

#include "Buttons.h"
#include "Time.h"

namespace joymouse {

// Detects a button combination held for a while (the mouse mode toggle).
//
// To keep games from flipping the mode by accident, the hold only counts while
// the members are the only keys held, and the owner can veto it (e.g. when a
// stick is being pushed at the same time). Once the chord fired or was vetoed,
// every member has to be released before it can count again.
class Chord {
public:
    void configure(std::vector<Button> members, Nanos holdTime);

    bool enabled() const { return !members_.empty(); }
    bool isMember(Button b) const { return memberMask_[index(b)]; }
    const std::vector<Button>& members() const { return members_; }
    Nanos holdTime() const { return holdTime_; }

    // buttonsDown: logical buttons held; otherKeys: held keys that are not members.
    void update(const std::array<bool, kButtonCount>& buttonsDown, int otherKeys, Nanos now);
    // True exactly once per completed hold.
    bool fire(Nanos now);
    // Discards the current hold.
    void veto();
    std::optional<Nanos> deadline() const;
    // All members (and nothing else) are held, whether or not it already fired.
    bool engaged() const { return engaged_; }
    // Engaged and still counting towards firing.
    bool armed() const { return engaged_ && !latched_; }

private:
    std::vector<Button> members_;
    std::array<bool, kButtonCount> memberMask_{};
    Nanos holdTime_ = 0;
    bool engaged_ = false;
    bool latched_ = false;
    Nanos since_ = 0;
};

}  // namespace joymouse
