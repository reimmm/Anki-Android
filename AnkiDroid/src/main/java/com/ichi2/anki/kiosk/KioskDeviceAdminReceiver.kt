// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.kiosk

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Device Administration receiver used for Android Enterprise Device Owner Kiosk (`LockTask`) mode.
 *
 * Provisioned once via ADB:
 * `adb shell dpm set-device-owner <packageId>/com.ichi2.anki.kiosk.KioskDeviceAdminReceiver`
 */
class KioskDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(
        context: Context,
        intent: Intent,
    ) {
        super.onEnabled(context, intent)
        Timber.i("KioskDeviceAdminReceiver enabled")
        KioskManager.syncSystemPolicies(context)
    }

    companion object {
        fun getComponentName(context: Context): ComponentName = ComponentName(context, KioskDeviceAdminReceiver::class.java)
    }
}
