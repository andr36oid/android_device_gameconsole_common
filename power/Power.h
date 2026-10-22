#pragma once

#include <aidl/android/hardware/power/BnPower.h>

#include <chrono>
#include <condition_variable>
#include <mutex>
#include <string>
#include <thread>

namespace aidl {
namespace android {
namespace hardware {
namespace power {
namespace impl {
namespace andr36oid {

/**
 * Button presses (Boost::INTERACTION) and app starts (Mode::LAUNCH) raise the CPU's lowest
 * clock to its top clock for a moment; schedutil alone takes a few frames to climb from idle.
 * Whatever lowest clock was set before (CpuOverclock's per-app profiles write it too) comes
 * back afterwards, unless someone changed it meanwhile.
 */
class Power : public BnPower {
  public:
    Power();
    ndk::ScopedAStatus setMode(Mode type, bool enabled) override;
    ndk::ScopedAStatus isModeSupported(Mode type, bool* _aidl_return) override;
    ndk::ScopedAStatus setBoost(Boost type, int32_t durationMs) override;
    ndk::ScopedAStatus isBoostSupported(Boost type, bool* _aidl_return) override;

  private:
    using Clock = std::chrono::steady_clock;
    void boostUntil(Clock::time_point end);
    static std::chrono::milliseconds interaction();
    void run();

    std::mutex mLock;
    std::condition_variable mWake;
    Clock::time_point mEnd;
    // The lowest clock from before the boost, empty while not boosting
    std::string mSavedMin;
    // What the boost wrote
    std::string mBoosted;
    std::thread mThread;
};

}  // namespace andr36oid
}  // namespace impl
}  // namespace power
}  // namespace hardware
}  // namespace android
}  // namespace aidl
