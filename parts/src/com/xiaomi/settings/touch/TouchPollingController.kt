/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.touch

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import android.util.Log

object TouchPollingController {
    private const val TAG = "TouchPollingController"
    private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

    private const val PREFS_NAME = "xiaomi_parts_prefs"
    private const val KEY_HIGH_TOUCH_POLLING = "high_touch_polling"

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HIGH_TOUCH_POLLING, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIGH_TOUCH_POLLING, enabled).apply()
        apply(context)
        runCatching {
            TileService.requestListeningState(
                context,
                ComponentName(context, TouchPollingTileService::class.java),
            )
        }
    }

    fun apply(context: Context): Boolean {
        val enabled = isEnabled(context)
        if (DEBUG) Log.d(TAG, "apply highTouchPolling=$enabled")
        return TouchFeatureWrapper.setModeValue(
            TouchFeatureWrapper.TOUCH_ID_PRIMARY,
            TouchFeatureWrapper.MODE_TOUCH_REPORT_RATE,
            if (enabled) 1 else 0,
        )
    }
}
