# Research Plan: Mobile Voice Mode

**Date**: 2026-04-18
**Input**: `project_plans/mobile-voice-mode/requirements.md`

## Subtopics

### 1. Stack
**Focus**: Evaluate STT options and LLM API client libraries available in Kotlin Multiplatform
**Search strategy**: Survey Android SpeechRecognizer, iOS SFSpeechRecognizer, OpenAI Whisper API, Android AICore / Gemini Nano, Apple Intelligence, KMP HTTP clients (Ktor), existing KMP LLM SDK wrappers
**Search cap**: 5 searches
**Trade-off axes**: KMP compatibility, offline capability, accuracy, cost, setup complexity
**Output**: `research/stack.md`

### 2. Features
**Focus**: Survey comparable voice-to-notes tools for UX and pipeline patterns
**Search strategy**: Audiopen, Whisper Memos, Notion AI voice, Bezel, Otter.ai — what do their capture flows look like, what formatting do they apply, how do they handle long rambles
**Search cap**: 4 searches
**Trade-off axes**: Capture UX, formatting quality, latency, outliner/structured output support
**Output**: `research/features.md`

### 3. Architecture
**Focus**: Plugin interface design for STT+LLM providers; commonMain pipeline; platform audio capture adapter pattern in KMP
**Search strategy**: KMP expect/actual for audio capture, provider plugin patterns in Kotlin, existing KMP AI SDK designs, Ktor client interceptor patterns
**Search cap**: 5 searches
**Trade-off axes**: Extensibility, testability, platform coupling, boilerplate overhead
**Output**: `research/architecture.md`

### 4. Pitfalls
**Focus**: Known failure modes and risks in voice capture on Android/iOS in KMP
**Search strategy**: Android microphone permissions + audio focus in KMP, iOS SFSpeechRecognizer authorization, on-device LLM model availability gates (AICore), LLM prompt reliability for structured output
**Search cap**: 4 searches
**Trade-off axes**: Permission failure modes, audio interruption handling, model gate fallbacks, prompt hallucination risk
**Output**: `research/pitfalls.md`

## Parallel Execution Plan

Spawn all 4 subagents simultaneously. Each writes its findings file independently.
Parent synthesizes after all complete → `research/synthesis.md`.
