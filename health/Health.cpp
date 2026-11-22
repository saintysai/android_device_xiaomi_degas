/*
 * Copyright (C) 2021 The Android Open Source Project
 * Copyright (C) 2022-2026 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

#include <algorithm>
#include <cstdint>
#include <limits>
#include <memory>
#include <string>
#include <string_view>

#include <android-base/file.h>
#include <android-base/logging.h>
#include <android-base/parseint.h>
#include <android-base/strings.h>
#include <android/binder_interface_utils.h>
#include <health-impl/Health.h>
#include <health/utils.h>

#ifndef CHARGER_FORCE_NO_UI
#define CHARGER_FORCE_NO_UI 0
#endif

#if !CHARGER_FORCE_NO_UI
#include <health-impl/ChargerUtils.h>
#endif

using aidl::android::hardware::health::HalHealthLoop;
using aidl::android::hardware::health::Health;
using aidl::android::hardware::health::HealthInfo;

#if !CHARGER_FORCE_NO_UI
using aidl::android::hardware::health::charger::ChargerCallback;
using aidl::android::hardware::health::charger::ChargerModeMain;
#endif

namespace {
constexpr const char* kInstanceName = "default";
constexpr std::string_view kChargerArg{"--charger"};

constexpr const char* kUsbVoltageNowPath = "/sys/class/power_supply/usb/voltage_now";
constexpr const char* kUsbInputCurrentNowPath = "/sys/class/power_supply/usb/input_current_now";
constexpr const char* kUsbCurrentNowPath = "/sys/class/power_supply/usb/current_now";
constexpr const char* kUsbApdoMaxPath = "/sys/class/power_supply/usb/apdo_max";
constexpr const char* kUsbQuickChargeTypePath = "/sys/class/power_supply/usb/quick_charge_type";
constexpr const char* kUsbRealTypePath = "/sys/class/power_supply/usb/real_type";
constexpr const char* kCpMasterVbusPath = "/sys/class/power_supply/cp_master/cp_vbus";
constexpr const char* kCpMasterIbusPath = "/sys/class/power_supply/cp_master/cp_ibus";
constexpr const char* kCpSlaveIbusPath = "/sys/class/power_supply/cp_slave/cp_ibus";
constexpr const char* kBatteryCurrentNowPath = "/sys/class/power_supply/battery/current_now";
constexpr const char* kBatteryVoltageNowPath = "/sys/class/power_supply/battery/voltage_now";

constexpr int32_t kMinValidVbusUv = 3000000;
constexpr int32_t kMaxValidVbusUv = 22000000;
constexpr int32_t kDefaultVbusUv = 5000000;
constexpr int32_t kMinValidCurrentUa = 100000;
constexpr int32_t kMaxValidCurrentUa = 15000000;
constexpr int64_t kMaxPlausibleChargeTimeSecs = 12LL * 60 * 60;

bool ReadTrimmedString(const char* path, std::string* out) {
    std::string content;
    if (!android::base::ReadFileToString(path, &content, true)) {
        return false;
    }
    *out = android::base::Trim(content);
    return !out->empty();
}

bool ReadInt32(const char* path, int32_t* out) {
    std::string content;
    if (!ReadTrimmedString(path, &content)) {
        return false;
    }
    return android::base::ParseInt(content, out);
}

int32_t NormalizeVbusToUv(int32_t raw) {
    if (raw <= 0) {
        return 0;
    }
    int64_t uv = (raw >= 1000000) ? static_cast<int64_t>(raw) : static_cast<int64_t>(raw) * 1000LL;
    if (uv < kMinValidVbusUv || uv > kMaxValidVbusUv) {
        return 0;
    }
    return static_cast<int32_t>(uv);
}

int32_t NormalizeIbusToUa(int32_t raw) {
    if (raw <= 0) {
        return 0;
    }
    int64_t ua = (raw > 20000) ? static_cast<int64_t>(raw) : static_cast<int64_t>(raw) * 1000LL;
    if (ua > kMaxValidCurrentUa) {
        ua = kMaxValidCurrentUa;
    }
    return static_cast<int32_t>(ua);
}

int32_t CurrentFromWattsAndUv(int32_t watts, int32_t voltage_uv) {
    if (watts <= 0 || voltage_uv <= 0) {
        return 0;
    }
    int64_t ua = (static_cast<int64_t>(watts) * 1000000LL * 1000000LL) / voltage_uv;
    ua = std::clamp<int64_t>(ua, kMinValidCurrentUa, kMaxValidCurrentUa);
    return static_cast<int32_t>(ua);
}

class DegasHealth : public Health {
  public:
    using Health::Health;

  protected:
    void UpdateHealthInfo(HealthInfo* health_info) override {
        if (!health_info) {
            return;
        }

        if (health_info->batteryChargeTimeToFullNowSeconds > kMaxPlausibleChargeTimeSecs) {
            health_info->batteryChargeTimeToFullNowSeconds =
                    HealthInfo::BATTERY_CHARGE_TIME_TO_FULL_NOW_SECONDS_UNSUPPORTED;
        }

        if (!health_info->chargerAcOnline && !health_info->chargerUsbOnline) {
            return;
        }

        int32_t raw_val = 0;
        int32_t cp_ibus_ua = 0;
        if (ReadInt32(kCpMasterIbusPath, &raw_val) && raw_val > 0) {
            cp_ibus_ua += NormalizeIbusToUa(raw_val);
        }
        if (ReadInt32(kCpSlaveIbusPath, &raw_val) && raw_val > 0) {
            cp_ibus_ua += NormalizeIbusToUa(raw_val);
        }
        cp_ibus_ua = std::min(cp_ibus_ua, kMaxValidCurrentUa);

        int32_t pmic_ibus_ua = 0;
        if (ReadInt32(kUsbInputCurrentNowPath, &raw_val) && raw_val > 0) {
            pmic_ibus_ua = NormalizeIbusToUa(raw_val);
        } else if (ReadInt32(kUsbCurrentNowPath, &raw_val) && raw_val > 0) {
            pmic_ibus_ua = NormalizeIbusToUa(raw_val);
        }

        const bool cp_active = (cp_ibus_ua > 200000);
        const int32_t live_ibus_ua = cp_active ? cp_ibus_ua : pmic_ibus_ua;

        int32_t usb_vbus_uv = 0;
        if (ReadInt32(kUsbVoltageNowPath, &raw_val)) {
            usb_vbus_uv = NormalizeVbusToUv(raw_val);
        }
        int32_t cp_vbus_uv = 0;
        if (ReadInt32(kCpMasterVbusPath, &raw_val)) {
            cp_vbus_uv = NormalizeVbusToUv(raw_val);
        }
        int32_t vbus_uv = 0;
        if (cp_active && cp_vbus_uv > 0) {
            vbus_uv = cp_vbus_uv;
        } else if (usb_vbus_uv > 0) {
            vbus_uv = usb_vbus_uv;
        } else if (cp_vbus_uv > 0) {
            vbus_uv = cp_vbus_uv;
        }

        int32_t bat_chg_ua = 0;
        if (ReadInt32(kBatteryCurrentNowPath, &raw_val) && raw_val < 0 &&
            raw_val > std::numeric_limits<int32_t>::min()) {
            bat_chg_ua = std::min(-raw_val, kMaxValidCurrentUa);
        }
        int32_t bat_v_uv = 0;
        if (ReadInt32(kBatteryVoltageNowPath, &raw_val) && raw_val > 0) {
            if (raw_val >= 1000000 && raw_val <= kMaxValidVbusUv) {
                bat_v_uv = raw_val;
            } else if (raw_val >= 2000 && raw_val <= 20000) {
                bat_v_uv = raw_val * 1000;
            }
        } else if (health_info->batteryVoltageMillivolts >= 2000 &&
                   health_info->batteryVoltageMillivolts <= 20000) {
            bat_v_uv = health_info->batteryVoltageMillivolts * 1000;
        }

        const int64_t p_in_uw = (vbus_uv > 0 && live_ibus_ua > 0)
                                        ? (static_cast<int64_t>(vbus_uv) * live_ibus_ua) / 1000000LL
                                        : 0;
        const int64_t p_bat_uw = (bat_v_uv > 0 && bat_chg_ua > 0)
                                         ? (static_cast<int64_t>(bat_v_uv) * bat_chg_ua) / 1000000LL
                                         : 0;

        int32_t live_v_uv = 0;
        int32_t live_i_ua = 0;
        int64_t live_p_uw = 0;
        if (p_in_uw >= p_bat_uw && p_in_uw > 0) {
            live_v_uv = vbus_uv;
            live_i_ua = live_ibus_ua;
            live_p_uw = p_in_uw;
        } else if (p_bat_uw > 0) {
            if (vbus_uv >= 4500000) {
                live_v_uv = vbus_uv;
                live_i_ua = static_cast<int32_t>(
                        std::clamp<int64_t>((p_bat_uw * 1000000LL) / vbus_uv,
                                            kMinValidCurrentUa, kMaxValidCurrentUa));
            } else {
                live_v_uv = bat_v_uv;
                live_i_ua = bat_chg_ua;
            }
            live_p_uw = p_bat_uw;
        }

        int32_t apdo_max_w = 0;
        if (ReadInt32(kUsbApdoMaxPath, &raw_val) && raw_val > 0 && raw_val <= 200) {
            apdo_max_w = raw_val;
        }
        int32_t qc_type = 0;
        if (ReadInt32(kUsbQuickChargeTypePath, &raw_val) && raw_val > 0) {
            qc_type = raw_val;
        }
        std::string real_type;
        ReadTrimmedString(kUsbRealTypePath, &real_type);

        const bool is_fast_adapter =
                (apdo_max_w > 0) || (qc_type >= 1) || (real_type == "USB_PD") ||
                (real_type == "HVCHG") || (real_type.rfind("USB_HVDCP", 0) == 0);

        int32_t cap_v_uv = (vbus_uv >= 4500000) ? vbus_uv : kDefaultVbusUv;
        int32_t cap_i_ua = 500000;

        if (apdo_max_w > 0) {
            cap_v_uv = (vbus_uv >= 6000000) ? vbus_uv : ((apdo_max_w >= 50) ? 11000000 : 9000000);
            cap_i_ua = CurrentFromWattsAndUv(apdo_max_w, cap_v_uv);
        } else if (qc_type >= 2) {
            cap_v_uv = (vbus_uv >= 6000000) ? vbus_uv : 9000000;
            cap_i_ua = CurrentFromWattsAndUv(27, cap_v_uv);
        } else if (is_fast_adapter) {
            cap_v_uv = (vbus_uv >= 6000000) ? vbus_uv : 9000000;
            cap_i_ua = CurrentFromWattsAndUv(18, cap_v_uv);
        } else if (real_type == "DCP" || real_type == "CDP" || health_info->chargerAcOnline) {
            cap_i_ua = 1500000;
        } else if (real_type == "USB_FLOAT") {
            cap_i_ua = 1000000;
        }

        const int64_t min_live_uw = is_fast_adapter ? 1000000LL : 500000LL;
        if (live_v_uv > 0 && live_i_ua > 0 && live_p_uw >= min_live_uw) {
            health_info->maxChargingVoltageMicrovolts = live_v_uv;
            health_info->maxChargingCurrentMicroamps =
                    std::clamp(live_i_ua, kMinValidCurrentUa, kMaxValidCurrentUa);
        } else {
            health_info->maxChargingVoltageMicrovolts = cap_v_uv;
            health_info->maxChargingCurrentMicroamps =
                    std::clamp(cap_i_ua, kMinValidCurrentUa, kMaxValidCurrentUa);
        }
    }
};
}

#if !CHARGER_FORCE_NO_UI
namespace aidl::android::hardware::health {
class ChargerCallbackImpl : public ChargerCallback {
  public:
    using ChargerCallback::ChargerCallback;
    bool ChargerEnableSuspend() override { return true; }
};
}
#endif

int main(int argc, char** argv) {
#ifdef __ANDROID_RECOVERY__
    android::base::InitLogging(argv, android::base::KernelLogger);
#endif

    auto config = std::make_unique<healthd_config>();
    ::android::hardware::health::InitHealthdConfig(config.get());
    auto binder = ndk::SharedRefBase::make<DegasHealth>(kInstanceName, std::move(config));

    if (argc >= 2 && argv[1] == kChargerArg) {
#if !CHARGER_FORCE_NO_UI
        return ChargerModeMain(
                binder,
                std::make_shared<aidl::android::hardware::health::ChargerCallbackImpl>(binder));
#endif
        LOG(INFO) << "Starting charger mode without UI.";
    } else {
        LOG(INFO) << "Starting degas health HAL.";
    }

    auto hal_health_loop = std::make_shared<HalHealthLoop>(binder, binder);
    return hal_health_loop->StartLoop();
}
