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
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

class ReviewerSoundFeedback {
    private val autoAdvanceOnSamples by lazy { synthesizeNotes(AUTO_ADVANCE_ON_NOTES) }
    private val autoAdvanceOffSamples by lazy { synthesizeNotes(AUTO_ADVANCE_OFF_NOTES) }
    private val againSamples by lazy { synthesizeNotes(AGAIN_NOTES) }
    private val hardSamples by lazy { synthesizeNotes(HARD_NOTES) }
    private val goodSamples by lazy { synthesizeNotes(GOOD_NOTES) }
    private val easySamples by lazy { synthesizeNotes(EASY_NOTES) }

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

    internal enum class Timbre {
        GLASS_MARIMBA,
        MUTED_WOOD,
        SYNTH_SHIMMER,
        SYNTH_SETTLE,
    }

    internal data class NoteSpec(
        val startMs: Int,
        val durationMs: Int,
        val startFreqHz: Double,
        val endFreqHz: Double = startFreqHz,
        val decayMs: Double,
        val gain: Double = 1.0,
        val timbre: Timbre = Timbre.GLASS_MARIMBA,
    )

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val MASTER_DRIVE = 0.65
        private const val GLIDE_TAU_SECONDS = 0.020
        private const val PERCUSSIVE_ATTACK_SECONDS = 0.004
        private const val SYNTH_ATTACK_SECONDS = 0.010
        private const val RELEASE_SECONDS = 0.014

        // Muted downward double-tap (clearly "miss / retry" without harsh buzzer fatigue)
        internal val AGAIN_NOTES =
            listOf(
                NoteSpec(
                    startMs = 0,
                    durationMs = 75,
                    startFreqHz = 329.63,
                    endFreqHz = 293.66,
                    decayMs = 26.0,
                    gain = 1.0,
                    timbre = Timbre.MUTED_WOOD,
                ),
                NoteSpec(
                    startMs = 68,
                    durationMs = 125,
                    startFreqHz = 246.94,
                    endFreqHz = 207.65,
                    decayMs = 44.0,
                    gain = 1.05,
                    timbre = Timbre.MUTED_WOOD,
                ),
            )

        // Crisp single upward-pop marimba tap (clearly "pass / OK", modest & non-fatiguing)
        internal val HARD_NOTES =
            listOf(
                NoteSpec(
                    startMs = 0,
                    durationMs = 135,
                    startFreqHz = 466.16,
                    endFreqHz = 493.88,
                    decayMs = 42.0,
                    gain = 0.95,
                    timbre = Timbre.GLASS_MARIMBA,
                ),
            )

        // Bright 2-note ascending fifth chime (clear "Good")
        internal val GOOD_NOTES =
            listOf(
                NoteSpec(
                    startMs = 0,
                    durationMs = 130,
                    startFreqHz = 587.33,
                    decayMs = 45.0,
                    gain = 0.88,
                    timbre = Timbre.GLASS_MARIMBA,
                ),
                NoteSpec(
                    startMs = 52,
                    durationMs = 165,
                    startFreqHz = 880.00,
                    decayMs = 62.0,
                    gain = 0.92,
                    timbre = Timbre.GLASS_MARIMBA,
                ),
            )

        // Airy 3-note ascending octave shimmer (rewarding "Easy")
        internal val EASY_NOTES =
            listOf(
                NoteSpec(
                    startMs = 0,
                    durationMs = 120,
                    startFreqHz = 587.33,
                    decayMs = 42.0,
                    gain = 0.80,
                    timbre = Timbre.GLASS_MARIMBA,
                ),
                NoteSpec(
                    startMs = 42,
                    durationMs = 135,
                    startFreqHz = 739.99,
                    decayMs = 48.0,
                    gain = 0.82,
                    timbre = Timbre.GLASS_MARIMBA,
                ),
                NoteSpec(
                    startMs = 84,
                    durationMs = 175,
                    startFreqHz = 1174.66,
                    decayMs = 65.0,
                    gain = 0.85,
                    timbre = Timbre.GLASS_MARIMBA,
                ),
            )

        // Sleek rising quartal/add9 synth chime (F4 -> C5 -> G5 with chorus shimmer)
        internal val AUTO_ADVANCE_ON_NOTES =
            listOf(
                NoteSpec(
                    startMs = 0,
                    durationMs = 165,
                    startFreqHz = 277.18,
                    endFreqHz = 349.23,
                    decayMs = 72.0,
                    gain = 0.72,
                    timbre = Timbre.SYNTH_SHIMMER,
                ),
                NoteSpec(
                    startMs = 48,
                    durationMs = 175,
                    startFreqHz = 523.25,
                    decayMs = 82.0,
                    gain = 0.76,
                    timbre = Timbre.SYNTH_SHIMMER,
                ),
                NoteSpec(
                    startMs = 96,
                    durationMs = 210,
                    startFreqHz = 783.99,
                    decayMs = 98.0,
                    gain = 0.82,
                    timbre = Timbre.SYNTH_SHIMMER,
                ),
            )

        // Warm descending synth settle (G5 -> C5 -> F4 gentle power-down)
        internal val AUTO_ADVANCE_OFF_NOTES =
            listOf(
                NoteSpec(
                    startMs = 0,
                    durationMs = 145,
                    startFreqHz = 783.99,
                    decayMs = 55.0,
                    gain = 0.72,
                    timbre = Timbre.SYNTH_SETTLE,
                ),
                NoteSpec(
                    startMs = 48,
                    durationMs = 155,
                    startFreqHz = 523.25,
                    decayMs = 58.0,
                    gain = 0.76,
                    timbre = Timbre.SYNTH_SETTLE,
                ),
                NoteSpec(
                    startMs = 96,
                    durationMs = 185,
                    startFreqHz = 349.23,
                    endFreqHz = 311.13,
                    decayMs = 70.0,
                    gain = 0.82,
                    timbre = Timbre.SYNTH_SETTLE,
                ),
            )

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
        ): ShortArray =
            synthesizeNotes(
                listOf(
                    NoteSpec(
                        startMs = 0,
                        durationMs = durationMs,
                        startFreqHz = frequencyHz,
                        decayMs = max(20.0, durationMs * 0.45),
                    ),
                ),
            )

        internal fun synthesizeNotes(notes: List<NoteSpec>): ShortArray {
            val totalMs = notes.maxOfOrNull { it.startMs + it.durationMs } ?: 0
            val totalSamples = (SAMPLE_RATE * totalMs) / 1000
            if (totalSamples <= 0) return ShortArray(0)

            val mixBuffer = DoubleArray(totalSamples)
            for (note in notes) {
                val startSample = (SAMPLE_RATE * note.startMs) / 1000
                val count = (SAMPLE_RATE * note.durationMs) / 1000
                if (count <= 0) continue

                val attackSeconds =
                    when (note.timbre) {
                        Timbre.SYNTH_SHIMMER, Timbre.SYNTH_SETTLE -> SYNTH_ATTACK_SECONDS
                        Timbre.GLASS_MARIMBA, Timbre.MUTED_WOOD -> PERCUSSIVE_ATTACK_SECONDS
                    }
                val attackSamples = min((SAMPLE_RATE * attackSeconds).roundToInt(), count / 3)
                val releaseSamples = min((SAMPLE_RATE * RELEASE_SECONDS).roundToInt(), count / 3)
                val decaySeconds = max(0.005, note.decayMs / 1000.0)

                var phase = 0.0
                var chorusPhase = 0.0
                for (i in 0 until count) {
                    val sampleIndex = startSample + i
                    if (sampleIndex >= totalSamples) break

                    val t = i.toDouble() / SAMPLE_RATE
                    val instFreq =
                        if (note.startFreqHz == note.endFreqHz) {
                            note.startFreqHz
                        } else {
                            note.startFreqHz + (note.endFreqHz - note.startFreqHz) * (1.0 - exp(-t / GLIDE_TAU_SECONDS))
                        }
                    phase += 2.0 * PI * instFreq / SAMPLE_RATE
                    chorusPhase += 2.0 * PI * (instFreq * 2.004) / SAMPLE_RATE

                    val attackEnv =
                        if (attackSamples > 0 && i < attackSamples) {
                            0.5 * (1.0 - cos(PI * i / attackSamples))
                        } else {
                            1.0
                        }
                    val releaseEnv =
                        if (releaseSamples > 0 && i >= count - releaseSamples) {
                            0.5 * (1.0 + cos(PI * (i - (count - releaseSamples)) / releaseSamples))
                        } else {
                            1.0
                        }
                    val decayEnv = exp(-t / decaySeconds)
                    val envelope = attackEnv * releaseEnv * decayEnv

                    val waveform =
                        when (note.timbre) {
                            Timbre.GLASS_MARIMBA ->
                                sin(phase) +
                                    0.25 * sin(2.0 * phase) * exp(-t / (decaySeconds * 0.35)) +
                                    0.08 * sin(3.0 * phase) * exp(-t / (decaySeconds * 0.18))

                            Timbre.MUTED_WOOD ->
                                0.90 * sin(phase) +
                                    0.32 * sin(2.0 * phase) * exp(-t / (decaySeconds * 0.45)) +
                                    0.06 * sin(3.0 * phase) * exp(-t / (decaySeconds * 0.15))

                            Timbre.SYNTH_SHIMMER ->
                                0.85 * sin(phase) +
                                    0.20 * sin(2.0 * phase) * exp(-t / (decaySeconds * 0.65)) +
                                    0.11 * sin(chorusPhase) * exp(-t / (decaySeconds * 0.55))

                            Timbre.SYNTH_SETTLE ->
                                0.90 * sin(phase) +
                                    0.14 * sin(2.0 * phase) * exp(-t / (decaySeconds * 0.30))
                        }

                    mixBuffer[sampleIndex] += waveform * envelope * note.gain
                }
            }

            return ShortArray(totalSamples) { idx ->
                val limited = tanh(mixBuffer[idx] * MASTER_DRIVE)
                (limited * Short.MAX_VALUE)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
        }
    }
}
