package com.eva.feature_settings.files

import com.eva.feature_settings.composables.files.defaultBulkFileName
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class BulkTextExportItemTest {
	@Test
	fun default_file_name_has_date() {
		assertEquals("RecorderApp-文字匯出-20261115.zip", defaultBulkFileName(LocalDate.of(2026, 11, 15)))
	}
}
