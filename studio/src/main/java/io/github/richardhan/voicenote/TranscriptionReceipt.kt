package io.github.richardhan.voicenote

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties

internal data class TranscriptionReceipt(val model: String, val elapsedMs: Long, val costUsd: String?)
internal data class SavedTranscript(val text: String, val receipt: TranscriptionReceipt? = null)

internal fun reportedCostUsd(value: String?): String? {
	val amount = value?.trim()?.takeIf { it.length <= 64 }?.toBigDecimalOrNull() ?: return null
	if (amount.signum() < 0 || amount.scale() !in -32..64) return null
	return amount.stripTrailingZeros().toPlainString()
}

private fun receiptFile(file: File) = File(file.parentFile, file.name + ".usage")
private fun digest(text: String) = Base64.getEncoder().encodeToString(
	MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
)

@Synchronized
internal fun saveTranscription(file: File, text: String, receipt: TranscriptionReceipt) {
	require(text.isNotBlank())
	val destination = transcriptFile(file)
	val metadata = receiptFile(file)
	val temp = File.createTempFile("transcript-", ".part", destination.parentFile)
	try {
		temp.writeText(text)
		// A new request can return identical words at a different price.
		Files.deleteIfExists(metadata.toPath())
		Files.move(temp.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
		try {
			val values = Properties().apply {
				setProperty("digest", digest(text))
				setProperty("model", receipt.model)
				setProperty("elapsedMs", receipt.elapsedMs.toString())
				receipt.costUsd?.let { setProperty("costUsd", it) }
			}
			temp.bufferedWriter().use { values.store(it, null) }
			Files.move(temp.toPath(), metadata.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
		} catch (_: IOException) {
			// The transcript is already saved. A receipt failure must not invite a paid retry.
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
	if (model == null || elapsed == null || elapsed < 0 || values.getProperty("digest") != digest(text)) return SavedTranscript(text)
	return SavedTranscript(text, TranscriptionReceipt(model, elapsed, reportedCostUsd(values.getProperty("costUsd"))))
}
