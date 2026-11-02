// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.orgs

import dev.ostrom.server.identity.IdentityModule
import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.StartWebSessionUseCase
import dev.ostrom.server.identity.domain.StartedSession
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.kernel.auth.CSRF_COOKIE
import dev.ostrom.server.kernel.auth.CSRF_HEADER
import dev.ostrom.server.kernel.auth.SESSION_COOKIE
import dev.ostrom.server.orgs.presentation.CreateOrgRequest
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.assertWithinBudget
import dev.ostrom.server.testing.measureRead
import dev.ostrom.server.testing.serverTest
import dev.ostrom.server.testing.writeReadReport
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `GET /api/v1/orgs/{org}/members` on 10 organizations of 1 000 members each: the whole list and one member. Each
 * keeps the read budget (ReadBench.kt); its queries, plans and p95 are written to `build/reports/perf/orgs.txt`.
 */
class OrgsReadsBenchTest {
    private suspend fun TestServer.owner(index: Int): StartedSession {
        val login = "owner$index"
        val user = koin.get<ResolveUserUseCase>()(
            VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
        )
        val session = koin.get<StartWebSessionUseCase>()(user.id)
        val created = client.post("/api/v1/orgs") {
            session(session)
            header(HttpHeaders.Origin, CONSOLE_ORIGIN)
            contentType(ContentType.Application.Json)
            setBody(CreateOrgRequest("Org $index", "org$index"))
        }
        assertEquals(HttpStatusCode.Created, created.status)
        return session
    }

    private fun HttpRequestBuilder.session(started: StartedSession) {
        header(HttpHeaders.Cookie, "$SESSION_COOKIE=${started.secret}; $CSRF_COOKIE=${started.csrfToken}")
        header(CSRF_HEADER, started.csrfToken)
    }

    // No `::` cast and no colon at all in the seeds: the driver would read `:x` as a named parameter.
    private fun membersOf(index: Int) = listOf(
        "INSERT INTO identity_users (id, login, display_name, created_at) SELECT CAST(md5('u$index-' || i) AS uuid), " +
            "'user$index-' || i, 'User ' || i, now() FROM generate_series(2, $MEMBERS_PER_ORG) AS i",
        "INSERT INTO orgs_members (id, org_id, user_id, role, joined_at) SELECT gen_random_uuid(), " +
            "(SELECT id FROM orgs_organizations WHERE slug = 'org$index'), CAST(md5('u$index-' || i) AS uuid), " +
            "CASE WHEN i % 10 = 0 THEN 'admin' ELSE 'member' END, now() - i * INTERVAL '1 hour' " +
            "FROM generate_series(2, $MEMBERS_PER_ORG) AS i",
    )

    @Test
    fun theMemberListKeepsItsBudget() = serverTest(listOf(IdentityModule, OrgsModule)) { server ->
        val sessions = (0 until ORGS).map { server.owner(it) }
        val fixtures = server.database.superuser
        (0 until ORGS).flatMap { membersOf(it) }.forEach { fixtures.execute(it).getOrThrow() }
        fixtures.execute("VACUUM ANALYZE identity_users").getOrThrow()
        fixtures.execute("VACUUM ANALYZE orgs_members").getOrThrow()

        val measures = listOf(
            "GET /api/v1/orgs/{org}/members (1 000 members)" to "/api/v1/orgs/org0/members",
            "GET /api/v1/orgs/{org}/members/{login}" to "/api/v1/orgs/org0/members/user0-500",
        ).map { (name, path) -> server.measureRead(name) { get(path) { session(sessions.first()) } } }
        writeReadReport("orgs", measures)
        measures.forEach { it.assertWithinBudget(setOf("orgs_members", "identity_users")) }
    }

    private companion object {
        const val ORGS = 10
        const val MEMBERS_PER_ORG = 1_000
        const val CONSOLE_ORIGIN = "http://localhost:5173"
    }
}
