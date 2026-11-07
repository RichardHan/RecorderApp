plugins {
    alias(libs.plugins.recorderapp.android.library)
    alias(libs.plugins.recorderapp.hilt)
    alias(libs.plugins.kotlinx.serialization)
}

android {
    namespace = "com.eva.transcribe"

    defaultConfig {
        ndk {
            abiFilters.addAll(setOf("armeabi-v7a", "arm64-v8a"))
        }
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            // optional Linux JNI smoke test: SHERPA_LINUX_JNI_DIR=<dir with libsherpa-onnx-jni.so>
            System.getenv("SHERPA_LINUX_JNI_DIR")?.let { test.systemProperty("java.library.path", it) }
            listOf("SHERPA_LINUX_JNI_DIR", "SHERPA_SMOKE_SENSEVOICE", "SHERPA_SMOKE_QWEN", "SHERPA_SMOKE_FIRERED", "SHERPA_SMOKE_WAV")
                .forEach { k -> System.getenv(k)?.let { test.environment(k, it) } }
            test.maxHeapSize = "4g"
        }
    }
}

dependencies {
    implementation(project(":core:utils"))
    implementation(project(":data:database"))
    // vosk-model
    implementation(libs.vosk.android)
    // sherpa-onnx Kotlin API (runtime AAR with JNI is packaged by :app)
    compileOnly(files(rootProject.file("third_party/sherpa-onnx/classes.jar")))
    implementation("org.apache.commons:commons-compress:1.27.1")
    testImplementation(libs.junit)
    testImplementation(files(rootProject.file("third_party/sherpa-onnx/classes.jar")))
    testImplementation(libs.kotlinx.coroutines.test)
    // ktor
    implementation(ktorLibs.client.core)
    implementation(ktorLibs.client.cio)
    implementation(ktorLibs.client.logging)
    // okio
    implementation(libs.okio)
    implementation(libs.kotlinx.io.core)
    // serialization
    implementation(libs.kotlinx.serialization.json)
}
