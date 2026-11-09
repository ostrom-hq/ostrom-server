// SPDX-License-Identifier: AGPL-3.0-only

package run.ostrom.server.identity

import run.ostrom.server.identity.domain.ADMIN_READ_SCOPES
import run.ostrom.server.identity.domain.ADMIN_SCOPES
import run.ostrom.server.identity.domain.AdminApprovalForbiddenException
import run.ostrom.server.identity.domain.AnswerDeviceAuthorizationUseCaseImpl
import run.ostrom.server.identity.domain.CLI_SCOPES
import run.ostrom.server.identity.domain.CLI_TOKEN_LIFETIME
import run.ostrom.server.identity.domain.DEVICE_CODE_LIFETIME
import run.ostrom.server.identity.domain.DeviceAuthorizationStatus
import run.ostrom.server.identity.domain.DeviceCodeNotFoundException
import run.ostrom.server.identity.domain.DevicePollResult
import run.ostrom.server.identity.domain.GetDeviceAuthorizationUseCaseImpl
import run.ostrom.server.identity.domain.InvalidScopesException
import run.ostrom.server.identity.domain.PollDeviceAuthorizationUseCaseImpl
import run.ostrom.server.identity.domain.RateLimitedException
import run.ostrom.server.identity.domain.StartDeviceAuthorizationUseCaseImpl
import run.ostrom.server.identity.domain.approximateNetwork
import run.ostrom.server.identity.domain.TokenKind
import run.ostrom.server.identity.domain.normalizeUserCode
import run.ostrom.server.identity.fakes.CountingSecretGenerator
import run.ostrom.server.identity.fakes.FakeHasher
import run.ostrom.server.identity.fakes.InMemoryApiTokensRepository
import run.ostrom.server.identity.fakes.InMemoryDeviceAuthorizationsRepository
import run.ostrom.server.identity.fakes.RecordingAuditLog
import run.ostrom.server.identity.fakes.ScriptedRateLimiter
import run.ostrom.server.identity.fakes.SettableClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
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
        // S2-04: the CLI token can neither answer a gate nor edit a workflow (an agent on the machine can read it);
        // it reads its organization's receipts (`ostrom receipts`, `verify`), approvals and gates (`ostrom inbox`,
        // `ostrom logs`), MCP servers (`ostrom claude`) and rules (`ostrom init`, `ostrom rules list`), writing none;
        // it starts, stops and attaches to its person's cloud sessions (`ostrom claude --cloud`, `ostrom attach`).
        val expected = setOf(
            "profile:read", "orgs:read", "workflows:read", "sessions:write", "receipts:read", "gates:read", "mcp:read",
            "rules:read", "cloud:write",
        )
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
        authorizations.update(started.authorization.id) {
            it.copy(status = DeviceAuthorizationStatus.APPROVED, userId = null)
        }

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
    fun anAdminLoginAsksForTheThreeGovernanceScopesOrNone() = runTest {
        assertTrue(start("cli", "ip", emptyList()).authorization.scopes.isEmpty(), "Empty: the ordinary sign-in")
        val admin = start("cli", "ip", listOf("gates:policy", "policy:write", "harness:write", "policy:write"))
        assertEquals(ADMIN_SCOPES, admin.authorization.scopes)
        for (wrong in listOf(listOf("policy:write"), ADMIN_SCOPES.toList() + "orgs:write", listOf("user:*"))) {
            assertFailsWith<InvalidScopesException>("$wrong") { start("cli", "ip", wrong) }
        }
    }

    @Test
    fun onlyAnOwnerOrAdminApprovesAnAdminLoginAndItsTokenIsBoundToTheirOrganizationForEightHours() = runTest {
        val orgId = Uuid.random()
        start("laptop", "ip", ADMIN_SCOPES.toList())
        assertEquals(ADMIN_SCOPES, get(userId, "BCDF-GHJK").scopes, "Shown before the yes")
        // Without the approver's organization (not an owner or admin of it), no yes.
        assertFailsWith<AdminApprovalForbiddenException> { answer(userId, "BCDF-GHJK", true) }
        assertFailsWith<AdminApprovalForbiddenException> { answer(userId, "BCDF-GHJK", true) { null } }
        assertTrue(audit.entries.isEmpty(), "A refused yes records nothing")
        answer(userId, "BCDF-GHJK", true) { orgId }
        val approvedEntry = audit.entries.single()
        assertEquals(orgId, approvedEntry.orgId, "In the organization's audit chain")
        assertEquals("gates:policy harness:write policy:write", approvedEntry.details["scopes"])
        clock.now += 6.seconds

        val approved = assertIs<DevicePollResult.Approved>(poll("secret1"))
        val token = approved.token.token
        assertEquals(TokenKind.ADMIN, token.kind)
        assertEquals("osg_secret2", approved.token.secret)
        assertEquals(orgId, token.orgId)
        assertEquals(CLI_SCOPES + ADMIN_SCOPES + ADMIN_READ_SCOPES, token.scopes)
        assertEquals(clock.now + 8.hours, token.expiresAt)
        val created = audit.entries.last()
        assertEquals(orgId, created.orgId)
        assertEquals("token.created", created.action)
        assertEquals("admin", created.details["kind"])
    }

    @Test
    fun denyingAnAdminLoginNeedsNoRoleAndAnOrdinaryYesNeverAsksForOne() = runTest {
        start("cli", "ip", ADMIN_SCOPES.toList())
        answer(userId, "BCDF-GHJK", false) { error("Not asked when denying") }
        assertNull(audit.entries.single().orgId)
        start("cli", "ip")
        answer(userId, "MNPQ-RSTV", true) { error("Not asked for the ordinary sign-in") }
        assertEquals(TokenKind.CLI, assertIs<DevicePollResult.Approved>(poll("secret2")).token.token.kind)
    }

    @Test
    fun anApprovedAdminLoginWithoutOrganizationIsADefect() = runTest {
        val started = start("cli", "ip", ADMIN_SCOPES.toList())
        authorizations.update(started.authorization.id) {
            it.copy(status = DeviceAuthorizationStatus.APPROVED, userId = userId)
        }

        assertFailsWith<IllegalStateException> { poll("secret1") }
    }

    @Test
    fun aLoginKeepsTheNetworkItWasAskedFromNeverTheAddress() = runTest {
        // Web audit 2, W3: the console shows where the request came from, so a yes to someone else's code shows.
        assertEquals("203.0.113.0/24", start("laptop", "203.0.113.77").authorization.requestedFrom)
        assertEquals("203.0.113.0/24", get(userId, "BCDF-GHJK").requestedFrom)
        assertEquals("2001:db8:abcd::/48", start("laptop", "2001:db8:abcd:12::7").authorization.requestedFrom)
        assertNull(start("laptop", "localhost").authorization.requestedFrom, "Not an IP address: nothing to show")
    }

    @Test
    fun anAddressIsShownAsItsSiteNetwork() {
        assertEquals("198.51.100.0/24", approximateNetwork("198.51.100.200"))
        assertEquals("192.0.2.0/24", approximateNetwork("::ffff:192.0.2.9"))
        assertEquals("2001:db8:1::/48", approximateNetwork("[2001:db8:1:ffff:1:2:3:4]"))
        assertEquals("::/48", approximateNetwork("::1"))
        assertNull(approximateNetwork("ci.example.com"))
    }

    @Test
    fun userCodesAreNormalized() {
        assertEquals("BCDF-GHJK", normalizeUserCode(" bcdf-ghjk "))
        assertNull(normalizeUserCode("BCDF-GHJ"))
        assertNull(normalizeUserCode("BCDF-GHJA"))
    }
}
