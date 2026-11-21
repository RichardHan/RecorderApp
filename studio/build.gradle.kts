plugins {
	id("com.android.application") version "9.5.0"
	id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
}
android {
	namespace = "io.github.richardhan.voicenote"
	compileSdk = 37
	defaultConfig {
		applicationId = "io.github.richardhan.voicenote"
		minSdk = 29
		targetSdk = 37
		versionCode = 1
		versionName = "0.1.0"
	}
	testOptions.unitTests.isIncludeAndroidResources = true
	buildFeatures { compose = true }
}
dependencies {
	testImplementation("junit:junit:4.13.2")
	implementation(platform("androidx.compose:compose-bom:2026.09.00"))
	implementation("androidx.compose.material3:material3")
	implementation("androidx.activity:activity-compose:1.13.0")
}
