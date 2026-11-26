// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright (c) 2022 Brian Da Silva <brianjose2010@gmail.com>                          *

package com.ichi2.anki.ui.windows.managespace

import android.os.Bundle
import androidx.fragment.app.Fragment
import com.ichi2.anki.R
import com.ichi2.anki.SingleFragmentActivity
import com.ichi2.anki.common.utils.android.showThemedToast
import com.ichi2.anki.kiosk.KioskManager

/**
 * This activity is called by the system from the app settings to let the user manage the app's
 * used space. The actual work is done in [ManageSpaceFragment] and the fragment is bound to this
 * activity automatically in [SingleFragmentActivity].
 *
 * @see ManageSpaceFragment
 * @see SingleFragmentActivity.onCreate
 */
class ManageSpaceActivity : SingleFragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        if (KioskManager.isKioskLocked(this)) {
            super.onCreate(savedInstanceState)
            showThemedToast(this, R.string.kiosk_external_blocked, false)
            finish()
            return
        }
        super.onCreate(savedInstanceState)
    }

    override fun createFragment(): Fragment = ManageSpaceFragment()
}
