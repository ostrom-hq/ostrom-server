// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github

import run.ostrom.server.integrations.github.domain.CreatedGitHubApp
import run.ostrom.server.integrations.github.domain.GitHubAppChange
import run.ostrom.server.integrations.github.domain.GitHubAppConfiguration
import run.ostrom.server.integrations.github.domain.GitHubAppConfigurations
import run.ostrom.server.integrations.github.domain.GitHubAppCredentials
import run.ostrom.server.integrations.github.domain.GitHubAppInput
import run.ostrom.server.integrations.github.domain.GitHubAppSource
import run.ostrom.server.integrations.github.domain.GitHubAppStore
import run.ostrom.server.integrations.github.domain.GitHubAppVerifier
import run.ostrom.server.integrations.github.domain.GitHubCallException
import run.ostrom.server.integrations.github.domain.GitHubIdentity
import run.ostrom.server.integrations.github.domain.GitHubInstallation
import run.ostrom.server.integrations.github.domain.GitHubLink
import run.ostrom.server.integrations.github.domain.GitHubLinkState
import run.ostrom.server.integrations.github.domain.NotAnInstanceAdminException
import run.ostrom.server.integrations.github.domain.StoredInstanceAdmins
import run.ostrom.server.integrations.github.domain.VerifiedGitHubApp
import run.ostrom.server.integrations.github.domain.appCallbackUrls
import run.ostrom.server.integrations.github.presentation.SaveGitHubAppConfigurationRequest
import run.ostrom.server.integrations.github.presentation.toInput
import run.ostrom.server.integrations.github.presentation.toResponse
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.kernel.settings.InstanceAuditRecord
import run.ostrom.server.kernel.settings.InstanceAuditTrail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** TeamCode's `GitHubLinkService.app` and `saveApp`, on the instance's stored App. */
class GitHubAppConfigurationsTest {
    private val clock = SettableClock()
    private val store = MemoryGitHubAppStore()
    private val links = MemoryGitHubLinkStore()
    private val github = FakeGitHubUserAuth()
    private val installations = MemoryGitHubRepository()
    private val logger = RecordingLogger()
    private val audit = RecordingInstanceAudit()
    private val source = ForgettingApps()
    private var verified: suspend (Long, String) -> VerifiedGitHubApp? = { id, _ ->
        VerifiedGitHubApp(id, "ostrom-acme", "acme", CLIENT)
    }
    private val admin = AuthenticatedPrincipal(
        ADMIN.toString(),
        PrincipalKind.USER,
        emptySet(),
        channel = PrincipalChannel.BROWSER,
    )

    private fun configurations(): GitHubAppConfigurations = GitHubAppConfigurations(
        StoredInstanceAdmins { it == ADMIN },
        store,
        source,
        GitHubAppVerifier { id, key -> verified(id, key) },
        GitHubAppChange(links, github, installations, logger),
        audit,
        appCallbackUrls("https://api.example"),
        clock,
    )

    private fun input(
        appId: Long = 7,
        clientId: String = CLIENT,
        clientSecret: String? = SECRET,
        privateKey: String? = KEY,
        webhookSecret: String? = "hook-secret",
        version: Int? = null,
    ) = GitHubAppInput(appId, clientId, clientSecret, privateKey, webhookSecret, version)

    private fun storedApp(clientSecret: String? = SECRET, webhookSecret: String? = "hook-secret") =
        GitHubAppCredentials("7", "ostrom-acme", KEY, webhookSecret, CLIENT, clientSecret, "acme", 1, NOW)

    private fun link(org: Uuid, clientId: String, access: String) {
        val member = Uuid.random()
        val identity = GitHubIdentity(member.hashCode().toLong(), "dev")
        val link = GitHubLink(org, member, identity, clientId, GitHubLinkState.LINKED, null, null, NOW, NOW)
        links.links[org to member] = link to tokens(access)
    }

    @Test
    fun anAdministratorEntersTheFirstAppWhichIsCheckedWithGitHubAndNeverReturnsItsSecrets() = runTest {
        val empty = configurations().read(admin)
        assertNull(empty.app)
        assertEquals(
            listOf(
                "https://api.example/api/v1/github/app/install/callback",
                "https://api.example/api/v1/github/link/callback",
                "https://api.example/api/v1/auth/github/callback",
            ),
            empty.callbackUrls,
        )
        val view = empty.toResponse()
        assertFalse(view.configured || view.privateKeySet || view.clientSecretSet || view.webhookSecretSet)

        val saved = configurations().save(admin, input())
        assertEquals("ostrom-acme" to "acme", saved.app?.slug to saved.app?.owner, "GitHub names the slug and owner")
        assertEquals(1 to NOW, saved.app?.version to saved.app?.updatedAt)
        assertEquals(1, source.forgotten, "Every replica reads the new App")
        val response = saved.toResponse()
        assertTrue(response.configured && response.privateKeySet && response.clientSecretSet)
        assertTrue(response.webhookSecretSet)
        assertEquals(7L to CLIENT, response.appId to response.clientId)
        assertEquals(NOW.toString(), response.updatedAt)
        assertFalse(SECRET in response.toString() || "PRIVATE KEY" in response.toString())
        val record = audit.records.single()
        val expected = InstanceAuditRecord(ADMIN, "github.app.saved", "github_app", "7", mapOf("clientId" to CLIENT))
        assertEquals(expected, record)
        assertTrue(logger.lines.isEmpty(), "A first App changes nothing else")
        assertEquals(saved, configurations().read(admin))
    }

    @Test
    fun onlyAnAdministratorSignedInAsAPersonReadsOrSavesIt() = runTest {
        val key = AuthenticatedPrincipal(ADMIN.toString(), PrincipalKind.SERVICE, emptySet())
        // The administrator's own API token is not the administrator signed in.
        val token = AuthenticatedPrincipal(ADMIN.toString(), PrincipalKind.USER, emptySet())
        val stranger = AuthenticatedPrincipal(
            Uuid.random().toString(),
            PrincipalKind.USER,
            emptySet(),
            channel = PrincipalChannel.BROWSER,
        )
        for (caller in listOf(key, token, stranger)) {
            assertFailsWith<NotAnInstanceAdminException> { configurations().read(caller) }
            assertFailsWith<NotAnInstanceAdminException> { configurations().save(caller, input()) }
        }
        assertNull(store.stored)
    }

    @Test
    fun whatTeamCodeRefusesIsRefusedBeforeGitHubIsAsked() = runTest {
        var asked = 0
        verified = { id, _ -> asked++; VerifiedGitHubApp(id, "ostrom-acme", "acme", CLIENT) }
        val refused = listOf(
            input(appId = 0),
            input(appId = GitHubAppConfigurations.MAX_APP_ID + 1),
            input(clientId = "abc"),
            input(clientId = "Iv"),
            input(clientSecret = "s".repeat(19)),
            input(clientSecret = "s".repeat(257)),
            input(clientSecret = "s".repeat(19) + " x"),
            input(clientSecret = "s".repeat(20) + "\u0001"),
            input(webhookSecret = ""),
            input(webhookSecret = "h".repeat(257)),
            input(webhookSecret = "hook secret"),
            input(privateKey = "not a key"),
            input(privateKey = "-----BEGIN CERTIFICATE-----"),
            input(privateKey = KEY + "x".repeat(GitHubAppConfigurations.MAX_KEY)),
            // A first App needs its client secret and its key.
            input(clientSecret = null),
            input(privateKey = null),
        )
        for (wrong in refused) {
            assertEquals("invalid_github_app", typeOf { configurations().save(admin, wrong) }, wrong.toString())
        }
        assertEquals(0, asked)
        // The bounds themselves pass.
        configurations().save(admin, input(appId = GitHubAppConfigurations.MAX_APP_ID, clientSecret = "s".repeat(256)))
        assertEquals(1, asked)
    }

    @Test
    fun aSaveNamesTheStoredVersionOrConflicts() = runTest {
        assertEquals("github_app_conflict", typeOf { configurations().save(admin, input(version = 1)) })
        store.stored = storedApp()
        assertEquals("github_app_conflict", typeOf { configurations().save(admin, input()) }, "Reload before saving")
        assertEquals("github_app_conflict", typeOf { configurations().save(admin, input(version = 2)) })
        // Another replica saved between the read and the write.
        val racing = object : GitHubAppStore by store {
            override suspend fun replaceApp(app: CreatedGitHubApp, expectedVersion: Int?, by: String, at: Instant) =
                false
        }
        val raced = GitHubAppConfigurations(
            StoredInstanceAdmins { it == ADMIN },
            racing,
            source,
            GitHubAppVerifier { id, _ -> VerifiedGitHubApp(id, "s", null, CLIENT) },
            GitHubAppChange(links, github, installations, logger),
            audit,
            emptyList(),
            clock,
        )
        assertEquals("github_app_conflict", typeOf { raced.save(admin, input(version = 1)) })
        assertTrue(audit.records.isEmpty())
    }

    @Test
    fun aChangeKeepsTheSecretsItDoesNotReplace() = runTest {
        store.stored = storedApp()
        val keepAll = input(clientSecret = null, privateKey = null, webhookSecret = null, version = 1)
        val kept = configurations().save(admin, keepAll)
        assertEquals(SECRET to KEY, kept.app?.clientSecret to kept.app?.privateKeyPem)
        assertEquals("hook-secret" to 2, kept.app?.webhookSecret to kept.app?.version)
        assertTrue(logger.lines.isEmpty() && github.revoked.isEmpty(), "Same App, same client: nothing else changes")

        // Another client needs its own secret, and so does a kept one that no longer opens.
        val other = input(clientId = "Iv1.other", clientSecret = null, version = 2)
        assertEquals("invalid_github_app", typeOf { configurations().save(admin, other) })
        store.stored = storedApp(clientSecret = null).copy(version = 2)
        val unopened = input(clientSecret = null, version = 2)
        assertEquals("invalid_github_app", typeOf { configurations().save(admin, unopened) })
        // A webhook secret that no longer opens stays absent until one is given.
        store.stored = storedApp(webhookSecret = null)
        val noHook = configurations().save(admin, input(webhookSecret = null, version = 1))
        assertNull(noHook.app?.webhookSecret)
        assertFalse(noHook.toResponse().webhookSecretSet)
    }

    @Test
    fun gitHubDecidesWhetherTheKeyIsTheApps() = runTest {
        verified = { _, _ -> null }
        assertEquals("github_app_rejected", typeOf { configurations().save(admin, input()) })
        verified = { _, _ -> throw GitHubCallException("GitHub did not answer GET /app.") }
        assertEquals("unavailable", typeOf { configurations().save(admin, input()) })
        verified = { _, _ -> VerifiedGitHubApp(8, "other", null, CLIENT) }
        assertEquals("github_app_mismatch", typeOf { configurations().save(admin, input()) }, "Another App's key")
        verified = { id, _ -> VerifiedGitHubApp(id, "other", null, "Iv1.other") }
        assertEquals("github_app_mismatch", typeOf { configurations().save(admin, input()) }, "Another App's client")
        verified = { _, _ -> throw CancellationException("stopped") }
        assertFailsWith<CancellationException> { configurations().save(admin, input()) }
        assertNull(store.stored)
        // An answer without the client is checked on the id alone (TeamCode's verifyApp).
        verified = { id, _ -> VerifiedGitHubApp(id, "other", null, null) }
        assertEquals(CLIENT, configurations().save(admin, input()).app?.clientId)
    }

    @Test
    fun anotherAppIdIsSaidAndAnotherClientEndsTheLinksOfTheFormerOne() = runTest {
        store.stored = storedApp()
        installations.installations += GitHubInstallation(ORG, 42, "acme")
        installations.installations += GitHubInstallation(OTHER_ORG, 43, "globex")
        link(ORG, CLIENT, "ghu_former_a")
        link(OTHER_ORG, CLIENT, "ghu_former_b")
        link(OTHER_ORG, "Iv1.next", "ghu_already_next")
        verified = { id, _ -> VerifiedGitHubApp(id, "ostrom-next", "acme", "Iv1.next") }
        val next = input(8, "Iv1.next", "n".repeat(40), privateKey = null, webhookSecret = null, version = 1)
        val saved = configurations().save(admin, next)
        assertEquals("8" to "hook-secret", saved.app?.appId to saved.app?.webhookSecret)
        val message = logger.lines.single().second
        assertTrue("installations of the former App stop working" in message, message)
        assertEquals(mapOf("formerAppId" to "7", "appId" to 8L, "formerInstallations" to 2L), logger.fields.single())
        assertEquals(listOf("Iv1.next"), links.links.values.map { it.first.clientId }, "The new client's link stays")
        assertEquals(setOf("ghu_former_a", "ghu_former_b"), github.revoked.toSet(), "Revoked by the former client")
    }

    @Test
    fun theFormerClientsLinksGoEvenWhenItsSecretNoLongerOpens() = runTest {
        store.stored = storedApp(clientSecret = null)
        link(ORG, CLIENT, "ghu_former")
        verified = { id, _ -> VerifiedGitHubApp(id, "ostrom-acme", "acme", "Iv1.next") }
        configurations().save(admin, input(clientId = "Iv1.next", version = 1))
        assertTrue(links.links.isEmpty())
        assertTrue(github.revoked.isEmpty(), "Only the client that issued a token revokes it")
        assertTrue(logger.lines.isEmpty(), "Same App ID")
    }

    @Test
    fun theInputAndTheRequestNeverPrintTheirSecrets() {
        val request = SaveGitHubAppConfigurationRequest(7, CLIENT, SECRET, KEY, "hook-secret", 3)
        for (text in listOf(request.toString(), request.toInput().toString())) {
            assertFalse(SECRET in text || "PRIVATE KEY" in text || "hook-secret" in text, text)
            assertTrue(CLIENT in text, text)
        }
        assertEquals(input(version = 3), request.toInput())
    }

    @Test
    fun anAppWithoutItsIdOrItsLastSaveMapsToNulls() {
        val view = GitHubAppConfiguration(GitHubAppCredentials(slug = "named"), emptyList()).toResponse()
        assertTrue(view.configured)
        assertEquals(null to null, view.appId to view.updatedAt)
    }

    private suspend fun typeOf(block: suspend () -> Unit): String? = try {
        block()
        null
    } catch (error: CancellationException) {
        throw error
    } catch (error: OstromException) {
        error.type
    }
}

/** The instance's audit log, in memory. */
class RecordingInstanceAudit : InstanceAuditTrail {
    val records = mutableListOf<InstanceAuditRecord>()

    override suspend fun record(record: InstanceAuditRecord) {
        records += record
    }
}

/** An App source that counts how often it is told to read again. */
private class ForgettingApps : GitHubAppSource {
    var forgotten = 0

    override suspend fun current(): GitHubAppCredentials = GitHubAppCredentials()

    override fun forget() {
        forgotten++
    }
}

private const val CLIENT = "Iv1.acme"
private val SECRET = "c".repeat(40)
private const val KEY = "-----BEGIN RSA PRIVATE KEY-----\nMIIsynthetic\n-----END RSA PRIVATE KEY-----"
private val ADMIN: Uuid = Uuid.parse("00000000-0000-0000-0000-00000000ad42")
