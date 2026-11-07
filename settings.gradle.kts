pluginManagement {
	includeBuild("build-logic")
	repositories {
		google {
			content {
				includeGroupByRegex("com\\.android.*")
				includeGroupByRegex("com\\.google.*")
				includeGroupByRegex("androidx.*")
			}
		}
		mavenCentral()
		gradlePluginPortal()
	}
}
dependencyResolutionManagement {
	repositories {
		google()
		mavenCentral()
	}
	versionCatalogs{
		create("ktorLibs") {
			from("io.ktor:ktor-version-catalog:3.6.0")
		}
	}
}

rootProject.name = "RecorderApp"
include(":app")
include(":data:interactions")
include(":data:location")
include(":core:utils")
include(":data:datastore")
include(":data:database")
include(":data:player")
include(":data:recordings")
include(":data:recorder")
include(":data:worker")
include(":data:drive")
include(":data:cloudtranscribe")
include(":data:bookmarks")
include(":data:categories")
include(":data:use_case")
include(":data:compression")

include(":feature:recorder")
include(":core:ui")
include(":feature:settings")
include(":feature:categories")
include(":feature:recordings")
include(":feature:player")
include(":feature:widget")
include(":feature:editor")
include(":data:editor")
include(":feature:player-shared")
include(":feature:onboarding")
include(":testing:runtime")
include(":data:visualizer")
include(":data:transcribe")
include(":benchmark")
include(":baselineprofile")

// ---- sherpa-onnx runtime (pinned, hash-verified, never committed) ----
// Downloads the official v2.0.1 Android AAR into third_party/ (gitignored), verifies SHA-256,
// and extracts classes.jar for compileOnly use by library modules. Set SHERPA_ONNX_AAR to a local
// copy to build offline.
run {
	val version = "2.0.1"
	val sha = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
	val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$version/sherpa-onnx-$version.aar"
	val dir = file("third_party/sherpa-onnx").apply { mkdirs() }
	val aar = File(dir, "sherpa-onnx-$version.aar")
	fun sha256(f: File): String = java.security.MessageDigest.getInstance("SHA-256").let { md ->
		f.inputStream().use { input -> val buf = ByteArray(1 shl 16); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
		md.digest().joinToString("") { "%02x".format(it) }
	}
	if (!aar.exists() || sha256(aar) != sha) {
		val local = System.getenv("SHERPA_ONNX_AAR")?.let(::File)
		val tmp = File(dir, "download.tmp")
		if (local != null && local.exists()) local.copyTo(tmp, overwrite = true)
		else java.net.URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
		check(sha256(tmp) == sha) { "sherpa-onnx AAR checksum mismatch" }
		tmp.renameTo(aar)
	}
	val classes = File(dir, "classes.jar")
	if (!classes.exists() || classes.lastModified() < aar.lastModified()) {
		java.util.zip.ZipFile(aar).use { zip ->
			zip.getInputStream(zip.getEntry("classes.jar")).use { input -> classes.outputStream().use { input.copyTo(it) } }
		}
	}
}
