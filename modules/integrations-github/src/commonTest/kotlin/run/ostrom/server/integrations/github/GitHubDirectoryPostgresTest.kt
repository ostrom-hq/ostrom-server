// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github

import run.ostrom.server.gates.GatesModule
import run.ostrom.server.gates.domain.models.ActorKind
import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.models.GateNode
import run.ostrom.server.gates.domain.services.GateActorResolver
import run.ostrom.server.gates.domain.services.GateNodeResolver
import run.ostrom.server.gates.domain.services.GateReceiptSigner
import run.ostrom.server.gates.domain.services.GateSessions
import run.ostrom.server.gates.presentation.GateScopes
import run.ostrom.server.identity.domain.LinkedIdentities
import run.ostrom.server.identity.domain.UsersRepository
import run.ostrom.server.integrations.github.domain.GitHubApi
import run.ostrom.server.integrations.github.domain.GitHubContentsApi
import run.ostrom.server.integrations.github.domain.GitHubAppStore
import run.ostrom.server.integrations.github.domain.GitHubDirectory
import run.ostrom.server.integrations.github.domain.GitHubDirectoryApi
import run.ostrom.server.integrations.github.domain.GitHubDirectoryRepository
import run.ostrom.server.integrations.github.domain.GitHubInstallation
import run.ostrom.server.integrations.github.domain.GitHubManifestApi
import run.ostrom.server.integrations.github.domain.GitHubOriginsRepository
import run.ostrom.server.integrations.github.domain.GitHubRepo
import run.ostrom.server.integrations.github.domain.GitHubRepository
import run.ostrom.server.integrations.github.domain.GitHubRepositoryReader
import run.ostrom.server.integrations.github.domain.GitHubTeam
import run.ostrom.server.integrations.github.domain.InstanceAdmins
import run.ostrom.server.integrations.github.infrastructure.GitHubAppSetup
import run.ostrom.server.integrations.github.infrastructure.GitHubDirectoryQueries
import run.ostrom.server.integrations.github.infrastructure.GitHubSettings
import run.ostrom.server.integrations.github.infrastructure.PostgresGitHubAppStore
import run.ostrom.server.integrations.github.presentation.GitHubAppStatus
import run.ostrom.server.integrations.github.presentation.RepoResponse
import run.ostrom.server.integrations.github.presentation.ReposResponse
import run.ostrom.server.integrations.github.presentation.TeamsResponse
import run.ostrom.server.integrations.github.presentation.toRepoResponse
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.orgs.domain.FindOrgUseCase
import run.ostrom.server.orgs.domain.OrgNotFoundException
import run.ostrom.server.rules.domain.models.AnalysisKind
import run.ostrom.server.rules.domain.models.AnalysisRun
import run.ostrom.server.rules.domain.models.RepositoryListing
import run.ostrom.server.rules.domain.models.RepositoryReadException
import run.ostrom.server.rules.domain.models.RuleQuery
import run.ostrom.server.rules.domain.models.RuleStatus
import run.ostrom.server.rules.domain.services.RepositoryReader
import run.ostrom.server.rules.domain.usecases.AnalyzeOrganizationUseCaseImpl
import run.ostrom.server.rules.infrastructure.PostgresAnalysisRepository
import run.ostrom.server.rules.infrastructure.PostgresRulesRepository
import run.ostrom.server.rules.infrastructure.RulesMigrations
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * The directory and the manifest flow against a real PostgreSQL and real HTTP: who may call each route, the isolation
 * of the organizations per route and per table (row level security), and the plans of the request-path reads.
 */
class GitHubDirectoryPostgresTest {
    private val clock = SettableClock(Clock.System.now())
    private val github = FakeGitHubApi().apply { accounts[42] = "acme" }
    private val directoryApi = FakeGitHubDirectoryApi().apply {
        repos[42] = listOf(repo("web"), repo("api"))
        teams[42] = listOf(GitHubTeam("platform", "Platform", listOf("dev"), listOf("api")))
        repos[43] = listOf(repo("globex-secret"))
        teams[43] = listOf(GitHubTeam("globex-team", "Globex", listOf("spy"), listOf("globex-secret")))
    }
    private val contentsApi = FakeGitHubContentsApi().acmeApi()

    // Tokens are `<kind>:<org letter>:<id>`: `user:a:owner` (a browser session, the instance's administrator),
    // `admin:a:ops` (a browser session), `reader:a:ops` (an API token with `orgs:read`), `agent:a:x` (no scope).
    private val overrides = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String) = when (token.substringBefore(':')) {
                    "user", "member", "admin" -> AuthenticatedPrincipal(
                        token,
                        PrincipalKind.USER,
                        setOf(GateScopes.ALL_USER),
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
                    val parts = principal.subjectId.split(":")
                    val org = parts.getOrNull(1) ?: return null
                    val role = if (parts[0] == "user") OrgRole.OWNER else OrgRole.MEMBER
                    return OrgPrincipal(orgOf(org), Uuid.random(), role)
                        .takeIf { orgId == null || it.orgId == orgId }
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
                    GateNode("ship", "g-main", "Merge into main", listOf("dev"), enforced = true)
            }
        }
        single<GateReceiptSigner> {
            object : GateReceiptSigner {
                override suspend fun sign(gate: Gate): String = "rcpt_${gate.id}"
            }
        }
        // No App stored: the manifest flow is open.
        single { GitHubSettings(null, null, null, null, webOrigin = "https://console.example") }
        single { GitHubAppSetup("https://api.example") }
        // The instance's administrator (the admin module's, stored): the owner of organization A.
        single<InstanceAdmins> {
            object : InstanceAdmins {
                override suspend fun isAdmin(principal: AuthenticatedPrincipal): Boolean =
                    principal.subjectId == "user:a:owner"
            }
        }
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        single<Clock> { clock }
        single<GitHubApi> { github }
        val users = LinkedGitHubUsers(emptyMap())
        single<UsersRepository> { users }
        single<LinkedIdentities> { users }
        single<GitHubDirectoryApi> { directoryApi }
        single<GitHubManifestApi> { directoryApi }
        single<GitHubContentsApi> { contentsApi }
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

    private fun orgOf(letter: String) = if (letter == "a") ORG else OTHER_ORG

    private fun gitHubTest(block: suspend (TestServer) -> Unit) =
        serverTest(listOf(GatesModule, GitHubIntegrationModule), overrides) { server -> block(server) }

    private suspend fun TestServer.get(token: String?, path: String): HttpResponse =
        client.config { followRedirects = false }.get(path) {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }

    private suspend fun TestServer.installBoth() {
        val installations = koin.get<GitHubRepository>()
        installations.saveInstallation(GitHubInstallation(ORG, 42, "acme"), NOW)
        installations.saveInstallation(GitHubInstallation(OTHER_ORG, 43, "globex"), NOW)
    }

    @Test
    fun eachOrganizationListsItsOwnRepositoriesAndTeams() = gitHubTest { server ->
        server.installBoth()
        val repos = server.get("user:a:owner", "/api/v1/repos").body<ReposResponse>()
        assertEquals(listOf("api", "web"), repos.items.map { it.name })
        val api = repos.items.first()
        assertEquals(listOf("platform"), api.owners)
        assertEquals("2026-10-29T12:00:00Z" to "main", api.pushedAt to api.defaultBranch)
        assertNull(repos.next)
        val raw = server.get("reader:a:ops", "/api/v1/repos?limit=1").bodyAsText()
        assertTrue(""""next":"api"""" in raw && """"defaultBranch":"main"""" in raw, raw)

        val teams = server.get("reader:a:ops", "/api/v1/orgs/$ORG/teams").body<TeamsResponse>()
        assertEquals(listOf("platform"), teams.items.map { it.slug })
        assertEquals(listOf("api"), teams.items.single().repos)

        val b = server.get("user:b:owner", "/api/v1/repos").body<ReposResponse>()
        assertEquals(listOf("globex-secret"), b.items.map { it.name }, "Org B sees its own, never org A's")
        val bTeams = server.get("user:b:owner", "/api/v1/orgs/$OTHER_ORG/teams").body<TeamsResponse>()
        assertEquals(listOf("globex-team"), bTeams.items.map { it.slug })
        assertEquals(2, directoryApi.reads, "One read of GitHub per organization")
        // A cursor: after the last row, nothing; one that names nothing of the organization (org A's in B), a 400.
        assertEquals(emptyList(), server.get("user:a:owner", "/api/v1/repos?after=web").body<ReposResponse>().items)
        val teamsOfA = "/api/v1/orgs/$ORG/teams?after=platform"
        assertEquals(emptyList(), server.get("user:a:owner", teamsOfA).body<TeamsResponse>().items)
        assertEquals(HttpStatusCode.BadRequest, server.get("user:b:owner", "/api/v1/repos?after=web").status)
        val bAfterA = "/api/v1/orgs/$OTHER_ORG/teams?after=platform"
        assertEquals(HttpStatusCode.BadRequest, server.get("user:b:owner", bAfterA).status)
    }

    @Test
    fun theRoutesAnswerOnlyTheirCallersAndHideAnotherOrganization() = gitHubTest { server ->
        server.installBoth()
        for (path in listOf("/api/v1/repos", "/api/v1/orgs/$ORG/teams")) {
            assertEquals(HttpStatusCode.Unauthorized, server.get(null, path).status, path)
            assertEquals(HttpStatusCode.Forbidden, server.get("agent:a:x", path).status, path)
        }
        assertEquals(HttpStatusCode.NotFound, server.get("user:b:owner", "/api/v1/orgs/$ORG/teams").status)
        assertEquals(HttpStatusCode.NotFound, server.get("user:a:owner", "/api/v1/orgs/${Uuid.random()}/teams").status)
        // `{org}` reads a short name too, through the orgs module: acme is A's, globex is B's.
        assertEquals(HttpStatusCode.OK, server.get("user:a:owner", "/api/v1/orgs/acme/teams").status)
        assertEquals(HttpStatusCode.NotFound, server.get("user:a:owner", "/api/v1/orgs/globex/teams").status)
        assertEquals(HttpStatusCode.NotFound, server.get("user:a", "/api/v1/repos").status, "No organization")
        assertEquals(HttpStatusCode.BadRequest, server.get("user:a:owner", "/api/v1/repos?limit=0").status)
        assertEquals(HttpStatusCode.BadRequest, server.get("user:a:owner", "/api/v1/orgs/$ORG/teams?limit=101").status)
    }

    @Test
    fun anOrganizationWithoutTheAppListsNothingAndGitHubDownIsUnavailable() = gitHubTest { server ->
        assertEquals(emptyList(), server.get("user:a:owner", "/api/v1/repos").body<ReposResponse>().items)
        server.koin.get<GitHubRepository>().saveInstallation(GitHubInstallation(ORG, 42, "acme"), NOW)
        directoryApi.failure = gitHubDown()
        assertEquals(HttpStatusCode.ServiceUnavailable, server.get("user:a:owner", "/api/v1/repos").status)
        directoryApi.failure = null
        // GitHub rests 30 s after a failed first read (AUDIT-SECU-2 B4): a console that keeps reloading waits.
        assertEquals(HttpStatusCode.ServiceUnavailable, server.get("user:a:owner", "/api/v1/repos").status)
        clock.now += 31.seconds
        assertEquals(2, server.get("user:a:owner", "/api/v1/repos").body<ReposResponse>().items.size)
    }

    @Test
    fun anInstallReadsTheDirectoryAgainAtTheNextList() = gitHubTest { server ->
        server.koin.get<GitHubRepository>().saveInstallation(GitHubInstallation(ORG, 42, "acme"), NOW)
        server.koin.get<GitHubOriginsRepository>().saveOrigin(ACME_ORIGIN, NOW)
        server.get("user:a:owner", "/api/v1/repos")
        val apps = server.koin.get<GitHubAppStore>()
        apps.saveApp(createdApp(), "user:a:owner", NOW)
        val start = server.get("user:a:owner", "/api/v1/github/app/install/start")
        val state = start.headers[HttpHeaders.Location].orEmpty().substringAfter("state=")
        val callback = "/api/v1/github/app/install/callback?installation_id=42&code=code-acme&state=$state"
        val back = server.get("user:a:owner", callback)
        assertEquals("https://console.example/github?installed=1", back.headers[HttpHeaders.Location])
        server.get("user:a:owner", "/api/v1/repos")
        assertEquals(2, directoryApi.reads)
    }

    @Test
    fun theRepositoryKeepsEveryFieldAndPagesByKey() = gitHubTest { server ->
        val directory = server.koin.get<GitHubDirectoryRepository>()
        assertNull(directory.state(ORG))
        assertFalse(directory.claim(ORG, NOW, NOW), "Nothing to claim")
        val many = (1..450).map { repo("r" + it.toString().padStart(3, '0')) }
        val bare = GitHubRepo("zz", null, null, "trunk", archived = true, pushedAt = null, owners = listOf("a", "b"))
        val teams = (1..250).map {
            GitHubTeam("t" + it.toString().padStart(3, '0'), "T $it", listOf("dev"), listOf("r001"))
        }
        directory.replace(ORG, GitHubDirectory(many + bare, teams), NOW)
        // A repository GitHub never pushed to answers without a date, as the contract's optional field.
        assertEquals(RepoResponse("zz", null, null, "trunk", true, null, listOf("a", "b")), bare.toRepoResponse())
        assertEquals(NOW to NOW, directory.state(ORG)?.let { it.syncedAt to it.checkedAt })
        assertEquals(many.take(100), directory.repos(ORG, null, 100))
        assertEquals(listOf(bare), directory.repos(ORG, "r450", 100))
        assertEquals(teams.drop(200).take(3), directory.teams(ORG, "t200", 3))
        assertTrue(directory.repos(OTHER_ORG, null, 100).isEmpty())

        assertFalse(directory.claim(ORG, NOW + 1.minutes, NOW), "Fresh: not stale before NOW")
        assertTrue(directory.claim(ORG, NOW + 11.minutes, NOW + 1.minutes))
        assertFalse(directory.claim(ORG, NOW + 11.minutes, NOW + 1.minutes), "Claimed once")
        directory.replace(ORG, GitHubDirectory(listOf(repo("only")), emptyList()), NOW + 12.minutes)
        assertEquals(listOf("only"), directory.repos(ORG, null, 100).map { it.name })
        assertTrue(directory.teams(ORG, null, 100).isEmpty())
        directory.forget(ORG)
        assertNull(directory.state(ORG))

        // The console's first lists arrive together: their reads replace the directory one after the other.
        coroutineScope {
            (1..4).map { async { directory.replace(ORG, GitHubDirectory(many, teams), NOW) } }.awaitAll()
        }
        assertEquals(100, directory.repos(ORG, null, 100).size)
    }

    @Test
    fun rowLevelSecurityKeepsEachOrganizationsDirectoryToItself() = gitHubTest { server ->
        server.installBoth()
        server.get("user:a:owner", "/api/v1/repos")
        server.get("user:b:owner", "/api/v1/repos")
        val tables = listOf("github_repos", "github_teams", "github_directory_syncs")
        val counts = server.driver.asTenant(null) { tables.map { count(it) } }
        assertEquals(listOf("0", "0", "0"), counts, "No organization set: nothing")
        val b = server.driver.asTenant(OTHER_ORG) { tables.map { count(it) } }
        assertEquals(listOf("1", "1", "1"), b, "Org B: its own rows only")
        val names = server.driver.asTenant(OTHER_ORG) {
            fetchAll("SELECT name FROM github_repos").getOrThrow().map { it.get("name").asString() }
        }
        assertEquals(listOf("globex-secret"), names)
        for (table in tables) {
            val insert = when (table) {
                "github_repos" -> "INSERT INTO github_repos (org_id, name, default_branch, archived, owners) " +
                    "VALUES (:org, 'x', 'main', FALSE, '[]')"
                "github_teams" -> "INSERT INTO github_teams (org_id, slug, name, members, repos) " +
                    "VALUES (:org, 'x', 'x', '[]', '[]')"
                else -> "INSERT INTO github_directory_syncs (org_id, checked_at) VALUES (:org, NOW())"
            }
            val forged = attempt {
                server.driver.asTenant(OTHER_ORG) { execute(Statement.create(insert).bind("org", Uuid.random())) }
            }
            assertTrue(forged.isFailure, "$table refuses a row of another organization")
        }
        val updated = server.driver.asTenant(OTHER_ORG) {
            execute(Statement.create("UPDATE github_repos SET archived = TRUE WHERE org_id = :org").bind("org", ORG))
                .getOrThrow()
        }
        assertEquals(0L, updated, "Org B cannot touch org A's rows")
    }

    @Test
    fun anAdministratorCreatesTheAppByTheManifestAndTheServerUsesItAtOnce() = gitHubTest { server ->
        val start = server.get("user:a:owner", "/api/v1/github/app/manifest/start?owner=acme")
        assertEquals(HttpStatusCode.OK, start.status)
        assertEquals("no-store", start.headers[HttpHeaders.CacheControl])
        val csp = start.headers["Content-Security-Policy"].orEmpty()
        assertTrue("form-action https://github.com" in csp && "script-src 'nonce-" in csp, csp)
        assertEquals("no-referrer", start.headers["Referrer-Policy"])
        val page = start.bodyAsText()
        assertTrue("action=\"https://github.com/organizations/acme/settings/apps/new?state=" in page, page)
        assertTrue("&quot;redirect_url&quot;:&quot;https://api.example/api/v1/github/app/manifest/callback" in page)
        val state = Regex("state=([0-9a-f]+)").find(page)?.groupValues?.get(1).orEmpty()

        directoryApi.created["code1"] = createdApp()
        val callback = "/api/v1/github/app/manifest/callback?code=code1&state=$state"
        // The state is the session's that started (AUDIT-SECU-2 B2): another session's, or none, is refused.
        assertEquals(HttpStatusCode.Forbidden, server.get("admin:a:ops", callback).status)
        assertEquals(HttpStatusCode.Unauthorized, server.get(null, callback).status)
        val back = server.get("user:a:owner", callback)
        assertEquals(HttpStatusCode.Found, back.status)
        assertEquals("https://console.example/console/settings?github=created", back.headers[HttpHeaders.Location])
        val status = server.get("user:a:owner", "/api/v1/github/app").body<GitHubAppStatus>()
        assertEquals("ostrom-acme" to "https://github.com/apps/ostrom-acme", status.slug to status.htmlUrl)
        assertEquals(HttpStatusCode.Forbidden, server.get("user:a:owner", callback).status, "Once")
        assertEquals(HttpStatusCode.Conflict, server.get("user:a:owner", "/api/v1/github/app/manifest/start").status)

        // The webhook secret GitHub chose for the App is the one checked now.
        val body = """{"action":"deleted","installation":{"id":99}}"""
        val delivered = server.client.post("/webhooks/github") {
            header("X-GitHub-Event", "installation")
            header("X-GitHub-Delivery", "72f2a3c0-0000-0000-0000-000000000001")
            header("X-Hub-Signature-256", sign("whsec-created", body))
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.NoContent, delivered.status)
    }

    @Test
    fun onlyTheInstancesAdministratorInABrowserStartsTheManifest() = gitHubTest { server ->
        val path = "/api/v1/github/app/manifest/start"
        assertEquals(HttpStatusCode.Unauthorized, server.get(null, path).status)
        assertEquals(HttpStatusCode.Forbidden, server.get("admin:a:ops", path).status, "An org admin is not one")
        assertEquals(HttpStatusCode.Forbidden, server.get("reader:a:ops", path).status, "An API token")
        assertEquals(HttpStatusCode.Forbidden, server.get("agent:a:x", path).status)
        assertEquals(HttpStatusCode.BadRequest, server.get("user:a:owner", "$path?owner=-bad").status)
        val callback = "/api/v1/github/app/manifest/callback"
        assertEquals(HttpStatusCode.BadRequest, server.get(null, callback).status)
        assertEquals(HttpStatusCode.Unauthorized, server.get(null, "$callback?code=c&state=forged").status)
        assertEquals(HttpStatusCode.Forbidden, server.get("user:a:owner", "$callback?code=c&state=forged").status)
        // Opened by another site in the administrator's browser (AUDIT-SECU-2 B1): refused before anything.
        val elsewhere = server.client.config { followRedirects = false }.get(path) {
            header(HttpHeaders.Authorization, "Bearer user:a:owner")
            header("Sec-Fetch-Site", "cross-site")
        }
        assertEquals(HttpStatusCode.Forbidden, elsewhere.status)
    }

    @Test
    fun theStoredAppIsSealedAndOpensOnlyWithItsKey() = gitHubTest { server ->
        val sealed = PostgresGitHubAppStore(server.driver, AesGcmSecretCipher(ByteArray(32) { 7 }))
        assertNull(sealed.app())
        assertTrue(sealed.saveApp(createdApp(), "usr_admin", NOW))
        assertFalse(sealed.saveApp(createdApp(78), "usr_admin", NOW), "One App per instance")
        val app = sealed.app()
        assertEquals("77" to "ostrom-acme", app?.appId to app?.slug)
        assertEquals(createdApp().privateKeyPem, app?.privateKeyPem)
        assertEquals("whsec-created" to "client-secret", app?.webhookSecret to app?.clientSecret)
        assertEquals("Iv1.abc", app?.clientId)
        val row = server.driver.fetchAll("SELECT * FROM github_apps").getOrThrow().first()
        for (column in listOf("private_key_sealed", "client_secret_sealed", "webhook_secret_sealed")) {
            val stored = row.get(column).asString()
            assertFalse("secret" in stored || "PRIVATE KEY" in stored, "$column is sealed")
        }
        val otherKey = PostgresGitHubAppStore(server.driver, AesGcmSecretCipher(ByteArray(32) { 8 })).app()
        assertEquals(null to null, otherKey?.privateKeyPem to otherKey?.clientSecret)
        assertEquals("ostrom-acme", otherKey?.slug)

        sealed.saveState("h1", "usr_admin", NOW)
        assertNull(sealed.takeState("h1", NOW), "Expired at that instant")
        assertNull(sealed.takeState("h1", NOW - 1.minutes), "Taken already")
        sealed.saveState("h2", "usr_admin", NOW + 10.minutes)
        assertEquals("usr_admin", sealed.takeState("h2", NOW))

        server.driver.execute("DELETE FROM github_apps").getOrThrow()
        val noHook = createdApp().copy(webhookSecret = null)
        sealed.saveApp(noHook, "usr_admin", NOW)
        assertNull(sealed.app()?.webhookSecret)

        // An App imported once from the former variables may have no OAuth client.
        server.driver.execute("DELETE FROM github_apps").getOrThrow()
        sealed.saveApp(createdApp().copy(clientId = null, clientSecret = null), "environment", NOW)
        val imported = sealed.app()
        assertEquals(null to null, imported?.clientId to imported?.clientSecret)
        assertEquals("ostrom-acme", imported?.slug)
    }

    @Test
    fun theDirectorysRequestPathReadsUseTheirIndexes() = gitHubTest { server ->
        // Fixtures only: the rows of many organizations at once and the statistics, which no request writes (#68).
        val fixtures = server.database.superuser
        val driver = server.driver
        for ((org, count) in listOf(OTHER_ORG to 20_000, ORG to 2_000)) {
            fixtures.execute(
                "INSERT INTO github_repos (org_id, name, default_branch, archived, owners) " +
                    "SELECT CAST('$org' AS uuid), 'repo-' || i, 'main', FALSE, '[]' " +
                    "FROM generate_series(1, $count) AS i",
            ).getOrThrow()
            fixtures.execute(
                "INSERT INTO github_teams (org_id, slug, name, members, repos) SELECT CAST('$org' AS uuid), " +
                    "'team-' || i, 'Team', '[]', '[]' FROM generate_series(1, $count) AS i",
            ).getOrThrow()
            fixtures.execute(
                "INSERT INTO github_directory_syncs (org_id, synced_at, checked_at) VALUES (CAST('$org' AS uuid), " +
                    "NOW(), NOW())",
            ).getOrThrow()
        }
        // One row per organization: as many others as an instance can hold.
        fixtures.execute(
            "INSERT INTO github_directory_syncs (org_id, synced_at, checked_at) SELECT gen_random_uuid(), NOW(), " +
                "NOW() FROM generate_series(1, 20000)",
        ).getOrThrow()
        fixtures.execute("VACUUM ANALYZE github_repos").getOrThrow()
        fixtures.execute("VACUUM ANALYZE github_teams").getOrThrow()
        fixtures.execute("VACUUM ANALYZE github_directory_syncs").getOrThrow()
        val plans = linkedMapOf(
            "first page of repositories" to driver.explain(GitHubDirectoryQueries.repos(false)),
            "next page of repositories" to driver.explain(GitHubDirectoryQueries.repos(true), "repo-500"),
            "first page of teams" to driver.explain(GitHubDirectoryQueries.teams(false)),
            "next page of teams" to driver.explain(GitHubDirectoryQueries.teams(true), "team-500"),
            "state of the directory" to driver.explain(GitHubDirectoryQueries.STATE),
        )
        val report = plans.entries.joinToString("\n\n") { (name, plan) -> "-- $name\n$plan" }
        SystemFileSystem.createDirectories(Path("build/reports/explain"))
        SystemFileSystem.sink(Path("build/reports/explain/integrations-github.txt")).buffered()
            .use { it.writeString(report) }
        for ((name, plan) in plans) {
            assertFalse("Seq Scan on github_" in plan, "$name scans the table:\n$plan")
        }
    }

    /** The plan of [sql] in org A, read as a role row level security applies to. */
    private suspend fun Driver.explain(sql: String, after: String? = null): String = asTenant(ORG) {
        var statement = Statement.create("EXPLAIN (ANALYZE, BUFFERS) $sql").bind("org", ORG)
        if (":limit" in sql) statement = statement.bind("limit", 101)
        if (after != null) statement = statement.bind("after", after)
        fetchAll(statement).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
    }

    private suspend fun sign(secret: String, body: String): String {
        val key = CryptographyProvider.Default.get(HMAC).keyDecoder(SHA256)
            .decodeFromByteArray(HMAC.Key.Format.RAW, secret.encodeToByteArray())
        val mac = key.signatureGenerator().generateSignature(body.encodeToByteArray())
        return "sha256=" + mac.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    @Test
    fun theAnalysisReadsEachOrganizationsRepositoriesThroughItsOwnInstallationOnly() = gitHubTest { server ->
        server.installBoth()
        // The module binds the rules analysis's reader: the rules module's default is never used with GitHub.
        val reader = assertIs<GitHubRepositoryReader>(server.koin.get<RepositoryReader>())
        val acme = assertIs<RepositoryListing.Connected>(reader.repositories(ORG)).repos
        assertEquals(listOf("acme/web", "acme/api"), acme.map { "${it.owner}/${it.name}" })
        val globex = assertIs<RepositoryListing.Connected>(reader.repositories(OTHER_ORG)).repos
        assertEquals(listOf("globex/globex-secret"), globex.map { "${it.owner}/${it.name}" })

        val api = acme.single { it.name == "api" }
        assertFailsWith<RepositoryReadException> { reader.tree(OTHER_ORG, api) }
        assertFailsWith<RepositoryReadException> { reader.file(OTHER_ORG, api, ".github/CODEOWNERS") }
        assertEquals(emptyList(), contentsApi.reads, "Org B never reads acme's code, through any installation")
        assertEquals(5, reader.tree(ORG, api).paths.size)
        assertEquals(setOf(42L), contentsApi.reads.map { it.first }.toSet())
    }

    @Test
    fun theEngineProposesRulesFromGitHubForItsOrganizationOnly() = gitHubTest { server ->
        server.installBoth()
        // The rules tables, migrated by their owner as `ostrom-server migrate` does; the driver serves them.
        server.database.migrationRunner(RecordingLogger()).migrate("rules", RulesMigrations)
        val runs = PostgresAnalysisRepository(server.driver)
        val rules = PostgresRulesRepository(server.driver)
        val reader = server.koin.get<RepositoryReader>()
        val acme = assertIs<RepositoryListing.Connected>(reader.repositories(ORG)).repos
        val run = AnalysisRun(ORG, AnalysisKind.ANALYSIS, "run_1", AnalysisRun.CONNECT, 0.0, null, "lyes", NOW, NOW)
        runs.transaction(ORG) {
            lock(AnalysisKind.ANALYSIS)
            put(run)
        }

        AnalyzeOrganizationUseCaseImpl(runs, rules, reader, RecordingLogger(), clock)(run, acme)

        assertEquals(AnalysisRun.READY, runs.run(ORG, AnalysisKind.ANALYSIS)?.phase)
        val proposed = rules.rules(ORG, RuleQuery(), 50)
        assertTrue(proposed.isNotEmpty(), "CODEOWNERS, migrations and a protected main give rules")
        assertTrue(proposed.all { it.status == RuleStatus.PROPOSED })
        assertEquals(emptyList(), rules.rules(OTHER_ORG, RuleQuery(), 50), "Org B sees none of acme's rules")
        assertNull(runs.run(OTHER_ORG, AnalysisKind.ANALYSIS))
        assertEquals(setOf(42L), contentsApi.reads.map { it.first }.toSet(), "Only acme's installation read")
    }

    private suspend fun QueryExecutor.count(table: String): String =
        fetchAll("SELECT COUNT(*) AS n FROM $table").getOrThrow().first().get("n").asString()
}

/**
 * Runs [block] in one transaction under row level security, the organization set when [org] is given. The tests'
 * driver is the serving role, subject to it as in production (#68).
 */
private suspend fun <T> Driver.asTenant(org: Uuid?, block: suspend QueryExecutor.() -> T): T =
    transaction {
        if (org != null) {
            fetchAll(Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", org.toString()))
                .getOrThrow()
        }
        block()
    }

// PostgreSQL refuses the row (WITH CHECK): the statement fails, or the commit of the aborted transaction does.
private suspend fun attempt(block: suspend () -> Result<Long>): Result<Long> = try {
    block().also { it.getOrThrow() }
} catch (error: CancellationException) {
    throw error
} catch (expected: Exception) {
    Result.failure(expected)
}
