// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.notifications

import run.ostrom.server.identity.IdentityModule
import run.ostrom.server.identity.domain.APP_REDIRECT_URI
import run.ostrom.server.identity.domain.ApiToken
import run.ostrom.server.identity.domain.ApiTokensRepository
import run.ostrom.server.identity.domain.CLI_SCOPES
import run.ostrom.server.identity.domain.IdentityProvider
import run.ostrom.server.identity.domain.MobileCode
import run.ostrom.server.identity.domain.MobileCodesRepository
import run.ostrom.server.identity.domain.ResolveUserUseCase
import run.ostrom.server.identity.domain.SecretHasher
import run.ostrom.server.identity.domain.TokenKind
import run.ostrom.server.identity.domain.VerifiedIdentity
import run.ostrom.server.identity.presentation.MobileTokenRequest
import run.ostrom.server.identity.presentation.MobileTokenResponse
import run.ostrom.server.instance.InstanceModule
import run.ostrom.server.kernel.auth.ORG_HEADER
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.kernel.module.OrgDeletionListener
import run.ostrom.server.notifications.domain.PushDevice
import run.ostrom.server.notifications.domain.PushDevicesRepository
import run.ostrom.server.notifications.presentation.PushDeviceDto
import run.ostrom.server.notifications.presentation.PushDeviceRequest
import run.ostrom.server.orgs.OrgsModule
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Org
import run.ostrom.server.orgs.domain.OrgsRepository
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.Transaction
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.core.qualifier.named
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * `POST /api/v1/push-devices` and `DELETE /api/v1/push-devices/{id}` on the real identity, orgs and notifications
 * modules and PostgreSQL, as the serving role: the phone app registers its phone and removes it, the push token never
 * comes back, another organization neither sees nor removes it, and only a person's phone or browser may register.
 */
class PushDevicesPostgresTest {
    private val modules = listOf(InstanceModule, IdentityModule, OrgsModule, NotificationsModule)
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())
    private val base = "/api/v1/push-devices"

    /** A person, the only member of a new organization: the phone app's token and their CLI token. */
    private class Person(val userId: Uuid, val orgId: Uuid, val memberId: Uuid, val phone: String, val cli: String)

    private suspend fun TestServer.person(login: String): Person {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        val org = Org(Uuid.random(), "$login-team", "$login team", null, null, now)
        val member = Member(Uuid.random(), org.id, user.id, OrgRole.MEMBER, now)
        koin.get<OrgsRepository>().create(org, member) { false }
        val hasher = koin.get<SecretHasher>()
        // The one-time code the sign-in in the browser gives the app, then the app's own exchange.
        val challenge = hasher.pkceChallenge(VERIFIER)
        val code = MobileCode(Uuid.random(), user.id, challenge, APP_REDIRECT_URI, now, now + 5.minutes)
        koin.get<MobileCodesRepository>().create(code, hasher.hash(CODE + login))
        val exchanged = client.post("/api/v1/auth/mobile/token") {
            contentType(ContentType.Application.Json)
            setBody(MobileTokenRequest(CODE + login, VERIFIER, APP_REDIRECT_URI))
        }
        assertEquals(HttpStatusCode.OK, exchanged.status, exchanged.bodyAsText())
        val cli = "${TokenKind.CLI.prefix}_" + Uuid.random().toHexString()
        val token = ApiToken(
            Uuid.random(), user.id, org.id, "cli", TokenKind.CLI, CLI_SCOPES, "hint", null, now, now + 1.hours,
            null, null,
        )
        koin.get<ApiTokensRepository>().create(token, hasher.hash(cli))
        return Person(user.id, org.id, member.id, exchanged.body<MobileTokenResponse>().token, cli)
    }

    // Inline and reified: the body is serialized as its own type (a request, or raw JSON as a string).
    private suspend inline fun <reified T> TestServer.register(
        token: String?,
        body: T,
        org: Uuid? = null,
    ): HttpResponse = client.post(base) {
        token?.let(::bearerAuth)
        org?.let { header(ORG_HEADER, it.toString()) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun TestServer.remove(token: String?, id: String, org: Uuid? = null): HttpResponse =
        client.delete("$base/$id") {
            token?.let(::bearerAuth)
            org?.let { header(ORG_HEADER, it.toString()) }
        }

    /** The phones a query of [org]'s transaction sees, or of no organization's. */
    private suspend fun TestServer.phonesSeen(org: Uuid?): Int {
        val count: suspend (Transaction) -> Int = { tx ->
            tx.fetchAll(Statement.create("SELECT count(*) AS n FROM notifications_push_devices")).getOrThrow()
                .first().get("n").asString().toInt()
        }
        return if (org == null) driver.transaction { count(this) } else driver.withOrganization(org, count)
    }

    @Test
    fun thePhoneRegistersWithTheAppsTokenWithoutItsPushTokenComingBackThenRemovesItOnce() = serverTest(modules) {
        val mia = it.person("mia")
        val made = it.register(mia.phone, PushDeviceRequest(PUSH_TOKEN, "expo", "ios", "fr-FR"))

        assertEquals(HttpStatusCode.Created, made.status, made.bodyAsText())
        assertFalse(made.bodyAsText().contains(PUSH_TOKEN), "The push token stays on the server")
        val device = made.body<PushDeviceDto>()
        assertEquals(PushDeviceDto(device.id, "expo", "ios", "fr-FR", device.createdAt), device)
        assertTrue(Instant.parse(device.createdAt) - now in (-1).minutes..1.minutes, "Registered now")
        assertEquals(HttpStatusCode.NoContent, it.remove(mia.phone, device.id).status)
        assertEquals(HttpStatusCode.NotFound, it.remove(mia.phone, device.id).status, "Removed once")
    }

    @Test
    fun anFcmPhoneRegistersItsTokenWithItsPublicKeyWhichIsKeptAndForgottenWithItsToken() = serverTest(modules) {
        val mia = it.person("mia")
        val key = phoneKey()
        val made = it.register(mia.phone, PushDeviceRequest(FCM_TOKEN, "fcm", "android", "fr-FR", key))

        assertEquals(HttpStatusCode.Created, made.status, made.bodyAsText())
        assertFalse(made.bodyAsText().contains(FCM_TOKEN), "The push token stays on the server")
        assertFalse(made.bodyAsText().contains(key), "The public key stays on the server too")
        val device = made.body<PushDeviceDto>()
        assertEquals("fcm" to "android", device.provider to device.platform)
        val phones = it.koin.get<PushDevicesRepository>()
        val stored = phones.ofMember(mia.orgId, mia.memberId).single()
        assertEquals(key to FCM_TOKEN, stored.publicKey to stored.pushToken)

        val noKey = PushDeviceRequest(FCM_TOKEN, "fcm", "ios")
        assertEquals(HttpStatusCode.BadRequest, it.register(mia.phone, noKey).status, "An fcm phone sends its key")
        val badKey = noKey.copy(publicKey = "bm90IGEga2V5")
        assertEquals(HttpStatusCode.BadRequest, it.register(mia.phone, badKey).status, "A P-256 key")

        phones.forgetToken(mia.orgId, stored.id, "another-token-the-app-registered-before")
        assertEquals(1, it.phonesSeen(mia.orgId), "A token the app already replaced forgets nothing")
        phones.forgetToken(mia.orgId, stored.id, FCM_TOKEN)
        assertEquals(0, it.phonesSeen(mia.orgId), "The token FCM no longer knows is forgotten")
    }

    @Test
    fun anotherOrganizationNeitherRemovesNorSeesThePhone() = serverTest(modules) {
        val mia = it.person("mia")
        val tom = it.person("tom")
        val id = it.register(mia.phone, PushDeviceRequest(PUSH_TOKEN, "expo", "android")).body<PushDeviceDto>().id

        assertEquals(HttpStatusCode.NotFound, it.remove(tom.phone, id).status, "Not a phone of tom's organization")
        assertEquals(HttpStatusCode.NotFound, it.remove(tom.phone, id, mia.orgId).status, "Not tom's organization")
        assertEquals(0, it.phonesSeen(tom.orgId), "Row level security hides it from tom's organization")
        assertEquals(0, it.phonesSeen(null), "and from a query of no organization")
        assertEquals(1, it.phonesSeen(mia.orgId))
        assertEquals(HttpStatusCode.NoContent, it.remove(mia.phone, id).status)
    }

    @Test
    fun onlyAPersonsPhoneOrBrowserRegistersAndNobodyWithoutACredential() = serverTest(modules) {
        val mia = it.person("mia")
        val phone = PushDeviceRequest(PUSH_TOKEN, "expo", "ios")

        assertEquals(HttpStatusCode.Unauthorized, it.register(null, phone).status)
        assertEquals(HttpStatusCode.Unauthorized, it.remove(null, Uuid.random().toString()).status)
        assertEquals(HttpStatusCode.Forbidden, it.register(mia.cli, phone).status, "A CLI token never registers")
        assertEquals(HttpStatusCode.Forbidden, it.remove(mia.cli, Uuid.random().toString()).status)
        assertEquals(HttpStatusCode.BadRequest, it.register(mia.phone, phone.copy(provider = "apns")).status)
        val noToken = """{"provider":"expo","platform":"ios"}"""
        assertEquals(HttpStatusCode.BadRequest, it.register(mia.phone, noToken).status, "A body without its token")
        assertEquals(HttpStatusCode.NotFound, it.remove(mia.phone, "not-a-device").status)
        assertEquals(0, it.phonesSeen(mia.orgId))
    }

    @Test
    fun aPersonOfTwoOrganizationsNamesTheOneAndADeletedOrganizationForgetsItsPhones() = serverTest(modules) {
        val mia = it.person("mia")
        val second = Org(Uuid.random(), "mia-second", "mia second", null, null, now)
        val membership = Member(Uuid.random(), second.id, mia.userId, OrgRole.MEMBER, now)
        it.koin.get<OrgsRepository>().create(second, membership) { false }
        val phone = PushDeviceRequest(PUSH_TOKEN, "expo", "ios")

        assertEquals(HttpStatusCode.Conflict, it.register(mia.phone, phone).status, "Which organization?")
        assertEquals(HttpStatusCode.Created, it.register(mia.phone, phone, second.id).status)
        assertEquals(HttpStatusCode.Created, it.register(mia.phone, phone, mia.orgId).status, "One per organization")
        it.koin.get<OrgDeletionListener>(named("notifications")).deleted(second.id)
        assertEquals(0 to 1, it.phonesSeen(second.id) to it.phonesSeen(mia.orgId))
    }

    @Test
    fun aPushTokenRegisteredAgainKeepsItsDeviceAndAMemberKeepsTheirTenLatestPhones() = serverTest(modules) {
        val mia = it.person("mia")
        val tom = it.person("tom")
        val phones = it.koin.get<PushDevicesRepository>()
        fun phone(token: String, at: Int, member: Uuid = mia.memberId) = PushDevice(
            Uuid.random(), mia.orgId, member, token, "expo", "ios", "fr", now + at.seconds, now + at.seconds,
        )
        val first = phones.save(phone("ExpoPushToken[first-phone]", 0), 10)
        val again = phones.save(phone("ExpoPushToken[first-phone]", 1, tom.memberId).copy(locale = null), 10)

        assertEquals(first.id to first.createdAt, again.id to again.createdAt, "The same device")
        assertEquals(tom.memberId to now + 1.seconds, again.memberId to again.seenAt, "Signed in again by tom")
        assertNull(again.locale)
        val oldest = phones.save(phone("ExpoPushToken[phone-number-2]", 2), 10)
        for (at in 3..11) phones.save(phone("ExpoPushToken[phone-number-$at]", at), 10)
        assertEquals(11, it.phonesSeen(mia.orgId), "Tom's phone and mia's ten")
        phones.save(phone("ExpoPushToken[phone-number-12]", 12), 10)
        assertEquals(11, it.phonesSeen(mia.orgId), "Still tom's phone and mia's ten")
        assertFalse(phones.delete(mia.orgId, mia.memberId, oldest.id), "Mia's oldest phone made room")
        assertTrue(phones.delete(mia.orgId, tom.memberId, first.id), "Tom's phone is untouched")
    }

    private companion object {
        const val CODE = "phone-code-0123456789abcdef0123456789abcdef-"
        const val VERIFIER = "phone-verifier-0123456789abcdef0123456789abcdef0123"
        const val PUSH_TOKEN = "ExponentPushToken[0123456789abcdefABCDEF]"
        const val FCM_TOKEN = "fcm-registration-token:APA91b0123456789abcdefABCDEF"
    }
}
