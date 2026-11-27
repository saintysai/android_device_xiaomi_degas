/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.touch

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.UserHandle
import android.provider.Settings
import android.service.quicksettings.TileService
import android.util.Log
import android.view.Display

class TouchGestureService : Service() {
    private lateinit var workerThread: HandlerThread
    private lateinit var workerHandler: Handler
    private lateinit var displayManager: DisplayManager
    private var lastWasOn = true

    private val settingsObserver by lazy {
        object : ContentObserver(workerHandler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = applyDoubleTapSetting()
        }
    }

    private val configReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = intent.action ?: return
                workerHandler.post {
                    when (action) {
                        TouchTuningController.ACTION_CONFIG_CHANGED -> {
                            TouchPollingController.apply(this@TouchGestureService)
                            TouchTuningController.apply(this@TouchGestureService)
                        }
                        TouchTuningController.ACTION_RESET_DEFAULTS -> {
                            TouchTuningController.applyResetDefaults(this@TouchGestureService)
                        }
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
                val isOn = getDisplayState() == Display.STATE_ON
                if (isOn == lastWasOn) return
                lastWasOn = isOn
                if (!isOn) {
                    applyDoubleTapSetting()
                } else {
                    if (TouchPollingController.isEnabled(this@TouchGestureService)) {
                        TouchPollingController.apply(this@TouchGestureService)
                    }
                    if (TouchTuningController.isGameModeEnabled(this@TouchGestureService)) {
                        TouchTuningController.apply(this@TouchGestureService)
                    }
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        if (DEBUG) Log.d(TAG, "onCreate")
        workerThread = HandlerThread(TAG).apply { start() }
        workerHandler = Handler(workerThread.looper)
        displayManager = getSystemService(DisplayManager::class.java)
        lastWasOn = getDisplayState() == Display.STATE_ON

        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.DOUBLE_TAP_TO_WAKE),
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        val filter =
            IntentFilter().apply {
                addAction(TouchTuningController.ACTION_CONFIG_CHANGED)
                addAction(TouchTuningController.ACTION_RESET_DEFAULTS)
            }
        registerReceiver(configReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        displayManager.registerDisplayListener(displayListener, workerHandler)

        workerHandler.post {
            applyDoubleTapSetting()
            if (TouchPollingController.isEnabled(this)) {
                TouchPollingController.apply(this)
            }
            if (TouchTuningController.isGameModeEnabled(this)) {
                TouchTuningController.apply(this)
            }
            runCatching {
                TileService.requestListeningState(
                    this,
                    ComponentName(this, TouchPollingTileService::class.java),
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(settingsObserver)
        unregisterReceiver(configReceiver)
        displayManager.unregisterDisplayListener(displayListener)
        workerHandler.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun getDisplayState(): Int =
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: Display.STATE_UNKNOWN

    private fun applyDoubleTapSetting() {
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
