/*
 * Copyright (c) 2022 Ankitects Pty Ltd <https://apps.ankiweb.net>
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import anki.collection.OpChangesAfterUndo
import com.google.android.material.snackbar.Snackbar
import com.ichi2.anki.CollectionManager.TR
import com.ichi2.anki.common.android.appContext
import com.ichi2.anki.common.time.TimeManager
import com.ichi2.anki.kiosk.KioskManager
import com.ichi2.anki.libanki.redoAvailable
import com.ichi2.anki.libanki.undoAvailable
import com.ichi2.anki.observability.undoableOp
import com.ichi2.anki.snackbar.showSnackbar

private const val KIOSK_REVLOG_LOOKBACK_MS = 7L * 86_400_000L

suspend fun tryUndo(): String {
    var revlogDelta = 0
    val changes =
        undoableOp {
            if (undoAvailable()) {
                val cutoff = TimeManager.time.intTimeMS() - KIOSK_REVLOG_LOOKBACK_MS
                val before = db.queryScalar("SELECT COUNT(*) FROM revlog WHERE id >= ?", cutoff)
                val result = undo()
                val after = db.queryScalar("SELECT COUNT(*) FROM revlog WHERE id >= ?", cutoff)
                revlogDelta = after - before
                result
            } else {
                OpChangesAfterUndo.getDefaultInstance()
            }
        }
    if (revlogDelta < 0) {
        KioskManager.onReviewsReverted(appContext, -revlogDelta)
    }
    return if (changes.operation.isEmpty()) {
        TR.actionsNothingToUndo()
    } else {
        TR.undoActionUndone(changes.operation)
    }
}

suspend fun tryRedo(): String {
    var revlogDelta = 0
    val changes =
        undoableOp {
            if (redoAvailable()) {
                val cutoff = TimeManager.time.intTimeMS() - KIOSK_REVLOG_LOOKBACK_MS
                val before = db.queryScalar("SELECT COUNT(*) FROM revlog WHERE id >= ?", cutoff)
                val result = redo()
                val after = db.queryScalar("SELECT COUNT(*) FROM revlog WHERE id >= ?", cutoff)
                revlogDelta = after - before
                result
            } else {
                OpChangesAfterUndo.getDefaultInstance()
            }
        }
    if (revlogDelta > 0) {
        KioskManager.onCardsReviewed(appContext, revlogDelta)
    }
    return if (changes.operation.isEmpty()) {
        TR.actionsNothingToRedo()
    } else {
        TR.undoRedoAction(changes.operation)
    }
}

/** If there's an action pending in the review queue, undo it and show a snackbar */
suspend fun FragmentActivity.undoAndShowSnackbar(duration: Int = Snackbar.LENGTH_SHORT) {
    withProgress {
        val text = tryUndo()
        showSnackbar(text, duration)
    }
}

/** If there's an action pending in the review queue, undo it and show a snackbar */
suspend fun Fragment.undoAndShowSnackbar(duration: Int = Snackbar.LENGTH_SHORT) {
    requireActivity().undoAndShowSnackbar(duration)
}

suspend fun FragmentActivity.redoAndShowSnackbar(duration: Int = Snackbar.LENGTH_SHORT) {
    withProgress {
        val text = tryRedo()
        showSnackbar(text, duration)
    }
}
