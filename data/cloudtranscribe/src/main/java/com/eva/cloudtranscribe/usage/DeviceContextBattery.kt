package com.eva.cloudtranscribe.usage

import com.eva.cloudtranscribe.store.AtomicTextFile
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Battery fields of `v1/<recordingId>/device-context.json`.
 *
 * Compatible with the in-progress "record-start device context" work: unknown keys are preserved on write
 * (location, network, storage, richer battery fields) and ignored on read. This PR captures start/end
 * battery only, so the usage card can show drain even if that other PR is not merged yet.
 */
@Serializable
data class BatteryLevelSnapshot(
	val levelPercent: Int,
	val isCharging: Boolean = false,
	val capturedAtMillis: Long = 0L,
)

@Serializable
data class RecordingBatteryContext(
	val recordingId: Long = 0L,
	val capturedAtMillis: Long = 0L,
	val endedAtMillis: Long? = null,
	val batteryStart: BatteryLevelSnapshot? = null,
	val batteryEnd: BatteryLevelSnapshot? = null,
) {
	val drainPercent: Int?
		get() {
			val start = batteryStart?.levelPercent?.takeIf { it in 0..100 } ?: return null
			val end = batteryEnd?.levelPercent?.takeIf { it in 0..100 } ?: return null
			return (start - end).coerceAtLeast(0)
		}

	val wasCharging: Boolean get() = batteryStart?.isCharging == true || batteryEnd?.isCharging == true
}

data class BatteryUsageSummary(
	val recordingsWithData: Int = 0,
	val drainPercent: Int = 0,
	val chargingSessions: Int = 0,
) {
	val hasData: Boolean get() = recordingsWithData > 0
}

class DeviceContextBatteryStore(root: File) {
	private val base = File(root, "v1")
	private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
	private val lock = Any()

	fun pendingFile(): File = File(base, "pending-battery.json")

	fun contextFile(recordingId: Long): File = File(File(base, recordingId.toString()), "device-context.json")

	fun savePendingStart(snapshot: BatteryLevelSnapshot) = synchronized(lock) {
		base.mkdirs()
		AtomicTextFile.write(pendingFile(), json.encodeToString(BatteryLevelSnapshot.serializer(), snapshot))
	}

	fun readPendingStart(): BatteryLevelSnapshot? = synchronized(lock) {
		val file = pendingFile()
		if (!file.exists()) return null
		read(file)?.let { runCatching { json.decodeFromString(BatteryLevelSnapshot.serializer(), it) }.getOrNull() }
	}

	fun clearPending() { synchronized(lock) { pendingFile().delete() } }

	fun finalizeRecording(
		recordingId: Long,
		start: BatteryLevelSnapshot?,
		end: BatteryLevelSnapshot?,
		capturedAtMillis: Long,
		endedAtMillis: Long,
	) {
		if (recordingId <= 0L) return
		synchronized(lock) {
			val file = contextFile(recordingId)
			val merged = mergeBatteryJson(
				existing = read(file),
				recordingId = recordingId,
				start = start,
				end = end,
				capturedAtMillis = capturedAtMillis,
				endedAtMillis = endedAtMillis,
			)
			AtomicTextFile.write(file, merged)
			pendingFile().delete()
		}
	}

	fun readContext(recordingId: Long): RecordingBatteryContext? =
		readContextFile(contextFile(recordingId))

	fun allContexts(): List<RecordingBatteryContext> {
		val dirs = base.listFiles { f -> f.isDirectory && f.name.toLongOrNull() != null }.orEmpty()
		return dirs.mapNotNull { dir -> readContextFile(File(dir, "device-context.json")) }
	}

	fun summarize(contexts: List<RecordingBatteryContext> = allContexts()): BatteryUsageSummary =
		summarizeSessions(contexts)

	private fun readContextFile(file: File): RecordingBatteryContext? =
		read(file)?.let { raw -> runCatching { json.decodeFromString(RecordingBatteryContext.serializer(), raw) }.getOrNull() }
			?.takeIf { it.batteryStart != null || it.batteryEnd != null }

	private fun read(file: File): String? = if (file.exists()) file.readText() else null

	companion object {
		fun mergeBatteryJson(
			existing: String?,
			recordingId: Long,
			start: BatteryLevelSnapshot?,
			end: BatteryLevelSnapshot?,
			capturedAtMillis: Long,
			endedAtMillis: Long?,
		): String {
			val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
			val root = existing?.let { raw ->
				runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
			}
			return buildJsonObject {
				root?.forEach { (key, value) -> put(key, value) }
				put("recordingId", recordingId)
				val previousCaptured = root?.get("capturedAtMillis")?.toString()?.toLongOrNull()
				val captured = when {
					capturedAtMillis > 0L -> capturedAtMillis
					previousCaptured != null && previousCaptured > 0L -> previousCaptured
					else -> 0L
				}
				if (captured > 0L) put("capturedAtMillis", captured)
				if (endedAtMillis != null) put("endedAtMillis", endedAtMillis)
				if (start != null) put("batteryStart", json.encodeToJsonElement(BatteryLevelSnapshot.serializer(), start))
				if (end != null) put("batteryEnd", json.encodeToJsonElement(BatteryLevelSnapshot.serializer(), end))
			}.toString()
		}

		fun summarizeSessions(contexts: List<RecordingBatteryContext>): BatteryUsageSummary {
			var withData = 0
			var drain = 0
			var charging = 0
			contexts.forEach { ctx ->
				val used = ctx.drainPercent ?: return@forEach
				if (ctx.wasCharging && used == 0) {
					charging += 1
					return@forEach
				}
				withData += 1
				drain += used
				if (ctx.wasCharging) charging += 1
			}
			return BatteryUsageSummary(recordingsWithData = withData, drainPercent = drain, chargingSessions = charging)
		}

		fun summarizeForPeriod(
			contexts: List<RecordingBatteryContext>,
			startInclusive: Long,
			endExclusive: Long,
		): BatteryUsageSummary {
			val inWindow = contexts.filter { ctx ->
				val at = ctx.endedAtMillis ?: ctx.batteryEnd?.capturedAtMillis ?: ctx.capturedAtMillis
				at in startInclusive until endExclusive
			}
			return summarizeSessions(inWindow)
		}
	}
}

/** Hook implemented by [RecordingBatteryCapture] and called from the recorder session coordinator. */
interface RecordingSessionCapture {
	fun onRecordingStarted()
	fun onRecordingSaved(recordingId: Long)
	fun onRecordingAbandoned()
}
