#include "Log.h"

#include <atomic>
#include <cstdarg>
#include <cstdio>

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace joymouse {

namespace {
std::atomic<bool> gDebug{false};
}  // namespace

void setDebugLogging(bool enabled) {
    gDebug = enabled;
}

bool debugLogging() {
    return gDebug;
}

void logPrint(LogLevel level, const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
#ifdef __ANDROID__
    int prio = ANDROID_LOG_INFO;
    switch (level) {
        case LogLevel::Debug: prio = ANDROID_LOG_DEBUG; break;
        case LogLevel::Info: prio = ANDROID_LOG_INFO; break;
        case LogLevel::Warn: prio = ANDROID_LOG_WARN; break;
        case LogLevel::Error: prio = ANDROID_LOG_ERROR; break;
    }
    __android_log_vprint(prio, "joyMouse", fmt, args);
#else
    static const char kLetters[] = "DIWE";
    fprintf(stderr, "%c joyMouse: ", kLetters[static_cast<int>(level)]);
    vfprintf(stderr, fmt, args);
    fputc('\n', stderr);
#endif
    va_end(args);
}

}  // namespace joymouse
