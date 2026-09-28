#include "SharedPad.h"

namespace joymouse {

SharedPad::SharedPad(PadPort& real) : real_(real) {
    for (Client& c : clients_) c.owner = this;
}

bool SharedPad::applyGrab() {
    bool want = false;
    for (const Client& c : clients_) want = want || c.wantsGrab;
    if (want == realGrabbed_) return true;
    if (!real_.setGrabbed(want)) return false;
    realGrabbed_ = want;
    return true;
}

void SharedPad::applyAbs() {
    bool want = false;
    for (const Client& c : clients_) want = want || c.wantsAbs;
    if (want == realAbs_) return;
    realAbs_ = want;
    real_.setAbsReporting(want);
}

bool SharedPad::Client::setGrabbed(bool grabbed) {
    const bool before = wantsGrab;
    wantsGrab = grabbed;
    if (owner->applyGrab()) return true;
    wantsGrab = before;
    return false;
}

void SharedPad::Client::setAbsReporting(bool enabled) {
    wantsAbs = enabled;
    owner->applyAbs();
}

bool SharedPad::Client::readState(KeyBits* keys, AbsValues* abs) {
    return owner->real_.readState(keys, abs);
}

}  // namespace joymouse
