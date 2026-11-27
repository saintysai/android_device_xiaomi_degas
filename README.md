Copyright (C) 2025-2026 The LineageOS Project

Device configuration for Xiaomi 14T
===================================

Basic   | Specification Sheet
-------:|:-------------------------
CPU     | Octa-core (1x3.35 GHz & 3x3.20 GHz & 4x2.20 GHz)
Chipset | MediaTek Dimensity 8300 Ultra (MT6897)
GPU     | Mali-G615 MC6
Memory  | 12 GB / 16 GB RAM
Shipped Android Version | 14
Vendor / Firmware Base | HyperOS `OS3.0.306.0.WNEEUXM` (Android 16 / GKI 6.1)
Storage | 256 GB / 512 GB (UFS 4.0)
Battery | Non-removable Li-Po 5000 mAh (67W Xiaomi PPS)
Display | 1220 x 2712 pixels, 6.67 inches, 144 Hz AMOLED
Camera  | 50 MP (wide) + 50 MP (telephoto) + 12 MP (ultrawide), 32 MP (front)

## Usage as an Android 17 base tree

This branch (`lineage-24.0`) is kept free of ROM-specific flags so it can be forked or adapted to any Android 17 ROM.

1. Sync dependencies using `degas.xml` (copy to `.repo/local_manifests/degas.xml`) or `lineage.dependencies`.
2. Apply out-of-tree patches from `patches/`:
   ```sh
   bash device/xiaomi/degas/patches/apply-patches.sh --apply "$ANDROID_BUILD_TOP"
   ```

## Porting to another Android 17 ROM

- Copy or rename `lineage_degas.mk` to `<rom>_degas.mk`, update `AndroidProducts.mk` and `PRODUCT_NAME`, and replace `vendor/lineage/config/common_full_phone.mk` with your ROM's common phone makefile.
- In `BoardConfig.mk`, adjust `-include vendor/lineage/config/BoardConfigReservedSize.mk` if your ROM ships reserved-size rules under a different `vendor/<rom>` path.
- `device.mk` guards `OpenEUICC`, `NcmTetheringOverlay`, `LineageApertureOverlayDegas`, and `vendor.lineage.health-service.default` automatically when those packages are absent from the ROM tree.
- For ROMs with a software FaceUnlock app (such as Evolution X), set `TARGET_PROVIDES_FACE_UNLOCK_HAL := true` in your ROM product makefile so it uses degas's native `mifaced` HAL instead of conflicting permissions.
- Optional vendor repos (`vendor/xiaomi/miuicamera`, `vendor/xiaomi/degas-dolby`, `vendor/xiaomi/degas-charge`) are inherited via `inherit-product-if-exists` and skipped when absent.
