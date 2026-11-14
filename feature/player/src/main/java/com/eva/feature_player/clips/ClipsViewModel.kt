package com.eva.feature_player.clips

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eva.editor.domain.AudioTransformer
import com.eva.editor.domain.model.AudioClipConfig
import com.eva.editor.domain.model.AudioEditAction
import com.eva.recordings.domain.clips.ClipRangePolicy
import com.eva.recordings.domain.models.RecordingClipModel
import com.eva.recordings.domain.provider.PlayerFileProvider
import com.eva.recordings.domain.provider.RecordingClipsProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Short clips of one recording. Export is local only (the editor's transformer, crop to the user's range), writes a
 * new file in app-private storage and never modifies the source recording. Nothing is uploaded; sharing only opens
 * the system share sheet for a file the user picked.
 */
@HiltViewModel
class ClipsViewModel @Inject constructor(
	private val clipsProvider: RecordingClipsProvider,
	private val fileProvider: PlayerFileProvider,
	private val transformer: AudioTransformer,
	@ApplicationContext private val context: Context,
) : ViewModel() {

	var clips by mutableStateOf<List<RecordingClipModel>>(emptyList())
		private set

	var exporting by mutableStateOf(false)
		private set

	var message by mutableStateOf<String?>(null)
		private set

	/** Source recording uri, used only for previewing a range before export. */
	var sourceUri by mutableStateOf<String?>(null)
		private set

	private var recordingId: Long = -1L
	private var exportJob: Job? = null
	private var listJob: Job? = null

	fun bind(id: Long) {
		if (recordingId == id && listJob != null) return
		recordingId = id
		listJob?.cancel()
		listJob = clipsProvider.clipsAsFlow(id)
			.onEach { clips = it }
			.catch { message = "無法讀取片段清單" }
			.launchIn(viewModelScope)
		viewModelScope.launch {
			sourceUri = fileProvider.getAudioFileFromId(id).getOrNull()?.fileUri
		}
	}

	fun dismissMessage() {
		message = null
	}

	/**
	 * Exports [startMs, endMs] of the bound recording. Only one export runs at a time. Cancel or failure leaves no row
	 * and no partial file (the transformer deletes its temp file; the provider deletes on a failed save).
	 */
	fun export(startMs: Long, endMs: Long, totalMs: Long, title: String, maxMs: Long, shareAfterSave: Boolean) {
		if (exporting || recordingId <= 0L) return
		val problem = ClipRangePolicy.validate(startMs, endMs, totalMs, maxMs)
		if (problem != null) {
			message = ClipRangePolicy.problemMessage(problem, maxMs)
			return
		}
		val id = recordingId
		exporting = true
		exportJob = viewModelScope.launch {
			try {
				val source = fileProvider.getAudioFileFromId(id).getOrElse {
					message = "找不到原錄音，可能已被刪除或移走"
					return@launch
				}
				val config = AudioClipConfig(start = startMs.milliseconds, end = endMs.milliseconds)
				val exported = transformer.transformAudio(source, listOf(config to AudioEditAction.CROP)).getOrElse {
					message = "匯出失敗：${it.message ?: "未知錯誤"}"
					return@launch
				}
				val saved = clipsProvider.saveExportedClip(
					audioId = id,
					title = title.ifBlank { ClipRangePolicy.defaultTitle(startMs, endMs) },
					startMs = startMs,
					endMs = endMs,
					mimeType = source.mimeType,
					exportedFile = exported,
				).getOrElse {
					message = "儲存片段失敗（儲存空間可能不足）"
					return@launch
				}
				message = "已儲存片段，原錄音沒有變更"
				if (shareAfterSave) share(saved)
			} catch (e: CancellationException) {
				message = "已取消匯出"
				throw e
			} finally {
				exporting = false
				exportJob = null
			}
		}
	}

	fun cancelExport() {
		exportJob?.cancel()
	}

	fun rename(clip: RecordingClipModel, title: String) {
		viewModelScope.launch { clipsProvider.rename(clip, title) }
	}

	fun delete(clip: RecordingClipModel) {
		viewModelScope.launch {
			clipsProvider.delete(clip)
			message = "已刪除片段（原錄音保留）"
		}
	}

	/** Opens the system share sheet for one clip file through the app FileProvider. */
	fun share(clip: RecordingClipModel) {
		val file = File(clip.filePath)
		if (!file.exists()) {
			message = "片段檔已不存在"
			return
		}
		try {
			val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
			val send = Intent(Intent.ACTION_SEND).apply {
				type = clip.mimeType.ifBlank { "audio/*" }
				putExtra(Intent.EXTRA_STREAM, uri)
				putExtra(Intent.EXTRA_SUBJECT, clip.title)
				clipData = ClipData.newRawUri(clip.title, uri)
				addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			}
			val chooser = Intent.createChooser(send, "分享片段").apply {
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
			}
			context.startActivity(chooser)
		} catch (_: ActivityNotFoundException) {
			message = "沒有可分享的應用程式"
		} catch (e: Exception) {
			message = "無法分享：${e.message ?: "未知錯誤"}"
		}
	}

	override fun onCleared() {
		exportJob?.cancel()
		transformer.cleanUp()
		super.onCleared()
	}
}
