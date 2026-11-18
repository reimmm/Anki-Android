// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.cardviewer

import android.content.Context
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.settings.Prefs
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class CardAudioFocusManagerTest : RobolectricTest() {
    private lateinit var audioManager: AudioManager

    @Before
    fun setUpManager() {
        CardAudioFocusManager.resetForTesting()
        audioManager = targetContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    @After
    fun tearDownManager() {
        CardAudioFocusManager.stopContinuousDucking()
        CardAudioFocusManager.resetForTesting()
    }

    @Test
    fun `continuous ducking is enabled by default and holds focus across playback completion`() {
        assertTrue(Prefs.duckBackgroundAudio)

        CardAudioFocusManager.startContinuousDucking()
        assertTrue(CardAudioFocusManager.isContinuousDuckingActive)
        val shadow = shadowOf(audioManager)
        assertNotNull(shadow.lastAudioFocusRequest)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, shadow.lastAudioFocusRequest.durationHint)
        assertNull(shadow.lastAbandonedAudioFocusRequest)

        CardAudioFocusManager.requestFocusForPlayback()
        CardAudioFocusManager.abandonFocusAfterPlayback()
        assertTrue(CardAudioFocusManager.isContinuousDuckingActive)
        assertNull(shadow.lastAbandonedAudioFocusRequest)

        CardAudioFocusManager.stopContinuousDucking()
        assertFalse(CardAudioFocusManager.isContinuousDuckingActive)
        assertNotNull(shadow.lastAbandonedAudioFocusRequest)
    }

    @Test
    fun `when continuous ducking is disabled focus is abandoned after playback`() {
        Prefs.duckBackgroundAudio = false

        CardAudioFocusManager.startContinuousDucking()
        assertFalse(CardAudioFocusManager.isContinuousDuckingActive)

        val shadow = shadowOf(audioManager)
        assertNull(shadow.lastAudioFocusRequest)

        CardAudioFocusManager.requestFocusForPlayback()
        assertNotNull(shadow.lastAudioFocusRequest)

        CardAudioFocusManager.abandonFocusAfterPlayback()
        assertNotNull(shadow.lastAbandonedAudioFocusRequest)
    }
}
