#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "Controller.h"

namespace joymouse {

// Capabilities of the pad that only matter for cloning it.
struct PadExtras {
    std::string phys;
    std::vector<uint16_t> msc;
    std::vector<uint16_t> props;
};

// The physical game pad, read through its evdev node.
class EvdevPad : public PadPort {
public:
    enum class ReadResult { Ok, Gone };

    ~EvdevPad() override;

    // Picks the built-in pad among /dev/input/event*. `hint` (a node path or a
    // name substring) overrides the automatic choice.
    static std::unique_ptr<EvdevPad> find(const std::string& hint);

    int fd() const { return fd_; }
    const std::string& path() const { return path_; }
    const PadInfo& info() const { return info_; }
    const PadExtras& extras() const { return extras_; }

    // Reads everything available without blocking.
    ReadResult read(std::vector<InputEvent>* out);

    bool setGrabbed(bool grabbed) override;
    void setAbsReporting(bool enabled) override;
    bool readState(KeyBits* keys, AbsValues* abs) override;

private:
    EvdevPad(int fd, std::string path, PadInfo info, PadExtras extras);

    int fd_;
    std::string path_;
    PadInfo info_;
    PadExtras extras_;
    bool grabbed_ = false;
};

}  // namespace joymouse
