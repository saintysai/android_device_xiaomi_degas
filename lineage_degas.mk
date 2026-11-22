#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

# Inherit from those products. Most specific first.
$(call inherit-product, $(SRC_TARGET_DIR)/product/core_64_bit_only.mk)
$(call inherit-product, $(SRC_TARGET_DIR)/product/full_base_telephony.mk)

# Inherit from device makefile.
$(call inherit-product, device/xiaomi/degas/device.mk)

# AxionOS build flags (evaluated by vendor/lineage/config/common_full_phone.mk)
TARGET_DISABLE_EPPE := true
TARGET_SHIPS_AXION_KERNEL_MODULES := false
TARGET_SUPPORTED_REFRESH_RATES := 60,90,120,144
TARGET_DOZE_PICKUP_PULSE_SUPPORTED := true
TARGET_DOZE_DOUBLE_TAP_PULSE_SUPPORTED := true

# Inherit some common LineageOS stuff.
$(call inherit-product, vendor/lineage/config/common_full_phone.mk)

# AxionOS
AXION_MAINTAINER := saiquer
AXION_PROCESSOR := MediaTek_Dimensity_8300-Ultra
AXION_CAMERA_REAR_INFO := 50,50,12
AXION_CAMERA_FRONT_INFO := 32
AXION_CPU_SMALL_CORES := 0,1,2,3
AXION_CPU_BIG_CORES := 4,5,6,7
TARGET_ENABLE_BLUR := true

# Device identifier
PRODUCT_BRAND := Xiaomi
PRODUCT_DEVICE := degas
PRODUCT_MANUFACTURER := Xiaomi
PRODUCT_MODEL := 2406APNFAG
PRODUCT_NAME := lineage_degas

PRODUCT_CHARACTERISTICS := nosdcard
PRODUCT_GMS_CLIENTID_BASE := android-xiaomi

PRODUCT_BUILD_PROP_OVERRIDES += \
    BuildDesc="missi-user 16 BP2A.250605.031.A3 OS3.0.4.0.WNEMIXM release-keys" \
    BuildFingerprint=Xiaomi/degas_global/degas:16/BP2A.250605.031.A3/OS3.0.4.0.WNEMIXM:user/release-keys \
    DeviceProduct=degas_global
