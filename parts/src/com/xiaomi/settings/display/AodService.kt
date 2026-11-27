/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.display

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.UserHandle
import android.util.Log
import android.view.Display
import com.xiaomi.settings.display.AodBrightnessController.Mode
import com.xiaomi.settings.touch.TouchFeatureWrapper
import java.io.File

class AodService : Service() {
    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler
    private lateinit var displayManager: DisplayManager
    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private var lightListenerRegistered = false
    @Volatile private var wasDozing = false

    private val lightSensorListener =
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val lux = event.values.firstOrNull() ?: return
                unregisterLightListener()
                workerHandler.removeCallbacks(lightTimeoutRunnable)
                if (!wasDozing) return
                if (DEBUG) Log.d(TAG, "auto AOD lux=$lux")
                applyBrightnessLevel(low = lux < AUTO_LOW_LUX_THRESHOLD)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

    private val lightTimeoutRunnable = Runnable {
        unregisterLightListener()
        if (wasDozing) {
            if (DEBUG) Log.d(TAG, "auto AOD light sensor timed out, falling back to high")
            applyBrightnessLevel(low = false)
        }
    }

    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}

            override fun onDisplayRemoved(displayId: Int) {}

            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val state = getDisplayState()
                val isDozing = isDozingState(state)
                if (isDozing == wasDozing) return
                wasDozing = isDozing
                if (DEBUG) Log.d(TAG, "display state=$state dozing=$isDozing")
                unregisterLightListener()
                workerHandler.removeCallbacksAndMessages(null)
                setFodDetectionEnabled(isDozing)
                if (isDozing) {
                    workerHandler.postDelayed({ applyDozeBrightness() }, ENTER_DELAY_MS)
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        if (DEBUG) Log.d(TAG, "onCreate")
        workerThread = HandlerThread(TAG).apply { start() }
        workerHandler = Handler(workerThread.looper)
        displayManager = getSystemService(DisplayManager::class.java)
        sensorManager = getSystemService(SensorManager::class.java)
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        wasDozing = isDozingState(getDisplayState())
        displayManager.registerDisplayListener(displayListener, workerHandler)
        if (wasDozing) {
            workerHandler.post {
                if (wasDozing) {
                    setFodDetectionEnabled(true)
                    applyDozeBrightness()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        displayManager.unregisterDisplayListener(displayListener)
        unregisterLightListener()
        workerHandler.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun getDisplayState(): Int =
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: Display.STATE_UNKNOWN

    private fun isDozingState(state: Int): Boolean =
        state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND

    private fun setFodDetectionEnabled(enabled: Boolean) {
        TouchFeatureWrapper.setModeValue(
            TouchFeatureWrapper.TOUCH_ID_PRIMARY,
            TouchFeatureWrapper.MODE_TOUCH_FOD_ENABLE,
            if (enabled) 1 else 0,
        )
    }

    private fun applyDozeBrightness() {
        if (!wasDozing) return
        when (AodBrightnessController.getMode(this)) {
            Mode.LOW -> applyBrightnessLevel(low = true)
            Mode.HIGH -> applyBrightnessLevel(low = false)
            Mode.AUTO -> requestAutoBrightnessSample()
        }
    }

    @Synchronized
    private fun requestAutoBrightnessSample() {
        val sm = sensorManager
        val sensor = lightSensor
        if (sm == null || sensor == null) {
            applyBrightnessLevel(low = false)
            return
        }
        unregisterLightListener()
        val registered =
            sm.registerListener(
                lightSensorListener,
                sensor,
                SensorManager.SENSOR_DELAY_FASTEST,
                workerHandler,
            )
        if (!registered) {
            applyBrightnessLevel(low = false)
            return
        }
        lightListenerRegistered = true
        workerHandler.postDelayed(lightTimeoutRunnable, LIGHT_SAMPLE_TIMEOUT_MS)
    }

    @Synchronized
    private fun unregisterLightListener() {
        if (lightListenerRegistered) {
            sensorManager?.unregisterListener(lightSensorListener)
            lightListenerRegistered = false
        }
    }

    private fun applyBrightnessLevel(low: Boolean) {
        if (!wasDozing) return
        if (low) {
            write(DOZE_BRIGHTNESS_LBM)
            return
        }
        // On fresh doze entry the driver caches doze_brightness=1 and drops redundant writes of 1;
        // stepping through LBM (2) forces the panel to emit the HBM (1) DSI command.
        write(DOZE_BRIGHTNESS_LBM)
        workerHandler.postDelayed(
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
        private const val LIGHT_SAMPLE_TIMEOUT_MS = 400L
        private const val AUTO_LOW_LUX_THRESHOLD = 20f

        fun startService(context: Context) {
            context.startServiceAsUser(Intent(context, AodService::class.java), UserHandle.CURRENT)
        }
    }
}
