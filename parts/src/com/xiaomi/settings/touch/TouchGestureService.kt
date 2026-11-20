/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.touch

import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.Display

class TouchGestureService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    private val settingsObserver =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = applySetting()
        }

    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}

            override fun onDisplayRemoved(displayId: Int) {}

            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return

                if (getDisplayState() != Display.STATE_ON) applySetting()
            }
        }

    override fun onCreate() {
        super.onCreate()
        if (DEBUG) Log.d(TAG, "onCreate")
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.DOUBLE_TAP_TO_WAKE),
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        getSystemService(DisplayManager::class.java)
            .registerDisplayListener(displayListener, handler)
        applySetting()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(settingsObserver)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun getDisplayState(): Int =
        getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
            ?.state ?: Display.STATE_UNKNOWN

    private fun applySetting() {
        val enabled =
            Settings.Secure.getIntForUser(
                contentResolver,
                Settings.Secure.DOUBLE_TAP_TO_WAKE,
                0,
                UserHandle.USER_CURRENT,
            ) != 0
        if (DEBUG) Log.d(TAG, "double tap to wake enabled=$enabled")
        TouchFeatureWrapper.setModeValue(
            TouchFeatureWrapper.TOUCH_ID_PRIMARY,
            TouchFeatureWrapper.MODE_TOUCH_DOUBLETAP,
            if (enabled) 1 else 0,
        )
    }

    companion object {
        private const val TAG = "TouchGestureService"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

        fun startService(context: Context) {
            context.startServiceAsUser(
                Intent(context, TouchGestureService::class.java),
                UserHandle.CURRENT,
            )
        }
    }
}
