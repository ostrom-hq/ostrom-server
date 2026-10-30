// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.github

import dev.ostrom.server.gates.GatesModule
import dev.ostrom.server.gates.domain.models.ActorKind
import dev.ostrom.server.gates.domain.models.Gate
import dev.ostrom.server.gates.domain.models.GateActor
import dev.ostrom.server.gates.domain.models.GateNode
import dev.ostrom.server.gates.domain.services.GateActorResolver
import dev.ostrom.server.gates.domain.services.GateNodeResolver
import dev.ostrom.server.gates.domain.services.GateReceiptSigner
import dev.ostrom.server.gates.domain.services.GateSessions
import dev.ostrom.server.gates.presentation.AnswerApprovalRequest
import dev.ostrom.server.gates.presentation.ApprovalAnswer
import dev.ostrom.server.gates.presentation.ApprovalsPage
import dev.ostrom.server.gates.presentation.GateScopes
import dev.ostrom.server.integrations.github.domain.GateCheck
import dev.ostrom.server.integrations.github.domain.GitHubApi
import dev.ostrom.server.integrations.github.domain.GitHubInstallation
import dev.ostrom.server.integrations.github.domain.GitHubPull
import dev.ostrom.server.integrations.github.domain.GitHubRepository
import dev.ostrom.server.integrations.github.infrastructure.GitHubSettings
import dev.ostrom.server.integrations.github.presentation.GitHubAppStatus
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalChannel
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.orgs.domain.FindOrgUseCase
import dev.ostrom.server.orgs.domain.OrgNotFoundException
import dev.ostrom.server.secrets.domain.SecretCipher
import dev.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The GitHub App against a real PostgreSQL and real HTTP: install, webhook, check, and the isolation of orgs. */
class GitHubPostgresTest {
    private val secret = "whsec-test"
    private val github = FakeGitHubApi().apply {
        accounts[42] = "acme"
        accounts[43] = "globex"
        pulls["acme/api" to 6] = GitHubPull(6, SHA, "main", open = true, author = "dev")
    }

    // Tokens are `<kind>:<org letter>:<id>`: `user:a:owner` (a browser session), `member:a:dev`, `reader:a:ops`.
    private val overrides = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = when (token.substringBefore(':')) {
                    // A browser session, just signed in: the only one that says yes (S2-31, S2-54).
                    "user", "member", "admin" -> AuthenticatedPrincipal(
                        token,
                        PrincipalKind.USER,
                        setOf(GateScopes.ALL_USER),
                        // An admin's session has its own id, as the identity module's do; the others fall back to the
                        // subject.
                        credentialId = if (token.startsWith("admin:")) "session-$token" else null,
                        channel = PrincipalChannel.BROWSER,
                        authenticatedAt = Clock.System.now(),
                    )
                    "reader" -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf("orgs:read"))
                    else -> AuthenticatedPrincipal(token, PrincipalKind.SERVICE, emptySet())
                }
            }
        }
        single<GateActorResolver> {
            object : GateActorResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal): GateActor? {
                    val (_, org, id) = principal.subjectId.split(":").takeIf { it.size == 3 } ?: return null
                    return GateActor(id, orgOf(org), ActorKind.USER, personId = id)
                }
            }
        }
        single<PrincipalOrgResolver> {
            object : PrincipalOrgResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? {
                    val (kind, org, _) = principal.subjectId.split(":")
                    val role = when (kind) {
                        "user" -> OrgRole.OWNER
                        "admin" -> OrgRole.ADMIN
                        else -> OrgRole.MEMBER
                    }
                    return OrgPrincipal(orgOf(org), Uuid.random(), role).takeIf { orgId == null || it.orgId == orgId }
                }
            }
        }
        single<GateSessions> {
            object : GateSessions {
                override suspend fun assertOpen(orgId: Uuid, sessionId: String, launchId: String?) = Unit

                override suspend fun agentOf(orgId: Uuid, sessionId: String): String? = null
            }
        }
        single<GateNodeResolver> {
            object : GateNodeResolver {
                override suspend fun resolve(orgId: Uuid, repo: String?, actionClass: String) =
                    GateNode("ship", "g-main", "Merge into main", listOf("dev", "lead"), enforced = true)
            }
        }
        single<GateReceiptSigner> {
            object : GateReceiptSigner {
                override suspend fun sign(gate: Gate): String = "rcpt_${gate.id}"
            }
        }
        single {
            GitHubSettings(
                "123", null, secret, slug, webOrigin = "https://console.example",
                clientId = "Iv1.test", clientSecret = "client-secret",
            )
        }
        single<GitHubApi> { github }
        // Seals an App the manifest flow would create (none in these tests: the environment names one).
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        // The orgs module's lookup: acme is org A, globex org B, by short name or id.
        single<FindOrgUseCase> {
            object : FindOrgUseCase {
                override suspend fun invoke(ref: String): Uuid = when (ref) {
                    "acme", ORG.toString() -> ORG
                    "globex", OTHER_ORG.toString() -> OTHER_ORG
                    else -> throw OrgNotFoundException()
                }
            }
        }
    }

    // Read when the server starts: a test sets it before.
    private var slug: String? = "ostrom-dev"

    private fun orgOf(letter: String) = if (letter == "a") ORG else OTHER_ORG

    private fun gitHubTest(block: suspend (TestServer) -> Unit) =
        serverTest(listOf(GatesModule, GitHubIntegrationModule), overrides) { server -> block(server) }

    private suspend fun TestServer.get(token: String?, path: String): HttpResponse =
        client.config { followRedirects = false }.get(path) {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }

    private suspend fun TestServer.stateOf(token: String, query: String = ""): String {
        val start = get(token, "/api/v1/github/app/install/start$query")
        assertEquals(HttpStatusCode.Found, start.status)
        return start.headers[HttpHeaders.Location].orEmpty().substringAfter("state=")
    }

    /**
     * Installs [installation] as the GitHub person who reaches it, unless [code] names another person; the browser
     * comes back with the session that started.
     *
     * @return Where the callback sends the browser.
     */
    private suspend fun TestServer.install(token: String, installation: Long, code: String? = null): String {
        val state = stateOf(token)
        val person = code ?: if (installation == 42L) "code-acme" else "code-globex"
        return back(token, "installation_id=$installation&code=$person&state=$state")
    }

    /** The callback, with [token]'s session: always a redirect to the console, whose target it returns. */
    private suspend fun TestServer.back(token: String?, query: String): String {
        val response = get(token, "/api/v1/github/app/install/callback?$query")
        assertEquals(HttpStatusCode.Found, response.status)
        return response.headers[HttpHeaders.Location].orEmpty()
    }

    private suspend fun sign(body: String): String {
        val key = CryptographyProvider.Default.get(HMAC).keyDecoder(SHA256)
            .decodeFromByteArray(HMAC.Key.Format.RAW, secret.encodeToByteArray())
        val mac = key.signatureGenerator().generateSignature(body.encodeToByteArray())
        return "sha256=" + mac.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private suspend fun TestServer.deliver(event: String, body: String, signature: String? = null): HttpResponse =
        client.post("/webhooks/github") {
            header("X-GitHub-Event", event)
            header("X-Hub-Signature-256", signature ?: sign(body))
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private val opened = """{"action":"opened","installation":{"id":42},"repository":{"full_name":"acme/api"},""" +
        """"pull_request":{"number":6,"state":"open","base":{"ref":"main"},"user":{"login":"dev"}}}"""

    @Test
    fun anOwnerInstallsTheAppThenAPullRequestWaitsForItsYesOnGitHub() = gitHubTest { server ->
        val start = server.get("user:a:owner", "/api/v1/github/app/install/start")
        assertTrue(
            start.headers[HttpHeaders.Location].orEmpty()
                .startsWith("https://github.com/apps/ostrom-dev/installations/new?state="),
        )
        assertEquals(INSTALLED, server.install("user:a:owner", 42))
        val status = server.get("user:a:owner", "/api/v1/github/app").body<GitHubAppStatus>()
        assertEquals(GitHubAppStatus(true, "ostrom-dev", 42, "https://github.com/apps/ostrom-dev"), status)
        assertEquals(true, server.get("reader:a:ops", "/api/v1/github/app").body<GitHubAppStatus>().installed)

        assertEquals(HttpStatusCode.NoContent, server.deliver("pull_request", opened).status)
        val waiting = github.published.last().second
        assertEquals("in_progress", waiting.status)
        assertEquals("Waiting for a yes from lead", waiting.title)

        val approval = server.get("user:a:lead", "/api/v1/approvals?limit=100").body<ApprovalsPage>().items
            .first { it.requestedFrom == "lead" }
        val answered = server.client.post("/api/v1/approvals/${approval.id}/answer") {
            header(HttpHeaders.Authorization, "Bearer user:a:lead")
            contentType(ContentType.Application.Json)
            setBody(AnswerApprovalRequest(ApprovalAnswer.APPROVE, approval.shownDigest))
        }
        assertEquals(HttpStatusCode.OK, answered.status)
        val approved = github.published.last()
        assertEquals("success" to "Approved by lead", approved.second.conclusion to approved.second.title)
        assertEquals(100L, approved.third, "The same check run turns green")
    }

    @Test
    fun withoutItsNameTheAppHasNoPageToLinkTo() = run {
        slug = null
        gitHubTest { server ->
            val status = server.get("user:a:owner", "/api/v1/github/app").body<GitHubAppStatus>()
            assertEquals(GitHubAppStatus(false, null, null, null), status)
        }
    }

    @Test
    fun aWebhookIsAcceptedOnlyWithTheAppsSignature() = gitHubTest { server ->
        server.install("user:a:owner", 42)
        // Incoming webhooks live outside the API prefix (contract 0.1.129): the old path is gone.
        val old = server.client.post("/api/v1/github/webhook") {
            header("X-GitHub-Event", "pull_request")
            header("X-Hub-Signature-256", sign(opened))
            contentType(ContentType.Application.Json)
            setBody(opened)
        }
        assertEquals(HttpStatusCode.NotFound, old.status)
        assertEquals(HttpStatusCode.Unauthorized, server.deliver("pull_request", opened, "sha256=00").status)
        assertEquals(HttpStatusCode.Unauthorized, server.deliver("pull_request", opened, "").status)
        assertTrue(github.published.isEmpty())

        val deleted = """{"action":"deleted","installation":{"id":42}}"""
        assertEquals(HttpStatusCode.NoContent, server.deliver("installation", deleted).status)
        assertEquals(false, server.get("user:a:owner", "/api/v1/github/app").body<GitHubAppStatus>().installed)
    }

    @Test
    fun onlyAnOwnerOrAnAdminStartsAnInstallAndAStateServesOnce() = gitHubTest { server ->
        assertEquals(HttpStatusCode.Forbidden, server.get("member:a:dev", "/api/v1/github/app/install/start").status)
        assertEquals(HttpStatusCode.Found, server.get("admin:a:ops", "/api/v1/github/app/install/start").status)
        assertEquals(HttpStatusCode.Forbidden, server.get("reader:a:ops", "/api/v1/github/app/install/start").status)
        assertEquals(HttpStatusCode.Forbidden, server.get("agent:a:x", "/api/v1/github/app").status)
        assertEquals(HttpStatusCode.Unauthorized, server.get(null, "/api/v1/github/app").status)
        assertEquals(HttpStatusCode.Forbidden, server.get("user:a", "/api/v1/github/app").status, "No organization")

        val callback = "installation_id=42&code=code-acme&state=${server.stateOf("user:a:owner")}"
        assertEquals(INSTALLED, server.back("user:a:owner", callback))
        assertEquals(failed("forbidden"), server.back("user:a:owner", callback), "Used once")
        assertEquals(failed("invalid_request"), server.back("user:a:owner", ""))

        assertEquals(failed("conflict"), server.install("user:b:owner", 42), "Org A holds it")
        assertEquals(INSTALLED, server.install("user:b:owner", 43))
    }

    @Test
    fun anInstallStartNamesItsOrganizationAndTheCallerMustAdministerIt() = gitHubTest { server ->
        for (org in listOf("acme", ORG.toString())) {
            val state = server.stateOf("user:a:owner", "?org=$org")
            assertEquals(INSTALLED, server.back("user:a:owner", "installation_id=42&code=code-acme&state=$state"))
        }
        assertEquals(true, server.get("user:a:owner", "/api/v1/github/app").body<GitHubAppStatus>().installed)
        // Not an owner or admin of it, not a member at all, or no such organization: 403 alike.
        for ((token, org) in listOf("member:a:dev" to "acme", "user:a:owner" to "globex", "user:a:owner" to "nope")) {
            val start = server.get(token, "/api/v1/github/app/install/start?org=$org")
            assertEquals(HttpStatusCode.Forbidden, start.status, "$token ?org=$org")
        }
        assertEquals(INSTALLED, server.install("admin:b:ops", 43))
    }

    @Test
    fun onlyTheSessionThatStartedAnInstallFinishesIt() = gitHubTest { server ->
        val callback = "installation_id=42&code=code-acme&state=${server.stateOf("user:a:owner")}"
        // Another session of the same organization (a link sent to an admin), or no session: refused, and the
        // state still serves its own session.
        assertEquals(failed("forbidden"), server.back("admin:a:ops", callback))
        assertEquals(failed("unauthorized"), server.back(null, callback))
        assertEquals(false, server.get("user:a:owner", "/api/v1/github/app").body<GitHubAppStatus>().installed)
        assertEquals(INSTALLED, server.back("user:a:owner", callback))
    }

    @Test
    fun gitHubDownDuringTheCallbackSendsTheBrowserBackWithTheReason() = gitHubTest { server ->
        github.down = true
        assertEquals(failed("unavailable"), server.install("user:a:owner", 42))
        github.down = false
        assertEquals(false, server.get("user:a:owner", "/api/v1/github/app").body<GitHubAppStatus>().installed)
    }

    @Test
    fun anOrganizationCannotTakeAnotherOnesInstallation() = gitHubTest { server ->
        // The owner of org B reaches only globex's installation (43) on GitHub, and comes back with acme's (42),
        // which no organization has linked yet.
        assertEquals(failed("installation_not_yours"), server.install("user:b:owner", 42, code = "code-globex"))
        assertEquals(false, server.get("user:b:owner", "/api/v1/github/app").body<GitHubAppStatus>().installed)
        // A code GitHub does not accept, or none at all (the App does not ask for the person's authorization).
        assertEquals(failed("installation_not_yours"), server.install("user:b:owner", 43, code = "code-forged"))
        val noCode = "installation_id=43&state=${server.stateOf("user:b:owner")}"
        assertEquals(failed("invalid_request"), server.back("user:b:owner", noCode))
        // The owner of acme's organization still installs it.
        assertEquals(INSTALLED, server.install("user:a:owner", 42))
        assertEquals(true, server.get("user:a:owner", "/api/v1/github/app").body<GitHubAppStatus>().installed)
    }

    @Test
    fun rowLevelSecurityHidesAnotherOrganizationsInstallationAndChecks() = gitHubTest { server ->
        server.install("user:a:owner", 42)
        server.deliver("pull_request", opened)
        val b = server.get("user:b:owner", "/api/v1/github/app").body<GitHubAppStatus>()
        assertEquals(false, b.installed, "Org B does not see org A's installation")

        val repository = server.koin.get<GitHubRepository>()
        val gateId = Uuid.parse(github.published.first().second.externalId)
        assertEquals(100L, repository.check(ORG, gateId)?.checkRunId)
        assertNull(repository.check(OTHER_ORG, gateId))
        assertNull(repository.installation(OTHER_ORG))

        // The CI database user is a superuser, which bypasses row level security: the proof runs as a plain role.
        server.driver.execute(
            "DO \$\$ BEGIN CREATE ROLE ostrom_rls_probe NOLOGIN; EXCEPTION WHEN duplicate_object THEN NULL; END \$\$",
        ).getOrThrow()
        val tables = "github_installations, github_install_states, github_checks"
        server.driver.execute("GRANT SELECT, INSERT, DELETE ON $tables TO ostrom_rls_probe").getOrThrow()
        val counts = server.driver.transaction {
            execute("SET LOCAL ROLE ostrom_rls_probe").getOrThrow()
            val none = listOf("github_installations", "github_checks").map { count(it) }
            val setOrg = Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)")
            fetchAll(setOrg.bind("org", OTHER_ORG.toString())).getOrThrow()
            val other = listOf("github_installations", "github_checks").map { count(it) }
            fetchAll(Statement.create("SELECT set_config('ostrom.github_installation', '43', TRUE)")).getOrThrow()
            val wrongInstallation = count("github_installations")
            fetchAll(Statement.create("SELECT set_config('ostrom.github_installation', '42', TRUE)")).getOrThrow()
            listOf(none, other, listOf(wrongInstallation, count("github_installations")))
        }
        assertEquals(
            listOf(listOf("0", "0"), listOf("0", "0"), listOf("0", "1")),
            counts,
            "No key: nothing; org B: nothing; only the verified installation's own row",
        )
        // PostgreSQL refuses the row (WITH CHECK): the statement fails, or the commit of the aborted transaction does.
        val forged = try {
            server.driver.transaction {
                execute("SET LOCAL ROLE ostrom_rls_probe").getOrThrow()
                fetchAll(Statement.create("SELECT set_config('ostrom.github_installation', '42', TRUE)")).getOrThrow()
                execute(
                    Statement.create(
                        "INSERT INTO github_checks (org_id, gate_id, repo, sha, check_run_id, updated_at) " +
                            "VALUES (:org, :gate, 'acme/api', 'x', 1, NOW())",
                    ).bind("org", ORG).bind("gate", Uuid.random()),
                ).getOrThrow()
            }
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (expected: Exception) {
            Result.failure(expected)
        }
        assertTrue(forged.isFailure, "An installation key never writes into an organization")
        val stillThere = repository.installation(ORG)
        assertEquals(GitHubInstallation(ORG, 42, "acme"), stillThere)
    }

    @Test
    fun theRepositoryKeepsEveryField() = gitHubTest { server ->
        val repository = server.koin.get<GitHubRepository>()
        val at = Instant.parse("2026-10-30T02:00:00Z")
        val check = GateCheck(Uuid.random(), "acme/api", SHA, 9_000_000_001, null, at)
        repository.saveCheck(ORG, check)
        assertEquals(check, repository.check(ORG, check.gateId))
        val done = check.copy(checkRunId = 9_000_000_002, conclusion = "success")
        repository.saveCheck(ORG, done)
        assertEquals(done, repository.check(ORG, check.gateId))

        repository.saveInstallation(GitHubInstallation(ORG, 42, "acme"), at)
        repository.saveInstallation(GitHubInstallation(ORG, 44, "acme-new"), at)
        assertEquals(GitHubInstallation(ORG, 44, "acme-new"), repository.installationById(44))
        assertNull(repository.installationById(42), "Reinstalled: the organization keeps one installation")
        repository.removeInstallation(GitHubInstallation(ORG, 44, "acme-new"))
        assertNull(repository.installation(ORG))

        repository.saveState(ORG, "h1", "owner", at)
        assertNull(repository.takeState("h1", at), "Expired at that instant")
        assertNull(repository.takeState("h1", Instant.parse("2026-10-30T01:00:00Z")), "Taken already")
    }

    private suspend fun QueryExecutor.count(table: String): String =
        fetchAll("SELECT COUNT(*) AS n FROM $table").getOrThrow().first().get("n").asString()
}

private const val INSTALLED = "https://console.example/github?installed=1"

private fun failed(type: String) = "https://console.example/github?error=$type"
