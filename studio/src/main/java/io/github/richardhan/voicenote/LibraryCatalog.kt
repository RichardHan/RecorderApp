package io.github.richardhan.voicenote

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class RecordingMarker(val ms: Long, val label: String)

internal class LibraryCatalog(context: Context) {
	private val prefs = context.getSharedPreferences("studio_library", Context.MODE_PRIVATE)
	private val values = mutableStateMapOf<String, String>().apply {
		prefs.all.forEach { (key, value) -> if (value is String) put(key, value) }
	}

	fun value(file: File, field: String) = values["${file.name}/$field"].orEmpty()

	fun update(file: File, vararg fields: Pair<String, String>) {
		val editor = prefs.edit()
		for ((field, text) in fields) {
			val key = "${file.name}/$field"
			if (text.isEmpty()) {
				values.remove(key)
				editor.remove(key)
			} else {
				values[key] = text
				editor.putString(key, text)
			}
		}
		editor.apply()
	}

	fun markers(file: File): List<RecordingMarker> {
		val raw = value(file, "bookmarks")
		if (raw.isEmpty()) return emptyList()
		val array = JSONArray(raw)
		return List(array.length()) { i ->
			val item = array.getJSONObject(i)
			RecordingMarker(item.getLong("ms"), item.getString("label"))
		}
	}

	fun saveMarkers(file: File, markers: List<RecordingMarker>) {
		val data = JSONArray()
		markers.sortedBy { it.ms }.forEach { data.put(JSONObject().put("ms", it.ms).put("label", it.label)) }
		update(file, "bookmarks" to data.toString())
	}
}

internal fun markerCsv(markers: List<RecordingMarker>): String = buildString {
	append("milliseconds,label\r\n")
	for (marker in markers) {
		append(marker.ms).append(",\"").append(marker.label.replace("\"", "\"\"")).append("\"\r\n")
	}
}
