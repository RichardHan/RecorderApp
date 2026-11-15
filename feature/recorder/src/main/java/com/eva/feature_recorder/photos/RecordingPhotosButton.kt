package com.eva.feature_recorder.photos

import androidx.compose.runtime.*
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.eva.ui.photos.PhotoAttachments
import com.eva.ui.photos.PhotoItem
import kotlinx.coroutines.flow.flowOf

@Composable
fun RecordingPhotosButton(timeMs: () -> Long, viewModel: RecordingPhotosViewModel = hiltViewModel()) {
	val repo = viewModel.photos
	val owner by repo.activeOwner.collectAsState()
	val flow = remember(owner) { owner?.let(repo::observe) ?: flowOf(emptyList()) }
	val photos by flow.collectAsState(emptyList())
	PhotoAttachments(owner, timeMs, photos.map { PhotoItem(it.id, it.startMs, it.path, it.caption) }, repo::add, repo::caption, repo::delete)
}
