// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ui.windows.reviewer

import android.media.AudioAttributes
import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.scheduler.CardAnswer.Rating
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ReviewerSoundFeedbackTest {
    @Test
    fun `feedback audio uses media stream attributes`() {
        val attributes = ReviewerSoundFeedback.feedbackAudioAttributes
        assertEquals(AudioAttributes.USAGE_MEDIA, attributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_MUSIC, attributes.contentType)
    }

    @Test
    fun `synthesizeToneSamples produces expected length and faded edges`() {
        val samples = ReviewerSoundFeedback.synthesizeToneSamples(frequencyHz = 440.0, durationMs = 100)
        assertEquals(4410, samples.size)
        assertEquals(0, samples.first().toInt())
        assertTrue(abs(samples.last().toInt()) < 100)
        assertTrue(samples.maxOf { abs(it.toInt()) } > 1000)
    }

    @Test
    fun `padToMinBuffer pads short arrays with silence without altering larger buffers`() {
        val shortSamples = shortArrayOf(100, -100, 200)
        val padded = ReviewerSoundFeedback.padToMinBuffer(shortSamples, minBufferSizeBytes = 10)
        assertEquals(5, padded.size)
        assertEquals(100, padded[0].toInt())
        assertEquals(-100, padded[1].toInt())
        assertEquals(200, padded[2].toInt())
        assertEquals(0, padded[3].toInt())
        assertEquals(0, padded[4].toInt())

        val unpadded = ReviewerSoundFeedback.padToMinBuffer(shortSamples, minBufferSizeBytes = 4)
        assertEquals(3, unpadded.size)
    }

    @Test
    fun `playback and release are safe under robolectric`() {
        val feedback = ReviewerSoundFeedback()
        feedback.playAutoAdvanceToggled(true)
        feedback.playAutoAdvanceToggled(false)
        feedback.playAnswerFeedback(Rating.AGAIN)
        feedback.playAnswerFeedback(Rating.HARD)
        feedback.playAnswerFeedback(Rating.GOOD)
        feedback.playAnswerFeedback(Rating.EASY)
        feedback.playAnswerFeedback(Rating.UNRECOGNIZED)
        feedback.release()
        feedback.playAnswerFeedback(Rating.GOOD)
    }
}
