#pragma once

#include "Controller.h"
#include <deque>
#include <functional>
#include <map>

namespace controller {
struct FlipperFrame { uint8_t type = 0, endpoint = 0; Bytes data; };
struct FlipperEndpoint { unsigned address, packet, kind; };
struct FlipperPort { unsigned control, data, notification, output, input; };
struct FlipperManifest {
    Bytes device, configuration;
    std::map<unsigned,Bytes> strings;
    std::vector<FlipperPort> ports;
    std::vector<FlipperEndpoint> endpoints;
    bool parse(const Bytes& payload);
    Bytes descriptors() const;
    Bytes labels() const;
    std::string text(unsigned index) const;
};
Bytes flipperWire(const FlipperFrame& frame);
// A stream has one partial frame, sixteen retained endpoint packets and sixteen
// replies. Endpoint IO is asynchronous; a slow PC never blocks lease checks.
class FlipperChannel {
public:
    explicit FlipperChannel(std::function<bool(int)> authorize) : authorize_(std::move(authorize)) { }
    ~FlipperChannel();
    bool listen(const char* name = "andr36oid-flipper-usb-v1");
    bool pump(int64_t time);
    bool reply(FlipperFrame frame);
    bool control(const Bytes& setup, int64_t time);
    bool controlPending() const { return controlDeadline_ != 0; }
    bool configured() const { return configured_; }
    bool accepted() const { return hello_; }
    FlipperManifest manifest;
    std::deque<FlipperFrame> input;
    std::deque<FlipperFrame> results;
private:
    bool frame(FlipperFrame frame, int64_t time);
    int server_ = -1, client_ = -1;
    bool hello_ = false, configured_ = false;
    int64_t deadline_ = 0, controlDeadline_ = 0;
    Bytes receive_;
    size_t wanted_ = 12, sent_ = 0;
    std::deque<Bytes> replies_;
    Bytes setup_;
    std::function<bool(int)> authorize_;
};
}
