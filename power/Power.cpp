#include "Power.h"

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/parseint.h>
#include <android-base/properties.h>
#include <android-base/strings.h>

namespace aidl {
namespace android {
namespace hardware {
namespace power {
namespace impl {
namespace andr36oid {

namespace {

const char kMinFreq[] = "/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq";
// The top clock allowed right now: CpuOverclock's limit or overclock, thermal throttling
const char kMaxFreq[] = "/sys/devices/system/cpu/cpufreq/policy0/scaling_max_freq";
constexpr auto kInteraction = std::chrono::milliseconds(1000);
// An app start that never reports its end still lets go after this
constexpr auto kLaunchLimit = std::chrono::milliseconds(5000);

std::string readFreq(const char* path) {
    std::string value;
    if (!::android::base::ReadFileToString(path, &value)) return "";
    return ::android::base::Trim(value);
}

}  // namespace

Power::Power() : mThread(&Power::run, this) {
    mThread.detach();
}

void Power::boostUntil(Clock::time_point end) {
    // Off with persist.andr36oid.input_boost=0, to compare
    if (!::android::base::GetBoolProperty("persist.andr36oid.input_boost", true)) return;
    std::lock_guard<std::mutex> lock(mLock);
    if (mSavedMin.empty()) {
        const std::string current = readFreq(kMinFreq);
        const std::string top = readFreq(kMaxFreq);
        long khz, topKhz;
        // Already at the top (a profile, or performance mode): nothing to do
        if (!::android::base::ParseInt(current, &khz) ||
            !::android::base::ParseInt(top, &topKhz) || khz >= topKhz) {
            return;
        }
        if (!::android::base::WriteStringToFile(top, kMinFreq)) return;
        mSavedMin = current;
        mBoosted = top;
    }
    if (end > mEnd) mEnd = end;
    mWake.notify_one();
}

void Power::run() {
    std::unique_lock<std::mutex> lock(mLock);
    for (;;) {
        if (mSavedMin.empty()) {
            mWake.wait(lock);
            continue;
        }
        if (Clock::now() < mEnd) {
            mWake.wait_until(lock, mEnd);
            continue;
        }
        // Put the old lowest clock back, unless someone else set one meanwhile. Throttling
        // that lowered the top clock pulls the lowest one down with it: still ours.
        const std::string current = readFreq(kMinFreq);
        if (current == mBoosted || current == readFreq(kMaxFreq)) {
            ::android::base::WriteStringToFile(mSavedMin, kMinFreq);
        }
        mSavedMin.clear();
    }
}

ndk::ScopedAStatus Power::setMode(Mode type, bool enabled) {
    if (type == Mode::LAUNCH && enabled) {
        boostUntil(Clock::now() + kLaunchLimit);
    } else if (type == Mode::LAUNCH) {
        // The app is up: the boost runs out a second later, like after a button press
        std::lock_guard<std::mutex> lock(mLock);
        const auto end = Clock::now() + kInteraction;
        if (end < mEnd) mEnd = end;
        mWake.notify_one();
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Power::isModeSupported(Mode type, bool* _aidl_return) {
    *_aidl_return = type == Mode::LAUNCH;
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Power::setBoost(Boost type, int32_t durationMs) {
    if (type == Boost::INTERACTION) {
        boostUntil(Clock::now() + (durationMs > 0 ? std::chrono::milliseconds(durationMs)
                                                  : kInteraction));
    }
    return ndk::ScopedAStatus::ok();
}

ndk::ScopedAStatus Power::isBoostSupported(Boost type, bool* _aidl_return) {
    *_aidl_return = type == Boost::INTERACTION;
    return ndk::ScopedAStatus::ok();
}

}  // namespace andr36oid
}  // namespace impl
}  // namespace power
}  // namespace hardware
}  // namespace android
}  // namespace aidl
