// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.reports.infrastructure

import run.ostrom.server.kernel.database.instant
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.core.Cast
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.CustomOperator
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.stringLiteral

// The tables the reports read, as the modules that own them write them (usage, sessions, orgs, identity, gates,
// compiler): the reports only read, so each object names the columns read and nothing else. The owners' migrations
// create the schema; these objects never create anything.

/** The usage module's daily totals of the ledger, by member, session and launch. */
internal object ReportsUsageDaily : Table("usage_daily") {
    val orgId = uuid("org_id")
    val day = registerColumn("day", DateColumnType())
    val memberId = uuid("member_id").nullable()
    val sessionId = text("session_id").nullable()
    val launchId = text("launch_id").nullable()
    val costMicros = long("cost_micros")
}

/** The usage module's ledger, one row per call. */
internal object ReportsUsageEvents : Table("usage_events") {
    val orgId = uuid("org_id")
    val memberId = uuid("member_id").nullable()
    val sessionId = text("session_id").nullable()
    val launchId = text("launch_id").nullable()
    val costMicros = long("cost_micros")
    val at = instant("at")
}

/** The sessions module's segments. */
internal object ReportsSessions : Table("sessions_sessions") {
    val orgId = uuid("org_id")
    val id = text("id")
    val segment = integer("segment")
    val launchId = text("launch_id").nullable()
    val repo = text("repo").nullable()
    val agent = text("agent")
    val dev = text("dev")
    val startedAt = instant("started_at")
    val endedAt = instant("ended_at").nullable()
    val costUsd = double("cost_usd").nullable()
}

/** The orgs module's members. */
internal object ReportsMembers : Table("orgs_members") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val userId = uuid("user_id")
}

/** The identity module's users. */
internal object ReportsUsers : Table("identity_users") {
    val id = uuid("id")
    val login = text("login")
}

/** The gates module's gates. */
internal object ReportsGates : Table("gates_gates") {
    val id = uuid("id")
    val orgId = uuid("org_id")
    val subject = text("subject")
    val label = text("label")
    val status = text("status")
    val askedBy = text("asked_by")
    val decidedBy = text("decided_by").nullable()
    val decidedAt = instant("decided_at").nullable()
    val createdAt = instant("created_at")
}

/** The compiler module's decisions, their time kept as text. */
internal object ReportsDecisions : Table("compiler_decisions") {
    val orgId = uuid("org_id")
    val id = text("id")
    val subjectKind = text("subject_kind")
    val subjectId = text("subject_id")
    val decidedBy = text("decided_by")
    val decidedAt = text("decided_at")
}

/** A PostgreSQL `DATE`, a UTC day. */
internal class DateColumnType : ColumnType<LocalDate>() {
    override fun sqlType(): String = "DATE"

    // pgjdbc reads a `DATE` as a `java.sql.Date`, written `yyyy-mm-dd`.
    override fun valueFromDB(value: Any): LocalDate = LocalDate.parse(value.toString())

    // A `java.time.LocalDate`, which pgjdbc sends as the date itself, whatever the JVM's zone.
    override fun notNullValueToDB(value: LocalDate): Any = java.time.LocalDate.parse(value.toString())
}

/** The UTC day of [at]: `CAST(timezone('UTC', at) AS DATE)`, as `CAST(at AT TIME ZONE 'UTC' AS DATE)`. */
internal fun utcDay(at: Expression<*>): Expression<LocalDate> =
    Cast<LocalDate>(CustomFunction<String>("timezone", TextColumnType(), stringLiteral("UTC"), at), DateColumnType())

/** `NULLIF(value, '')`: an empty text as none. */
internal fun nullIfEmpty(value: Expression<String?>): ExpressionWithColumnType<String?> =
    CustomFunction<String?>("NULLIF", TextColumnType(), value, stringLiteral(""))

/** `CAST(json AS JSONB) ->> key`: a member of a JSON text, as text. */
internal fun jsonText(json: Expression<String>, key: Expression<*>): ExpressionWithColumnType<String?> =
    CustomOperator<String?>("->>", TextColumnType(), Cast(json, JsonbColumnType()), key)

/** A JSON text read as `JSONB`, only to cast to. */
private class JsonbColumnType : TextColumnType() {
    override fun sqlType(): String = "JSONB"
}
