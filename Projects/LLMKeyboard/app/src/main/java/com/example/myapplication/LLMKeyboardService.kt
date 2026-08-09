package com.example.myapplication

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.widget.Button

class LLMKeyboardService : InputMethodService() {

    override fun onCreateInputView(): View {

        val view = layoutInflater.inflate(
            R.layout.keyboard_view,
            null
        )

        val keys = mapOf(
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

        for ((id, text) in keys) {
            view.findViewById<Button>(id).setOnClickListener {
                currentInputConnection?.commitText(text, 1)
            }
        }

        view.findViewById<Button>(R.id.key_space).setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
        }

        view.findViewById<Button>(R.id.key_backspace).setOnClickListener {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }

        view.findViewById<Button>(R.id.key_enter).setOnClickListener {
            currentInputConnection?.sendKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_ENTER
                )
            )
        }

        return view
    }
}