package com.example.myapplication

import android.graphics.Color

data class KeyboardVisualTheme(
    val surface: Int,
    val key: Int,
    val keyPressed: Int,
    val action: Int,
    val actionPressed: Int,
    val accent: Int,
    val accentPressed: Int,
    val text: Int,
    val accentText: Int,
    val suggestion: Int,
    val suggestionPressed: Int,
    val radiusDp: Float
)

object KeyboardVisualThemes {
    fun forTheme(theme: KeyboardTheme): KeyboardVisualTheme = when (theme) {
        KeyboardTheme.NPL_GLASS -> KeyboardVisualTheme(
            Color.rgb(224, 227, 231),
            Color.rgb(249, 250, 251), Color.rgb(224, 228, 233),
            Color.rgb(207, 212, 218), Color.rgb(190, 197, 205),
            Color.rgb(199, 207, 216), Color.rgb(178, 188, 199),
            Color.rgb(24, 27, 31), Color.rgb(24, 27, 31),
            Color.argb(45, 255, 255, 255), Color.argb(150, 255, 255, 255),
            10f
        )

        KeyboardTheme.ONE_UI -> KeyboardVisualTheme(
            Color.rgb(236, 238, 241),
            Color.WHITE, Color.rgb(229, 233, 238),
            Color.rgb(216, 220, 225), Color.rgb(198, 204, 211),
            Color.rgb(185, 211, 255), Color.rgb(164, 196, 250),
            Color.rgb(20, 23, 27), Color.rgb(20, 48, 78),
            Color.rgb(244, 246, 248), Color.rgb(225, 230, 236),
            8f
        )

        KeyboardTheme.PIXEL -> KeyboardVisualTheme(
            Color.rgb(238, 241, 245),
            Color.rgb(249, 250, 252), Color.rgb(226, 232, 239),
            Color.rgb(220, 226, 234), Color.rgb(200, 209, 219),
            Color.rgb(194, 211, 255), Color.rgb(165, 191, 255),
            Color.rgb(31, 35, 40), Color.rgb(25, 48, 83),
            Color.rgb(247, 249, 252), Color.rgb(225, 232, 240),
            14f
        )

        KeyboardTheme.MINIMAL -> KeyboardVisualTheme(
            Color.rgb(247, 247, 248),
            Color.WHITE, Color.rgb(238, 240, 242),
            Color.rgb(239, 241, 243), Color.rgb(222, 225, 229),
            Color.rgb(230, 234, 239), Color.rgb(211, 217, 224),
            Color.rgb(30, 34, 39), Color.rgb(30, 34, 39),
            Color.TRANSPARENT, Color.rgb(235, 238, 241),
            6f
        )

        KeyboardTheme.TERMINAL -> KeyboardVisualTheme(
            Color.rgb(8, 10, 11),
            Color.rgb(20, 24, 22), Color.rgb(8, 12, 10),
            Color.rgb(47, 54, 59), Color.rgb(29, 35, 39),
            Color.rgb(28, 74, 42), Color.rgb(20, 53, 29),
            Color.rgb(90, 255, 120), Color.rgb(120, 255, 145),
            Color.rgb(27, 42, 31), Color.rgb(38, 69, 46),
            5f
        )

        KeyboardTheme.CLASSIC -> KeyboardVisualTheme(
            Color.rgb(232, 226, 217),
            Color.rgb(253, 249, 243), Color.rgb(229, 220, 208),
            Color.rgb(218, 210, 198), Color.rgb(198, 188, 175),
            Color.rgb(194, 207, 219), Color.rgb(170, 186, 201),
            Color.rgb(42, 37, 32), Color.rgb(34, 49, 61),
            Color.rgb(242, 236, 227), Color.rgb(220, 211, 199),
            7f
        )
    }
}
