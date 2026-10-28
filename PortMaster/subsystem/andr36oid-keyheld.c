// Snapshot the physical START key at boot. No event reads, waits or EVIOCGRAB.
#include <fcntl.h>
#include <glob.h>
#include <limits.h>
#include <linux/input.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define KEY_BYTES ((KEY_MAX + 8) / 8)

static int bit(const unsigned char *bits, unsigned int code) {
    return (bits[code / 8] >> (code % 8)) & 1;
}

static int start_held(const char *path) {
    char name[256] = {0};
    char sysfs[PATH_MAX];
    char resolved[PATH_MAX];
    struct input_id id = {0};
    unsigned char supported[KEY_BYTES] = {0};
    unsigned char held[KEY_BYTES] = {0};
    unsigned int code = BTN_START;
    int fd = open(path, O_RDONLY | O_NONBLOCK | O_CLOEXEC);
    if (fd < 0) return 0;

    const char *event = strrchr(path, '/');
    snprintf(sysfs, sizeof(sysfs), "/sys/class/input/%s", event ? event + 1 : path);
    // joyMouse/ogage clones are virtual. Only a physical pad may select boot.
    if (realpath(sysfs, resolved) && strstr(resolved, "/virtual/")) goto done;
    if (ioctl(fd, EVIOCGID, &id) < 0 || id.bustype == BUS_VIRTUAL ||
        id.bustype == BUS_USB || id.bustype == BUS_BLUETOOTH) goto done;
    if (ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name) < 0) goto done;

    int console = (id.vendor == 0x484b && id.product == 0x1100) ||
                  strstr(name, "GO-Super Gamepad") || strstr(name, "odroidgo");
    int gpio = strstr(name, "gpio-keys") || strstr(name, "gpio_keys") ||
               strstr(name, "GPIO Keys") || strstr(name, "gpio keys");
    // gpio-keys uses the DT node's name on some boards, rather than its driver
    // name. Check compatible too, including every separately registered node.
    snprintf(sysfs, sizeof(sysfs), "/sys/class/input/%s/device/device/of_node/compatible",
             event ? event + 1 : path);
    int compatible_fd = open(sysfs, O_RDONLY | O_CLOEXEC);
    if (compatible_fd >= 0) {
        char compatible[256] = {0};
        ssize_t size = read(compatible_fd, compatible, sizeof(compatible) - 1);
        for (ssize_t i = 0; i < size; ++i) if (!compatible[i]) compatible[i] = ' ';
        if (strstr(compatible, "gpio-keys")) gpio = 1;
        close(compatible_fd);
    }
    if (!console && !gpio) goto done;
    if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(supported)), supported) < 0) goto done;

    // Vendor_484b_Product_1100.kl: 315 BUTTON_START, 172 HOME. Do not mistake
    // HOME/Fn for START. Scan every gpio-keys node; variants may use ENTER.
    if (!bit(supported, BTN_START)) {
        if (gpio && bit(supported, KEY_ENTER)) code = KEY_ENTER;
        else goto done;
    }
    // EVIOCGKEY is the live bitmap, independent of unread/old queued events.
    if (ioctl(fd, EVIOCGKEY(sizeof(held)), held) >= 0 && bit(held, code)) {
        fprintf(stderr, "START held: %s (%s), Linux keycode %u\n", path, name, code);
        close(fd);
        return 1;
    }
done:
    close(fd);
    return 0;
}

int main(void) {
    glob_t nodes = {0};
    int result = 1;
    if (glob("/dev/input/event*", 0, NULL, &nodes) == 0) {
        for (size_t i = 0; i < nodes.gl_pathc; ++i) {
            if (start_held(nodes.gl_pathv[i])) {
                result = 0;
                break;
            }
        }
    }
    globfree(&nodes);
    return result;
}
