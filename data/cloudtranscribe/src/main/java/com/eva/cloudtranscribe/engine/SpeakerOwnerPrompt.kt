package com.eva.cloudtranscribe.engine

/**
 * How live key points and post-recording summaries should attribute speaker and owner.
 * Names may be used only when they actually appear in the transcript; never guessed.
 */
object SpeakerOwnerPrompt {
	const val EXAMPLE_FACT = "Hank：正處理 503 問題（負責：Hank）"
	const val EXAMPLE_TODO = "待辦：整理投影片 design（負責：Charlie）"

	const val RULE =
		"若逐字稿中明確出現說話者或負責人（自稱、被稱呼、被點名，或既有的說話者標籤），該點寫成「姓名：內容（負責：姓名）」，例如「$EXAMPLE_FACT」；待辦可寫「$EXAMPLE_TODO」。" +
			"沒有明確姓名就不要加，絕不可猜測或編造說話者與負責人。"
}
