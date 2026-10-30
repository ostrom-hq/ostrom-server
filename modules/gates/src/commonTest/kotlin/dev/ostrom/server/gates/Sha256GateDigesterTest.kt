package dev.ostrom.server.gates

import dev.ostrom.server.gates.infrastructure.Sha256GateDigester
import kotlin.test.Test
import kotlin.test.assertEquals

class Sha256GateDigesterTest {
    @Test
    fun digestsAsSha256InLowerCaseHex() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256GateDigester().digest("abc"),
        )
    }
}
