// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ui.windows.reviewer

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.annotation.RawRes
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.R
import com.ichi2.anki.common.utils.android.isRobolectric
import com.ichi2.anki.settings.Prefs
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class ReviewerSoundFeedback(
    context: Context,
    private val isGoodMuted: () -> Boolean = { Prefs.soundFeedbackMuteGood },
) {
    @Volatile
    private var isReleased = false

    private val loadedSoundIds = ConcurrentHashMap.newKeySet<Int>()

    private var soundPool: SoundPool? =
        if (isRobolectric) {
            null
        } else {
            SoundPool
                .Builder()
                .setMaxStreams(MAX_STREAMS)
                .setAudioAttributes(feedbackAudioAttributes)
                .build()
                .apply {
                    setOnLoadCompleteListener { _, sampleId, status ->
                        if (status == 0) {
                            loadedSoundIds.add(sampleId)
                        } else {
                            Timber.w("Failed to load feedback sound sample %d (status=%d)", sampleId, status)
                        }
                    }
                }
        }

    private val autoAdvanceOnSoundId: Int = loadSound(context, R.raw.sound_feedback_aa_on)
    private val autoAdvanceOffSoundId: Int = loadSound(context, R.raw.sound_feedback_aa_off)
    private val againSoundId: Int = loadSound(context, R.raw.sound_feedback_again)
    private val hardSoundId: Int = loadSound(context, R.raw.sound_feedback_hard)
    private val goodSoundId: Int = loadSound(context, R.raw.sound_feedback_good)
    private val easySoundId: Int = loadSound(context, R.raw.sound_feedback_easy)

    fun playAutoAdvanceToggled(isEnabled: Boolean) {
        playSound(if (isEnabled) autoAdvanceOnSoundId else autoAdvanceOffSoundId)
    }

    fun playAnswerFeedback(rating: Rating) {
        val soundId = soundIdForRating(rating) ?: return
        playSound(soundId)
    }

    internal fun soundIdForRating(rating: Rating): Int? =
        when (rating) {
            Rating.AGAIN -> againSoundId
            Rating.HARD -> hardSoundId
            Rating.GOOD -> if (isGoodMuted()) null else goodSoundId
            Rating.EASY -> easySoundId
            Rating.UNRECOGNIZED -> null
        }

    fun release() {
        isReleased = true
        loadedSoundIds.clear()
        runCatching { soundPool?.release() }
        soundPool = null
    }

    private fun loadSound(
        context: Context,
        @RawRes resId: Int,
    ): Int =
        if (isRobolectric || isReleased) {
            0
        } else {
            soundPool?.load(context, resId, 1) ?: 0
        }

    private fun playSound(soundId: Int) {
        if (isRobolectric || isReleased || soundId == 0 || !loadedSoundIds.contains(soundId)) return
        val rate = MIN_RATE + Random.nextFloat() * (MAX_RATE - MIN_RATE)
        soundPool?.play(soundId, 1.0f, 1.0f, 1, 0, rate)
    }

    companion object {
        private const val MAX_STREAMS = 2
        private const val MIN_RATE = 0.994f
        private const val MAX_RATE = 1.006f

        internal val feedbackAudioAttributes: AudioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
    }
}
