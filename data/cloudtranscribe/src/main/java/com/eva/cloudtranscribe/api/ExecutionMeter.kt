package com.eva.cloudtranscribe.api

import com.eva.cloudtranscribe.model.TranscriptionExecution
import java.math.BigDecimal

/** Per-worker wall time and gateway-reported spend, including HTTP errors and retries. */
internal class ExecutionMeter(
	private val transport: HttpTransport,
	private val nanoTime: () -> Long = System::nanoTime,
) : HttpTransport {
	private val started = nanoTime()
	private var requests = 0
	private var priced = 0
	private var spend = BigDecimal.ZERO

	override fun postJson(url: String, headers: Map<String, String>, body: String, timeoutSeconds: Int): HttpResponse {
		synchronized(this) { requests++ }
		val response = transport.postJson(url, headers, body, timeoutSeconds)
		val amount = response.reportedCostUsd?.trim()?.takeIf { it.length <= 64 }?.toBigDecimalOrNull()
		if (amount != null && amount.signum() >= 0 && amount.scale() in -32..32) {
			synchronized(this) {
				priced++
				spend += amount
			}
		}
		return response
	}

	@Synchronized
	fun snapshot() = TranscriptionExecution(
		elapsedMs = (nanoTime() - started) / 1_000_000,
		requests = requests,
		pricedRequests = priced,
		knownUsd = if (priced > 0) spend.stripTrailingZeros().toPlainString() else null,
	)
}
