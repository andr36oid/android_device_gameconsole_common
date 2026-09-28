#pragma once

#include <array>

#include "Controller.h"

namespace joymouse {

// Lets mouse mode and touch mode share the one physical pad. Each gets its own
// PadPort; the pad is grabbed while either wants it grabbed, and stick events
// are reported while either needs them. evdev only allows one grab, so without
// this the second mode to grab would fail.
class SharedPad {
public:
    static constexpr int kClients = 2;

    explicit SharedPad(PadPort& real);
    SharedPad(const SharedPad&) = delete;
    SharedPad& operator=(const SharedPad&) = delete;

    PadPort& client(int i) { return clients_[static_cast<size_t>(i)]; }
    bool grabbed() const { return realGrabbed_; }

private:
    class Client : public PadPort {
    public:
        bool setGrabbed(bool grabbed) override;
        void setAbsReporting(bool enabled) override;
        bool readState(KeyBits* keys, AbsValues* abs) override;

        SharedPad* owner = nullptr;
        bool wantsGrab = false;
        bool wantsAbs = true;
    };

    bool applyGrab();
    void applyAbs();

    PadPort& real_;
    std::array<Client, kClients> clients_;
    bool realGrabbed_ = false;
    bool realAbs_ = true;
};

}  // namespace joymouse
