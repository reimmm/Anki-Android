// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ui.windows.reviewer

import android.content.Context
import android.media.AudioAttributes
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.R
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ReviewerSoundFeedbackTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `feedback audio uses media stream attributes`() {
        val attributes = ReviewerSoundFeedback.feedbackAudioAttributes
        assertEquals(AudioAttributes.USAGE_MEDIA, attributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_MUSIC, attributes.contentType)
    }

    @Test
    fun `all raw sound feedback wav resources are present and valid`() {
        val rawIds =
            listOf(
                R.raw.sound_feedback_good,
                R.raw.sound_feedback_hard,
                R.raw.sound_feedback_easy,
                R.raw.sound_feedback_again,
                R.raw.sound_feedback_aa_on,
                R.raw.sound_feedback_aa_off,
            )
        for (resId in rawIds) {
            val bytes = context.resources.openRawResource(resId).use { it.readBytes() }
            assertTrue(bytes.size > 44, "WAV resource $resId is unexpectedly small")
            assertEquals("RIFF", bytes.copyOfRange(0, 4).decodeToString())
            assertEquals("WAVE", bytes.copyOfRange(8, 12).decodeToString())
        }
    }

    @Test
    fun `good answer feedback is skipped only when muted`() {
        val unmuted = ReviewerSoundFeedback(context, isGoodMuted = { false })
        assertNotNull(unmuted.soundIdForRating(Rating.AGAIN))
        assertNotNull(unmuted.soundIdForRating(Rating.HARD))
        assertNotNull(unmuted.soundIdForRating(Rating.GOOD))
        assertNotNull(unmuted.soundIdForRating(Rating.EASY))
        assertNull(unmuted.soundIdForRating(Rating.UNRECOGNIZED))

        val muted = ReviewerSoundFeedback(context, isGoodMuted = { true })
        assertNotNull(muted.soundIdForRating(Rating.AGAIN))
        assertNotNull(muted.soundIdForRating(Rating.HARD))
        assertNull(muted.soundIdForRating(Rating.GOOD))
        assertNotNull(muted.soundIdForRating(Rating.EASY))
        assertNull(muted.soundIdForRating(Rating.UNRECOGNIZED))

        unmuted.release()
        muted.release()
    }

    @Test
    fun `playback and release are safe under robolectric`() {
        val feedback = ReviewerSoundFeedback(context)
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
