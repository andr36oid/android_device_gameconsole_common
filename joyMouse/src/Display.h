#pragma once

#include <utility>

namespace joymouse {

// Size of the built-in panel in pixels, from DRM or fbdev sysfs; 640x480 if
// neither can be read. Only used to scale pointer speed, so the orientation
// doesn't matter.
std::pair<int, int> detectDisplaySize();

}  // namespace joymouse
