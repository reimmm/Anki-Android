// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ui.windows.reviewer.autoadvance

import com.ichi2.anki.asyncIO
import com.ichi2.anki.libanki.Card
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Implementation of the `Auto Advance` deck options
 *
 * A timer (in seconds) can be set to automatically trigger an action after it runs out,
 * either in the question side ([QuestionAction]) or in the answer side ([AnswerAction]).
 *
 * If a timer is set to 0, the corresponding action is not triggered.
 *
 * @see AutoAdvanceSettings
 */
class AutoAdvance(
    private val scope: CoroutineScope,
    private val listener: ActionListener,
    initialCard: Deferred<Card>,
) {
    /**
     * Listens to the `Auto Advance` actions set in Deck options,
     * which can be either a [QuestionAction] or a [AnswerAction].
     */
    fun interface ActionListener {
        suspend fun onAutoAdvanceAction(action: AutoAdvanceAction)
    }

    var isEnabled = false
        set(value) {
            field = value
            if (!value) {
                cancelQuestionAndAnswerActionJobs()
            }
        }
    private var questionActionJob: Job? = null
    private var answerActionJob: Job? = null

    private var settings =
        scope.asyncIO {
            AutoAdvanceSettings.createInstance(initialCard.await().currentDeckId())
        }

    suspend fun shouldWaitForAudio() = settings.await().waitForAudio

    @androidx.annotation.VisibleForTesting
    internal suspend fun awaitSettingsForTesting() {
        settings.await()
    }

    fun cancelQuestionAndAnswerActionJobs() {
        questionActionJob?.cancel()
        answerActionJob?.cancel()
    }

    fun onCardChange(card: Card) {
        cancelQuestionAndAnswerActionJobs()
        settings =
            scope.asyncIO {
                AutoAdvanceSettings.createInstance(card.currentDeckId())
            }
    }

    suspend fun onShowQuestion() {
        cancelQuestionAndAnswerActionJobs()
        val currentSettings = settings
        val loadedSettings = currentSettings.await()
        if (settings !== currentSettings) return
        if (!loadedSettings.durationToShowQuestionFor.isPositive() || !isEnabled) return

        questionActionJob =
            scope.launch {
                delay(loadedSettings.durationToShowQuestionFor)
                listener.onAutoAdvanceAction(loadedSettings.questionAction)
            }
    }

    suspend fun onShowAnswer() {
        cancelQuestionAndAnswerActionJobs()
        val currentSettings = settings
        val loadedSettings = currentSettings.await()
        if (settings !== currentSettings) return
        if (!loadedSettings.durationToShowAnswerFor.isPositive() || !isEnabled) return

        answerActionJob =
            scope.launch {
                delay(loadedSettings.durationToShowAnswerFor)
                listener.onAutoAdvanceAction(loadedSettings.answerAction)
            }
    }
}
