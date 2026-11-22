/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.thermal

import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

object ThermalController {
    private const val TAG = "ThermalController"
    private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

    private const val PREFS_NAME = "xiaomi_parts_prefs"
    private const val KEY_THERMAL_PROFILE = "thermal_profile"
    private const val KEY_SMART_CHARGING_THERMAL = "smart_charging_thermal"

    private const val SCONFIG_NODE = "/sys/class/thermal/thermal_message/sconfig"

    const val ACTION_CONFIG_CHANGED = "com.xiaomi.settings.thermal.CONFIG_CHANGED"

    private const val SCENARIO_CHARGE_SCREEN_OFF = 27
    private const val SCENARIO_CHARGE_SCREEN_ON = 0

    enum class ThermalProfile(val scenario: Int, val prefValue: String) {
        DEFAULT(50, "50"),
        BALANCED(0, "0"),
        PERFORMANCE(6, "6");

        companion object {
            fun fromPrefValue(value: String?): ThermalProfile =
                entries.find { it.prefValue == value } ?: DEFAULT
        }
    }

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isSupported(): Boolean = File(SCONFIG_NODE).exists()

    fun getSelectedProfile(context: Context): ThermalProfile =
        ThermalProfile.fromPrefValue(prefs(context).getString(KEY_THERMAL_PROFILE, null))

    fun setSelectedProfile(context: Context, profile: ThermalProfile) {
        prefs(context).edit().putString(KEY_THERMAL_PROFILE, profile.prefValue).apply()
        notifyConfigChanged(context)
    }

    fun isSmartChargingThermalEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SMART_CHARGING_THERMAL, false)

    fun setSmartChargingThermalEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SMART_CHARGING_THERMAL, enabled).apply()
        notifyConfigChanged(context)
    }

    fun resolveScenario(
        context: Context,
        isCharging: Boolean,
        isScreenOn: Boolean,
    ): Int {
        if (isCharging && isSmartChargingThermalEnabled(context)) {
            return if (isScreenOn) SCENARIO_CHARGE_SCREEN_ON else SCENARIO_CHARGE_SCREEN_OFF
        }
        return getSelectedProfile(context).scenario
    }

    fun applyEffectiveProfile(
        context: Context,
        isCharging: Boolean,
        isScreenOn: Boolean,
        force: Boolean = false,
    ) {
        val target = resolveScenario(context, isCharging, isScreenOn)
        writeScenario(target, force)
    }

    fun writeScenario(scenario: Int, force: Boolean = false): Boolean {
        val node = File(SCONFIG_NODE)
        if (!node.exists()) {
            if (DEBUG) Log.d(TAG, "$SCONFIG_NODE not present, skipping")
            return false
        }
        val targetStr = scenario.toString()
        if (!force) {
            val current = runCatching { node.readText().trim() }.getOrNull()
            if (current == targetStr) {
                return true
            }
        }
        if (DEBUG) Log.d(TAG, "writing sconfig=$targetStr (force=$force)")
        return runCatching { node.writeText(targetStr) }
            .onFailure { e -> Log.e(TAG, "failed to write sconfig=$targetStr", e) }
            .isSuccess
    }

    private fun notifyConfigChanged(context: Context) {
        context.sendBroadcast(Intent(ACTION_CONFIG_CHANGED).setPackage(context.packageName))
    }
}
