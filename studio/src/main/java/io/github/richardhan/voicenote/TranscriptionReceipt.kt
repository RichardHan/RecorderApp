package io.github.richardhan.voicenote

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import org.json.JSONArray
import org.json.JSONObject

internal data class TranscriptionReceipt(val model: String, val elapsedMs: Long, val costUsd: String?)
internal data class SavedTranscript(val text: String, val receipt: TranscriptionReceipt? = null)

internal fun reportedCostUsd(value: String?): String? {
	val amount = value?.trim()?.takeIf { it.length <= 64 }?.toBigDecimalOrNull() ?: return null
	if (amount.signum() < 0 || amount.scale() !in -32..64) return null
	return amount.stripTrailingZeros().toPlainString()
}

private fun receiptFile(file: File) = File(file.parentFile, file.name + ".usage")
internal fun transcriptDigest(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())

private inline fun saveMetadata(temp: File, target: File, write: () -> Unit) {
	try {
		write()
		Files.move(temp.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
	} catch (_: IOException) {
		// The transcript is already saved. Missing metadata must not invite a paid retry.
	}
}

@Synchronized
internal fun saveTranscription(file: File, text: String, receipt: TranscriptionReceipt, segments: JSONArray? = null) {
	require(text.isNotBlank())
	val destination = transcriptFile(file)
	val metadata = receiptFile(file)
	val timing = timingFile(file)
	val temp = File.createTempFile("transcript-", ".part", destination.parentFile)
	try {
		temp.writeText(text)
		// Identical words can have different charges and segment boundaries on a new request.
		Files.deleteIfExists(metadata.toPath())
		Files.deleteIfExists(timing.toPath())
		Files.move(temp.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
		val hash = transcriptDigest(text)
		saveMetadata(temp, metadata) {
			val values = Properties().apply {
				setProperty("digest", Base64.getEncoder().encodeToString(hash))
				setProperty("model", receipt.model)
				setProperty("elapsedMs", receipt.elapsedMs.toString())
				receipt.costUsd?.let { setProperty("costUsd", it) }
			}
			temp.bufferedWriter().use { values.store(it, null) }
		}
		if (segments != null && segments.length() != 0) saveMetadata(temp, timing) {
			temp.writeText(JSONObject().put("digest", hash.toHexString()).put("segments", segments).toString())
		}
	} finally {
		temp.delete()
	}
}

@Synchronized
internal fun loadSavedTranscript(file: File): SavedTranscript {
	val text = transcriptFile(file).takeIf { it.isFile }?.readText().orEmpty()
	val metadata = receiptFile(file)
	if (text.isEmpty() || !metadata.isFile) return SavedTranscript(text)
	val values = Properties()
	try {
		metadata.bufferedReader().use { values.load(it) }
	} catch (_: IOException) {
		return SavedTranscript(text)
	} catch (_: IllegalArgumentException) {
		return SavedTranscript(text)
	}
	val model = values.getProperty("model")?.takeIf { it.isNotBlank() }
	val elapsed = values.getProperty("elapsedMs")?.toLongOrNull()
	if (model == null || elapsed == null || elapsed < 0 || values.getProperty("digest") != Base64.getEncoder().encodeToString(transcriptDigest(text))) return SavedTranscript(text)
	return SavedTranscript(text, TranscriptionReceipt(model, elapsed, reportedCostUsd(values.getProperty("costUsd"))))
}
