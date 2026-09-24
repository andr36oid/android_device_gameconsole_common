#pragma once

#include <cstdint>
#include <string>
#include <utility>
#include <vector>

#include "Controller.h"
#include "EvdevPad.h"

namespace joymouse {

// phys prefix of every device joyMouse creates, so it never picks them up as a pad.
constexpr const char kOwnPhysPrefix[] = "joymouse/";

// A device created through /dev/uinput. It exists between open() and close().
class UinputDevice : public OutputPort {
public:
    ~UinputDevice() override;

    bool open() override;
    void close() override;
    bool isOpen() const override { return fd_ >= 0; }
    bool send(const std::vector<InputEvent>& events) override;

protected:
    struct Setup {
        std::string name;
        std::string phys;
        input_id id{};
        std::vector<uint16_t> keys;
        std::vector<uint16_t> rels;
        std::vector<std::pair<uint16_t, input_absinfo>> abs;
        std::vector<uint16_t> msc;
        std::vector<uint16_t> props;
    };

    virtual Setup setup() const = 0;

private:
    int fd_ = -1;
    std::string name_;
    bool writeFailed_ = false;
};

// The mouse Android sees while mouse mode is on.
class VirtualMouse : public UinputDevice {
protected:
    Setup setup() const override;
};

// A copy of the physical pad (same name and IDs, so Android applies the same
// key layout) that carries every input mouse mode leaves alone.
class VirtualPad : public UinputDevice {
public:
    VirtualPad(PadInfo info, PadExtras extras) : info_(std::move(info)), extras_(std::move(extras)) {}

protected:
    Setup setup() const override;

private:
    PadInfo info_;
    PadExtras extras_;
};

}  // namespace joymouse
