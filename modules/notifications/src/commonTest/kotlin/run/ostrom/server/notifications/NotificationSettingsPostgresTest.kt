// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.core.qualifier.named
import org.koin.dsl.module
import run.ostrom.core.api.models.NotificationSetting
import run.ostrom.core.api.models.NotificationSettingChange
import run.ostrom.core.api.models.NotificationSettings
import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.kernel.http.ApiError
import run.ostrom.server.kernel.module.OrgDeletionListener
import run.ostrom.server.notifications.domain.ChannelResult
import run.ostrom.server.notifications.domain.Notification
import run.ostrom.server.notifications.domain.NotificationChannel
import run.ostrom.server.notifications.domain.NotificationKind
import run.ostrom.server.notifications.domain.NotificationSettingsRepository
import run.ostrom.server.notifications.domain.SwitchedOff
import run.ostrom.server.orgs.OrgsModule
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * `GET`/`PUT /api/v1/notifications/settings` on PostgreSQL as the serving role: a member's own switches, every kind on
 * until turned off, only a person's interactive login, and another organization never sees them.
 */
class NotificationSettingsPostgresTest {
    private val modules = listOf(InstanceModule, IdentityModule, OrgsModule, NotificationsModule)
    private val orgA = Uuid.parse("0000000a-0000-0000-0000-000000000000")
    private val orgB = Uuid.parse("0000000b-0000-0000-0000-000000000000")
    private val miaA = Uuid.parse("0000000a-0000-0000-0000-0000000000b1")
    private val leoB = Uuid.parse("0000000b-0000-0000-0000-0000000000b1")
    private val base = "/api/v1/notifications/settings"

    private val members = mapOf(
        "mia-a" to OrgPrincipal(orgA, miaA, OrgRole.MEMBER),
        "mia-token" to OrgPrincipal(orgA, miaA, OrgRole.MEMBER),
        "mia-cli" to OrgPrincipal(orgA, miaA, OrgRole.MEMBER),
        "agent-a" to OrgPrincipal(orgA, miaA, OrgRole.MEMBER),
        "leo-b" to OrgPrincipal(orgB, leoB, OrgRole.MEMBER),
    )

    private fun principal(token: String): AuthenticatedPrincipal? = when (token) {
        "mia-a", "leo-b", "stranger" ->
            AuthenticatedPrincipal(token, PrincipalKind.USER, setOf("user:*"), channel = PrincipalChannel.BROWSER)
        "mia-token" -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf("cloud:read", "cloud:write"))
        "mia-cli" -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf("cloud:read"), signedIn = true)
        "agent-a" -> AuthenticatedPrincipal(token, PrincipalKind.AGENT, setOf("agent:session"))
        else -> null
    }

    /** A channel that reaches nobody: the settings only need its id. */
    private class SilentChannel(override val id: String) : NotificationChannel {
        override suspend fun reachable(orgId: Uuid, memberIds: Set<Uuid>): Set<Uuid> = emptySet()

        override suspend fun deliver(notifications: List<Notification>): Map<Uuid, ChannelResult> =
            notifications.associate { it.id to ChannelResult.failed("silent") }
    }

    private val overrides = module {
        single<NotificationChannel>(named("slack")) { SilentChannel("slack") }
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String): AuthenticatedPrincipal? = principal(token)

                override suspend fun isLive(subjectId: String, credentialId: String): Boolean = true
            }
        }
        single<PrincipalOrgResolver> {
            object : PrincipalOrgResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
                    members[principal.subjectId]?.takeIf { orgId == null || it.orgId == orgId }
            }
        }
    }

    private suspend fun TestServer.read(token: String?): HttpResponse = client.get(base) {
        token?.let(::bearerAuth)
    }

    private suspend fun TestServer.change(token: String?, body: NotificationSettingChange): HttpResponse =
        client.put(base) {
            token?.let(::bearerAuth)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun on(channel: String, vararg off: String) = NotificationKind.entries.map {
        NotificationSetting(channel, it.wire, it.wire !in off)
    }

    @Test
    fun everyKindIsOnUntilTheMemberTurnsItOffOnOneChannel() = serverTest(modules, overrides) { server ->
        val fresh = server.read("mia-a")
        assertEquals(HttpStatusCode.OK, fresh.status, fresh.bodyAsText())
        assertEquals(NotificationSettings(listOf("push", "slack"), on("push") + on("slack")), fresh.body())

        val off = server.change("mia-a", NotificationSettingChange("push", "turn_completed", false))
        assertEquals(HttpStatusCode.OK, off.status, off.bodyAsText())
        val expected = NotificationSettings(listOf("push", "slack"), on("push", "turn_completed") + on("slack"))
        assertEquals(expected, off.body())
        assertEquals(expected, server.read("mia-a").body())
        server.change("mia-a", NotificationSettingChange("push", "waiting", false))
        server.change("mia-a", NotificationSettingChange("push", "waiting", true))

        val repository = server.koin.get<NotificationSettingsRepository>()
        assertEquals(
            setOf(SwitchedOff(miaA, "push", NotificationKind.TURN_COMPLETED)),
            repository.disabled(orgA, setOf(miaA, leoB)),
        )
        assertEquals(emptySet(), repository.disabled(orgA, emptySet()))
        val leo = NotificationSettings(listOf("push", "slack"), on("push") + on("slack"))
        assertEquals(leo, server.read("leo-b").body(), "Mia's switches are hers")
    }

    @Test
    fun aChannelTheServerDoesNotOfferOrAnUnknownKindIsRefused() = serverTest(modules, overrides) { server ->
        val channel = server.change("mia-a", NotificationSettingChange("discord", "waiting", false))
        assertEquals(HttpStatusCode.BadRequest, channel.status)
        assertEquals("invalid_notification_setting", channel.body<ApiError>().error.type)
        val kind = server.change("mia-a", NotificationSettingChange("push", "approval", false))
        assertEquals(HttpStatusCode.BadRequest, kind.status)
        assertEquals("invalid_notification_setting", kind.body<ApiError>().error.type)
    }

    @Test
    fun onlyAPersonsInteractiveLoginInAnOrganizationReadsOrChangesThem() = serverTest(modules, overrides) { server ->
        assertEquals(HttpStatusCode.Unauthorized, server.read(null).status)
        val anonymous = server.change(null, NotificationSettingChange("push", "waiting", false))
        assertEquals(HttpStatusCode.Unauthorized, anonymous.status)
        for (token in listOf("mia-token", "agent-a")) {
            assertEquals(HttpStatusCode.Forbidden, server.read(token).status, token)
            val change = server.change(token, NotificationSettingChange("push", "waiting", false))
            assertEquals(HttpStatusCode.Forbidden, change.status, token)
        }
        assertEquals(HttpStatusCode.NotFound, server.read("stranger").status, "A person of no organization")
        assertEquals(HttpStatusCode.OK, server.read("mia-cli").status, "The token of `ostrom login`")
    }

    @Test
    fun aTestPushIsAPersonsOwnAndWaitsTenSeconds() = serverTest(modules, overrides) { server ->
        val path = "/api/v1/notifications/push/test"
        assertEquals(HttpStatusCode.Unauthorized, server.client.post(path).status)
        assertEquals(HttpStatusCode.Forbidden, server.client.post(path) { bearerAuth("agent-a") }.status)
        assertEquals(HttpStatusCode.NotFound, server.client.post(path) { bearerAuth("stranger") }.status)
        val off = server.client.post(path) { bearerAuth("mia-a") }
        assertEquals(HttpStatusCode.Conflict, off.status, "No provider is chosen")
        assertEquals("push_off", off.body<ApiError>().error.type)
        val again = server.client.post(path) { bearerAuth("mia-a") }
        assertEquals(HttpStatusCode.TooManyRequests, again.status)
        assertEquals("push_test_rate_limited", again.body<ApiError>().error.type)
        assertEquals("10", again.headers[HttpHeaders.RetryAfter])
    }

    @Test
    fun anotherOrganizationNeverSeesTheSwitchesAndADeletedOneLosesThem() = serverTest(modules, overrides) { server ->
        server.change("mia-a", NotificationSettingChange("push", "waiting", false))
        server.change("leo-b", NotificationSettingChange("push", "waiting", false))
        val seen = server.driver.withOrganization(orgB) { tx ->
            tx.fetchAll(Statement.create("SELECT count(*) AS n FROM notifications_member_settings")).getOrThrow()
                .first().get("n").asLong()
        }
        assertEquals(1, seen, "Row-level security confines the switches to the transaction's organization")
        val repository = server.koin.get<NotificationSettingsRepository>()
        assertEquals(emptySet(), repository.disabled(orgB, setOf(miaA)), "Not A's member")

        // A kind a newer server stored is left out by this one.
        server.driver.withOrganization(orgA) { tx ->
            tx.execute(
                Statement.create(
                    "INSERT INTO notifications_member_settings VALUES (:org, :member, 'push', 'later', FALSE)",
                ).bind("org", orgA).bind("member", miaA),
            ).getOrThrow()
        }
        assertEquals(1, repository.settings(orgA, miaA).size)
        assertEquals(1, repository.disabled(orgA, setOf(miaA)).size)

        val listener = server.koin.get<OrgDeletionListener>(named("notifications-settings"))
        listener.deleted(orgA)
        assertEquals(emptyList(), repository.settings(orgA, miaA))
        listener.purge(orgA)
        assertEquals(1, repository.settings(orgB, leoB).size)
    }
}
