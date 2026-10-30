package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.AnswerDeviceAuthorizationUseCaseImpl
import dev.ostrom.server.identity.domain.CLI_SCOPES
import dev.ostrom.server.identity.domain.CLI_TOKEN_LIFETIME
import dev.ostrom.server.identity.domain.DEVICE_CODE_LIFETIME
import dev.ostrom.server.identity.domain.DeviceAuthorizationStatus
import dev.ostrom.server.identity.domain.DeviceCodeNotFoundException
import dev.ostrom.server.identity.domain.DevicePollResult
import dev.ostrom.server.identity.domain.GetDeviceAuthorizationUseCaseImpl
import dev.ostrom.server.identity.domain.PollDeviceAuthorizationUseCaseImpl
import dev.ostrom.server.identity.domain.RateLimitedException
import dev.ostrom.server.identity.domain.StartDeviceAuthorizationUseCaseImpl
import dev.ostrom.server.identity.domain.TokenKind
import dev.ostrom.server.identity.domain.normalizeUserCode
import dev.ostrom.server.identity.fakes.CountingSecretGenerator
import dev.ostrom.server.identity.fakes.FakeHasher
import dev.ostrom.server.identity.fakes.InMemoryApiTokensRepository
import dev.ostrom.server.identity.fakes.InMemoryDeviceAuthorizationsRepository
import dev.ostrom.server.identity.fakes.RecordingAuditLog
import dev.ostrom.server.identity.fakes.ScriptedRateLimiter
import dev.ostrom.server.identity.fakes.SettableClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class DeviceUseCasesTest {
    private val tokens = InMemoryApiTokensRepository()
    private val authorizations = InMemoryDeviceAuthorizationsRepository(tokens)
    private val secrets = CountingSecretGenerator()
    private val hasher = FakeHasher()
    private val limiter = ScriptedRateLimiter()
    private val audit = RecordingAuditLog()
    private val clock = SettableClock()
    private val start = StartDeviceAuthorizationUseCaseImpl(authorizations, secrets, hasher, limiter, clock)
    private val get = GetDeviceAuthorizationUseCaseImpl(authorizations, limiter, clock)
    private val answer = AnswerDeviceAuthorizationUseCaseImpl(authorizations, limiter, audit, clock)
    private val poll = PollDeviceAuthorizationUseCaseImpl(authorizations, secrets, hasher, audit, clock)
    private val userId = Uuid.random()

    @Test
    fun theWholeLoginGivesTheCliOneTokenOnce() = runTest {
        val started = start("  laptop\u0007 ", "10.0.0.1")
        assertEquals("secret1", started.deviceCode)
        assertEquals("BCDF-GHJK", started.authorization.userCode)
        assertEquals("laptop", started.authorization.clientName)
        assertEquals(clock.now + DEVICE_CODE_LIFETIME, started.authorization.expiresAt)
        assertEquals(listOf("device-start:10.0.0.1"), limiter.keys)

        assertEquals(DevicePollResult.Pending, poll("secret1"))
        assertEquals(DevicePollResult.SlowDown, poll("secret1"))
        assertEquals("laptop", get(userId, "bcdf ghjk").clientName)
        answer(userId, "bcdfghjk", approve = true)
        assertEquals(listOf("device.approved"), audit.entries.map { it.action })
        clock.now += 6.seconds

        val approved = assertIs<DevicePollResult.Approved>(poll("secret1"))
        assertEquals(TokenKind.CLI, approved.token.token.kind)
        assertEquals(CLI_SCOPES, approved.token.token.scopes)
        // S2-04: the CLI token can neither answer a gate nor edit a workflow (an agent on the machine can read it).
        val expected = setOf("profile:read", "orgs:read", "workflows:read", "sessions:write")
        assertEquals(expected, approved.token.token.scopes)
        assertEquals("osc_secret2", approved.token.secret)
        assertEquals(userId, approved.token.token.userId)
        assertEquals(clock.now + CLI_TOKEN_LIFETIME, approved.token.token.expiresAt)
        assertEquals(approved.token.token, tokens.findBySecretHash("h(osc_secret2)"))
        assertEquals(DevicePollResult.Expired, poll("secret1"), "A device code gives one token")
    }

    @Test
    fun aDeniedLoginSaysSo() = runTest {
        start(null, "ip")
        answer(userId, "BCDF-GHJK", approve = false)

        assertEquals(DevicePollResult.Denied, poll("secret1"))
        assertEquals("device.denied", audit.entries.single().action)
        assertEquals("Ostrom CLI", authorizations.authorizations.values.single().clientName)
    }

    @Test
    fun anExpiredLoginCannotBeApprovedOrPolled() = runTest {
        start("cli", "ip")
        clock.now += 11.minutes

        assertFailsWith<DeviceCodeNotFoundException> { get(userId, "BCDF-GHJK") }
        assertFailsWith<DeviceCodeNotFoundException> { answer(userId, "BCDF-GHJK", true) }
        assertEquals(DevicePollResult.Expired, poll("secret1"))
    }

    @Test
    fun unknownCodesAreNotFound() = runTest {
        assertFailsWith<DeviceCodeNotFoundException> { poll("nope") }
        assertFailsWith<DeviceCodeNotFoundException> { poll("") }
        assertFailsWith<DeviceCodeNotFoundException> { poll("x".repeat(129)) }
        assertFailsWith<DeviceCodeNotFoundException> { get(userId, "AEIO-U123") }
        assertFailsWith<DeviceCodeNotFoundException> { answer(userId, "short", true) }
        assertFailsWith<DeviceCodeNotFoundException> { answer(userId, "BCDF-GHJK", true) }
    }

    @Test
    fun anAnswerLostToARaceIsNotFound() = runTest {
        start("cli", "ip")
        authorizations.refuseAnswer = true

        assertFailsWith<DeviceCodeNotFoundException> { answer(userId, "BCDF-GHJK", true) }
    }

    @Test
    fun aConsumeLostToAConcurrentPollGivesNoToken() = runTest {
        start("cli", "ip")
        answer(userId, "BCDF-GHJK", true)
        authorizations.refuseConsume = true

        assertEquals(DevicePollResult.Expired, poll("secret1"))
    }

    @Test
    fun anApprovedAuthorizationWithoutUserIsADefect() = runTest {
        val started = start("cli", "ip")
        authorizations.update(started.authorization.id) { it.copy(status = DeviceAuthorizationStatus.APPROVED, userId = null) }

        assertFailsWith<IllegalStateException> { poll("secret1") }
    }

    @Test
    fun startsLookupsAndAnswersAreRateLimited() = runTest {
        limiter.allow = false

        assertFailsWith<RateLimitedException> { start("cli", "ip") }
        assertFailsWith<RateLimitedException> { get(userId, "BCDF-GHJK") }
        assertFailsWith<RateLimitedException> { answer(userId, "BCDF-GHJK", true) }
    }

    @Test
    fun userCodesAreNormalized() {
        assertEquals("BCDF-GHJK", normalizeUserCode(" bcdf-ghjk "))
        assertNull(normalizeUserCode("BCDF-GHJ"))
        assertNull(normalizeUserCode("BCDF-GHJA"))
    }
}
