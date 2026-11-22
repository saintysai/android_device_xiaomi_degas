/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.speaker

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.round
import kotlin.math.sin

class SpeakerCleaner(private val context: Context) {
    enum class StartResult {
        STARTED,
        ALREADY_RUNNING,
        BLOCKED_BY_HEADSET,
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)
    @Volatile private var workerThread: Thread? = null
    @Volatile private var activeTrack: AudioTrack? = null

    val isRunning: Boolean
        get() = running.get()

    fun start(
        onTick: (remainingSeconds: Int) -> Unit,
        onComplete: () -> Unit,
    ): StartResult {
        val audioManager = context.getSystemService(AudioManager::class.java)
        if (audioManager != null && hasExternalHeadset(audioManager)) {
            return StartResult.BLOCKED_BY_HEADSET
        }
        if (!running.compareAndSet(false, true)) return StartResult.ALREADY_RUNNING
        cancelRequested.set(false)

        val thread =
            Thread(
                {
                    val builtinSpeaker =
                        audioManager
                            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    val attributes =
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    val focusRequest =
                        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(attributes)
                            .setOnAudioFocusChangeListener {}
                            .build()
                    val previousVolume =
                        audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
                    var focusGranted = false
                    var track: AudioTrack? = null
                    try {
                        if (audioManager != null) {
                            focusGranted =
                                audioManager.requestAudioFocus(focusRequest) ==
                                    AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)
                        }

                        val chunkPcm = buildToneChunk()
                        val minBuf =
                            AudioTrack.getMinBufferSize(
                                SAMPLE_RATE,
                                AudioFormat.CHANNEL_OUT_MONO,
                                AudioFormat.ENCODING_PCM_16BIT,
                            )
                        val bufferBytes = maxOf(minBuf, chunkPcm.size * 2)

                        track =
                            AudioTrack.Builder()
                                .setAudioAttributes(attributes)
                                .setAudioFormat(
                                    AudioFormat.Builder()
                                        .setSampleRate(SAMPLE_RATE)
                                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                        .build()
                                )
                                .setBufferSizeInBytes(bufferBytes)
                                .setTransferMode(AudioTrack.MODE_STREAM)
                                .build()
                        if (builtinSpeaker != null) {
                            track.setPreferredDevice(builtinSpeaker)
                        }
                        activeTrack = track
                        if (cancelRequested.get()) return@Thread

                        track.play()

                        for (sec in DURATION_SECONDS downTo 1) {
                            if (cancelRequested.get()) break
                            mainHandler.post { onTick(sec) }
                            for (chunk in 0 until CHUNKS_PER_SECOND) {
                                if (cancelRequested.get()) break
                                val written =
                                    track.write(
                                        chunkPcm,
                                        0,
                                        chunkPcm.size,
                                        AudioTrack.WRITE_BLOCKING,
                                    )
                                if (written < 0) {
                                    if (!cancelRequested.get()) {
                                        Log.e(TAG, "AudioTrack write error: $written")
                                    }
                                    cancelRequested.set(true)
                                    break
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (!cancelRequested.get()) {
                            Log.e(TAG, "Speaker cleaning failed", e)
                        }
                    } finally {
                        activeTrack = null
                        runCatching {
                            track?.pause()
                            track?.flush()
                            track?.stop()
                            track?.release()
                        }
                        if (audioManager != null) {
                            if (previousVolume >= 0) {
                                runCatching {
                                    audioManager.setStreamVolume(
                                        AudioManager.STREAM_MUSIC,
                                        previousVolume,
                                        0,
                                    )
                                }
                            }
                            if (focusGranted) {
                                runCatching { audioManager.abandonAudioFocusRequest(focusRequest) }
                            }
                        }
                        workerThread = null
                        running.set(false)
                        mainHandler.post { onComplete() }
                    }
                },
                TAG,
            )
        workerThread = thread
        thread.start()
        return StartResult.STARTED
    }

    fun stop() {
        cancelRequested.set(true)
        val track = activeTrack
        runCatching {
            track?.pause()
            track?.flush()
            track?.stop()
        }
        workerThread?.interrupt()
    }

    private fun hasExternalHeadset(audioManager: AudioManager): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type in EXTERNAL_HEADSET_TYPES
        }

    private fun buildToneChunk(): ShortArray {
        val count = SAMPLE_RATE / CHUNKS_PER_SECOND
        val samples = ShortArray(count)
        // Integer number of full cycles per chunk avoids phase clicks between writes.
        val cycles = round(TONE_FREQ_HZ / CHUNKS_PER_SECOND).coerceAtLeast(1.0)
        val twoPiCycles = 2.0 * PI * cycles / count
        for (i in 0 until count) {
            val value = sin(twoPiCycles * i) * AMPLITUDE
            samples[i] =
                value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return samples
    }

    companion object {
        private const val TAG = "SpeakerCleaner"
        private const val SAMPLE_RATE = 44100
        private const val CHUNKS_PER_SECOND = 10
        private const val TONE_FREQ_HZ = 170.0
        private const val DURATION_SECONDS = 30
        private const val AMPLITUDE = 28000.0

        private val EXTERNAL_HEADSET_TYPES =
            setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_HEARING_AID,
            )
    }
}
