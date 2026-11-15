// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners

import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.github.smyrgeorge.sqlx4k.Statement
import org.koin.dsl.module
import run.ostrom.core.api.Idempotency
import run.ostrom.core.api.OnBehalfOf
import run.ostrom.server.kernel.audit.OrgAuditRecord
import run.ostrom.server.kernel.audit.OrgAuditTrail
import run.ostrom.server.kernel.audit.TransactionalOrgAuditTrail
import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.kernel.auth.MemberLogins
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.runners.fakes.ALICE
import run.ostrom.server.runners.fakes.BOB
import run.ostrom.server.runners.fakes.CAROL
import run.ostrom.server.runners.fakes.DANA
import run.ostrom.server.runners.fakes.ORG_A
import run.ostrom.server.runners.fakes.testIdentities
import run.ostrom.server.runners.infrastructure.inTenant
import run.ostrom.server.runners.presentation.CloudSessionResponse
import run.ostrom.server.runners.presentation.CloudSessionsResponse
import run.ostrom.server.runners.presentation.CreateCloudSessionRequest
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Against a real PostgreSQL: `Ostrom-On-Behalf-Of` on the cloud session writes (`runners.cloud.onbehalf.v1`, TeamCode's
 * `TeamCode-On-Behalf-Of`), and on the model and effort writes (`runners.cloud.onbehalf.writes.v1`). Alice and bob are
 * members of org A, dana its admin; carol is org B's owner.
 */
class CloudSessionsOnBehalfPostgresTest {
    private val audit = mutableListOf<OrgAuditRecord>()

    /** Makes the audit entries written in a session write's transaction fail, as a broken audit log does. */
    private var journalFails = false
    private val overrides = module {
        includes(testIdentities)
        single<OrgAuditTrail> { OrgAuditTrail { audit += it } }
        single<TransactionalOrgAuditTrail> {
            TransactionalOrgAuditTrail { _, record ->
                check(!journalFails) { "The audit log is unavailable." }
                audit += record
            }
        }
        single<MemberDirectory> {
            object : MemberDirectory {
                private val roles = mapOf(ALICE to OrgRole.MEMBER, BOB to OrgRole.MEMBER, DANA to OrgRole.ADMIN)

                override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean =
                    orgId == ORG_A && Uuid.parseOrNull(memberId) in roles

                override suspend fun roleOf(orgId: Uuid, memberId: String): OrgRole? =
                    roles[Uuid.parseOrNull(memberId)]?.takeIf { orgId == ORG_A }
            }
        }
        single<MemberLogins> {
            object : MemberLogins {
                private val logins = mapOf("alice" to ALICE, "bob" to BOB, "dana" to DANA)

                override suspend fun idsOf(orgId: Uuid, logins: Collection<String>): Map<String, Uuid> =
                    this.logins.filterKeys { it in logins && orgId == ORG_A }

                override suspend fun loginsOf(orgId: Uuid, memberIds: Collection<Uuid>): Map<Uuid, String> =
                    logins.entries.filter { it.value in memberIds }.associate { it.value to it.key }
            }
        }
    }

    private fun onBehalfTest(block: suspend (TestServer) -> Unit) =
        serverTest(listOf(RunnersModule), overrides) { server -> block(server) }

    private suspend fun TestServer.create(
        token: String,
        vararg onBehalfOf: String,
        key: String? = null,
        request: CreateCloudSessionRequest = CreateCloudSessionRequest(agent = "codex", name = "Fix it"),
    ): HttpResponse = client.post("/api/v1/cloud/sessions") {
        bearerAuth(token)
        onBehalfOf.forEach { header(OnBehalfOf.HEADER, it) }
        key?.let { header(Idempotency.KEY_HEADER, it) }
        contentType(ContentType.Application.Json)
        setBody(request)
    }

    private suspend fun TestServer.write(method: String, token: String, id: String, onBehalfOf: String?) =
        when (method) {
            "pause", "resume" -> client.post("/api/v1/cloud/sessions/$id/$method") {
                bearerAuth(token)
                onBehalfOf?.let { header(OnBehalfOf.HEADER, it) }
            }
            else -> client.delete("/api/v1/cloud/sessions/$id") {
                bearerAuth(token)
                onBehalfOf?.let { header(OnBehalfOf.HEADER, it) }
            }
        }

    @Test
    fun aMalformedOrRepeatedHeaderIsRefused() = onBehalfTest { server ->
        for (values in listOf(arrayOf("bob", "bob"), arrayOf("a b"), arrayOf("x".repeat(OnBehalfOf.MAX_LENGTH + 1)))) {
            val refused = server.create("dana", *values)
            assertEquals(HttpStatusCode.BadRequest, refused.status, values.joinToString())
            assertTrue(OnBehalfOf.INVALID in refused.bodyAsText())
        }
        val created = server.create("alice").body<CloudSessionResponse>()
        val pause = server.write("pause", "alice", created.id, "a b")
        assertEquals(HttpStatusCode.BadRequest, pause.status)
        assertTrue(audit.none { it.event == "cloud.session.paused" })
    }

    @Test
    fun aMemberActsOnlyForThemselvesAndAnAdministratorForAnActiveMember() = onBehalfTest { server ->
        val forbidden = server.create("alice", "bob")
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        assertTrue(OnBehalfOf.FORBIDDEN in forbidden.bodyAsText())
        // Her own login names her.
        assertEquals(ALICE.toString(), server.create("alice", "alice").body<CloudSessionResponse>().owner)
        // A login nobody has, and another organization's owner, are no members to act for.
        for (target in listOf("ghost", CAROL.toString())) {
            val invalid = server.create("dana", target)
            assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status, target)
            assertTrue(OnBehalfOf.INVALID_MEMBER in invalid.bodyAsText())
        }
    }

    @Test
    fun anAdministratorCreatesAMembersSessionWhichRecordsWhoCreatedIt() = onBehalfTest { server ->
        val created = server.create("dana", "bob", key = "press-1")
        assertEquals(HttpStatusCode.Created, created.status)
        val session = created.body<CloudSessionResponse>()
        assertEquals(BOB.toString(), session.owner)
        assertEquals("none", session.githubAccess)
        assertEquals(DANA.toString(), session.createdBy?.initiatorId)
        assertEquals(BOB.toString(), session.createdBy?.effectiveMemberId)
        // Read back from the row by its owner.
        val read = server.client.get("/api/v1/cloud/sessions/${session.id}") { bearerAuth("bob") }
        assertEquals(session.createdBy, read.body<CloudSessionResponse>().createdBy)
        // A row missing the effective member answers no attribution rather than half of one.
        server.driver.inTenant(ORG_A) {
            execute(
                Statement.create("UPDATE runners_sessions SET created_by_effective_member_id = NULL WHERE id = :id")
                    .bind("id", Uuid.parse(session.id)),
            ).getOrThrow()
        }
        val partial = server.client.get("/api/v1/cloud/sessions/${session.id}") { bearerAuth("bob") }
        assertNull(partial.body<CloudSessionResponse>().createdBy)
        // The key is dana's: replayed for her, bob's own key of that name is his.
        val replay = server.create("dana", "bob", key = "press-1")
        assertEquals("true", replay.headers[Idempotency.REPLAYED_HEADER])
        val conflict = server.create("dana", "alice", key = "press-1")
        assertEquals(HttpStatusCode.UnprocessableEntity, conflict.status, "the digest covers the effective member")
        assertEquals(HttpStatusCode.Created, server.create("bob", key = "press-1").status)

        val created1 = audit.filter { it.event == "cloud.session.created" }
        assertEquals(2, created1.size, "a replay or a refusal is not journaled")
        assertEquals("dana", created1.first().actor.subjectId)
        assertEquals(BOB.toString(), created1.first().details["effective_member_id"])
        // A profile or an environment that requires GitHub is the owner's to create.
        val github = CreateCloudSessionRequest(
            agent = "codex",
            environment = run.ostrom.server.runners.presentation.CloudEnvironmentDto(requireGitHub = true),
        )
        val consent = server.create("dana", "bob", request = github)
        assertEquals(HttpStatusCode.UnprocessableEntity, consent.status)
        assertTrue(OnBehalfOf.GITHUB_OWNER_CONSENT in consent.bodyAsText())
    }

    @Test
    fun aRemoteSessionIsNeverCreatedOnBehalfOfAnotherMember() = onBehalfTest { server ->
        val remote = CreateCloudSessionRequest(agent = "claude", kind = "remote", directoryName = "ostrom")
        val refused = server.create("dana", "bob", request = remote)
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertTrue(OnBehalfOf.FORBIDDEN in refused.bodyAsText())
    }

    @Test
    fun theHeaderAttributesAWriteButOpensNoOtherMembersSession() = onBehalfTest { server ->
        // TeamCode's visible(): a role, an administrator's included, opens nobody else's session, header or not.
        val bobs = server.create("bob").body<CloudSessionResponse>().id
        assertEquals(HttpStatusCode.Forbidden, server.write("pause", "alice", bobs, "bob").status)
        for (method in listOf("pause", "resume", "delete")) {
            assertEquals(HttpStatusCode.NotFound, server.write(method, "dana", bobs, "bob").status, method)
        }
        // On the caller's own session the header only names the member journaled as the effective one.
        val id = server.create("dana").body<CloudSessionResponse>().id
        for (method in listOf("pause", "resume", "delete")) {
            assertEquals(HttpStatusCode.Accepted, server.write(method, "dana", id, "bob").status, method)
        }
        val lines = audit.filter { it.subjectId == id && it.event != "cloud.session.created" }
        assertEquals(
            listOf("cloud.session.paused", "cloud.session.resumed", "cloud.session.deleted"),
            lines.map { it.event },
        )
        assertTrue(
            lines.all {
                it.actor.subjectId == "dana" && it.details["effective_member_id"] == BOB.toString() &&
                    it.details["owner"] == DANA.toString()
            },
        )
    }

    @Test
    fun gitHubAccessAndTheBypassAreTheOwnersAlone() = onBehalfTest { server ->
        val id = server.create("bob").body<CloudSessionResponse>().id
        val github = server.client.put("/api/v1/cloud/sessions/$id/github") {
            bearerAuth("dana")
            header(OnBehalfOf.HEADER, "bob")
            contentType(ContentType.Application.Json)
            setBody("""{"access":"shared"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, github.status)
        assertTrue("github_owner_only" in github.bodyAsText())
        val bypass = server.client.put("/api/v1/cloud/sessions/$id/permission-bypass") {
            bearerAuth("dana")
            header(OnBehalfOf.HEADER, "bob")
            contentType(ContentType.Application.Json)
            setBody("""{"enabled":true}""")
        }
        assertEquals(HttpStatusCode.Forbidden, bypass.status)
        assertTrue("bypass_owner_only" in bypass.bodyAsText())
        // Naming themselves, the owner goes on as without the header.
        val own = server.client.put("/api/v1/cloud/sessions/$id/github") {
            bearerAuth("bob")
            header(OnBehalfOf.HEADER, "bob")
            contentType(ContentType.Application.Json)
            setBody("""{"access":"shared"}""")
        }
        assertEquals(HttpStatusCode.OK, own.status)
        assertNull(audit.firstOrNull { it.event.startsWith("cloud.session.permission_bypass") })
    }

    @Test
    fun aSessionWriteWhoseJournalCannotBeWrittenDoesNotHappen() = onBehalfTest { server ->
        val id = server.create("dana-cli").body<CloudSessionResponse>().id
        journalFails = true
        assertEquals(HttpStatusCode.InternalServerError, server.create("dana-cli", "bob").status)
        assertEquals(HttpStatusCode.InternalServerError, server.write("pause", "dana-cli", id, "bob").status)
        journalFails = false
        val bobs = server.client.get("/api/v1/cloud/sessions") { bearerAuth("bob") }.body<CloudSessionsResponse>()
        assertEquals(emptyList(), bobs.items.map { it.id }, "the creation was undone")
        val listed = server.client.get("/api/v1/cloud/sessions") { bearerAuth("dana") }.body<CloudSessionsResponse>()
        assertEquals(listOf(id), listed.items.map { it.id })
        assertEquals("running", listed.items.single().desiredStatus, "the pause was undone")

        assertEquals(HttpStatusCode.Accepted, server.write("pause", "dana-cli", id, "bob").status)
        journalFails = true
        assertEquals(HttpStatusCode.InternalServerError, server.write("resume", "dana-cli", id, "bob").status)
        assertEquals(HttpStatusCode.InternalServerError, server.write("delete", "dana-cli", id, "bob").status)
        journalFails = false
        val read = server.client.get("/api/v1/cloud/sessions/$id") { bearerAuth("dana") }.body<CloudSessionResponse>()
        assertEquals("paused", read.desiredStatus, "neither the resume nor the deletion happened")
        val paused = audit.single { it.event == "cloud.session.paused" }
        assertEquals("dana", paused.actor.subjectId)
        assertEquals(
            mapOf("effective_member_id" to BOB.toString(), "credential_id" to "pat-dana", "owner" to DANA.toString()),
            paused.details,
        )
    }

    @Test
    fun theModelAndTheEffortAreSetOnTheCallersOwnSessionAttributedToTheMemberNamed() = onBehalfTest { server ->
        val bobs = server.create("bob").body<CloudSessionResponse>().id
        val id = server.create("dana-cli").body<CloudSessionResponse>().id
        suspend fun option(kind: String, token: String, onBehalfOf: String, value: String = "opus", on: String = id) =
            server.client.put("/api/v1/cloud/sessions/$on/$kind") {
                bearerAuth(token)
                header(OnBehalfOf.HEADER, onBehalfOf)
                contentType(ContentType.Application.Json)
                setBody("""{"$kind":"$value"}""")
            }

        val forbidden = option("model", "alice", "bob")
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        assertTrue(OnBehalfOf.FORBIDDEN in forbidden.bodyAsText())
        val invalid = option("effort", "dana-cli", "ghost", "high")
        assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)
        assertTrue(OnBehalfOf.INVALID_MEMBER in invalid.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, option("model", "dana-cli", "bob", on = bobs).status, "bob's own")
        assertEquals("opus", option("model", "dana-cli", "bob").body<CloudSessionResponse>().model)
        assertEquals("high", option("effort", "dana-cli", BOB.toString(), "high").body<CloudSessionResponse>().effort)

        val lines = audit.filter { it.event.endsWith("_changed") }
        assertEquals(listOf("cloud.session.model_changed", "cloud.session.effort_changed"), lines.map { it.event })
        val details = mapOf(
            "effective_member_id" to BOB.toString(),
            "credential_id" to "pat-dana",
            "owner" to DANA.toString(),
        )
        assertTrue(lines.all { it.actor.subjectId == "dana" && it.details == details })
    }

    @Test
    fun theListingIsTheCallersOwnWhateverTheirRole() = onBehalfTest { server ->
        val id = server.create("bob").body<CloudSessionResponse>().id
        suspend fun list(token: String, vararg owners: String) = server.client.get("/api/v1/cloud/sessions") {
            bearerAuth(token)
            owners.forEach { parameter("owner", it) }
        }

        assertEquals(listOf(id), list("bob", BOB.toString()).body<CloudSessionsResponse>().items.map { it.id })
        for ((token, owner) in listOf("bob" to ALICE.toString(), "dana" to BOB.toString(), "dana" to "all")) {
            val refused = list(token, owner)
            assertEquals(HttpStatusCode.Forbidden, refused.status, "$token $owner")
            assertTrue("Only your own sessions are listed." in refused.bodyAsText())
        }
        val twice = list("bob", BOB.toString(), BOB.toString())
        assertEquals(HttpStatusCode.BadRequest, twice.status)
        assertTrue("invalid_query" in twice.bodyAsText())
    }
}
