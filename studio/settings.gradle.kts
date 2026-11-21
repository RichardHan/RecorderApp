pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }

dependencyResolutionManagement {
	repositories {
		google()
		mavenCentral()
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
