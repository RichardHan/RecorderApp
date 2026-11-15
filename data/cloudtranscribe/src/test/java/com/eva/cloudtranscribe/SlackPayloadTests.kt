package com.eva.cloudtranscribe

import com.eva.cloudtranscribe.slack.*
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SlackPayloadTests {
	@Test fun fullChineseMessageSurvivesRecreationWithSmallWorkData() {
		val root = Files.createTempDirectory("slack-payload").toFile()
		try {
			val body = "中".repeat(3900) + "🎙️"
			val name = SlackPayloadStore(root).save(body)
			val data = slackWorkData(SlackOutgoing("session", SlackMessageKind.KEY_POINTS, body), name)
			assertTrue(data.toByteArray().size < 1024)
			assertNull(data.getString(SlackSendWorker.KEY_TEXT))
			assertEquals(body, SlackPayloadStore(root).read(name))
			SlackPayloadStore(root).delete(name)
			assertNull(SlackPayloadStore(root).read(name))
		} finally { root.deleteRecursively() }
	}

	@Test fun payloadLookupCannotEscapePrivateDirectory() {
		val root = Files.createTempDirectory("slack-payload").toFile()
		try {
			val outside = java.io.File(root, "keep.txt").apply { writeText("keep") }
			val store = SlackPayloadStore(java.io.File(root, "queue"))
			assertNull(store.read("../keep.txt"))
			store.delete("../keep.txt")
			assertEquals("keep", outside.readText())
		} finally { root.deleteRecursively() }
	}
}
