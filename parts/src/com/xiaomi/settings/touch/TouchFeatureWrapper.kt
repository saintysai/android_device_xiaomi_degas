/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.touch

import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.ServiceManager
import android.util.Log

object TouchFeatureWrapper {
    private const val TAG = "TouchFeatureWrapper"
    private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

    private const val DESCRIPTOR = "vendor.xiaomi.hw.touchfeature.ITouchFeature"
    private const val SERVICE = "$DESCRIPTOR/default"

    private const val TRANSACTION_GET_MODE_CUR_VALUE = 1
    private const val TRANSACTION_SET_MODE_VALUE = 9

    const val TOUCH_ID_PRIMARY = 0

    const val MODE_TOUCH_GAME_MODE = 0
    const val MODE_TOUCH_REPORT_RATE = 9
    const val MODE_TOUCH_FOD_ENABLE = 10
    const val MODE_TOUCH_DOUBLETAP = 14

    @Volatile private var service: IBinder? = null

    private val deathRecipient =
        IBinder.DeathRecipient {
            if (DEBUG) Log.d(TAG, "serviceDied")
            service = null
        }

    @Synchronized
    private fun getService(): IBinder? =
        service
            ?: runCatching {
                Binder.allowBlocking(ServiceManager.waitForDeclaredService(SERVICE)).apply {
                    linkToDeath(deathRecipient, 0)
                }
            }
                .onSuccess { service = it }
                .onFailure { e -> Log.e(TAG, "getService failed!", e) }
                .getOrNull()

    fun setModeValue(touchId: Int, mode: Int, value: Int): Boolean =
        transact(TRANSACTION_SET_MODE_VALUE, touchId, mode, value) != null

    fun getModeCurValue(touchId: Int, mode: Int): Int? =
        transact(TRANSACTION_GET_MODE_CUR_VALUE, touchId, mode)

    private fun transact(code: Int, vararg args: Int): Int? {
        val binder =
            getService()
                ?: run {
                    Log.e(TAG, "touchfeature service is null!")
                    return null
                }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            args.forEach { data.writeInt(it) }
            if (!binder.transact(code, data, reply, 0)) {
                Log.e(TAG, "transact $code returned false")
                return null
            }
            reply.readException()
            reply.readInt()
        } catch (e: DeadObjectException) {
            Log.w(TAG, "touchfeature service died during transact $code", e)
            service = null
            null
        } catch (e: Exception) {
            Log.e(TAG, "transact $code failed!", e)
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
