// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.discord

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import run.ostrom.server.gates.domain.models.Answer
import run.ostrom.server.gates.domain.models.AnswerChannel
import run.ostrom.server.gates.domain.models.ApprovalRequest
import run.ostrom.server.gates.domain.models.ApprovalView
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.models.GateStatus
import run.ostrom.server.gates.domain.models.GateView
import run.ostrom.server.gates.domain.models.RequestStatus
import run.ostrom.server.gates.domain.usecases.AnswerGateUseCase
import run.ostrom.server.gates.domain.usecases.GetApprovalUseCase
import run.ostrom.server.integrations.discord.domain.DiscordApps
import run.ostrom.server.integrations.discord.domain.DiscordInteractionOutcome
import run.ostrom.server.integrations.discord.domain.DiscordMember
import run.ostrom.server.integrations.discord.domain.ReceiveDiscordInteractionUseCaseImpl
import run.ostrom.server.integrations.discord.domain.StoredDiscordLink
import run.ostrom.server.integrations.discord.infrastructure.Ed25519DiscordVerifier
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Discord's signature is checked before anything is read; only a linked member's Reject reaches the gates. */
class DiscordInteractionsTest {
    private val now = Instant.parse("2026-11-15T09:00:00Z")
    private val settings = DiscordInstanceSettings()
    private val verifier = Ed25519DiscordVerifier()
    private val answered = mutableListOf<Pair<GateActor, Answer>>()
    private val org = Uuid.random()
    private val bob = Uuid.random()
    private val gone = Uuid.random()
    private val links = InMemoryDiscordLinks().apply {
        links[org to bob] = StoredDiscordLink(bob, "300000000000000003", "bob", now)
        links[org to gone] = StoredDiscordLink(gone, "300000000000000009", "gone", now)
    }
    private val people = MapDiscordPeople(mapOf(bob to DiscordMember(bob, "bob-s", OrgRole.MEMBER, "Acme")))
    private val gate = sampleDecidedGate(org, Uuid.random(), now).copy(status = GateStatus.PENDING)
    private var refusal: Exception? = null

    private val interactions = ReceiveDiscordInteractionUseCaseImpl(
        DiscordApps(settings),
        verifier,
        links,
        people,
        object : GetApprovalUseCase {
            override suspend fun invoke(actor: GateActor, approvalId: Uuid): ApprovalView = ApprovalView(
                ApprovalRequest(approvalId, gate.id, org, "bob-s", RequestStatus.PENDING, createdAt = now),
                gate,
            )
        },
        object : AnswerGateUseCase {
            override suspend fun invoke(
                actor: GateActor,
                requestId: Uuid,
                answer: Answer,
                shownDigest: String,
                channel: AnswerChannel,
            ): GateView {
                refusal?.let { throw it }
                assertEquals(gate.shownDigest to AnswerChannel.DISCORD, shownDigest to channel)
                answered += actor to answer
                return GateView(gate, emptyList(), emptyList())
            }
        },
        object : Clock {
            override fun now(): Instant = now
        },
    )

    private fun click(user: String, customId: String, inServer: Boolean = false): String {
        val clicker = """{"id":"$user"}"""
        val who = if (inServer) """"member":{"user":$clicker}""" else """"user":$clicker"""
        return """{"type":3,$who,"data":{"custom_id":"$customId"},"message":{"content":"**Push** to main"}}"""
    }

    private suspend fun receive(keys: DiscordKeys, body: String, stamp: String = now.epochSeconds.toString()) =
        interactions(body.encodeToByteArray(), keys.sign(stamp, body), stamp)

    private fun DiscordInteractionOutcome.json(): JsonObject = (this as DiscordInteractionOutcome.Respond).response

    @Test
    fun aRejectFromALinkedMembersDiscordAnswersNoAsThemAndUpdatesTheClickedMessage() = runTest {
        val keys = DiscordKeys.generate()
        settings.publicKey = keys.publicKey
        val body = click("300000000000000003", "ostrom_reject:$org:${Uuid.random()}")
        val response = receive(keys, body).json()
        assertEquals(
            Json.parseToJsonElement(
                """{"type":7,"data":{"content":"**Push** to main\nRejected by bob\\-s.","components":[],""" +
                    """"allowed_mentions":{"parse":[]}}}""",
            ),
            response,
        )
        assertEquals(listOf(GateActor.person("bob-s", org, OrgRole.MEMBER) to Answer.NO), answered)
        val inServer = click("300000000000000003", "ostrom_reject:$org:${Uuid.random()}", inServer = true)
        assertEquals(7, receive(keys, inServer).json()["type"].toString().toInt(), "Clicked in a server")
        assertEquals(2, answered.size)

        refusal = OstromException(ErrorKind.FORBIDDEN, "Not the approver.")
        val ephemeral = receive(keys, body).json()
        assertEquals(
            Json.parseToJsonElement(
                """{"type":4,"data":{"content":"This request is not yours to answer, or it is already decided.",""" +
                    """"flags":64}}""",
            ),
            ephemeral,
        )
        refusal = IllegalStateException("The database is down")
        val failed = receive(keys, body).json().toString()
        assertTrue("Ostrom could not record this answer; try again in a moment." in failed, failed)
        assertTrue(""""flags":64""" in failed)
        refusal = CancellationException("Stop")
        assertFailsWith<CancellationException> { receive(keys, body) }
        refusal = null
        for (other in listOf(
            click("300000000000000009", "ostrom_reject:$org:${Uuid.random()}"),
            click("300000000000000007", "ostrom_reject:$org:${Uuid.random()}"),
            click("300000000000000003", "ostrom_reject:$org:bad"),
            click("300000000000000003", "ostrom_reject:bad:${Uuid.random()}"),
            click("300000000000000003", "ostrom_reject:$org"),
        )) {
            assertEquals(4, receive(keys, other).json()["type"].toString().toInt(), other)
        }
        assertEquals(2, answered.size)
    }

    @Test
    fun theVerifierAcceptsDiscordsShapeAndRefusesAMalformedKeyOrSignature() = runTest {
        val keys = DiscordKeys.generate()
        val stamp = "1700000000"
        val body = """{"type":1}"""
        val signature = keys.sign(stamp, body)
        assertEquals(64, keys.publicKey.length)
        assertEquals(128, signature.length)
        val data = (stamp + body).encodeToByteArray()
        assertTrue(verifier.verify(keys.publicKey, data, signature))
        assertTrue(verifier.verify(keys.publicKey.uppercase(), data, signature.uppercase()), "Hexadecimal either case")
        assertFalse(verifier.verify(keys.publicKey, data + 1, signature), "Another body")
        assertFalse(verifier.verify(DiscordKeys.generate().publicKey, data, signature), "Another key")
        val malformed = listOf(
            keys.publicKey to signature.dropLast(2),
            keys.publicKey to signature.dropLast(1),
            keys.publicKey to signature.dropLast(1) + "z",
            keys.publicKey.dropLast(2) to signature,
            keys.publicKey.dropLast(1) + "z" to signature,
            "" to signature,
            keys.publicKey to "",
            // Hexadecimal of the right length, but no point of the curve.
            "ff".repeat(32) to signature,
        )
        for ((key, signed) in malformed) {
            assertFalse(verifier.verify(key, data, signed), "$key $signed")
        }
    }

    @Test
    fun aRequestNotSignedByTheApplicationOrTooOldIsRefusedBeforeItsBody() = runTest {
        val keys = DiscordKeys.generate()
        settings.publicKey = keys.publicKey
        val ping = """{"type":1}"""
        val stamp = now.epochSeconds.toString()
        assertEquals(Json.parseToJsonElement("""{"type":1}"""), receive(keys, ping).json(), "PONG")
        val bytes = ping.encodeToByteArray()
        val signature = keys.sign(stamp, ping)
        val unauthorized = DiscordInteractionOutcome.Unauthorized
        assertEquals(unauthorized, interactions(bytes, "00".repeat(64), stamp))
        assertEquals(unauthorized, interactions(bytes + 32, signature, stamp))
        assertEquals(unauthorized, interactions(bytes, null, stamp))
        assertEquals(unauthorized, interactions(bytes, signature, null))
        assertEquals(unauthorized, interactions(bytes, signature, "soon"))
        assertEquals(unauthorized, receive(DiscordKeys.generate(), ping), "Another application's key")
        val old = (now.epochSeconds - 301).toString()
        assertEquals(unauthorized, receive(keys, ping, old))
        val ahead = (now.epochSeconds + 301).toString()
        assertEquals(unauthorized, receive(keys, ping, ahead))
        val late = (now.epochSeconds + 300).toString()
        assertEquals(Json.parseToJsonElement("""{"type":1}"""), receive(keys, ping, late).json())
        val reads = settings.reads
        assertEquals(unauthorized, interactions(bytes, null, stamp))
        assertEquals(unauthorized, interactions(bytes, signature, null))
        assertEquals(unauthorized, interactions(bytes, signature, (now.epochSeconds - 600).toString()))
        assertEquals(reads, settings.reads, "A request without a signature or a fresh timestamp reads no settings")
        settings.publicKey = "ff".repeat(32)
        assertEquals(unauthorized, receive(keys, ping), "A saved key that is no point of the curve")
        settings.configured = false
        assertEquals(unauthorized, receive(keys, ping))
    }

    @Test
    fun aSignedInteractionThatIsNoRejectOfOursChangesNothing() = runTest {
        val keys = DiscordKeys.generate()
        settings.publicKey = keys.publicKey
        val bad = listOf("not json", "[]", "", """{"type":"1"}""", """{"type":2}""", """{"type":{}}""", "{}")
        for (body in bad) {
            assertEquals(DiscordInteractionOutcome.BadRequest, receive(keys, body), body)
        }
        val deferred = listOf(
            """{"type":3}""",
            """{"type":3,"data":"x","user":{"id":"300000000000000003"}}""",
            """{"type":3,"data":{"custom_id":5},"user":{"id":"300000000000000003"}}""",
            """{"type":3,"data":{},"user":{"id":"300000000000000003"}}""",
            """{"type":3,"data":{"custom_id":"other"},"user":{"id":"300000000000000003"}}""",
            """{"type":3,"data":{"custom_id":"ostrom_reject:$org:${Uuid.random()}"}}""",
            """{"type":3,"data":{"custom_id":"ostrom_reject:$org:${Uuid.random()}"},"user":"x"}""",
            """{"type":3,"data":{"custom_id":"ostrom_reject:$org:${Uuid.random()}"},"member":{"user":"x"}}""",
            """{"type":3,"data":{"custom_id":"ostrom_reject:$org:${Uuid.random()}"},"member":"x"}""",
            """{"type":3,"data":{"custom_id":"ostrom_reject:$org:${Uuid.random()}"},"user":{"id":5}}""",
        )
        for (body in deferred) {
            assertEquals(Json.parseToJsonElement("""{"type":6}"""), receive(keys, body).json(), body)
        }
        val noMessage = """{"type":3,"data":{"custom_id":"ostrom_reject:$org:${Uuid.random()}"},""" +
            """"user":{"id":"300000000000000003"},"message":"x"}"""
        val update = receive(keys, noMessage).json().toString()
        assertTrue(""""content":"\nRejected by bob\\-s."""" in update, update)
        val noContent = noMessage.replace(""""message":"x"""", """"message":{}""")
        assertTrue(""""content":"\nRejected by bob\\-s."""" in receive(keys, noContent).json().toString())
        assertEquals(2, answered.size, "Only the Reject of a linked approver answers")
    }
}
