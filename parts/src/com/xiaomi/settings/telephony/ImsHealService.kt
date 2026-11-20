/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.telephony

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.telephony.AccessNetworkConstants
import android.telephony.NetworkRegistrationInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.ims.ImsMmTelManager
import android.util.Log

class ImsHealService : Service() {
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler

    private var strikes = 0

    private var attempts = 0

    private var quietUntil = 0L

    private var globalModeSanitized = false
    private val healedSubIds = mutableSetOf<Int>()

    private val pollRunnable =
        object : Runnable {
            override fun run() {
                try {
                    check()
                } catch (e: Exception) {
                    Log.e(TAG, "check failed", e)
                }
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }

    override fun onCreate() {
        super.onCreate()
        if (DEBUG) Log.d(TAG, "onCreate")
        handlerThread = HandlerThread(TAG)
        handlerThread.start()
        handler = Handler(handlerThread.looper)
        handler.post { healAllowedNetworkTypes() }
        handler.postDelayed(pollRunnable, FIRST_POLL_DELAY_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        handlerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun check() {
        healAllowedNetworkTypes()

        val subId = SubscriptionManager.getDefaultVoiceSubscriptionId()
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            reset()
            return
        }

        if (hasImsNetwork()) {
            reset()
            return
        }

        val telephonyManager =
            getSystemService(TelephonyManager::class.java).createForSubscriptionId(subId)

        if (!isOnLteOrNr(telephonyManager)) {
            reset()
            return
        }

        if (telephonyManager.callState != TelephonyManager.CALL_STATE_IDLE) return

        val volteEnabled =
            try {
                ImsMmTelManager.createForSubscriptionId(subId).isAdvancedCallingSettingEnabled
            } catch (e: Exception) {
                if (DEBUG) Log.d(TAG, "cannot read VoLTE setting for sub $subId", e)
                return
            }
        if (!volteEnabled) {
            reset()
            return
        }

        strikes++
        if (DEBUG) Log.d(TAG, "no IMS network on LTE/NR, strike $strikes/$STRIKES_BEFORE_POKE")
        if (strikes < STRIKES_BEFORE_POKE) return

        val now = SystemClock.uptimeMillis()
        if (now < quietUntil) return
        if (attempts >= MAX_ATTEMPTS) {
            if (DEBUG) Log.d(TAG, "giving up after $attempts pokes")
            return
        }

        attempts++
        quietUntil = now + COOLDOWN_MS
        poke(telephonyManager)
    }

    private fun poke(telephonyManager: TelephonyManager) {
        Log.i(TAG, "IMS missing on LTE/NR, cycling radio power (attempt $attempts)")
        try {
            telephonyManager.setRadioPower(false)
        } catch (e: Exception) {
            Log.e(TAG, "cannot power the radio down", e)
            return
        }
        handler.postDelayed(
            {
                try {
                    telephonyManager.setRadioPower(true)
                } catch (e: Exception) {
                    Log.e(TAG, "cannot power the radio back up", e)
                }
            },
            POKE_GAP_MS,
        )
    }

    private fun reset() {
        if (strikes != 0 || attempts != 0) {
            if (DEBUG) Log.d(TAG, "healthy again, clearing counters")
        }
        strikes = 0
        attempts = 0
        quietUntil = 0L
    }

    private fun hasImsNetwork(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        return connectivityManager.allNetworks.any {
            connectivityManager
                .getNetworkCapabilities(it)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS) == true
        }
    }

    private fun isOnLteOrNr(telephonyManager: TelephonyManager): Boolean {
        val registrationInfo =
            telephonyManager.serviceState?.getNetworkRegistrationInfo(
                NetworkRegistrationInfo.DOMAIN_PS,
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
            ) ?: return false
        if (!registrationInfo.isRegistered) return false
        return when (registrationInfo.accessNetworkTechnology) {
            TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyManager.NETWORK_TYPE_LTE_CA,
            TelephonyManager.NETWORK_TYPE_NR -> true
            else -> false
        }
    }

    private fun healAllowedNetworkTypes() {
        if (!globalModeSanitized) {
            try {
                val rawModes = Settings.Global.getString(contentResolver, PREFERRED_NETWORK_MODE)
                if (!rawModes.isNullOrEmpty()) {
                    val parts = rawModes.split(",")
                    if (parts.any { it == "35" || it == "33" || it == "32" }) {
                        val sanitized =
                            parts.joinToString(",") {
                                if (it == "35" || it == "33" || it == "32") "26" else it
                            }
                        Settings.Global.putString(
                            contentResolver,
                            PREFERRED_NETWORK_MODE,
                            sanitized,
                        )
                        Log.i(TAG, "Sanitized $PREFERRED_NETWORK_MODE: $rawModes -> $sanitized")
                    }
                }
                globalModeSanitized = true
            } catch (e: Exception) {
                Log.w(TAG, "cannot sanitize $PREFERRED_NETWORK_MODE", e)
            }
        }

        val subManager = getSystemService(SubscriptionManager::class.java) ?: return
        val activeSubs =
            try {
                subManager.activeSubscriptionInfoList
            } catch (e: Exception) {
                Log.w(TAG, "cannot query active subscriptions", e)
                return
            } ?: return

        val baseTm = getSystemService(TelephonyManager::class.java) ?: return
        for (subInfo in activeSubs) {
            val subId = subInfo.subscriptionId
            if (!SubscriptionManager.isValidSubscriptionId(subId)) continue
            if (subId in healedSubIds) continue
            try {
                val tm = baseTm.createForSubscriptionId(subId)
                val userMask =
                    tm.getAllowedNetworkTypesForReason(
                        TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER
                    )
                if (userMask != 0L && (userMask and RAF_GSM) != 0L) {
                    healedSubIds.add(subId)
                    continue
                }

                val healedMask =
                    when {
                        userMask == 0L -> RAF_NR_LTE_GSM_WCDMA
                        (userMask and TelephonyManager.NETWORK_TYPE_BITMASK_NR) != 0L ->
                            RAF_NR_LTE_GSM_WCDMA
                        (userMask and TelephonyManager.NETWORK_TYPE_BITMASK_LTE) != 0L ->
                            RAF_LTE_GSM_WCDMA
                        else -> RAF_GSM_WCDMA
                    }
                Log.i(
                    TAG,
                    "Healing invalid user allowed network types for sub $subId: $userMask -> $healedMask",
                )
                tm.setAllowedNetworkTypesForReason(
                    TelephonyManager.ALLOWED_NETWORK_TYPES_REASON_USER,
                    healedMask,
                )
                healedSubIds.add(subId)
            } catch (e: Exception) {
                Log.w(TAG, "cannot heal allowed network types for sub $subId", e)
            }
        }
    }

    companion object {
        private const val TAG = "ImsHeal"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)
        private const val PREFERRED_NETWORK_MODE = "preferred_network_mode"

        private val RAF_GSM =
            TelephonyManager.NETWORK_TYPE_BITMASK_GSM or
                TelephonyManager.NETWORK_TYPE_BITMASK_GPRS or
                TelephonyManager.NETWORK_TYPE_BITMASK_EDGE

        private val RAF_WCDMA =
            TelephonyManager.NETWORK_TYPE_BITMASK_UMTS or
                TelephonyManager.NETWORK_TYPE_BITMASK_HSDPA or
                TelephonyManager.NETWORK_TYPE_BITMASK_HSUPA or
                TelephonyManager.NETWORK_TYPE_BITMASK_HSPA or
                TelephonyManager.NETWORK_TYPE_BITMASK_HSPAP

        private val RAF_GSM_WCDMA = RAF_GSM or RAF_WCDMA
        private val RAF_LTE_GSM_WCDMA =
            RAF_GSM_WCDMA or
                TelephonyManager.NETWORK_TYPE_BITMASK_LTE or
                TelephonyManager.NETWORK_TYPE_BITMASK_LTE_CA
        private val RAF_NR_LTE_GSM_WCDMA =
            RAF_LTE_GSM_WCDMA or TelephonyManager.NETWORK_TYPE_BITMASK_NR

        private const val POLL_INTERVAL_MS = 15_000L
        private const val STRIKES_BEFORE_POKE = 3

        private const val FIRST_POLL_DELAY_MS = 60_000L

        private const val POKE_GAP_MS = 3_000L

        private const val COOLDOWN_MS = 180_000L

        private const val MAX_ATTEMPTS = 3

        fun startService(context: Context) {
            context.startServiceAsUser(
                Intent(context, ImsHealService::class.java),
                UserHandle.CURRENT,
            )
        }
    }
}
