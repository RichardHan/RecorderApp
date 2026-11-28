pluginManagement {
	repositories {
		google()
		mavenCentral()
		gradlePluginPortal()
	}
}

dependencyResolutionManagement {
	repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
	repositories {
		google()
		mavenCentral()
		// Official sherpa-onnx Android AAR from its GitHub release (pinned in gradle/verification-metadata.xml).
		exclusiveContent {
			forRepository {
				ivy {
					url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/")
					patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
					metadataSources { artifact() }
				}
			}
			filter { includeModule("com.k2fsa", "sherpa-onnx") }
		}
	}
}

rootProject.name = "OpenRouterRecorder"

// The only Android app in this repository: the clean-room OpenRouter Recorder in studio/.
include(":studio")
