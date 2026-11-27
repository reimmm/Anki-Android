// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.kiosk

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.ichi2.anki.R
import com.ichi2.anki.common.utils.android.showThemedToast
import com.ichi2.anki.kiosk.KioskQrScannerActivity.ScanMode
import kotlinx.coroutines.launch

/**
 * Control dialog for Kiosk mode: displays lock/break status, Device Owner status,
 * pre-build reward settings (`KIOSK_REWARD_CARDS` / `KIOSK_REWARD_MINUTES`), progress toward
 * the next reward, and actions to register/lock/unlock via QR or start/pause a reward break.
 */
class KioskStatusDialogFragment : DialogFragment() {
    private val uiHandler = Handler(Looper.getMainLooper())

    private lateinit var statusLineText: TextView
    private lateinit var deviceOwnerText: TextView
    private lateinit var qrStatusText: TextView
    private lateinit var rewardRuleText: TextView
    private lateinit var rewardProgressText: TextView
    private lateinit var rewardProgressBar: LinearProgressIndicator
    private lateinit var bankedBreakText: TextView
    private lateinit var copyAdbBtn: MaterialButton
    private lateinit var registerOrChangeQrBtn: MaterialButton
    private lateinit var lockQrBtn: MaterialButton
    private lateinit var startBreakBtn: MaterialButton
    private lateinit var endBreakEarlyBtn: MaterialButton
    private lateinit var unlockQrBtn: MaterialButton

    private val refreshTick =
        object : Runnable {
            override fun run() {
                if (isAdded && context != null) {
                    refreshUi()
                    uiHandler.postDelayed(this, 1000L)
                }
            }
        }

    private val qrScanLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (isAdded && context != null) {
                refreshUi()
                if (result.resultCode == Activity.RESULT_OK) {
                    val snapshot = KioskManager.getStatusSnapshot(requireContext())
                    // Keep dialog open after registering QR so user can immediately scan to lock
                    if (snapshot.isLocked || !snapshot.hasQrKey) {
                        dismissAllowingStateLoss()
                    }
                }
            }
        }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density

        fun dp(value: Int): Int = (value * density).toInt()

        val container =
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(12), dp(24), dp(8))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }

        fun addInfoText(bold: Boolean = false): TextView =
            TextView(ctx).apply {
                textSize = if (bold) 16f else 14f
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(4))
                setTextIsSelectable(true)
                container.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }

        fun addActionButton(): MaterialButton =
            MaterialButton(ctx).apply {
                val params =
                    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        topMargin = dp(6)
                    }
                container.addView(this, params)
            }

        statusLineText = addInfoText(bold = true)
        deviceOwnerText = addInfoText()
        qrStatusText = addInfoText()
        rewardRuleText = addInfoText()
        rewardProgressText = addInfoText()

        rewardProgressBar =
            LinearProgressIndicator(ctx).apply {
                val params =
                    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                        topMargin = dp(4)
                        bottomMargin = dp(8)
                    }
                container.addView(this, params)
            }

        bankedBreakText = addInfoText(bold = true)

        copyAdbBtn =
            addActionButton().apply {
                text = getString(R.string.kiosk_action_copy_adb)
                setOnClickListener {
                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val cmd = KioskManager.getAdbDeviceOwnerCommand(ctx)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("adb_device_owner", cmd))
                    showThemedToast(ctx, R.string.kiosk_adb_copied, true)
                }
            }

        registerOrChangeQrBtn =
            addActionButton().apply {
                setOnClickListener {
                    qrScanLauncher.launch(KioskQrScannerActivity.createIntent(ctx, ScanMode.REGISTER_KEY))
                }
            }

        lockQrBtn =
            addActionButton().apply {
                text = getString(R.string.kiosk_action_lock_qr)
                setOnClickListener {
                    qrScanLauncher.launch(KioskQrScannerActivity.createIntent(ctx, ScanMode.LOCK))
                }
            }

        startBreakBtn =
            addActionButton().apply {
                setOnClickListener {
                    val act = activity ?: return@setOnClickListener
                    if (KioskManager.startBreak(act) > 0L) {
                        dismissAllowingStateLoss()
                    }
                }
            }

        endBreakEarlyBtn =
            addActionButton().apply {
                text = getString(R.string.kiosk_action_end_break_early)
                setOnClickListener {
                    KioskManager.endBreakEarlyAndRelock(ctx)
                    dismissAllowingStateLoss()
                }
            }

        unlockQrBtn =
            addActionButton().apply {
                text = getString(R.string.kiosk_action_unlock_qr)
                setOnClickListener {
                    qrScanLauncher.launch(KioskQrScannerActivity.createIntent(ctx, ScanMode.UNLOCK))
                }
            }

        val scrollView =
            ScrollView(ctx).apply {
                addView(container)
            }

        refreshUi()

        return AlertDialog
            .Builder(ctx)
            .setTitle(R.string.kiosk_mode)
            .setView(scrollView)
            .setNegativeButton(R.string.close, null)
            .create()
    }

    override fun onStart() {
        super.onStart()
        uiHandler.removeCallbacks(refreshTick)
        uiHandler.post(refreshTick)
        lifecycleScope.launch {
            val ctx = context ?: return@launch
            KioskManager.reconcileReviewedCardsFromCollection(ctx)
            if (isAdded && context != null) {
                refreshUi()
            }
        }
    }

    override fun onStop() {
        uiHandler.removeCallbacks(refreshTick)
        super.onStop()
    }

    private fun refreshUi() {
        val ctx = context ?: return
        val snap = KioskManager.getStatusSnapshot(ctx)

        statusLineText.text =
            when {
                snap.isBreakActive ->
                    getString(R.string.kiosk_status_break, KioskManager.formatDuration(snap.remainingBreakMillis))
                snap.isLocked -> getString(R.string.kiosk_status_locked)
                else -> getString(R.string.kiosk_status_unlocked)
            }

        deviceOwnerText.text =
            if (snap.isDeviceOwner) {
                getString(R.string.kiosk_device_owner_active)
            } else {
                getString(R.string.kiosk_device_owner_inactive, KioskManager.getAdbDeviceOwnerCommand(ctx))
            }

        qrStatusText.text =
            if (snap.hasQrKey) {
                getString(R.string.kiosk_qr_status_registered)
            } else {
                getString(R.string.kiosk_qr_status_unregistered)
            }

        rewardRuleText.text =
            getString(
                R.string.kiosk_reward_rule_summary,
                snap.cardsPerUnit,
                snap.minutesPerUnit,
            )

        rewardProgressText.text =
            getString(
                R.string.kiosk_reward_progress_summary,
                snap.cardsUntilNextReward,
                snap.progressInCurrentUnit,
                snap.cardsPerUnit,
            )

        rewardProgressBar.max = snap.cardsPerUnit
        rewardProgressBar.progress = snap.progressInCurrentUnit

        bankedBreakText.text =
            getString(
                R.string.kiosk_reward_banked_summary,
                KioskManager.formatDuration(snap.bankedBreakMillis),
            )

        copyAdbBtn.visibility = if (!snap.isDeviceOwner && !snap.isLocked) View.VISIBLE else View.GONE

        if (!snap.isLocked) {
            registerOrChangeQrBtn.visibility = View.VISIBLE
            registerOrChangeQrBtn.text =
                getString(
                    if (snap.hasQrKey) R.string.kiosk_action_change_qr else R.string.kiosk_action_register_qr,
                )
            lockQrBtn.visibility = if (snap.hasQrKey) View.VISIBLE else View.GONE
            startBreakBtn.visibility = View.GONE
            endBreakEarlyBtn.visibility = View.GONE
            unlockQrBtn.visibility = View.GONE
        } else {
            registerOrChangeQrBtn.visibility = View.GONE
            lockQrBtn.visibility = View.GONE
            unlockQrBtn.visibility = View.VISIBLE
            if (snap.isBreakActive) {
                startBreakBtn.visibility = View.GONE
                endBreakEarlyBtn.visibility = View.VISIBLE
            } else {
                endBreakEarlyBtn.visibility = View.GONE
                if (snap.bankedBreakMillis > 0L) {
                    startBreakBtn.visibility = View.VISIBLE
                    startBreakBtn.text =
                        getString(
                            R.string.kiosk_action_start_break,
                            KioskManager.formatDuration(snap.bankedBreakMillis),
                        )
                } else {
                    startBreakBtn.visibility = View.GONE
                }
            }
        }
    }

    companion object {
        private const val TAG = "KioskStatusDialog"

        fun show(fragmentManager: FragmentManager) {
            if (fragmentManager.isStateSaved) return
            val existing = fragmentManager.findFragmentByTag(TAG) as? DialogFragment
            existing?.dismissAllowingStateLoss()
            KioskStatusDialogFragment().show(fragmentManager, TAG)
        }
    }
}
