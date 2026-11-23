// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.provider

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.BuildConfig
import com.ichi2.anki.RobolectricTest
import com.ichi2.testutils.grantPermissions
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
class CardContentProviderTest : RobolectricTest() {
    @Test
    fun `authority and permission match applicationId`() {
        assertEquals("${BuildConfig.APPLICATION_ID}.flashcards", CardContentProvider.AUTHORITY)
        assertEquals("${BuildConfig.APPLICATION_ID}.permission.READ_WRITE_DATABASE", CardContentProvider.READ_WRITE_PERMISSION)
    }

    @Test
    fun `querying decks with applicationId authority succeeds`() {
        grantPermissions(CardContentProvider.READ_WRITE_PERMISSION)
        val provider =
            Robolectric
                .buildContentProvider(CardContentProvider::class.java)
                .create(CardContentProvider.AUTHORITY)
                .get()

        val decksUri = Uri.parse("content://${CardContentProvider.AUTHORITY}/decks")
        provider.query(decksUri, null, null, null, null).use { cursor ->
            assertNotNull(cursor)
        }
    }
}
