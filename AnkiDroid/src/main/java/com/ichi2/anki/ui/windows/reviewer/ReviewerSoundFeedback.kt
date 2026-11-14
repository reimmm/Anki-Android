// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ui.windows.reviewer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.common.utils.android.isRobolectric
import timber.log.Timber
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class ReviewerSoundFeedback {
    private val autoAdvanceOnTrack by lazy {
        createToneTrack(
            ToneSegment(frequencyHz = 523.25, durationMs = 55),
            ToneSegment(frequencyHz = 659.25, durationMs = 55),
            ToneSegment(frequencyHz = 783.99, durationMs = 95),
        )
    }

    private val autoAdvanceOffTrack by lazy {
        createToneTrack(
            ToneSegment(frequencyHz = 783.99, durationMs = 55),
            ToneSegment(frequencyHz = 659.25, durationMs = 55),
            ToneSegment(frequencyHz = 523.25, durationMs = 95),
        )
    }

    private val againTrack by lazy {
        createToneTrack(
            ToneSegment(frequencyHz = 329.63, durationMs = 65),
            ToneSegment(frequencyHz = 246.94, durationMs = 95),
        )
    }

    private val hardTrack by lazy {
        createToneTrack(ToneSegment(frequencyHz = 392.00, durationMs = 90))
    }

    private val goodTrack by lazy {
        createToneTrack(ToneSegment(frequencyHz = 587.33, durationMs = 90))
    }

    private val easyTrack by lazy {
        createToneTrack(
            ToneSegment(frequencyHz = 587.33, durationMs = 60),
            ToneSegment(frequencyHz = 880.00, durationMs = 95),
        )
    }

    fun playAutoAdvanceToggled(isEnabled: Boolean) {
        playTrack(if (isEnabled) autoAdvanceOnTrack else autoAdvanceOffTrack)
    }

    fun playAnswerFeedback(rating: Rating) {
        val track =
            when (rating) {
                Rating.AGAIN -> againTrack
                Rating.HARD -> hardTrack
                Rating.GOOD -> goodTrack
                Rating.EASY -> easyTrack
                Rating.UNRECOGNIZED -> return
            }
        playTrack(track)
    }

    fun release() {
        for (track in allTracks) {
            runCatching { track.release() }
        }
        allTracks.clear()
    }

    private val allTracks = mutableListOf<AudioTrack>()

    private fun playTrack(track: AudioTrack?) {
        if (track == null) return
        try {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.stop()
            }
            track.reloadStaticData()
            track.play()
        } catch (e: IllegalStateException) {
            Timber.w(e, "Failed to play reviewer feedback sound")
        }
    }

    private fun createToneTrack(vararg segments: ToneSegment): AudioTrack? {
        if (isRobolectric) return null
        val samples = synthesizeSegments(segments)
        val bufferSizeBytes = samples.size * Short.SIZE_BYTES
        val attributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        val format =
            AudioFormat
                .Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        return try {
            AudioTrack
                .Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bufferSizeBytes)
                .build()
                .apply {
                    if (state != AudioTrack.STATE_INITIALIZED) {
                        release()
                        return null
                    }
                    write(samples, 0, samples.size)
                    allTracks.add(this)
                }
        } catch (e: RuntimeException) {
            Timber.w(e, "Failed to initialize reviewer feedback AudioTrack")
            null
        }
    }

    private data class ToneSegment(
        val frequencyHz: Double,
        val durationMs: Int,
    )

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val AMPLITUDE = 0.22
        private const val FADE_MS = 8

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
