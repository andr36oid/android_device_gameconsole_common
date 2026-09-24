#include "Properties.h"

#include <sys/eventfd.h>
#include <unistd.h>

#include <cctype>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <thread>

#ifdef __ANDROID__
#include <sys/system_properties.h>
#endif

#include "Log.h"

namespace joymouse {

#ifdef __ANDROID__

std::string getProperty(const char* name) {
    char value[PROP_VALUE_MAX] = {};
    __system_property_get(name, value);
    return value;
}

bool setProperty(const char* name, const std::string& value) {
    return __system_property_set(name, value.c_str()) == 0;
}

PropertyWatcher::PropertyWatcher() {
    fd_ = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    if (fd_ < 0) {
        LOGW("eventfd failed, settings changes need a restart: %s", strerror(errno));
        return;
    }
    const int fd = fd_;
    // Waits on the global property serial, which changes with every property
    // update, and pokes the main loop. The thread lives as long as the process.
    std::thread([fd] {
        uint32_t serial = 0;
        __system_property_wait(nullptr, 0, &serial, nullptr);
        while (__system_property_wait(nullptr, serial, &serial, nullptr)) {
            const uint64_t one = 1;
            if (write(fd, &one, sizeof(one)) < 0 && errno != EAGAIN) break;
        }
    }).detach();
}

#else

namespace {

std::string environmentName(const char* name) {
    static const char* const kPrefixes[] = {"persist.sys.joymouse.", "sys.joymouse."};
    std::string rest = name;
    for (const char* prefix : kPrefixes) {
        if (rest.rfind(prefix, 0) == 0) {
            rest = "JOYMOUSE_" + rest.substr(std::strlen(prefix));
            break;
        }
    }
    for (char& c : rest) c = c == '.' ? '_' : static_cast<char>(std::toupper(static_cast<unsigned char>(c)));
    return rest;
}

}  // namespace

std::string getProperty(const char* name) {
    const char* value = std::getenv(environmentName(name).c_str());
    return value != nullptr ? value : "";
}

bool setProperty(const char* name, const std::string& value) {
    return setenv(environmentName(name).c_str(), value.c_str(), 1) == 0;
}

PropertyWatcher::PropertyWatcher() = default;

#endif

PropertyWatcher::~PropertyWatcher() {
    // The watcher thread may still write to the eventfd; keep it open until exit.
}

void PropertyWatcher::drain() {
    uint64_t value;
    while (fd_ >= 0 && read(fd_, &value, sizeof(value)) > 0) {
    }
}

}  // namespace joymouse
