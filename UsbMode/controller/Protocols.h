#pragma once

#include "Controller.h"
#include <deque>

namespace controller {
Bytes switchDescriptor();
Bytes ps3Descriptor();
Bytes cubeDescriptor();
Bytes mediaDescriptor();
Bytes padReport(unsigned function, const State& state);
Bytes functionDescriptors(unsigned function);
class PadControl {
public:
    ControlReply setup(unsigned function, uint8_t type, uint8_t request, uint16_t value,
                       uint16_t index, uint16_t length, const State& state);
    void received(unsigned function, uint16_t value, const Bytes& data);
    void output(unsigned function, const uint8_t* data, size_t size);
    void identity(const std::string& serial);
    void reset() { cubeReady_ = false; }
    bool ready(unsigned function) const { return function != GameCube || cubeReady_; }
private:
    bool cubeReady_ = false;
    uint8_t calibration_ = 0xa0;
    std::array<uint8_t, 6> host_{}, address_{2,0x36,0,0,0,1};
};
std::array<Bytes, 2> mediaReports(const State& state);
class MidiPads {
public:
    void update(const State& state);
    void release();
    const std::deque<Bytes>& pending() const { return pending_; }
    void sent() { pending_.pop_front(); }
    int octave() const { return octave_; }
    unsigned pads() const { return pads_; }
private:
    std::deque<Bytes> pending_;
    unsigned pads_ = 0;
    int octave_ = 0, bend_ = -1, mod_ = -1;
    bool down_ = false, up_ = false;
    std::array<uint8_t, 8> notes_{};
};
std::array<uint8_t, 4> midiEvent(const Bytes& message);
}
