# Copyright (C) 2024 The LineageOS Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

#
# Input Device Configuration for R36S Game Console Controller
# Vendor: 0x1209 (Generic/Arduino)
# Product: 0x3100
#
# This configuration enables the right analog stick for mouse pointer control
# when mouse mode is active.
#

# Basic device configuration
device.internal = 0
device.type = joystick

# Keyboard configuration
keyboard.layout = Vendor_1209_Product_3100
keyboard.characterMap = Vendor_1209_Product_3100
keyboard.orientationAware = 0
keyboard.builtIn = 0

# Cursor/Pointer configuration
# Enable pointer mode capability
cursor.mode = navigation
cursor.orientationAware = 0

# Left analog stick - standard joystick navigation
axis 0x00 {
    # X axis (left stick horizontal)
    mode = normal
    axis = X
    source = joystick
    flat = 4096
    fuzz = 255
}

axis 0x01 {
    # Y axis (left stick vertical)
    mode = normal
    axis = Y
    source = joystick
    flat = 4096
    fuzz = 255
}

# Right analog stick - configured for mouse pointer control
# When mouse mode is active, these axes will control the pointer
axis 0x02 {
    # RX axis (right stick horizontal)
    mode = normal
    axis = Z
    source = joystick
    flat = 4096
    fuzz = 255
    # Enable as potential pointer control
}

axis 0x05 {
    # RY axis (right stick vertical)
    mode = normal
    axis = RZ
    source = joystick
    flat = 4096
    fuzz = 255
    # Enable as potential pointer control
}

# Trigger axes (analog triggers)
axis 0x0a {
    # Left trigger (L2)
    mode = normal
    axis = LTRIGGER
    source = joystick
    flat = 0
    fuzz = 15
}

axis 0x09 {
    # Right trigger (R2)
    mode = normal
    axis = RTRIGGER
    source = joystick
    flat = 0
    fuzz = 15
}

# Hat switch (D-pad)
axis 0x10 {
    # Hat X
    mode = normal
    axis = HAT_X
    source = joystick
    flat = 0
    fuzz = 0
}

axis 0x11 {
    # Hat Y
    mode = normal
    axis = HAT_Y
    source = joystick
    flat = 0
    fuzz = 0
}
