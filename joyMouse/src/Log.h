#pragma once

namespace joymouse {

enum class LogLevel { Debug, Info, Warn, Error };

void setDebugLogging(bool enabled);
bool debugLogging();
void logPrint(LogLevel level, const char* fmt, ...) __attribute__((format(printf, 2, 3)));

}  // namespace joymouse

#define LOGD(...)                                                           \
    do {                                                                    \
        if (::joymouse::debugLogging())                                     \
            ::joymouse::logPrint(::joymouse::LogLevel::Debug, __VA_ARGS__); \
    } while (0)
#define LOGI(...) ::joymouse::logPrint(::joymouse::LogLevel::Info, __VA_ARGS__)
#define LOGW(...) ::joymouse::logPrint(::joymouse::LogLevel::Warn, __VA_ARGS__)
#define LOGE(...) ::joymouse::logPrint(::joymouse::LogLevel::Error, __VA_ARGS__)
