package vip.qoriginal.quantumplugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmApiErrorTest {
    @Test
    fun `parses daily quota error and retry headers`() {
        val error = LlmApiError.parse(
            429,
            """{"error":{"code":"daily_quota_exceeded","message":"今天的对话额度已经用完"}}""",
            mapOf(
                "retry-after" to listOf("3600"),
                "x-ratelimit-remaining" to listOf("0"),
                "x-ratelimit-reset" to listOf("1800000000"),
            ),
        )

        assertNotNull(error)
        assertEquals("daily_quota_exceeded", error.code())
        assertEquals(0, error.remaining())
        assertEquals(1_800_000_000L, error.resetAtEpochSeconds())
        assertTrue(error.userMessage().contains("约 60 分钟后可重试"))
    }

    @Test
    fun `uses status fallback for non-json service errors`() {
        val error = LlmApiError.parse(503, "gateway unavailable", emptyMap())

        assertNotNull(error)
        assertTrue(error.userMessage().contains("暂时不可用"))
    }

    @Test
    fun `successful assistant response is not an error`() {
        assertNull(
            LlmApiError.parse(
                200,
                """{"choices":[{"message":{"content":"ok"}}]}""",
                emptyMap(),
            ),
        )
    }
}
