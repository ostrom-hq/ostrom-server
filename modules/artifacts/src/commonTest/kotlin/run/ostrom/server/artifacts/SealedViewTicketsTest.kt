// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts

import run.ostrom.server.artifacts.domain.ViewTicket
import run.ostrom.server.artifacts.infrastructure.SealedViewTickets
import run.ostrom.server.kernel.signing.Ed25519InstanceSigner
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class SealedViewTicketsTest {
    private var sealer: SealedViewTickets? = null

    /** One instance's tickets for the whole test, its key made once. */
    private suspend fun tickets(): SealedViewTickets =
        sealer ?: SealedViewTickets(Ed25519InstanceSigner.generate()).also { sealer = it }
    private val artifact = Uuid.random()
    private val ticket = ViewTicket(artifact, 3, "member-1", "user-1", "session-1", 1_800_000_000)

    @Test
    fun theKeysAreHkdfSha256AsRfc5869() = runTest {
        // RFC 5869, test case 3 (zero salt, empty info): the first 32 bytes of its OKM.
        val okm = SealedViewTickets.hkdf(ByteArray(22) { 0x0b }, "")
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d", okm.toHexString())
        // The label is the info: computed apart with Python's hmac over bytes 0..63.
        val labelled = SealedViewTickets.hkdf(ByteArray(64) { it.toByte() }, "ostrom artifact-view")
        assertEquals("b267fbb6d55d7a3276b2f3488b4106b414bdbd8534427e4ed25530ef63f952ed", labelled.toHexString())
    }

    @Test
    fun aTicketOpensForItsArtifactOnlyAndTellsNothingOfItsReader() = runTest {
        val sealed = tickets().seal(ticket)
        assertEquals(sealed, tickets().seal(ticket), "The same ticket is the same address")
        assertEquals(ticket, tickets().open(artifact, sealed))
        listOf("member-1", "user-1", "session-1", "1800000000").forEach { assertFalse(it in sealed, it) }
        assertTrue(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]{43}").matches(sealed))
        assertNull(tickets().open(Uuid.random(), sealed), "Another artifact's")
        assertNotEquals(sealed, tickets().seal(ticket.copy(version = 4)))
        assertNull(SealedViewTickets(Ed25519InstanceSigner.generate()).open(artifact, sealed), "Another instance's")
    }

    @Test
    fun aForgedOrAlteredTicketOpensNothing() = runTest {
        val sealed = tickets().seal(ticket)
        val (ciphertext, mac) = sealed.split('.')
        val flipped = ciphertext.first().let { if (it == 'A') 'B' else 'A' } + ciphertext.drop(1)
        assertNull(tickets().open(artifact, "$flipped.$mac"), "Altered payload")
        val otherMac = mac.first().let { if (it == 'A') 'B' else 'A' } + mac.drop(1)
        assertNull(tickets().open(artifact, "$ciphertext.$otherMac"), "Altered MAC")
        // The MAC's last character carries two unused bits: another spelling of the same bytes is refused.
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val last = alphabet[alphabet.indexOf(mac.last()) xor 1]
        assertNull(tickets().open(artifact, "$ciphertext.${mac.dropLast(1)}$last"), "Not canonical")
        assertNull(tickets().open(artifact, "aaaaa.$mac"), "Not base64")
        listOf("", "x", "abc.def", "$ciphertext.$mac.x", "${"a".repeat(460)}.$mac").forEach {
            assertNull(tickets().open(artifact, it), it.take(20))
        }
    }

    @Test
    fun onlyAWellFormedTicketIsSealed() = runTest {
        listOf(
            ticket.copy(version = 0),
            ticket.copy(memberId = ""),
            ticket.copy(subjectId = "a:b"),
            ticket.copy(credentialId = "c:d"),
        ).forEach { assertFailsWith<IllegalArgumentException> { tickets().seal(it) } }
    }
}
