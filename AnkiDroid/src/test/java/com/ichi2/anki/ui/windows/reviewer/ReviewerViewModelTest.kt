// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.ui.windows.reviewer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.scheduler.CardAnswer.Rating
import app.cash.turbine.test
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.cardviewer.CardMediaPlayer
import com.ichi2.anki.cardviewer.SoundTagPlayer
import com.ichi2.anki.libanki.SoundOrVideoTag
import com.ichi2.anki.libanki.TtsPlayer
import com.ichi2.anki.preferences.reviewer.ViewerAction
import com.ichi2.anki.utils.ext.cardStateCustomizer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@RunWith(AndroidJUnit4::class)
class ReviewerViewModelTest : RobolectricTest() {
    private val viewModelStore = ViewModelStore()

    override fun getCollectionStorageMode() = CollectionStorageMode.IN_MEMORY_WITH_MEDIA

    @Before
    fun disableAutoplay() {
        // stored in collection; no need for a reset
        updateDeckConfig(col.decks.selected()) { autoplay = false }
    }

    @After
    fun clearViewModels() {
        viewModelStore.clear()
    }

    @Test
    fun `answering waits for the custom scheduler of the first card`() =
        runTest {
            val card = addBasicNote().firstCard()
            col.cardStateCustomizer = "await new Promise(resolve => setTimeout(resolve, 5000));"
            val viewModel = ReviewerViewModel(SavedStateHandle()).also { viewModelStore.put("reviewer", it) }

            viewModel.onShowAnswer()
            viewModel.answerCard(Rating.GOOD)
            viewModel.onPageFinished(false)
            advanceUntilIdle()
            assertFalse(viewModel.showingAnswer.value)
            assertEquals(0, col.getCard(card.id).reps)

            viewModel.onStateMutationCallback()
            advanceUntilIdle()
            assertEquals(1, col.getCard(card.id).reps)
        }

    @Test
    fun `restored answer can be graded after process death`() =
        runTest {
            val card = addBasicNote().firstCard()
            val savedStateHandle = SavedStateHandle(mapOf("showingAnswer" to true))
            val viewModel = ReviewerViewModel(savedStateHandle).also { viewModelStore.put("reviewer", it) }

            viewModel.onPageFinished(true)
            advanceUntilIdle()
            assertTrue(viewModel.showingAnswer.value)

            viewModel.answerCard(Rating.GOOD)
            advanceUntilIdle()
            assertEquals(1, col.getCard(card.id).reps)
        }

    @Test
    fun `restored answer waits for the custom scheduler after process death`() =
        runTest {
            val card = addBasicNote().firstCard()
            col.cardStateCustomizer = "await new Promise(resolve => setTimeout(resolve, 5000));"
            val savedStateHandle = SavedStateHandle(mapOf("showingAnswer" to true))
            val viewModel = ReviewerViewModel(savedStateHandle).also { viewModelStore.put("reviewer", it) }

            viewModel.statesMutationEvalFlow.test {
                viewModel.onPageFinished(true)
                advanceUntilIdle()
                assertTrue(viewModel.showingAnswer.value)
                assertTrue(awaitItem().contains(col.cardStateCustomizer))

                viewModel.answerCard(Rating.GOOD)
                advanceUntilIdle()
                assertEquals(0, col.getCard(card.id).reps)
                cancelAndIgnoreRemainingEvents()

                viewModel.onStateMutationCallback()
                advanceUntilIdle()
                assertEquals(1, col.getCard(card.id).reps)
            }
        }

    @Test
    fun `restoring answer with retained view model does not rerun custom scheduler`() =
        runTest {
            val card = addBasicNote().firstCard()
            col.cardStateCustomizer = "await new Promise(resolve => setTimeout(resolve, 5000));"
            val viewModel = ReviewerViewModel(SavedStateHandle()).also { viewModelStore.put("reviewer", it) }

            viewModel.statesMutationEvalFlow.test {
                viewModel.onPageFinished(false)
                advanceUntilIdle()
                awaitItem()
                viewModel.onStateMutationCallback()
                viewModel.onShowAnswer()
                advanceUntilIdle()
                assertTrue(viewModel.showingAnswer.value)

                viewModel.onPageFinished(true)
                advanceUntilIdle()
                expectNoEvents()
                assertTrue(viewModel.showingAnswer.value)
                cancelAndIgnoreRemainingEvents()

                viewModel.answerCard(Rating.GOOD)
                advanceUntilIdle()
                assertEquals(1, col.getCard(card.id).reps)
            }
        }

    @Test
    fun `answering and undoing resets auto advance timer`() =
        runTest {
            val card1 = addBasicNote("front1", "back1").firstCard()
            val card2 = addBasicNote("front2", "back2").firstCard()
            updateDeckConfig(col.decks.selected()) {
                secondsToShowQuestion = 5.0
                secondsToShowAnswer = 5.0
            }
            val viewModel = ReviewerViewModel(SavedStateHandle()).also { viewModelStore.put("reviewer", it) }

            viewModel.onPageFinished(false)
            runCurrent()
            viewModel.executeAction(ViewerAction.TOGGLE_AUTO_ADVANCE)
            runCurrent()

            advanceTimeBy(3.seconds)
            runCurrent()

            viewModel.onShowAnswer()
            runCurrent()
            viewModel.answerCard(Rating.GOOD)
            runCurrent()
            assertEquals(card2.id, viewModel.getCardId())
            assertFalse(viewModel.showingAnswer.value)

            advanceTimeBy(3.seconds)
            runCurrent()
            assertFalse(viewModel.showingAnswer.value)

            viewModel.onShowAnswer()
            runCurrent()
            assertTrue(viewModel.showingAnswer.value)

            advanceTimeBy(3.seconds)
            runCurrent()

            viewModel.executeAction(ViewerAction.UNDO)
            runCurrent()
            assertEquals(card1.id, viewModel.getCardId())
            assertFalse(viewModel.showingAnswer.value)

            advanceTimeBy(3.seconds)
            runCurrent()
            assertFalse(viewModel.showingAnswer.value)

            advanceTimeBy(2.seconds)
            runCurrent()
            assertTrue(viewModel.showingAnswer.value)
        }

    @Test
    fun `toggling auto advance emits feedback event`() =
        runTest {
            addBasicNote()
            val viewModel = ReviewerViewModel(SavedStateHandle()).also { viewModelStore.put("reviewer", it) }

            viewModel.autoAdvanceFeedbackFlow.test {
                viewModel.executeAction(ViewerAction.TOGGLE_AUTO_ADVANCE)
                assertTrue(awaitItem())

                viewModel.executeAction(ViewerAction.TOGGLE_AUTO_ADVANCE)
                assertFalse(awaitItem())
            }
        }

    @Test
    fun `replaying media resets auto advance timer and waits for playback completion`() =
        runTest {
            addBasicNote("[sound:front.mp3]", "[sound:back.mp3]")
            updateDeckConfig(col.decks.selected()) {
                secondsToShowQuestion = 5.0
                secondsToShowAnswer = 5.0
            }

            val playbackStarted = CompletableDeferred<Unit>()
            val playbackGate = CompletableDeferred<Unit>()
            val tagPlayer =
                mockk<SoundTagPlayer> {
                    coEvery { play(any(), any()) } coAnswers {
                        playbackStarted.complete(Unit)
                        playbackGate.await()
                    }
                    coEvery { release() } returns Unit
                }
            val viewModel = ReviewerViewModel(SavedStateHandle()).also { viewModelStore.put("reviewer", it) }
            viewModel.setCardMediaPlayerForTesting(
                CardMediaPlayer(
                    soundTagPlayer = tagPlayer,
                    ttsPlayer = CompletableDeferred(mockk<TtsPlayer>(relaxed = true)),
                    mediaErrorListener = viewModel.mediaErrorHandler,
                ),
            )

            viewModel.onPageFinished(false)
            runCurrent()
            viewModel.executeAction(ViewerAction.TOGGLE_AUTO_ADVANCE)
            runCurrent()

            advanceTimeBy(3.seconds)
            runCurrent()
            assertFalse(viewModel.showingAnswer.value)

            viewModel.executeAction(ViewerAction.PLAY_MEDIA)
            runCurrent()
            playbackStarted.await()

            advanceTimeBy(6.seconds)
            runCurrent()
            assertFalse(viewModel.showingAnswer.value)

            playbackGate.complete(Unit)
            viewModel.cardMediaPlayer.playAvTagsJob?.join()
            runCurrent()

            advanceTimeBy(4.seconds)
            runCurrent()
            assertFalse(viewModel.showingAnswer.value)

            advanceTimeBy(1.seconds)
            runCurrent()
            assertTrue(viewModel.showingAnswer.value)
        }

    @Test
    fun `play answer media plays only back audio and resets auto advance timer`() =
        runTest {
            val card1 = addBasicNote("[sound:front.mp3]", "[sound:back.mp3]").firstCard()
            addBasicNote("front2", "back2")
            updateDeckConfig(col.decks.selected()) {
                replayq = true
                secondsToShowQuestion = 10.0
                secondsToShowAnswer = 5.0
            }

            val answerPlaybackStarted = CompletableDeferred<Unit>()
            val answerPlaybackGate = CompletableDeferred<Unit>()
            val tagPlayer =
                mockk<SoundTagPlayer> {
                    coEvery { play(SoundOrVideoTag("back.mp3"), any()) } coAnswers {
                        answerPlaybackStarted.complete(Unit)
                        answerPlaybackGate.await()
                    }
                    coEvery { play(SoundOrVideoTag("front.mp3"), any()) } returns Unit
                    coEvery { release() } returns Unit
                }
            val viewModel = ReviewerViewModel(SavedStateHandle()).also { viewModelStore.put("reviewer", it) }
            viewModel.setCardMediaPlayerForTesting(
                CardMediaPlayer(
                    soundTagPlayer = tagPlayer,
                    ttsPlayer = CompletableDeferred(mockk<TtsPlayer>(relaxed = true)),
                    mediaErrorListener = viewModel.mediaErrorHandler,
                ),
            )

            viewModel.onPageFinished(false)
            runCurrent()
            viewModel.onShowAnswer()
            runCurrent()
            viewModel.executeAction(ViewerAction.TOGGLE_AUTO_ADVANCE)
            runCurrent()

            advanceTimeBy(3.seconds)
            runCurrent()

            viewModel.executeAction(ViewerAction.PLAY_ANSWER_MEDIA)
            runCurrent()
            answerPlaybackStarted.await()

            advanceTimeBy(6.seconds)
            runCurrent()
            assertEquals(card1.id, viewModel.getCardId())

            answerPlaybackGate.complete(Unit)
            viewModel.cardMediaPlayer.playAvTagsJob?.join()
            runCurrent()

            coVerify(exactly = 0) { tagPlayer.play(SoundOrVideoTag("front.mp3"), any()) }
            coVerify(exactly = 1) { tagPlayer.play(SoundOrVideoTag("back.mp3"), any()) }

            advanceTimeBy(4.seconds)
            runCurrent()
            assertEquals(card1.id, viewModel.getCardId())

            // Verify PLAY_MEDIA replays both front and back when replayQuestion = true and resets timer
            viewModel.executeAction(ViewerAction.PLAY_MEDIA)
            runCurrent()
            viewModel.cardMediaPlayer.playAvTagsJob?.join()
            runCurrent()
            coVerifyOrder {
                tagPlayer.play(SoundOrVideoTag("front.mp3"), any())
                tagPlayer.play(SoundOrVideoTag("back.mp3"), any())
            }

            advanceTimeBy(4.seconds)
            runCurrent()
            assertEquals(card1.id, viewModel.getCardId())

            advanceTimeBy(1.seconds)
            runCurrent()
            assertFalse(viewModel.getCardId() == card1.id)
        }

    @Test
    fun `disabling auto advance stops active media playback`() =
        runTest {
            addBasicNote("[sound:front.mp3]", "[sound:back.mp3]")
            updateDeckConfig(col.decks.selected()) {
                autoplay = true
                secondsToShowQuestion = 5.0
            }

            val playbackStarted = CompletableDeferred<Unit>()
            val playbackGate = CompletableDeferred<Unit>()
            val tagPlayer =
                mockk<SoundTagPlayer> {
                    coEvery { play(any(), any()) } coAnswers {
                        playbackStarted.complete(Unit)
                        playbackGate.await()
                    }
                    coEvery { release() } returns Unit
                }
            val viewModel = ReviewerViewModel(SavedStateHandle()).also { viewModelStore.put("reviewer", it) }
            viewModel.setCardMediaPlayerForTesting(
                CardMediaPlayer(
                    soundTagPlayer = tagPlayer,
                    ttsPlayer = CompletableDeferred(mockk<TtsPlayer>(relaxed = true)),
                    mediaErrorListener = viewModel.mediaErrorHandler,
                ),
            )

            viewModel.onPageFinished(false)
            runCurrent()
            playbackStarted.await()
            assertTrue(viewModel.cardMediaPlayer.isPlaying)

            viewModel.executeAction(ViewerAction.TOGGLE_AUTO_ADVANCE)
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) {
                    viewModel.isAutoAdvanceEnabledFlow.first { it }
                }
            }
            runCurrent()
            assertTrue(viewModel.isAutoAdvanceEnabledFlow.value)
            assertTrue(viewModel.cardMediaPlayer.isPlaying)

            viewModel.executeAction(ViewerAction.TOGGLE_AUTO_ADVANCE)
            withContext(Dispatchers.Default) {
                withTimeout(5.seconds) {
                    viewModel.isAutoAdvanceEnabledFlow.first { !it }
                }
            }
            runCurrent()
            assertFalse(viewModel.isAutoAdvanceEnabledFlow.value)
            assertFalse(viewModel.cardMediaPlayer.isPlaying)

            advanceTimeBy(10.seconds)
            runCurrent()
            assertFalse(viewModel.showingAnswer.value)
        }
}
