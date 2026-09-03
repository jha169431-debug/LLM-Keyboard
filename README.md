# LLM Keyboard

An experimental Android keyboard with local language-model assistance.

## Features

- Android IME / custom keyboard
- Fully local inference
- Qwen3 0.6B LiteRT-LM model
- Google AI Edge LiteRT-LM runtime
- GPU-backed model execution
- AOSP / LineageOS dictionary completion
- Learned next-word history
- Contextual next-word prediction
- Number and symbol keyboard
- Emoji panel
- Keyboard haptic feedback
- iOS-inspired compact keyboard UI
- No cloud inference required

## Current Model

`qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm`

The model is stored using Git LFS.

## Clone

    git clone https://github.com/jha169431-debug/LLM-Keyboard.git
    cd LLM-Keyboard
    git lfs pull

## Build

    cd Projects/LLMKeyboard
    ./gradlew :app:assembleDebug

APK output:

`Projects/LLMKeyboard/app/build/outputs/apk/debug/app-debug.apk`

## Install

    adb install -r app/build/outputs/apk/debug/app-debug.apk
    adb shell ime set com.example.myapplication/.LLMKeyboardService

## Keyboard

Current functionality includes:

- QWERTY alphabet layout
- `?123` number/symbol mode
- secondary `#+=` symbol page
- emoji panel
- suggestion strip
- haptic key feedback
- local word completion
- contextual next-word prediction

## Development Branch

`studio-quail-migration`

## Privacy

Language-model inference runs locally on the Android device. The keyboard does not require a remote LLM service for generation.

## Status

Experimental / active development.
