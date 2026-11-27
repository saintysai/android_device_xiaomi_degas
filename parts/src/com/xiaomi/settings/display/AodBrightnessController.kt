/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.display

import android.content.Context
import android.content.Intent

object AodBrightnessController {
    private const val PREFS_NAME = "xiaomi_parts_prefs"
    private const val KEY_AOD_BRIGHTNESS = "aod_brightness_mode"

    const val ACTION_AOD_CONFIG_CHANGED = "com.xiaomi.settings.display.CONFIG_CHANGED"

    enum class Mode(val prefValue: String) {
        AUTO("auto"),
        HIGH("high"),
        LOW("low");

        companion object {
            fun fromPrefValue(value: String?): Mode =
                entries.find { it.prefValue == value } ?: AUTO
        }
    }

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMode(context: Context): Mode =
        Mode.fromPrefValue(prefs(context).getString(KEY_AOD_BRIGHTNESS, null))

    fun setMode(context: Context, mode: Mode) {
        prefs(context).edit().putString(KEY_AOD_BRIGHTNESS, mode.prefValue).apply()
        context.sendBroadcast(Intent(ACTION_AOD_CONFIG_CHANGED).setPackage(context.packageName))
    }
}
