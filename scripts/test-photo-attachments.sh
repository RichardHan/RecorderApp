#!/usr/bin/env bash
set -euo pipefail
./gradlew :data:database:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.eva.database.DBMigrationsTests#migrate_14_to_15_preserves_metadata_and_cascades_photos' -PincludeEmulatorAbis=true --no-parallel --max-workers=2 --stacktrace
./gradlew :data:recordings:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.eva.recordings.photos.PhotoStorageTests -PincludeEmulatorAbis=true --no-parallel --max-workers=2 --stacktrace
