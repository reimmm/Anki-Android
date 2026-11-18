// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.cardviewer

import android.content.Context
import android.media.AudioManager
import androidx.annotation.CheckResult
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.audio.AudioFocusRequestCompat
import androidx.media3.common.audio.AudioManagerCompat
import androidx.media3.common.util.UnstableApi
import com.ichi2.anki.common.android.appContext
import com.ichi2.anki.settings.Prefs
import timber.log.Timber

/**
 * Coordinates audio focus for card media playback and optional continuous background music ducking
 * while a study or preview screen is active in the foreground.
 */
@OptIn(UnstableApi::class)
object CardAudioFocusManager {
    private val audioManager: AudioManager
        get() = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // the same instance of an AudioFocusRequestCompat must be used to cancel focus
    private val audioFocusRequest: AudioFocusRequestCompat by lazy {
        AudioFocusRequestCompat
            .Builder(AudioManagerCompat.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setOnAudioFocusChangeListener { }
            .build()
    }

    @Volatile
    var isContinuousDuckingActive: Boolean = false
        private set

    fun startContinuousDucking() {
        if (!Prefs.duckBackgroundAudio) {
            stopContinuousDucking()
            return
        }
        if (isContinuousDuckingActive) return
        Timber.d("Starting continuous background audio ducking")
        val result = requestAudioFocus()
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            isContinuousDuckingActive = true
        } else {
            Timber.w("Failed to acquire continuous ducking audio focus: %d", result)
        }
    }

    fun stopContinuousDucking() {
        if (!isContinuousDuckingActive) return
        Timber.d("Stopping continuous background audio ducking")
        isContinuousDuckingActive = false
        abandonAudioFocus()
    }

    @CheckResult
    fun requestFocusForPlayback(): Int = requestAudioFocus()

    fun abandonFocusAfterPlayback(): Int {
        if (isContinuousDuckingActive) {
            Timber.d("Keeping audio focus held for continuous ducking")
            return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        return abandonAudioFocus()
    }

    private fun requestAudioFocus(): Int {
        Timber.d("Requesting audio focus")
        return AudioManagerCompat.requestAudioFocus(audioManager, audioFocusRequest)
    }

    private fun abandonAudioFocus(): Int {
        Timber.d("Abandoning audio focus")
        return AudioManagerCompat.abandonAudioFocusRequest(audioManager, audioFocusRequest)
    }

    @VisibleForTesting
    internal fun resetForTesting() {
        isContinuousDuckingActive = false
    }
}
