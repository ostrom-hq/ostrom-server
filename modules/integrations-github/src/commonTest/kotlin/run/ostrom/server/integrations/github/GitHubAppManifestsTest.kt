// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github

import run.ostrom.server.integrations.github.domain.GitHubAppCredentials
import run.ostrom.server.integrations.github.domain.GitHubAppExistsException
import run.ostrom.server.integrations.github.domain.GitHubAppManifests
import run.ostrom.server.integrations.github.domain.GitHubAppSourceImpl
import run.ostrom.server.integrations.github.domain.GitHubManifestTarget
import run.ostrom.server.integrations.github.domain.GitHubUnavailableException
import run.ostrom.server.integrations.github.domain.NotAnInstanceAdminException
import run.ostrom.server.integrations.github.domain.StoredInstanceAdmins
import run.ostrom.server.integrations.github.infrastructure.AppJwts
import run.ostrom.server.integrations.github.infrastructure.GitHubAppSetup
import run.ostrom.server.integrations.github.infrastructure.GitHubSettings
import run.ostrom.server.integrations.github.infrastructure.credentials
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class GitHubAppManifestsTest {
    private val clock = SettableClock()
    private val store = MemoryGitHubAppStore()
    private val api = FakeGitHubDirectoryApi()
    private val admin =
        AuthenticatedPrincipal(ADMIN_ID.toString(), PrincipalKind.USER, emptySet(), channel = PrincipalChannel.BROWSER)
    private val target =
        GitHubManifestTarget("https://api.example", "https://console.example", "https://github.com", "Ostrom")

    private fun manifests(): GitHubAppManifests {
        val apps = GitHubAppSourceImpl(store, clock)
        return GitHubAppManifests(apps, store, api, StoredInstanceAdmins { it == ADMIN_ID }, { target }, clock)
    }

    @Test
    fun anAdministratorStartsTheFlowWithAManifestPointingAtThisServer() = runTest {
        val form = manifests().start(admin, null, SESSION)
        assertTrue(form.action.startsWith("https://github.com/settings/apps/new?state="))
        assertEquals(64, form.action.substringAfter("state=").length)
        val manifest = Json.parseToJsonElement(form.manifest).jsonObject
        assertEquals("Ostrom", manifest["name"]?.jsonPrimitive?.content)
        assertEquals("https://console.example", manifest["url"]?.jsonPrimitive?.content)
        assertEquals(
            "https://api.example/api/v1/github/app/manifest/callback",
            manifest["redirect_url"]?.jsonPrimitive?.content,
        )
        val hook = manifest["hook_attributes"]?.jsonObject
        assertEquals("https://api.example/webhooks/github", hook?.get("url")?.jsonPrimitive?.content)
        val permissions = manifest["default_permissions"]?.jsonObject.orEmpty()
            .mapValues { it.value.jsonPrimitive.content }
        assertEquals("write", permissions["checks"])
        assertEquals("read", permissions["members"])
        // The cloud sessions' user tokens clone and push: never more than the App's own permissions.
        assertEquals("write", permissions["contents"])
        assertEquals("write", permissions["pull_requests"])
        assertEquals(
            listOf(
                "https://api.example/api/v1/github/app/install/callback",
                "https://api.example/api/v1/github/link/callback",
            ),
            manifest["callback_urls"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
        assertEquals("pull_request", manifest["default_events"]?.jsonArray?.single()?.jsonPrimitive?.content)
        assertEquals(1, store.states.size, "Kept as its hash only")
        assertFalse(store.states.keys.single() == form.action.substringAfter("state="))

        val org = manifests().start(admin, "acme-inc", SESSION)
        assertTrue(org.action.startsWith("https://github.com/organizations/acme-inc/settings/apps/new?state="))
    }

    @Test
    fun onlyAnAdministratorPersonStartsItAndNeverOverAnExistingApp() = runTest {
        val service = AuthenticatedPrincipal(ADMIN_ID.toString(), PrincipalKind.SERVICE, emptySet())
        val stranger = AuthenticatedPrincipal("usr_other", PrincipalKind.USER, emptySet())
        assertFailsWith<NotAnInstanceAdminException> { manifests().start(service, null, SESSION) }
        assertFailsWith<NotAnInstanceAdminException> { manifests().start(stranger, null, SESSION) }
        // The administrator's own API token is not the administrator signed in.
        val token = AuthenticatedPrincipal(ADMIN_ID.toString(), PrincipalKind.USER, emptySet())
        assertFailsWith<NotAnInstanceAdminException> { manifests().start(token, null, SESSION) }
        val nobody = GitHubAppManifests(
            GitHubAppSourceImpl(store, clock),
            store,
            api,
            StoredInstanceAdmins { false },
            { target },
            clock,
        )
        assertFailsWith<NotAnInstanceAdminException> { nobody.start(admin, null, SESSION) }
        for (owner in listOf("-acme", "acme/x", "a".repeat(40), "")) {
            assertEquals(ErrorKind.INVALID_REQUEST, kindOf { manifests().start(admin, owner, SESSION) })
        }
        assertTrue(store.states.isEmpty())
        store.stored = GitHubAppCredentials(slug = "stored")
        assertFailsWith<GitHubAppExistsException> { manifests().start(admin, null, SESSION) }
        assertTrue(store.states.isEmpty())
    }

    @Test
    fun theCallbackStoresTheAppOnceAndTheSourceServesItAtOnce() = runTest {
        val flow = manifests()
        val state = flow.start(admin, null, SESSION).action.substringAfter("state=")
        api.created["code1"] = createdApp()
        val app = flow.finish("code1", state, SESSION)
        assertEquals("ostrom-acme", app.slug)
        assertEquals("ostrom-acme", store.stored?.slug)
        assertEquals(ErrorKind.FORBIDDEN, kindOf { flow.finish("code1", state, SESSION) }, "Used once")
    }

    @Test
    fun onlyTheSessionThatStartedTheFlowFinishesIt() = runTest {
        val flow = manifests()
        val state = flow.start(admin, null, SESSION).action.substringAfter("state=")
        api.created["code1"] = createdApp()
        // A state that leaked (a proxy log, a shared history) is worth nothing in another browser.
        assertEquals(ErrorKind.FORBIDDEN, kindOf { flow.finish("code1", state, "another-session") })
        assertEquals(ErrorKind.UNAUTHORIZED, kindOf { flow.finish("code1", state, null) })
        assertEquals(null, store.stored)
        val again = flow.start(admin, null, SESSION).action.substringAfter("state=")
        assertEquals("ostrom-acme", flow.finish("code1", again, SESSION).slug)
    }

    @Test
    fun aMalformedUnknownOrExpiredCallbackIsRefused() = runTest {
        val flow = manifests()
        for ((code, state) in listOf(null to "s", "bad code" to "s", "c" to null, "c" to "", "c" to "s".repeat(129))) {
            assertEquals(ErrorKind.INVALID_REQUEST, kindOf { flow.finish(code, state, SESSION) })
        }
        assertEquals(ErrorKind.FORBIDDEN, kindOf { flow.finish("c", "unknown", SESSION) })
        val expired = flow.start(admin, null, SESSION).action.substringAfter("state=")
        clock.now += 11.minutes
        assertEquals(ErrorKind.FORBIDDEN, kindOf { flow.finish("c", expired, SESSION) })

        val unknownCode = flow.start(admin, null, SESSION).action.substringAfter("state=")
        assertEquals(ErrorKind.INVALID_REQUEST, kindOf { flow.finish("nope", unknownCode, SESSION) })
    }

    @Test
    fun aCallbackAfterAnotherAppExistsIsAConflict() = runTest {
        val flow = manifests()
        val first = flow.start(admin, null, SESSION).action.substringAfter("state=")
        val second = flow.start(admin, null, SESSION).action.substringAfter("state=")
        api.created["a"] = createdApp(1)
        api.created["b"] = createdApp(2)
        flow.finish("a", first, SESSION)
        assertFailsWith<GitHubAppExistsException> { flow.finish("b", second, SESSION) }

        // Another server stored one between the check and the save.
        val racing = MemoryGitHubAppStore()
        val raced = GitHubAppManifests(
            GitHubAppSourceImpl(MemoryGitHubAppStore(), clock),
            racing,
            api,
            StoredInstanceAdmins { it == ADMIN_ID },
            { target },
            clock,
        )
        val state = raced.start(admin, null, SESSION).action.substringAfter("state=")
        racing.stored = GitHubAppCredentials(slug = "other")
        api.created["c"] = createdApp(3)
        assertFailsWith<GitHubAppExistsException> { raced.finish("c", state, SESSION) }
    }

    @Test
    fun gitHubFailingIsUnavailableAndACancellationStaysOne() = runTest {
        val flow = manifests()
        val state = flow.start(admin, null, SESSION).action.substringAfter("state=")
        api.failure = gitHubDown()
        assertFailsWith<GitHubUnavailableException> { flow.finish("c", state, SESSION) }
        val again = flow.start(admin, null, SESSION).action.substringAfter("state=")
        api.failure = CancellationException("stopped")
        assertFailsWith<CancellationException> { flow.finish("c", again, SESSION) }
    }

    @Test
    fun theStoredAppIsTheOnlyOneAndIsReadAtMostEveryMinute() = runTest {
        assertEquals(GitHubAppCredentials(), GitHubAppSourceImpl(MemoryGitHubAppStore(), clock).current())
        store.stored = GitHubAppCredentials(appId = "2", slug = "stored")
        val stored = GitHubAppSourceImpl(store, clock)
        assertEquals("stored", stored.current().slug)
        store.stored = null
        clock.now += 30.seconds
        assertEquals("stored", stored.current().slug, "Kept for a minute")
        clock.now += 31.seconds
        assertNull(stored.current().slug)
        store.stored = GitHubAppCredentials(slug = "created")
        assertNull(stored.current().slug, "No App is kept for a minute too")
        stored.forget()
        assertEquals("created", stored.current().slug)
        assertEquals(3, store.reads)
    }

    @Test
    fun theSecretsNeverAppearInADump() {
        val dump = GitHubAppCredentials("1", "s", "pem-secret", "hook-secret", "id", "client-secret").toString()
        val created = createdApp().toString()
        for (secret in listOf("pem-secret", "hook-secret", "client-secret", "key", "whsec")) {
            assertFalse(secret in dump || secret in created, secret)
        }
        assertTrue("ostrom-acme" in created && "appId=1" in dump)
    }

    @Test
    fun theJwtIsMadeOncePerAppAndOnlyWithAnIdAndAKey() = runTest {
        val apps = MemoryGitHubAppStore()
        val source = GitHubAppSourceImpl(apps, clock)
        val jwts = AppJwts(source, clock)
        assertNull(jwts.current())
        apps.stored = GitHubAppCredentials(appId = "1")
        source.forget()
        assertNull(jwts.current(), "No key")
        apps.stored = GitHubAppCredentials(appId = "1", privateKeyPem = "pem")
        source.forget()
        val first = jwts.current()
        assertSame(first, jwts.current())
        apps.stored = GitHubAppCredentials(appId = "2", privateKeyPem = "pem")
        source.forget()
        assertNotSame(first, jwts.current())
    }

    @Test
    fun theSetupIsReadFromTheEnvironment() {
        val defaults = GitHubAppSetup.from(MapConfigSource(emptyMap()))
        assertEquals(GitHubAppSetup("http://localhost:8080"), defaults)
        val set = GitHubAppSetup.from(MapConfigSource(mapOf("OSTROM_PUBLIC_URL" to " https://api.acme.dev/ ")))
        assertEquals(GitHubAppSetup("https://api.acme.dev"), set)
        val settings = GitHubSettings("1", "pem", "hook", "slug")
        assertEquals(GitHubAppCredentials("1", "slug", "pem", "hook"), settings.credentials())
    }
}

private suspend fun kindOf(block: suspend () -> Unit): ErrorKind? = try {
    block()
    null
} catch (error: CancellationException) {
    throw error
} catch (error: OstromException) {
    error.kind
}

private const val SESSION = "session-of-the-admin"

private val ADMIN_ID: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000ad111")
