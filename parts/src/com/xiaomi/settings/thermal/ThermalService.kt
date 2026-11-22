/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.thermal

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.UserHandle
import android.util.Log
import android.view.Display

class ThermalService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var displayManager: DisplayManager
    private var isCharging = false
    private var isScreenOn = true

    private val eventReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_POWER_CONNECTED -> {
                        if (!isCharging) {
                            isCharging = true
                            apply(force = false)
                        }
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        if (isCharging) {
                            isCharging = false
                            apply(force = false)
                        }
                    }
                    ThermalController.ACTION_CONFIG_CHANGED -> {
                        apply(force = true)
                    }
                }
            }
        }

    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}

            override fun onDisplayRemoved(displayId: Int) {}

            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val screenOn = queryScreenOn()
                if (screenOn == isScreenOn) return
                isScreenOn = screenOn
                if (isCharging && ThermalController.isSmartChargingThermalEnabled(this@ThermalService)) {
                    apply(force = false)
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        if (DEBUG) Log.d(TAG, "onCreate")
        displayManager = getSystemService(DisplayManager::class.java)
        isScreenOn = queryScreenOn()
        isCharging = queryCharging()

        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(ThermalController.ACTION_CONFIG_CHANGED)
            }
        registerReceiver(eventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        displayManager.registerDisplayListener(displayListener, handler)

        apply(force = true)
        // Re-assert once after boot in case mi_thermald finishes arming its inotify watch late.
        handler.postDelayed({ apply(force = true) }, BOOT_REASSERT_DELAY_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(eventReceiver)
        displayManager.unregisterDisplayListener(displayListener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun queryScreenOn(): Boolean =
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON

    private fun queryCharging(): Boolean {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return plugged != 0
    }

    private fun apply(force: Boolean) {
        ThermalController.applyEffectiveProfile(this, isCharging, isScreenOn, force)
    }

    companion object {
        private const val TAG = "ThermalService"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)
        private const val BOOT_REASSERT_DELAY_MS = 10_000L

        fun startService(context: Context) {
            context.startServiceAsUser(
                Intent(context, ThermalService::class.java),
                UserHandle.CURRENT,
            )
        }
    }
}
