plugins {
	alias(libs.plugins.recorderapp.android.library)
	alias(libs.plugins.recorderapp.hilt)
	alias(libs.plugins.recorderapp.compose.compiler)
}

android {
	namespace = "com.eva.feature_settings"
	buildFeatures {
		compose = true
	}
	packaging {
		resources {
			// two google-auth jars (via :data:drive) ship the same META-INF/INDEX.LIST and META-INF/DEPENDENCIES, which
			// breaks the androidTest APK merge; same exclusion list as :app
			excludes += "/META-INF/{AL2.0,LGPL2.1,INDEX.LIST,DEPENDENCIES}"
		}
	}
}


dependencies {
	implementation(project(":data:drive"))
	implementation(project(":data:cloudtranscribe"))
	implementation(libs.androidx.activity.compose)
	//navigation
	implementation(libs.androidx.navigation.compose)
	implementation(libs.androidx.hilt.navigation.compose)

	implementation(project(":core:ui"))
	implementation(project(":core:utils"))
	implementation(project(":data:recordings"))
	implementation(project(":data:datastore"))
	implementation(project(":data:transcribe"))
	implementation(project(":data:worker"))

	// compose ui test for the advanced cloud settings section
	androidTestImplementation(libs.androidx.ui.test.junit4)
}
