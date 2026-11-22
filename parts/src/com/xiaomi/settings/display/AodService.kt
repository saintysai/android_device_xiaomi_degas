/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.display

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.UserHandle
import android.util.Log
import android.view.Display
import com.xiaomi.settings.touch.TouchFeatureWrapper
import java.io.File

class AodService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var displayManager: DisplayManager
    private var wasDozing = false

    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}

            override fun onDisplayRemoved(displayId: Int) {}

            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val state = getDisplayState()
                val isDozing = state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND
                if (isDozing == wasDozing) return
                wasDozing = isDozing
                if (DEBUG) Log.d(TAG, "display state=$state dozing=$isDozing")
                handler.removeCallbacksAndMessages(null)
                setFodDetectionEnabled(isDozing)
                if (isDozing) {
                    handler.postDelayed({ applyDozeBrightness() }, ENTER_DELAY_MS)
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        if (DEBUG) Log.d(TAG, "onCreate")
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(displayListener, handler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        displayManager.unregisterDisplayListener(displayListener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun getDisplayState(): Int =
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: Display.STATE_UNKNOWN

    private fun setFodDetectionEnabled(enabled: Boolean) {
        TouchFeatureWrapper.setModeValue(
            TouchFeatureWrapper.TOUCH_ID_PRIMARY,
            TouchFeatureWrapper.MODE_TOUCH_FOD_ENABLE,
            if (enabled) 1 else 0,
        )
    }

    private fun applyDozeBrightness() {
        if (!wasDozing) return
        write(DOZE_BRIGHTNESS_LBM)
        handler.postDelayed(
            {
                if (wasDozing) write(DOZE_BRIGHTNESS_HBM)
            },
            STEP_DELAY_MS,
        )
    }

    private fun write(value: Int) {
        runCatching { File(DOZE_BRIGHTNESS_NODE).writeText(value.toString()) }
            .onFailure { e -> Log.e(TAG, "failed to write doze_brightness=$value", e) }
    }

    companion object {
        private const val TAG = "AodService"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

        private const val DOZE_BRIGHTNESS_NODE =
            "/sys/class/mi_display/disp-DSI-0/doze_brightness"

        private const val DOZE_BRIGHTNESS_HBM = 1
        private const val DOZE_BRIGHTNESS_LBM = 2

        private const val ENTER_DELAY_MS = 250L
        private const val STEP_DELAY_MS = 50L

        fun startService(context: Context) {
            context.startServiceAsUser(Intent(context, AodService::class.java), UserHandle.CURRENT)
        }
    }
}
