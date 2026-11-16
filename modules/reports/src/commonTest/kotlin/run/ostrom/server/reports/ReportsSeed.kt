// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.reports

import run.ostrom.server.kernel.auth.AuthenticatedPrincipal
import run.ostrom.server.kernel.auth.OrgPrincipal
import run.ostrom.server.kernel.auth.OrgRole
import run.ostrom.server.kernel.auth.PrincipalKind
import run.ostrom.server.kernel.auth.PrincipalOrgResolver
import run.ostrom.server.kernel.auth.TokenAuthenticator
import run.ostrom.server.kernel.config.ConfigSource
import run.ostrom.server.kernel.config.EnvironmentConfigSource
import run.ostrom.server.kernel.config.LayeredConfigSource
import run.ostrom.server.kernel.config.MapConfigSource
import run.ostrom.server.kernel.database.InstantColumnType
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.withOrganization
import run.ostrom.server.secrets.domain.SecretCipher
import run.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import run.ostrom.server.testing.TestServer
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.DoubleColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import java.sql.ResultSet
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    // The compiler signs its packages for the public address.
    single<ConfigSource> {
        LayeredConfigSource(
            listOf(MapConfigSource(mapOf("OSTROM_PUBLIC_URL" to "https://ostrom.example/")), EnvironmentConfigSource()),
        )
    }
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
    inOrganization(org) { execNamed(text, values.toMap()) }
}

/** [block] in one transaction of [org] on the serving pool, under its row level security like the server's reads. */
suspend fun <T> TestServer.inOrganization(org: Uuid, block: JdbcTransaction.() -> T): T =
    koin.get<JdbcDatabase>().withOrganization(org, block)

/** Runs each of [statements] (no parameter) in [org], in one transaction, under its row level security. */
suspend fun TestServer.seedIn(org: Uuid, statements: List<String>) {
    inOrganization(org) { statements.forEach { exec(it) } }
}

/** Runs [text], its `:name` markers bound from [values] (see [positional]). */
fun JdbcTransaction.execNamed(text: String, values: Map<String, Any?> = emptyMap()) {
    val (sql, args) = positional(text, values)
    exec(sql, args)
}

/** Runs the query [text] ([positional]), [row] read from each of its rows. */
fun <T> JdbcTransaction.queryNamed(text: String, values: Map<String, Any?>, row: (ResultSet) -> T): List<T> {
    val (sql, args) = positional(text, values)
    return exec(sql, args, StatementType.SELECT) { rs -> buildList { while (rs.next()) add(row(rs)) } }.orEmpty()
}

/**
 * [text] with each `:name` marker (outside a string literal, and not a `::` cast) turned into a `?`, and the typed
 * value of each marker in order: the SQL written with named
 * parameters (the former driver's), run through JDBC.
 */
fun positional(text: String, values: Map<String, Any?>): Pair<String, List<Pair<IColumnType<*>, Any?>>> {
    val args = mutableListOf<Pair<IColumnType<*>, Any?>>()
    val sql = MARKER.replace(text) { match ->
        val name = match.groups[NAME]?.value ?: return@replace match.value
        require(name in values) { "No value for :$name in: $text" }
        val value = values.getValue(name)
        args += columnTypeOf(value) to value
        "?"
    }
    return sql to args
}

// A string literal, a `::` cast, or a `:name` marker: only the last is replaced.
private val MARKER = Regex("'(?:[^']|'')*'|::|:([A-Za-z_][A-Za-z0-9_]*)")
private const val NAME = 1

private fun columnTypeOf(value: Any?): IColumnType<*> = when (value) {
    is Uuid -> UuidColumnType()
    is Instant -> InstantColumnType()
    is Int -> IntegerColumnType()
    is Long -> LongColumnType()
    is Double -> DoubleColumnType()
    is Boolean -> BooleanColumnType()
    is String -> TextColumnType()
    else -> error("No column type for ${value?.let { it::class.simpleName }}")
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

/**
 * A gate of the seed; [decidedBy] and [decidedAt] together when a person decided it. [repo] and [realRepo] go into its
 * stored subject, as the gates module writes it: the repository the session declared, and the one the command really
 * ran in when it differs.
 */
data class SeedGate(
    val org: Uuid,
    val id: Uuid,
    val label: String,
    val status: String,
    val createdAt: Instant,
    val decidedBy: String? = null,
    val decidedAt: Instant? = null,
    val repo: String? = null,
    val realRepo: String? = null,
)

/** Writes a gate, opened by the agent `claude`. */
suspend fun TestServer.gate(seed: SeedGate) = with(seed) {
    val columns = "id, org_id, gate_key, subject, label, mode, status, asked_by, asked_via, session_ids, launch_ids, " +
        "shown_digest, evidence, created_at"
    val values = ":id, :org, 'merge', :subject, :label, 'ENFORCE', :status, '[\"claude\"]', 'cli', '[]', '[]', 'd', " +
        "'{}', :created"
    val subject = buildJsonObject {
        repo?.let { put("repo", it) }
        realRepo?.let { put("realRepo", it) }
        put("actionClass", "merge")
        put("command", "")
        put("scope", "sha:0")
    }.toString()
    val common = arrayOf<Pair<String, Any>>(
        "id" to id, "org" to org, "subject" to subject, "label" to label, "status" to status, "created" to createdAt,
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

/** A signed decision of the compiler about [subject] (a `workflow` or another [kind]). */
data class SeedDecision(
    val org: Uuid,
    val id: String,
    val kind: String,
    val subject: String,
    val by: String,
    val at: Instant,
)

/** Writes a decision, its time kept as text like the compiler does. */
suspend fun TestServer.decision(seed: SeedDecision) = with(seed) {
    sql(
        org,
        "INSERT INTO compiler_decisions (org_id, id, subject_kind, subject_id, decided_by, decided_at) " +
            "VALUES (:org, :id, :kind, :subject, :by, :at)",
        "org" to org, "id" to id, "kind" to kind, "subject" to subject, "by" to by, "at" to at.toString(),
    )
}
