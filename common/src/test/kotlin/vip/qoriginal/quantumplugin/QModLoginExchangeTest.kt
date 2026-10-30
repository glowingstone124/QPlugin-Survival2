package vip.qoriginal.quantumplugin

import kotlin.test.*

class QModLoginExchangeTest {
    private val nonce = "A".repeat(43)
    private val challenge = "GC-" + "B".repeat(43)

    @Test fun `hello reports capability but only the ordered proof advances authentication`() {
        val exchange = QModLoginExchange()
        assertFalse(exchange.verify(challenge))
        assertTrue(exchange.start())
        assertFalse(exchange.start())
        assertFalse(exchange.verify(challenge))
        assertFalse(exchange.challenge("invalid", 1))
        assertFalse(exchange.challenge(challenge, -1))
        assertTrue(exchange.challenge(challenge, 1))
        assertFalse(exchange.verify("GC-" + "C".repeat(43)))
        assertTrue(exchange.verify(challenge))
        assertFalse(exchange.verify(challenge))
        assertEquals(QModLoginExchange.Stage.VERIFYING, exchange.stage)
    }

    @Test fun `password login cancellation prevents late challenge or proof completion`() {
        for (before in listOf("probe", "loading", "proof", "verifying")) {
            val exchange = QModLoginExchange()
            if (before != "probe") exchange.start()
            if (before in listOf("proof", "verifying")) exchange.challenge(challenge, 1)
            if (before == "verifying") exchange.verify(challenge)
            exchange.finish()
            assertFalse(exchange.start(), before)
            assertFalse(exchange.challenge(challenge, 1), before)
            assertFalse(exchange.verify(challenge), before)
        }
    }

    @Test fun `wire parser accepts raw mod hello and proof and rejects spoofed capability types`() {
        val hello = """{"version":1,"op":"hello","nonce":"$nonce","modVersion":"1.0.0","autoLogin":true}"""
        assertNotNull(QModWire.decode(hello.toByteArray()))
        assertNull(QModWire.decode(hello.replace("\"autoLogin\":true", "\"autoLogin\":\"true\"").toByteArray()))
        assertNull(QModWire.decode(hello.replace("\"version\":1", "\"version\":\"1\"").toByteArray()))
        assertNull(QModWire.decode(hello.replace("\"version\":1,", "").toByteArray()))
        assertNull(QModWire.decode(hello.replace("1.0.0", "x".repeat(65)).toByteArray()))
        val proof = """{"version":1,"op":"proof","nonce":"$nonce","challenge":"$challenge","ticket":"GP-${"C".repeat(43)}"}"""
        assertNotNull(QModWire.decode(proof.toByteArray()))
        assertNull(QModWire.decode(proof.replace("GP-", "GC-").toByteArray()))
        assertNull(QModWire.decode(byteArrayOf(0, 10) + hello.toByteArray()))
        assertNull(QModWire.decode(ByteArray(4097)))
        assertNull(QModWire.decode("[]".toByteArray()))
    }
}
