package com.example.myapplication
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView




class LLMKeyboardService : InputMethodService() {

    companion object {
        private const val TAG = "LLMKeyboard"
    }

    private var isShifted = false

    override fun onCreateInputView(): View {
        Log.d(TAG, "onCreateInputView() called")

        val view = layoutInflater.inflate(
            R.layout.keyboard_view,
            null
        )

        setupKeys(view)

        Log.d(TAG, "Keyboard view created successfully")

        return view
    }

    private fun setupKeys(view: View) {

        // =========================
        // EMOJI BUTTON
        // =========================

        view.findViewById<Button>(R.id.key_emoji)
            .setOnClickListener {

                Log.d(TAG, "Opening emoji panel")

                showEmojiPanel(view)
            }

        // =========================
        // NUMBER + LETTER KEYS
        // =========================

        val keys = mapOf(
            R.id.key_1 to "1",
            R.id.key_2 to "2",
            R.id.key_3 to "3",
            R.id.key_4 to "4",
            R.id.key_5 to "5",
            R.id.key_6 to "6",
            R.id.key_7 to "7",
            R.id.key_8 to "8",
            R.id.key_9 to "9",
            R.id.key_0 to "0",

            R.id.key_q to "q",
            R.id.key_w to "w",
            R.id.key_e to "e",
            R.id.key_r to "r",
            R.id.key_t to "t",
            R.id.key_y to "y",
            R.id.key_u to "u",
            R.id.key_i to "i",
            R.id.key_o to "o",
            R.id.key_p to "p",

            R.id.key_a to "a",
            R.id.key_s to "s",
            R.id.key_d to "d",
            R.id.key_f to "f",
            R.id.key_g to "g",
            R.id.key_h to "h",
            R.id.key_j to "j",
            R.id.key_k to "k",
            R.id.key_l to "l",

            R.id.key_z to "z",
            R.id.key_x to "x",
            R.id.key_c to "c",
            R.id.key_v to "v",
            R.id.key_b to "b",
            R.id.key_n to "n",
            R.id.key_m to "m"
        )

        // =========================
        // NUMBER + LETTER PRESSES
        // =========================

        for ((id, keyText) in keys) {

            val button = view.findViewById<Button>(id)

            button.setOnClickListener {

                val output =
                    if (
                        isShifted &&
                        keyText.isNotEmpty() &&
                        keyText[0].isLetter()
                    ) {
                        keyText.uppercase()
                    } else {
                        keyText
                    }

                Log.d(TAG, "Pressed: $output")

                currentInputConnection?.commitText(
                    output,
                    1
                )

                if (
                    isShifted &&
                    keyText.isNotEmpty() &&
                    keyText[0].isLetter()
                ) {
                    isShifted = false

                    updateLetterDisplay(
                        view,
                        keys
                    )

                    updateShiftButton(view)
                }
            }
        }

        // =========================
        // BACKSPACE
        // =========================

        view.findViewById<Button>(R.id.key_backspace)
            .setOnClickListener {

                Log.d(TAG, "Pressed: BACKSPACE")

                currentInputConnection?.let {
                    UnicodeInputHelper.deletePreviousGrapheme(it)
                }
            }

        // =========================
        // SPACE
        // =========================

        view.findViewById<Button>(R.id.key_space)
            .setOnClickListener {

                Log.d(TAG, "Pressed: SPACE")

                currentInputConnection?.commitText(
                    " ",
                    1
                )
            }

        // =========================
        // ENTER
        // =========================

        view.findViewById<Button>(R.id.key_enter)
            .setOnClickListener {

                Log.d(TAG, "Pressed: ENTER")

                val editorInfo = currentInputEditorInfo

                if (editorInfo != null) {

                    val action =
                        editorInfo.imeOptions and
                                android.view.inputmethod.EditorInfo.IME_MASK_ACTION

                    if (action != 0) {
                        currentInputConnection?.performEditorAction(
                            action
                        )
                    } else {
                        currentInputConnection?.commitText(
                            "\n",
                            1
                        )
                    }

                } else {
                    currentInputConnection?.commitText(
                        "\n",
                        1
                    )
                }
            }

        // =========================
        // SHIFT
        // =========================

        view.findViewById<Button>(R.id.key_shift)
            .setOnClickListener {

                isShifted = !isShifted

                Log.d(
                    TAG,
                    "Pressed: SHIFT -> shifted=$isShifted"
                )

                updateLetterDisplay(
                    view,
                    keys
                )

                updateShiftButton(view)
            }

        // =========================
        // SYMBOLS
        // =========================

        view.findViewById<Button>(R.id.key_symbols)
            .setOnClickListener {

                Log.d(TAG, "Pressed: SYMBOLS")

                // TODO: symbols panel
            }
    }

    // =========================================================
    // EMOJI PANEL
    // =========================================================

    private fun showEmojiPanel(view: View) {

        val parent = view as? ViewGroup ?: return

        // Hide normal keyboard.
        for (i in 0 until parent.childCount) {
            parent.getChildAt(i).visibility = View.GONE
        }

        val emojiView = layoutInflater.inflate(
            R.layout.emoji_panel,
            parent,
            false
        )

        parent.addView(emojiView)

        val emojiGrid =
            emojiView.findViewById<GridLayout>(
                R.id.emoji_grid
            )

        val emojis = EmojiData.common

        for ((index, emoji) in emojis.withIndex()) {

            val button = ImageButton(this)

            // Our generated image assets:
            // emoji_01.png ... emoji_48.png
            val drawableName =
                "emoji_%02d".format(index + 1)

            val drawableId =
                resources.getIdentifier(
                    drawableName,
                    "drawable",
                    packageName
                )

            if (drawableId != 0) {
                button.setImageResource(drawableId)
            }

            button.setPadding(
                0,
                0,
                0,
                0
            )

            button.scaleType =
                android.widget.ImageView.ScaleType.CENTER_INSIDE

            button.setBackgroundColor(
                Color.rgb(37, 40, 45)
            )

            val density = resources.displayMetrics.density

            val params = GridLayout.LayoutParams()

            params.width = 0
            params.height = (44 * density).toInt()

            params.columnSpec =
                GridLayout.spec(
                    GridLayout.UNDEFINED,
                    1f
                )

            val margin = (1 * density).toInt()

            params.setMargins(
                margin,
                margin,
                margin,
                margin
            )

            button.layoutParams = params

            button.setOnClickListener {

                Log.d(
                    TAG,
                    "Pressed emoji: $emoji"
                )

                // Insert the original Unicode emoji.
                currentInputConnection?.commitText(
                    emoji,
                    1
                )
            }

            emojiGrid.addView(button)
        }

        // =========================
        // RETURN TO KEYBOARD
        // =========================

        emojiView.findViewById<Button>(
            R.id.key_emoji_back
        ).setOnClickListener {

            parent.removeView(emojiView)

            for (i in 0 until parent.childCount) {
                parent.getChildAt(i).visibility = View.VISIBLE
            }
        }
    }

    // =========================================================
    // UPDATE LETTER DISPLAY
    // =========================================================

    private fun updateLetterDisplay(
        view: View,
        keys: Map<Int, String>
    ) {

        for ((id, keyText) in keys) {

            if (
                keyText.isEmpty() ||
                !keyText[0].isLetter()
            ) {
                continue
            }

            val button =
                view.findViewById<Button>(id)

            button.text =
                if (isShifted) {
                    keyText.uppercase()
                } else {
                    keyText
                }
        }
    }

    // =========================================================
    // UPDATE SHIFT BUTTON
    // =========================================================

    private fun updateShiftButton(view: View) {

        val shiftButton =
            view.findViewById<Button>(R.id.key_shift)

        shiftButton.text =
            if (isShifted) {
                "SHIFT"
            } else {
                "⇧"
            }
    }
}
