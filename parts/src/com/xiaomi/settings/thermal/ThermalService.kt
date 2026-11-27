/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.thermal

import android.app.ActivityTaskManager
import android.app.Service
import android.app.TaskStackListener
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.RemoteException
import android.os.UserHandle
import android.util.Log
import android.view.Display

class ThermalService : Service() {
    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler
    private lateinit var displayManager: DisplayManager
    private var isCharging = false
    private var isScreenOn = true
    private var foregroundPackage: String? = null
    private var taskListenerRegistered = false

    private val taskChangeRunnable = Runnable {
        if (!isScreenOn || !isPerAppActive()) return@Runnable
        val nextPkg = queryForegroundPackage()
        if (nextPkg == foregroundPackage) return@Runnable
        foregroundPackage = nextPkg
        if (DEBUG) Log.d(TAG, "foregroundPackage=$foregroundPackage")
        apply(force = false)
    }

    private val taskStackListener =
        object : TaskStackListener() {
            override fun onTaskStackChanged() {
                workerHandler.removeCallbacks(taskChangeRunnable)
                workerHandler.postDelayed(taskChangeRunnable, TASK_CHANGE_DEBOUNCE_MS)
            }
        }

    private val eventReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action ?: return
                workerHandler.post { handleAction(action) }
            }
        }

    private val packageReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return
                val pkg = intent.data?.schemeSpecificPart ?: return
                workerHandler.post {
                    if (ThermalController.getAppProfile(this@ThermalService, pkg) != null) {
                        ThermalController.setAppProfile(this@ThermalService, pkg, null)
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
                if (!screenOn) {
                    workerHandler.removeCallbacks(taskChangeRunnable)
                    foregroundPackage = null
                } else if (isPerAppActive()) {
                    foregroundPackage = queryForegroundPackage()
                }
                apply(force = false)
            }
        }

    override fun onCreate() {
        super.onCreate()
        if (DEBUG) Log.d(TAG, "onCreate")
        workerThread = HandlerThread(TAG).apply { start() }
        workerHandler = Handler(workerThread.looper)
        displayManager = getSystemService(DisplayManager::class.java)

        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(ThermalController.ACTION_CONFIG_CHANGED)
            }
        registerReceiver(eventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        val pkgFilter =
            IntentFilter(Intent.ACTION_PACKAGE_FULLY_REMOVED).apply { addDataScheme("package") }
        registerReceiver(packageReceiver, pkgFilter, Context.RECEIVER_NOT_EXPORTED)
        displayManager.registerDisplayListener(displayListener, workerHandler)

        workerHandler.post {
            isScreenOn = queryScreenOn()
            isCharging = queryCharging()
            updateTaskStackRegistration()
            if (isScreenOn && isPerAppActive()) {
                foregroundPackage = queryForegroundPackage()
            }
            apply(force = true)
            ThermalController.requestTileSync(this)
        }
        // Re-assert once after boot in case mi_thermald finishes arming its inotify watch late.
        workerHandler.postDelayed({ apply(force = true) }, BOOT_REASSERT_DELAY_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        unregisterReceiver(eventReceiver)
        unregisterReceiver(packageReceiver)
        displayManager.unregisterDisplayListener(displayListener)
        unregisterTaskStackListener()
        workerHandler.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleAction(action: String) {
        when (action) {
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
                updateTaskStackRegistration()
                foregroundPackage =
                    if (isScreenOn && isPerAppActive()) queryForegroundPackage() else null
                apply(force = true)
            }
        }
    }

    private fun isPerAppActive(): Boolean =
        ThermalController.isPerAppEnabled(this) && ThermalController.getCustomizedAppCount(this) > 0

    @Synchronized
    private fun updateTaskStackRegistration() {
        val shouldListen = isPerAppActive()
        if (shouldListen && !taskListenerRegistered) {
            try {
                ActivityTaskManager.getService().registerTaskStackListener(taskStackListener)
                taskListenerRegistered = true
                if (DEBUG) Log.d(TAG, "registered TaskStackListener")
            } catch (e: RemoteException) {
                Log.e(TAG, "failed to register TaskStackListener", e)
            }
        } else if (!shouldListen && taskListenerRegistered) {
            unregisterTaskStackListener()
        }
    }

    @Synchronized
    private fun unregisterTaskStackListener() {
        if (!taskListenerRegistered) return
        try {
            ActivityTaskManager.getService().unregisterTaskStackListener(taskStackListener)
        } catch (e: RemoteException) {
            Log.w(TAG, "failed to unregister TaskStackListener", e)
        } finally {
            taskListenerRegistered = false
        }
    }

    private fun queryForegroundPackage(): String? =
        runCatching { ActivityTaskManager.getService().focusedRootTaskInfo?.topActivity?.packageName }
            .onFailure { e -> if (DEBUG) Log.d(TAG, "queryForegroundPackage failed", e) }
            .getOrNull()

    private fun queryScreenOn(): Boolean =
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON

    private fun queryCharging(): Boolean {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return plugged != 0
    }

    private fun apply(force: Boolean) {
        ThermalController.applyEffectiveProfile(
            this,
            isCharging,
            isScreenOn,
            foregroundPackage,
            force,
        )
    }

    companion object {
        private const val TAG = "ThermalService"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)
        private const val BOOT_REASSERT_DELAY_MS = 10_000L
        private const val TASK_CHANGE_DEBOUNCE_MS = 100L

        fun startService(context: Context) {
            context.startServiceAsUser(
                Intent(context, ThermalService::class.java),
                UserHandle.CURRENT,
            )
        }
    }
}
