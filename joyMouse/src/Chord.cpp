#include "Chord.h"

#include <utility>

namespace joymouse {

void Chord::configure(std::vector<Button> members, Nanos holdTime) {
    members_ = std::move(members);
    memberMask_.fill(false);
    for (Button b : members_) memberMask_[index(b)] = true;
    holdTime_ = holdTime;
    engaged_ = false;
    latched_ = false;
}

void Chord::update(const std::array<bool, kButtonCount>& buttonsDown, int otherKeys, Nanos now) {
    if (!enabled()) return;
    bool all = true;
    bool any = false;
    for (Button b : members_) {
        all = all && buttonsDown[index(b)];
        any = any || buttonsDown[index(b)];
    }
    const bool engaged = all && otherKeys == 0;
    if (engaged && !engaged_) since_ = now;
    engaged_ = engaged;
    if (!any) latched_ = false;
}

bool Chord::fire(Nanos now) {
    if (!armed() || now < since_ + holdTime_) return false;
    latched_ = true;
    return true;
}

void Chord::veto() {
    if (engaged_) latched_ = true;
}

std::optional<Nanos> Chord::deadline() const {
    if (!armed()) return std::nullopt;
    return since_ + holdTime_;
}

}  // namespace joymouse
