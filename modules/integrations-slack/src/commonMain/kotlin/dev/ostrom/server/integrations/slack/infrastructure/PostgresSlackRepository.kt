// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.integrations.slack.infrastructure

import dev.ostrom.server.integrations.slack.domain.SlackLink
import dev.ostrom.server.integrations.slack.domain.SlackMessageRef
import dev.ostrom.server.integrations.slack.domain.SlackRepository
import io.github.smyrgeorge.sqlx4k.Driver
import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.ResultSet
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInstant
import io.github.smyrgeorge.sqlx4k.impl.extensions.asUuid
import kotlin.uuid.Uuid

/**
 * [SlackRepository] on PostgreSQL (sqlx4k). Every call is one transaction that first sets the organization, or the
 * Slack member of a verified click, read by the row level security policies.
 *
 * @param driver The connection pool.
 */
class PostgresSlackRepository(private val driver: Driver) : SlackRepository {
    private suspend fun <T> keyed(setting: String, value: String, block: suspend QueryExecutor.() -> T): T =
        driver.transaction {
            rows(Statement.create("SELECT set_config(:name, :value, TRUE)").bind("name", setting).bind("value", value))
            block()
        }

    private suspend fun <T> scoped(orgId: Uuid, block: suspend QueryExecutor.() -> T): T =
        keyed("ostrom.org_id", orgId.toString(), block)

    override suspend fun links(orgId: Uuid): List<SlackLink> = scoped(orgId) {
        rows(
            Statement.create(
                "SELECT $LINK_COLUMNS FROM slack_links WHERE org_id = :org ORDER BY person_id LIMIT $MAX_LINKS",
            ).bind("org", orgId),
        ).map { it.toLink() }
    }

    override suspend fun link(orgId: Uuid, personId: String): SlackLink? = scoped(orgId) {
        rows(
            Statement.create("SELECT $LINK_COLUMNS FROM slack_links WHERE org_id = :org AND person_id = :person")
                .bind("org", orgId).bind("person", personId),
        ).firstOrNull()?.toLink()
    }

    override suspend fun linkOf(teamId: String, userId: String): SlackLink? =
        keyed("ostrom.slack_member", "$teamId:$userId") {
            rows(
                Statement.create("SELECT $LINK_COLUMNS FROM slack_links WHERE team_id = :team AND user_id = :user")
                    .bind("team", teamId).bind("user", userId),
            ).firstOrNull()?.toLink()
        }

    override suspend fun saveLink(link: SlackLink): Boolean = try {
        scoped(link.orgId) {
            execute(
                Statement.create("DELETE FROM slack_links WHERE org_id = :org AND person_id = :person")
                    .bind("org", link.orgId).bind("person", link.personId),
            ).getOrThrow()
            val inserted = rows(
                Statement.create(
                    "INSERT INTO slack_links ($LINK_COLUMNS) VALUES (:org, :person, :team, :user, :by, :at) " +
                        "ON CONFLICT DO NOTHING RETURNING person_id",
                ).bind("org", link.orgId).bind("person", link.personId).bind("team", link.teamId)
                    .bind("user", link.userId).bind("by", link.linkedBy).bind("at", link.linkedAt),
            )
            // Another member holds this Slack account: undo the delete with the transaction.
            if (inserted.isEmpty()) throw TakenException()
        }
        true
    } catch (_: TakenException) {
        false
    }

    override suspend fun deleteLink(orgId: Uuid, personId: String): Boolean = scoped(orgId) {
        execute(
            Statement.create("DELETE FROM slack_links WHERE org_id = :org AND person_id = :person")
                .bind("org", orgId).bind("person", personId),
        ).getOrThrow() > 0
    }

    override suspend fun saveMessage(message: SlackMessageRef) {
        scoped(message.orgId) {
            execute(
                Statement.create(
                    "INSERT INTO slack_messages (org_id, request_id, gate_id, channel, ts) " +
                        "VALUES (:org, :request, :gate, :channel, :ts) " +
                        "ON CONFLICT (org_id, request_id) DO UPDATE SET channel = EXCLUDED.channel, ts = EXCLUDED.ts",
                ).bind("org", message.orgId).bind("request", message.requestId).bind("gate", message.gateId)
                    .bind("channel", message.channel).bind("ts", message.ts),
            ).getOrThrow()
        }
    }

    override suspend fun messages(orgId: Uuid, gateId: Uuid): List<SlackMessageRef> = scoped(orgId) {
        rows(
            Statement.create(
                "SELECT org_id, request_id, gate_id, channel, ts FROM slack_messages " +
                    "WHERE org_id = :org AND gate_id = :gate ORDER BY request_id LIMIT $MAX_LINKS",
            ).bind("org", orgId).bind("gate", gateId),
        ).map {
            SlackMessageRef(
                orgId = it.get("org_id").asUuid(),
                requestId = it.get("request_id").asUuid(),
                gateId = it.get("gate_id").asUuid(),
                channel = it.get("channel").asString(),
                ts = it.get("ts").asString(),
            )
        }
    }

    private class TakenException : Exception()

    private companion object {
        const val MAX_LINKS = 1000
        const val LINK_COLUMNS = "org_id, person_id, team_id, user_id, linked_by, linked_at"

        suspend fun QueryExecutor.rows(statement: Statement): List<ResultSet.Row> =
            fetchAll(statement).getOrThrow().toList()

        fun ResultSet.Row.toLink() = SlackLink(
            orgId = get("org_id").asUuid(),
            personId = get("person_id").asString(),
            teamId = get("team_id").asString(),
            userId = get("user_id").asString(),
            linkedBy = get("linked_by").asString(),
            linkedAt = get("linked_at").asInstant(),
        )
    }
}
