package com.example.myapplication

import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale

class LocalSuggestionEngine(
    private val assets: AssetManager
) {

    companion object {
        private const val TAG = "LLMKeyboard"
        private const val DICTIONARY_ASSET =
            "dictionaries/en_wordlist.combined"
    }

    private data class WordEntry(
        val word: String,
        val normalized: String,
        val frequency: Int
    )

    private data class BigramEntry(
        val word: String,
        val normalized: String,
        val frequency: Int
    )

    private data class DictionaryData(
        val words: List<WordEntry>,
        val unigramByWord: Map<String, WordEntry>,
        val bigrams: Map<String, List<BigramEntry>>
    )

    @Volatile
    private var loadedData: DictionaryData? = null

    private val loadLock = Any()

    // =========================================================
    // CURRENT-WORD COMPLETION
    // =========================================================

    fun suggest(
        prefix: String,
        limit: Int = 3
    ): List<String> {

        if (limit <= 0) return emptyList()

        val cleanPrefix = prefix.trim()
        if (cleanPrefix.isEmpty()) return emptyList()

        val normalizedPrefix =
            cleanPrefix.lowercase(Locale.ROOT)

        val data = ensureLoaded()
        val dictionary = data.words

        val startIndex =
            lowerBound(
                dictionary,
                normalizedPrefix
            )

        if (startIndex >= dictionary.size) {
            return emptyList()
        }

        val matches =
            ArrayList<WordEntry>()

        var index = startIndex

        while (index < dictionary.size) {

            val entry = dictionary[index]

            if (
                !entry.normalized.startsWith(
                    normalizedPrefix
                )
            ) {
                break
            }

            if (
                entry.normalized !=
                normalizedPrefix
            ) {
                matches.add(entry)
            }

            index++
        }

        return matches
            .sortedWith(
                compareByDescending<WordEntry> {
                    it.frequency
                }
                    .thenBy {
                        it.word.length
                    }
                    .thenBy {
                        it.normalized
                    }
            )
            .asSequence()
            .map {
                applyTypedCapitalization(
                    cleanPrefix,
                    it.word
                )
            }
            .distinctBy {
                it.lowercase(Locale.ROOT)
            }
            .take(limit)
            .toList()
    }

    // =========================================================
    // NEXT-WORD BIGRAM PREDICTION
    // =========================================================

    fun nextWords(
        previousWord: String,
        limit: Int = 3
    ): List<String> {

        if (limit <= 0) return emptyList()

        val normalizedPrevious =
            previousWord
                .trim()
                .trim('\'', '’', '"', '.', ',', '!', '?', ':', ';')
                .lowercase(Locale.ROOT)

        if (normalizedPrevious.isEmpty()) {
            return emptyList()
        }

        val data = ensureLoaded()

        val candidates =
            data.bigrams[normalizedPrevious]
                ?: return emptyList()

        return candidates
            .sortedWith(
                compareByDescending<BigramEntry> {
                    it.frequency
                }
                    .thenByDescending {
                        data.unigramByWord[
                            it.normalized
                        ]?.frequency ?: 0
                    }
                    .thenBy {
                        it.word.length
                    }
            )
            .asSequence()
            .map {
                it.word
            }
            .distinctBy {
                it.lowercase(Locale.ROOT)
            }
            .take(limit)
            .toList()
    }

    fun warmUp() {
        ensureLoaded()
    }

    // =========================================================
    // LOAD AOSP COMBINED DICTIONARY
    // =========================================================

    private fun ensureLoaded(): DictionaryData {

        loadedData?.let {
            return it
        }

        synchronized(loadLock) {

            loadedData?.let {
                return it
            }

            val start =
                SystemClock.elapsedRealtime()

            val words =
                HashMap<String, WordEntry>()

            val bigrams =
                HashMap<String, MutableList<BigramEntry>>()

            var currentParentWord:
                String? = null

            assets
                .open(DICTIONARY_ASSET)
                .use { input ->

                    BufferedReader(
                        InputStreamReader(
                            input,
                            Charsets.UTF_8
                        ),
                        64 * 1024
                    ).useLines { lines ->

                        lines.forEach { rawLine ->

                            val line =
                                rawLine.trim()

                            when {

                                line.startsWith("word=") -> {

                                    val wordName =
                                        extractValue(
                                            line,
                                            "word="
                                        )

                                    currentParentWord =
                                        wordName
                                            ?.lowercase(
                                                Locale.ROOT
                                            )

                                    parseWordEntry(line)
                                        ?.let { entry ->

                                            val old =
                                                words[
                                                    entry.normalized
                                                ]

                                            if (
                                                old == null ||
                                                entry.frequency >
                                                old.frequency
                                            ) {
                                                words[
                                                    entry.normalized
                                                ] = entry
                                            }
                                        }
                                }

                                line.startsWith("bigram=") -> {

                                    val parent =
                                        currentParentWord
                                            ?: return@forEach

                                    parseBigramEntry(line)
                                        ?.let { entry ->

                                            bigrams
                                                .getOrPut(parent) {
                                                    ArrayList()
                                                }
                                                .add(entry)
                                        }
                                }
                            }
                        }
                    }
                }

            val sortedWords =
                words
                    .values
                    .sortedBy {
                        it.normalized
                    }

            val finalizedBigrams =
                bigrams.mapValues { (_, list) ->

                    list
                        .groupBy {
                            it.normalized
                        }
                        .map { (_, duplicates) ->

                            duplicates.maxBy {
                                it.frequency
                            }
                        }
                }

            val result =
                DictionaryData(
                    words = sortedWords,
                    unigramByWord = words,
                    bigrams = finalizedBigrams
                )

            loadedData = result

            Log.i(
                TAG,
                "Local dictionary loaded: " +
                    "${sortedWords.size} words, " +
                    "${finalizedBigrams.size} bigram roots " +
                    "in ${SystemClock.elapsedRealtime() - start}ms"
            )

            return result
        }
    }

    // =========================================================
    // PARSING
    // =========================================================

    private fun parseWordEntry(
        line: String
    ): WordEntry? {

        if (
            line.contains(
                "not_a_word=true"
            )
        ) {
            return null
        }

        val word =
            extractValue(
                line,
                "word="
            )
                ?: return null

        val frequency =
            extractFrequency(line)
                ?: return null

        if (
            frequency <= 0 ||
            word.isEmpty() ||
            word.length > 48
        ) {
            return null
        }

        return WordEntry(
            word = word,
            normalized =
                word.lowercase(Locale.ROOT),
            frequency = frequency
        )
    }

    private fun parseBigramEntry(
        line: String
    ): BigramEntry? {

        val word =
            extractValue(
                line,
                "bigram="
            )
                ?: return null

        val frequency =
            extractFrequency(line)
                ?: return null

        if (
            frequency <= 0 ||
            word.isEmpty() ||
            word.length > 48
        ) {
            return null
        }

        return BigramEntry(
            word = word,
            normalized =
                word.lowercase(Locale.ROOT),
            frequency = frequency
        )
    }

    private fun extractValue(
        line: String,
        prefix: String
    ): String? {

        val marker =
            line.indexOf(",f=")

        if (
            !line.startsWith(prefix) ||
            marker <= prefix.length
        ) {
            return null
        }

        return line
            .substring(
                prefix.length,
                marker
            )
            .trim()
            .takeIf {
                it.isNotEmpty()
            }
    }

    private fun extractFrequency(
        line: String
    ): Int? {

        return Regex(
            "(?:^|,)f=(\\d+)(?:,|$)"
        )
            .find(line)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    // =========================================================
    // BINARY SEARCH
    // =========================================================

    private fun lowerBound(
        entries: List<WordEntry>,
        prefix: String
    ): Int {

        var low = 0
        var high = entries.size

        while (low < high) {

            val middle =
                (low + high) ushr 1

            if (
                entries[middle].normalized <
                prefix
            ) {
                low = middle + 1
            } else {
                high = middle
            }
        }

        return low
    }

    // =========================================================
    // CAPITALIZATION
    // =========================================================

    private fun applyTypedCapitalization(
        typedPrefix: String,
        suggestion: String
    ): String {

        if (
            typedPrefix.length >= 2 &&
            typedPrefix.all {
                !it.isLetter() ||
                it.isUpperCase()
            }
        ) {
            return suggestion.uppercase(
                Locale.ROOT
            )
        }

        if (
            typedPrefix
                .firstOrNull()
                ?.isUpperCase() == true
        ) {

            return suggestion
                .replaceFirstChar {

                    if (it.isLowerCase()) {
                        it.titlecase(Locale.ROOT)
                    } else {
                        it.toString()
                    }
                }
        }

        return suggestion
    }
}
