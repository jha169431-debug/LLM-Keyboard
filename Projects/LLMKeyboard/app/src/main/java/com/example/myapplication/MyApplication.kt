package com.example.myapplication

import android.app.Application
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat

class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val config = BundledEmojiCompatConfig(this)

        EmojiCompat.init(config)
    }
}