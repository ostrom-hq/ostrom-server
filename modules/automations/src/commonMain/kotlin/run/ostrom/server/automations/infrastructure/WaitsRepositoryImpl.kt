// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations.infrastructure

import run.ostrom.server.automations.domain.CreateOutcome
import run.ostrom.server.automations.domain.SecretsUnavailableException
import run.ostrom.server.automations.domain.ServedSecrets
import run.ostrom.server.automations.domain.Wait
import run.ostrom.server.automations.domain.WaitAnswer
import run.ostrom.server.automations.domain.WaitFilter
import run.ostrom.server.automations.domain.WaitSecret
import run.ostrom.server.automations.domain.WaitSpec
import run.ostrom.server.automations.domain.WaitStatus
import run.ostrom.server.automations.domain.WaitsRepository
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.lockUntilCommit
import run.ostrom.server.kernel.database.rowBefore
import run.ostrom.server.kernel.events.OrgEventPublisher
import run.ostrom.server.kernel.events.OrgEventType
import run.ostrom.server.kernel.http.OstromJson
import run.ostrom.server.secrets.domain.SecretCipher
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.compoundAnd
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsertReturning
import org.jetbrains.exposed.v1.jdbc.updateReturning
import org.jetbrains.exposed.v1.jdbc.upsert
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * [WaitsRepository] on PostgreSQL (table `automations_waits` of [AutomationsMigrations]). The question is JSON; the
 * first answer wins because a wait is only ever resolved or cancelled `WHERE status = 'open'`. Every query runs
 * confined to its organization (`inOrg`, S2-01) and filters on `org_id` too. Each change publishes its wait event to
 * the organization's feed (`cloud.wait.created`, `.resolved`, `.cancelled`) in its own transaction: the event exists
 * exactly when the change committed.
 *
 * The values of secret fields (`waits.secrets.v1`) are sealed with [cipher] into `automations_wait_secrets`, in the
 * answer's own transaction; the wait's row holds only `provided`. Without a cipher no secret can be kept or served.
 * The cipher suspends, so a transaction never calls it: [resolve] seals before its transaction, [serveSecrets] opens
 * after the one that claims the secrets.
 *
 * @param database The serving role's pool.
 * @param events The organization's event feed.
 * @param cipher Seals the secrets at rest; null on a server without the secrets module.
 * @param secretsPage The secrets read by one statement when they are served.
 */
@Suppress("TooManyFunctions") // One implementation of the one port of the feature.
internal class WaitsRepositoryImpl(
    private val database: JdbcDatabase,
    private val events: OrgEventPublisher,
    private val cipher: SecretCipher? = null,
    private val secretsPage: Int = SECRETS_PAGE,
) : WaitsRepository {
    override suspend fun createWithinLimit(wait: Wait, maxOpen: Int, sessions: List<String>): CreateOutcome =
        database.inOrg(wait.orgId) {
            // Serialize the count and the insert of one scope, so that two concurrent asks never both pass the limit.
            // Keyed on the session written to: an agent asks only in its launch's open segment.
            val session = wait.sessionId
            val scope = if (session == null) "member:${wait.createdBy}" else "session:$session"
            lockUntilCommit("automations:${wait.orgId}:$scope")
            val w = AutomationsWaits
            val waits = w.id.count()
            val inScope = if (session == null) {
                (w.createdBy eq wait.createdBy) and w.sessionId.isNull()
            } else {
                w.sessionId inList (sessions + session).distinct()
            }
            val open = w.select(waits)
                .where { (w.orgId eq wait.orgId) and (w.status eq WaitStatus.OPEN.wire) and inScope }
                .single()[waits]
            if (open >= maxOpen) return@inOrg CreateOutcome.AT_LIMIT
            // A replay of the same ask (its idempotency key) inserts nothing and returns no row: `ON CONFLICT (org_id,
            // created_by, idempotency_key) DO UPDATE … WHERE FALSE`, the sqlx4k `DO NOTHING` on that target alone, so
            // any other conflict still fails loudly.
            val inserted = w.upsertReturning(
                w.orgId, w.createdBy, w.idempotencyKey,
                returning = listOf(w.id),
                onUpdate = { it[w.id] = w.id },
                where = { Op.FALSE },
            ) {
                it[w.id] = wait.id
                it[w.orgId] = wait.orgId
                it[w.kind] = wait.kind
                it[w.status] = wait.status.wire
                it[w.sessionId] = wait.sessionId
                it[w.runId] = wait.runId
                it[w.assignee] = wait.assignee
                it[w.createdBy] = wait.createdBy
                it[w.question] = OstromJson.encodeToString(WaitSpec.serializer(), wait.question)
                it[w.createdAt] = wait.createdAt
                it[w.idempotencyKey] = wait.idempotencyKey
                it[w.resolver] = wait.resolver.wire
                it[w.dueAt] = wait.timer?.dueAt
                it[w.timeoutAt] = wait.timer?.timeoutAt
                it[w.eventSource] = wait.timer?.source
                it[w.resumeMessage] = wait.timer?.resumeMessage
                it[w.eventSecretHash] = wait.timer?.secretHash
            }
            if (inserted.none()) return@inOrg CreateOutcome.DUPLICATE
            events.publishIn(this, wait.orgId, listOf(wait.event(OrgEventType.WAIT_CREATED)))
            CreateOutcome.CREATED
        }

    override suspend fun findByIdempotencyKey(orgId: Uuid, createdBy: String, key: String): Wait? =
        database.inOrg(orgId) {
            val w = AutomationsWaits
            w.select(WAIT_COLUMNS)
                .where {
                    (w.orgId eq orgId) and (w.createdBy eq createdBy) and (w.idempotencyKey eq key)
                }
                .singleOrNull()?.toWait()
        }

    override suspend fun get(orgId: Uuid, id: Uuid): Wait? = database.inOrg(orgId) {
        val w = AutomationsWaits
        w.select(WAIT_COLUMNS).where { (w.id eq id) and (w.orgId eq orgId) }.singleOrNull()?.toWait()
    }

    override suspend fun list(filter: WaitFilter, before: Uuid?, limit: Int): List<Wait> =
        database.inOrg(filter.orgId) {
            val w = AutomationsWaits
            w.select(WAIT_COLUMNS)
                .where {
                    listOfNotNull(
                        w.orgId eq filter.orgId,
                        w.assignee isSet filter.assignee,
                        filter.involving?.let { (w.assignee eq it) or (w.createdBy eq it) },
                        w.createdBy isSet filter.createdBy,
                        w.status isSet filter.status?.wire,
                        w.sessionId isSet filter.sessionId,
                        // A session id never holds a comma (`SESSION_ID` of the sessions module, `REFERENCE` of the
                        // waits).
                        filter.sessionIds?.let { w.sessionId inList it },
                        w.runId isSet filter.runId,
                        w.resolver isSet filter.resolver?.wire,
                        before?.let {
                            rowBefore(
                                listOf(w.createdAt, w.id),
                                w.select(w.createdAt, w.id).where { (w.id eq it) and (w.orgId eq filter.orgId) },
                            )
                        },
                    ).compoundAnd()
                }
                .orderBy(w.createdAt to SortOrder.DESC, w.id to SortOrder.DESC)
                .limit(limit)
                .map { it.toWait() }
        }

    override suspend fun resolve(orgId: Uuid, id: Uuid, answer: WaitAnswer, secrets: List<WaitSecret>): Wait? {
        if (secrets.isNotEmpty() && cipher == null) throw SecretsUnavailableException()
        val sealed = if (cipher == null || secrets.isEmpty()) emptyList() else seal(cipher, orgId, id, secrets)
        return database.inOrg(orgId) {
            val w = AutomationsWaits
            val resolved = w.updateReturning(
                WAIT_COLUMNS,
                where = { (w.id eq id) and (w.orgId eq orgId) and (w.status eq WaitStatus.OPEN.wire) },
            ) {
                it[w.status] = WaitStatus.RESOLVED.wire
                it[w.answerAction] = answer.action
                it[w.answerValues] = JsonObject(answer.values).toString()
                it[w.answeredBy] = answer.author
                it[w.answeredAt] = answer.answeredAt
                it[w.resolvedAt] = answer.answeredAt
                it[w.wakePending] = w.sessionId.isNotNull()
            }.map { it.toWait() }
            events.publishIn(this, orgId, resolved.map { it.event(OrgEventType.WAIT_RESOLVED, answer.action) })
            // Kept with the answer, so that an answer is never stored without the secret it says was provided.
            resolved.firstOrNull()?.also { wait -> keep(wait, sealed, answer.answeredAt) }
        }
    }

    /**
     * Seals [secrets] for the wait's session, before the answer's transaction. The context binds each to its
     * organization, session and name, so a value copied to another row does not open. No sealed value when the wait
     * does not exist: the answer then resolves nothing. A wait with a secret field always has a session (the ask
     * refuses it otherwise, and the table refuses a row without one).
     */
    private suspend fun seal(
        sealing: SecretCipher,
        orgId: Uuid,
        id: Uuid,
        secrets: List<WaitSecret>,
    ): List<SealedSecret> {
        val row = database.inOrg(orgId) {
            val w = AutomationsWaits
            w.select(w.sessionId).where { (w.id eq id) and (w.orgId eq orgId) }.singleOrNull()
        } ?: return emptyList()
        val session = checkNotNull(row[AutomationsWaits.sessionId]) { "A wait with secrets has a session" }
        return secrets.map { secret ->
            val value = sealing.seal(secret.value.encodeToByteArray(), secretContext(orgId, session, secret.envName))
            SealedSecret(secret, session, value)
        }
    }

    /**
     * Keeps the [sealed] secrets of the answered [wait]: a later answer with the same name replaces the row. The member
     * the session runs for is the wait's asker: an agent asks for its person only.
     */
    private fun JdbcTransaction.keep(wait: Wait, sealed: List<SealedSecret>, at: Instant) {
        val s = AutomationsWaitSecrets
        for ((secret, session, value) in sealed) {
            s.upsert {
                it[s.orgId] = wait.orgId
                it[s.sessionId] = session
                it[s.envName] = secret.envName
                it[s.waitId] = wait.id
                it[s.fieldId] = secret.fieldId
                it[s.ownerId] = wait.createdBy
                it[s.oneTime] = secret.oneTime
                it[s.value] = value
                it[s.createdAt] = at
                it[s.expiresAt] = secret.expiresAt
            }
        }
    }

    override suspend fun serveSecrets(orgId: Uuid, sessionId: String, ownerId: String, now: Instant): ServedSecrets {
        val sealing = cipher ?: return ServedSecrets()
        // Claimed in one transaction, the one-time rows deleted before its commit: of two consumers at once, the
        // second waits on the first one's locks, then finds them gone.
        val rows = database.inOrg(orgId) { claimSecrets(orgId, sessionId, ownerId, now) }
        // Opened after the commit (the cipher suspends). A value that does not open (another key, another row) is
        // never served; a one-time one is consumed all the same.
        val opened = rows.mapNotNull { row ->
            sealing.open(row.sealed, secretContext(orgId, sessionId, row.name))?.let { row to it.decodeToString() }
        }
        return ServedSecrets(
            opened.associate { (row, value) -> row.name to value },
            opened.filter { (row, _) -> row.oneTime }.map { (row, _) -> row.name }.toSet(),
        )
    }

    // Every live secret of the session's owner, locked (`FOR UPDATE`) page after page in the order of their names,
    // each page's one-time rows deleted in this transaction: no row is left out, and none is served twice.
    private fun claimSecrets(orgId: Uuid, sessionId: String, ownerId: String, now: Instant): List<Opening> {
        val s = AutomationsWaitSecrets
        val claimed = mutableListOf<Opening>()
        var after = ""
        while (true) {
            val page = s.select(s.envName, s.value, s.oneTime)
                .where {
                    (s.orgId eq orgId) and (s.sessionId eq sessionId) and (s.ownerId eq ownerId) and
                        (s.expiresAt greater now) and (s.envName greater after)
                }
                .orderBy(s.envName to SortOrder.ASC)
                .limit(secretsPage)
                .forUpdate()
                .map { Opening(it[s.envName], it[s.value], it[s.oneTime]) }
            val once = page.filter { it.oneTime }.map { it.name }
            if (once.isNotEmpty()) {
                s.deleteWhere { (s.orgId eq orgId) and (s.sessionId eq sessionId) and (s.envName inList once) }
            }
            claimed += page
            if (page.size < secretsPage) return claimed
            after = page.last().name
        }
    }

    override suspend fun cancel(orgId: Uuid, id: Uuid, now: Instant): Wait? =
        database.changeInOrg(orgId, events, { it.event(OrgEventType.WAIT_CANCELLED) }) {
            val w = AutomationsWaits
            w.updateReturning(
                WAIT_COLUMNS,
                where = { (w.id eq id) and (w.orgId eq orgId) and (w.status eq WaitStatus.OPEN.wire) },
            ) {
                it[w.status] = WaitStatus.CANCELLED.wire
                it[w.resolvedAt] = now
            }.map { it.toWait() }
        }.firstOrNull()

    override suspend fun cancelOpenOfSessions(orgId: Uuid, sessions: List<String>, now: Instant): List<Wait> =
        database.changeInOrg(orgId, events, { it.event(OrgEventType.WAIT_CANCELLED) }) {
            // A session id never holds a comma (`SESSION_ID` of the sessions module, `REFERENCE` of the waits).
            val w = AutomationsWaits
            w.updateReturning(
                WAIT_COLUMNS,
                where = {
                    (w.orgId eq orgId) and (w.sessionId inList sessions) and (w.status eq WaitStatus.OPEN.wire)
                },
            ) {
                it[w.status] = WaitStatus.CANCELLED.wire
                it[w.resolvedAt] = now
            }.map { it.toWait() }
        }

    override suspend fun takeAnswered(orgId: Uuid, sessions: List<String>, limit: Int): List<Wait> =
        database.inOrg(orgId) {
            // The answers not handed over, locked and skipping those another replica takes now, in the order they
            // were resolved; then they are marked handed over, in the same transaction.
            val w = AutomationsWaits
            val taken = w.select(WAIT_COLUMNS)
                .where { (w.orgId eq orgId) and (w.sessionId inList sessions) and w.wakePending }
                .orderBy(w.resolvedAt to SortOrder.ASC, w.id to SortOrder.ASC)
                .limit(limit)
                .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(ForUpdateOption.PostgreSQL.MODE.SKIP_LOCKED))
                .map { it.toWait() }
            w.update({ (w.orgId eq orgId) and (w.id inList taken.map { it.id }) }) { it[w.wakePending] = false }
            taken.sortedWith(compareBy({ it.resolvedAt }, { it.id.toString() }))
        }

    override suspend fun hasOpen(orgId: Uuid, sessions: List<String>): Boolean = database.inOrg(orgId) {
        val w = AutomationsWaits
        !w.select(w.id)
            .where {
                (w.orgId eq orgId) and (w.sessionId inList sessions) and (w.status eq WaitStatus.OPEN.wire)
            }
            .empty()
    }

    /** A secret sealed for [session], to keep with the answer. */
    private data class SealedSecret(val secret: WaitSecret, val session: String, val value: String)

    /** A sealed secret read, before it is opened. */
    private class Opening(val name: String, val sealed: String, val oneTime: Boolean)

    private companion object {
        /** The secrets of a session read by one statement; a session with more is read page after page. */
        const val SECRETS_PAGE = 1000
    }
}

/** The condition that [this] equals [value], none when the filter is not set. */
private infix fun <T> Column<T>.isSet(value: T?): Op<Boolean>? = value?.let { this eq it }

/** What a secret is sealed with: its organization, session and variable, so that a copied value does not open. */
private fun secretContext(orgId: Uuid, sessionId: String?, envName: String) = "wait-secret:$orgId:$sessionId:$envName"
