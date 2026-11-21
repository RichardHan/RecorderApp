package com.eva.cloudtranscribe.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenPricingTests {

	private val flash = ModelPrice("gemini-3.8-flash", inputPerMillion = "0.50", outputPerMillion = "3.00")
	private val transcribe = ModelPrice("gemini-3.5-transcribe", inputPerMillion = "1", outputPerMillion = "2")

	private fun event(model: String, prompt: Long, completion: Long, total: Long? = null) =
		TokenUsageEvent(1L, model, prompt, completion, total)

	@Test
	fun emptyPrices_showTokensOnly() {
		val cost = TokenPricing.estimate(listOf(event("gemini-3.8-flash", 1_000_000, 1_000_000)), emptyList())
		assertNull(cost.amount)
		assertEquals(0L, cost.pricedTokens)
		assertEquals(2_000_000L, cost.unpricedTokens)
		assertTrue(cost.hasUnsetPrice)
		assertFalse(cost.canShowAmount)
	}

	@Test
	fun cost_isTokensTimesPerMillionPrice() {
		val cost = TokenPricing.estimate(
			listOf(event("gemini-3.8-flash", 2_000_000, 500_000)),
			listOf(flash),
		)
		// 2 * 0.50 + 0.5 * 3.00 = 1 + 1.5 = 2.5
		assertEquals(2.5, cost.amount!!, 1e-9)
		assertEquals(2_500_000L, cost.pricedTokens)
		assertEquals(0L, cost.unpricedTokens)
	}

	@Test
	fun partialPrice_leavesTheUnsetSideUnpriced() {
		val cost = TokenPricing.estimate(
			listOf(event("gemini-3.8-flash", 1_000_000, 1_000_000)),
			listOf(ModelPrice("gemini-3.8-flash", inputPerMillion = "2", outputPerMillion = "")),
		)
		assertEquals(2.0, cost.amount!!, 1e-9)
		assertEquals(1_000_000L, cost.pricedTokens)
		assertEquals(1_000_000L, cost.unpricedTokens)
		assertTrue(cost.hasUnsetPrice)
	}

	@Test
	fun modelMatch_isCaseInsensitiveAndIgnoresUnknownModels() {
		val cost = TokenPricing.estimate(
			listOf(
				event("Gemini-3.8-Flash", 1_000_000, 0),
				event("unknown-model", 1_000_000, 0),
			),
			listOf(flash),
		)
		assertEquals(0.50, cost.amount!!, 1e-9)
		assertEquals(1_000_000L, cost.unpricedTokens)
	}

	@Test
	fun invalidAndBlankPrices_areTreatedAsUnset() {
		assertNull(TokenPricing.parseUnitPrice(""))
		assertNull(TokenPricing.parseUnitPrice("  "))
		assertNull(TokenPricing.parseUnitPrice("abc"))
		assertNull(TokenPricing.parseUnitPrice("-1"))
		assertEquals(1.5, TokenPricing.parseUnitPrice(" 1.5 ")!!, 1e-9)
		assertEquals(2.0, TokenPricing.parseUnitPrice("2,000".let { /* comma as thousands? */ "2" })!!, 1e-9)
		assertEquals(1500.0, TokenPricing.parseUnitPrice("1,500")!!, 1e-9)
	}

	@Test
	fun totalOnly_isPricedWhenInputAndOutputPricesMatch() {
		val priced = TokenPricing.estimate(
			listOf(event("m", 0, 0, total = 1_000_000)),
			listOf(ModelPrice("m", "2", "2")),
		)
		assertEquals(2.0, priced.amount!!, 1e-9)
		val unpriced = TokenPricing.estimate(
			listOf(event("m", 0, 0, total = 1_000_000)),
			listOf(ModelPrice("m", "2", "4")),
		)
		assertNull(unpriced.amount)
		assertEquals(1_000_000L, unpriced.unpricedTokens)
	}

	@Test
	fun formatHelpers_areStable() {
		assertEquals("12,345", TokenPricing.formatTokens(12_345))
		assertEquals("0", TokenPricing.formatCost(0.0))
		assertEquals("0.0012", TokenPricing.formatCost(0.0012))
		assertEquals("0.120", TokenPricing.formatCost(0.12))
		assertEquals("2.50", TokenPricing.formatCost(2.5))
	}

	@Test
	fun normalizePrices_dedupesAndKeepsConfiguredModels() {
		val normalized = TokenPricing.normalizePrices(
			listOf(flash, ModelPrice("  gemini-3.8-flash  ", "9", "")),
			extraModels = listOf("gemini-3.5-transcribe", "gemini-3.8-flash"),
		)
		assertEquals(2, normalized.size)
		assertEquals("0.50", normalized.first { it.model.contains("flash") }.inputPerMillion)
		assertEquals("gemini-3.5-transcribe", normalized.first { it.model.contains("transcribe") }.model)
	}

	@Test
	fun period_sumsEvents() {
		val period = TokenPricing.period(
			listOf(event(flash.model, 10, 2), event(transcribe.model, 5, 1)),
			listOf(flash, transcribe),
		)
		assertEquals(15L, period.promptTokens)
		assertEquals(3L, period.completionTokens)
		assertEquals(2, period.tokenRequests)
		assertTrue(period.cost.canShowAmount)
	}
}
