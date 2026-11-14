import java.util.Properties

plugins {
	alias(libs.plugins.recorderapp.android.library)
	alias(libs.plugins.recorderapp.hilt)
}

val localProperties = Properties().apply {
	val file = rootProject.file("local.properties")
	if (file.exists()) file.inputStream().use(::load)
}

android {
	namespace = "com.eva.drive"
	defaultConfig {
		buildConfigField(
			"String",
			"DRIVE_WEB_CLIENT_ID",
			"\"${localProperties.getProperty("DRIVE_WEB_CLIENT_ID")?.trim()?.takeIf { it.isNotEmpty() } ?: System.getenv("DRIVE_WEB_CLIENT_ID")?.trim().orEmpty()}\"",
		)
	}
	buildFeatures {
		buildConfig = true
	}
	packaging {
		resources {
			// Google auth JAR indexes also reach this module's instrumented test APK.
			excludes += "/META-INF/INDEX.LIST"
		}
	}
	testOptions {
		unitTests.isReturnDefaultValues = true
	}
}

dependencies {
	implementation(libs.androidx.credentials)
	implementation(libs.androidx.credentials.play.services)
	implementation(libs.googleid)
	implementation(libs.gms.play.services.auth)
	implementation(libs.google.api.services.drive) {
		exclude("com.google.guava", "guava-jdk5")
		exclude("com.google.code.findbugs", "jsr305")
	}
	implementation(libs.google.http.client.android)
	implementation(libs.google.http.client.gson)
	implementation(libs.androidx.datastore.preferences)
	implementation(libs.work.runtime.ktx)
	implementation(libs.androidx.hilt.work)

	api(project(":core:utils"))
	implementation(project(":data:database"))
	implementation(project(":data:recordings"))

	testImplementation(libs.junit)
	testImplementation(libs.mockk)
	testImplementation(libs.work.runtime.testing)
}
