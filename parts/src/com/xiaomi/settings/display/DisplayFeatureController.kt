/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.display

import android.content.Context
import android.content.Intent
import android.util.Log

object DisplayFeatureController {
    private const val TAG = "DisplayFeatureController"
    private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

    private const val PREFS_NAME = "xiaomi_parts_prefs"
    private const val KEY_TRUE_TONE = "true_tone_enabled"

    const val ACTION_CONFIG_CHANGED = "com.xiaomi.settings.display.CONFIG_CHANGED"

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isTrueToneEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TRUE_TONE, false)

    fun setTrueToneEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_TRUE_TONE, enabled).apply()
        context.sendBroadcast(Intent(ACTION_CONFIG_CHANGED).setPackage(context.packageName))
    }

    fun applyTrueTone(context: Context) {
        val enabled = isTrueToneEnabled(context)
        if (DEBUG) Log.d(TAG, "applyTrueTone enabled=$enabled")
        DisplayFeatureWrapper.setFeature(
            DisplayFeatureWrapper.MODE_TRUE_TONE,
            if (enabled) 1 else 0,
            DisplayFeatureWrapper.COOKIE_DEFAULT,
        )
    }
}
