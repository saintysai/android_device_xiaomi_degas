# Out-of-tree patches for Xiaomi 14T (`degas`) on Android 17

Apply after `repo sync`:

```sh
bash device/xiaomi/degas/patches/apply-patches.sh --apply "$ANDROID_BUILD_TOP"
bash device/xiaomi/degas/patches/apply-patches.sh --status "$ANDROID_BUILD_TOP"
bash device/xiaomi/degas/patches/apply-patches.sh --revert "$ANDROID_BUILD_TOP"
```

## Patch list

| Patch | Target repo | Tier | Purpose |
| --- | --- | --- | --- |
| `hardware_mediatek/0001-drop-duplicate-mediatek-audio-service.patch` | `hardware/mediatek` | Required | Drops duplicate upstream `android.hardware.audio.service.mediatek` so Soong builds `device/xiaomi/degas/audio`. |
| `hardware_mediatek/0002-usb-gadget-do-not-link-nonexistent-mass_storage-on-mtp.patch` | `hardware/mediatek` | Required | Fixes MTP enumeration by not linking missing `mass_storage` ConfigFS function. |
| `external_wpa_supplicant_8/0001-nl80211-do-not-advertise-WPA_VERSION_3-for-SAE-on-MTK-gen4m.patch` | `external/wpa_supplicant_8` | Required | Keeps MTK `gen4m` from rejecting WPA3/SAE APs (`status=16`); enabled by `board_wlan_mtk_gen4m`. |
| `frameworks_opt_telephony/0001-UiccPort-set-isEs10-for-ISD-R-logical-channel-APDUs.patch` | `frameworks/opt/telephony` | Required | Marks ISD-R logical channel APDUs as ES10 and injects MEP-A1 `targetPort` (`0x82`) into `ES10c.EnableProfile` (`0xBF31`) so eSIM download and profile switching work on degas eUICC. |
| `frameworks_native/0001-Scheduler-do-not-resync-hw-vsync-on-a-powered-off-display.patch` | `frameworks/native` | Required | Prevents HW vsync resync while the panel is powered off. |
| `frameworks_native/0002-SurfaceFlinger-do-not-mode-set-a-powered-off-display.patch` | `frameworks/native` | Required | Skips mode sets when `!display->isPoweredOn()` to stop screen-off 144 Hz vsync drain. |
| `frameworks_base/0003-udfps-hold-refresh-rate-while-overlay-shown.patch` | `frameworks/base` | Required | Holds `config_udfpsRefreshRate` (120 Hz) while the UDFPS overlay is active so local HBM stays stable. |
| `packages_apps_Settings/0001-settings-subscription-network-selection.patch` | `packages/apps/Settings` | Required | Uses `getConfigForSubId(subId)` and GSM-inclusive network modes accepted by `mtkfusionrild`. |
| `device_mediatek_sepolicy_vndr/0001-drop-duplicate-typec-genfscon-for-A17.patch` | `device/mediatek/sepolicy_vndr` | Optional | Drops duplicate `typec` genfscon entry on forks that have not dropped it upstream for A17. |
| `frameworks_base/0002-BatteryStats-ignore-implausible-charge-time-from-health-HAL.patch` | `frameworks/base` | Optional | Caps bogus MTK fuel-gauge `time_to_full_now` values (>12 h) in BatteryStats. |
| `packages_apps_Settings/0002-FingerprintEnrollEnrolling-keep-skip-button-off-low-udfps.patch` | `packages/apps/Settings` | Optional | Moves the Skip button out of the low-mounted UDFPS sensor area during enrollment. |
| `packages_apps_Launcher3/0001-Launcher3-do-not-blur-the-wallpaper-during-a-drag.patch` | `packages/apps/Launcher3` | Optional | Disables drag-state wallpaper blur to prevent Mali-G615 144 Hz drag jank. |
