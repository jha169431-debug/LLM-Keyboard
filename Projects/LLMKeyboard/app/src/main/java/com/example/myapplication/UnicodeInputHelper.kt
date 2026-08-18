package com.example.myapplication

import android.view.inputmethod.InputConnection
import java.text.BreakIterator
import java.util.Locale

object UnicodeInputHelper {

    private val breakIterator =
        ThreadLocal.withInitial {
            BreakIterator.getCharacterInstance(Locale.ROOT)
        }

    fun deletePreviousGrapheme(inputConnection: InputConnection) {

        val beforeCursor =
            inputConnection.getTextBeforeCursor(32, 0)
                ?: return

        if (beforeCursor.isEmpty()) {
            return
        }

        val iterator = breakIterator.get()

        iterator.setText(
            beforeCursor.toString()
        )

        val end = beforeCursor.length

        val start = iterator.preceding(end)

        if (start == BreakIterator.DONE) {
            inputConnection.deleteSurroundingText(
                end,
                0
            )
            return
        }

        val deleteLength = end - start

        inputConnection.deleteSurroundingText(
            deleteLength,
            0
        )
    }
}
