package com.example.myapplication

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LLMKeyboardService : InputMethodService() {

    companion object {
        private const val TAG = "LLMKeyboard"

        // Wait after typing stops before requesting a prediction.
        private const val PREDICTION_DELAY_MS = 60L

        // Maximum text before cursor sent to Qwen.
        private const val CONTEXT_LENGTH = 256
    }

    private var isShifted = false

    // =========================================================
    // LLM STATE
    // =========================================================

    private val llmScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default
        )

    private var keyboardLlm: KeyboardLlm? = null

    @Volatile
    private var llmReady = false

    /*
     * Fast AOSP dictionary path for per-keystroke completion.
     *
     * The dictionary is loaded lazily on a background coroutine
     * the first time suggestions are requested.
     */
    private val localSuggestionEngine by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED
    ) {
        LocalSuggestionEngine(
            assets
        )
    }

    private var llmInitStarted = false

    private val userHistoryPredictor by lazy(
        LazyThreadSafetyMode.SYNCHRONIZED
    ) {
        UserHistoryPredictor(
            this
        )
    }

    // =========================================================
    // PREDICTION PIPELINE
    // =========================================================

    private data class PredictionRequest(
        val context: String,
        val generation: Long
    )

    /*
     * Only this debounce job gets cancelled while typing.
     * Running Qwen inference is NEVER cancelled by new keypresses.
     */
    private var debounceJob: Job? = null

    /*
     * One persistent worker owns Qwen inference.
     */
    private var predictionWorkerJob: Job? = null

    /*
     * CONFLATED = while Qwen is busy, retain only the newest request.
     */
    private val predictionRequests =
        Channel<PredictionRequest>(
            capacity = Channel.CONFLATED
        )

    /*
     * Every text change increments this.
     *
     * When an old inference finishes, its generation is compared
     * with the latest generation. Old results are discarded.
     */
    private var predictionGeneration = 0L

    // =========================================================
    // SUGGESTION BAR
    // =========================================================

    private var suggestionButtons:
            List<Button> = emptyList()

    /*
     * The suggestion strip must consume zero height when empty.
     */
    private var suggestionRowView: View? = null


    // =========================================================
    // CREATE KEYBOARD
    // =========================================================

    override fun onCreateInputView(): View {

        Log.d(
            TAG,
            "onCreateInputView() called"
        )

        val keyboardView =
            layoutInflater.inflate(
                R.layout.keyboard_view,
                null
            )

        setupKeys(
            keyboardView
        )

        /*
         * Wrap existing keyboard:
         *
         * ┌──────────────────────┐
         * │ AI suggestions       │
         * ├──────────────────────┤
         * │ existing keyboard    │
         * └──────────────────────┘
         */

        val root =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setBackgroundResource(R.drawable.bg_glass_keyboard)
            }

        val suggestionRow =
            createSuggestionRow()

        root.addView(
            suggestionRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            )
        )

        root.addView(
            keyboardView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        initializeLlmIfNeeded()

        Log.d(
            TAG,
            "Keyboard view created successfully"
        )

        return root
    }

    // =========================================================
    // CREATE SUGGESTION ROW
    // =========================================================

    private fun createSuggestionRow(): View {

        val row =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.HORIZONTAL

                setPadding(
                    dp(8),
                    0,
                    dp(8),
                    0
                )

                setBackgroundResource(
                    R.drawable.bg_glass_suggestion_bar
                )

                /*
                 * Critical UI behavior:
                 *
                 * No predictions = no suggestion-row height.
                 */
                visibility =
                    View.GONE
            }

        val buttons =
            mutableListOf<Button>()

        repeat(3) {

            val button =
                Button(this).apply {

                    /*
                     * Never reserve UI space for fake status text.
                     */
                    text = ""

                    isAllCaps =
                        false

                    textSize =
                        15f

                    typeface =
                        android.graphics.Typeface.create(
                            "sans-serif-medium",
                            android.graphics.Typeface.NORMAL
                        )

                    setTextColor(
                        Color.rgb(
                            35,
                            42,
                            49
                        )
                    )

                    gravity =
                        android.view.Gravity.CENTER

                    includeFontPadding =
                        false

                    minWidth =
                        0

                    minHeight =
                        0

                    setBackgroundResource(
                        R.drawable.bg_glass_suggestion
                    )

                    stateListAnimator =
                        null

                    elevation =
                        0f

                    setPadding(
                        dp(8),
                        0,
                        dp(8),
                        0
                    )

                    isEnabled =
                        false

                    setOnClickListener {

                        val suggestion =
                            text
                                ?.toString()
                                ?.trim()
                                .orEmpty()

                        if (
                            suggestion.isNotBlank()
                        ) {

                            insertSuggestion(
                                suggestion
                            )
                        }
                    }
                }

            row.addView(
                button,
                LinearLayout.LayoutParams(
                    0,
                    dp(38),
                    1f
                ).apply {

                    setMargins(
                        dp(2),
                        0,
                        dp(2),
                        0
                    )
                }
            )

            buttons.add(
                button
            )
        }

        suggestionButtons =
            buttons

        suggestionRowView =
            row

        return row
    }

    // =========================================================
    // INITIALIZE QWEN
    // =========================================================

    private fun initializeLlmIfNeeded() {

        if (llmInitStarted) {

            Log.d(
                TAG,
                "LLM initialization already started. Ready=$llmReady"
            )

            if (llmReady) {

                startPredictionWorker()

                schedulePrediction(
                    delayMs = 100L
                )
            }

            return
        }

        llmInitStarted = true

        val modelFile =
            File(
                filesDir,
                "models/model.litertlm"
            )

        llmScope.launch {

            try {

                /*
                 * The model is packaged inside:
                 *
                 * assets/models/model.litertlm
                 *
                 * LiteRT-LM needs a real filesystem path, so copy it
                 * into app-private storage the first time the IME runs.
                 *
                 * The 347 MB copy happens on Dispatchers.IO so the
                 * keyboard UI is not frozen.
                 */
                withContext(
                    Dispatchers.IO
                ) {

                    val modelDirectory =
                        modelFile.parentFile
                            ?: throw IllegalStateException(
                                "Model directory unavailable"
                            )

                    if (
                        !modelDirectory.exists() &&
                        !modelDirectory.mkdirs()
                    ) {
                        throw IllegalStateException(
                            "Unable to create model directory: ${modelDirectory.absolutePath}"
                        )
                    }

                    if (
                        !modelFile.exists() ||
                        modelFile.length() == 0L
                    ) {

                        Log.i(
                            TAG,
                            "Bundled model not installed yet. Copying asset..."
                        )

                        val temporaryFile =
                            File(
                                modelDirectory,
                                "model.litertlm.tmp"
                            )

                        if (temporaryFile.exists()) {
                            temporaryFile.delete()
                        }

                        assets
                            .open(
                                "models/model.litertlm"
                            )
                            .use { input ->

                                temporaryFile
                                    .outputStream()
                                    .buffered(
                                        1024 * 1024
                                    )
                                    .use { output ->

                                        input.copyTo(
                                            output,
                                            1024 * 1024
                                        )
                                    }
                            }

                        if (
                            !temporaryFile.exists() ||
                            temporaryFile.length() == 0L
                        ) {
                            temporaryFile.delete()

                            throw IllegalStateException(
                                "Bundled model copy produced an empty file"
                            )
                        }

                        /*
                         * Never expose a half-copied model as the final
                         * model file. Install the completed temp file
                         * atomically when possible.
                         */
                        if (
                            modelFile.exists() &&
                            !modelFile.delete()
                        ) {
                            temporaryFile.delete()

                            throw IllegalStateException(
                                "Unable to replace existing model"
                            )
                        }

                        if (
                            !temporaryFile.renameTo(
                                modelFile
                            )
                        ) {

                            temporaryFile.copyTo(
                                target = modelFile,
                                overwrite = true
                            )

                            temporaryFile.delete()
                        }

                        Log.i(
                            TAG,
                            "Bundled model copied successfully: ${modelFile.length()} bytes"
                        )

                    } else {

                        Log.d(
                            TAG,
                            "Using existing model copy"
                        )
                    }
                }

                Log.d(
                    TAG,
                    "Model path: ${modelFile.absolutePath}"
                )

                Log.d(
                    TAG,
                    "Model exists: ${modelFile.exists()}"
                )

                Log.d(
                    TAG,
                    "Model size: ${modelFile.length()} bytes"
                )

                if (
                    !modelFile.exists() ||
                    modelFile.length() == 0L
                ) {
                    throw IllegalStateException(
                        "Model file missing or empty after asset installation"
                    )
                }

                val llm =
                    KeyboardLlm(
                        modelFile.absolutePath
                    )

                keyboardLlm =
                    llm

                Log.i(
                    TAG,
                    "Initializing Qwen..."
                )

                llm.initialize()

                llmReady = true

                /*
                 * Start exactly one inference worker.
                 */
                startPredictionWorker()

                Log.i(
                    TAG,
                    "Qwen initialized successfully"
                )

                withContext(
                    Dispatchers.Main
                ) {

                    clearSuggestions()

                    /*
                     * User may already have typed while model
                     * initialization was happening.
                     */
                    schedulePrediction(
                        delayMs = 100L
                    )
                }

            } catch (
                e: CancellationException
            ) {

                throw e

            } catch (
                e: Exception
            ) {

                llmReady = false
                llmInitStarted = false

                Log.e(
                    TAG,
                    "Qwen initialization failed",
                    e
                )

                withContext(
                    Dispatchers.Main
                ) {

                    showLlmStatus(
                        "AI unavailable"
                    )
                }
            }
        }
    }

    // =========================================================
    // START SINGLE QWEN WORKER
    // =========================================================

    private fun startPredictionWorker() {

        if (
            predictionWorkerJob
                ?.isActive == true
        ) {
            return
        }

        predictionWorkerJob =
            llmScope.launch {

                Log.d(
                    TAG,
                    "Prediction worker started"
                )

                for (
                request
                in predictionRequests
                ) {

                    try {

                        Log.d(
                            TAG,
                            "Running prediction: ${
                                request.context.takeLast(80)
                            }"
                        )

                        /*
                         * IMPORTANT:
                         *
                         * Once this starts, typing does NOT cancel it.
                         */
                        val predictions =
                            keyboardLlm
                                ?.predict(
                                    request.context
                                )
                                .orEmpty()

                        /*
                         * Text changed while Qwen was working.
                         *
                         * Result is now outdated, so don't display it.
                         */
                        if (
                            request.generation !=
                            predictionGeneration
                        ) {

                            Log.d(
                                TAG,
                                "Discarding stale prediction"
                            )

                            continue
                        }

                        Log.i(
                            TAG,
                            "LIVE PREDICTIONS: $predictions"
                        )

                        withContext(
                            Dispatchers.Main
                        ) {

                            showSuggestions(
                                predictions
                            )
                        }

                    } catch (
                        e: CancellationException
                    ) {

                        /*
                         * Expected only when service is shutting down.
                         */
                        throw e

                    } catch (
                        e: Exception
                    ) {

                        Log.e(
                            TAG,
                            "Prediction failed",
                            e
                        )
                    }
                }
            }
    }

    // =========================================================
    // DEBOUNCED PREDICTION REQUEST
    // =========================================================

    private fun schedulePrediction(
        delayMs: Long = PREDICTION_DELAY_MS
    ) {

        /*
         * Any new keystroke invalidates the suggestion generation
         * that was previously visible.
         */
        predictionGeneration++

        val generation =
            predictionGeneration

        /*
         * Cancel only the waiting debounce.
         *
         * Never cancel an already-running LiteRT-LM inference.
         * predictionGeneration will reject stale model output.
         */
        debounceJob
            ?.cancel()

        debounceJob =
            llmScope.launch {

                delay(
                    delayMs
                )

                /*
                 * InputConnection access belongs on Main.
                 */
                val context =
                    withContext(
                        Dispatchers.Main
                    ) {

                        currentInputConnection
                            ?.getTextBeforeCursor(
                                CONTEXT_LENGTH,
                                0
                            )
                            ?.toString()
                            .orEmpty()
                    }

                if (context.isBlank()) {

                    withContext(
                        Dispatchers.Main
                    ) {

                        if (
                            generation ==
                            predictionGeneration
                        ) {

                            clearSuggestions()
                        }
                    }

                    return@launch
                }

                /*
                 * Determine whether the cursor is inside a word.
                 *
                 * Examples:
                 *
                 * "hel"             -> "hel"
                 * "I need hel"      -> "hel"
                 * "I need help "    -> ""
                 */
                val currentWord =
                    context.takeLastWhile { character ->

                        character.isLetter() ||
                                character == '\'' ||
                                character == '-' ||
                                character == '’'
                    }

                // =================================================
                // FAST LOCAL COMPLETION
                // =================================================

                if (
                    currentWord.isNotEmpty()
                ) {

                    val suggestions =
                        try {

                            withContext(
                                Dispatchers.Default
                            ) {

                                localSuggestionEngine
                                    .suggest(
                                        prefix =
                                            currentWord,

                                        limit =
                                            3
                                    )
                            }

                        } catch (e: CancellationException) {

                            throw e

                        } catch (e: Exception) {

                            Log.e(
                                TAG,
                                "Local suggestion lookup failed",
                                e
                            )

                            emptyList()
                        }

                    /*
                     * User typed again while dictionary lookup was
                     * running.
                     */
                    if (
                        generation !=
                        predictionGeneration
                    ) {

                        return@launch
                    }

                    Log.d(
                        TAG,
                        "LOCAL SUGGESTIONS prefix=$currentWord suggestions=$suggestions"
                    )

                    withContext(
                        Dispatchers.Main
                    ) {

                        showSuggestions(
                            suggestions
                        )
                    }

                    return@launch
                }

                // =================================================
                // FAST LOCAL NEXT-WORD PREDICTION
                // =================================================

                val previousWord =
                    context
                        .trimEnd()
                        .takeLastWhile { character ->

                            character.isLetter() ||
                                    character == '\'' ||
                                    character == '-' ||
                                    character == '’'
                        }

                val localNextWords =
                    if (
                        previousWord.isNotEmpty()
                    ) {

                        try {

                            withContext(
                                Dispatchers.Default
                            ) {

                                localSuggestionEngine
                                    .nextWords(
                                        previousWord =
                                            previousWord,

                                        limit =
                                            3
                                    )
                            }

                        } catch (
                            e: CancellationException
                        ) {

                            throw e

                        } catch (
                            e: Exception
                        ) {

                            Log.e(
                                TAG,
                                "Local next-word lookup failed",
                                e
                            )

                            emptyList()
                        }

                    } else {

                        emptyList()
                    }

                if (
                    generation !=
                    predictionGeneration
                ) {
                    return@launch
                }

                if (
                    localNextWords.isNotEmpty()
                ) {

                    Log.d(
                        TAG,
                        "LOCAL NEXT WORD previous=$previousWord suggestions=$localNextWords"
                    )

                    withContext(
                        Dispatchers.Main
                    ) {

                        showSuggestions(
                            localNextWords
                        )
                    }

                    /*
                     * Fast dictionary prediction wins.
                     * Qwen becomes fallback only.
                     */
                    return@launch
                }

                // =================================================
                // PERSONAL NEXT-WORD HISTORY
                // =================================================

                val learnedNextWords =
                    userHistoryPredictor
                        .suggest(
                            previousWord =
                                previousWord,

                            limit =
                                3
                        )

                if (
                    learnedNextWords.isNotEmpty()
                ) {

                    Log.d(
                        TAG,
                        "HISTORY NEXT WORD previous=$previousWord suggestions=$learnedNextWords"
                    )

                    withContext(
                        Dispatchers.Main
                    ) {

                        showSuggestions(
                            learnedNextWords
                        )
                    }

                    return@launch
                }

                // =================================================
                // QWEN FALLBACK
                // =================================================

                /*
                 * Qwen is intentionally used only at a word boundary.
                 *
                 * Example:
                 *
                 * "I need some "
                 *
                 * This keeps expensive ~500 ms inference away from
                 * normal per-letter typing.
                 */
                if (!llmReady) {

                    Log.d(
                        TAG,
                        "Skipping contextual prediction: LLM not ready"
                    )

                    withContext(
                        Dispatchers.Main
                    ) {

                        if (
                            generation ==
                            predictionGeneration
                        ) {

                            clearSuggestions()
                        }
                    }

                    return@launch
                }

                Log.d(
                    TAG,
                    "Queued contextual prediction: ${
                        context.takeLast(80)
                    }"
                )

                /*
                 * Channel.CONFLATED keeps only the newest pending
                 * contextual request while Qwen is busy.
                 */
                val result =
                    predictionRequests.trySend(
                        PredictionRequest(
                            context = context,
                            generation = generation
                        )
                    )

                if (
                    result.isFailure
                ) {

                    Log.w(
                        TAG,
                        "Unable to queue contextual prediction"
                    )
                }
            }
    }

    // =========================================================
    // DISPLAY PREDICTIONS
    // =========================================================

    private fun showSuggestions(
        predictions: List<String>
    ) {

        val visiblePredictions =
            predictions
                .map {
                    it.trim()
                }
                .filter {
                    it.isNotBlank()
                }
                .take(3)

        /*
         * No useful predictions means no strip at all.
         */
        if (
            visiblePredictions.isEmpty()
        ) {

            clearSuggestions()
            return
        }

        suggestionButtons
            .forEachIndexed { index, button ->

                val prediction =
                    visiblePredictions
                        .getOrNull(index)
                        .orEmpty()

                if (
                    prediction.isNotEmpty()
                ) {

                    button.text =
                        prediction

                    button.isEnabled =
                        true

                    button.visibility =
                        View.VISIBLE

                } else {

                    button.text =
                        ""

                    button.isEnabled =
                        false

                    /*
                     * Retain equal column geometry while the strip
                     * exists, but leave unused slots visually empty.
                     */
                    button.visibility =
                        View.INVISIBLE
                }
            }

        suggestionRowView
            ?.apply {

                if (
                    visibility !=
                    View.VISIBLE
                ) {

                    alpha =
                        0f

                    visibility =
                        View.VISIBLE

                    animate()
                        .alpha(1f)
                        .setDuration(90L)
                        .start()
                }
            }
    }

    private fun clearSuggestions() {

        suggestionButtons
            .forEach { button ->

                button.text =
                    ""

                button.isEnabled =
                    false

                button.visibility =
                    View.VISIBLE
            }

        /*
         * GONE, rather than INVISIBLE, is the important part:
         * the keyboard immediately gives the 38dp back to the app.
         */
        suggestionRowView
            ?.apply {

                animate()
                    .cancel()

                alpha =
                    1f

                visibility =
                    View.GONE
            }
    }

    private fun showLlmStatus(
        message: String
    ) {

        suggestionButtons
            .forEachIndexed { index, button ->

                button.text =
                    if (index == 0) {
                        message
                    } else {
                        ""
                    }

                button.isEnabled =
                    false
            }
    }

    // =========================================================
    // INSERT SELECTED PREDICTION
    // =========================================================

    private fun insertSuggestion(
        suggestion: String
    ) {

        val connection =
            currentInputConnection
                ?: return

        /*
         * The prediction that was visible when the user tapped the
         * button is now consumed.
         */
        predictionGeneration++

        debounceJob
            ?.cancel()

        val beforeCursor =
            connection
                .getTextBeforeCursor(
                    CONTEXT_LENGTH,
                    0
                )
                ?.toString()
                .orEmpty()

        /*
         * Word currently being composed.
         *
         * "I need hel"
         *         ^^^
         */
        val currentWord =
            beforeCursor.takeLastWhile { character ->

                character.isLetter() ||
                        character == '\'' ||
                        character == '-'
            }

        /*
         * Completion:
         *
         * hel + suggestion "hello"
         *
         * must become:
         *
         * hello
         *
         * NOT:
         *
         * hel hello
         */
        val replacesCurrentWord =
            currentWord.isNotEmpty() &&
                    suggestion.startsWith(
                        currentWord,
                        ignoreCase = true
                    )

        Log.d(
            TAG,
            "Suggestion selected: $suggestion currentWord=$currentWord replace=$replacesCurrentWord"
        )

        connection.beginBatchEdit()

        try {

            if (replacesCurrentWord) {

                /*
                 * Delete only the partially typed word.
                 */
                connection.deleteSurroundingText(
                    currentWord.length,
                    0
                )

                /*
                 * Commit the full completion and leave the cursor ready
                 * for the next word.
                 */
                connection.commitText(
                    "$suggestion ",
                    1
                )

            } else {

                /*
                 * Normal NEXT-WORD prediction.
                 */
                val previousCharacter =
                    beforeCursor.lastOrNull()

                val needsLeadingSpace =
                    previousCharacter != null &&
                            !previousCharacter.isWhitespace()

                val textToInsert =
                    buildString {

                        if (needsLeadingSpace) {
                            append(" ")
                        }

                        append(
                            suggestion
                        )

                        append(" ")
                    }

                connection.commitText(
                    textToInsert,
                    1
                )
            }

        } finally {

            connection.endBatchEdit()
        }

        // =================================================
        // LEARN AFTER SUGGESTION COMMIT
        // =================================================

        /*
         * insertSuggestion() adds its own trailing space, so this path
         * bypasses the physical SPACE key.
         *
         * Learn the completed word pair here as well.
         */
        val textAfterSuggestion =
            currentInputConnection
                ?.getTextBeforeCursor(
                    CONTEXT_LENGTH,
                    0
                )
                ?.toString()
                .orEmpty()
                .trimEnd()

        userHistoryPredictor
            .learnFromText(
                textAfterSuggestion
            )

        clearSuggestions()

        /*
         * We just inserted a trailing space, so immediately prepare
         * contextual next-word suggestions.
         */
        schedulePrediction(
            delayMs = 350L
        )
    }

    // =========================================================
    // SETUP KEYS
    // =========================================================

    private fun setupKeys(
        view: View
    ) {

        // =========================
        // EMOJI BUTTON
        // =========================

        view.findViewById<Button>(
            R.id.key_emoji
        ).setOnClickListener {

            Log.d(
                TAG,
                "Opening emoji panel"
            )

            showEmojiPanel(
                view
            )
        }

        // =========================
        // NUMBER + LETTER KEYS
        // =========================

        val keys =
            mapOf(

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
        // LETTER + NUMBER PRESSES
        // =========================

        for (
        (id, keyText)
        in keys
        ) {

            val button =
                view.findViewById<Button>(
                    id
                )

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

                Log.d(
                    TAG,
                    "Pressed: $output"
                )

                currentInputConnection
                    ?.commitText(
                        output,
                        1
                    )

                if (
                    isShifted &&
                    keyText.isNotEmpty() &&
                    keyText[0].isLetter()
                ) {

                    isShifted =
                        false

                    updateLetterDisplay(
                        view,
                        keys
                    )

                    updateShiftButton(
                        view
                    )
                }

                schedulePrediction()
            }
        }

        // =========================
        // BACKSPACE
        // =========================

        view.findViewById<Button>(
            R.id.key_backspace
        ).setOnClickListener {

            Log.d(
                TAG,
                "Pressed: BACKSPACE"
            )

            currentInputConnection
                ?.let {

                    UnicodeInputHelper
                        .deletePreviousGrapheme(
                            it
                        )
                }

            schedulePrediction()
        }

        // =========================
        // SPACE
        // =========================

        view.findViewById<Button>(
            R.id.key_space
        ).setOnClickListener {

            Log.d(
                TAG,
                "Pressed: SPACE"
            )

            currentInputConnection
                ?.let { connection ->

                    val beforeSpace =
                        connection
                            .getTextBeforeCursor(
                                CONTEXT_LENGTH,
                                0
                            )
                            ?.toString()
                            .orEmpty()

                    /*
                     * Learn only on an explicit word boundary.
                     *
                     * This prevents repeated schedulePrediction()
                     * calls from artificially increasing counts.
                     */
                    userHistoryPredictor
                        .learnFromText(
                            beforeSpace
                        )

                    connection.commitText(
                        " ",
                        1
                    )
                }

            /*
             * Space is a strong prediction boundary,
             * so use a shorter debounce.
             */
            schedulePrediction(
                delayMs = 250L
            )
        }

        // =========================
        // ENTER
        // =========================

        view.findViewById<Button>(
            R.id.key_enter
        ).setOnClickListener {

            Log.d(
                TAG,
                "Pressed: ENTER"
            )

            predictionGeneration++

            debounceJob
                ?.cancel()

            clearSuggestions()

            val editorInfo =
                currentInputEditorInfo

            if (
                editorInfo != null
            ) {

                val action =
                    editorInfo
                        .imeOptions and
                            android.view.inputmethod
                                .EditorInfo
                                .IME_MASK_ACTION

                if (
                    action != 0
                ) {

                    currentInputConnection
                        ?.performEditorAction(
                            action
                        )

                } else {

                    currentInputConnection
                        ?.commitText(
                            "\n",
                            1
                        )
                }

            } else {

                currentInputConnection
                    ?.commitText(
                        "\n",
                        1
                    )
            }
        }

        // =========================
        // SHIFT
        // =========================

        view.findViewById<Button>(
            R.id.key_shift
        ).setOnClickListener {

            isShifted =
                !isShifted

            Log.d(
                TAG,
                "Pressed: SHIFT -> shifted=$isShifted"
            )

            updateLetterDisplay(
                view,
                keys
            )

            updateShiftButton(
                view
            )
        }

        // =========================
        // SYMBOLS
        // =========================

        view.findViewById<Button>(
            R.id.key_symbols
        ).setOnClickListener {

            Log.d(
                TAG,
                "Pressed: SYMBOLS"
            )

            // TODO: symbols panel
        }
    }

    // =========================================================
    // EMOJI PANEL
    // =========================================================

    private fun showEmojiPanel(
        view: View
    ) {

        val parent =
            view as? ViewGroup
                ?: return

        /*
         * Hide normal keyboard contents.
         */
        for (
        i in
        0 until parent.childCount
        ) {

            parent
                .getChildAt(i)
                .visibility =
                View.GONE
        }

        val emojiView =
            layoutInflater.inflate(
                R.layout.emoji_panel,
                parent,
                false
            )

        parent.addView(
            emojiView
        )

        val emojiGrid =
            emojiView
                .findViewById<GridLayout>(
                    R.id.emoji_grid
                )

        val emojis =
            EmojiData.common

        for (
        (index, emoji)
        in emojis.withIndex()
        ) {

            val button =
                ImageButton(this)

            val drawableName =
                "emoji_%02d".format(
                    index + 1
                )

            val drawableId =
                resources.getIdentifier(
                    drawableName,
                    "drawable",
                    packageName
                )

            if (
                drawableId != 0
            ) {

                button.setImageResource(
                    drawableId
                )
            }

            button.setPadding(
                dp(4),
                dp(4),
                dp(4),
                dp(4)
            )

            button.scaleType =
                android.widget.ImageView
                    .ScaleType
                    .CENTER_INSIDE

            button.setBackgroundResource(
                R.drawable.bg_emoji_cell
            )

            button.backgroundTintList =
                null

            val density =
                resources
                    .displayMetrics
                    .density

            val params =
                GridLayout
                    .LayoutParams()

            params.width = 0

            params.height =
                (43 * density)
                    .toInt()

            params.columnSpec =
                GridLayout.spec(
                    GridLayout.UNDEFINED,
                    1f
                )

            val margin =
                (2 * density)
                    .toInt()

            params.setMargins(
                margin,
                margin,
                margin,
                margin
            )

            button.layoutParams =
                params

            button.setOnClickListener {

                Log.d(
                    TAG,
                    "Pressed emoji: $emoji"
                )

                currentInputConnection
                    ?.commitText(
                        emoji,
                        1
                    )

                schedulePrediction()
            }

            emojiGrid.addView(
                button
            )
        }

        // =========================
        // RETURN TO KEYBOARD
        // =========================

        emojiView
            .findViewById<Button>(
                R.id.key_emoji_back
            )
            .setOnClickListener {

                parent.removeView(
                    emojiView
                )

                for (
                i in
                0 until parent.childCount
                ) {

                    parent
                        .getChildAt(i)
                        .visibility =
                        View.VISIBLE
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

        for (
        (id, keyText)
        in keys
        ) {

            if (
                keyText.isEmpty() ||
                !keyText[0].isLetter()
            ) {

                continue
            }

            val button =
                view.findViewById<Button>(
                    id
                )

            button.text =
                if (
                    isShifted
                ) {

                    keyText.uppercase()

                } else {

                    keyText
                }
        }
    }

    // =========================================================
    // UPDATE SHIFT BUTTON
    // =========================================================

    private fun updateShiftButton(
        view: View
    ) {

        val shiftButton =
            view.findViewById<Button>(
                R.id.key_shift
            )

        shiftButton.text =
            if (
                isShifted
            ) {

                "SHIFT"

            } else {

                "⇧"
            }
    }

    // =========================================================
    // DP HELPER
    // =========================================================

    private fun dp(
        value: Int
    ): Int {

        return (
                value *
                        resources
                            .displayMetrics
                            .density
                ).toInt()
    }

    // =========================================================
    // CLEANUP
    // =========================================================

    override fun onDestroy() {

        Log.d(
            TAG,
            "Destroying keyboard service"
        )

        /*
         * Invalidate any pending result.
         */
        predictionGeneration++

        /*
         * Cancel only our jobs because the service itself is dying.
         */
        debounceJob
            ?.cancel()

        predictionRequests
            .close()

        predictionWorkerJob
            ?.cancel()

        try {

            keyboardLlm
                ?.close()

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "Error closing LLM",
                e
            )
        }

        keyboardLlm = null
        llmReady = false

        llmScope.cancel()

        super.onDestroy()
    }
}