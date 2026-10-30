// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.reports

import dev.ostrom.server.kernel.auth.AuthenticatedPrincipal
import dev.ostrom.server.kernel.auth.OrgPrincipal
import dev.ostrom.server.kernel.auth.OrgRole
import dev.ostrom.server.kernel.auth.PrincipalKind
import dev.ostrom.server.kernel.auth.PrincipalOrgResolver
import dev.ostrom.server.kernel.auth.TokenAuthenticator
import dev.ostrom.server.kernel.database.withOrganization
import dev.ostrom.server.secrets.domain.SecretCipher
import dev.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import dev.ostrom.server.testing.TestServer
import io.github.smyrgeorge.sqlx4k.Statement
import org.koin.dsl.module
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

val OLGA = Person("olga", ORG_A, OrgRole.OWNER)
val MIA = Person("mia", ORG_A, OrgRole.MEMBER)
val CAROL = Person("carol", ORG_B, OrgRole.OWNER)
val PEOPLE = listOf(OLGA, MIA, CAROL)

/**
 * The test identities: `olga` (owner of A), `mia` (member of A) and `carol` (owner of B) are browser sessions;
 * `nobody` belongs to no organization; `bot` is a machine of A with no scope; `feed` and `spend` are mia's tokens
 * with `activity:read` and `usage:read` only.
 */
fun reportsOverrides(clock: Clock) = module {
    single<Clock> { clock }
    single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
    single<TokenAuthenticator> {
        object : TokenAuthenticator {
            override suspend fun authenticate(token: String): AuthenticatedPrincipal? = when (token) {
                "olga", "mia", "carol", "nobody" -> AuthenticatedPrincipal(token, PrincipalKind.USER, setOf("user:*"))
                "bot" -> AuthenticatedPrincipal("bot", PrincipalKind.SERVICE, emptySet())
                // A machine never gets a person's rights, even with `user:*` listed.
                "fake" -> AuthenticatedPrincipal("mia", PrincipalKind.SERVICE, setOf("user:*"))
                "feed" -> AuthenticatedPrincipal("mia", PrincipalKind.SERVICE, setOf("activity:read"))
                "spend" -> AuthenticatedPrincipal("mia", PrincipalKind.SERVICE, setOf("usage:read"))
                else -> null
            }
        }
    }
    single<PrincipalOrgResolver> {
        object : PrincipalOrgResolver {
            override suspend fun resolve(principal: AuthenticatedPrincipal, orgId: Uuid?): OrgPrincipal? =
                when (principal.subjectId) {
                    "bot" -> OrgPrincipal(ORG_A, Uuid.random(), OrgRole.MEMBER)
                    else -> PEOPLE.firstOrNull { it.login == principal.subjectId }?.principal
                }
        }
    }
}

/** One statement in [org], under its row level security like the server's reads. */
suspend fun TestServer.sql(org: Uuid, text: String, vararg values: Pair<String, Any>) {
    val statement = Statement.create(text)
    for ((name, value) in values) statement.bind(name, value)
    driver.withOrganization(org) { it.execute(statement).getOrThrow() }
}

/** The two organizations and their people. */
suspend fun TestServer.seedPeople() {
    for (org in listOf(ORG_A, ORG_B)) {
        sql(
            org,
            "INSERT INTO orgs_organizations (id, slug, name, created_at) VALUES (:id, :slug, :slug, :at)",
            "id" to org, "slug" to "org-$org", "at" to NOW,
        )
    }
    for (person in PEOPLE) {
        sql(
            person.org,
            "INSERT INTO identity_users (id, login, display_name, created_at) VALUES (:id, :login, :login, :at)",
            "id" to person.userId, "login" to person.login, "at" to NOW,
        )
        sql(
            person.org,
            "INSERT INTO orgs_members (id, org_id, user_id, role, joined_at) VALUES (:id, :org, :user, :role, :at)",
            "id" to person.memberId, "org" to person.org, "user" to person.userId,
            "role" to person.role.name.lowercase(), "at" to NOW,
        )
    }
}

/** A session of the seed; [endedAt] and [costUsd] together when it ended. */
data class SeedSession(
    val org: Uuid,
    val id: String,
    val agent: String,
    val repo: String,
    val dev: String,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val costUsd: Double = 0.0,
)

/** Writes a session. */
suspend fun TestServer.session(seed: SeedSession) = with(seed) {
    val columns = "org_id, id, root_id, segment, agent, dev, repo, started_at, last_seen_at"
    val values = ":org, :id, :id, 0, :agent, :dev, :repo, :started, :started"
    val common = arrayOf<Pair<String, Any>>(
        "org" to org, "id" to id, "agent" to agent, "dev" to dev, "repo" to repo, "started" to startedAt,
    )
    if (endedAt == null) {
        sql(org, "INSERT INTO sessions_sessions ($columns) VALUES ($values)", *common)
    } else {
        sql(
            org, "INSERT INTO sessions_sessions ($columns, ended_at, cost_usd) VALUES ($values, :ended, :cost)",
            *common, "ended" to endedAt, "cost" to costUsd,
        )
    }
}

/** A model call of the ledger; no member is the organization's own spending, no session is spending outside one. */
suspend fun TestServer.spend(org: Uuid, member: Uuid?, session: String?, micros: Long, at: Instant) {
    val memberValue = if (member == null) "NULL" else ":member"
    val sessionValue = if (session == null) "NULL" else ":session"
    val values = mutableListOf<Pair<String, Any>>("id" to Uuid.random(), "org" to org, "micros" to micros, "at" to at)
    member?.let { values += "member" to it }
    session?.let { values += "session" to it }
    sql(
        org,
        "INSERT INTO usage_events (id, org_id, member_id, kind, name, session_id, input_tokens, output_tokens, " +
            "cache_read_tokens, cache_write_tokens, cost_micros, duration_ms, outcome, at) VALUES (:id, :org, " +
            "$memberValue, 'MODEL', 'claude-sonnet', $sessionValue, 1, 1, 0, 0, :micros, 5, 'ok', :at)",
        *values.toTypedArray(),
    )
}

/** A gate of the seed; [decidedBy] and [decidedAt] together when a person decided it. */
data class SeedGate(
    val org: Uuid,
    val id: Uuid,
    val label: String,
    val status: String,
    val createdAt: Instant,
    val decidedBy: String? = null,
    val decidedAt: Instant? = null,
)

/** Writes a gate, opened by the agent `claude`. */
suspend fun TestServer.gate(seed: SeedGate) = with(seed) {
    val columns = "id, org_id, gate_key, subject, label, mode, status, asked_by, asked_via, session_ids, launch_ids, " +
        "shown_digest, evidence, created_at"
    val values = ":id, :org, 'merge', 'pr', :label, 'ENFORCE', :status, '[\"claude\"]', 'cli', '[]', '[]', 'd', " +
        "'{}', :created"
    val common = arrayOf<Pair<String, Any>>(
        "id" to id, "org" to org, "label" to label, "status" to status, "created" to createdAt,
    )
    if (decidedBy == null || decidedAt == null) {
        sql(org, "INSERT INTO gates_gates ($columns) VALUES ($values)", *common)
    } else {
        sql(
            org, "INSERT INTO gates_gates ($columns, decided_by, decided_at) VALUES ($values, :by, :decided)",
            *common, "by" to decidedBy, "decided" to decidedAt,
        )
    }
}
