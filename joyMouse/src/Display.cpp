#include "Display.h"

#include <dirent.h>

#include <cstdio>
#include <cstring>
#include <fstream>
#include <optional>
#include <string>

#include "Log.h"

namespace joymouse {

namespace {

std::string firstLine(const std::string& path) {
    std::ifstream in(path);
    std::string line;
    std::getline(in, line);
    return line;
}

// Accepts "640x480", "640x480p-60" and fbdev's "U:640x480p-60".
std::optional<std::pair<int, int>> parseMode(std::string text) {
    const size_t colon = text.find(':');
    if (colon != std::string::npos) text.erase(0, colon + 1);
    int w = 0;
    int h = 0;
    if (std::sscanf(text.c_str(), "%dx%d", &w, &h) != 2 || w <= 0 || h <= 0) return std::nullopt;
    return std::make_pair(w, h);
}

std::optional<std::pair<int, int>> fromDrm() {
    DIR* dir = opendir("/sys/class/drm");
    if (dir == nullptr) return std::nullopt;
    std::optional<std::pair<int, int>> size;
    while (const dirent* entry = readdir(dir)) {
        // Connectors look like card0-DSI-1.
        if (std::strncmp(entry->d_name, "card", 4) != 0 || std::strchr(entry->d_name, '-') == nullptr) {
            continue;
        }
        const std::string base = std::string("/sys/class/drm/") + entry->d_name;
        if (firstLine(base + "/status") != "connected") continue;
        size = parseMode(firstLine(base + "/modes"));
        if (size) break;
    }
    closedir(dir);
    return size;
}

std::optional<std::pair<int, int>> fromFbdev() {
    if (auto size = parseMode(firstLine("/sys/class/graphics/fb0/modes"))) return size;
    int w = 0;
    int h = 0;
    const std::string virtualSize = firstLine("/sys/class/graphics/fb0/virtual_size");
    if (std::sscanf(virtualSize.c_str(), "%d,%d", &w, &h) == 2 && w > 0 && h > 0) {
        return std::make_pair(w, h);
    }
    return std::nullopt;
}

}  // namespace

std::pair<int, int> detectDisplaySize() {
    if (auto size = fromDrm()) return *size;
    if (auto size = fromFbdev()) return *size;
    LOGW("display size unknown, assuming 640x480");
    return {640, 480};
}

}  // namespace joymouse
