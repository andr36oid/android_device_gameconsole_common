#pragma once

#include <cstdint>
#include <ctime>

namespace joymouse {

// Monotonic time in nanoseconds.
using Nanos = int64_t;

constexpr Nanos kNanosPerMs = 1000000;
constexpr Nanos kNanosPerSec = 1000000000;

constexpr Nanos msToNanos(int64_t ms) {
    return ms * kNanosPerMs;
}

constexpr double nanosToSec(Nanos n) {
    return static_cast<double>(n) / static_cast<double>(kNanosPerSec);
}

inline Nanos monotonicNow() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<Nanos>(ts.tv_sec) * kNanosPerSec + ts.tv_nsec;
}

}  // namespace joymouse
