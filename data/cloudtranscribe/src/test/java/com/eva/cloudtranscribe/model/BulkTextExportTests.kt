package com.eva.cloudtranscribe.model

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkTextExportTests {
	private val prov = Provenance(engine = "e", model = "m")

	private fun unzip(bytes: ByteArray): Map<String, String> {
		val out = LinkedHashMap<String, String>()
		ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
			while (true) {
				val e = z.nextEntry ?: break
				out[e.name] = z.readBytes().toString(Charsets.UTF_8)
			}
		}
		return out
	}

	@Test
	fun zip_has_index_and_folders_only_for_recordings_with_text() {
		val entries = listOf(
			BulkExportEntry(1, "週會", "2026-11-14T10:00", 65_000, "T1", "- S1"),
			BulkExportEntry(2, "空的", "2026-11-14T11:00", 5_000, null, null),
			BulkExportEntry(3, "只有摘要", "2026-11-14T12:00", 1_000, null, "- S3"),
		)
		val buf = ByteArrayOutputStream()
		assertEquals(2, BulkTextExport.write(entries, buf))
		val files = unzip(buf.toByteArray())
		assertEquals(listOf("index.csv", "1_週會/transcript.txt", "1_週會/summary.md", "3_只有摘要/summary.md"), files.keys.toList())
		assertEquals("T1", files["1_週會/transcript.txt"])
		val csv = files.getValue("index.csv")
		assertTrue(csv.startsWith("\uFEFFrecording_id,title"))
		assertTrue(csv.contains("2,空的,2026-11-14T11:00,5,false,false"))
	}

	@Test
	fun csv_escaping() {
		assertEquals("plain", BulkTextExport.csvField("plain"))
		assertEquals("\"a,b\"", BulkTextExport.csvField("a,b"))
		assertEquals("\"say \"\"hi\"\"\"", BulkTextExport.csvField("say \"hi\""))
		assertEquals("\"x\ny\"", BulkTextExport.csvField("x\ny"))
	}

	@Test
	fun folder_names_are_cleaned_and_cut() {
		assertEquals("7_a b c", BulkTextExport.folderName(BulkExportEntry(7, "a/b:c", "", 0, null, null)))
		assertEquals("8", BulkTextExport.folderName(BulkExportEntry(8, " ** ", "", 0, null, null)))
		assertEquals(2 + BulkTextExport.MAX_FOLDER_TITLE, BulkTextExport.folderName(BulkExportEntry(9, "字".repeat(80), "", 0, null, null)).length)
	}

	@Test
	fun transcript_uses_default_selection_and_existing_render_and_summary_is_latest_success() {
		val offline = TranscriptRecord("o", 1, TranscriptSource.OFFLINE, prov, 1, status = RunStatus.SUCCESS, text = "離線")
		val cloud = TranscriptRecord("c", 1, TranscriptSource.CLOUD, prov, 2, status = RunStatus.SUCCESS, text = "雲端")
		assertEquals(TranscriptTextExport.render(cloud), BulkTextExport.transcriptText(listOf(offline, cloud)))
		assertNull(BulkTextExport.transcriptText(emptyList()))
		val sums = listOf(
			SummaryRecord("a", 1, "c", prov, 3, RunStatus.FAILED, "壞"),
			SummaryRecord("b", 1, "c", prov, 2, RunStatus.SUCCESS, "新"),
			SummaryRecord("d", 1, "c", prov, 1, RunStatus.SUCCESS, "舊"),
		)
		assertEquals("新", BulkTextExport.summaryText(sums))
	}
}
