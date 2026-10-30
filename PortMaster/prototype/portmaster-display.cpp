// SPDX-License-Identifier: MIT
// portmaster-display off|on
//
// Turns the internal panel's power mode off or on through SurfaceFlinger,
// the same path the framework uses when the screen blanks. This disables the
// composer's scanout planes cleanly while the SoC stays awake: it is NOT a
// system sleep (PowerManager wakelocks are untouched), so it does not trip the
// clone deep-sleep freeze that KEYCODE_SLEEP does.
//
// It exists to make the PortMaster display handoff safe. On Linux 4.19,
// drm_release() flushes an rmfb work item for any framebuffer still bound to an
// active plane when the owning fd closes. If we stop the composer while it is
// still scanning out, that flush blocks uninterruptibly on a flip that never
// completes, and every later open("/dev/dri/card0") wedges on drm_global_mutex.
// Blanking first leaves no active FB, so the composer's fd closes without the
// rmfb work and card0 is free to hand over.
//
// SurfaceFlinger must be running when this is called (power off is issued
// before SF is stopped; on restart SF restores the display to ON itself).

#include <gui/SurfaceComposerClient.h>
#include <binder/ProcessState.h>
#include <utils/StrongPointer.h>

#include <cstdio>
#include <cstring>

using android::IBinder;
using android::sp;
using android::SurfaceComposerClient;

// hardware::graphics::composer V2.1 IComposerClient.PowerMode
static const int kPowerModeOff = 0;
static const int kPowerModeOn = 2;

int main(int argc, char** argv) {
    if (argc != 2 || (std::strcmp(argv[1], "off") && std::strcmp(argv[1], "on"))) {
        std::fprintf(stderr, "Usage: %s off|on\n", argv[0]);
        return 2;
    }
    const bool off = std::strcmp(argv[1], "off") == 0;
    const int mode = off ? kPowerModeOff : kPowerModeOn;

    // A binder thread pool is not needed for a single synchronous transaction,
    // but the process must be a binder client to reach SurfaceFlinger.
    android::ProcessState::self()->setThreadPoolMaxThreadCount(0);

    const sp<IBinder> display = SurfaceComposerClient::getInternalDisplayToken();
    if (display == nullptr) {
        std::fprintf(stderr, "FAIL no internal display token (is SurfaceFlinger running?)\n");
        return 1;
    }

    SurfaceComposerClient::setDisplayPowerMode(display, mode);
    std::printf("DISPLAY power=%s mode=%d\n", off ? "off" : "on", mode);
    return 0;
}
