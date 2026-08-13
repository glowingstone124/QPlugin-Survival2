package vip.qoriginal.quantumplugin

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatSyncMessageSelectionTest {
    @Test
    fun `keeps every distinct message with the same timestamp`() {
        val getter = ChatSync().WebMsgGetter()
        val messages = listOf(
            message(1000, 11, "first"),
            message(1000, 12, "second"),
            message(1000, 13, "third"),
        )

        assertEquals(messages, getter.selectUnseenMessages(messages))
    }

    @Test
    fun `accepts a late message older than the newest timestamp`() {
        val getter = ChatSync().WebMsgGetter()
        val newest = message(2000, 11, "newest")
        val late = message(1000, 12, "late")

        assertEquals(listOf(newest), getter.selectUnseenMessages(listOf(newest)))
        assertEquals(listOf(late), getter.selectUnseenMessages(listOf(late, newest)))
    }

    @Test
    fun `does not resend messages from a repeated response`() {
        val getter = ChatSync().WebMsgGetter()
        val messages = listOf(
            message(1000, 11, "first"),
            message(1001, 12, "second"),
        )

        assertEquals(messages, getter.selectUnseenMessages(messages))
        assertEquals(emptyList(), getter.selectUnseenMessages(messages))
    }

    @Test
    fun `preserves identical messages repeated in one response`() {
        val getter = ChatSync().WebMsgGetter()
        val repeated = message(1000, 11, "same")
        val messages = listOf(repeated, repeated.deepCopy())

        assertEquals(messages, getter.selectUnseenMessages(messages))
        assertEquals(emptyList(), getter.selectUnseenMessages(messages))
    }

    @Test
    fun `parses both object and encoded object messages`() {
        val getter = ChatSync().WebMsgGetter()
        val first = message(1000, 11, "object")
        val second = message(1001, 12, "encoded")
        val messages = JsonArray().apply {
            add(first)
            add(second.toString())
        }

        assertEquals(listOf(first, second), getter.parseMessages(messages))
    }

    @Test
    fun `a malformed entry does not hide valid messages in the same response`() {
        val getter = ChatSync().WebMsgGetter()
        val first = message(1000, 11, "first")
        val second = message(1001, 12, "second")
        val messages = JsonArray().apply {
            add(first)
            add("not json")
            add(second)
        }

        assertEquals(listOf(first, second), getter.parseMessages(messages))
    }

    @Test
    fun `stable ids distinguish otherwise identical messages`() {
        val getter = ChatSync().WebMsgGetter()
        val first = message(1000, 11, "same").apply { addProperty("id", "qq:1") }
        val second = message(1000, 11, "same").apply { addProperty("id", "qq:2") }

        assertEquals(listOf(first), getter.selectUnseenMessages(listOf(first)))
        assertEquals(listOf(second), getter.selectUnseenMessages(listOf(first, second)))
    }

    private fun message(time: Long, sender: Long, content: String) = JsonObject().apply {
        addProperty("time", time)
        addProperty("from", 0)
        addProperty("sender", sender)
        addProperty("type", "game_chat")
        addProperty("message", content)
    }
}
