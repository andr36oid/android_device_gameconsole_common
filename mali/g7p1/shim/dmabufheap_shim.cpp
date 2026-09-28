/*
 * The Rockchip Android 12 Mali driver (g7p1) links two things that Android 11
 * does not have:
 *
 * - libdmabufheap.so. The driver uses it in one place only, to allocate
 *   protected (secure) memory from the "protected" heap. This kernel has no
 *   such heap, so the allocator is reported as unavailable, which is what the
 *   driver sees on a device without one.
 * - AHardwareBuffer_getId() from libnativewindow (API 31). The Vulkan driver
 *   calls it for AHardwareBuffer backed memory. The driver finds it here
 *   because this library is one of its own dependencies.
 */
#include <errno.h>
#include <stddef.h>
#include <stdint.h>

extern "C" {

struct AHardwareBuffer;
struct BufferAllocator;

BufferAllocator* CreateDmabufHeapBufferAllocator() {
    return nullptr;
}

void FreeDmabufHeapBufferAllocator(BufferAllocator*) {}

int MapDmabufHeapNameToIonHeap(BufferAllocator*, const char*, const char*, unsigned int,
                               unsigned int, unsigned int) {
    return -ENODEV;
}

int DmabufHeapAlloc(BufferAllocator*, const char*, size_t, unsigned int, size_t) {
    return -ENODEV;
}

/*
 * Android 12 does GraphicBuffer::fromAHardwareBuffer(buffer)->getId(): an
 * AHardwareBuffer pointer is the GraphicBuffer itself and the id is its mId
 * member. libui is not reachable from a same-process HAL, so read mId at its
 * offset in Android 11's GraphicBuffer (frameworks/native/libs/ui/include/ui/
 * GraphicBuffer.h). The offsets come from compiling getId() against those
 * headers with the platform clang: 216 on arm64, 128 on arm.
 */
#if defined(__LP64__)
static constexpr size_t kGraphicBufferIdOffset = 216;
#else
static constexpr size_t kGraphicBufferIdOffset = 128;
#endif

int AHardwareBuffer_getId(const AHardwareBuffer* buffer, uint64_t* outId) {
    if (buffer == nullptr || outId == nullptr) return -EINVAL;
    *outId = *reinterpret_cast<const uint64_t*>(reinterpret_cast<const char*>(buffer) +
                                                kGraphicBufferIdOffset);
    return 0;
}

}  // extern "C"
