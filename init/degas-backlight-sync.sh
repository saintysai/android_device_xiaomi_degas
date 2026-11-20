#!/vendor/bin/sh
#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

node=/sys/class/leds/lcd-backlight/brightness
level=$(cat $node) || exit 1

if [ "$level" -gt 1 ]; then
    echo $((level - 1)) > $node
else
    echo $((level + 1)) > $node
fi
echo $level > $node
