// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.admin

import run.ostrom.core.api.models.ServerFeatures
import run.ostrom.server.admin.domain.AdminSource
import run.ostrom.server.admin.domain.InstanceAdmin
import run.ostrom.server.admin.domain.InstanceAdminView
import run.ostrom.server.admin.domain.InstanceAdminsRepository
import run.ostrom.server.admin.domain.InstanceSettingsRepository
import run.ostrom.server.admin.domain.RemoveOutcome
import run.ostrom.server.admin.domain.SettingsView
import run.ostrom.server.admin.domain.StoredSettings
import run.ostrom.server.admin.presentation.InstanceAdminListResponse
import run.ostrom.server.admin.presentation.InstanceSettingsListResponse
import run.ostrom.server.admin.presentation.InstanceSettingsResponse
import run.ostrom.server.admin.presentation.adminCaller
import run.ostrom.server.admin.presentation.toResponse
import run.ostrom.server.kernel.auth.ALL_USER_SCOPES
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.settings.InstanceAccounts
import run.ostrom.server.kernel.settings.InstanceAdministrators
import run.ostrom.server.kernel.settings.InstanceAuditTrail
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsSection
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.testing.serverTest
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.test.runTest
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** The administration routes on a real server: who may, what is stored, what never comes back. */
class AdminRoutesPostgresTest {
    private val audit = RecordingAudit()
    private val overrides = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String): AuthenticatedPrincipal? = when (token) {
                    "alice" -> person(ALICE)
                    "bob" -> person(BOB)
                    // Alice's own token, presented by a program.
                    "alicekey" -> AuthenticatedPrincipal(ALICE.toString(), PrincipalKind.USER, setOf("orgs:write"))
                    else -> null
                }
            }
        }
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        single<InstanceAccounts> { FakeAccounts(owners = mapOf(ALICE to NOW)) }
        single<InstanceAuditTrail> { audit }
        single<SettingsSection> { MAIL }
    }

    private fun person(id: Uuid) = AuthenticatedPrincipal(
        id.toString(),
        PrincipalKind.USER,
        setOf(ALL_USER_SCOPES),
        channel = PrincipalChannel.BROWSER,
    )

    @Test
    fun theSetupLinksPersonAdministersAndAddsAnother() = serverTest(listOf(AdminModule), overrides) { server ->
        assertEquals(HttpStatusCode.Unauthorized, server.client.get("/api/v1/admin/instance-admins").status)
        val path = "/api/v1/admin/instance-admins"
        assertEquals(HttpStatusCode.Forbidden, server.client.get(path) { bearerAuth("bob") }.status)
        val first = server.client.get(path) { bearerAuth("alice") }.body<InstanceAdminListResponse>()
        assertEquals(listOf(ALICE.toString()), first.admins.map { it.userId })
        assertEquals(setOf(ServerFeatures.ADMIN_SETTINGS), AdminModule.features(server.koin))
        val refused = server.client.get("/api/v1/admin/settings") { bearerAuth("alicekey") }
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertTrue("instance_admin_required" in refused.bodyAsText())

        val added = server.client.post("/api/v1/admin/instance-admins") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"bob@example.com"}""")
        }
        assertEquals(HttpStatusCode.OK, added.status)
        val admins = added.body<InstanceAdminListResponse>().admins
        assertEquals(listOf("setup", "dashboard"), admins.map { it.source })
        assertEquals("bob", admins.last().login)
        assertEquals(ALICE.toString(), admins.last().addedBy)
        assertTrue(server.koin.get<InstanceAdministrators>().isAdmin(BOB))

        val unknown = server.client.post("/api/v1/admin/instance-admins") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody("""{"email":"nobody@example.com"}""")
        }
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertEquals(
            HttpStatusCode.NotFound,
            server.client.delete("/api/v1/admin/instance-admins/not-an-id") { bearerAuth("alice") }.status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            server.client.delete("/api/v1/admin/instance-admins/$CAROL") { bearerAuth("alice") }.status,
        )
        assertEquals(
            HttpStatusCode.NoContent,
            server.client.delete("/api/v1/admin/instance-admins/$ALICE") { bearerAuth("bob") }.status,
        )
        val last = server.client.delete("/api/v1/admin/instance-admins/$BOB") { bearerAuth("bob") }
        assertEquals(HttpStatusCode.Conflict, last.status)
        assertTrue("last_instance_admin" in last.bodyAsText())
        assertEquals(
            HttpStatusCode.Forbidden,
            server.client.get("/api/v1/admin/instance-admins") { bearerAuth("alice") }.status,
        )
    }

    @Test
    fun aSectionIsSavedSealedAndItsSecretNeverComesBack() = serverTest(listOf(AdminModule), overrides) { server ->
        val empty = server.client.get("/api/v1/admin/settings") { bearerAuth("alice") }
            .body<InstanceSettingsListResponse>()
        assertEquals(listOf("mail"), empty.sections.map { it.section })
        assertEquals(0, empty.sections.single().version)
        assertEquals(
            HttpStatusCode.NotFound,
            server.client.get("/api/v1/admin/settings/other") { bearerAuth("alice") }.status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            server.client.put("/api/v1/admin/settings/mail") {
                bearerAuth("bob")
                contentType(ContentType.Application.Json)
                setBody("""{"version":0}""")
            }.status,
        )

        val saved = server.client.put("/api/v1/admin/settings/mail") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody("""{"version":0,"values":{"from":"Ostrom <a@example.com>"},"secrets":{"apiKey":"re_secret"}}""")
        }
        assertEquals(HttpStatusCode.OK, saved.status)
        val text = saved.bodyAsText()
        assertFalse("re_secret" in text)
        val section = saved.body<InstanceSettingsResponse>()
        assertEquals(1, section.version)
        assertEquals(true, section.secrets["apiKey"]?.configured)
        assertEquals(false, section.secrets["token"]?.configured)
        assertEquals(ALICE.toString(), section.updatedBy)
        assertFalse(section.importedFromEnvironment)
        assertEquals("re_secret", server.koin.get<InstanceSettings>().read("mail")["apiKey"])

        val stale = server.client.put("/api/v1/admin/settings/mail") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody("""{"version":0}""")
        }
        assertEquals(HttpStatusCode.Conflict, stale.status)
        val invalid = server.client.put("/api/v1/admin/settings/mail") {
            bearerAuth("alice")
            contentType(ContentType.Application.Json)
            setBody("""{"version":1,"values":{"url":"http://example.com"}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertTrue("invalid_settings" in invalid.bodyAsText())
        val again = server.client.get("/api/v1/admin/settings/mail") { bearerAuth("alice") }
            .body<InstanceSettingsResponse>()
        assertEquals(section, again)
        assertEquals(listOf("instance.admin.setup", "instance.settings.saved"), audit.records.map { it.event })
    }

    @Test
    fun theRepositoriesKeepTheRowsAsTheRoutesNeedThem() = serverTest(listOf(AdminModule), overrides) { server ->
        val admins = server.koin.get<InstanceAdminsRepository>()
        val settings = server.koin.get<InstanceSettingsRepository>()

        assertFalse(admins.addIfNeverSeen(InstanceAdmin(ALICE, AdminSource.ENVIRONMENT, NOW)))
        assertTrue(admins.add(InstanceAdmin(BOB, AdminSource.DASHBOARD, NOW + 1.minutes, ALICE)))
        assertFalse(admins.add(InstanceAdmin(BOB, AdminSource.DASHBOARD, NOW + 1.minutes, ALICE)))
        assertEquals(RemoveOutcome.NOT_FOUND, admins.remove(CAROL, ALICE, NOW))
        assertEquals(RemoveOutcome.REMOVED, admins.remove(BOB, ALICE, NOW + 2.minutes))
        assertEquals(RemoveOutcome.LAST, admins.remove(ALICE, ALICE, NOW))
        assertFalse(admins.isAdmin(BOB))
        // Removed: never brought back by an import, only by an administrator.
        assertFalse(admins.addIfNeverSeen(InstanceAdmin(BOB, AdminSource.ENVIRONMENT, NOW)))
        assertTrue(admins.add(InstanceAdmin(BOB, AdminSource.DASHBOARD, NOW + 3.minutes, ALICE)))
        assertEquals(
            listOf(
                InstanceAdmin(ALICE, AdminSource.SETUP, NOW),
                InstanceAdmin(BOB, AdminSource.DASHBOARD, NOW + 3.minutes, ALICE),
            ),
            admins.list(),
        )

        val first = StoredSettings("mail", mapOf("from" to "a"), mapOf("apiKey" to "v1:x"), 1, NOW, null)
        assertNull(settings.get("mail"))
        assertTrue(settings.insertIfAbsent(first))
        assertFalse(settings.insertIfAbsent(first))
        assertFalse(settings.save(first.copy(version = 3)))
        val second = first.copy(values = emptyMap(), sealed = emptyMap(), version = 2, updatedBy = ALICE)
        assertTrue(settings.save(second))
        assertEquals(second, settings.get("mail"))
        assertEquals(mapOf("mail" to second), settings.all())
    }

    @Test
    fun aCredentialThatIsNoAccountIsNobody() = runTest {
        val service = AuthenticatedPrincipal("svc_1", PrincipalKind.SERVICE, setOf("orgs:write"))

        assertEquals(Uuid.NIL, service.adminCaller().userId)
        assertFalse(service.adminCaller().person)
        assertTrue(person(ALICE).adminCaller().person)
    }

    @Test
    fun anAdministratorWithoutAnAccountAndAnImportedSectionAreShownAsSuch() {
        val orphan = InstanceAdminView(InstanceAdmin(BOB, AdminSource.ENVIRONMENT, NOW), null).toResponse()
        val imported = SettingsView(MAIL, StoredSettings("mail", emptyMap(), emptyMap(), 1, NOW, null)).toResponse()

        assertNull(orphan.login)
        assertNull(orphan.email)
        assertNull(imported.updatedBy)
        assertTrue(imported.importedFromEnvironment)
    }
}
