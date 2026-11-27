#!/usr/bin/env bash
# Apply, check, or revert Android 17 out-of-tree patches for Xiaomi 14T (degas).
set -euo pipefail

MODE="${1:---apply}"
TOP="${2:-$PWD}"

usage() {
    echo "Usage: $0 [--apply|--status|--revert] [/path/to/rom-root]" >&2
    exit 1
}

case "$MODE" in
    --apply|--status|--revert) ;;
    -h|--help) usage ;;
    *) usage ;;
esac

TOP="$(realpath "$TOP")"
PATCH_ROOT="$(cd "$(dirname "$0")" && pwd)"

# Format: "tier|target_repo|relative_patch_path"
# tier: required (hardware/build breakage if missing) or optional (UI/ROM-dependent)
PATCHES=(
    "required|hardware/mediatek|hardware_mediatek/0001-drop-duplicate-mediatek-audio-service.patch"
    "required|hardware/mediatek|hardware_mediatek/0002-usb-gadget-do-not-link-nonexistent-mass_storage-on-mtp.patch"
    "optional|device/mediatek/sepolicy_vndr|device_mediatek_sepolicy_vndr/0001-drop-duplicate-typec-genfscon-for-A17.patch"
    "required|external/wpa_supplicant_8|external_wpa_supplicant_8/0001-nl80211-do-not-advertise-WPA_VERSION_3-for-SAE-on-MTK-gen4m.patch"
    "required|frameworks/opt/telephony|frameworks_opt_telephony/0001-UiccPort-set-isEs10-for-ISD-R-logical-channel-APDUs.patch"
    "required|frameworks/native|frameworks_native/0001-Scheduler-do-not-resync-hw-vsync-on-a-powered-off-display.patch"
    "required|frameworks/native|frameworks_native/0002-SurfaceFlinger-do-not-mode-set-a-powered-off-display.patch"
    "required|frameworks/base|frameworks_base/0003-udfps-hold-refresh-rate-while-overlay-shown.patch"
    "optional|frameworks/base|frameworks_base/0002-BatteryStats-ignore-implausible-charge-time-from-health-HAL.patch"
    "required|packages/apps/Settings|packages_apps_Settings/0001-settings-subscription-network-selection.patch"
    "optional|packages/apps/Settings|packages_apps_Settings/0002-FingerprintEnrollEnrolling-keep-skip-button-off-low-udfps.patch"
    "optional|packages/apps/Launcher3|packages_apps_Launcher3/0001-Launcher3-do-not-blur-the-wallpaper-during-a-drag.patch"
)

rc=0

for entry in "${PATCHES[@]}"; do
    IFS='|' read -r tier repo rel_patch <<< "$entry"
    repo_dir="$TOP/$repo"
    patch_file="$PATCH_ROOT/$rel_patch"

    if [[ ! -f "$patch_file" ]]; then
        echo "[MISSING]  $rel_patch" >&2
        rc=1
        continue
    fi

    if [[ ! -d "$repo_dir/.git" && ! -f "$repo_dir/.git" ]]; then
        if [[ "$tier" == "optional" ]]; then
            echo "[SKIP]     $repo (repo absent; optional)"
            continue
        fi
        echo "[ERROR]    missing git repo: $repo_dir" >&2
        rc=1
        continue
    fi

    if [[ "$MODE" == "--revert" ]]; then
        if git -C "$repo_dir" apply --reverse --check "$patch_file" 2>/dev/null; then
            git -C "$repo_dir" apply --reverse "$patch_file"
            echo "[REVERTED] $repo <- $rel_patch"
        else
            echo "[CLEAN]    $repo <- $rel_patch"
        fi
        continue
    fi

    if git -C "$repo_dir" apply --reverse --check "$patch_file" 2>/dev/null; then
        echo "[APPLIED]  $repo <- $rel_patch"
        continue
    fi

    if [[ "$MODE" == "--status" ]]; then
        if git -C "$repo_dir" apply --check "$patch_file" 2>/dev/null; then
            echo "[PENDING]  $repo <- $rel_patch"
        else
            tag="CONFLICT"
            [[ "$tier" == "optional" ]] && tag="SKIP-OPT"
            echo "[$tag] $repo <- $rel_patch"
            [[ "$tier" == "required" ]] && rc=1
        fi
        continue
    fi

    if git -C "$repo_dir" apply --check "$patch_file" 2>/dev/null; then
        git -C "$repo_dir" apply "$patch_file"
        echo "[OK]       $repo <- $rel_patch"
    else
        if [[ "$tier" == "optional" ]]; then
            echo "[SKIP-OPT] $repo <- $rel_patch (already upstream or ROM diverged)"
        else
            echo "[CONFLICT] $repo <- $rel_patch (needs rebase for this ROM)" >&2
            rc=1
        fi
    fi
done

exit "$rc"
