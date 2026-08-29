package com.example.myapplication

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class KeyboardLlm(
    private val modelPath: String
) {

    private var engine: Engine? = null

    suspend fun initialize() = withContext(Dispatchers.IO) {

        if (engine?.isInitialized() == true) {
            return@withContext
        }

        engine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU()
            )
        )

        engine?.initialize()
    }

    suspend fun predict(context: String): List<String> =
        withContext(Dispatchers.Default) {

            val currentEngine =
                engine ?: return@withContext emptyList()

            if (!currentEngine.isInitialized()) {
                return@withContext emptyList()
            }

            val conversationConfig =
                ConversationConfig(
                    systemInstruction = Contents.of(
                        """
                        You provide text suggestions for a smartphone keyboard.

                        Given text already typed by the user, return exactly
                        three likely continuations.

                        Return ONLY this format:

                        suggestion1|suggestion2|suggestion3

                        Do not write labels.
                        Do not explain.
                        Do not repeat the same suggestion.
                        Suggestions should usually be one word or a very short phrase.

                        Example:
                        Input: I am going to
                        Output: school|sleep|the store

                        Example:
                        Input: How are
                        Output: you|things going|you doing
                        """.trimIndent()
                    ),

                    samplerConfig = SamplerConfig(
                        topK = 20,
                        topP = 0.9,
                        temperature = 0.7
                    )
                )

            currentEngine
                .createConversation(conversationConfig)
                .use { conversation ->

                    val response =
                        conversation.sendMessage(
                            """
                            Input: $context
                            Output:
                            """.trimIndent(),
                            maxOutputToken = 24
                        )

                    response
                        .toString()
                        .trim()
                        .split("|")
                        .map {
                            it.trim()
                                .removePrefix("\"")
                                .removeSuffix("\"")
                        }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .take(3)
                }
        }

    fun close() {

        val currentEngine = engine

        if (currentEngine?.isInitialized() == true) {
            currentEngine.close()
        }

        engine = null
    }
}