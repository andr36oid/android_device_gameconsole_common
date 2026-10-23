#pragma once

#include "Controller.h"
#include "../../joyMouse/src/PointerModel.h"
#include "../../joyMouse/src/ScrollModel.h"
#include <deque>
#include <string>

namespace controller {

uint8_t mouseButtons(const State& state);

constexpr const char kKeyboardSocket[] = "andr36oid-controller-keyboard";
using KeyboardPacket = std::array<uint8_t, 16>;

class KeyboardPointer {
public:
    KeyboardPointer();
    std::array<uint8_t, 5> report(const State& state, uint8_t buttons, bool scroll,
                                  int wheel, double seconds);
private:
    joymouse::PointerModel pointer_;
    joymouse::ScrollModel scroll_;
};

// The grabbed pad stays private to the daemon. Only the leased app can reply.
class KeyboardChannel {
public:
    KeyboardChannel() = default;
    KeyboardChannel(const KeyboardChannel&) = delete;
    KeyboardChannel& operator=(const KeyboardChannel&) = delete;
    ~KeyboardChannel();
    bool listen(const char* name = kKeyboardSocket);
    bool pump(const State& state, uint8_t leds, bool connected, int64_t time,
              const std::string& owner, const std::string& start, bool abort = false);
    void release();
    bool menu() const { return menu_ || menuWait_; }
    bool active() const { return client_ >= 0 && received_; }
    uint8_t buttons() const { return buttons_; }
    bool scrolling() const { return scrolling_; }
    int wheel() const { return wheel_; }
    const std::deque<Bytes>& pending() const { return pending_; }
    void sent() { pending_.pop_front(); }
    const Bytes* next(uint8_t tag) const {
        for (const auto& report : pending_) if (report[0] == tag) return &report;
        return nullptr;
    }
    void sent(uint8_t tag) {
        for (auto it = pending_.begin(); it != pending_.end(); ++it) if ((*it)[0] == tag) {
            pending_.erase(it); return;
        }
    }
private:
    int server_ = -1, client_ = -1;
    int64_t lastReply_ = 0, lastFrame_ = -1;
    bool menu_ = false, menuWait_ = false, received_ = false, connected_ = false, scrolling_ = false;
    uint8_t buttons_ = 0, consumer_ = 0;
    int wheel_ = 0;
    std::array<uint8_t, 9> keys_{1};
    std::deque<Bytes> pending_;
};

}  // namespace controller
