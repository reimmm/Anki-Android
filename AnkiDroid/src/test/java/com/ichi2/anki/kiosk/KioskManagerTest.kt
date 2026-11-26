// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.kiosk

import android.app.Activity
import androidx.test.ext.junit.runners.AndroidJUnit4
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.BuildConfig
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.common.time.MockTime
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.observability.undoableOp
import com.ichi2.anki.tryRedo
import com.ichi2.anki.tryUndo
import com.ichi2.anki.utils.ext.answerCard
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class KioskManagerTest : RobolectricTest() {
    private lateinit var activityController: ActivityController<Activity>
    private lateinit var activity: Activity

    @Before
    fun setUpKiosk() {
        KioskManager.resetForTesting(targetContext)
        activityController = Robolectric.buildActivity(Activity::class.java).setup()
        activity = activityController.get()
    }

    @After
    fun tearDownKiosk() {
        activityController.pause().stop().destroy()
        KioskManager.resetForTesting(targetContext)
        TimeManager.reset()
    }

    @Test
    fun `default build config reward values match 100 cards for 10 minutes`() {
        assertEquals(100, BuildConfig.KIOSK_REWARD_CARDS)
        assertEquals(10, BuildConfig.KIOSK_REWARD_MINUTES)
        assertEquals(100, KioskManager.rewardCardsPerUnit)
        assertEquals(10, KioskManager.rewardMinutesPerUnit)
    }

    @Test
    fun `arbitrary QR key registers locks blocks external navigation and unlocks only with matching QR`() {
        val secretQr = "https://example.com/my-arbitrary-qr?id=麻酔科-9876"
        assertFalse(KioskManager.hasRegisteredQrKey(targetContext))
        assertTrue(KioskManager.registerQrKey(targetContext, secretQr))
        assertTrue(KioskManager.hasRegisteredQrKey(targetContext))

        // Wrong QR fails to lock
        assertFalse(KioskManager.lockWithQr(activity, "wrong-qr"))
        assertFalse(KioskManager.isKioskLocked(targetContext))

        // Matching QR locks Kiosk mode
        assertTrue(KioskManager.lockWithQr(activity, secretQr))
        assertTrue(KioskManager.isKioskLocked(targetContext))
        assertTrue(KioskManager.shouldEnforceLockTask(targetContext))
        assertTrue(KioskManager.shouldBlockExternalNavigation(targetContext))

        // Cannot overwrite QR key while locked
        assertFalse(KioskManager.registerQrKey(targetContext, "attacker-new-qr"))

        // Wrong QR fails to unlock
        assertFalse(KioskManager.unlockWithQr(activity, "attacker-new-qr"))
        assertTrue(KioskManager.isKioskLocked(targetContext))

        // Matching QR unlocks Kiosk mode
        assertTrue(KioskManager.unlockWithQr(activity, secretQr))
        assertFalse(KioskManager.isKioskLocked(targetContext))
        assertFalse(KioskManager.shouldEnforceLockTask(targetContext))
        assertFalse(KioskManager.shouldBlockExternalNavigation(targetContext))
    }

    @Test
    fun `reviewing 100 cards grants 10 minutes and undo cannot farm duplicate rewards`() {
        val secretQr = "kiosk-key-123"
        assertTrue(KioskManager.registerQrKey(targetContext, secretQr))

        // Reviews while unlocked do not accumulate Kiosk break time
        assertNull(KioskManager.onCardsReviewed(targetContext, 10))

        assertTrue(KioskManager.lockWithQr(activity, secretQr))

        // Review 99 cards -> 1 card remaining, 0 break minutes granted yet
        val update99 = KioskManager.onCardsReviewed(targetContext, 99)
        assertNotNull(update99)
        assertEquals(0, update99.grantedMinutes)
        assertEquals(1, update99.cardsUntilNextReward)
        assertEquals(99, update99.progressInCurrentUnit)
        assertEquals(0L, KioskManager.getBankedBreakMillis(targetContext))

        // Review 100th card -> grants +10 minutes (600,000 ms)
        val update100 = KioskManager.onCardsReviewed(targetContext, 1)
        assertNotNull(update100)
        assertEquals(10, update100.grantedMinutes)
        assertEquals(10L * 60_000L, update100.totalBankedMillis)
        assertEquals(100, update100.cardsUntilNextReward)
        assertEquals(0, update100.progressInCurrentUnit)

        // Undo 1 card (back to 99 net) and re-answer 100th card -> must NOT grant another 10 minutes
        KioskManager.onReviewsReverted(targetContext, 1)
        val reAnswer100 = KioskManager.onCardsReviewed(targetContext, 1)
        assertNotNull(reAnswer100)
        assertEquals(0, reAnswer100.grantedMinutes)
        assertEquals(10L * 60_000L, KioskManager.getBankedBreakMillis(targetContext))

        // Reaching 200 net cards grants the second +10 minutes (total 20 minutes)
        val update200 = KioskManager.onCardsReviewed(targetContext, 100)
        assertNotNull(update200)
        assertEquals(10, update200.grantedMinutes)
        assertEquals(20L * 60_000L, KioskManager.getBankedBreakMillis(targetContext))
    }

    @Test
    fun `break lifecycle releases lock allows early pause saving remaining time and relocks on expiry`() {
        val mockTime = MockTime(1_700_000_000_000L)
        TimeManager.resetWith(mockTime)

        KioskManager.rewardCardsOverride = 10
        KioskManager.rewardMinutesOverride = 5

        val secretQr = "qr-break-test"
        KioskManager.registerQrKey(targetContext, secretQr)
        KioskManager.lockWithQr(activity, secretQr)

        // Earn 5 minutes (300,000 ms) by reviewing 10 cards
        KioskManager.onCardsReviewed(targetContext, 10)
        assertEquals(300_000L, KioskManager.getBankedBreakMillis(targetContext))

        // Start break -> LockTask temporarily lifted
        val startedMs = KioskManager.startBreak(activity)
        assertEquals(300_000L, startedMs)
        assertTrue(KioskManager.isBreakActive(targetContext))
        assertFalse(KioskManager.shouldEnforceLockTask(targetContext))
        assertFalse(KioskManager.shouldBlockExternalNavigation(targetContext))
        assertEquals(0L, KioskManager.getBankedBreakMillis(targetContext))

        // Spend 2 minutes (120,000 ms) and return early -> 3 minutes (180,000 ms) saved back
        mockTime.addM(2)
        assertEquals(180_000L, KioskManager.getRemainingBreakMillis(targetContext))
        KioskManager.endBreakEarlyAndRelock(targetContext)
        assertFalse(KioskManager.isBreakActive(targetContext))
        assertTrue(KioskManager.shouldEnforceLockTask(targetContext))
        assertEquals(180_000L, KioskManager.getBankedBreakMillis(targetContext))

        // Resume break with saved 3 minutes and let timer expire completely
        assertEquals(180_000L, KioskManager.startBreak(activity))
        assertFalse(KioskManager.shouldEnforceLockTask(targetContext))
        mockTime.addM(3)
        assertEquals(0L, KioskManager.getRemainingBreakMillis(targetContext))
        assertFalse(KioskManager.isBreakActive(targetContext))
        assertTrue(KioskManager.shouldEnforceLockTask(targetContext))
        assertTrue(KioskManager.shouldBlockExternalNavigation(targetContext))
    }

    @Test
    fun `tryUndo and tryRedo reconcile Kiosk review count via revlog`() =
        runTest {
            KioskManager.rewardCardsOverride = 2
            KioskManager.rewardMinutesOverride = 5
            KioskManager.registerQrKey(targetContext, "qr-undo")
            KioskManager.lockWithQr(activity, "qr-undo")

            addBasicNote("Front 1", "Back 1")
            addBasicNote("Front 2", "Back 2")

            val state1 = col.sched.currentQueueState()!!
            val answer1 = col.sched.answerCard(state1.topCard, state1.states, Rating.GOOD)
            undoableOp { sched.answerCard(answer1) }
            KioskManager.onCardsReviewed(targetContext)
            assertEquals(1, KioskManager.getStatusSnapshot(targetContext).progressInCurrentUnit)

            // Undoing the review decrements progress back to 0
            tryUndo()
            assertEquals(0, KioskManager.getStatusSnapshot(targetContext).progressInCurrentUnit)
            assertEquals(0L, KioskManager.getBankedBreakMillis(targetContext))

            // Redoing the review restores progress to 1, and answering card 2 grants +5 min
            tryRedo()
            assertEquals(1, KioskManager.getStatusSnapshot(targetContext).progressInCurrentUnit)

            val state2 = col.sched.currentQueueState()!!
            val answer2 = col.sched.answerCard(state2.topCard, state2.states, Rating.GOOD)
            undoableOp { sched.answerCard(answer2) }
            KioskManager.onCardsReviewed(targetContext)
            assertEquals(5L * 60_000L, KioskManager.getBankedBreakMillis(targetContext))
        }
}
