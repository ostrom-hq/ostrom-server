// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.identity

import dev.ostrom.server.identity.domain.IdentityProvider
import dev.ostrom.server.identity.domain.ResolveUserUseCase
import dev.ostrom.server.identity.domain.StartWebSessionUseCase
import dev.ostrom.server.identity.domain.VerifiedIdentity
import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgProfile
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.testing.assertWithinBudget
import dev.ostrom.server.testing.benchTest
import dev.ostrom.server.testing.measureRead
import dev.ostrom.server.testing.writeReadReport
import io.ktor.client.request.get
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The identity module's read routes on a filled database: `GET /api/v1/audit` over 100 000 audit lines of 10
 * organizations (its first page, an actor's, a rare event's, an older page), and `GET /api/v1/devices` for a person
 * with 5 000 live agent tokens among 50 000. Each keeps the read budget (ReadBench.kt): its queries, their plans
 * under row-level security and its p95 at 20 concurrent calls are written to `build/reports/perf/identity.txt`.
 */
class IdentityReadsBenchTest {
    private val orgs = List(ORGS) { Uuid.random() }
    private val acme = orgs.first()
    private val memberships = mutableMapOf<String, OrgPrincipal>()
    private val overrides = module {
        single<PrincipalOrgResolver> {
            object : PrincipalOrgResolver {
                override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
                    memberships[principal.subjectId]

                override suspend fun profile(orgId: Uuid): OrgProfile? = null
            }
        }
    }

    // No `::` cast and no colon at all in the seeds: the driver would read `:x` as a named parameter.
    private fun auditOf(org: Uuid, ada: Uuid) =
        "INSERT INTO identity_audit_events (id, chain, sequence, org_id, actor_id, action, target_type, target_id, " +
            "details, occurred_at, previous_hash, hash, signature, key_id) SELECT gen_random_uuid(), '$org', i, " +
            "CAST('$org' AS uuid), CASE WHEN i % 1000 = 0 THEN CAST('$ada' AS uuid) " +
            "WHEN i % 7 = 0 THEN NULL ELSE CAST(md5('actor' || (i % 30)) AS uuid) END, " +
            "CASE WHEN i % 2000 = 0 THEN 'org.renamed' ELSE 'event.' || (i % 12) END, 'thing', 't' || i, '{}', " +
            "now() - (${AUDIT_PER_ORG} - i) * INTERVAL '1 minute', md5('p' || i), md5('h' || i), 'sig', 'key' " +
            "FROM generate_series(1, $AUDIT_PER_ORG) AS i"

    private fun tokensOf(user: Uuid, org: Uuid, kind: String, count: Int) =
        "INSERT INTO identity_api_tokens (id, user_id, org_id, name, kind, scopes, hint, agent_session_id, " +
            "secret_hash, created_at, expires_at, revoked_at) SELECT gen_random_uuid(), CAST('$user' AS uuid), " +
            (if (kind == "AGENT") "CAST('$org' AS uuid)" else "NULL") + ", 'seed', '$kind', 'sessions-read', 'hint', " +
            "'session-' || i, md5('$user$kind' || i), now() - i * INTERVAL '1 minute', now() + INTERVAL '1 day', " +
            "NULL FROM generate_series(1, $count) AS i"

    @Test
    fun theReadRoutesKeepTheirBudget() = benchTest(listOf(IdentityModule), overrides) { server ->
        val people = orgs.mapIndexed { index, org ->
            val login = if (index == 0) "ada" else "person$index"
            val user = server.koin.get<ResolveUserUseCase>()(
                VerifiedIdentity(IdentityProvider.GITHUB, login, "$login@example.com", true, login, login = login),
            )
            memberships["${user.id}"] = OrgPrincipal(org, Uuid.random(), OrgRole.OWNER)
            user.id to org
        }
        val ada = people.first().first
        val fixtures = server.database.superuser
        people.forEach { (user, org) ->
            fixtures.execute(auditOf(org, ada)).getOrThrow()
            fixtures.execute(tokensOf(user, org, "AGENT", AGENT_TOKENS_PER_PERSON)).getOrThrow()
        }
        fixtures.execute(tokensOf(ada, acme, "CLI", 3)).getOrThrow()
        fixtures.execute("VACUUM ANALYZE identity_audit_events").getOrThrow()
        fixtures.execute("VACUUM ANALYZE identity_api_tokens").getOrThrow()
        val session = server.koin.get<StartWebSessionUseCase>()(ada)

        val measures = listOf(
            "GET /api/v1/audit" to "/api/v1/audit",
            "GET /api/v1/audit?actor=ada (10 lines of 10 000)" to "/api/v1/audit?actor=ada",
            "GET /api/v1/audit?event=org.renamed (5 lines of 10 000)" to "/api/v1/audit?event=org.renamed",
            "GET /api/v1/audit?before=5000" to "/api/v1/audit?before=5000",
            "GET /api/v1/audit?actor=ada&before=5000 (an actor's older page)" to "/api/v1/audit?actor=ada&before=5000",
            "GET /api/v1/devices (3 among 5 000 live tokens)" to "/api/v1/devices",
        ).map { (name, path) ->
            server.measureRead(name) { get(path) { browserSession(session.secret, null) } }
        }
        writeReadReport("identity", measures)
        measures.forEach { it.assertWithinBudget(setOf("identity_audit_events", "identity_api_tokens")) }
        // Each filter reads through its own index (IdentityMigrations version 11), not the chain's.
        listOf(1 to "identity_audit_events_chain_actor", 2 to "identity_audit_events_chain_action")
            .plus(listOf(4 to "identity_audit_events_chain_actor", 5 to "identity_api_tokens_user_kind"))
            .forEach { (at, index) -> assertTrue(measures[at].plans.any { index in it }, measures[at].describe()) }
    }

    private companion object {
        const val ORGS = 10
        const val AUDIT_PER_ORG = 10_000
        const val AGENT_TOKENS_PER_PERSON = 5_000
    }
}
