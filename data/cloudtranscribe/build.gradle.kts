plugins {
	alias(libs.plugins.recorderapp.android.library)
	alias(libs.plugins.recorderapp.hilt)
	alias(libs.plugins.kotlinx.serialization)
}

android {
	namespace = "com.eva.cloudtranscribe"
	testOptions {
		unitTests.isReturnDefaultValues = true
		unitTests.all { t -> listOf("MP3_ROUNDTRIP_WAV", "MP3_ROUNDTRIP_OUT").forEach { k -> System.getenv(k)?.let { t.environment(k, it) } } }
	}
}

dependencies {
	implementation(project(":core:utils"))
	implementation(project(":data:recordings"))
	implementation(project(":data:transcribe"))
	implementation(libs.kotlinx.serialization.json)
	implementation(libs.androidx.datastore.preferences)
	implementation(libs.work.runtime.ktx)
	implementation(libs.androidx.hilt.work)
	// Pure-Java LAME port (LGPL-2.1+) used only for MP3 encoding of upload chunks.
	implementation("de.sciss:jump3r:1.0.5")

	testImplementation(libs.junit)
	testImplementation(libs.kotlinx.coroutines.test)
}
