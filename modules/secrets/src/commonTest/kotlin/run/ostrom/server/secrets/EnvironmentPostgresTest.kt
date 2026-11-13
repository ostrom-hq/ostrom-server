// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.secrets

import run.ostrom.server.kernel.auth.MemberDirectory
import run.ostrom.server.secrets.domain.AssignmentSource
import run.ostrom.server.secrets.domain.CreateVariantOutcome
import run.ostrom.server.secrets.domain.EnvironmentBinding
import run.ostrom.server.secrets.domain.EnvironmentRepository
import run.ostrom.server.secrets.domain.EnvironmentVariant
import run.ostrom.server.secrets.domain.EnvironmentVariantNotFoundException
import run.ostrom.server.secrets.domain.ResolveEnvironmentUseCase
import run.ostrom.server.secrets.domain.StoredEnvironmentVariant
import run.ostrom.server.secrets.domain.UpdateVariantOutcome
import run.ostrom.server.secrets.domain.VariantChange
import run.ostrom.server.secrets.domain.VariantLimitException
import run.ostrom.server.secrets.infrastructure.executeInOrg
import run.ostrom.server.secrets.infrastructure.fetchInOrg
import run.ostrom.server.secrets.presentation.EnvironmentAssignRequest
import run.ostrom.server.secrets.presentation.EnvironmentAssignmentResponse
import run.ostrom.server.secrets.presentation.EnvironmentAssignmentsPageResponse
import run.ostrom.server.secrets.presentation.EnvironmentBulkAssignmentResponse
import run.ostrom.server.secrets.presentation.EnvironmentValuesResponse
import run.ostrom.server.secrets.presentation.EnvironmentVariantCreateRequest
import run.ostrom.server.secrets.presentation.EnvironmentVariantResponse
import run.ostrom.server.secrets.presentation.EnvironmentVariantUpdateRequest
import run.ostrom.server.secrets.presentation.EnvironmentVariantsPageResponse
import run.ostrom.server.testing.TestServer
import run.ostrom.server.testing.serverTest
import io.github.smyrgeorge.sqlx4k.Statement
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Environment variants on real PostgreSQL and real HTTP: encryption at rest, assignments, bulk, exclusions, personal
 * variants, the caller's own values, who may call what, and another organization.
 */
class EnvironmentPostgresTest {
    private val variants = "/api/v1/environment/variants"
    private val assignments = "/api/v1/environment/assignments"
    private val exclusions = "/api/v1/environment/exclusions"
    private val mine = "/api/v1/me/environment/variants"
    private val adminA = TestCallers.adminA.memberId
    private val memberA = TestCallers.memberA.memberId
    private val member2A = TestCallers.member2A.memberId

    /** The members of each organization: the test callers, so a bulk assignment reaches them. */
    private var active = mutableMapOf(
        TestCallers.orgA to mutableListOf(adminA, memberA, member2A),
        TestCallers.orgB to mutableListOf(TestCallers.adminB.memberId),
    )

    private val overrides = module {
        includes(TestCallers.overrides)
        single<MemberDirectory> {
            object : MemberDirectory {
                override suspend fun isActiveMember(orgId: Uuid, memberId: String): Boolean =
                    active[orgId].orEmpty().any { it.toString() == memberId }

                override suspend fun memberIds(orgId: Uuid, limit: Int): List<String>? =
                    active[orgId]?.map { it.toString() }
            }
        }
    }

    private fun test(block: suspend (TestServer) -> Unit) =
        serverTest(listOf(SecretsModule), overrides) { server -> block(server) }

    @Test
    fun anAdminDeclaresASharedValueEncryptedAtRestAndOnlyTheAssignedMemberReceivesIt() = test { server ->
        val created = server.send("POST", variants, "admin-a", create("GITHUB_TOKEN", "org-a", value = "ghp_org_a"))
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        assertFalse("ghp_org_a" in created.bodyAsText(), "The value never comes back")
        val variant = created.body<EnvironmentVariantResponse>()
        assertEquals("shared", variant.binding)
        assertTrue(variant.hasValue)
        assertEquals(1, variant.version)
        assertNull(variant.owner)
        val stored = server.driver.fetchInOrg(
            TestCallers.orgA,
            Statement.create("SELECT envelope FROM environment_variants"),
        ).getOrThrow().single().get("envelope").asString()
        assertFalse("ghp_org_a" in stored, "Encrypted at rest")

        assertEquals(emptyMap(), server.values("member-a").variables, "A shared variant is never an implicit grant")
        val assigned = server.send(
            "PUT",
            "$assignments/$memberA/GITHUB_TOKEN",
            "admin-a",
            EnvironmentAssignRequest(variant.id),
        )
        assertEquals(HttpStatusCode.OK, assigned.status, assigned.bodyAsText())
        val assignment = assigned.body<EnvironmentAssignmentResponse>()
        assertEquals("direct", assignment.source)
        assertFalse(assignment.hasMemberValue)

        val own = server.client.get("/api/v1/environment") { bearerAuth("member-a") }
        assertEquals("no-store", own.headers[HttpHeaders.CacheControl])
        assertEquals(mapOf("GITHUB_TOKEN" to "ghp_org_a"), own.body<EnvironmentValuesResponse>().variables)
        assertEquals(emptyMap(), server.values("member2-a").variables, "Another member gets nothing")
        assertEquals(mapOf("GITHUB_TOKEN" to "ghp_org_a"), server.values("cli-env-a").variables, "The CLI's token")

        val listed = server.page(variants, "admin-a")
        assertEquals(listOf("org-a"), listed.items.map { it.name })
        val page = server.client.get("$assignments?member=$memberA") {
            bearerAuth("admin-a")
        }.body<EnvironmentAssignmentsPageResponse>()
        assertEquals(listOf("GITHUB_TOKEN"), page.items.map { it.variable })
        assertEquals(emptyList(), page.exclusions)
    }

    @Test
    fun aPerMemberVariantCarriesEachMembersOwnValueOnTheAssignment() = test { server ->
        val refused = server.send("POST", variants, "admin-a", create("NPM_TOKEN", "own", "perMember", "npm_x"))
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertTrue("invalid_environment_variant" in refused.bodyAsText())
        val variant = server.send("POST", variants, "admin-a", create("NPM_TOKEN", "own", "perMember"))
            .body<EnvironmentVariantResponse>()
        assertFalse(variant.hasValue)

        val slot = "$assignments/$memberA/NPM_TOKEN"
        assertEquals(
            HttpStatusCode.BadRequest,
            server.send("PUT", slot, "admin-a", EnvironmentAssignRequest(variant.id)).status,
            "The member's value is required",
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            server.assign("$memberA/OTHER", variant.id, "v").status,
            "The variant fills another variable",
        )
        val assigned = server.send("PUT", slot, "admin-a", EnvironmentAssignRequest(variant.id, "npm_member_a"))
        assertTrue(assigned.body<EnvironmentAssignmentResponse>().hasMemberValue)
        server.assign("$member2A/NPM_TOKEN", variant.id, "npm_b")

        assertEquals(mapOf("NPM_TOKEN" to "npm_member_a"), server.values("member-a").variables)
        assertEquals(mapOf("NPM_TOKEN" to "npm_b"), server.values("member2-a").variables)

        val shared = server.send("POST", variants, "admin-a", create("API_URL", "prod", value = "https://x"))
            .body<EnvironmentVariantResponse>()
        val withValue = server.send(
            "PUT",
            "$assignments/$memberA/API_URL",
            "admin-a",
            EnvironmentAssignRequest(shared.id, "mine"),
        )
        assertEquals(HttpStatusCode.BadRequest, withValue.status, "A shared variant refuses a member value")
    }

    @Test
    fun aBulkAssignmentKeepsDirectSelectionsAndSkipsExcludedMembers() = test { server ->
        val orgA = server.send("POST", variants, "admin-a", create("GH", "org-a", value = "token-a"))
            .body<EnvironmentVariantResponse>()
        val orgB = server.send("POST", variants, "admin-a", create("GH", "org-b", value = "token-b"))
            .body<EnvironmentVariantResponse>()
        server.assign("$adminA/GH", orgA.id)
        assertEquals(HttpStatusCode.NoContent, server.send("PUT", "$exclusions/$member2A/GH", "admin-a").status)

        val bulk = server.send("POST", "$variants/${orgB.id}/bulk-assign", "admin-a")
        assertEquals(HttpStatusCode.OK, bulk.status, bulk.bodyAsText())
        assertEquals(EnvironmentBulkAssignmentResponse(1, 1, 1), bulk.body<EnvironmentBulkAssignmentResponse>())
        assertEquals(mapOf("GH" to "token-b"), server.values("member-a").variables)
        assertEquals(mapOf("GH" to "token-a"), server.values("admin-a").variables, "Direct beats bulk")
        assertEquals(emptyMap(), server.values("member2-a").variables, "Excluded")
        val again = server.send("POST", "$variants/${orgB.id}/bulk-assign", "admin-a")
        assertEquals(EnvironmentBulkAssignmentResponse(1, 1, 1), again.body<EnvironmentBulkAssignmentResponse>())
        val page = server.client.get(assignments) { bearerAuth("admin-a") }.body<EnvironmentAssignmentsPageResponse>()
        assertEquals(listOf("direct", "bulk"), page.items.sortedBy { it.member }.map { it.source })
        assertEquals(listOf(member2A.toString()), page.exclusions.map { it.member })

        val perMember = server.send("POST", variants, "admin-a", create("NPM", "own", "perMember"))
            .body<EnvironmentVariantResponse>()
        val refused = server.send("POST", "$variants/${perMember.id}/bulk-assign", "admin-a")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertTrue("variant_not_shared" in refused.bodyAsText())

        active[TestCallers.orgA] = mutableListOf()
        val nobody = server.send("POST", "$variants/${orgB.id}/bulk-assign", "admin-a")
        assertEquals(EnvironmentBulkAssignmentResponse(0, 0, 0), nobody.body<EnvironmentBulkAssignmentResponse>())
        assertEquals(emptyMap(), server.values("member-a").variables, "A member who left gets none")
    }

    @Test
    fun anExclusionRemovesTheSelectionAndRefusesAssignmentsUntilItIsRemoved() = test { server ->
        val variant = server.send("POST", variants, "admin-a", create("TOKEN", "t", value = "value-1"))
            .body<EnvironmentVariantResponse>()
        val slot = "$assignments/$memberA/TOKEN"
        server.send("PUT", slot, "admin-a", EnvironmentAssignRequest(variant.id))
        server.send("PUT", "$exclusions/$memberA/TOKEN", "admin-a")
        server.send("PUT", "$exclusions/$memberA/TOKEN", "admin-a")
        assertEquals(emptyMap(), server.values("member-a").variables)
        val refused = server.send("PUT", slot, "admin-a", EnvironmentAssignRequest(variant.id))
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertTrue("variant_excluded" in refused.bodyAsText())

        assertEquals(HttpStatusCode.NoContent, server.send("DELETE", "$exclusions/$memberA/TOKEN", "admin-a").status)
        assertEquals(HttpStatusCode.NoContent, server.send("DELETE", "$exclusions/$memberA/TOKEN", "admin-a").status)
        assertEquals(emptyMap(), server.values("member-a").variables, "Removing an exclusion grants nothing back")
        assertEquals(HttpStatusCode.OK, server.assign("$memberA/TOKEN", variant.id).status)
        assertEquals(HttpStatusCode.NoContent, server.send("DELETE", slot, "admin-a").status)
        assertEquals(HttpStatusCode.NoContent, server.send("DELETE", slot, "admin-a").status, "Also when none")
        assertEquals(emptyMap(), server.values("member-a").variables)

        assertEquals(HttpStatusCode.BadRequest, server.send("PUT", "$exclusions/$memberA/path", "admin-a").status)
        assertEquals(HttpStatusCode.BadRequest, server.send("DELETE", "$exclusions/$memberA/PATH", "admin-a").status)
        assertEquals(HttpStatusCode.BadRequest, server.send("DELETE", "$assignments/$memberA/LD_X", "admin-a").status)
        assertEquals(
            HttpStatusCode.NotFound,
            server.send("PUT", "$exclusions/${Uuid.random()}/TOKEN", "admin-a").status,
            "An unknown member",
        )
    }

    @Test
    fun anUpdateNeedsTheVersionReadAndReachesEveryAssigneeAtTheirNextRead() = test { server ->
        val variant = server.send("POST", variants, "admin-a", create("KEY", "Prod", value = "value-1"))
            .body<EnvironmentVariantResponse>()
        server.send("POST", variants, "admin-a", create("KEY", "staging", value = "value-2"))
        val duplicate = server.send("POST", variants, "admin-a", create("KEY", "prod", value = "value-3"))
        assertEquals(HttpStatusCode.Conflict, duplicate.status, "Names are unique per variable, case-insensitively")
        assertTrue("variant_exists" in duplicate.bodyAsText())
        server.assign("$memberA/KEY", variant.id)

        val stale = server.send("PATCH", "$variants/${variant.id}", "admin-a", EnvironmentVariantUpdateRequest(7, "x"))
        assertEquals(HttpStatusCode.Conflict, stale.status)
        assertTrue("variant_version_conflict" in stale.bodyAsText())
        val path = "$variants/${variant.id}"
        val taken = server.send("PATCH", path, "admin-a", EnvironmentVariantUpdateRequest(1, "STAGING"))
        assertTrue("variant_exists" in taken.bodyAsText(), taken.bodyAsText())
        val updated = server.send(
            "PATCH",
            "$variants/${variant.id}",
            "admin-a",
            EnvironmentVariantUpdateRequest(1, value = "value-rotated"),
        ).body<EnvironmentVariantResponse>()
        assertEquals(2, updated.version)
        assertEquals("Prod", updated.name)
        assertEquals(mapOf("KEY" to "value-rotated"), server.values("member-a").variables)
        val renamed = server.send("PATCH", path, "admin-a", EnvironmentVariantUpdateRequest(2, "live"))
            .body<EnvironmentVariantResponse>()
        assertEquals("live" to 3, renamed.name to renamed.version)
        assertEquals(mapOf("KEY" to "value-rotated"), server.values("member-a").variables, "A rename keeps the value")

        val perMember = server.send("POST", variants, "admin-a", create("NPM", "own", "perMember"))
            .body<EnvironmentVariantResponse>()
        val onPerMember = server.send(
            "PATCH",
            "$variants/${perMember.id}",
            "admin-a",
            EnvironmentVariantUpdateRequest(1, value = "value-x"),
        )
        assertEquals(HttpStatusCode.BadRequest, onPerMember.status)

        assertEquals(HttpStatusCode.NoContent, server.send("DELETE", "$variants/${variant.id}", "admin-a").status)
        assertEquals(emptyMap(), server.values("member-a").variables, "Deleting a variant removes its assignments")
        assertEquals(HttpStatusCode.NotFound, server.send("DELETE", "$variants/${variant.id}", "admin-a").status)
        assertEquals(
            HttpStatusCode.NotFound,
            server.send("PATCH", "$variants/${variant.id}", "admin-a", EnvironmentVariantUpdateRequest(3)).status,
        )
        assertEquals(HttpStatusCode.NotFound, server.send("DELETE", "$variants/not-an-id", "admin-a").status)
    }

    @Test
    fun aMemberDeclaresPersonalValuesAssignedToThemAlone() = test { server ->
        val created = server.send("POST", mine, "member-a", create("OPENROUTER_API_KEY", "mine", value = "or-key-1"))
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val personal = created.body<EnvironmentVariantResponse>()
        assertEquals(memberA.toString(), personal.owner)
        assertEquals(mapOf("OPENROUTER_API_KEY" to "or-key-1"), server.values("member-a").variables)
        assertEquals(emptyMap(), server.values("member2-a").variables)
        val listed = server.page(mine, "member-a")
        assertEquals(listOf(personal.id), listed.items.map { it.id })
        assertEquals(emptyList(), server.page(mine, "member2-a").items)

        val again = server.send("POST", mine, "member-a", create("OPENROUTER_API_KEY", "other", value = "or-key-2"))
        assertTrue("slot_taken" in again.bodyAsText(), again.bodyAsText())
        val perMember = server.send("POST", mine, "member-a", create("X_KEY", "own", "perMember"))
        assertEquals(HttpStatusCode.BadRequest, perMember.status)
        server.send("PUT", "$exclusions/$memberA/BLOCKED", "admin-a")
        val excluded = server.send("POST", mine, "member-a", create("BLOCKED", "mine", value = "value-1"))
        assertTrue("variant_excluded" in excluded.bodyAsText(), excluded.bodyAsText())

        // An administrator reviews it, cannot edit or assign it, may promote it.
        val review = server.page("$variants/personal", "admin-a")
        assertEquals(listOf(memberA.toString()), review.items.map { it.owner })
        val edit = server.send("PATCH", "$variants/${personal.id}", "admin-a", EnvironmentVariantUpdateRequest(1, "x"))
        assertTrue("variant_personal" in edit.bodyAsText(), edit.bodyAsText())
        val assign = server.send(
            "PUT",
            "$assignments/$member2A/OPENROUTER_API_KEY",
            "admin-a",
            EnvironmentAssignRequest(personal.id),
        )
        assertTrue("variant_personal" in assign.bodyAsText())
        assertTrue(
            "variant_personal" in server.send("POST", "$variants/${personal.id}/bulk-assign", "admin-a").bodyAsText(),
        )

        // Its owner renames and rotates it; another member does not see it.
        val rotate = EnvironmentVariantUpdateRequest(1, value = "or-key-3")
        val updated = server.send("PATCH", "$mine/${personal.id}", "member-a", rotate)
        assertEquals(2, updated.body<EnvironmentVariantResponse>().version)
        assertEquals(mapOf("OPENROUTER_API_KEY" to "or-key-3"), server.values("member-a").variables)
        assertEquals(
            HttpStatusCode.NotFound,
            server.send("PATCH", "$mine/${personal.id}", "member2-a", EnvironmentVariantUpdateRequest(2, "x")).status,
        )
        assertEquals(HttpStatusCode.NotFound, server.send("DELETE", "$mine/${personal.id}", "member2-a").status)

        // A team variant of the same name blocks the promotion; once renamed, it becomes a team variant.
        server.send("POST", variants, "admin-a", create("OPENROUTER_API_KEY", "mine", value = "team-key-1"))
        val blocked = server.send("POST", "$variants/${personal.id}/promote", "admin-a")
        assertTrue("variant_exists" in blocked.bodyAsText(), blocked.bodyAsText())
        server.send("PATCH", "$mine/${personal.id}", "member-a", EnvironmentVariantUpdateRequest(2, "reviewed"))
        val promote = "$variants/${personal.id}/promote"
        val promoted = server.send("POST", promote, "admin-a").body<EnvironmentVariantResponse>()
        assertNull(promoted.owner)
        val kept = server.values("member-a").variables
        assertEquals(mapOf("OPENROUTER_API_KEY" to "or-key-3"), kept, "Its owner keeps it")
        val twice = server.send("POST", promote, "admin-a").body<EnvironmentVariantResponse>()
        assertEquals(promoted, twice, "A team variant stays as it is")
        assertEquals(
            HttpStatusCode.NotFound,
            server.send("POST", "$variants/${Uuid.random()}/promote", "admin-a").status,
        )
        assertEquals(HttpStatusCode.NotFound, server.send("DELETE", "$mine/${personal.id}", "member-a").status)
    }

    @Test
    fun aTeamAssignmentReplacesAMembersPersonalSelection() = test { server ->
        val second = server.send("POST", mine, "member2-a", create("LOCAL_TOKEN", "mine", value = "local-1"))
            .body<EnvironmentVariantResponse>()
        val team = server.send("POST", variants, "admin-a", create("LOCAL_TOKEN", "team", value = "team-local"))
            .body<EnvironmentVariantResponse>()
        server.assign("$member2A/LOCAL_TOKEN", team.id)
        assertEquals(mapOf("LOCAL_TOKEN" to "team-local"), server.values("member2-a").variables)
        // Unassigned, the slot is free again, and the member's own variant still holds its name.
        server.send("DELETE", "$assignments/$member2A/LOCAL_TOKEN", "admin-a")
        val again = server.send("POST", mine, "member2-a", create("LOCAL_TOKEN", "MINE", value = "local-2"))
        assertTrue("variant_exists" in again.bodyAsText(), again.bodyAsText())
        val rename = EnvironmentVariantUpdateRequest(1, "x")
        val unknown = server.send("PATCH", "$mine/${Uuid.random()}", "member2-a", rename)
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertEquals(HttpStatusCode.NoContent, server.send("DELETE", "$mine/${second.id}", "member2-a").status)
        assertEquals(HttpStatusCode.NoContent, server.send("DELETE", "$variants/${team.id}", "admin-a").status)
    }

    @Test
    fun aMemberKeepsAtMostTwentyPersonalVariantsAndATeamAtMostItsLimit() = test { server ->
        repeat(20) { index ->
            val created = server.send("POST", mine, "member-a", create("KEY_$index", "mine", value = "value-1"))
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        }
        val over = server.send("POST", mine, "member-a", create("KEY_20", "mine", value = "value-1"))
        assertTrue("variant_limit" in over.bodyAsText(), over.bodyAsText())

        val repository = server.koin.get<EnvironmentRepository>()
        val now = Instant.parse("2026-11-13T00:00:00Z")
        fun team(name: String) = StoredEnvironmentVariant(
            EnvironmentVariant(
                Uuid.random(), TestCallers.orgA, "TEAM", name, EnvironmentBinding.SHARED, null, true, 1, now, now,
            ),
            "sealed",
        )
        assertEquals(CreateVariantOutcome.CREATED, repository.createTeam(team("a"), limit = 1))
        assertEquals(CreateVariantOutcome.LIMIT, repository.createTeam(team("b"), limit = 1))
        val personal = server.page(mine, "member-a").items.first()
        assertFailsWith<VariantLimitException>("A promotion counts too") {
            repository.promote(TestCallers.orgA, Uuid.parse(personal.id), now, limit = 1)
        }
        assertNull(repository.promote(TestCallers.orgA, Uuid.random(), now, limit = 1), "Gone meanwhile")
        val change = VariantChange(1, "x", null, now)
        assertEquals(UpdateVariantOutcome.NotFound, repository.update(TestCallers.orgA, Uuid.random(), null, change))
        assertFailsWith<IllegalArgumentException>("A personal variant has an owner") {
            repository.createPersonal(team("ownerless"), limit = 1)
        }
        // A variant deleted between the use case's read and the write: a 404, not a broken foreign key.
        val gone = team("gone").variant
        assertFailsWith<EnvironmentVariantNotFoundException> {
            repository.assign(TestCallers.orgA, memberA, gone, AssignmentSource.DIRECT, null, now)
        }
        assertFailsWith<EnvironmentVariantNotFoundException> {
            repository.bulkAssign(TestCallers.orgA, gone, listOf(memberA), now)
        }
        val tooMany = server.send("POST", variants, "admin-a", create("TEAM", "a", value = "value-1"))
        assertTrue("variant_exists" in tooMany.bodyAsText(), "The name is checked under the organization's limit")
    }

    @Test
    fun aValueThatNoLongerOpensIsWithheldByNameOnly() = test { server ->
        val good = server.send("POST", variants, "admin-a", create("GOOD", "g", value = "value-1"))
            .body<EnvironmentVariantResponse>()
        val bad = server.send("POST", variants, "admin-a", create("BAD", "b", value = "value-2"))
            .body<EnvironmentVariantResponse>()
        val odd = server.send("POST", variants, "admin-a", create("ODD", "o", value = "value-3"))
            .body<EnvironmentVariantResponse>()
        val empty = server.send("POST", variants, "admin-a", create("EMPTY", "e", value = "value-4"))
            .body<EnvironmentVariantResponse>()
        for (variant in listOf(good, bad, odd, empty)) {
            server.assign("$memberA/${variant.variable}", variant.id)
        }
        server.driver.executeInOrg(
            TestCallers.orgA,
            Statement.create("UPDATE environment_variants SET envelope = 'v1:moved' WHERE variable = 'BAD'"),
        ).getOrThrow()
        server.driver.executeInOrg(
            TestCallers.orgA,
            Statement.create("UPDATE environment_variants SET binding = 'future' WHERE variable = 'ODD'"),
        ).getOrThrow()
        server.driver.executeInOrg(
            TestCallers.orgA,
            Statement.create("UPDATE environment_variants SET envelope = NULL WHERE variable = 'EMPTY'"),
        ).getOrThrow()

        val values = server.values("member-a")
        assertEquals(mapOf("GOOD" to "value-1"), values.variables)
        assertEquals(listOf("BAD", "EMPTY", "ODD"), values.unavailable, "Nor a binding this version cannot read")
        val listed = server.page(variants, "admin-a")
        assertEquals("perMember", listed.items.single { it.variable == "ODD" }.binding)
        val resolve = server.koin.get<ResolveEnvironmentUseCase>()
        assertEquals(emptyMap(), resolve(TestCallers.orgA, Uuid.random()).values, "Nobody's")
    }

    @Test
    fun malformedVariantsAreRefused() = test { server ->
        val cases = listOf(
            create("PATH", "x", value = "value-1"),
            create("OSTROM_TOKEN", "x", value = "value-1"),
            create("lowercase", "x", value = "value-1"),
            create("GOOD", " ", value = "value-1"),
            create("GOOD", "x".repeat(121), value = "value-1"),
            create("GOOD", "x"),
            create("GOOD", "x", value = ""),
            create("GOOD", "x", value = "a\u0000b"),
            create("GOOD", "x", "sometimes", "value-1"),
        )
        for (case in cases) {
            val refused = server.send("POST", variants, "admin-a", case)
            assertEquals(HttpStatusCode.BadRequest, refused.status, case.toString())
            assertTrue("invalid_environment_variant" in refused.bodyAsText(), refused.bodyAsText())
        }
        val variant = server.send("POST", variants, "admin-a", create("GOOD", "x", value = "value-1"))
            .body<EnvironmentVariantResponse>()
        val blank = server.send("PATCH", "$variants/${variant.id}", "admin-a", EnvironmentVariantUpdateRequest(1, ""))
        assertEquals(HttpStatusCode.BadRequest, blank.status)
        assertEquals(
            HttpStatusCode.NotFound,
            server.assign("not-a-member/GOOD", variant.id).status,
        )
        assertEquals(
            HttpStatusCode.NotFound,
            server.assign("$memberA/GOOD", "${Uuid.random()}").status,
        )
    }

    @Test
    fun everyRouteChecksItsCaller() = test { server ->
        val variant = server.send("POST", variants, "admin-a", create("TOKEN", "t", value = "value-1"))
            .body<EnvironmentVariantResponse>()
        val writes = listOf(
            Triple("POST", variants, create("OTHER", "o", value = "value-1")),
            Triple("PATCH", "$variants/${variant.id}", EnvironmentVariantUpdateRequest(1, "x")),
            Triple("DELETE", "$variants/${variant.id}", null),
            Triple("POST", "$variants/${variant.id}/bulk-assign", null),
            Triple("POST", "$variants/${variant.id}/promote", null),
            Triple("PUT", "$assignments/$memberA/TOKEN", EnvironmentAssignRequest(variant.id)),
            Triple("DELETE", "$assignments/$memberA/TOKEN", null),
            Triple("PUT", "$exclusions/$memberA/TOKEN", null),
            Triple("DELETE", "$exclusions/$memberA/TOKEN", null),
        )
        for ((method, path, body) in writes) {
            assertEquals(HttpStatusCode.Unauthorized, server.send(method, path, null, body).status, "$method $path")
            assertEquals(HttpStatusCode.Forbidden, server.send(method, path, "member-a", body).status, "$method $path")
            val stale = server.send(method, path, "stale-admin-a", body)
            assertTrue("step_up_required" in stale.bodyAsText(), "$method $path")
            assertEquals(HttpStatusCode.Forbidden, server.send(method, path, "service-a", body).status, "$method $path")
            assertEquals(HttpStatusCode.NotFound, server.send(method, path, "stranger", body).status, "$method $path")
        }
        for (path in listOf(variants, "$variants/personal", assignments)) {
            assertEquals(HttpStatusCode.Unauthorized, server.client.get(path).status)
            assertEquals(HttpStatusCode.Forbidden, server.client.get(path) { bearerAuth("member-a") }.status, path)
            assertEquals(HttpStatusCode.Forbidden, server.client.get(path) { bearerAuth("agent-a") }.status, path)
        }
        for (token in listOf("service-a", "agent-a", "device-a", "cli-a")) {
            assertEquals(
                HttpStatusCode.Forbidden,
                server.client.get("/api/v1/environment") { bearerAuth(token) }.status,
                token,
            )
        }
        assertEquals(HttpStatusCode.Unauthorized, server.client.get("/api/v1/environment").status)
        assertEquals(HttpStatusCode.Unauthorized, server.client.get(mine).status)
        assertEquals(HttpStatusCode.Forbidden, server.client.get(mine) { bearerAuth("service-a") }.status)
        val personal = create("MINE", "m", value = "value-1")
        assertEquals(HttpStatusCode.Forbidden, server.send("POST", mine, "service-a", personal).status)
        assertTrue("step_up_required" in server.send("POST", mine, "stale-admin-a", personal).bodyAsText())
    }

    @Test
    fun anotherOrganizationNeitherSeesNorChangesTheVariants() = test { server ->
        val variant = server.send("POST", variants, "admin-a", create("TOKEN", "t", value = "value-a"))
            .body<EnvironmentVariantResponse>()
        server.assign("$memberA/TOKEN", variant.id)
        val personal = server.send("POST", mine, "member-a", create("MINE", "m", value = "value-m"))
            .body<EnvironmentVariantResponse>()

        assertEquals(emptyList(), server.page(variants, "admin-b").items)
        assertEquals(
            emptyList(),
            server.page("$variants/personal", "admin-b").items,
        )
        val page = server.client.get(assignments) { bearerAuth("admin-b") }.body<EnvironmentAssignmentsPageResponse>()
        assertEquals(emptyList(), page.items)
        for (id in listOf(variant.id, personal.id)) {
            assertEquals(
                HttpStatusCode.NotFound,
                server.send("PATCH", "$variants/$id", "admin-b", EnvironmentVariantUpdateRequest(1, "stolen")).status,
            )
            assertEquals(HttpStatusCode.NotFound, server.send("DELETE", "$variants/$id", "admin-b").status)
            assertEquals(HttpStatusCode.NotFound, server.send("POST", "$variants/$id/bulk-assign", "admin-b").status)
            assertEquals(HttpStatusCode.NotFound, server.send("POST", "$variants/$id/promote", "admin-b").status)
        }
        val stolen = server.send(
            "PUT",
            "$assignments/${TestCallers.adminB.memberId}/TOKEN",
            "admin-b",
            EnvironmentAssignRequest(variant.id),
        )
        assertEquals(HttpStatusCode.NotFound, stolen.status)
        assertEquals(HttpStatusCode.NotFound, server.send("PUT", "$exclusions/$memberA/TOKEN", "admin-b").status)
        assertEquals(emptyMap(), server.values("admin-b").variables)
        assertEquals(mapOf("MINE" to "value-m", "TOKEN" to "value-a"), server.values("member-a").variables)
    }

    @Test
    fun withoutAMemberDirectoryNobodyReceivesAValue() =
        serverTest(listOf(SecretsModule), TestCallers.overrides) { server ->
            val variant = server.send("POST", variants, "admin-a", create("TOKEN", "t", value = "value-a"))
                .body<EnvironmentVariantResponse>()
            val assign = server.assign("$memberA/TOKEN", variant.id)
            assertEquals(HttpStatusCode.NotFound, assign.status)
            assertEquals(emptyMap(), server.values("member-a").variables)
        }

    private fun create(variable: String, name: String, binding: String? = null, value: String? = null) =
        EnvironmentVariantCreateRequest(variable, name, binding, value)

    private suspend fun TestServer.page(path: String, token: String): EnvironmentVariantsPageResponse =
        client.get(path) { bearerAuth(token) }.body()

    private suspend fun TestServer.assign(slot: String, variantId: String, value: String? = null): HttpResponse =
        send("PUT", "$assignments/$slot", "admin-a", EnvironmentAssignRequest(variantId, value))

    private suspend fun TestServer.values(token: String): EnvironmentValuesResponse =
        client.get("/api/v1/environment") { bearerAuth(token) }.body()

    private suspend fun TestServer.send(method: String, path: String, token: String?, body: Any? = null): HttpResponse {
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            if (token != null) bearerAuth(token)
            contentType(ContentType.Application.Json)
            when (body) {
                null -> setBody("{}")
                is EnvironmentVariantCreateRequest -> setBody(body)
                is EnvironmentVariantUpdateRequest -> setBody(body)
                is EnvironmentAssignRequest -> setBody(body)
                else -> error("Unknown body $body")
            }
        }
        return when (method) {
            "POST" -> client.post(path, build)
            "PUT" -> client.put(path, build)
            "PATCH" -> client.patch(path, build)
            else -> client.delete(path, build)
        }
    }
}
