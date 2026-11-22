/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.speaker

import android.content.Context
import android.media.AudioAttributes
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    @Volatile private var workerThread: Thread? = null

    val isRunning: Boolean
        get() = running.get()

    fun start(
        onTick: (remainingSeconds: Int) -> Unit,
        onComplete: () -> Unit,
    ): Boolean {
        if (!running.compareAndSet(false, true)) return false

        val thread =
            Thread(
                {
                    val audioManager = context.getSystemService(AudioManager::class.java)
                    val previousVolume =
                        audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: -1
                    var track: AudioTrack? = null
                    try {
                        if (audioManager != null) {
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
                                .setAudioAttributes(
                                    AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                        .build()
                                )
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

                        track.play()

                        for (sec in DURATION_SECONDS downTo 1) {
                            if (!running.get()) break
                            mainHandler.post { onTick(sec) }
                            for (chunk in 0 until CHUNKS_PER_SECOND) {
                                if (!running.get()) break
                                val written =
                                    track.write(
                                        chunkPcm,
                                        0,
                                        chunkPcm.size,
                                        AudioTrack.WRITE_BLOCKING,
                                    )
                                if (written < 0) {
                                    Log.e(TAG, "AudioTrack write error: $written")
                                    running.set(false)
                                    break
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Speaker cleaning failed", e)
                    } finally {
                        runCatching {
                            track?.pause()
                            track?.flush()
                            track?.stop()
                            track?.release()
                        }
                        if (audioManager != null && previousVolume >= 0) {
                            runCatching {
                                audioManager.setStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    previousVolume,
                                    0,
                                )
                            }
                        }
                        running.set(false)
                        workerThread = null
                        mainHandler.post { onComplete() }
                    }
                },
                TAG,
            )
        workerThread = thread
        thread.start()
        return true
    }

    fun stop() {
        running.set(false)
        workerThread?.interrupt()
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
    }
}
