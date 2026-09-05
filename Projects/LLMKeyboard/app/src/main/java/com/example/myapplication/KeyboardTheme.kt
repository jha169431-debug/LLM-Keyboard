package com.example.myapplication

import android.content.Context

enum class KeyboardTheme(
    val id: String,
    val displayName: String,
    val fontResourceName: String?
) {
    NPL_GLASS("npl_glass", "NPL Glass", "misans_static"),
    ONE_UI("one_ui", "OneUI", null),
    PIXEL("pixel", "Pixel", "roboto_flex"),
    MINIMAL("minimal", "Minimal", "inter"),
    TERMINAL("terminal", "Terminal", "jetbrains_mono_nl"),
    CLASSIC("classic", "Classic", "noto_sans");

    companion object {
        fun fromId(id: String?): KeyboardTheme {
            return values().firstOrNull { it.id == id } ?: NPL_GLASS
        }
    }
}

object KeyboardThemeStore {
    private const val PREFS = "keyboard_theme_preferences"
    private const val KEY_THEME = "selected_theme"

    fun current(context: Context): KeyboardTheme {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return KeyboardTheme.fromId(
            prefs.getString(KEY_THEME, KeyboardTheme.NPL_GLASS.id)
        )
    }

    fun save(context: Context, theme: KeyboardTheme) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, theme.id)
            .apply()
    }
}
