#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#
"""Offline validator for device/xiaomi/degas (Android 17 / LineageOS 24.0 / MT6897)."""

from __future__ import annotations

import argparse
import glob
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

TEMPERATURE_TYPES = {
    "UNKNOWN",
    "CPU",
    "GPU",
    "BATTERY",
    "SKIN",
    "USB_PORT",
    "POWER_AMPLIFIER",
    "BCL_VOLTAGE",
    "BCL_CURRENT",
    "BCL_PERCENTAGE",
    "NPU",
    "TPU",
    "DISPLAY",
    "MODEM",
    "SOC",
    "WIFI",
    "CAMERA",
    "FLASHLIGHT",
    "SPEAKER",
    "AMBIENT",
    "POGO",
}

COOLING_TYPES = {
    "FAN",
    "BATTERY",
    "CPU",
    "GPU",
    "MODEM",
    "NPU",
    "COMPONENT",
    "TPU",
    "POWER_AMPLIFIER",
    "DISPLAY",
    "SPEAKER",
    "WIFI",
    "CAMERA",
    "FLASHLIGHT",
    "USB_PORT",
}

POWERHINT_ALLOWLIST = {
    # Boost.aidl
    "INTERACTION",
    "DISPLAY_UPDATE_IMMINENT",
    "ML_ACC",
    "AUDIO_LAUNCH",
    "CAMERA_LAUNCH",
    "CAMERA_SHOT",
    # Mode.aidl (AOSP + common extended hints)
    "DOUBLE_TAP_TO_WAKE",
    "LOW_POWER",
    "SUSTAINED_PERFORMANCE",
    "FIXED_PERFORMANCE",
    "VR",
    "LAUNCH",
    "EXPENSIVE_RENDERING",
    "INTERACTIVE",
    "DEVICE_IDLE",
    "DISPLAY_INACTIVE",
    "AUDIO_STREAMING_LOW_LATENCY",
    "CAMERA_STREAMING_SECURE",
    "CAMERA_STREAMING_LOW",
    "CAMERA_STREAMING_MID",
    "CAMERA_STREAMING_HIGH",
    "GAME",
    "GAME_LOADING",
    "DISPLAY_CHANGE",
    "AUTOMOTIVE_PROJECTION",
    "APP_START",
    "UI_ANIMATION",
    "SYSTEM_UI",
    "FLING",
    "CPU_BENCHMARK",
    "GPU_BENCHMARK",
}

SELINUX_MACROS = (
    "binder_call",
    "binder_use",
    "hwbinder_use",
    "vndbinder_use",
    "set_prop",
    "get_prop",
    "rw_dir_file",
    "r_dir_file",
    "hal_client_domain",
    "hal_server_domain",
    "init_daemon_domain",
    "add_service",
    "add_hwservice",
)


class Validator:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.errors: list[dict[str, str]] = []
        self.warnings: list[dict[str, str]] = []

    def err(self, check: str, path: str, msg: str) -> None:
        self.errors.append({"check": check, "path": path, "message": msg})

    def warn(self, check: str, path: str, msg: str) -> None:
        self.warnings.append({"check": check, "path": path, "message": msg})

    def rel(self, path: Path) -> str:
        return str(path.relative_to(self.root))

    def load_json_strict(self, path: Path) -> Any:
        def hook(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
            out: dict[str, Any] = {}
            for k, v in pairs:
                if k in out:
                    self.err("json", self.rel(path), f"Duplicate JSON key: {k}")
                out[k] = v
            return out

        try:
            with path.open(encoding="utf-8") as fh:
                return json.load(fh, object_pairs_hook=hook)
        except Exception as exc:
            self.err("json", self.rel(path), f"Invalid JSON: {exc}")
            return None

    def check_xml_files(self) -> None:
        xml_files = sorted(self.root.rglob("*.xml"))
        for path in xml_files:
            if ".git" in path.parts:
                continue
            try:
                tree = ET.parse(path)
            except Exception as exc:
                self.err("xml", self.rel(path), f"XML parse error: {exc}")
                continue

            root_el = tree.getroot()
            if root_el.tag == "resources":
                seen: set[tuple[str, str]] = set()
                for child in root_el:
                    name = child.attrib.get("name")
                    if not name:
                        continue
                    key = (child.attrib.get("type", child.tag), name)
                    if key in seen:
                        self.err("xml", self.rel(path), f"Duplicate resource {key[0]}/{key[1]}")
                    seen.add(key)

        self._check_frameworks_overlay()
        self._check_carrier_config()

    def _check_frameworks_overlay(self) -> None:
        path = self.root / "overlay/DegasFrameworksOverlay/res/values/config.xml"
        if not path.exists():
            return
        root_el = ET.parse(path).getroot()
        levels = root_el.find('.//integer-array[@name="config_autoBrightnessLevels"]')
        nits = root_el.find('.//array[@name="config_autoBrightnessDisplayValuesNits"]')
        if levels is not None and nits is not None:
            if len(nits) != len(levels) + 1:
                self.err(
                    "display",
                    self.rel(path),
                    f"config_autoBrightnessDisplayValuesNits ({len(nits)}) must be len(levels)+1 ({len(levels) + 1})",
                )
            vals = [float(x.text) for x in nits if x.text]
            if vals != sorted(vals):
                self.err("display", self.rel(path), "config_autoBrightnessDisplayValuesNits is not non-decreasing")

        # Check XiaomiParts default thermal profile stays on sconfig=6 (thermal-nolimits.conf)
        parts_xml = self.root / "parts/res/xml/xiaomi_settings.xml"
        if parts_xml.exists():
            px = ET.parse(parts_xml).getroot()
            for lp in px.iter("ListPreference"):
                if lp.attrib.get("{http://schemas.android.com/apk/res/android}key") == "thermal_profile":
                    dv = lp.attrib.get("{http://schemas.android.com/apk/res/android}defaultValue", "")
                    if dv != "6":
                        self.err("parts", self.rel(parts_xml), f"thermal_profile defaultValue={dv!r} overrides init sconfig=6")

        disp_cfg = self.root / "configs/displayconfig/display_id_4627039422300187648.xml"
        if disp_cfg.exists():
            dc = ET.parse(disp_cfg).getroot()
            hbm = dc.find("highBrightnessMode")
            if hbm is None or hbm.attrib.get("enabled") != "true":
                self.err("display", self.rel(disp_cfg), "highBrightnessMode must be enabled=true")
            else:
                tp = hbm.findtext("transitionPoint")
                if tp is None or float(tp) >= 0.60:
                    self.err("display", self.rel(disp_cfg), f"transitionPoint={tp!r} caps manual brightness; expected < 0.60")
            points = [
                (float(p.findtext("value", "0")), float(p.findtext("nits", "0")))
                for p in dc.findall("./screenBrightnessMap/point")
            ]
            if not points or points[-1][0] < 1.0 or points[-1][1] < 4000.0:
                self.err("display", self.rel(disp_cfg), "screenBrightnessMap must reach value=1.0 (>=4000 nits HBM)")

    def _check_carrier_config(self) -> None:
        path = self.root / "overlay/DegasCarrierConfigOverlay/res/xml/vendor.xml"
        if not path.exists():
            return
        root_el = ET.parse(path).getroot()
        seen_blocks: set[tuple[tuple[str, str], ...]] = set()
        for cc in root_el.findall("carrier_config"):
            attrs = cc.attrib
            mcc = attrs.get("mcc")
            mnc = attrs.get("mnc")
            gid1 = attrs.get("gid1")
            if mcc is not None and not re.fullmatch(r"\d{3}", mcc):
                self.err("carrierconfig", self.rel(path), f"Invalid mcc={mcc!r}")
            if mnc is not None and not re.fullmatch(r"\d{2,3}", mnc):
                self.err("carrierconfig", self.rel(path), f"Invalid mnc={mnc!r} (must be 2-3 digits, no commas)")
            if gid1 is not None and gid1.upper().startswith("0X"):
                self.err("carrierconfig", self.rel(path), f"Unsupported 0X prefix in gid1={gid1!r}")

            block_key = tuple(sorted(attrs.items()))
            if block_key in seen_blocks:
                self.warn("carrierconfig", self.rel(path), f"Duplicate carrier_config block {dict(attrs)}")
            seen_blocks.add(block_key)

            for child in cc:
                if child.tag in ("boolean", "int", "string"):
                    if "name" not in child.attrib or "value" not in child.attrib:
                        self.err(
                            "carrierconfig",
                            self.rel(path),
                            f"Missing name/value on <{child.tag} {child.attrib}> in {dict(attrs)}",
                        )
                if (
                    child.tag == "int"
                    and child.attrib.get("name", "").startswith("carrier_default_wfc_ims")
                    and child.attrib.get("value") not in ("0", "1", "2")
                ):
                    self.err(
                        "carrierconfig",
                        self.rel(path),
                        f"Invalid AOSP WFC mode {child.attrib.get('value')} in {dict(attrs)}",
                    )


    def check_json_files(self) -> None:
        json_paths = list(self.root.rglob("*.json"))
        deps = self.root / "lineage.dependencies"
        if deps.exists():
            json_paths.append(deps)
        for path in sorted(json_paths):
            if ".git" in path.parts:
                continue
            self.load_json_strict(path)

        self._check_thermal_info_config()
        self._check_powerhint()

    def _check_thermal_info_config(self) -> None:
        path = self.root / "configs/thermal_info_config.json"
        if not path.exists():
            return
        data = self.load_json_strict(path)
        if not isinstance(data, dict):
            return

        cdevs: dict[str, dict[str, Any]] = {}
        for cdev in data.get("CoolingDevices", []):
            name = cdev.get("Name", "")
            ctype = cdev.get("Type", "")
            if ctype not in COOLING_TYPES:
                self.err("thermal", self.rel(path), f"Invalid CoolingType {ctype!r} on {name}")
            cdevs[name] = cdev

        visible_cb = 0
        for sensor in data.get("Sensors", []):
            name = sensor.get("Name", "")
            stype = sensor.get("Type", "")
            if stype not in TEMPERATURE_TYPES:
                self.err("thermal", self.rel(path), f"Invalid TemperatureType {stype!r} on {name}")

            hidden = bool(sensor.get("Hidden", False))
            send_cb = bool(sensor.get("SendCallback", False))
            zone_name = sensor.get("ZoneName", name)
            if hidden and send_cb:
                self.err("thermal", self.rel(path), f"Sensor {name} has both Hidden and SendCallback")
            if send_cb and zone_name != name:
                self.err("thermal", self.rel(path), f"Sensor {name} (ZoneName={zone_name}) must not set SendCallback (aborts MTK Thermal HAL)")
            if not hidden and send_cb:
                visible_cb += 1

            thresholds = sensor.get("HotThreshold", [])
            if len(thresholds) != 7:
                self.err("thermal", self.rel(path), f"Sensor {name} HotThreshold must have 7 entries")
            else:
                numeric = [(i, float(v)) for i, v in enumerate(thresholds) if v != "NaN"]
                for (_, a), (_, b) in zip(numeric, numeric[1:]):
                    if b < a:
                        self.err("thermal", self.rel(path), f"Sensor {name} HotThreshold not non-decreasing")
                        break

                hyst = sensor.get("HotHysteresis")
                if hyst is not None:
                    if len(hyst) != 7:
                        self.err("thermal", self.rel(path), f"Sensor {name} HotHysteresis must have 7 entries")
                    else:
                        prev_val: float | None = None
                        for t_raw, h_raw in zip(thresholds, hyst):
                            if t_raw == "NaN":
                                continue
                            t_val = float(t_raw)
                            h_val = float(h_raw)
                            if h_val < 0:
                                self.err("thermal", self.rel(path), f"Sensor {name} has negative hysteresis")
                            if prev_val is not None and (t_val - h_val) < prev_val:
                                self.err(
                                    "thermal",
                                    self.rel(path),
                                    f"Sensor {name} hysteresis {h_val} overlaps previous threshold {prev_val} at {t_val}",
                                )
                            prev_val = t_val

            for bind in sensor.get("BindedCdevInfo", []):
                req = bind.get("CdevRequest", "")
                if req not in cdevs:
                    self.err("thermal", self.rel(path), f"Sensor {name} binds unknown cdev {req!r}")
                limits = bind.get("LimitInfo", [])
                if len(limits) != 7:
                    self.err("thermal", self.rel(path), f"Sensor {name} LimitInfo for {req} must have 7 entries")

        _ = visible_cb

    def _check_powerhint(self) -> None:
        path = self.root / "configs/powerhint.json"
        if not path.exists():
            return
        data = self.load_json_strict(path)
        if not isinstance(data, dict):
            return

        nodes: dict[str, dict[str, Any]] = {}
        for node in data.get("Nodes", []):
            name = node.get("Name", "")
            if name in nodes:
                self.err("powerhint", self.rel(path), f"Duplicate Node {name}")
            nodes[name] = node
            vals = node.get("Values", [])
            if not vals:
                self.err("powerhint", self.rel(path), f"Node {name} has empty Values")
                continue

            if node.get("HoldFd"):
                if node.get("ResetOnInit"):
                    self.err("powerhint", self.rel(path), f"HoldFd node {name} must not set ResetOnInit")
                for v in vals:
                    if len(str(v)) == 4:
                        self.err(
                            "powerhint",
                            self.rel(path),
                            f"HoldFd node {name} value {v!r} is 4 bytes (triggers kernel pm_qos binary s32 parse)",
                        )

            if len(set(vals)) != len(vals):
                self.err("powerhint", self.rel(path), f"Node {name} has duplicate Values")

        used_nodes: set[str] = set()
        defined_hints: set[str] = set()
        seen_actions: set[tuple[str, str]] = set()
        actions = data.get("Actions", [])
        for act in actions:
            hint = act.get("PowerHint", "")
            defined_hints.add(hint)
            if hint not in POWERHINT_ALLOWLIST:
                self.err("powerhint", self.rel(path), f"Unknown PowerHint {hint!r}")
            if "Node" in act:
                nname = act["Node"]
                if nname not in nodes:
                    self.err("powerhint", self.rel(path), f"Action {hint} references unknown Node {nname!r}")
                else:
                    used_nodes.add(nname)
                    if act.get("Value") not in nodes[nname].get("Values", []):
                        self.err(
                            "powerhint",
                            self.rel(path),
                            f"Action {hint}/{nname} value {act.get('Value')!r} not in Node Values",
                        )
                key = (hint, nname)
                if key in seen_actions:
                    self.err("powerhint", self.rel(path), f"Duplicate action ({hint}, {nname})")
                seen_actions.add(key)

        for act in actions:
            if act.get("Type") in ("DoHint", "EndHint", "MaskHint"):
                target = act.get("Value", "")
                if target not in defined_hints:
                    self.err("powerhint", self.rel(path), f"{act['Type']} references undefined hint {target!r}")

        exp_actions = {
            act["Node"]: act.get("Value", "")
            for act in actions
            if act.get("PowerHint") == "EXPENSIVE_RENDERING" and "Node" in act
        }
        if int(exp_actions.get("CPULittleClusterMinFreq", "0") or 0) < 2000000:
            self.err("powerhint", self.rel(path), "EXPENSIVE_RENDERING CPULittleClusterMinFreq < 2000000 causes 144 Hz blur jank")
        if int(exp_actions.get("CPUMidClusterMinFreq", "0") or 0) < 2850000:
            self.err("powerhint", self.rel(path), "EXPENSIVE_RENDERING CPUMidClusterMinFreq < 2850000 causes 144 Hz blur jank")
        if exp_actions.get("UclampTAMin") != "100":
            self.err("powerhint", self.rel(path), "EXPENSIVE_RENDERING UclampTAMin must stay 100")
        if int(exp_actions.get("GpuPwrLevel", "99") or 99) > 38:
            self.err("powerhint", self.rel(path), "EXPENSIVE_RENDERING GpuPwrLevel > 38 drops GPU floor below 780 MHz")

        _ = used_nodes

    def check_android_bp_and_patches(self) -> None:
        module_names: dict[str, str] = {}
        for path in sorted(self.root.rglob("Android.bp")):
            if ".git" in path.parts:
                continue
            text = path.read_text(encoding="utf-8")
            stripped = re.sub(r"//.*", "", text)
            if stripped.count("{") != stripped.count("}"):
                self.err("soong", self.rel(path), "Unbalanced curly braces")
            if stripped.count("[") != stripped.count("]"):
                self.err("soong", self.rel(path), "Unbalanced square brackets")

            for m in re.finditer(r'\bname:\s*"([^"]+)"', stripped):
                mod = m.group(1)
                if mod in module_names:
                    self.err(
                        "soong",
                        self.rel(path),
                        f"Duplicate module name {mod!r} (also in {module_names[mod]})",
                    )
                module_names[mod] = self.rel(path)

            for field in ("src", "init_rc", "vintf_fragments"):
                for m in re.finditer(rf'\b{field}:\s*"([^"]+)"', stripped):
                    target = path.parent / m.group(1)
                    if not target.exists():
                        self.err("soong", self.rel(path), f"Missing {field} path {m.group(1)!r}")

        apply_sh = self.root / "patches/apply-patches.sh"
        if apply_sh.exists():
            txt = apply_sh.read_text(encoding="utf-8")
            for m in re.finditer(r'"(?:required|optional)\|[^|]+\|([^"]+\.patch)"', txt):
                patch_path = self.root / "patches" / m.group(1)
                if not patch_path.exists():
                    self.err("patches", self.rel(apply_sh), f"Referenced patch missing: patches/{m.group(1)}")

    def check_init_and_fstab(self) -> None:
        for path in sorted((self.root / "init").glob("*.rc")):
            if path.name.startswith("ueventd."):
                continue
            for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                line = raw.strip()
                if not line or line.startswith("#"):
                    continue
                parts = line.split()
                cmd = parts[0]
                if cmd == "chmod" and len(parts) >= 3:
                    mode = parts[1]
                    if not re.fullmatch(r"0[0-7]{3}", mode):
                        self.err("init", f"{self.rel(path)}:{lineno}", f"Non-4-digit octal chmod mode {mode!r}")
                elif cmd == "mkdir" and len(parts) >= 3:
                    mode = parts[2]
                    if not re.fullmatch(r"0[0-7]{3}", mode):
                        self.err("init", f"{self.rel(path)}:{lineno}", f"Non-4-digit octal mkdir mode {mode!r}")
                    elif mode == "0777":
                        self.err("init", f"{self.rel(path)}:{lineno}", f"World-writable 0777 dir {parts[1]}")
                elif cmd == "socket" and len(parts) >= 4:
                    mode = parts[3]
                    if not re.fullmatch(r"0[0-7]{3}", mode):
                        self.err("init", f"{self.rel(path)}:{lineno}", f"Non-4-digit octal socket mode {mode!r}")

        for fstab_name in ("fstab.mt6897", "fstab.enableswap"):
            fpath = self.root / "init" / fstab_name
            if not fpath.exists():
                continue
            for lineno, raw in enumerate(fpath.read_text(encoding="utf-8").splitlines(), 1):
                line = raw.strip()
                if not line or line.startswith("#"):
                    continue
                cols = line.split()
                if len(cols) != 5:
                    self.err("fstab", f"{self.rel(fpath)}:{lineno}", f"Expected 5 columns, got {len(cols)}")

    def check_props(self) -> None:
        prop_files = ("vendor.prop", "odm.prop", "product.prop", "system_ext.prop")
        all_props: dict[str, dict[str, str]] = {}
        for fname in prop_files:
            path = self.root / fname
            if not path.exists():
                continue
            seen: dict[str, str] = {}
            for lineno, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                if raw != raw.rstrip(" \t"):
                    self.err("props", f"{fname}:{lineno}", "Trailing whitespace")
                line = raw.strip()
                if not line or line.startswith("#"):
                    continue
                if "=" not in line:
                    self.err("props", f"{fname}:{lineno}", f"Malformed prop line: {line!r}")
                    continue
                key, val = line.split("=", 1)
                if key in seen:
                    self.err("props", f"{fname}:{lineno}", f"Duplicate property {key!r}")
                seen[key] = val
            all_props[fname] = seen

        merged_props: dict[str, str] = {}
        for fname, pmap in all_props.items():
            merged_props.update(pmap)
        if merged_props.get("debug.renderengine.backend") == "skiavkthreaded":
            self.err("props", "vendor.prop", "skiavkthreaded causes severe GPU missed frames on Mali-G615; use skiavk")
        if merged_props.get("persist.vendor.EnableP3ColorSpace") == "1":
            self.err("props", "vendor.prop", "EnableP3ColorSpace=1 forces CLIENT composition on MTK HWC")
        if merged_props.get("ro.surface_flinger.use_content_detection_for_refresh_rate") == "true":
            self.err("props", "vendor.prop", "use_content_detection_for_refresh_rate drops peak refresh rate to 60/90 Hz")
        sf144 = merged_props.get("debug.sf.early.sf.duration.144")
        if sf144 is not None and sf144.isdigit() and int(sf144) > 6944444:
            self.err("props", "vendor.prop", f"debug.sf.early.sf.duration.144={sf144} exceeds 144 Hz frame period (6.94 ms)")

    def check_sepolicy(self) -> None:
        se_dir = self.root / "sepolicy"
        if not se_dir.exists():
            return

        macro_re = re.compile(rf"^\s*({'|'.join(SELINUX_MACROS)})\([^)]*\)\s*;")
        for path in sorted(se_dir.rglob("*.te")):
            for lineno, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                if macro_re.match(line):
                    self.err("sepolicy", f"{self.rel(path)}:{lineno}", "Stray trailing semicolon on SELinux macro")

        genfs = se_dir / "vendor/genfs_contexts"
        if genfs.exists():
            seen_genfs: set[tuple[str, str]] = set()
            for lineno, raw in enumerate(genfs.read_text(encoding="utf-8").splitlines(), 1):
                line = raw.strip()
                if not line or line.startswith("#"):
                    continue
                parts = line.split()
                if len(parts) != 4 or parts[0] != "genfscon":
                    self.err("sepolicy", f"{self.rel(genfs)}:{lineno}", f"Malformed genfscon line: {line!r}")
                    continue
                key = (parts[1], parts[2])
                if key in seen_genfs:
                    self.err("sepolicy", f"{self.rel(genfs)}:{lineno}", f"Duplicate genfscon {key}")
                seen_genfs.add(key)

        fc = se_dir / "vendor/file_contexts"
        if fc.exists():
            for lineno, raw in enumerate(fc.read_text(encoding="utf-8").splitlines(), 1):
                line = raw.strip()
                if not line or line.startswith("#"):
                    continue
                pattern = line.split()[0]
                if "/bin/" in pattern and re.search(r"(?<!\\)\.(?!\*|\+)", pattern):
                    self.warn(
                        "sepolicy",
                        f"{self.rel(fc)}:{lineno}",
                        f"Unescaped dot in binary file_contexts regex: {pattern}",
                    )

    def check_xiaomi_parts(self) -> None:
        parts_dir = self.root / "parts"
        if not parts_dir.exists():
            return

        defined: dict[str, set[str]] = {
            "string": set(),
            "plurals": set(),
            "array": set(),
            "drawable": set(),
            "layout": set(),
            "xml": set(),
            "id": set(),
        }
        for path in parts_dir.rglob("*.xml"):
            try:
                tree = ET.parse(path)
            except Exception:
                continue
            rel_res = os.path.relpath(path, parts_dir / "res")
            if rel_res.startswith("drawable/"):
                defined["drawable"].add(path.stem)
            elif rel_res.startswith("layout/"):
                defined["layout"].add(path.stem)
            elif rel_res.startswith("xml/"):
                defined["xml"].add(path.stem)
            for el in tree.getroot().iter():
                if el.tag == "string" and "name" in el.attrib:
                    defined["string"].add(el.attrib["name"])
                elif el.tag == "plurals" and "name" in el.attrib:
                    defined["plurals"].add(el.attrib["name"])
                elif el.tag in ("string-array", "integer-array", "array") and "name" in el.attrib:
                    defined["array"].add(el.attrib["name"])
                for k, v in el.attrib.items():
                    if k.endswith("}id") and v.startswith("@+id/"):
                        defined["id"].add(v[5:])

        for path in parts_dir.rglob("*.xml"):
            txt = path.read_text(encoding="utf-8")
            for kind, name in re.findall(r"@(string|plurals|array|drawable|layout|xml)/([a-zA-Z0-9_]+)", txt):
                if name not in defined[kind]:
                    self.err("parts", self.rel(path), f"Unresolved @{kind}/{name}")

        for path in parts_dir.rglob("*.kt"):
            txt = path.read_text(encoding="utf-8")
            for kind, name in re.findall(
                r"(?<!android\.)\bR\.(string|plurals|array|drawable|layout|xml|id)\.([a-zA-Z0-9_]+)", txt
            ):
                if kind == "id" and name == "content_frame":
                    continue
                if name not in defined[kind]:
                    self.err("parts", self.rel(path), f"Unresolved R.{kind}.{name}")
            if txt.count("{") != txt.count("}"):
                self.err("parts", self.rel(path), "Unbalanced curly braces")
            if txt.count("(") != txt.count(")"):
                self.err("parts", self.rel(path), "Unbalanced parentheses")
            if re.search(r"modeReset\s*\([^)]*MODE_TOUCH_GAME_MODE", txt):
                self.err("parts", self.rel(path), "Direct modeReset on MODE_TOUCH_GAME_MODE wipes all touch modes")

        manifest = parts_dir / "AndroidManifest.xml"
        if manifest.exists():
            m = ET.parse(manifest).getroot()
            pkg = m.attrib.get("package", "")
            ns = "{http://schemas.android.com/apk/res/android}"
            for tag in ("activity", "service", "receiver"):
                for el in m.findall(f".//{tag}"):
                    cname = el.attrib.get(f"{ns}name", "")
                    fq = (pkg + cname) if cname.startswith(".") else cname
                    kt_path = parts_dir / "src" / (fq.replace(".", "/") + ".kt")
                    if not kt_path.exists():
                        self.err("parts", self.rel(manifest), f"Manifest {tag} {cname} missing {self.rel(kt_path)}")

    def check_board_and_extract(self) -> None:
        bc = self.root / "BoardConfig.mk"
        if bc.exists():
            txt = bc.read_text(encoding="utf-8")
            m_cpu = re.search(r"^\s*TARGET_CPU_VARIANT\s*:=\s*(\S+)", txt, re.M)
            if not m_cpu or m_cpu.group(1) != "cortex-a55":
                self.err("board", self.rel(bc), "TARGET_CPU_VARIANT must stay cortex-a55 on MT6897")

            forbidden_ota = {
                "lk",
                "preloader",
                "tee",
                "gz",
                "spmfw",
                "sspm",
                "mcupm",
                "dpm",
                "pi_img",
                "scp",
                "ccu",
                "vcp",
                "gpueb",
                "audio_dsp",
                "md1img",
                "connsys_bt",
                "connsys_gnss",
                "connsys_wifi",
                "logo",
                "dtbo",
            }
            ota_m = re.search(r"AB_OTA_PARTITIONS\s*\+?=\s*((?:[^\n]*\\\n)*[^\n]*)", txt)
            if ota_m:
                tokens = set(re.findall(r"[a-zA-Z0-9_]+", ota_m.group(1)))
                bad = sorted(tokens & forbidden_ota)
                if bad:
                    self.err("board", self.rel(bc), f"Forbidden firmware partitions in AB_OTA_PARTITIONS: {bad}")

        prop_files = self.root / "proprietary-files.txt"
        if prop_files.exists():
            for lineno, raw in enumerate(prop_files.read_text(encoding="utf-8").splitlines(), 1):
                line = raw.strip()
                if not line or line.startswith("#"):
                    continue
                if "display_id_4627039422300187648.xml" in line:
                    self.err(
                        "extract",
                        f"proprietary-files.txt:{lineno}",
                        "Do not extract stock display_id_4627039422300187648.xml; device.mk ships HBM-unlocked config",
                    )

    def run_all(self) -> None:
        self.check_xml_files()
        self.check_json_files()
        self.check_android_bp_and_patches()
        self.check_init_and_fstab()
        self.check_props()
        self.check_sepolicy()
        self.check_xiaomi_parts()
        self.check_board_and_extract()


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate device/xiaomi/degas configuration and sources.")
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parent.parent,
        help="Path to device/xiaomi/degas root",
    )
    parser.add_argument("--strict", action="store_true", help="Treat warnings as errors")
    parser.add_argument("--json", action="store_true", dest="as_json", help="Emit JSON output")
    args = parser.parse_args()

    validator = Validator(args.root.resolve())
    validator.run_all()

    failed = bool(validator.errors) or (args.strict and bool(validator.warnings))
    if args.as_json:
        print(
            json.dumps(
                {
                    "ok": not failed,
                    "errors": validator.errors,
                    "warnings": validator.warnings,
                },
                indent=2,
            )
        )
    else:
        for item in validator.errors:
            print(f"ERROR [{item['check']}] {item['path']}: {item['message']}")
        for item in validator.warnings:
            print(f"WARN  [{item['check']}] {item['path']}: {item['message']}")
        status = "FAILED" if failed else "PASSED"
        print(f"\nValidation {status}: {len(validator.errors)} error(s), {len(validator.warnings)} warning(s)")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
