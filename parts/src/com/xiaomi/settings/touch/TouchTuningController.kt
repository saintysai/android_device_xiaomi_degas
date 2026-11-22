/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.touch

import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

object TouchTuningController {
    private const val TAG = "TouchTuningController"
    private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

    private const val PREFS_NAME = "xiaomi_parts_prefs"
    private const val KEY_GAME_MODE = "touch_game_mode_enabled"
    private const val KEY_UP_THRESHOLD = "touch_up_threshold"
    private const val KEY_TOLERANCE = "touch_tolerance"
    private const val KEY_EDGE_FILTER = "touch_edge_filter"

    const val ACTION_CONFIG_CHANGED = "com.xiaomi.settings.touch.CONFIG_CHANGED"
    const val ACTION_RESET_DEFAULTS = "com.xiaomi.settings.touch.RESET_DEFAULTS"

    data class ModeRange(val min: Int, val max: Int, val def: Int)

    // Verified factory defaults from degas dtbo xiaomi_touch@0 node.
    private val FALLBACK_RANGES =
        mapOf(
            TouchFeatureWrapper.MODE_TOUCH_UP_THRESHOLD to ModeRange(0, 4, 3),
            TouchFeatureWrapper.MODE_TOUCH_TOLERANCE to ModeRange(0, 4, 2),
            TouchFeatureWrapper.MODE_TOUCH_EDGE_FILTER to ModeRange(0, 3, 2),
        )

    private val cachedRanges = ConcurrentHashMap<Int, ModeRange>()

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getModeRange(mode: Int): ModeRange =
        cachedRanges.getOrPut(mode) {
            val fallback = FALLBACK_RANGES[mode] ?: ModeRange(0, 4, 2)
            val min = TouchFeatureWrapper.getModeMinValue(TouchFeatureWrapper.TOUCH_ID_PRIMARY, mode)
            val max = TouchFeatureWrapper.getModeMaxValue(TouchFeatureWrapper.TOUCH_ID_PRIMARY, mode)
            val def =
                TouchFeatureWrapper.getModeDefaultValue(TouchFeatureWrapper.TOUCH_ID_PRIMARY, mode)
            if (min != null && max != null && max > min) {
                val safeDef = (def ?: fallback.def).coerceIn(min, max)
                ModeRange(min, max, safeDef)
            } else {
                fallback
            }
        }

    fun isGameModeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GAME_MODE, false)

    fun setGameModeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_GAME_MODE, enabled).apply()
        notifyConfigChanged(context)
    }

    fun getUpThreshold(context: Context): Int =
        getSliderValue(context, KEY_UP_THRESHOLD, TouchFeatureWrapper.MODE_TOUCH_UP_THRESHOLD)

    fun setUpThreshold(context: Context, value: Int) =
        setSliderValue(context, KEY_UP_THRESHOLD, TouchFeatureWrapper.MODE_TOUCH_UP_THRESHOLD, value)

    fun getTolerance(context: Context): Int =
        getSliderValue(context, KEY_TOLERANCE, TouchFeatureWrapper.MODE_TOUCH_TOLERANCE)

    fun setTolerance(context: Context, value: Int) =
        setSliderValue(context, KEY_TOLERANCE, TouchFeatureWrapper.MODE_TOUCH_TOLERANCE, value)

    fun getEdgeFilter(context: Context): Int =
        getSliderValue(context, KEY_EDGE_FILTER, TouchFeatureWrapper.MODE_TOUCH_EDGE_FILTER)

    fun setEdgeFilter(context: Context, value: Int) =
        setSliderValue(context, KEY_EDGE_FILTER, TouchFeatureWrapper.MODE_TOUCH_EDGE_FILTER, value)

    fun requestResetDefaults(context: Context) {
        prefs(context)
            .edit()
            .remove(KEY_UP_THRESHOLD)
            .remove(KEY_TOLERANCE)
            .remove(KEY_EDGE_FILTER)
            .apply()
        context.sendBroadcast(Intent(ACTION_RESET_DEFAULTS).setPackage(context.packageName))
    }

    fun apply(context: Context) {
        val enabled = isGameModeEnabled(context)
        if (DEBUG) Log.d(TAG, "apply gameMode=$enabled")
        if (!enabled) {
            // Never call modeReset(0, 0); set MODE_TOUCH_GAME_MODE to 0 directly.
            TouchFeatureWrapper.setModeValue(
                TouchFeatureWrapper.TOUCH_ID_PRIMARY,
                TouchFeatureWrapper.MODE_TOUCH_GAME_MODE,
                0,
            )
            return
        }
        TouchFeatureWrapper.setModeValue(
            TouchFeatureWrapper.TOUCH_ID_PRIMARY,
            TouchFeatureWrapper.MODE_TOUCH_GAME_MODE,
            1,
        )
        applyMode(TouchFeatureWrapper.MODE_TOUCH_UP_THRESHOLD, getUpThreshold(context))
        applyMode(TouchFeatureWrapper.MODE_TOUCH_TOLERANCE, getTolerance(context))
        applyMode(TouchFeatureWrapper.MODE_TOUCH_EDGE_FILTER, getEdgeFilter(context))
    }

    fun applyResetDefaults(context: Context) {
        if (DEBUG) Log.d(TAG, "applyResetDefaults")
        // Only reset individual tuning modes (2, 3, 7); never mode 0.
        TouchFeatureWrapper.modeReset(
            TouchFeatureWrapper.TOUCH_ID_PRIMARY,
            TouchFeatureWrapper.MODE_TOUCH_UP_THRESHOLD,
        )
        TouchFeatureWrapper.modeReset(
            TouchFeatureWrapper.TOUCH_ID_PRIMARY,
            TouchFeatureWrapper.MODE_TOUCH_TOLERANCE,
        )
        TouchFeatureWrapper.modeReset(
            TouchFeatureWrapper.TOUCH_ID_PRIMARY,
            TouchFeatureWrapper.MODE_TOUCH_EDGE_FILTER,
        )
        apply(context)
    }

    private fun getSliderValue(context: Context, key: String, mode: Int): Int {
        val range = getModeRange(mode)
        val raw = prefs(context).getInt(key, Int.MIN_VALUE)
        return if (raw == Int.MIN_VALUE) range.def else raw.coerceIn(range.min, range.max)
    }

    private fun setSliderValue(context: Context, key: String, mode: Int, value: Int) {
        val range = getModeRange(mode)
        prefs(context).edit().putInt(key, value.coerceIn(range.min, range.max)).apply()
        notifyConfigChanged(context)
    }

    private fun applyMode(mode: Int, value: Int) {
        val range = getModeRange(mode)
        TouchFeatureWrapper.setModeValue(
            TouchFeatureWrapper.TOUCH_ID_PRIMARY,
            mode,
            value.coerceIn(range.min, range.max),
        )
    }

    private fun notifyConfigChanged(context: Context) {
        context.sendBroadcast(Intent(ACTION_CONFIG_CHANGED).setPackage(context.packageName))
    }
}
