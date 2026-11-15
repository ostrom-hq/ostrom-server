// SPDX-License-Identifier: Apache-2.0

package run.ostrom.server.identity

import run.ostrom.server.identity.domain.ApiToken
import run.ostrom.server.identity.domain.AuthSession
import run.ostrom.server.identity.domain.AuthSessionsOfUserUseCaseImpl
import run.ostrom.server.identity.domain.CLI_SCOPES
import run.ostrom.server.identity.domain.RegisteredDevice
import run.ostrom.server.identity.domain.SESSION_LIFETIME
import run.ostrom.server.identity.domain.StartWebSessionUseCaseImpl
import run.ostrom.server.identity.domain.TokenKind
import run.ostrom.server.identity.domain.WEB_SESSION_NAME
import run.ostrom.server.identity.domain.WebSessionNotFoundException
import run.ostrom.server.identity.fakes.CountingSecretGenerator
import run.ostrom.server.identity.fakes.FakeHasher
import run.ostrom.server.identity.fakes.InMemoryApiTokensRepository
import run.ostrom.server.identity.fakes.InMemoryDevicesRepository
import run.ostrom.server.identity.fakes.InMemoryWebSessionsRepository
import run.ostrom.server.identity.fakes.RecordingAuditLog
import run.ostrom.server.identity.fakes.SettableClock
import run.ostrom.server.identity.presentation.toAuthSessionResponse
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant
import kotlin.uuid.Uuid

class AuthSessionsTest {
    private val audit = RecordingAuditLog()
    private val clock = SettableClock()
    private val sessions = InMemoryWebSessionsRepository()
    private val tokens = InMemoryApiTokensRepository()
    private val devices = InMemoryDevicesRepository(tokens)
    private val start = StartWebSessionUseCaseImpl(sessions, CountingSecretGenerator(), FakeHasher(), clock)
    private val own = AuthSessionsOfUserUseCaseImpl(sessions, tokens, devices, audit, clock)
    private val ada = Uuid.random()

    private suspend fun token(
        kind: TokenKind,
        name: String,
        userId: Uuid = ada,
        deviceId: Uuid? = null,
        lastUsedAt: Instant? = null,
    ): ApiToken {
        val token = ApiToken(
            Uuid.random(), userId, null, name, kind, CLI_SCOPES, "hint", null, clock.now, null, lastUsedAt, null,
            deviceId = deviceId,
        )
        tokens.create(token, "h(${token.id})")
        return token
    }

    private fun device(name: String): RegisteredDevice {
        val device = RegisteredDevice(Uuid.random(), ada, "install-$name", name, "macos", "15.1", clock.now, clock.now)
        devices.devices[device.id] = device
        return device
    }

    @Test
    fun aPersonListsTheirBrowserSessionsAndTheTokensTheySignedInWithTheLastUsedFirst() = runTest {
        val browser = start(ada).session
        clock.now += 1.minutes
        val laptop = device("Ada's laptop")
        val onLaptop = token(TokenKind.CLI, "ostrom login", deviceId = laptop.id, lastUsedAt = clock.now + 2.minutes)
        // Its device was removed meanwhile: the token's own name is shown.
        val removed = token(TokenKind.CLI, "old machine", deviceId = Uuid.random())
        val phone = token(TokenKind.MOBILE, "Ada's phone", lastUsedAt = clock.now + 1.minutes)
        token(TokenKind.PERSONAL, "ci")
        token(TokenKind.CLI, "bob's", userId = Uuid.random())
        start(Uuid.random())

        assertEquals(
            listOf(
                AuthSession(onLaptop.id, "Ada's laptop", laptop.id, onLaptop.createdAt, clock.now + 2.minutes),
                AuthSession(phone.id, "Ada's phone", null, phone.createdAt, clock.now + 1.minutes),
                AuthSession(removed.id, "old machine", removed.deviceId, removed.createdAt, removed.createdAt),
                AuthSession(browser.id, WEB_SESSION_NAME, null, browser.createdAt, browser.lastSeenAt),
            ),
            own.list(ada),
        )
        val response = own.list(ada).first().toAuthSessionResponse(onLaptop.id)
        assertEquals(laptop.id.toString() to true, response.deviceId to response.current)
        assertEquals("Ada's laptop", response.name)
    }

    @Test
    fun aPersonEndsOnlyTheirOwnLiveSignIns() = runTest {
        val first = start(ada).session
        val second = start(ada).session
        val bobs = start(Uuid.random()).session
        val ended = start(ada).session.also { sessions.revoke(it.id, clock.now) }

        assertFailsWith<WebSessionNotFoundException>("Another person's session") { own.revoke(ada, bobs.id) }
        assertFailsWith<WebSessionNotFoundException>("An ended session") { own.revoke(ada, ended.id) }
        own.revoke(ada, first.id)
        assertEquals(listOf(second.id), own.list(ada).map { it.id })
        val recorded = audit.entries.last()
        assertEquals("session.revoked" to first.id.toString(), recorded.action to recorded.targetId)
        clock.now += SESSION_LIFETIME
        assertEquals(emptyList(), own.list(ada), "An expired session is not listed")
        assertFailsWith<WebSessionNotFoundException>("An expired session") { own.revoke(ada, second.id) }
    }

    @Test
    fun endingACliOrPhoneSignInRevokesItsTokenButNoOtherToken() = runTest {
        val cli = token(TokenKind.CLI, "laptop")
        val phone = token(TokenKind.MOBILE, "phone")
        val personal = token(TokenKind.PERSONAL, "ci")
        val bobs = token(TokenKind.CLI, "bob's", userId = Uuid.random())

        own.revoke(ada, cli.id)
        assertEquals(clock.now, tokens.get(ada, cli.id)?.revokedAt)
        val recorded = audit.entries.last()
        assertEquals("token.revoked" to cli.id.toString(), recorded.action to recorded.targetId)
        assertEquals(mapOf("kind" to "cli"), recorded.details)
        own.revoke(ada, phone.id)
        assertEquals(mapOf("kind" to "mobile"), audit.entries.last().details)

        assertFailsWith<WebSessionNotFoundException>("A personal token") { own.revoke(ada, personal.id) }
        assertEquals(null, tokens.get(ada, personal.id)?.revokedAt)
        assertFailsWith<WebSessionNotFoundException>("Another person's token") { own.revoke(ada, bobs.id) }
        assertFailsWith<WebSessionNotFoundException>("Already ended") { own.revoke(ada, cli.id) }
        assertFailsWith<WebSessionNotFoundException>("Unknown") { own.revoke(ada, Uuid.random()) }
        assertEquals(emptyList(), own.list(ada))
    }
}
