package io.github.richardhan.voicenote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource

@Composable
internal fun CategoryManager(library: RecordingLibrary, onRenamed: (String, String) -> Unit) {
	var open by remember { mutableStateOf(false) }
	var selected by remember { mutableStateOf<String?>(null) }
	var name by remember { mutableStateOf("") }
	TextButton(onClick = { open = true }) { Text(stringResource(R.string.manage_categories)) }
	if (!open) return
	val catalog = library.catalog
	val files = library.files + library.trashedFiles
	val labels = (catalog.categoryNames + files.map { catalog.value(it, "category") }).filter(String::isNotBlank).distinct().sorted()
	fun save(value: String) {
		val old = selected
		if (old != null) {
			files.filter { catalog.value(it, "category") == old }.forEach { catalog.update(it, "category" to value) }
			onRenamed(old, value)
		}
		catalog.setCategories((labels - (old ?: "") + value).filter(String::isNotBlank).toSet())
		selected = null
		name = ""
	}
	AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(R.string.manage_categories)) }, text = {
		Column(Modifier.verticalScroll(rememberScrollState())) {
			FlowRow { labels.forEach { label -> FilterChip(selected = selected == label, onClick = { selected = label; name = label }, label = { Text(label) }) } }
			OutlinedTextField(name, { name = it }, singleLine = true, label = { Text(stringResource(R.string.category)) })
			TextButton(enabled = name.isNotBlank(), onClick = { save(name.trim()) }) { Text(stringResource(R.string.save_category)) }
			if (selected != null) TextButton(onClick = { save("") }) { Text(stringResource(R.string.remove_category)) }
		}
	}, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.done)) } })
}
