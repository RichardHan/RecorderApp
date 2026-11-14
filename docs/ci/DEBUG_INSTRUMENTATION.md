# Debug instrumentation regression checks

Run against an emulator after installing the Android SDK/NDK versions required by the project:

```sh
./gradlew :data:compression:connectedDebugAndroidTest \
  :data:database:connectedDebugAndroidTest \
  -PincludeEmulatorAbis=true --no-parallel --max-workers=2 --continue --stacktrace
```

The full CI command is `connectedDebugAndroidTest` with the same options. It includes all modules' debug tests. It intentionally does not build benchmark/profile release variants, which have separate optimization requirements. A successful build or JVM unit test run does not establish that device tests passed.

## Compression contract

`LZ4NativeCompression` must return round-tripped data for valid frames. Malformed, truncated and trailing-byte frames must fail consistently with the Kotlin API's `IllegalStateException` decode contract, for byte and float data. Keep `byteArray_invalidCompressedData_throws` and the added malformed-frame cases enabled. Native exception translation must preserve a Java exception already pending rather than replacing it.

Host C++ tests with a JNI test double can help isolate exception translation and sanitizer defects, but do not replace Android/JNI device tests.

## Migration fixture

The version 6-to-7 migration creates `stt_models_table`; it does not reset values in a newly inserted row. Verify existing tables survive, the new table starts empty, and a named-column insertion with `local_model_status = 2` reads back as 2 with the supplied model ID and size. Do not change production migration behavior to satisfy a contradictory fixture.

## Failure investigation

Read the exact workflow head SHA and per-module XML/report artifacts. A test-results publisher can succeed even when a Gradle compilation or test task failed. Keep setup/packaging errors, native failures, database assertions and emulator failures separate. Do not disable failing debug tests to obtain a green summary.
