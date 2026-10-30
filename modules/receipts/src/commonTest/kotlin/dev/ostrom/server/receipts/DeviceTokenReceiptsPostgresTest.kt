// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.receipts

import dev.ostrom.server.identity.IdentityModule
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.StartWebSessionUseCase
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.identity.presentation.AnswerDeviceRequest
import dev.ostrom.server.identity.presentation.DeviceCodeRequest
import dev.ostrom.server.identity.presentation.DeviceCodeResponse
import dev.ostrom.server.identity.presentation.DeviceTokenRequest
import dev.ostrom.server.identity.presentation.DeviceTokenResponse
import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.kernel.auth.CSRF_COOKIE
import dev.ostrom.server.kernel.auth.CSRF_HEADER
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.SESSION_COOKIE
import dev.ostrom.server.orgs.OrgsModule
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.Org
import dev.ostrom.server.orgs.domain.OrgsRepository
import dev.ostrom.server.receipts.domain.usecases.SignReceiptUseCase
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * `ostrom login` then `ostrom receipts` and `verify`, on the real identity, orgs and receipts modules and PostgreSQL:
 * the token the device flow gives the CLI reads its organization's receipts, and another organization's are absent.
 */
class DeviceTokenReceiptsPostgresTest {
    private val modules = listOf(InstanceModule, IdentityModule, OrgsModule, ReceiptsModule)
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    /** A person, the only member of a new organization, signed in to the console; their organization's id. */
    private suspend fun TestServer.member(login: String): Pair<Uuid, String> {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        val org = Org(Uuid.random(), "$login-team", "$login team", null, null, now)
        koin.get<OrgsRepository>().create(org, Member(Uuid.random(), org.id, user.id, OrgRole.OWNER, now)) { false }
        return org.id to deviceToken(koin.get<StartWebSessionUseCase>()(user.id).let { it.secret to it.csrfToken })
    }

    // The device flow as the CLI and the console run it: the token is the CLI's own, bound to no organization.
    private suspend fun TestServer.deviceToken(session: Pair<String, String>): String {
        val codes = client.post("/api/v1/auth/device") {
            contentType(ContentType.Application.Json)
            setBody(DeviceCodeRequest("laptop"))
        }.body<DeviceCodeResponse>()
        val answered = client.post("/api/v1/auth/device/answer") {
            header(HttpHeaders.Cookie, "$SESSION_COOKIE=${session.first}; $CSRF_COOKIE=${session.second}")
            header(CSRF_HEADER, session.second)
            header(HttpHeaders.Origin, CONSOLE_ORIGIN)
            contentType(ContentType.Application.Json)
            setBody(AnswerDeviceRequest(codes.userCode, true))
        }
        assertEquals(HttpStatusCode.NoContent, answered.status, answered.bodyAsText())
        val polled = client.post("/api/v1/auth/device/token") {
            contentType(ContentType.Application.Json)
            setBody(DeviceTokenRequest(codes.deviceCode))
        }.body<DeviceTokenResponse>()
        return assertNotNull(polled.accessToken, polled.status)
    }

    private suspend fun TestServer.sign(org: Uuid, subjectId: String): String =
        koin.get<SignReceiptUseCase>()(org, draft(subjectId)).getValue("id").jsonPrimitive.content

    @Test
    fun theDeviceTokenReadsItsOrganizationsReceiptsAndNotAnothers() =
        serverTest(modules) { server ->
            val (adaOrg, adaToken) = server.member("ada")
            val (bobOrg, _) = server.member("bob")
            val adas = server.sign(adaOrg, "gate-ada")
            val bobs = server.sign(bobOrg, "gate-bob")
            assertTrue(adaToken.startsWith("osc_"), "The CLI's device token")

            val read = server.client.get("/api/v1/receipts/$adas") { bearerAuth(adaToken) }
            assertEquals(HttpStatusCode.OK, read.status, read.bodyAsText())
            assertEquals(adas, read.body<JsonObject>().getValue("id").jsonPrimitive.content)
            val verified = server.client.get("/api/v1/receipts/$adas/verify") { bearerAuth(adaToken) }
            assertEquals(HttpStatusCode.OK, verified.status, verified.bodyAsText())
            val listed = server.client.get("/api/v1/receipts") { bearerAuth(adaToken) }.body<JsonObject>()
            val ids = listed.getValue("items").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
            assertEquals(listOf(adas), ids, "Only Ada's organization's")

            val across = server.client.get("/api/v1/receipts/$bobs") { bearerAuth(adaToken) }
            assertEquals(HttpStatusCode.NotFound, across.status, "Bob's receipt is absent for Ada's device")
            val acrossVerify = server.client.get("/api/v1/receipts/$bobs/verify") { bearerAuth(adaToken) }
            assertEquals(HttpStatusCode.NotFound, acrossVerify.status)
        }

    private companion object {
        /** The console's origin in tests: the default `OSTROM_CONSOLE_URL`. */
        const val CONSOLE_ORIGIN = "http://localhost:5173"
    }
}
