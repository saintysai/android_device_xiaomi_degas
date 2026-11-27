/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.thermal

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import android.util.Log
import com.xiaomi.settings.R
import java.io.File

object ThermalController {
    private const val TAG = "ThermalController"
    private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

    private const val PREFS_NAME = "xiaomi_parts_prefs"
    private const val PER_APP_PREFS_NAME = "xiaomi_parts_per_app_thermal"
    private const val KEY_THERMAL_PROFILE = "thermal_profile"
    private const val KEY_SMART_CHARGING_THERMAL = "smart_charging_thermal"
    private const val KEY_PER_APP_ENABLED = "per_app_thermal_enabled"

    private const val SCONFIG_NODE = "/sys/class/thermal/thermal_message/sconfig"

    const val ACTION_CONFIG_CHANGED = "com.xiaomi.settings.thermal.CONFIG_CHANGED"

    private const val SCENARIO_CHARGE_SCREEN_OFF = 27
    private const val SCENARIO_CHARGE_SCREEN_ON = 0

    enum class ThermalProfile(
        val scenario: Int,
        val prefValue: String,
        val labelRes: Int,
        val isGlobal: Boolean,
    ) {
        DEFAULT(50, "50", R.string.thermal_profile_default, true),
        BALANCED(0, "0", R.string.thermal_profile_balanced, true),
        GAMING(18, "18", R.string.thermal_profile_gaming, true),
        PERFORMANCE(6, "6", R.string.thermal_profile_performance, true),
        CAMERA(15, "15", R.string.thermal_profile_camera, false),
        VIDEO(11, "11", R.string.thermal_profile_video, false),
        NAVIGATION(10, "10", R.string.thermal_profile_navigation, false),
        POWER_SAVER(5, "5", R.string.thermal_profile_power_saver, false);

        companion object {
            val globalEntries: List<ThermalProfile> = entries.filter { it.isGlobal }

            fun fromPrefValue(value: String?): ThermalProfile? =
                entries.find { it.prefValue == value }

            fun fromGlobalPrefValue(value: String?): ThermalProfile =
                globalEntries.find { it.prefValue == value } ?: PERFORMANCE

            fun nextGlobal(current: ThermalProfile): ThermalProfile {
                val idx = globalEntries.indexOf(current)
                return if (idx < 0) PERFORMANCE else globalEntries[(idx + 1) % globalEntries.size]
            }
        }
    }

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun perAppPrefs(context: Context) =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PER_APP_PREFS_NAME, Context.MODE_PRIVATE)

    fun isSupported(): Boolean = File(SCONFIG_NODE).exists()

    fun getSelectedProfile(context: Context): ThermalProfile =
        ThermalProfile.fromGlobalPrefValue(prefs(context).getString(KEY_THERMAL_PROFILE, null))

    fun setSelectedProfile(context: Context, profile: ThermalProfile) {
        val target = if (profile.isGlobal) profile else ThermalProfile.PERFORMANCE
        prefs(context).edit().putString(KEY_THERMAL_PROFILE, target.prefValue).apply()
        notifyConfigChanged(context)
        requestTileSync(context)
    }

    fun isSmartChargingThermalEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SMART_CHARGING_THERMAL, false)

    fun setSmartChargingThermalEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SMART_CHARGING_THERMAL, enabled).apply()
        notifyConfigChanged(context)
    }

    fun isPerAppEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PER_APP_ENABLED, false)

    fun setPerAppEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PER_APP_ENABLED, enabled).apply()
        notifyConfigChanged(context)
    }

    fun getAppProfile(context: Context, packageName: String): ThermalProfile? =
        ThermalProfile.fromPrefValue(perAppPrefs(context).getString(packageName, null))

    fun setAppProfile(context: Context, packageName: String, profile: ThermalProfile?) {
        val editor = perAppPrefs(context).edit()
        if (profile == null) {
            editor.remove(packageName)
        } else {
            editor.putString(packageName, profile.prefValue)
        }
        editor.apply()
        notifyConfigChanged(context)
    }

    fun getAllAppProfiles(context: Context): Map<String, ThermalProfile> {
        val result = HashMap<String, ThermalProfile>()
        for ((pkg, raw) in perAppPrefs(context).all) {
            val profile = ThermalProfile.fromPrefValue(raw as? String) ?: continue
            result[pkg] = profile
        }
        return result
    }

    fun getCustomizedAppCount(context: Context): Int = perAppPrefs(context).all.size

    fun clearAllAppProfiles(context: Context) {
        perAppPrefs(context).edit().clear().apply()
        notifyConfigChanged(context)
    }

    fun resolveScenario(
        context: Context,
        isCharging: Boolean,
        isScreenOn: Boolean,
        foregroundPackage: String? = null,
    ): Int {
        if (isCharging && isSmartChargingThermalEnabled(context)) {
            return if (isScreenOn) SCENARIO_CHARGE_SCREEN_ON else SCENARIO_CHARGE_SCREEN_OFF
        }
        if (!isScreenOn) {
            return getSelectedProfile(context).scenario
        }
        if (isPerAppEnabled(context) && !foregroundPackage.isNullOrEmpty()) {
            getAppProfile(context, foregroundPackage)?.let { return it.scenario }
        }
        return getSelectedProfile(context).scenario
    }

    fun applyEffectiveProfile(
        context: Context,
        isCharging: Boolean,
        isScreenOn: Boolean,
        foregroundPackage: String? = null,
        force: Boolean = false,
    ) {
        val target = resolveScenario(context, isCharging, isScreenOn, foregroundPackage)
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

    fun requestTileSync(context: Context) {
        runCatching {
            TileService.requestListeningState(
                context,
                ComponentName(context, ThermalTileService::class.java),
            )
        }
    }

    private fun notifyConfigChanged(context: Context) {
        context.sendBroadcast(Intent(ACTION_CONFIG_CHANGED).setPackage(context.packageName))
    }
}
