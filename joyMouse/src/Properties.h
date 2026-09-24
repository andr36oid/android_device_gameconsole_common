#pragma once

#include <string>

namespace joymouse {

// System properties. Off Android they come from the environment instead, with
// the joymouse prefix replaced by JOYMOUSE_ (persist.sys.joymouse.speed ->
// JOYMOUSE_SPEED), which is handy for running the daemon on a desktop.
std::string getProperty(const char* name);
bool setProperty(const char* name, const std::string& value);

// Signals a file descriptor whenever any system property changes.
class PropertyWatcher {
public:
    PropertyWatcher();
    ~PropertyWatcher();
    PropertyWatcher(const PropertyWatcher&) = delete;
    PropertyWatcher& operator=(const PropertyWatcher&) = delete;

    // Readable after a change; -1 if watching is unsupported.
    int fd() const { return fd_; }
    void drain();

private:
    int fd_ = -1;
};

}  // namespace joymouse
