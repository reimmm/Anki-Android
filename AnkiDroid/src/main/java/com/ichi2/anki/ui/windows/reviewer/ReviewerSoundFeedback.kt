// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ui.windows.reviewer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.common.utils.android.isRobolectric
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class ReviewerSoundFeedback {
    private val autoAdvanceOnSamples by lazy {
        synthesizeSegments(
            arrayOf(
                ToneSegment(frequencyHz = 523.25, durationMs = 65),
                ToneSegment(frequencyHz = 659.25, durationMs = 65),
                ToneSegment(frequencyHz = 783.99, durationMs = 110),
            ),
        )
    }

    private val autoAdvanceOffSamples by lazy {
        synthesizeSegments(
            arrayOf(
                ToneSegment(frequencyHz = 783.99, durationMs = 65),
                ToneSegment(frequencyHz = 659.25, durationMs = 65),
                ToneSegment(frequencyHz = 523.25, durationMs = 110),
            ),
        )
    }

    private val againSamples by lazy {
        synthesizeSegments(
            arrayOf(
                ToneSegment(frequencyHz = 329.63, durationMs = 75),
                ToneSegment(frequencyHz = 246.94, durationMs = 115),
            ),
        )
    }

    private val hardSamples by lazy {
        synthesizeSegments(arrayOf(ToneSegment(frequencyHz = 392.00, durationMs = 120)))
    }

    private val goodSamples by lazy {
        synthesizeSegments(arrayOf(ToneSegment(frequencyHz = 587.33, durationMs = 120)))
    }

    private val easySamples by lazy {
        synthesizeSegments(
            arrayOf(
                ToneSegment(frequencyHz = 587.33, durationMs = 70),
                ToneSegment(frequencyHz = 880.00, durationMs = 115),
            ),
        )
    }

    private val minBufferBytes: Int by lazy {
        if (isRobolectric) {
            0
        } else {
            AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        }
    }

    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ReviewerSoundFeedback").apply { isDaemon = true }
        }

    @Volatile
    private var isReleased = false

    // Accessed exclusively on executor
    private var activeTrack: AudioTrack? = null

    fun playAutoAdvanceToggled(isEnabled: Boolean) {
        playSamples(if (isEnabled) autoAdvanceOnSamples else autoAdvanceOffSamples)
    }

    fun playAnswerFeedback(rating: Rating) {
        val samples =
            when (rating) {
                Rating.AGAIN -> againSamples
                Rating.HARD -> hardSamples
                Rating.GOOD -> goodSamples
                Rating.EASY -> easySamples
                Rating.UNRECOGNIZED -> return
            }
        playSamples(samples)
    }

    fun release() {
        isReleased = true
        try {
            executor.execute {
                releaseActiveTrack()
            }
        } catch (_: RejectedExecutionException) {
            // Executor already shut down
        }
        executor.shutdown()
    }

    private fun playSamples(samples: ShortArray) {
        if (isRobolectric || isReleased) return
        try {
            executor.execute {
                if (isReleased) return@execute
                playOnAudioThread(samples)
            }
        } catch (e: RejectedExecutionException) {
            Timber.w(e, "Sound feedback executor rejected playback")
        }
    }

    private fun playOnAudioThread(samples: ShortArray) {
        releaseActiveTrack()
        val paddedSamples = padToMinBuffer(samples, minBufferBytes)
        val bufferSizeBytes = paddedSamples.size * Short.SIZE_BYTES
        val format =
            AudioFormat
                .Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        try {
            val track =
                AudioTrack
                    .Builder()
                    .setAudioAttributes(feedbackAudioAttributes)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(bufferSizeBytes)
                    .build()
            // MODE_STATIC starts in STATE_NO_STATIC_DATA and transitions to STATE_INITIALIZED after write().
            if (track.state == AudioTrack.STATE_UNINITIALIZED) {
                Timber.w("Reviewer feedback AudioTrack uninitialized")
                track.release()
                return
            }
            val written = track.write(paddedSamples, 0, paddedSamples.size)
            if (written <= 0 || track.state != AudioTrack.STATE_INITIALIZED) {
                Timber.w("Failed to load static samples into AudioTrack (written=%d, state=%d)", written, track.state)
                track.release()
                return
            }
            track.play()
            activeTrack = track
        } catch (e: RuntimeException) {
            Timber.w(e, "Failed to play reviewer feedback sound")
        }
    }

    private fun releaseActiveTrack() {
        activeTrack?.let { previous ->
            runCatching {
                if (previous.playState != AudioTrack.PLAYSTATE_STOPPED) {
                    previous.stop()
                }
            }
            runCatching { previous.release() }
        }
        activeTrack = null
    }

    private data class ToneSegment(
        val frequencyHz: Double,
        val durationMs: Int,
    )

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val AMPLITUDE = 0.60
        private const val FADE_MS = 8

        internal val feedbackAudioAttributes: AudioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

        internal fun padToMinBuffer(
            samples: ShortArray,
            minBufferSizeBytes: Int,
        ): ShortArray {
            if (minBufferSizeBytes <= 0) return samples
            val minSamples = (minBufferSizeBytes + Short.SIZE_BYTES - 1) / Short.SIZE_BYTES
            val targetSize = max(samples.size, minSamples)
            return if (targetSize == samples.size) samples else samples.copyOf(targetSize)
        }

        internal fun synthesizeToneSamples(
            frequencyHz: Double,
            durationMs: Int,
        ): ShortArray = synthesizeSegments(arrayOf(ToneSegment(frequencyHz, durationMs)))

        private fun synthesizeSegments(segments: Array<out ToneSegment>): ShortArray {
            val totalSamples = segments.sumOf { (SAMPLE_RATE * it.durationMs) / 1000 }
            val result = ShortArray(totalSamples)
            var offset = 0
            val fadeSamples = (SAMPLE_RATE * FADE_MS) / 1000
            for (segment in segments) {
                val count = (SAMPLE_RATE * segment.durationMs) / 1000
                val clampedFade = min(fadeSamples, count / 2)
                for (i in 0 until count) {
                    val attack = if (i < clampedFade) i.toDouble() / clampedFade else 1.0
                    val release = if (i >= count - clampedFade) (count - i).toDouble() / clampedFade else 1.0
                    val envelope = min(attack, release)
                    val angle = 2.0 * PI * segment.frequencyHz * i / SAMPLE_RATE
                    val sample = (sin(angle) * envelope * AMPLITUDE * Short.MAX_VALUE).roundToInt()
                    result[offset + i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
                offset += count
            }
            return result
        }
    }
}
