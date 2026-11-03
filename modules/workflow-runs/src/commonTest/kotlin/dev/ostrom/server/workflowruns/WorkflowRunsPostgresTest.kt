// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.workflowruns

import dev.ostrom.core.api.models.CheckAttestation
import dev.ostrom.core.api.models.CheckRun
import dev.ostrom.core.api.models.Notification
import dev.ostrom.core.api.models.NotificationStatus
import dev.ostrom.core.api.models.PackageEnvelope
import dev.ostrom.core.api.models.PackageMode
import dev.ostrom.server.compiler.CompilerModule
import dev.ostrom.server.compiler.domain.models.PackagePin
import dev.ostrom.server.compiler.domain.models.StoredPackage
import dev.ostrom.server.compiler.domain.repositories.CompilerRepository
import dev.ostrom.server.gates.GatesModule
import dev.ostrom.server.gates.domain.models.GateSubject
import dev.ostrom.server.gates.domain.services.GateCheckProof
import dev.ostrom.server.gates.domain.services.GateCheckProofs
import dev.ostrom.server.identity.IdentityModule
import dev.ostrom.server.identity.domain.ApiToken
import dev.ostrom.server.identity.domain.ApiTokensRepository
import dev.ostrom.server.identity.domain.CLI_SCOPES
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.SecretHasher
import dev.ostrom.server.identity.domain.TokenKind
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.instance.InstanceModule
import dev.ostrom.server.kernel.auth.ORG_HEADER
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.database.withOrganization
import dev.ostrom.server.kernel.http.OstromJson
import dev.ostrom.server.launches.LaunchesModule
import dev.ostrom.server.launches.presentation.LaunchResponse
import dev.ostrom.server.orgs.OrgsModule
import dev.ostrom.server.orgs.domain.Member
import dev.ostrom.server.orgs.domain.Org
import dev.ostrom.server.orgs.domain.OrgEvent
import dev.ostrom.server.orgs.domain.OrgsRepository
import dev.ostrom.server.receipts.ReceiptsModule
import dev.ostrom.server.runners.RunnersModule
import dev.ostrom.server.runners.domain.AgentKind
import dev.ostrom.server.runners.domain.CloudSession
import dev.ostrom.server.runners.domain.CloudSessionsRepository
import dev.ostrom.server.runners.domain.DesiredState
import dev.ostrom.server.runners.domain.RunnerTokens
import dev.ostrom.server.runners.domain.SessionLimits
import dev.ostrom.server.runners.domain.SessionState
import dev.ostrom.server.sessions.SessionsModule
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import dev.ostrom.server.workflowruns.domain.repositories.RunQuery
import dev.ostrom.server.workflowruns.domain.repositories.WorkflowRunsRepository
import dev.ostrom.server.workflowruns.infrastructure.RunQueries
import dev.ostrom.server.workflowruns.infrastructure.RunsGateCheckProofs
import dev.ostrom.server.workflowruns.presentation.BUNDLE_FORMAT
import dev.ostrom.server.workflowruns.presentation.ChecksPage
import dev.ostrom.server.workflowruns.presentation.NotificationsPage
import dev.ostrom.server.workflowruns.presentation.WorkflowReceiptResponse
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * `ostrom claude` after the launch, on the real modules and PostgreSQL: the hooks report a check and a notification
 * with the launch's agent token, the launcher signs the session's workflow receipt and pulls its bundle, the person
 * reads them, and nothing crosses a launch or an organization.
 */
class WorkflowRunsPostgresTest {
    private val modules = listOf(
        InstanceModule, IdentityModule, OrgsModule, ReceiptsModule, GatesModule, SessionsModule, CompilerModule,
        RunnersModule, LaunchesModule, WorkflowRunsModule,
    )
    private val now = Instant.fromEpochMilliseconds(Clock.System.now().toEpochMilliseconds())

    /**
     * A person of a new organization with its package: the CLI's token, a personal one reading receipts, and a
     * personal one reading every list these modules serve.
     */
    private class Person(
        val org: Org,
        val memberId: Uuid,
        val cliToken: String,
        val personalToken: String,
        val listReader: String,
    )

    private suspend fun TestServer.person(login: String): Person {
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        val org = Org(Uuid.random(), "$login-team", "$login team", null, null, now)
        val member = Member(Uuid.random(), org.id, user.id, OrgRole.OWNER, now)
        koin.get<OrgsRepository>().create(org, member) { false }
        suspend fun token(kind: TokenKind, scopes: Set<String>): String {
            val secret = kind.prefix + "_" + Uuid.random().toHexString()
            val token = ApiToken(
                Uuid.random(), user.id, org.id, "t", kind, scopes, "hint", null, now, now + 1.hours, null, null,
            )
            koin.get<ApiTokensRepository>().create(token, koin.get<SecretHasher>().hash(secret))
            return secret
        }
        val envelope = PackageEnvelope(
            "ostrom.package/1", "v1", "acme/api", DIGEST, "sha256:content", "Ed25519", "k", "sig", BODY, true, false,
        )
        koin.get<CompilerRepository>().savePackage(org.id, StoredPackage(envelope, now.toString(), emptyMap()))
        return Person(
            org,
            member.id,
            token(TokenKind.CLI, CLI_SCOPES),
            token(TokenKind.PERSONAL, READER_SCOPES),
            token(TokenKind.PERSONAL, LIST_SCOPES),
        )
    }

    private suspend fun TestServer.call(token: String?, path: String, body: String? = null): HttpResponse =
        if (body == null) {
            client.get(path) { token?.let { header(HttpHeaders.Authorization, "Bearer $it") } }
        } else {
            client.post(path) {
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    /** A launch and its session `s-1`; the agent token. */
    private suspend fun TestServer.session(person: Person): String {
        val launch = call(person.cliToken, "/api/v1/launches", LAUNCH).body<LaunchResponse>()
        val started = call(launch.agentToken, "/api/v1/sessions", SESSION)
        assertEquals(HttpStatusCode.OK, started.status, started.bodyAsText())
        return launch.agentToken
    }

    /** A cloud session of [person]'s, its execution 2 running, and the runner token of that execution. */
    private suspend fun TestServer.cloudSession(person: Person): Pair<CloudSession, String> {
        val tokens = koin.get<RunnerTokens>()
        val token = tokens.mint()
        val session = CloudSession(
            id = Uuid.random(), orgId = person.org.id, ownerId = person.memberId.toString(), name = "task",
            agent = AgentKind.CLAUDE, repository = null, state = SessionState.CREATING,
            desiredState = DesiredState.RUNNING, phase = null, epoch = 2, generation = 0, failure = null,
            limits = SessionLimits(IDLE_MINUTES, RUN_MINUTES, false), createdAt = now, updatedAt = now,
            executionStartedAt = now, runnerTokenHash = tokens.hash(token),
        )
        assertTrue(koin.get<CloudSessionsRepository>().createWithinCap(session, RUNNING_CAP))
        return session to token
    }

    /** The runner of [cloud] opens its launch, whose agent token opens the session `s-1`; the agent token. */
    private suspend fun TestServer.runnerSession(cloud: CloudSession, token: String): String {
        val opened = runner(token, "/api/v1/cloud/runner/launch", """{"sessionId":"${cloud.id}","epoch":2}""")
        assertEquals(HttpStatusCode.Created, opened.status, opened.bodyAsText())
        val agent = opened.body<LaunchResponse>().agentToken
        assertEquals(HttpStatusCode.OK, call(agent, "/api/v1/sessions", SESSION).status)
        return agent
    }

    private suspend fun TestServer.runner(
        token: String?,
        path: String,
        body: String,
        scheme: String = "Bearer ",
    ): HttpResponse =
        client.post(path) {
            token?.let { header(HttpHeaders.Authorization, scheme + it) }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun ranByRunner(cloud: CloudSession, epoch: Long = 2, check: String = CHECK) =
        """{"sessionId":"${cloud.id}","epoch":$epoch,"check":$check}"""

    @Test
    fun theCloudSessionsRunnerReportsItsChecksAndTheReceiptSaysOstromRanThem() =
        serverTest(modules) { server ->
            val ada = server.person("ada")
            val (cloud, token) = server.cloudSession(ada)
            val agent = server.runnerSession(cloud, token)

            val reported = server.runner(token, "/api/v1/cloud/runner/checks", ranByRunner(cloud))
            assertEquals(HttpStatusCode.Created, reported.status, reported.bodyAsText())
            val run = reported.body<CheckRun>()
            assertEquals("s-1" to "ada", run.sessionId to run.dev)
            val repository = server.koin.get<WorkflowRunsRepository>()
            assertEquals(
                mapOf(run.id to CheckAttestation.ISOLATED_RUNNER),
                repository.attestations(ada.org.id, "s-1"),
                "Recorded as run by Ostrom, which the receipt reads",
            )

            assertEquals(HttpStatusCode.OK, server.call(agent, "/api/v1/sessions/s-1/end", "{}").status)
            val signed = server.call(agent, "/api/v1/sessions/s-1/workflow-receipt", RECEIPT)
            assertEquals(HttpStatusCode.OK, signed.status, signed.bodyAsText())
            val receipt = signed.body<WorkflowReceiptResponse>()
            assertTrue(receipt.verification.ok, receipt.verification.reason)
            val check = receipt.receipt.getValue("nodes").jsonArray.first().jsonObject
            assertEquals("isolated_runner", check.getValue("attestation").jsonPrimitive.content)
            assertEquals(run.id, check.getValue("ref").jsonPrimitive.content)
        }

    @Test
    fun aScriptTheAgentRewroteRunsOnTheRunnerButTheReceiptNeitherSaysOstromRanItNorThatItPassed() =
        serverTest(modules) { server ->
            val ada = server.person("ada")
            val (cloud, token) = server.cloudSession(ada)
            val agent = server.runnerSession(cloud, token)
            // The hook asked the runner to run `exit 0` for the node: the runner hashed that text, not the package's.
            val rewritten = CHECK.replace("\"sha-1\"", "\"sha-of-exit-0\"")
            val reported = server.runner(token, "/api/v1/cloud/runner/checks", ranByRunner(cloud, check = rewritten))
            assertEquals(HttpStatusCode.Created, reported.status, reported.bodyAsText())
            assertEquals(true, reported.body<CheckRun>().tampered)
            assertEquals(emptyMap(), server.koin.get<WorkflowRunsRepository>().attestations(ada.org.id, "s-1"))

            assertEquals(HttpStatusCode.OK, server.call(agent, "/api/v1/sessions/s-1/end", "{}").status)
            val signed = server.call(agent, "/api/v1/sessions/s-1/workflow-receipt", RECEIPT)
            assertEquals(HttpStatusCode.OK, signed.status, signed.bodyAsText())
            val check = signed.body<WorkflowReceiptResponse>().receipt.getValue("nodes").jsonArray.first().jsonObject
            assertEquals("workstation_declared", check.getValue("attestation").jsonPrimitive.content)
            assertEquals("failed: the script run is not the package's", check.getValue("outcome").jsonPrimitive.content)
        }

    @Test
    fun aGateReadsTheProofOfACheckInItsOwnOrganizationOnly() =
        serverTest(modules) { server ->
            val proofs = assertIs<RunsGateCheckProofs>(server.koin.get<GateCheckProofs>())
            val subject = GateSubject(
                repo = "ostrom-hq/gate-demo",
                actionClass = "push-main",
                command = "git push origin main",
                scope = "session:s-1",
            )
            val ada = server.person("ada")
            val (cloud, token) = server.cloudSession(ada)
            server.runnerSession(cloud, token)
            val ran = server.runner(token, "/api/v1/cloud/runner/checks", ranByRunner(cloud))
            assertEquals(HttpStatusCode.Created, ran.status, ran.bodyAsText())
            val attested = GateCheckProof(CheckAttestation.ISOLATED_RUNNER, passed = true)
            assertEquals(attested, proofs.of(ada.org.id, "s-1", subject, "n-check"))

            // Bob's own session s-1 only declared its run: Ada's runner run is not in Bob's organization.
            val bob = server.person("bob")
            assertEquals(HttpStatusCode.Created, server.call(server.session(bob), "/api/v1/checks", CHECK).status)
            val declared = GateCheckProof(CheckAttestation.WORKSTATION_DECLARED, passed = true)
            assertEquals(declared, proofs.of(bob.org.id, "s-1", subject, "n-check"))
            val none = GateCheckProof(attestation = null, passed = false)
            assertEquals(none, proofs.of(Uuid.random(), "s-1", subject, "n-check"), "An organization with no run")
            assertEquals(none, proofs.of(ada.org.id, "s-1", subject, "n-other"), "A check that never ran")
        }

    @Test
    fun anAgentNeverReportsUnderThePackageOfAnotherRepository() =
        serverTest(modules) { server ->
            val ada = server.person("ada")
            val agent = server.session(ada)
            // A package of the same organization, for another repository, whose node `n-check` is easier to pass.
            val docs = PackageEnvelope(
                "ostrom.package/1", "v1", "acme/docs", "sha256:docs", "sha256:content-docs", "Ed25519", "k", "sig",
                BODY, true, false,
            )
            val stored = StoredPackage(docs, now.toString(), emptyMap())
            server.koin.get<CompilerRepository>().savePackage(ada.org.id, stored)
            val other = server.call(agent, "/api/v1/checks", CHECK.replace(DIGEST, "sha256:docs"))
            assertEquals(HttpStatusCode.Conflict, other.status, other.bodyAsText())
            val own = server.call(agent, "/api/v1/checks", CHECK)
            assertEquals(HttpStatusCode.Created, own.status, "Its own repository: ${own.bodyAsText()}")
        }

    @Test
    fun anAgentReportsUnderItsRepositorysPinnedOrLatestPackageOnly() =
        serverTest(modules) { server ->
            val ada = server.person("ada")
            val agent = server.session(ada)
            val compiler = server.koin.get<CompilerRepository>()
            suspend fun publish(digest: String, at: Instant, repo: String = "acme/api", revoked: Boolean = false) {
                val envelope = PackageEnvelope(
                    "ostrom.package/1", "v1", repo, digest, "content-$digest", "Ed25519", "k", "sig", BODY, false,
                    revoked,
                )
                compiler.savePackage(ada.org.id, StoredPackage(envelope, at.toString(), emptyMap()))
            }
            suspend fun report(digest: String): HttpStatusCode =
                server.call(agent, "/api/v1/checks", CHECK.replace(DIGEST, digest)).status
            // Published while the session runs, revoked, or another repository's: none replaces the session's package.
            publish("sha256:later", now + 1.hours)
            publish("sha256:revoked", now + 1.milliseconds, revoked = true)
            publish("sha256:docs-newer", now + 1.milliseconds, repo = "acme/docs")
            assertEquals(HttpStatusCode.Created, report(DIGEST))
            assertEquals(HttpStatusCode.Created, report("sha256:later"), "The latest one")
            // A newer package of the repository, compiled before the session started: the older one no longer passes.
            publish("sha256:newer", now + 1.milliseconds)
            assertEquals(HttpStatusCode.Conflict, report(DIGEST), "An older version of the repository's package")
            assertEquals(HttpStatusCode.Created, report("sha256:newer"))
            // Unless an admin pinned it: the pinned package is the one its sessions receive.
            compiler.setPin(ada.org.id, "acme/api", PackagePin("sha256:content", "ada", now))
            assertEquals(HttpStatusCode.Created, report(DIGEST), "The pinned version")
        }

    @Test
    fun theRunnerChannelTakesOnlyTheRunnerTokenOfTheSessionsOwnCloudSession() =
        serverTest(modules) { server ->
            val ada = server.person("ada")
            val bob = server.person("bob")
            val (cloud, token) = server.cloudSession(ada)
            server.runnerSession(cloud, token)
            val path = "/api/v1/cloud/runner/checks"
            assertEquals(HttpStatusCode.Unauthorized, server.runner(null, path, ranByRunner(cloud)).status)
            val bare = server.runner(token, path, ranByRunner(cloud), scheme = "")
            assertEquals(HttpStatusCode.Unauthorized, bare.status, "Not Bearer")
            assertEquals(HttpStatusCode.Unauthorized, server.runner("", path, ranByRunner(cloud)).status)
            val member = server.runner(ada.cliToken, path, ranByRunner(cloud))
            assertEquals(HttpStatusCode.Unauthorized, member.status, "A member's token is not a runner token")
            // S2-46: an unknown token never makes the server read the body.
            assertEquals(HttpStatusCode.Unauthorized, server.runner("ostrom_runner_x", path, "{not json").status)
            assertEquals(HttpStatusCode.BadRequest, server.runner(token, path, "{not json").status)
            val malformed = ranByRunner(cloud).replace(cloud.id.toString(), "nope")
            assertEquals(HttpStatusCode.BadRequest, server.runner(token, path, malformed).status)
            assertEquals(HttpStatusCode.Conflict, server.runner(token, path, ranByRunner(cloud, epoch = 1)).status)

            // A session of a launch of the CLI is not the runner's, even in its own organization.
            val cli = server.call(ada.cliToken, "/api/v1/launches", LAUNCH).body<LaunchResponse>()
            val other = SESSION.replace("\"s-1\"", "\"s-2\"")
            assertEquals(HttpStatusCode.OK, server.call(cli.agentToken, "/api/v1/sessions", other).status)
            val cliCheck = CHECK.replace("\"s-1\"", "\"s-2\"")
            val notMine = server.runner(token, path, ranByRunner(cloud, check = cliCheck))
            assertEquals(HttpStatusCode.NotFound, notMine.status)

            // Bob's runner reports in Bob's organization only: Ada's session is absent there.
            val (bobs, bobsToken) = server.cloudSession(bob)
            assertEquals(HttpStatusCode.NotFound, server.runner(bobsToken, path, ranByRunner(bobs)).status)
            assertEquals(emptyMap(), server.koin.get<WorkflowRunsRepository>().attestations(ada.org.id, "s-1"))
        }

    @Test
    fun theHooksReportTheLauncherSignsAndPullsTheBundleAndThePersonReadsThem() =
        serverTest(modules) { server ->
            val ada = server.person("ada")
            val agent = server.session(ada)

            // The workstation cannot attest its own run: an `attestation` it sends is not read (E2E-ATT-002).
            val claimed = CHECK.replace("\"sandbox\"", "\"attestation\":\"ci\",\"sandbox\"")
            val reported = server.call(agent, "/api/v1/checks", claimed)
            assertEquals(HttpStatusCode.Created, reported.status, reported.bodyAsText())
            val run = reported.body<CheckRun>()
            assertEquals(listOf("ada", "v1", "acme/api"), listOf(run.dev, run.version, run.repo))
            assertEquals(false, run.tampered)

            val notified = server.call(agent, "/api/v1/notifications", NOTIFY)
            assertEquals(HttpStatusCode.Accepted, notified.status, notified.bodyAsText())
            val notification = notified.body<Notification>()
            assertEquals(NotificationStatus.SENT, notification.status)
            assertEquals("ada pushed git push in acme/api", notification.text)
            val again = server.call(agent, "/api/v1/notifications", NOTIFY).body<Notification>()
            assertEquals(notification.id, again.id, "A hook passing twice on the same action gets the first one")

            val early = server.call(agent, "/api/v1/sessions/s-1/workflow-receipt", RECEIPT)
            assertEquals(HttpStatusCode.Conflict, early.status, "Signed once the session has ended")
            assertEquals(HttpStatusCode.OK, server.call(agent, "/api/v1/sessions/s-1/end", "{}").status)
            val late = server.call(agent, "/api/v1/checks", CHECK)
            assertEquals(HttpStatusCode.Conflict, late.status, "An ended session takes nothing more")
            val signed = server.call(agent, "/api/v1/sessions/s-1/workflow-receipt", RECEIPT)
            assertEquals(HttpStatusCode.OK, signed.status, signed.bodyAsText())
            val receipt = signed.body<WorkflowReceiptResponse>()
            assertTrue(receipt.verification.ok, receipt.verification.reason)
            val id = receipt.receipt.getValue("id").jsonPrimitive.content
            assertEquals("rcpt_workflow_s-1", id)
            val resigned = server.call(agent, "/api/v1/sessions/s-1/workflow-receipt", RECEIPT)
            assertEquals(receipt.receipt, resigned.body<WorkflowReceiptResponse>().receipt, "Signed once")
            val outcomes = receipt.receipt.getValue("nodes").jsonArray.map {
                it.jsonObject.getValue("outcome").jsonPrimitive.content
            }
            assertEquals(listOf("passed", "sent to log:team"), outcomes)
            val attested = receipt.receipt.getValue("nodes").jsonArray.map {
                it.jsonObject["attestation"]?.jsonPrimitive?.content
            }
            assertEquals(listOf("workstation_declared", null), attested, "Signed with the node: a declared check")

            val bundle = server.call(agent, "/api/v1/receipts/$id/bundle")
            assertEquals(HttpStatusCode.OK, bundle.status, bundle.bodyAsText())
            val gathered = bundle.body<JsonObject>()
            assertEquals(BUNDLE_FORMAT, gathered.getValue("format").jsonPrimitive.content)
            assertEquals(receipt.receipt, gathered.getValue("receipt"))
            val chain = gathered.getValue("chain").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
            assertEquals(listOf("rcpt_session_s-1"), chain)
            assertEquals(DIGEST, gathered.getValue("package").jsonObject.getValue("digest").jsonPrimitive.content)
            assertTrue(gathered.getValue("keys").jsonArray.isNotEmpty())

            val byPerson = server.call(ada.personalToken, "/api/v1/receipts/rcpt_session_s-1/bundle")
                .body<JsonObject>()
            assertEquals(JsonNull, byPerson["package"], "A session receipt cites no package; the key is still there")
            val checks = server.call(ada.cliToken, "/api/v1/checks?sessionId=s-1").body<ChecksPage>()
            assertEquals(listOf(run.id), checks.items.map { it.id })
            assertNull(checks.next)
            val sent = server.call(agent, "/api/v1/notifications?sessionId=s-1&status=sent").body<NotificationsPage>()
            assertEquals(listOf(notification.id), sent.items.map { it.id })
            val none = server.call(ada.cliToken, "/api/v1/notifications?status=not-sent").body<NotificationsPage>()
            assertTrue(none.items.isEmpty())
        }

    @Test
    fun eachRouteRefusesWithoutACredentialAndAnswers404ForWhatItDoesNotHave() =
        serverTest(modules) { server ->
            val routes = listOf(
                "/api/v1/checks" to CHECK,
                "/api/v1/checks" to null,
                "/api/v1/notifications" to NOTIFY,
                "/api/v1/notifications" to null,
                "/api/v1/sessions/s-1/workflow-receipt" to RECEIPT,
                "/api/v1/receipts/rcpt_session_s-1/bundle" to null,
            )
            routes.forEach { (path, body) ->
                assertEquals(HttpStatusCode.Unauthorized, server.call(null, path, body).status, path)
            }
            val ada = server.person("ada")
            val agent = server.session(ada)
            val absent = listOf(
                Triple(agent, "/api/v1/checks", CHECK.replace("\"s-1\"", "\"s-9\"")),
                Triple(agent, "/api/v1/checks", CHECK.replace(DIGEST, "sha256:other")),
                Triple(agent, "/api/v1/checks", CHECK.replace("sc-1", "sc-9")),
                Triple(ada.cliToken, "/api/v1/checks?sessionId=s-9", null),
                Triple(agent, "/api/v1/notifications", NOTIFY.replace("nt-1", "nt-9")),
                Triple(ada.cliToken, "/api/v1/notifications?sessionId=s-9", null),
                Triple(agent, "/api/v1/sessions/s-9/workflow-receipt", RECEIPT),
                Triple(ada.personalToken, "/api/v1/receipts/rcpt_absent/bundle", null),
            )
            absent.forEach { (token, path, body) ->
                val response = server.call(token, path, body)
                assertEquals(HttpStatusCode.NotFound, response.status, "$path ${response.bodyAsText()}")
            }
            val badStatus = server.call(ada.cliToken, "/api/v1/notifications?status=lost")
            assertEquals(HttpStatusCode.BadRequest, badStatus.status)
            // The CLI's own token reads its organization's receipts (`ostrom receipts`, `verify`, #83): one not signed
            // yet is absent, as for anyone.
            val cliBundle = server.call(ada.cliToken, "/api/v1/receipts/rcpt_session_s-1/bundle")
            assertEquals(HttpStatusCode.NotFound, cliBundle.status, cliBundle.bodyAsText())
            // A credential of no organization: its person left it.
            val deleted = OrgEvent(ada.org.id, Uuid.random(), "org.deleted", "org", ada.org.id.toString())
            server.koin.get<OrgsRepository>().delete(ada.org.id, now, deleted)
            assertEquals(HttpStatusCode.NotFound, server.call(ada.cliToken, "/api/v1/checks").status)
            assertEquals(
                HttpStatusCode.NotFound,
                server.call(ada.personalToken, "/api/v1/receipts/rcpt_session_s-1/bundle").status,
            )
        }

    @Test
    fun aRouteNamingAnOrganizationTheCallerIsNotInAnswersNotFound() =
        serverTest(modules) { server ->
            // The contract's 404, never a 403 that would tell the organization exists: gates, sessions, the compiler
            // and the workflow runs, on the real resolver, with Ada's token naming Bob's organization.
            val ada = server.person("ada")
            val bob = server.person("bob")
            val paths = listOf(
                "/api/v1/approvals", "/api/v1/sessions", "/api/v1/workflows", "/api/v1/checks",
                "/api/v1/receipts/rcpt_session_s-1/bundle",
            )
            for (path in paths) {
                val foreign = server.client.get(path) {
                    header(HttpHeaders.Authorization, "Bearer ${ada.listReader}")
                    header(ORG_HEADER, bob.org.id.toString())
                }
                assertEquals(HttpStatusCode.NotFound, foreign.status, path)
                assertTrue("Organization not found." in foreign.bodyAsText(), path)
            }
            for (path in paths.dropLast(1)) {
                val own = server.client.get(path) {
                    header(HttpHeaders.Authorization, "Bearer ${ada.listReader}")
                    header(ORG_HEADER, ada.org.id.toString())
                }
                assertEquals(HttpStatusCode.OK, own.status, "$path ${own.bodyAsText()}")
            }
        }

    @Test
    fun anotherLaunchOrOrganizationNeitherReportsNorReads() =
        serverTest(modules) { server ->
            val ada = server.person("ada")
            val adas = server.session(ada)
            assertEquals(HttpStatusCode.Created, server.call(adas, "/api/v1/checks", CHECK).status)
            assertEquals(HttpStatusCode.Accepted, server.call(adas, "/api/v1/notifications", NOTIFY).status)
            assertEquals(HttpStatusCode.OK, server.call(adas, "/api/v1/sessions/s-1/end", "{}").status)

            // Another launch of Ada's: the session is not its own.
            val other = server.call(ada.cliToken, "/api/v1/launches", LAUNCH).body<LaunchResponse>().agentToken
            assertEquals(HttpStatusCode.Forbidden, server.call(other, "/api/v1/checks", CHECK).status)
            assertEquals(HttpStatusCode.NotFound, server.call(other, "/api/v1/checks?sessionId=s-1").status)
            val otherBundle = server.call(other, "/api/v1/receipts/rcpt_session_s-1/bundle")
            assertEquals(HttpStatusCode.NotFound, otherBundle.status)

            // Bob's organization: Ada's session, receipts and runs do not exist.
            val bob = server.person("bob")
            val bobs = server.session(bob)
            val crossed = server.call(bobs, "/api/v1/checks", CHECK)
            assertEquals(HttpStatusCode.Created, crossed.status, "Bob's own session s-1, in Bob's organization")
            val bobsChecks = server.call(bob.cliToken, "/api/v1/checks").body<ChecksPage>()
            assertEquals(listOf(crossed.body<CheckRun>().id), bobsChecks.items.map { it.id })
            assertTrue(server.call(bob.cliToken, "/api/v1/notifications").body<NotificationsPage>().items.isEmpty())
            val adasBundle = server.call(bob.personalToken, "/api/v1/receipts/rcpt_session_s-1/bundle")
            assertEquals(HttpStatusCode.NotFound, adasBundle.status, "Ada's session receipt is not in Bob's org")

            // The database itself: without its organization set, a transaction reads no run and changes none. The test
            // server's driver is the serving role, as in production: row level security applies to it.
            val seen = server.driver.transaction {
                listOf("workflow_runs_checks", "workflow_runs_notifications").map { table ->
                    val count = fetchAll("SELECT COUNT(*) AS n FROM $table").getOrThrow().first().get("n").asString()
                    count to execute("UPDATE $table SET session_id = 'x'").getOrThrow()
                }
            }
            assertEquals(listOf("0" to 0L, "0" to 0L), seen)
        }

    @Test
    fun aCheckThatDidNotRunAnswersItsExitAsNull() =
        serverTest(modules) { server ->
            val ada = server.person("ada")
            val agent = server.session(ada)
            val notRun = CHECK.replace("\"exit\":0,\"outcome\":\"passed\"", "\"outcome\":\"failed\"")
            val reported = server.call(agent, "/api/v1/checks", notRun)
            assertEquals(HttpStatusCode.Created, reported.status, reported.bodyAsText())
            assertEquals(JsonNull, reported.body<JsonObject>()["exit"], "Required and nullable in the contract")
            val page = server.call(ada.cliToken, "/api/v1/checks?sessionId=s-1").body<JsonObject>()
            assertEquals(JsonNull, page.getValue("items").jsonArray.single().jsonObject["exit"])
        }

    @Test
    fun aPageEndsWithACursorAndTwoPassesAtOnceKeepTheFirstNotification() =
        serverTest(modules) { server ->
            val ada = server.person("ada")
            val agent = server.session(ada)
            val ids = (1..PAGE + 1).map { server.call(agent, "/api/v1/checks", CHECK).body<CheckRun>().id }
            val first = server.call(ada.cliToken, "/api/v1/checks?sessionId=s-1&nodeId=n-check").body<ChecksPage>()
            assertEquals(PAGE, first.items.size)
            assertEquals(first.items.last().id, first.next)
            val rest = server.call(ada.cliToken, "/api/v1/checks?before=${first.next}").body<ChecksPage>()
            assertEquals((first.items + rest.items).map { it.id }.toSet(), ids.toSet())
            assertNull(rest.next)

            // Two passes of a hook racing past the first read: the database keeps one, both get it back.
            val repository = server.koin.get<WorkflowRunsRepository>()
            val stored = notification("ntf_a")
            assertEquals(stored, repository.addNotification(ada.org.id, stored, "key"))
            assertEquals(stored, repository.addNotification(ada.org.id, notification("ntf_b"), "key"))
            val outside = notification("ntf_c", sessionId = null)
            assertEquals(outside, repository.addNotification(ada.org.id, outside, "key"))
            val twice = repository.addNotification(ada.org.id, outside.copy(id = "ntf_d"), "key")
            assertEquals("ntf_d", twice.id, "Outside a session, nothing is deduplicated")
            val page = repository.notifications(ada.org.id, RunQuery(status = NotificationStatus.SENT))
            assertEquals(setOf("ntf_a", "ntf_c", "ntf_d"), page.map { it.id }.toSet())
            val older = repository.notifications(ada.org.id, RunQuery(before = "ntf_d"))
            assertEquals(listOf("ntf_c", "ntf_a"), older.map { it.id }, "Older than the cursor, newest first")
            // Held by key, in the organization only: what refuses a cursor no page gave.
            assertTrue(repository.hasNotification(ada.org.id, "ntf_a"))
            assertTrue(repository.hasCheck(ada.org.id, ids.first()))
            val other = Uuid.random()
            assertFalse(repository.hasNotification(other, "ntf_a"))
            assertFalse(repository.hasCheck(other, ids.first()))
            for (list in listOf("checks", "notifications")) {
                val refused = server.call(ada.cliToken, "/api/v1/$list?before=nope")
                assertEquals(HttpStatusCode.BadRequest, refused.status, list)
            }
        }

    @Test
    fun theRequestPathQueriesUseTheirIndexes() =
        serverTest(modules) { server ->
            val ada = server.person("ada")
            val agent = server.session(ada)
            repeat(ROWS) {
                server.call(agent, "/api/v1/checks", CHECK)
                server.call(agent, "/api/v1/notifications", NOTIFY.replace("git push", "git push $it"))
            }
            // Statistics only: the serving role owns no table, the owner analyzes them.
            server.database.owner.execute("ANALYZE workflow_runs_checks").getOrThrow()
            server.database.owner.execute("ANALYZE workflow_runs_notifications").getOrThrow()
            val plans = listOf(
                Triple("checks of a session", RunQueries.checks(RunQuery(sessionId = "s-1")), "session"),
                Triple("checks of a node", RunQueries.checks(RunQuery(nodeId = "n-check")), "workflow_runs_checks_"),
                Triple("checks of the organization", RunQueries.checks(RunQuery()), "at"),
                Triple("checks before a cursor", RunQueries.checks(RunQuery(before = "chk_x")), "pkey"),
                Triple("notifications of a session", RunQueries.notifications(RunQuery(sessionId = "s-1")), "session"),
                Triple("notifications of the organization", RunQueries.notifications(RunQuery()), "at"),
                Triple(
                    "notifications by status",
                    RunQueries.notifications(RunQuery(status = NotificationStatus.SENT)),
                    "workflow_runs_notifications_",
                ),
                Triple("an action's first notification", RunQueries.FIRST_NOTIFICATION, "once"),
                Triple("the attested runs of a session", RunQueries.ATTESTED, "session"),
            )
            plans.forEach { (name, sql, index) ->
                val plan = server.driver.withOrganization(ada.org.id) { tx ->
                    tx.execute("SET LOCAL enable_seqscan = off").getOrThrow()
                    val explain = Statement.create("EXPLAIN (ANALYZE, BUFFERS) $sql").bind("org_id", ada.org.id)
                        .bindIfPresent(sql, "limit", PAGE).bindIfPresent(sql, "session_id", "s-1")
                        .bindIfPresent(sql, "before", "chk_x").bindIfPresent(sql, "notify_id", "nt-1")
                        .bindIfPresent(sql, "action_key", "k").bindIfPresent(sql, "node_id", "n-check")
                        .bindIfPresent(sql, "status", "sent")
                    tx.fetchAll(explain).getOrThrow().joinToString("\n") { it.get("QUERY PLAN").asString() }
                }
                println("-- $name\n$plan")
                assertTrue("Index" in plan && index in plan, "$name\n$plan")
            }
        }

    private fun Statement.bindIfPresent(sql: String, name: String, value: Any): Statement =
        if (":$name" in sql) bind(name, value) else this

    private fun notification(id: String, sessionId: String? = "s-1") = Notification(
        id = id,
        at = now.toString(),
        sessionId = sessionId,
        digest = DIGEST,
        nodeId = "n-notify",
        notifyId = "nt-1",
        channel = "log:team",
        mode = PackageMode.ENFORCE,
        status = NotificationStatus.SENT,
        text = "t",
        dev = "ada",
    )

    private companion object {
        const val PAGE = 50
        const val ROWS = 30
        const val IDLE_MINUTES = 30
        const val RUN_MINUTES = 120
        const val RUNNING_CAP = 5
        const val DIGEST = "sha256:runs"
        val READER_SCOPES = setOf("receipts:read", "sessions:read")
        val LIST_SCOPES = READER_SCOPES + setOf("gates:read", "workflows:read")
        const val LAUNCH = """{"repo":"acme/api","agent":"claude-code"}"""
        const val SESSION = """{"id":"s-1","agent":"claude-code","repo":"acme/api"}"""
        const val CHECK = """{"sessionId":"s-1","digest":"sha256:runs","nodeId":"n-check","scriptId":"sc-1",""" +
            """"scriptSha":"sha-1","exit":0,"outcome":"passed","sandbox":"bwrap"}"""
        const val NOTIFY =
            """{"sessionId":"s-1","digest":"sha256:runs","nodeId":"n-notify","notifyId":"nt-1","command":"git push"}"""
        const val RECEIPT = """{"digest":"sha256:runs","agent":"claude-code"}"""
        val BODY: String = OstromJson.encodeToString(
            JsonObject.serializer(),
            OstromJson.parseToJsonElement(
                """{"nodes":[
                {"id":"n-check","workflowId":"wf1","workflowName":"Ship","kind":"check","label":"Tests"},
                {"id":"n-notify","workflowId":"wf1","workflowName":"Ship","kind":"notify","label":"Tell"}],
                "scripts":[{"id":"sc-1","nodeId":"n-check","sha256":"sha-1","mode":"enforce"}],
                "notifications":[{"id":"nt-1","nodeId":"n-notify","channel":"log:team",
                "message":"{dev} pushed {command} in {repo}","mode":"enforce"}]}""",
            ).jsonObject,
        )
    }
}
