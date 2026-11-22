#!/usr/bin/env python3
#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#
"""Offline validator for device/xiaomi/degas (AxionOS 2.9 / MT6897)."""

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
    # Mode.aidl (AOSP + AxionOS AxDragonite)
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
        self._check_axion_xmls()

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

        for key in ("config_brightness_ramp_rate_fast", "config_brightness_ramp_rate_slow"):
            el = root_el.find(f'.//integer[@name="{key}"]')
            if el is not None and el.text and int(el.text.strip()) > 255:
                self.err("display", self.rel(path), f"{key}={el.text.strip()} exceeds AOSP 0..255 range")

        gamma = root_el.find('.//fraction[@name="config_autoBrightnessAdjustmentMaxGamma"]')
        if gamma is not None and gamma.text and gamma.text.strip() in ("100%", "100.0%"):
            self.err("display", self.rel(path), "config_autoBrightnessAdjustmentMaxGamma=100% disables adaptive brightness")

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

    def _check_axion_xmls(self) -> None:
        thermal_xml = self.root / "configs/axion/ax_perf_thermal.xml"
        if thermal_xml.exists():
            root_el = ET.parse(thermal_xml).getroot()
            cpu_levels = {el.attrib["id"] for el in root_el.findall(".//cpu_levels/level") if "id" in el.attrib}
            gpu_levels = {el.attrib["id"] for el in root_el.findall(".//gpu_levels/level") if "id" in el.attrib}
            for action in root_el.findall(".//action"):
                unit = action.attrib.get("unit")
                status = action.attrib.get("status")
                if unit == "cpu" and status not in cpu_levels:
                    self.err("axion", self.rel(thermal_xml), f"CPU action references unknown level id={status}")
                if unit == "gpu" and status not in gpu_levels:
                    self.err("axion", self.rel(thermal_xml), f"GPU action references unknown level id={status}")

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
            if hidden and send_cb:
                self.err("thermal", self.rel(path), f"Sensor {name} has both Hidden and SendCallback")
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

        if visible_cb == 0:
            self.warn("thermal", self.rel(path), "No visible sensor enables SendCallback")

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

            if name.endswith("MinFreq") or name in ("MemFreq", "UclampTAMin", "UclampFgMin"):
                nums = [int(v) for v in vals]
                if nums != sorted(nums, reverse=True) or len(set(nums)) != len(nums):
                    self.err("powerhint", self.rel(path), f"Node {name} Values must be strictly descending")
            elif name.endswith("MaxFreq"):
                nums = [int(v) for v in vals]
                if node.get("DefaultIndex") != 0:
                    self.err("powerhint", self.rel(path), f"MaxFreq node {name} must have DefaultIndex=0")
                if nums != sorted(nums, reverse=True) or len(set(nums)) != len(nums):
                    self.err("powerhint", self.rel(path), f"MaxFreq node {name} Values must be strictly descending")
            elif name == "GpuPwrLevel":
                nums = [int(v) for v in vals]
                if nums != sorted(nums) or len(set(nums)) != len(nums):
                    self.err("powerhint", self.rel(path), "GpuPwrLevel Values must be strictly ascending OPP indices")

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

        for nname in nodes:
            if nname not in used_nodes:
                self.warn("powerhint", self.rel(path), f"Unused Node {nname}")

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

        apply_sh = self.root / "axion/apply-patches.sh"
        if apply_sh.exists():
            txt = apply_sh.read_text(encoding="utf-8")
            for m in re.finditer(r'"\$DT/([^"]+\.patch)"', txt):
                patch_path = self.root / m.group(1)
                if not patch_path.exists():
                    self.err("patches", self.rel(apply_sh), f"Referenced patch missing: {m.group(1)}")

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

        if "debug.renderengine.backend" in all_props.get("vendor.prop", {}):
            self.err(
                "props",
                "vendor.prop",
                "debug.renderengine.backend belongs in product.prop (overridden by AxionOS defaults_common.prop)",
            )
        re_backend = all_props.get("product.prop", {}).get("debug.renderengine.backend", "")
        hwui_backend = all_props.get("product.prop", {}).get("debug.hwui.renderer", "")
        if re_backend.startswith("skiavk") and hwui_backend and not hwui_backend.startswith("skiavk"):
            self.err("props", "product.prop", f"Mixed RenderEngine ({re_backend}) and HWUI ({hwui_backend}) backends")

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

    def run_all(self) -> None:
        self.check_xml_files()
        self.check_json_files()
        self.check_android_bp_and_patches()
        self.check_init_and_fstab()
        self.check_props()
        self.check_sepolicy()
        self.check_xiaomi_parts()


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
