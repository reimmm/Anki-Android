// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.kiosk

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.ichi2.anki.DeckPicker

/**
 * Persistent HOME trampoline enabled only while Kiosk mode is actively locked (not on break),
 * ensuring device reboots or Home presses immediately route back into [DeckPicker] under `LockTask`.
 */
class KioskHomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!KioskManager.shouldEnforceLockTask(this)) {
            KioskManager.syncHomeComponent(this, enabled = false)
        }
        val intent =
            Intent(this, DeckPicker::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        startActivity(intent)
        finish()
    }
}
