package com.eva.feature_recordings.recap

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentRecapTests {

	private val taipei = ZoneId.of("Asia/Taipei")
	// 2026-11-15 10:00 Asia/Taipei
	private val now = 1_794_708_000_000L
	private fun rec(id: Long, at: Long, title: String = "R$id") = RecapRecording(id, title, at, 65_000)

	@Test
	fun today_starts_at_local_midnight_and_week_is_seven_days_back() {
		val start = RecentRecap.periodStart(RecapPeriod.TODAY, now, taipei)
		assertEquals(now - 10 * 3_600_000L, start)
		assertEquals(now - 7 * 86_400_000L, RecentRecap.periodStart(RecapPeriod.WEEK, now, taipei))
	}

	@Test
	fun select_filters_inclusive_sorts_newest_first_and_caps() {
		val start = now - 1000
		val (picked, more) = RecentRecap.select(listOf(rec(1, start), rec(2, now), rec(3, start - 1), rec(4, now + 1), rec(5, now - 10)), start, now)
		assertEquals(listOf(2L, 5L, 1L), picked.map { it.id })
		assertEquals(0, more)
		val many = (1..60).map { rec(it.toLong(), now - it) }
		val (capped, cut) = RecentRecap.select(many, 0, now)
		assertEquals(RecentRecap.MAX_RECORDINGS, capped.size)
		assertEquals(10, cut)
		assertEquals(1L, capped.first().id)
	}

	@Test
	fun points_take_three_clean_bullets_and_skip_headings() {
		assertEquals(listOf("A", "B", "C"), RecentRecap.points("## 重點\n決議：\n- **A**\n* B\n1. C\n- D"))
		assertEquals(emptyList<String>(), RecentRecap.points("目前無重點"))
	}

	@Test
	fun entry_keeps_five_open_items_and_counts_the_rest() {
		val e = RecentRecap.entry(rec(1, now), null, (1..7).map { "t$it" })
		assertNull(e.points)
		assertEquals(listOf("t1", "t2", "t3", "t4", "t5"), e.openItems)
		assertEquals(2, e.moreOpenItems)
	}

	@Test
	fun markdown_is_stable() {
		val recap = Recap(
			listOf(
				RecentRecap.entry(rec(1, now, "週會"), "- 報價\n- 時程", listOf("寄合約")),
				RecentRecap.entry(rec(2, now - 3_600_000, "訪談"), null, emptyList()),
				RecentRecap.entry(rec(3, now - 7_200_000, "閒聊"), "目前無重點", emptyList()),
				RecapEntry(rec(4, now - 9_000_000, "壞掉"), null, emptyList(), 0, failed = true),
			),
			moreRecordings = 2,
		)
		val md = RecentRecap.toMarkdown(recap, RecapPeriod.WEEK, taipei)
		val expected = """
			# 近期回顧（最近 7 天）

			## 週會
			2026-11-15 10:00・01:05

			- 報價
			- 時程

			未完成待辦：
			- [ ] 寄合約

			## 訪談
			2026-11-15 09:00・01:05

			（尚無摘要）

			## 閒聊
			2026-11-15 08:00・01:05

			（沒有重點）

			## 壞掉
			2026-11-15 07:30・01:05

			（讀取失敗）

			另有 2 段錄音未列出
		""".trimIndent() + "\n"
		assertEquals(expected, md)
	}

	@Test
	fun empty_recap_and_long_durations() {
		assertTrue(RecentRecap.toMarkdown(Recap(emptyList(), 0), RecapPeriod.TODAY, taipei).contains("這段期間沒有錄音"))
		assertEquals("1:01:05", RecentRecap.duration(3_665_000))
	}
}
