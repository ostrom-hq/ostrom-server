// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import run.ostrom.server.automations.domain.AnswerWaitUseCaseImpl
import run.ostrom.server.automations.domain.AskWait
import run.ostrom.server.automations.domain.AskWaitUseCaseImpl
import run.ostrom.server.automations.domain.InvalidWaitAnswerException
import run.ostrom.server.automations.domain.InvalidWaitException
import run.ostrom.server.automations.domain.SecretUnsupportedAgentException
import run.ostrom.server.automations.domain.SecretUnsupportedSessionException
import run.ostrom.server.automations.domain.Wait
import run.ostrom.server.automations.domain.WaitAction
import run.ostrom.server.automations.domain.WaitNotFoundException
import run.ostrom.server.automations.domain.WaitField
import run.ostrom.server.automations.domain.WaitFieldType
import run.ostrom.server.automations.domain.WaitMessages
import run.ostrom.server.automations.domain.WaitRules
import run.ostrom.server.automations.domain.WaitSecret
import run.ostrom.server.automations.domain.WaitSecretSessions
import run.ostrom.server.automations.domain.WaitSecretSourceImpl
import run.ostrom.server.automations.domain.WaitSecrets
import run.ostrom.server.automations.domain.WaitSpec
import run.ostrom.server.automations.fakes.ALICE
import run.ostrom.server.automations.fakes.BOB
import run.ostrom.server.automations.fakes.CountingSecrets
import run.ostrom.server.automations.fakes.InMemoryTimedWaits
import run.ostrom.server.automations.fakes.InMemoryWaits
import run.ostrom.server.automations.fakes.ORG_A
import run.ostrom.server.automations.fakes.RecordingEvents
import run.ostrom.server.automations.fakes.TestClock
import run.ostrom.server.automations.fakes.TestMembers
import run.ostrom.server.automations.fakes.alice
import run.ostrom.server.automations.fakes.bob
import run.ostrom.server.automations.fakes.testWaitSessions
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** The `secret` and `file` fields of a wait (`waits.secrets.v1`, TeamCode's `docs/waits.md#secrets`). */
class WaitSecretsTest {
    private val repository = InMemoryWaits()
    private val clock = TestClock()
    private val audit = mutableListOf<OrgAuditRecord>()
    private val agents = mutableMapOf("session-1" to "claude", "codex-1" to "codex")
    private val sessions = WaitSecretSessions { _, id -> agents[id] }
    private val ask = AskWaitUseCaseImpl(
        repository, TestMembers, RecordingEvents(), clock, testWaitSessions, CountingSecrets(), sessions,
    )
    private val answers = AnswerWaitUseCaseImpl(repository, RecordingEvents(), clock, OrgAuditTrail { audit += it })
    private val sentinel = "S3NTINEL-pa55w0rd"

    private val password = WaitField("shop_password", WaitFieldType.SECRET, "Shop password", required = true)
    private val question = WaitSpec(
        "Shop access",
        fields = listOf(password, WaitField("report", WaitFieldType.FILE, "Report")),
        actions = listOf(WaitAction("done", "Done")),
    )

    private suspend fun asked(spec: WaitSpec = question, session: String? = "session-1"): Wait =
        ask.ask(bob, AskWait(spec, session), null).wait

    private fun values(vararg pairs: Pair<String, String>) = pairs.associate { it.first to JsonPrimitive(it.second) }

    @Test
    fun aSecretIsAskedInAClaudeCodeCloudSessionOnly() = runTest {
        assertEquals("OSTROM_WAIT_SHOP_PASSWORD", WaitRules.secretVariable("shop_password"))
        asked()
        assertFailsWith<SecretUnsupportedAgentException> { asked(session = "codex-1") }
        val refused = assertFailsWith<SecretUnsupportedSessionException> { asked(session = "other-1") }
        assertEquals("secret_unsupported_session", refused.type)
        assertTrue("codex" in assertFailsWith<SecretUnsupportedAgentException> { asked(session = "codex-1") }.message)
        // A file field and the others need no cloud session.
        asked(WaitSpec("Upload", fields = listOf(WaitField("report", WaitFieldType.FILE, "Report"))), session = null)
    }

    @Test
    fun aSecretIsNeverAskedByAWorkflowRunOrOutsideASession() = runTest {
        val noSession = assertFailsWith<InvalidWaitException> { asked(session = null) }
        assertTrue("session" in noSession.message)
        val run = assertFailsWith<InvalidWaitException> { ask.ask(bob, AskWait(question, "session-1", "run-1"), null) }
        assertTrue("workflow" in run.message)
    }

    @Test
    fun aSecretFieldHasNoDefaultAndOnlyASecretIsOneTime() {
        fun refused(vararg fields: WaitField) =
            assertFailsWith<InvalidWaitException> { WaitRules.question(WaitSpec("Q", fields = fields.toList()), true) }
        assertTrue("default" in refused(password.copy(default = JsonPrimitive("x"))).message)
        assertTrue("oneTime" in refused(WaitField("t", WaitFieldType.TEXT, "T", oneTime = true)).message)
        val twice = refused(password, password.copy(id = "SHOP_PASSWORD")).message
        assertTrue("OSTROM_WAIT_SHOP_PASSWORD" in twice, twice)
        WaitRules.question(WaitSpec("Q", fields = listOf(password.copy(oneTime = true))), true)
    }

    @Test
    fun theValueOfASecretAndTheNameOfAFileAreChecked() = runTest {
        val wait = asked()
        fun refused(vararg pairs: Pair<String, String>) =
            assertFailsWith<InvalidWaitAnswerException> { WaitRules.answer(wait.question, "done", values(*pairs)) }
        refused("shop_password" to "a\u0000b")
        refused("shop_password" to "é".repeat(WaitRules.MAX_SECRET_BYTES / 2 + 1))
        refused("shop_password" to "x", "report" to "../etc/passwd")
        refused("shop_password" to "x", "report" to "a\\b")
        refused("shop_password" to "x", "report" to " ")
        refused("shop_password" to "x", "report" to "a".repeat(WaitRules.MAX_FILE_NAME + 1))
        assertTrue("file" in refused("shop_password" to "x", "report" to "a\nb").message)
        assertFailsWith<InvalidWaitAnswerException> {
            WaitRules.answer(wait.question, "done", mapOf("shop_password" to JsonPrimitive(1)))
        }
        assertFailsWith<InvalidWaitAnswerException> {
            WaitRules.answer(wait.question, "done", values("shop_password" to "x") + ("report" to JsonPrimitive(1)))
        }
        WaitRules.answer(wait.question, "done", values("shop_password" to "é".repeat(WaitRules.MAX_SECRET_BYTES / 2)))
        WaitRules.answer(wait.question, "done", values("shop_password" to "x", "report" to "report.pdf"))
    }

    @Test
    fun theStoredAnswerHoldsProvidedNeverTheValue() = runTest {
        val wait = asked()
        val answered = answers.answer(bob, wait.id, "done", values("shop_password" to sentinel, "report" to "r.pdf"))
        assertEquals(JsonPrimitive("provided"), answered.answer!!.values["shop_password"])
        assertEquals(JsonPrimitive("r.pdf"), answered.answer!!.values["report"])
        assertFalse(sentinel in answered.toString() + audit.joinToString())
        val kept = repository.secrets.values.single().secret
        assertEquals("OSTROM_WAIT_SHOP_PASSWORD", kept.envName)
        assertEquals(sentinel, kept.value)
        assertEquals(answered.answer!!.answeredAt + 24.hours, kept.expiresAt)
        assertFalse(sentinel in kept.toString())
        val record = audit.single()
        assertEquals("wait.secret_provided", record.event)
        assertEquals("shop_password", record.details["fields"])
        assertEquals(ORG_A, record.orgId)
    }

    @Test
    fun anEmptySecretIsNotProvided() = runTest {
        val optional = WaitSpec("Q", fields = listOf(password.copy(required = false)))
        val wait = asked(optional)
        val answered = answers.answer(bob, wait.id, "done", mapOf("shop_password" to JsonNull))
        assertEquals(JsonPrimitive("not provided"), answered.answer!!.values["shop_password"])
        assertTrue(repository.secrets.isEmpty())
        assertTrue(audit.isEmpty())
    }

    @Test
    fun theSameAnswerSentAgainStoresNothingAndAnotherIsClosed() = runTest {
        val wait = asked()
        val given = values("shop_password" to sentinel)
        answers.answer(bob, wait.id, "done", given)
        repository.secrets.clear()
        assertEquals(wait.id, answers.answer(bob, wait.id, "done", given).id)
        assertTrue(repository.secrets.isEmpty(), "a retry stores no secret")
        assertEquals(1, audit.size)
        // The value is not kept, so a retry with another one is the same answer; another person never sees the wait.
        assertEquals(wait.id, answers.answer(bob, wait.id, "done", values("shop_password" to "other")).id)
        val carol = bob.copy(memberId = "carol")
        assertFailsWith<WaitNotFoundException> { answers.answer(carol, wait.id, "done", given) }
    }

    @Test
    fun theAuditNamesTheCredentialOfTheAnswerOrElseTheWaitsService() = runTest {
        val person = AuthenticatedPrincipal("bob", PrincipalKind.USER, emptySet())
        answers.answer(bob.copy(principal = person), asked().id, "done", values("shop_password" to sentinel))
        answers.answer(bob, asked().id, "done", values("shop_password" to sentinel))
        assertEquals(listOf("bob", "waits"), audit.map { it.actor.subjectId })
    }

    @Test
    fun theWakeMessageNamesTheVariableNeverTheValue() = runTest {
        val spec = WaitSpec(
            "Access",
            fields = listOf(password.copy(oneTime = true), WaitField("backup", WaitFieldType.SECRET, "Backup")),
        )
        val wait = asked(spec)
        val answered = answers.answer(bob, wait.id, "done", values("shop_password" to sentinel))
        val message = WaitMessages.answered(answered, "bob")
        val plain = asked(WaitSpec("Access", fields = listOf(WaitField("backup", WaitFieldType.SECRET, "Backup"))))
        val shown = WaitMessages.answered(answers.answer(bob, plain.id, "done", values("backup" to "b")), "bob")
        assertTrue("Backup: secret provided: OSTROM_WAIT_BACKUP (available in your shell environment)" in shown, shown)
        assertTrue(
            "Shop password: secret provided: OSTROM_WAIT_SHOP_PASSWORD (available in your shell environment, " +
                "for this turn only)" in message,
            message,
        )
        assertTrue("Backup: secret not provided" in message, message)
        assertFalse(sentinel in message)
        // Whatever the stored answer holds, only the marker reads as provided.
        val forged = answered.copy(answer = answered.answer!!.copy(values = values("shop_password" to sentinel)))
        val echoed = WaitMessages.answered(forged, "bob")
        assertFalse(sentinel in echoed)
        assertTrue("Shop password: secret not provided" in echoed)
    }

    @Test
    fun aSessionServesItsOwnersUnexpiredSecretsAndAOneTimeSecretOnce() = runTest {
        val spec = WaitSpec(
            "Access",
            fields = listOf(password.copy(oneTime = true), WaitField("api_key", WaitFieldType.SECRET, "Key")),
        )
        val wait = asked(spec)
        answers.answer(bob, wait.id, "done", values("shop_password" to sentinel, "api_key" to "key-1"))
        audit.clear()
        val source = WaitSecretSourceImpl(repository, clock, OrgAuditTrail { audit += it })
        val owner = BOB.toString()
        assertTrue(source.serve(ORG_A, "session-1", ALICE.toString()).values.isEmpty(), "another member's")
        assertTrue(source.serve(ORG_A, "session-2", owner).values.isEmpty(), "another session's")
        val first = source.serve(ORG_A, "session-1", owner)
        assertEquals(mapOf("OSTROM_WAIT_API_KEY" to "key-1", "OSTROM_WAIT_SHOP_PASSWORD" to sentinel), first.values)
        assertEquals(setOf("OSTROM_WAIT_SHOP_PASSWORD"), first.oneTime)
        assertFalse(sentinel in first.toString())
        assertEquals("wait.secret_consumed", audit.single().event)
        assertEquals("OSTROM_WAIT_SHOP_PASSWORD", audit.single().details["variables"])
        // The one-time secret is gone; the other stays until it expires.
        val second = source.serve(ORG_A, "session-1", owner)
        assertEquals(mapOf("OSTROM_WAIT_API_KEY" to "key-1"), second.values)
        assertEquals(1, audit.size, "nothing consumed, nothing written")
        clock.now += 25.hours
        assertTrue(source.serve(ORG_A, "session-1", owner).values.isEmpty(), "expired")
    }

    @Test
    fun theClockDeletesTheExpiredSecretsWhetherUsedOrNot() = runTest {
        val wait = asked()
        answers.answer(bob, wait.id, "done", values("shop_password" to sentinel))
        val timed = InMemoryTimedWaits(repository)
        assertEquals(0, timed.purgeSecrets(clock.now + 1.minutes, 10))
        assertEquals(1, timed.purgeSecrets(clock.now + 25.hours, 10))
        assertTrue(repository.secrets.isEmpty())
    }

    @Test
    fun twoAnswersKeepOneSecretPerVariableOfASession() = runTest {
        val first = asked()
        answers.answer(bob, first.id, "done", values("shop_password" to "one"))
        val second = asked()
        answers.answer(bob, second.id, "done", values("shop_password" to "two"))
        assertEquals("two", repository.secrets.values.single().secret.value)
        val kept = repository.secrets.keys.map { it.second }.toSet()
        assertEquals(setOf(WaitRules.secretVariable("shop_password")), kept)
    }

    @Test
    fun splitLeavesAnAnswerWithoutSecretsAsItIs() {
        val plain = mapOf("a" to JsonPrimitive("x"))
        val split = WaitSecrets.split(listOf(WaitField("a", WaitFieldType.TEXT, "A")), plain, clock.now)
        assertEquals(plain, split.values)
        assertTrue(split.secrets.isEmpty())
        assertNull(split.secrets.firstOrNull())
        val shown = WaitSecret("N", "n", "v", true, clock.now).toString()
        assertEquals("WaitSecret(name=N, oneTime=true, value=[REDACTED])", shown)
        assertEquals(Uuid.parse(ORG_A.toString()), ORG_A)
        assertEquals(ALICE.toString(), alice.memberId)
    }
}
