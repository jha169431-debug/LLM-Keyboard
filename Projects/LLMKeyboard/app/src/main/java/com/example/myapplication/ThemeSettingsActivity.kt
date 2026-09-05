package com.example.myapplication

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class ThemeSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        val title = TextView(this).apply {
            text = "LLM Keyboard Themes"
            textSize = 24f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(16))
        }

        val status = TextView(this).apply {
            val current = KeyboardThemeStore.current(this@ThemeSettingsActivity)
            text = "Selected: ${current.displayName}"
            textSize = 16f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(16))
        }

        container.addView(title)
        container.addView(status)

        KeyboardTheme.values().forEach { theme ->
            val button = Button(this).apply {
                text = theme.displayName
                textSize = 17f
                isAllCaps = false
                setOnClickListener {
                    KeyboardThemeStore.save(this@ThemeSettingsActivity, theme)
                    status.text = "Selected: ${theme.displayName}"
                    Toast.makeText(
                        this@ThemeSettingsActivity,
                        "${theme.displayName} selected — reopen keyboard to apply",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            container.addView(
                button,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(56)
                ).apply {
                    bottomMargin = dp(8)
                }
            )
        }

        setContentView(
            ScrollView(this).apply {
                addView(container)
            }
        )
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
