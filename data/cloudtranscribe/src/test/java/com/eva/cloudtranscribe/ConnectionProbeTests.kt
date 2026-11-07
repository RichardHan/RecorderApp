package com.eva.cloudtranscribe

import com.eva.cloudtranscribe.api.*
import com.eva.cloudtranscribe.model.*
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import org.junit.Assert.*
import org.junit.Test

class ConnectionProbeTests {
    private val key = "SECRET_DO_NOT_RETURN"
    private val settings = CloudSettings(baseUrl = "https://service.example/v1", transcribeModel = "gemini-flash", reasoningEffort = ReasoningEffort.LOW)
    private val ok = """{"choices":[{"message":{"content":"OK"}}]}"""
    @Test fun openaiIsOneTinyTextRequestUsingConfiguredModel() {
        var calls = 0
        val t = HttpTransport { url, headers, body, timeout ->
            calls++
            assertEquals("https://service.example/v1/chat/completions", url)
            assertEquals("Bearer $key", headers["Authorization"])
            assertTrue(body.contains("\"model\":\"gemini-flash\""))
            assertTrue(body.contains("\"max_tokens\":16"))
            assertTrue(body.contains("\"reasoning_effort\":\"low\""))
            assertTrue(body.contains("Reply with OK only."))
            assertFalse(body.contains("input_audio")); assertFalse(body.contains("inline_data"))
            assertEquals(15, timeout)
            HttpResponse(200, ok)
        }
        val result = ConnectionProbe.run(settings, key, t)
        assertEquals(1, calls); assertTrue(result.contains("連線成功")); assertTrue(result.contains("service.example")); assertFalse(result.contains(key))
    }
    @Test fun nativeGeminiHasCorrectRouteHeaderAndNoAudio() {
        val result = ConnectionProbe.run(settings.copy(providerType = ProviderType.GEMINI_NATIVE), key, HttpTransport { url, headers, body, _ ->
            assertEquals("https://service.example/v1/models/gemini-flash:generateContent", url)
            assertEquals(key, headers["x-goog-api-key"])
            assertFalse(headers.containsKey("Authorization"))
            assertTrue(body.contains("\"maxOutputTokens\":16")); assertFalse(body.contains("inline_data"))
            HttpResponse(200, """{"candidates":[{"content":{"parts":[{"text":"OK"}]}}]}""")
        })
        assertTrue(result.contains("連線成功"))
    }
    @Test fun failuresNeverEchoResponseOrSecretsAndNeverRetry() {
        for (code in listOf(400,401,403,404,422,429,500,302)) {
            var calls = 0
            val result = ConnectionProbe.run(settings, key, HttpTransport { _,_,_,_ -> calls++; HttpResponse(code, "raw response unsupported model $key OTHER_CREDENTIAL Bearer leak") })
            assertEquals(1,calls); assertFalse(result.contains("連線成功")); assertFalse(result.contains(key)); assertFalse(result.contains("OTHER_CREDENTIAL")); assertFalse(result.contains("Bearer leak"))
        }
    }
    @Test fun timeoutAndTlsAreSafeReadableAndNotRetried() {
        for (e in listOf(SocketTimeoutException(key), SSLException(key), java.io.IOException(key))) {
            var calls=0
            val result=ConnectionProbe.run(settings,key,HttpTransport { _,_,_,_ -> calls++; throw e })
            assertEquals(1,calls);assertFalse(result.contains(key));assertTrue(result.contains("未重試"))
        }
    }
    @Test fun htmlEmptyMalformedAreNotSuccessAndValidationDoesNotCall() {
        for (body in listOf("<html>OK</html>","{}", "", """{"choices":[{"message":{"content":""}}]}""")) {
            assertFalse(ConnectionProbe.run(settings,key,HttpTransport { _,_,_,_ -> HttpResponse(200,body) }).contains("連線成功"))
        }
        val never=HttpTransport { _,_,_,_ -> error("must not call") }
        assertTrue(ConnectionProbe.run(settings,null,never).contains("Key"))
        assertTrue(ConnectionProbe.run(settings.copy(baseUrl=""),key,never).contains("Endpoint"))
        assertTrue(ConnectionProbe.run(settings.copy(baseUrl="https://x.example/?key=$key"),key,never).contains("Endpoint"))
    }
    @Test fun modelEffortRulesStillApply() {
        ConnectionProbe.run(settings.copy(transcribeModel="gemini-transcribe"),key,HttpTransport { _,_,body,_ -> assertFalse(body.contains("reasoning_effort")); HttpResponse(200,ok) })
    }
}
