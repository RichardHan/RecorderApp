package com.eva.transcribe.resources

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import com.eva.transcribe.data.ModelConstants
import com.eva.transcribe.data.repository.STTModelMetadataReader
import com.eva.transcribe.domain.models.STTModel
import com.eva.transcribe.domain.models.STTModelState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ResourceReader {
    /** IO only. StatFs uses the actual model storage volume, never the advertised phone capacity. */
    suspend fun read(context: Context, models: List<STTModel>): ResourceSnapshot = withContext(Dispatchers.IO) {
        val disk = runCatching { StatFs(context.filesDir.absolutePath) }.getOrNull()
        val mem = runCatching { ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) } }.getOrNull()
        val catalogue = STTModelMetadataReader(context).readMetaData().getOrNull()?.models?.associateBy { it.modelId }
        val resources = models.mapNotNull { model ->
            val m = catalogue?.get(model.modelId) ?: return@mapNotNull null
            val installed = model.localModelURI != null && model.state in setOf(STTModelState.DOWNLOADED, STTModelState.UPDATE_AVAILABLE)
            val partialDir = File(context.filesDir, "${ModelConstants.MODEL_FILES_DIR}/${model.modelId}.partial")
            val have = if (m.archiveFormat == "files") m.files.sumOf { f ->
                ResourcePolicy.partialBytes(f.sizeBytes,
                    File(partialDir, f.path).takeIf { it.isFile }?.length(),
                    File(partialDir, f.path + ".part").takeIf { it.isFile }?.length())
            } else 0L
            val extracted = m.files.takeIf { it.isNotEmpty() }?.sumOf { it.sizeBytes }
            val weights = if (model.engine.startsWith("sherpa-")) {
                if (installed) runCatching {
                    val uri = android.net.Uri.parse(model.localModelURI)
                    require(uri.scheme == "file")
                    val dir = File(requireNotNull(uri.path))
                    require(dir.isDirectory)
                    dir.walkTopDown().filter { it.isFile && it.name.endsWith(".onnx") }.sumOf { it.length() }.takeIf { it > 0 }
                }.getOrNull()
                else m.files.filter { it.path.endsWith(".onnx") }.takeIf { it.isNotEmpty() }?.sumOf { it.sizeBytes }
            } else null
            model.modelId to ModelResources(installed, m.sizeBytes, have,
                ResourcePolicy.downloadBytes(m.archiveFormat == "files", m.sizeBytes, extracted, have, installed),
                weights?.let(ResourcePolicy::ramBytes), weights)
        }.toMap()
        ResourceSnapshot(disk?.availableBytes, disk?.totalBytes, mem?.availMem, mem?.totalMem,
            mem?.lowMemory, mem?.threshold, android.os.Process.is64Bit(), resources)
    }
}
