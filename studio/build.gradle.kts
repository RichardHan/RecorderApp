plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.plugin.compose")
}

// Optional sideload flags (used by the manual APK workflow): -Parm64Only=true keeps only arm64-v8a native
// libraries, -PcompressNative=true stores them compressed in the APK. Defaults keep arm64-v8a + x86_64.
val arm64Only = providers.gradleProperty("arm64Only").map(String::toBoolean).getOrElse(false)
val compressNative = providers.gradleProperty("compressNative").map(String::toBoolean).getOrElse(false)

android {
	namespace = "io.github.richardhan.voicenote"
	compileSdk = 37
	defaultConfig {
		applicationId = "io.github.richardhan.voicenote"
		ndk.abiFilters += if (arm64Only) listOf("arm64-v8a") else listOf("arm64-v8a", "x86_64")
		minSdk = 29
		targetSdk = 37
		versionCode = 3
		versionName = "0.3.0"
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}
	testOptions.unitTests.isIncludeAndroidResources = true
	buildFeatures { compose = true }
	packaging { jniLibs { useLegacyPackaging = compressNative } }
}
dependencies {
	implementation("de.sciss:jump3r:1.0.5")
	implementation("com.google.android.gms:play-services-auth:22.0.0")
	implementation("androidx.work:work-runtime:2.12.0")
	androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
	androidTestImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
	androidTestImplementation("androidx.work:work-testing:2.12.0")
	androidTestImplementation("androidx.test:runner:1.7.0")
	androidTestImplementation("androidx.test.ext:junit:1.3.0")
	testImplementation("junit:junit:4.13.2")
	implementation(platform("androidx.compose:compose-bom:2026.09.00"))
	implementation("androidx.compose.material3:material3")
	implementation("androidx.activity:activity-compose:1.13.0")
	implementation("com.alphacephei:vosk-android:0.3.75")
	implementation("com.k2fsa:sherpa-onnx:2.0.1@aar")
	implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")
}
