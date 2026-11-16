package com.eva.feature_recorder

import com.eva.cloudtranscribe.live.LiveToggleKind
import com.eva.feature_recorder.composable.liveToggleChipLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class RecorderLiveToggleChipsTest {
	@Test
	fun chip_labels_show_on_off() {
		assertEquals("本機字幕 關", liveToggleChipLabel(LiveToggleKind.LOCAL, false))
		assertEquals("本機字幕 開", liveToggleChipLabel(LiveToggleKind.LOCAL, true))
		assertEquals("雲端即時 開", liveToggleChipLabel(LiveToggleKind.CLOUD, true))
		assertEquals("Slack 關", liveToggleChipLabel(LiveToggleKind.SLACK, false))
	}
}
