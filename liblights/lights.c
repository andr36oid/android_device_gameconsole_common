/*
 * Copyright (C) 2012 Stefan Seidel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "lights"

#include <log/log.h>

#include <sys/types.h>
#include <dirent.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>

#include <hardware/lights.h>
#include <cutils/properties.h>

/******************************************************************************/

static pthread_once_t g_init = PTHREAD_ONCE_INIT;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static int max_brightness = 255;
static char brightness_file[PROPERTY_VALUE_MAX] = { '\0' };

char const*const LLP_BRIGHTNESS_FILE     = "backlight.brightness_file";
char const*const LLP_MAX_BRIGHTNESS_FILE = "backlight.max_brightness_file";
char const*const LLP_MAX_BRIGHTNESS      = "backlight.max_brightness";

void init_globals(void)
{
    pthread_mutex_init(&g_lock, NULL);
}

static int write_int(char* path, int value)
{
    int fd;
    static int already_warned = 0;

    fd = open(path, O_RDWR);
    if (fd >= 0) {
        char buffer[20];
        int bytes = sprintf(buffer, "%d\n", value);
        int amt = write(fd, buffer, bytes);
        close(fd);
        return amt == -1 ? -errno : 0;
    } else {
        if (already_warned == 0) {
            ALOGE("write_int failed to open %s\n", path);
            already_warned = 1;
        }
        return -errno;
    }
}

static int read_int(char* path)
{
    int fd;

    fd = open(path, O_RDONLY);
    if (fd >= 0) {
        char buffer[20];
        int amt = read(fd, buffer, 20);
        close(fd);
        if (amt <= 0) return -errno;
        int ret = -1;
        amt = sscanf(buffer, "%d", &ret);
        return amt == -1 ? -errno : ret;
    }
    return -errno;
}

static int rgb_to_brightness16(struct light_state_t const* state)
{
    int color = state->color & 0x00ffffff;
    return ((77*((color>>16)&0x00ff)) + (150*((color>>8)&0x00ff)) + (29*(color&0x00ff)));
}

static int set_light_backlight(struct light_device_t* dev __attribute__ ((__unused__)), struct light_state_t const* state)
{
    int err = 0;
    int brightness = rgb_to_brightness16(state);

    if (max_brightness < 65536)
        brightness = brightness / (65536 / (max_brightness + 1));
    else
        brightness = brightness * (max_brightness / 65536);

    pthread_mutex_lock(&g_lock);
    err = write_int(brightness_file, (brightness));
    pthread_mutex_unlock(&g_lock);

    return err;
}

/*
 * Notification light: the status LED driver (andr36oid-status-led) blinks a
 * colour by name, so pick the nearest colour this console's LED can show.
 * Lineage dims a colour by scaling it, so compare hues at full brightness.
 * The driver's first colour is the LED at rest ("off", or green on the
 * genuine R36S, whose green LED is always on), which can't show anything.
 */
#define STATUS_LED "/sys/devices/platform/andr36oid-status-led/"

static const struct {
    const char *name;
    int r, g, b;
} led_colors[] = {
    { "red", 255, 0, 0 },
    { "green", 0, 255, 0 },
    { "blue", 0, 0, 255 },
    { "purple", 160, 0, 255 },
    { "pink", 255, 50, 160 },
    { "orange", 255, 128, 0 },
    { "yellow", 255, 255, 0 },
    { "cyan", 0, 255, 255 },
    { "white", 255, 255, 255 },
};

static int write_str(const char *path, const char *value)
{
    int fd = open(path, O_WRONLY);
    int ret;

    if (fd < 0)
        return -errno;
    ret = write(fd, value, strlen(value)) < 0 ? -errno : 0;
    close(fd);
    return ret;
}

/* the colour name of this LED nearest to rgb, or NULL if it has none */
static const char *nearest_led_color(unsigned int rgb, char *names, size_t len)
{
    int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
    int max = r > g ? (r > b ? r : b) : (g > b ? g : b);
    const char *best = NULL;
    long best_dist = 0;
    char *save = NULL, *name;
    int fd = open(STATUS_LED "colors", O_RDONLY);
    ssize_t n;
    size_t i;

    if (fd < 0)
        return NULL;
    n = read(fd, names, len - 1);
    close(fd);
    if (n <= 0)
        return NULL;
    names[n] = '\0';
    if (max == 0)
        return NULL;
    r = r * 255 / max;
    g = g * 255 / max;
    b = b * 255 / max;

    if (!strtok_r(names, " \n", &save))
        return NULL;
    for (name = strtok_r(NULL, " \n", &save); name; name = strtok_r(NULL, " \n", &save)) {
        for (i = 0; i < sizeof(led_colors) / sizeof(led_colors[0]); i++) {
            long dr, dg, db, dist;

            if (strcmp(led_colors[i].name, name))
                continue;
            dr = r - led_colors[i].r;
            dg = g - led_colors[i].g;
            db = b - led_colors[i].b;
            dist = dr * dr + dg * dg + db * db;
            if (!best || dist < best_dist) {
                best = name;
                best_dist = dist;
            }
        }
    }
    return best;
}

static int set_light_notifications(struct light_device_t* dev __attribute__ ((__unused__)),
        struct light_state_t const* state)
{
    char names[128], buf[64];
    const char *color = NULL;
    int on = 0, off = 0;

    if (state->color & 0x00ffffff)
        color = nearest_led_color(state->color & 0x00ffffff, names, sizeof(names));
    if (color && state->flashMode != LIGHT_FLASH_NONE && state->flashOnMS > 0) {
        on = state->flashOnMS < 50 ? 50 : state->flashOnMS;
        off = state->flashOffMS < 50 ? 50 : state->flashOffMS;
    } else if (color) {
        on = 1000;
    }
    if (on > 60000)
        on = 60000;
    if (off > 60000)
        off = 60000;
    if (color)
        snprintf(buf, sizeof(buf), "%s %d %d\n", color, on, off);
    else
        snprintf(buf, sizeof(buf), "none\n");

    pthread_mutex_lock(&g_lock);
    int err = write_str(STATUS_LED "notify", buf);
    pthread_mutex_unlock(&g_lock);
    return err;
}

static int close_lights(struct light_device_t *dev)
{
    free(dev);
    return 0;
}

static int check_backlight_file(char const* name, char* path)
{
    int ret;
    if (access(name, F_OK)) {
        ret = 0;
    } else {
        ALOGD("Detect %s", name);
        strcpy(path, name);
        ret = 1; // found
    }
    return ret;
}

static int find_backlight_file(char const* file, char* path)
{
    int ret = 0;
    size_t i;
    DIR* dir;
    char name[PATH_MAX];
    const char* dirname = "/sys/class/backlight";
    const char* dirs[] = {
        "backlight",
        "pwm-backlight",
        "pwm_backlight",
    };

    // Check some known dirs
    for (i = 0; i < sizeof(dirs) / sizeof(const char*); ++i) {
        snprintf(name, PATH_MAX, "%s/%s/%s", dirname, dirs[i], file);
        if (check_backlight_file(name, path)) {
            ALOGV("Using device %s", name);
            return 1;
        }
    }

    if ((dir = opendir(dirname))) {
        struct dirent* de;
        while ((de = readdir(dir))) {
            if (de->d_name[0] != '.') {
                snprintf(name, PATH_MAX, "%s/%s/%s", dirname, de->d_name, file);
                if ((ret = check_backlight_file(name, path))) {
                    break;
                }
            }
        }
        closedir(dir);
    }
    return ret;
}

static int open_lights(const struct hw_module_t* module, char const* name, struct hw_device_t** device)
{
    int (*set_light)(struct light_device_t* dev,
            struct light_state_t const* state);

    if (0 == strcmp(LIGHT_ID_BACKLIGHT, name)) {
        set_light = set_light_backlight;
        char max_b_file[PROPERTY_VALUE_MAX] = { '\0' };
        if (property_get(LLP_MAX_BRIGHTNESS, max_b_file, NULL)) {
            if (!sscanf(max_b_file, "%d", &max_brightness)) {
                ALOGE("%s system property is set to '%s', this could not be parsed as an integer!", LLP_MAX_BRIGHTNESS, max_b_file);
                return -EINVAL;
            }
        } else {
            if (property_get(LLP_MAX_BRIGHTNESS_FILE, max_b_file, NULL) ||
                    find_backlight_file("max_brightness", max_b_file)) {
                max_brightness = read_int(max_b_file);
            } else {
                ALOGE("Unable to detect max_brightness. Try to set %s", LLP_MAX_BRIGHTNESS_FILE);
                return -EINVAL;
            }
        }
        ALOGV("Read max display brightness of %d", max_brightness);
        if (max_brightness < 1) {
            max_brightness = 255;
        }
        if (!property_get(LLP_BRIGHTNESS_FILE, brightness_file, NULL) &&
                !find_backlight_file("brightness", brightness_file)) {
            ALOGE("%s system property not set", LLP_BRIGHTNESS_FILE);
            return -EINVAL;
        }
    } else if (0 == strcmp(LIGHT_ID_NOTIFICATIONS, name) &&
            !access(STATUS_LED "notify", W_OK)) {
        set_light = set_light_notifications;
    } else {
        return -EINVAL;
    }

    pthread_once(&g_init, init_globals);

    struct light_device_t *dev = malloc(sizeof(struct light_device_t));
    memset(dev, 0, sizeof(*dev));

    dev->common.tag = HARDWARE_DEVICE_TAG;
    dev->common.close = (int (*)(struct hw_device_t*))close_lights;
    dev->common.module = (struct hw_module_t*)module;
    dev->common.version = 0;
    dev->set_light = set_light;

    *device = (struct hw_device_t*)dev;
    return 0;
}


static struct hw_module_methods_t lights_module_methods = {
    .open = open_lights,
};

struct hw_module_t HAL_MODULE_INFO_SYM = {
    .tag = HARDWARE_MODULE_TAG,
    .version_major = 1,
    .version_minor = 0,
    .id = LIGHTS_HARDWARE_MODULE_ID,
    .name = "Generic sysfs liblights implementation",
    .author = "Stefan Seidel",
    .methods = &lights_module_methods,
};
