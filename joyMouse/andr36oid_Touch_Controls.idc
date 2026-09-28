# The virtual touchscreen of touch controls (joyMouse touch mode, see README.md).
# joyMouse creates it as "andr36oid Touch Controls" with vendor and product 0,
# so Android looks this file up by name.
#
# A touchscreen on the built-in display. Its raw range is the display in its
# natural orientation, and Android rotates touches along with the display.
touch.deviceType = touchScreen
touch.orientationAware = 1
device.internal = 1

# Fingers have no size or pressure; Android reports pressure 1 while touching.
touch.size.calibration = none
touch.pressure.calibration = none
