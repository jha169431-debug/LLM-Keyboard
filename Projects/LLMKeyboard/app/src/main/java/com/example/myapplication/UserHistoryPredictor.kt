package com.example.myapplication

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.Locale

/**
 * Lightweight persistent next-word learning.
 *
 * Example:
 *
 * "how are " -> learns how -> are
 * "are you " -> learns are -> you
 *
 * No network and no model inference required.
 */
class UserHistoryPredictor(
    context: Context
) {

    companion object {

        private const val TAG =
            "LLMKeyboard"

        private const val PREFS_NAME =
            "keyboard_next_word_history"

        private const val KEY_PREFIX =
            "next::"

        private const val MAX_TARGETS_PER_WORD =
            16
    }

    private val preferences =
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    private val lock =
        Any()

    private val wordRegex =
        Regex(
            "[\\p{L}]+(?:['’\\-][\\p{L}]+)*"
        )

    // =========================================================
    // LEARN FROM TEXT BEFORE SPACE
    // =========================================================

    fun learnFromText(
        textBeforeSpace: String
    ) {

        val words =
            wordRegex
                .findAll(
                    textBeforeSpace
                )
                .map {
                    normalize(
                        it.value
                    )
                }
                .filter {
                    it.isNotEmpty()
                }
                .toList()

        if (words.size < 2) {
            return
        }

        val previous =
            words[
                words.size - 2
            ]

        val current =
            words[
                words.size - 1
            ]

        learnPair(
            previous,
            current
        )
    }

    // =========================================================
    // LEARN PAIR
    // =========================================================

    fun learnPair(
        previousWord: String,
        nextWord: String
    ) {

        val previous =
            normalize(
                previousWord
            )

        val next =
            normalize(
                nextWord
            )

        if (
            previous.isEmpty() ||
            next.isEmpty() ||
            previous == next
        ) {
            return
        }

        synchronized(lock) {

            val key =
                KEY_PREFIX +
                    previous

            val objectValue =
                try {

                    JSONObject(
                        preferences
                            .getString(
                                key,
                                "{}"
                            )
                            ?: "{}"
                    )

                } catch (_: Exception) {

                    JSONObject()
                }

            val oldCount =
                objectValue.optInt(
                    next,
                    0
                )

            objectValue.put(
                next,
                oldCount + 1
            )

            /*
             * Prevent unlimited SharedPreferences growth.
             */
            val entries =
                objectValue
                    .keys()
                    .asSequence()
                    .map { word ->

                        word to
                            objectValue.optInt(
                                word,
                                0
                            )
                    }
                    .sortedByDescending {
                        it.second
                    }
                    .take(
                        MAX_TARGETS_PER_WORD
                    )
                    .toList()

            val trimmed =
                JSONObject()

            entries.forEach {
                (word, count) ->

                trimmed.put(
                    word,
                    count
                )
            }

            preferences
                .edit()
                .putString(
                    key,
                    trimmed.toString()
                )
                .apply()

            Log.d(
                TAG,
                "LEARNED NEXT WORD $previous -> $next count=${oldCount + 1}"
            )
        }
    }

    // =========================================================
    // PREDICT
    // =========================================================

    fun suggest(
        previousWord: String,
        limit: Int = 3
    ): List<String> {

        val previous =
            normalize(
                previousWord
            )

        if (
            previous.isEmpty() ||
            limit <= 0
        ) {
            return emptyList()
        }

        synchronized(lock) {

            val raw =
                preferences.getString(
                    KEY_PREFIX + previous,
                    null
                )
                    ?: return emptyList()

            val objectValue =
                try {

                    JSONObject(raw)

                } catch (_: Exception) {

                    return emptyList()
                }

            return objectValue
                .keys()
                .asSequence()
                .map { word ->

                    word to
                        objectValue.optInt(
                            word,
                            0
                        )
                }
                .filter {
                    it.second > 0
                }
                .sortedWith(
                    compareByDescending<Pair<String, Int>> {
                        it.second
                    }
                        .thenBy {
                            it.first
                        }
                )
                .map {
                    it.first
                }
                .take(limit)
                .toList()
        }
    }

    private fun normalize(
        value: String
    ): String {

        return value
            .trim()
            .trim(
                '\'',
                '’',
                '"',
                '.',
                ',',
                '!',
                '?',
                ':',
                ';',
                '(',
                ')'
            )
            .lowercase(
                Locale.ROOT
            )
    }
}
