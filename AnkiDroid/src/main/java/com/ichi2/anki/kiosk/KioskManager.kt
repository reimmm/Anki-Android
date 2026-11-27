// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.kiosk

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import androidx.annotation.VisibleForTesting
import androidx.core.app.PendingIntentCompat
import androidx.core.content.edit
import com.ichi2.anki.BuildConfig
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.common.utils.android.showThemedToast
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import java.security.MessageDigest
import java.util.Locale

/**
 * Coordinates Android Device Owner Kiosk (`LockTask`) mode, QR key registration/verification,
 * and card-review-based reward break time.
 */
object KioskManager {
    private const val PREFS_NAME = "kiosk_state"
    private const val HASH_DOMAIN_PREFIX = "ankidroid-kiosk-v1:"
    private const val KEY_QR_HASH = "qr_key_sha256"
    private const val KEY_IS_LOCKED = "is_kiosk_locked"
    private const val KEY_NET_REVIEWED_CARDS = "net_reviewed_cards"
    private const val KEY_REWARDED_UNITS = "rewarded_units"
    private const val KEY_BANKED_BREAK_MS = "banked_break_ms"
    private const val KEY_BREAK_END_MS = "break_end_epoch_ms"
    private const val KEY_LOCK_STARTED_MS = "lock_started_epoch_ms"
    private const val KEY_CARRYOVER_CARDS = "carryover_reviewed_cards"
    private const val BREAK_ALARM_REQUEST_CODE = 29401

    @VisibleForTesting
    internal var rewardCardsOverride: Int? = null

    @VisibleForTesting
    internal var rewardMinutesOverride: Int? = null

    val rewardCardsPerUnit: Int
        get() = (rewardCardsOverride ?: BuildConfig.KIOSK_REWARD_CARDS).coerceAtLeast(1)

    val rewardMinutesPerUnit: Int
        get() = (rewardMinutesOverride ?: BuildConfig.KIOSK_REWARD_MINUTES).coerceAtLeast(1)

    data class RewardUpdate(
        val grantedMinutes: Int,
        val totalBankedMillis: Long,
        val cardsUntilNextReward: Int,
        val progressInCurrentUnit: Int,
        val cardsPerUnit: Int,
    )

    data class KioskStatusSnapshot(
        val isLocked: Boolean,
        val isBreakActive: Boolean,
        val remainingBreakMillis: Long,
        val bankedBreakMillis: Long,
        val hasQrKey: Boolean,
        val isDeviceOwner: Boolean,
        val cardsPerUnit: Int,
        val minutesPerUnit: Int,
        val cardsUntilNextReward: Int,
        val progressInCurrentUnit: Int,
    )

    private val _rewardEarnedFlow = MutableSharedFlow<RewardUpdate>(extraBufferCapacity = 4)
    val rewardEarnedFlow: SharedFlow<RewardUpdate> = _rewardEarnedFlow.asSharedFlow()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAdbDeviceOwnerCommand(context: Context): String =
        "adb shell dpm set-device-owner ${context.packageName}/${KioskDeviceAdminReceiver::class.java.name}"

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return false
        return runCatching { dpm.isDeviceOwnerApp(context.packageName) }.getOrDefault(false)
    }

    fun hasRegisteredQrKey(context: Context): Boolean = !prefs(context).getString(KEY_QR_HASH, null).isNullOrEmpty()

    fun isKioskLocked(context: Context): Boolean = prefs(context).getBoolean(KEY_IS_LOCKED, false)

    fun getRemainingBreakMillis(context: Context): Long {
        if (!isKioskLocked(context)) return 0L
        val breakEnd = prefs(context).getLong(KEY_BREAK_END_MS, 0L)
        if (breakEnd <= 0L) return 0L
        val remaining = breakEnd - TimeManager.time.intTimeMS()
        return if (remaining > 0L) remaining else 0L
    }

    fun isBreakActive(context: Context): Boolean = getRemainingBreakMillis(context) > 0L

    fun getBankedBreakMillis(context: Context): Long = prefs(context).getLong(KEY_BANKED_BREAK_MS, 0L).coerceAtLeast(0L)

    fun shouldEnforceLockTask(context: Context): Boolean {
        if (!isKioskLocked(context)) return false
        val p = prefs(context)
        val breakEnd = p.getLong(KEY_BREAK_END_MS, 0L)
        if (breakEnd > 0L) {
            val now = TimeManager.time.intTimeMS()
            if (now < breakEnd) {
                return false
            }
            // Break already expired while in background or asleep; clear stale timestamp
            p.edit { putLong(KEY_BREAK_END_MS, 0L) }
        }
        return true
    }

    fun shouldBlockExternalNavigation(context: Context): Boolean = shouldEnforceLockTask(context)

    fun getStatusSnapshot(context: Context): KioskStatusSnapshot {
        val p = prefs(context)
        val isLocked = p.getBoolean(KEY_IS_LOCKED, false)
        val remainingBreak = getRemainingBreakMillis(context)
        val banked = p.getLong(KEY_BANKED_BREAK_MS, 0L).coerceAtLeast(0L)
        val netCards = p.getLong(KEY_NET_REVIEWED_CARDS, 0L).coerceAtLeast(0L)
        val rewardedUnits = p.getLong(KEY_REWARDED_UNITS, 0L).coerceAtLeast(0L)
        val perUnit = rewardCardsPerUnit
        val nextTarget = (rewardedUnits + 1L) * perUnit.toLong()
        val untilNext = (nextTarget - netCards).coerceAtLeast(1L).toInt()
        val progressInUnit = (perUnit - untilNext).coerceIn(0, perUnit)
        return KioskStatusSnapshot(
            isLocked = isLocked,
            isBreakActive = remainingBreak > 0L,
            remainingBreakMillis = remainingBreak,
            bankedBreakMillis = banked,
            hasQrKey = !p.getString(KEY_QR_HASH, null).isNullOrEmpty(),
            isDeviceOwner = isDeviceOwner(context),
            cardsPerUnit = perUnit,
            minutesPerUnit = rewardMinutesPerUnit,
            cardsUntilNextReward = untilNext,
            progressInCurrentUnit = progressInUnit,
        )
    }

    internal fun hashQrPayload(rawPayload: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((HASH_DOMAIN_PREFIX + rawPayload).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { b -> String.format(Locale.ROOT, "%02x", b) }
    }

    /**
     * Registers [rawPayload] as the Kiosk QR key. Only permitted while Kiosk is unlocked.
     */
    fun registerQrKey(
        context: Context,
        rawPayload: String,
    ): Boolean {
        if (rawPayload.isEmpty() || isKioskLocked(context)) return false
        prefs(context).edit {
            putString(KEY_QR_HASH, hashQrPayload(rawPayload))
        }
        return true
    }

    fun verifyQrKey(
        context: Context,
        rawPayload: String,
    ): Boolean {
        if (rawPayload.isEmpty()) return false
        val storedHex = prefs(context).getString(KEY_QR_HASH, null) ?: return false
        val candidateHex = hashQrPayload(rawPayload)
        return MessageDigest.isEqual(
            storedHex.toByteArray(Charsets.UTF_8),
            candidateHex.toByteArray(Charsets.UTF_8),
        )
    }

    fun getLockStartedEpochMillis(context: Context): Long = prefs(context).getLong(KEY_LOCK_STARTED_MS, 0L)

    /**
     * Verifies [rawPayload] and activates Kiosk lock if it matches the registered QR key.
     * Preserves any previously banked break time and carried-over card progress.
     */
    fun lockWithQr(
        activity: Activity,
        rawPayload: String,
    ): Boolean {
        if (!verifyQrKey(activity, rawPayload)) return false
        cancelBreakAlarm(activity)
        KioskBreakTimerService.stop(activity)
        val p = prefs(activity)
        val carryover = p.getLong(KEY_CARRYOVER_CARDS, 0L).coerceAtLeast(0L)
        val existingBanked = p.getLong(KEY_BANKED_BREAK_MS, 0L).coerceAtLeast(0L)
        val now = TimeManager.time.intTimeMS()
        p.edit {
            putBoolean(KEY_IS_LOCKED, true)
            putLong(KEY_LOCK_STARTED_MS, now)
            putLong(KEY_CARRYOVER_CARDS, carryover)
            putLong(KEY_NET_REVIEWED_CARDS, carryover)
            putLong(KEY_REWARDED_UNITS, 0L)
            putLong(KEY_BANKED_BREAK_MS, existingBanked)
            putLong(KEY_BREAK_END_MS, 0L)
        }
        syncSystemPolicies(activity)
        enforceOnActivityResume(activity)
        return true
    }

    /**
     * Verifies [rawPayload] and unlocks Kiosk mode while preserving banked break time
     * (including any remaining time from an active break) and current card progress.
     */
    fun unlockWithQr(
        activity: Activity,
        rawPayload: String,
    ): Boolean {
        if (!verifyQrKey(activity, rawPayload)) return false
        val remainingActiveBreak = getRemainingBreakMillis(activity)
        val preservedBanked = getBankedBreakMillis(activity) + remainingActiveBreak
        val carryover = getStatusSnapshot(activity).progressInCurrentUnit.toLong().coerceAtLeast(0L)
        cancelBreakAlarm(activity)
        KioskBreakTimerService.stop(activity)
        prefs(activity).edit {
            putBoolean(KEY_IS_LOCKED, false)
            putLong(KEY_LOCK_STARTED_MS, 0L)
            putLong(KEY_CARRYOVER_CARDS, carryover)
            putLong(KEY_NET_REVIEWED_CARDS, carryover)
            putLong(KEY_REWARDED_UNITS, 0L)
            putLong(KEY_BANKED_BREAK_MS, preservedBanked)
            putLong(KEY_BREAK_END_MS, 0L)
        }
        syncSystemPolicies(activity)
        stopLockTaskIfActive(activity)
        return true
    }

    /**
     * Records [count] answered review cards while Kiosk is locked and grants break minutes
     * whenever a new [rewardCardsPerUnit] milestone is reached.
     */
    fun onCardsReviewed(
        context: Context,
        count: Int = 1,
    ): RewardUpdate? {
        if (count <= 0 || !isKioskLocked(context)) return null
        val currentNet = prefs(context).getLong(KEY_NET_REVIEWED_CARDS, 0L).coerceAtLeast(0L)
        return reconcileReviewedCards(context, currentNet + count.toLong())
    }

    /**
     * Reconciles the net reviewed card counter against an authoritative total (such as `revlog`
     * rows recorded since Kiosk lock started, including reviews synced from other devices).
     *
     * [KEY_REWARDED_UNITS] remains monotonic so repeated syncs or Undo + re-answer never grant
     * duplicate break time.
     */
    fun reconcileReviewedCards(
        context: Context,
        totalReviewedCardsSinceLock: Long,
    ): RewardUpdate? {
        if (!isKioskLocked(context)) return null
        val p = prefs(context)
        val perUnit = rewardCardsPerUnit.toLong()
        val minutesPerUnit = rewardMinutesPerUnit
        val newNet = totalReviewedCardsSinceLock.coerceAtLeast(0L)
        val prevUnits = p.getLong(KEY_REWARDED_UNITS, 0L).coerceAtLeast(0L)
        val targetUnits = newNet / perUnit
        var banked = p.getLong(KEY_BANKED_BREAK_MS, 0L).coerceAtLeast(0L)
        var grantedMinutes = 0
        val updatedUnits =
            if (targetUnits > prevUnits) {
                val newlyEarnedUnits = (targetUnits - prevUnits).toInt()
                grantedMinutes = newlyEarnedUnits * minutesPerUnit
                banked += grantedMinutes.toLong() * 60_000L
                targetUnits
            } else {
                prevUnits
            }
        p.edit {
            putLong(KEY_NET_REVIEWED_CARDS, newNet)
            putLong(KEY_REWARDED_UNITS, updatedUnits)
            putLong(KEY_BANKED_BREAK_MS, banked)
        }
        val nextTarget = (updatedUnits + 1L) * perUnit
        val untilNext = (nextTarget - newNet).coerceAtLeast(1L).toInt()
        val progressInUnit = (perUnit.toInt() - untilNext).coerceIn(0, perUnit.toInt())
        val update =
            RewardUpdate(
                grantedMinutes = grantedMinutes,
                totalBankedMillis = banked,
                cardsUntilNextReward = untilNext,
                progressInCurrentUnit = progressInUnit,
                cardsPerUnit = perUnit.toInt(),
            )
        if (grantedMinutes > 0) {
            _rewardEarnedFlow.tryEmit(update)
        }
        return update
    }

    /**
     * Queries the Anki `revlog` table for actual card reviews recorded at or after Kiosk lock
     * start (excluding manual reschedules where `type = 4` or `ease <= 0`) and updates the reward
     * break state. This ensures reviews synced from PC/other devices are credited accurately.
     */
    suspend fun reconcileReviewedCardsFromCollection(context: Context): RewardUpdate? {
        if (!isKioskLocked(context)) return null
        val lockStartedMs = getLockStartedEpochMillis(context)
        if (lockStartedMs <= 0L) return null
        val carryover = prefs(context).getLong(KEY_CARRYOVER_CARDS, 0L).coerceAtLeast(0L)
        val revlogCount =
            runCatching {
                withCol {
                    db.queryLongScalar(
                        "SELECT COUNT(*) FROM revlog WHERE id >= ? AND ease > 0 AND type IN (0, 1, 2, 3)",
                        lockStartedMs,
                    )
                }
            }.onFailure { e ->
                Timber.w(e, "Failed to reconcile Kiosk reviewed cards from revlog")
            }.getOrNull() ?: return null
        return reconcileReviewedCards(context, carryover + revlogCount)
    }

    /**
     * Rolls back [revertedCount] reviewed cards after an Undo operation while keeping
     * [KEY_REWARDED_UNITS] monotonic so Answer + Undo cannot farm duplicate reward minutes.
     */
    fun onReviewsReverted(
        context: Context,
        revertedCount: Int,
    ) {
        if (revertedCount <= 0 || !isKioskLocked(context)) return
        val p = prefs(context)
        val updatedNet = (p.getLong(KEY_NET_REVIEWED_CARDS, 0L) - revertedCount.toLong()).coerceAtLeast(0L)
        p.edit {
            putLong(KEY_NET_REVIEWED_CARDS, updatedNet)
        }
    }

    /**
     * Starts a reward break using all banked break time, temporarily releasing `LockTask`
     * so other apps can be used until the timer expires.
     *
     * @return duration of started break in milliseconds, or `0L` if unavailable.
     */
    fun startBreak(activity: Activity): Long {
        if (!isKioskLocked(activity) || isBreakActive(activity)) return 0L
        val banked = getBankedBreakMillis(activity)
        if (banked <= 0L) return 0L
        val now = TimeManager.time.intTimeMS()
        val endMs = now + banked
        prefs(activity).edit {
            putLong(KEY_BREAK_END_MS, endMs)
            putLong(KEY_BANKED_BREAK_MS, 0L)
        }
        syncSystemPolicies(activity)
        stopLockTaskIfActive(activity)
        scheduleBreakAlarm(activity, endMs)
        KioskBreakTimerService.start(activity)
        showThemedToast(
            activity,
            activity.getString(R.string.kiosk_break_started_toast, formatDuration(banked)),
            false,
        )
        runCatching {
            val homeIntent =
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            activity.startActivity(homeIntent)
        }.onFailure { e ->
            Timber.w(e, "Failed to launch home screen on break start")
        }
        return banked
    }

    /**
     * Ends an active break early, banking any unused time and immediately re-locking AnkiDroid.
     */
    fun endBreakEarlyAndRelock(context: Context) {
        if (!isKioskLocked(context)) return
        val remaining = getRemainingBreakMillis(context)
        val p = prefs(context)
        val newBanked = p.getLong(KEY_BANKED_BREAK_MS, 0L).coerceAtLeast(0L) + remaining
        p.edit {
            putLong(KEY_BREAK_END_MS, 0L)
            putLong(KEY_BANKED_BREAK_MS, newBanked)
        }
        cancelBreakAlarm(context)
        KioskBreakTimerService.stop(context)
        syncSystemPolicies(context)
        bringAnkiToFrontForRelock(context)
    }

    /**
     * Invoked when the reward break timer reaches zero; brings AnkiDroid back to the foreground
     * and re-enters `LockTask` mode.
     */
    fun onBreakExpired(context: Context) {
        if (!isKioskLocked(context)) return
        prefs(context).edit {
            putLong(KEY_BREAK_END_MS, 0L)
        }
        cancelBreakAlarm(context)
        KioskBreakTimerService.stop(context)
        syncSystemPolicies(context)
        showThemedToast(context, R.string.kiosk_break_ended_toast, false)
        bringAnkiToFrontForRelock(context)
    }

    fun onBootOrPackageReplaced(context: Context) {
        syncSystemPolicies(context)
        if (!isKioskLocked(context)) return
        if (isBreakActive(context)) {
            scheduleBreakAlarm(context, prefs(context).getLong(KEY_BREAK_END_MS, 0L))
            KioskBreakTimerService.start(context)
        } else {
            prefs(context).edit { putLong(KEY_BREAK_END_MS, 0L) }
            bringAnkiToFrontForRelock(context)
        }
    }

    fun enforceOnActivityResume(activity: Activity) {
        if (!shouldEnforceLockTask(activity)) return
        syncSystemPolicies(activity)
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val isAlreadyLocked = am?.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        if (!isAlreadyLocked) {
            runCatching {
                activity.startLockTask()
            }.onFailure { e ->
                Timber.w(e, "Failed to enter LockTask mode on %s", activity::class.java.simpleName)
            }
        }
    }

    private fun stopLockTaskIfActive(activity: Activity) {
        val am = activity.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am?.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) {
            runCatching {
                activity.stopLockTask()
            }.onFailure { e ->
                Timber.w(e, "Failed to stop LockTask mode")
            }
        }
    }

    fun syncSystemPolicies(context: Context) {
        val appCtx = context.applicationContext
        val locked = isKioskLocked(appCtx)
        val enforceLockNow = shouldEnforceLockTask(appCtx)
        syncHomeComponent(appCtx, enabled = enforceLockNow)

        val dpm = appCtx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager ?: return
        if (!isDeviceOwner(appCtx)) return
        val admin = KioskDeviceAdminReceiver.getComponentName(appCtx)
        val pkg = appCtx.packageName

        runCatching {
            // Auto-grant CAMERA, RECORD_AUDIO, and POST_NOTIFICATIONS so permission dialogs
            // never block inside LockTask
            dpm.setPermissionGrantState(
                admin,
                pkg,
                Manifest.permission.CAMERA,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
            dpm.setPermissionGrantState(
                admin,
                pkg,
                Manifest.permission.RECORD_AUDIO,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                dpm.setPermissionGrantState(
                    admin,
                    pkg,
                    Manifest.permission.POST_NOTIFICATIONS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            }

            // Prevent uninstalling AnkiDroid and prevent changing device clock while Kiosk is locked
            // (including during reward breaks, so break timers and revlog timestamps cannot be bypassed)
            dpm.setUninstallBlocked(admin, pkg, locked)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (locked) {
                    dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME)
                } else {
                    dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_DATE_TIME)
                }
            }

            if (enforceLockNow) {
                dpm.setLockTaskPackages(admin, arrayOf(pkg))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // Allow power menu (GLOBAL_ACTIONS) and status bar clock/battery (SYSTEM_INFO)
                    // while blocking Home, Recents, and notification shade escape. Volume keys always work.
                    dpm.setLockTaskFeatures(
                        admin,
                        DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
                            DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO,
                    )
                }
                val homeFilter =
                    IntentFilter(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        addCategory(Intent.CATEGORY_DEFAULT)
                    }
                dpm.addPersistentPreferredActivity(
                    admin,
                    homeFilter,
                    ComponentName(appCtx, KioskHomeActivity::class.java),
                )
            } else {
                dpm.clearPackagePersistentPreferredActivities(admin, pkg)
                if (!locked) {
                    dpm.setLockTaskPackages(admin, emptyArray())
                }
            }
        }.onFailure { e ->
            Timber.w(e, "Failed to synchronize DevicePolicyManager kiosk policies")
        }
    }

    internal fun syncHomeComponent(
        context: Context,
        enabled: Boolean,
    ) {
        runCatching {
            val component = ComponentName(context, KioskHomeActivity::class.java)
            val desiredState =
                if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
            if (context.packageManager.getComponentEnabledSetting(component) != desiredState) {
                context.packageManager.setComponentEnabledSetting(
                    component,
                    desiredState,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }.onFailure { e ->
            Timber.w(e, "Failed to update KioskHomeActivity component state")
        }
    }

    fun bringAnkiToFrontForRelock(context: Context) {
        runCatching {
            val intent =
                Intent(context, DeckPicker::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
            context.startActivity(intent)
        }.onFailure { e ->
            Timber.e(e, "Failed to bring DeckPicker to front for Kiosk re-lock")
        }
    }

    private fun scheduleBreakAlarm(
        context: Context,
        triggerAtEpochMillis: Long,
    ) {
        if (triggerAtEpochMillis <= 0L) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = buildBreakExpiredPendingIntent(context) ?: return
        runCatching {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtEpochMillis,
                pendingIntent,
            )
        }.onFailure { e ->
            Timber.w(e, "Failed to schedule Kiosk break expiry alarm")
        }
    }

    private fun cancelBreakAlarm(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pendingIntent = buildBreakExpiredPendingIntent(context) ?: return
        runCatching { alarmManager.cancel(pendingIntent) }
    }

    private fun buildBreakExpiredPendingIntent(context: Context): PendingIntent? {
        val intent =
            Intent(context, KioskBreakTimerService::class.java).apply {
                action = KioskBreakTimerService.ACTION_BREAK_ALARM_EXPIRED
            }
        return PendingIntentCompat.getService(
            context,
            BREAK_ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT,
            false,
        )
    }

    fun formatDuration(millis: Long): String {
        val totalSeconds = ((millis.coerceAtLeast(0L) + 999L) / 1000L).takeIf { millis > 0L } ?: 0L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }

    @VisibleForTesting
    internal fun resetForTesting(context: Context) {
        rewardCardsOverride = null
        rewardMinutesOverride = null
        prefs(context).edit { clear() }
    }
}
