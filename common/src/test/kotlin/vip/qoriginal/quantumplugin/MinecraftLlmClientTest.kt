package vip.qoriginal.quantumplugin

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MinecraftLlmClientTest {
    @Test fun `Minecraft mention always requests fast without reasoning and carries an idempotency id`() {
        val body = JsonParser.parseString(MinecraftLlmClient.requestBody("数学证明与大型建筑规划")).asJsonObject
        assertEquals("fast", body["model"].asString)
        assertEquals("none", body["reasoning_effort"].asString)
        assertFalse(body["stream"].asBoolean)
        val headers = MinecraftLlmClient.requestHeaders("test-token", "Steve", "world,x:1 y:2 z:3", "20", "request-1")
        assertEquals("Bearer test-token", headers["Authorization"])
        assertEquals("Steve", headers["X-Minecraft-Name"])
        assertEquals("request-1", headers["X-Request-ID"])
    }

    @Test fun `paid quota response preserves the answer and actual units when weekly quota is exhausted`() {
        val reply = MinecraftLlmClient.parseResponse(Request.Response(200,
            """{"choices":[{"message":{"content":" 建议使用石砖。 "}}],"quota":{"limit":90,"used":90,"remaining":0,"paid_credits":792,"charged_units":20}}""",
            emptyMap()))
        assertEquals("建议使用石砖。", reply.answer())
        assertEquals(20, reply.chargedUnits())
    }

    @Test fun `weekly quota failures use weekly credits wording and case insensitive retry header`() {
        val error = assertFailsWith<MinecraftLlmClient.ApiException> {
            MinecraftLlmClient.parseResponse(Request.Response(429,
                """{"error":{"code":"weekly_quota_exceeded"}}""", mapOf("retry-after" to listOf("3600"))))
        }
        assertTrue(error.message!!.contains("本周额度与 Paid Credits"))
        assertTrue(error.message!!.contains("60 分钟"))
    }

    @Test fun `rate limits are distinct from quota exhaustion and never return a chat answer`() {
        val error = assertFailsWith<MinecraftLlmClient.ApiException> {
            MinecraftLlmClient.parseResponse(Request.Response(429,
                """{"error":{"code":"rate_limited"}}""", emptyMap()))
        }
        assertTrue(error.message!!.contains("过于频繁"))
        assertFalse(error.message!!.contains("Credits"))
    }

    @Test fun `HTTP failures and error envelopes with 200 cannot be broadcast as successful replies`() {
        for ((status, body) in listOf(503 to "<html>gateway failure</html>",
            200 to """{"error":{"code":"quota_unavailable"}}""",
            409 to """{"error":{"code":"duplicate_request"}}""")) {
            val error = assertFailsWith<MinecraftLlmClient.ApiException> {
                MinecraftLlmClient.parseResponse(Request.Response(status, body, emptyMap()))
            }
            assertNotNull(error.message)
            assertFalse(error.message!!.contains("<html>"))
        }
    }

    @Test fun `a completion without content is a failure`() {
        assertFailsWith<MinecraftLlmClient.ApiException> {
            MinecraftLlmClient.parseResponse(Request.Response(200, """{"choices":[]}""", emptyMap()))
        }
    }
}
