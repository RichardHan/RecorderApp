# AGENTS.md

Context for AI coding agents working in this repository.

## What this repository is

A single Android app, **OpenRouter Recorder** (`io.github.richardhan.voicenote`), written from scratch
(clean-room) in `studio/` and built as Gradle project `:studio`. The legacy app forked from upstream
(`com.eva.recorderapp`, modules `:app`, `core/`, `data/`, `feature/`, `build-logic/`, …) was removed; do not
reintroduce code from it.

## Layout

- `settings.gradle.kts` / `build.gradle.kts` (root): repositories, plugin versions (AGP 9.5.0, Kotlin Compose
  plugin 2.4.20) and `include(":studio")`.
- `studio/build.gradle.kts`: the app module. Optional `-Parm64Only=true` and `-PcompressNative=true` flags for
  sideload APKs.
- `studio/src/main/java/io/github/richardhan/voicenote/`: Compose UI (`StudioActivity`), recording library,
  cloud transcription (`SpeechConnection`, `TranscriptionReceipt`), import/share, AAC trim, local summary
  (LiteRT-LM), offline transcript search, offline speaker turns (sherpa-onnx).
- `studio/src/test`: JVM unit tests. `studio/src/androidTest`: instrumented tests.
- `gradle/verification-metadata.xml`: pins the SHA-256 of the sherpa-onnx AAR.
- `studio-research/`: offline research scripts (not part of the build).

## Build & test

JDK 17, Android SDK platform `android-37.0` and build-tools `37.0.0`. From the repo root:

```sh
./gradlew test lintDebug assembleDebug
./gradlew connectedDebugAndroidTest   # needs an emulator or device
```

Unit tests must not call real network or paid APIs.

## Conventions

- Strings live in `values`, `values-b+zh+Hant`, `values-b+zh+Hans` and `values-ja`; add every new string to all four.
- Never commit secrets, keystores, `local.properties` or build outputs.
- `manual_apk_build.yml` stays `workflow_dispatch`-only and uploads artifacts only (never publishes releases).
