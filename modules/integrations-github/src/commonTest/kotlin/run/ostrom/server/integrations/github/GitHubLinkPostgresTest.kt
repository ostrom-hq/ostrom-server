// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.integrations.github

import run.ostrom.server.gates.GatesModule
import run.ostrom.server.gates.domain.models.Gate
import run.ostrom.server.gates.domain.models.GateActor
import run.ostrom.server.gates.domain.models.GateNode
import run.ostrom.server.gates.domain.services.GateActorResolver
import run.ostrom.server.gates.domain.services.GateNodeResolver
import run.ostrom.server.gates.domain.services.GateReceiptSigner
import run.ostrom.server.gates.domain.services.GateSessions
import run.ostrom.server.identity.domain.LinkedIdentities
import run.ostrom.server.identity.domain.UsersRepository
import run.ostrom.server.integrations.github.domain.CloudSessionsSwitch
import run.ostrom.server.integrations.github.domain.GitHubAccountInUseException
import run.ostrom.server.integrations.github.domain.GitHubApi
import run.ostrom.server.integrations.github.domain.GitHubAppCredentials
import run.ostrom.server.integrations.github.domain.GitHubAppStore
import run.ostrom.server.integrations.github.domain.GitHubDevicePoll
import run.ostrom.server.integrations.github.domain.GitHubIdentity
import run.ostrom.server.integrations.github.domain.GitHubLinkAttempt
import run.ostrom.server.integrations.github.domain.GitHubLinkKind
import run.ostrom.server.integrations.github.domain.GitHubLinkState
import run.ostrom.server.integrations.github.domain.GitHubLinkStore
import run.ostrom.server.integrations.github.domain.GitHubLinks
import run.ostrom.server.integrations.github.domain.GitHubTokenClaim
import run.ostrom.server.integrations.github.domain.GitHubTokens
import run.ostrom.server.integrations.github.domain.GitHubUserAuthApi
import run.ostrom.server.integrations.github.infrastructure.GitHubSettings
import run.ostrom.server.integrations.github.presentation.GitHubDeviceLinkDto
import run.ostrom.server.integrations.github.presentation.GitHubDeviceLinkPollDto
import run.ostrom.server.integrations.github.presentation.GitHubLinkDto
import run.ostrom.server.integrations.github.presentation.GitHubLinksPage
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalChannel
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.orgs.domain.FindOrgUseCase
import run.ostrom.server.orgs.domain.OrgNotFoundException
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
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
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The members' GitHub links against a real PostgreSQL and real HTTP: both flows, the admin's links, isolation. */
class GitHubLinkPostgresTest {
    // Whole seconds: PostgreSQL keeps microseconds, and the tests compare what they wrote with what they read.
    private val clock = SettableClock(Instant.fromEpochSeconds(Clock.System.now().epochSeconds))
    private val github = FakeGitHubUserAuth()
    private val audit = RecordingAudit()
    private val cipher = AesGcmSecretCipher(ByteArray(32) { 7 })
    private val members = SettableMembers(mutableSetOf(ORG to OWNER, ORG to DEV, ORG to ROBOT, OTHER_ORG to B_OWNER))

    // Tokens are `<kind>:<org letter>:<member>`: `user` a browser session, `cli` the CLI's login (`cloud:write`),
    // `read` a token with `cloud:read`, `orgs` one with `orgs:read`, `svc` a service key. Members: owner (an owner),
    // admin (OWNER's id as an admin), dev, robot; `user:c:x` belongs to no organization.
    private val overrides = module {
        single<TokenAuthenticator> {
            object : TokenAuthenticator {
                override suspend fun authenticate(token: String): AuthenticatedPrincipal {
                    val kind = token.substringBefore(':')
                    val (scopes, channel) = when (kind) {
                        "user" -> setOf("user:*") to PrincipalChannel.BROWSER
                        "cli" -> setOf("cloud:write") to PrincipalChannel.API_TOKEN
                        "read" -> setOf("cloud:read") to PrincipalChannel.API_TOKEN
                        else -> setOf("orgs:read") to PrincipalChannel.API_TOKEN
                    }
                    val person = if (kind == "svc") PrincipalKind.SERVICE else PrincipalKind.USER
                    return AuthenticatedPrincipal(token, person, scopes, channel = channel)
                }
            }
        }
        single<PrincipalOrgResolver> {
            object : PrincipalOrgResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? {
                    val (_, org, name) = principal.subjectId.split(":")
                    val member = when (name) {
                        "owner" -> if (org == "b") B_OWNER else OWNER
                        "dev" -> DEV
                        "robot" -> ROBOT
                        else -> return null
                    }
                    val role = if (name == "owner") OrgRole.OWNER else OrgRole.MEMBER
                    return OrgPrincipal(if (org == "b") OTHER_ORG else ORG, member, role)
                }
            }
        }
        single<MemberDirectory> { members }
        single<GitHubUserAuthApi> { github }
        single<CloudSessionsSwitch> { CloudSessionsSwitch { true } }
        single<OrgAuditTrail> { audit }
        single<Clock> { clock }
        single<SecretCipher> { cipher }
        single { GitHubSettings(null, null, null, null, webOrigin = CONSOLE) }
        // The instance's App, as the manifest flow (or the one-time import of the former variables) stored it.
        single<GitHubAppStore> {
            MemoryGitHubAppStore(
                GitHubAppCredentials("123", "ostrom-dev", null, "whsec", CLIENT_ID, "client-secret"),
            )
        }
        // The rest of the module, as GitHubPostgresTest binds it.
        single<GitHubApi> { FakeGitHubApi() }
        single<GateActorResolver> {
            object : GateActorResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal): GateActor? = null
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
        val users = LinkedGitHubUsers(emptyMap())
        single<UsersRepository> { users }
        single<LinkedIdentities> { users }
        single<FindOrgUseCase> {
            object : FindOrgUseCase {
                override suspend fun invoke(ref: String): Uuid = throw OrgNotFoundException()
            }
        }
    }

    private fun linkTest(block: suspend (TestServer) -> Unit) =
        serverTest(listOf(GatesModule, GitHubIntegrationModule), overrides) { server -> block(server) }

    private suspend fun TestServer.call(
        method: String,
        token: String?,
        path: String,
        site: String? = "same-origin",
    ): HttpResponse {
        val http = client.config { followRedirects = false }
        val setup: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            site?.let { header("Sec-Fetch-Site", it) }
        }
        return when (method) {
            "POST" -> http.post(path, setup)
            "DELETE" -> http.delete(path, setup)
            else -> http.get(path, setup)
        }
    }

    /** Starts a web link with [token]'s session, for [member] when given: the state GitHub will send back. */
    private suspend fun TestServer.startWeb(token: String, member: Uuid? = null): String {
        val path = if (member == null) "/api/v1/github/link/start" else "/api/v1/github/links/$member/start"
        val start = call("GET", token, path)
        assertEquals(HttpStatusCode.Found, start.status)
        return start.headers[HttpHeaders.Location].orEmpty().substringAfter("state=").substringBefore('&')
    }

    /** GitHub's callback, with [token]'s session: always back to the console, whose query it returns. */
    private suspend fun TestServer.callback(token: String?, query: String): String {
        val back = call("GET", token, "/api/v1/github/link/callback?$query", site = "cross-site")
        assertEquals(HttpStatusCode.Found, back.status)
        return back.headers[HttpHeaders.Location].orEmpty().removePrefix("$CONSOLE/github?")
    }

    private suspend fun TestServer.poll(token: String, id: String) =
        call("POST", token, "/api/v1/github/link/device/$id").body<GitHubDeviceLinkPollDto>()

    @Test
    fun aMemberLinksFromTheCliByTheDeviceFlow() = linkTest { server ->
        val started = server.call("POST", "cli:a:dev", "/api/v1/github/link/device")
        assertEquals(HttpStatusCode.Created, started.status)
        assertEquals("no-store", started.headers[HttpHeaders.CacheControl])
        val link = started.body<GitHubDeviceLinkDto>()
        assertEquals("ABCD-1234" to 5, link.userCode to link.interval)
        assertEquals(GitHubDeviceLinkPollDto("pending", 5), server.poll("cli:a:dev", link.id))

        clock.now += 5.seconds
        github.polls += GitHubDevicePoll.Authorized(GitHubTokens("ghu_device", clock.now + 8.hours, "ghr_1", null))
        val linked = server.poll("cli:a:dev", link.id)
        assertEquals("linked", linked.status)
        assertEquals("octocat", linked.link?.login)
        val status = server.call("GET", "cli:a:dev", "/api/v1/github/link").body<GitHubLinkDto>()
        assertEquals("linked" to "octocat", status.state to status.login)
        assertEquals(DEV.toString() to 7L, status.memberId to status.userId)
        assertEquals((clock.now + 8.hours).toString(), status.accessExpiresAt)
        assertEquals(listOf("device-code"), github.polled)
        assertTrue(server.poll404("cli:a:dev", link.id), "Spent")

        for ((answer, expected) in listOf(GitHubDevicePoll.Denied to "denied", GitHubDevicePoll.Expired to "expired")) {
            val id = server.call("POST", "cli:a:dev", "/api/v1/github/link/device").body<GitHubDeviceLinkDto>().id
            clock.now += 5.seconds
            github.polls += answer
            assertEquals(GitHubDeviceLinkPollDto(expected), server.poll("cli:a:dev", id))
        }
        assertTrue(server.poll404("cli:a:dev", "not-an-id"))
        assertTrue(server.poll404("cli:a:dev", Uuid.random().toString()))
    }

    private suspend fun TestServer.poll404(token: String, id: String): Boolean =
        call("POST", token, "/api/v1/github/link/device/$id").status == HttpStatusCode.NotFound

    @Test
    fun aMemberLinksFromTheConsoleInTheBrowserThatStartedIt() = linkTest { server ->
        var state = server.startWeb("user:a:dev")
        assertEquals("linked=1", server.callback("user:a:dev", "code=c1&state=$state"))
        assertEquals("error=github_link_not_found", server.callback("user:a:dev", "code=c1&state=$state"), "Once")
        val status = server.call("GET", "user:a:dev", "/api/v1/github/link").body<GitHubLinkDto>()
        assertEquals("linked" to "octocat", status.state to status.login)

        state = server.startWeb("user:a:dev")
        assertEquals("error=github_link_browser_mismatch", server.callback("user:a:owner", "code=c&state=$state"))
        assertEquals("error=github_link_browser_mismatch", server.callback(null, "code=c&state=$state"))
        assertEquals("error=github_link_denied", server.callback("user:a:dev", "error=access_denied&state=$state"))
        assertEquals("error=github_link_not_found", server.callback(null, "code=c"))

        // Only from the console, in a browser.
        val elsewhere = server.call("GET", "user:a:dev", "/api/v1/github/link/start", site = "cross-site")
        assertEquals(HttpStatusCode.Forbidden, elsewhere.status)
        assertEquals(HttpStatusCode.Forbidden, server.call("GET", "cli:a:dev", "/api/v1/github/link/start").status)

        assertEquals(HttpStatusCode.NoContent, server.call("DELETE", "user:a:dev", "/api/v1/github/link").status)
        assertEquals(listOf("ghu_web"), github.revoked)
        val gone = server.call("GET", "user:a:dev", "/api/v1/github/link").body<GitHubLinkDto>()
        assertEquals(GitHubLinkDto(DEV.toString(), "not_linked", true), gone)
    }

    @Test
    fun anOwnerLinksAServiceAccountAndManagesEveryLink() = linkTest { server ->
        github.exchange = { GitHubTokens("ghu_robot", clock.now + 8.hours, "ghr_robot", clock.now + 180.days) }
        val state = server.startWeb("user:a:owner", ROBOT)
        val started = audit.records.last()
        assertEquals(GitHubLinks.STARTED to ROBOT.toString(), started.event to started.subjectId)
        assertEquals("linked=1", server.callback("user:a:owner", "code=c&state=$state"))
        val robot = server.call("GET", "user:a:owner", "/api/v1/github/links/$ROBOT").body<GitHubLinkDto>()
        assertEquals("acme-bot", robot.login)
        val page = server.call("GET", "user:a:owner", "/api/v1/github/links").body<GitHubLinksPage>()
        assertEquals(listOf(ROBOT.toString()), page.items.map { it.memberId })

        for ((method, path) in listOf("GET" to "links", "GET" to "links/$ROBOT", "DELETE" to "links/$ROBOT")) {
            val refused = server.call(method, "user:a:dev", "/api/v1/github/$path")
            assertEquals(HttpStatusCode.Forbidden, refused.status, "A member: $method $path")
        }
        val robotStart = server.call("GET", "user:a:dev", "/api/v1/github/links/$ROBOT/start")
        assertEquals(HttpStatusCode.Forbidden, robotStart.status)
        for (unknown in listOf("not-a-uuid", Uuid.random().toString(), B_OWNER.toString())) {
            val missing = server.call("GET", "user:a:owner", "/api/v1/github/links/$unknown")
            assertEquals(HttpStatusCode.NotFound, missing.status, unknown)
        }
        val removed = server.call("DELETE", "user:a:owner", "/api/v1/github/links/$ROBOT")
        assertEquals(HttpStatusCode.NoContent, removed.status)
        assertEquals(GitHubLinks.REMOVED, audit.records.last().event)
        assertEquals(listOf("ghu_robot"), github.revoked)
        val after = server.call("GET", "user:a:owner", "/api/v1/github/links").body<GitHubLinksPage>()
        assertEquals(emptyList(), after.items)
    }

    @Test
    fun theRoutesAnswerOnlyAPersonOfTheOrganizationWithTheRightScope() = linkTest { server ->
        val routes = listOf(
            "GET" to "/api/v1/github/link",
            "DELETE" to "/api/v1/github/link",
            "POST" to "/api/v1/github/link/device",
            "POST" to "/api/v1/github/link/device/${Uuid.random()}",
            "GET" to "/api/v1/github/link/start",
            "GET" to "/api/v1/github/links",
            "GET" to "/api/v1/github/links/$DEV",
            "DELETE" to "/api/v1/github/links/$DEV",
            "GET" to "/api/v1/github/links/$DEV/start",
        )
        for ((method, path) in routes) {
            assertEquals(HttpStatusCode.Unauthorized, server.call(method, null, path).status, "$method $path")
            assertEquals(HttpStatusCode.Forbidden, server.call(method, "svc:a:dev", path).status, "A service key")
            assertEquals(HttpStatusCode.Forbidden, server.call(method, "orgs:a:dev", path).status, "No cloud scope")
            assertEquals(HttpStatusCode.NotFound, server.call(method, "user:c:x", path).status, "No organization")
        }
        assertEquals(HttpStatusCode.OK, server.call("GET", "read:a:dev", "/api/v1/github/link").status)
        assertEquals(HttpStatusCode.Forbidden, server.call("DELETE", "read:a:dev", "/api/v1/github/link").status)
    }

    @Test
    fun eachOrganizationSeesAndChangesItsOwnLinksOnly() = linkTest { server ->
        val state = server.startWeb("user:a:dev")
        server.callback("user:a:dev", "code=c&state=$state")
        server.call("POST", "cli:a:dev", "/api/v1/github/link/device")
        val theirs = server.call("GET", "user:b:owner", "/api/v1/github/links").body<GitHubLinksPage>()
        assertEquals(emptyList(), theirs.items)
        val unlinked = server.call("DELETE", "user:b:owner", "/api/v1/github/links/$DEV")
        assertEquals(HttpStatusCode.NotFound, unlinked.status, "Not a member of B's")
        val store = server.koin.get<GitHubLinkStore>()
        assertNull(store.link(OTHER_ORG, DEV))
        assertEquals("octocat", store.link(ORG, DEV)?.identity?.login, "A's link is untouched")

        val tables = listOf("github_links", "github_link_attempts")
        assertEquals(listOf("0", "0"), server.driver.asTenant(null) { tables.map { count(it) } }, "No organization")
        assertEquals(listOf("0", "0"), server.driver.asTenant(OTHER_ORG) { tables.map { count(it) } })
        assertEquals(listOf("1", "1"), server.driver.asTenant(ORG) { tables.map { count(it) } })
        val forged = forgedInsert(server.driver)
        assertTrue(forged, "Org B cannot write a row of org A")
        val updated = server.driver.asTenant(OTHER_ORG) {
            execute(Statement.create("UPDATE github_links SET state = 'linked' WHERE org_id = :org").bind("org", ORG))
                .getOrThrow()
        }
        assertEquals(0L, updated)
        val sealed = server.driver.asTenant(ORG) {
            fetchAll("SELECT tokens_sealed FROM github_links").getOrThrow().single().get("tokens_sealed").asString()
        }
        assertFalse("ghu_" in sealed || "ghr_" in sealed, "The tokens are sealed")
    }

    private suspend fun forgedInsert(driver: Driver): Boolean = try {
        driver.asTenant(OTHER_ORG) {
            execute(
                Statement.create(
                    "INSERT INTO github_links (org_id, member_id, github_user_id, github_login, client_id, " +
                        "tokens_sealed, state, token_version, linked_at, refreshed_at) VALUES (:org, :org, 1, 'x', " +
                        "'c', 'x', 'linked', 1, NOW(), NOW())",
                ).bind("org", ORG),
            ).getOrThrow()
        }
        false
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        true
    }

    @Test
    fun theStoreKeepsOneMemberPerAccountAndSingleUseAttempts() = linkTest { server ->
        val store = server.koin.get<GitHubLinkStore>()
        val now = clock.now
        val octocat = GitHubIdentity(7, "octocat")
        val first = store.saveLink(ORG, DEV, octocat, CLIENT_ID, GitHubTokens("ghu_1", null, null, null), now)
        assertEquals(GitHubLinkState.LINKED to null, first.link.state to first.link.accessExpiresAt)
        assertNull(first.replaced)
        val again = store.saveLink(ORG, DEV, octocat, CLIENT_ID, GitHubTokens("ghu_2", now, "ghr_2", now), now)
        assertEquals(now, again.link.refreshExpiresAt, "Relinking the same account replaces the link")
        assertEquals(CLIENT_ID to "ghu_1", again.replaced?.clientId to again.replaced?.accessToken, "To revoke")
        assertFailsWith<GitHubAccountInUseException> {
            store.saveLink(ORG, ROBOT, octocat, CLIENT_ID, GitHubTokens("ghu_3", null, null, null), now)
        }
        store.saveLink(OTHER_ORG, B_OWNER, octocat, CLIENT_ID, GitHubTokens("ghu_b", null, null, null), now)
        assertEquals(listOf("octocat"), store.links(ORG, 10).map { it.identity.login }, "Another organization may")

        val device = GitHubLinkAttempt("a-1", ORG, DEV, GitHubLinkKind.DEVICE, "dc", null, 5, now, now + 15.minutes)
        store.startAttempt(device, now)
        val read = store.attempt(ORG, "a-1")
        assertEquals(listOf("dc", null, "DEVICE"), listOf(read?.secret, read?.binding, read?.kind?.name))
        assertFalse(store.deferPoll(ORG, "a-1", now + 1.seconds, 5, now + 5.seconds), "Another poll moved it")
        assertTrue(store.deferPoll(ORG, "a-1", now, 5, now + 5.seconds))
        assertTrue(store.deferPoll(ORG, "a-1", null, 10, now + 10.seconds))
        assertEquals(10 to now + 10.seconds, store.attempt(ORG, "a-1")?.let { it.pollIntervalSeconds to it.nextPollAt })
        assertNull(store.attempt(OTHER_ORG, "a-1"))
        assertTrue(store.consumeAttempt(ORG, "a-1"))
        assertFalse(store.consumeAttempt(ORG, "a-1"))
        assertNull(store.attempt(ORG, "a-1"))

        // A row this version cannot read is no attempt.
        store.startAttempt(device, now)
        server.driver.asTenant(ORG) { execute("UPDATE github_link_attempts SET kind = 'other'").getOrThrow() }
        assertNull(store.attempt(ORG, "a-1"))
        store.startAttempt(device, now)
        val foreign = cipher.seal("dc".encodeToByteArray(), "github-attempt:$ORG:other:$DEV")
        server.driver.asTenant(ORG) {
            execute(Statement.create("UPDATE github_link_attempts SET secret_sealed = :s").bind("s", foreign))
                .getOrThrow()
        }
        assertNull(store.attempt(ORG, "a-1"), "An envelope moved from another row does not open")

        val removed = store.removeLink(ORG, DEV)
        assertEquals(CLIENT_ID to "ghu_2", removed?.clientId to removed?.accessToken)
        assertNull(store.removeLink(ORG, DEV))
        assertNull(store.attempt(ORG, "a-1"), "The member's attempts go with the link")
    }

    @Test
    fun aClaimHandsOutAValidTokenOrTheRightToRefreshItOnce() = linkTest { server ->
        val store = server.koin.get<GitHubLinkStore>()
        val now = clock.now
        suspend fun link(tokens: GitHubTokens) =
            store.saveLink(ORG, DEV, GitHubIdentity(7, "octocat"), CLIENT_ID, tokens, now)
        suspend fun claim(at: Instant = now, client: String = CLIENT_ID) =
            store.claim(ORG, DEV, client, at, at + 1.hours, at + 5.minutes, 60.seconds)

        assertEquals(GitHubTokenClaim.NotLinked, claim())
        link(GitHubTokens("ghu_forever", null, null, null))
        assertEquals("ghu_forever", assertIs<GitHubTokenClaim.Ready>(claim()).accessToken)
        assertEquals(GitHubTokenClaim.ReconnectRequired(), claim(client = "Iv1.other"), "Another App's link")

        link(GitHubTokens("ghu_long", now + 8.hours, "ghr_1", now + 180.days))
        assertEquals("ghu_long", assertIs<GitHubTokenClaim.Ready>(claim()).accessToken, "Valid long enough")

        // Expiring within the hour: one caller refreshes, the others take the current token while it lasts.
        link(GitHubTokens("ghu_soon", now + 30.minutes, "ghr_1", now + 180.days))
        val owner = assertIs<GitHubTokenClaim.Refresh>(claim())
        assertEquals("ghr_1" to now, owner.refreshToken to owner.startedAt)
        assertEquals("ghu_soon", assertIs<GitHubTokenClaim.Ready>(claim()).accessToken, "Still usable meanwhile")
        // Silent for a minute: taken over; the first owner's writes are fenced.
        val takeover = assertIs<GitHubTokenClaim.Refresh>(claim(now + 61.seconds))
        val renewed = GitHubTokens("ghu_new", now + 8.hours, "ghr_2", now + 180.days)
        assertFalse(store.completeRefresh(ORG, DEV, owner, renewed, now))
        assertFalse(store.abandonRefresh(ORG, DEV, owner, reconnect = true))
        assertTrue(store.completeRefresh(ORG, DEV, takeover, renewed, now + 62.seconds))
        assertEquals("ghu_new", assertIs<GitHubTokenClaim.Ready>(claim()).accessToken)
        assertEquals(now + 62.seconds, store.link(ORG, DEV)?.refreshedAt)

        // A token not usable five more minutes: the others wait for the refresh.
        link(GitHubTokens("ghu_short", now + 3.minutes, "ghr_1", now + 180.days))
        assertIs<GitHubTokenClaim.Refresh>(claim())
        assertEquals(GitHubTokenClaim.Wait, claim())

        // A refresh that failed releases the claim; a refused refresh token ends the link.
        link(GitHubTokens("ghu_soon", now + 30.minutes, "ghr_1", null))
        val failed = assertIs<GitHubTokenClaim.Refresh>(claim())
        assertFalse(store.abandonRefresh(ORG, DEV, failed, reconnect = false))
        val rejected = assertIs<GitHubTokenClaim.Refresh>(claim())
        assertTrue(store.abandonRefresh(ORG, DEV, rejected, reconnect = true))
        assertEquals(GitHubTokenClaim.ReconnectRequired(), claim())
        assertEquals(GitHubLinkState.RECONNECT_REQUIRED, store.link(ORG, DEV)?.state)
    }

    @Test
    fun aTokenThatCannotBeRefreshedLastsUntilItsLastMinute() = linkTest { server ->
        val store = server.koin.get<GitHubLinkStore>()
        val now = clock.now
        suspend fun claim(at: Instant) =
            store.claim(ORG, DEV, CLIENT_ID, at, at + 1.hours, at + 5.minutes, 60.seconds)
        val octocat = GitHubIdentity(7, "octocat")

        store.saveLink(ORG, DEV, octocat, CLIENT_ID, GitHubTokens("ghu_a", now + 30.minutes, null, null), now)
        assertEquals("ghu_a", assertIs<GitHubTokenClaim.Ready>(claim(now)).accessToken)
        assertEquals(GitHubTokenClaim.ReconnectRequired("refresh_expired"), claim(now + 29.minutes + 30.seconds))
        assertEquals(GitHubTokenClaim.ReconnectRequired(), claim(now + 29.minutes + 30.seconds), "Ended once")

        // The refresh token expired.
        store.saveLink(ORG, DEV, octocat, CLIENT_ID, GitHubTokens("ghu_b", now + 30.minutes, "ghr", now), now)
        assertEquals(GitHubTokenClaim.ReconnectRequired("refresh_expired"), claim(now + 30.minutes))

        // A pending refresh without its start time (an older row) is no refresh at all.
        store.saveLink(ORG, DEV, octocat, CLIENT_ID, GitHubTokens("ghu_c", now + 30.minutes, "ghr", null), now)
        server.driver.asTenant(ORG) { execute("UPDATE github_links SET refresh_pending = TRUE").getOrThrow() }
        assertIs<GitHubTokenClaim.Refresh>(claim(now))
        assertEquals("ghu_c", assertIs<GitHubTokenClaim.Ready>(claim(now)).accessToken, "Pending: its token, usable")
        server.driver.asTenant(ORG) { execute("UPDATE github_links SET access_expires_at = NULL").getOrThrow() }
        assertIs<GitHubTokenClaim.Ready>(claim(now), "Pending, but a token that never expires")

        // Sealed by another key, or not tokens at all: the member links again.
        val notTokens = cipher.seal("not json".encodeToByteArray(), "github-link:$ORG:$DEV")
        server.driver.asTenant(ORG) {
            execute(Statement.create("UPDATE github_links SET tokens_sealed = :t").bind("t", notTokens)).getOrThrow()
        }
        assertEquals(GitHubTokenClaim.ReconnectRequired("tokens_unreadable"), claim(now))
        assertEquals(GitHubLinkState.RECONNECT_REQUIRED, store.link(ORG, DEV)?.state, "The member is told")
        assertEquals(GitHubTokenClaim.ReconnectRequired(), claim(now), "Ended once")
        assertNull(store.removeLink(ORG, DEV), "Removed, with nothing to revoke")
        store.saveLink(ORG, DEV, octocat, CLIENT_ID, GitHubTokens("ghu_d", null, null, null), now)
        server.driver.asTenant(ORG) { execute("UPDATE github_links SET tokens_sealed = 'garbage'").getOrThrow() }
        assertEquals(GitHubTokenClaim.ReconnectRequired("tokens_unreadable"), claim(now))
        val relinked = store.saveLink(ORG, DEV, octocat, CLIENT_ID, GitHubTokens("ghu_e", null, null, null), now)
        assertNull(relinked.replaced, "Unreadable: nothing to revoke")
    }

    @Test
    fun theLinksRequestPathReadsUseTheirIndexes() = linkTest { server ->
        // Fixtures only: many organizations' links and attempts, and the statistics, which no request writes.
        val fixtures = server.database.superuser
        for ((org, count) in listOf(OTHER_ORG to 20_000, ORG to 2_000)) {
            fixtures.execute(
                "INSERT INTO github_links (org_id, member_id, github_user_id, github_login, client_id, " +
                    "tokens_sealed, state, token_version, linked_at, refreshed_at) SELECT CAST('$org' AS uuid), " +
                    "gen_random_uuid(), i, 'login-' || i, 'c', 'x', 'linked', 1, NOW(), NOW() " +
                    "FROM generate_series(1, $count) AS i",
            ).getOrThrow()
            fixtures.execute(
                "INSERT INTO github_link_attempts (org_id, id, member_id, kind, secret_sealed, poll_interval, " +
                    "next_poll_at, expires_at) SELECT CAST('$org' AS uuid), 'a-' || i, gen_random_uuid(), 'device', " +
                    "'x', 5, NOW(), NOW() FROM generate_series(1, $count) AS i",
            ).getOrThrow()
        }
        fixtures.execute("VACUUM ANALYZE github_links").getOrThrow()
        fixtures.execute("VACUUM ANALYZE github_link_attempts").getOrThrow()
        val member = "member_id = CAST('$DEV' AS uuid)"
        val plans = linkedMapOf(
            "a member's link" to "SELECT member_id, state FROM github_links WHERE org_id = :org AND $member",
            "a claim" to "SELECT member_id, tokens_sealed FROM github_links WHERE org_id = :org AND $member FOR UPDATE",
            "the account's holder" to "SELECT member_id FROM github_links WHERE org_id = :org AND github_user_id = 7",
            "the organization's links" to
                "SELECT member_id FROM github_links WHERE org_id = :org ORDER BY github_login, member_id LIMIT 1000",
            "an attempt" to "SELECT member_id FROM github_link_attempts WHERE org_id = :org AND id = 'a-5'",
            "a new attempt's cleanup" to
                "DELETE FROM github_link_attempts WHERE org_id = :org AND ($member OR expires_at <= NOW() - " +
                "INTERVAL '1 day')",
        ).mapValues { (_, sql) -> server.driver.explain(sql) }
        val report = plans.entries.joinToString("\n\n") { (name, plan) -> "-- $name\n$plan" }
        SystemFileSystem.createDirectories(Path("build/reports/explain"))
        SystemFileSystem.sink(Path("build/reports/explain/integrations-github-links.txt")).buffered()
            .use { it.writeString(report) }
        for ((name, plan) in plans) {
            assertFalse("Seq Scan on github_" in plan, "$name scans the table:\n$plan")
        }
    }

    private suspend fun Driver.explain(sql: String): String = asTenant(ORG) {
        fetchAll(Statement.create("EXPLAIN (ANALYZE, BUFFERS) $sql").bind("org", ORG)).getOrThrow()
            .joinToString("\n") { it.get("QUERY PLAN").asString() }
    }

    private suspend fun QueryExecutor.count(table: String): String =
        fetchAll("SELECT COUNT(*) AS n FROM $table").getOrThrow().first().get("n").asString()

    private companion object {
        const val CONSOLE = "https://console.example"
        val B_OWNER: Uuid = Uuid.parse("00000000-0000-0000-0000-0000000000b0")
    }
}

/** Runs [block] in one transaction under row level security, the organization set when [org] is given. */
private suspend fun <T> Driver.asTenant(org: Uuid?, block: suspend QueryExecutor.() -> T): T =
    transaction {
        if (org != null) {
            fetchAll(Statement.create("SELECT set_config('ostrom.org_id', :org, TRUE)").bind("org", org.toString()))
                .getOrThrow()
        }
        block()
    }
