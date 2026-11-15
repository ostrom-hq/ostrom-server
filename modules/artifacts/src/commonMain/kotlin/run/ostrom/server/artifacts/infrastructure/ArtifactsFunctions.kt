// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.infrastructure

import run.ostrom.server.kernel.database.InstantColumnType
import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.appendTo
import kotlin.time.Instant
import kotlin.uuid.Uuid

// The functions of `ostrom_router` the module calls across organizations (ArtifactsMigrations versions 2, 12 and 13),
// their arguments bound by Exposed.

/** What `artifacts_route_by_id(id)` returns, read as a table: an artifact's organization. */
internal object ArtifactsRouteById : Table("artifacts_route_by_id") {
    val artifactId = uuid("artifact_id")
    val orgId = uuid("org_id")

    /**
     * The function called for the artifact [id].
     *
     * @param id The artifact.
     */
    fun of(id: Uuid): ColumnSet = FunctionCall(tableName, listOf(QueryParameter(id, UuidColumnType())))
}

/** What `artifacts_route_by_slug(slug)` returns, read as a table: the artifact a link slug opens. */
internal object ArtifactsRouteBySlug : Table("artifacts_route_by_slug") {
    val artifactId = uuid("artifact_id")
    val orgId = uuid("org_id")

    /**
     * The function called for the link [slug].
     *
     * @param slug The slug.
     */
    fun of(slug: String): ColumnSet = FunctionCall(tableName, listOf(QueryParameter(slug, TextColumnType())))
}

/** What `artifacts_due_organizations(now, drafts, publications, limit)` returns (a set of UUIDs), as a table. */
internal object ArtifactsDueOrganizations : Table("artifacts_due_organizations") {
    val orgId = uuid("artifacts_due_organizations")

    /**
     * The function called.
     *
     * @param now The time expired uploads and artifacts are measured at.
     * @param drafts Drafts created before are due.
     * @param publications Publications made before are due.
     * @param limit The most organizations.
     */
    fun of(now: Instant, drafts: Instant, publications: Instant, limit: Int): ColumnSet = FunctionCall(
        tableName,
        listOf(
            QueryParameter(now, InstantColumnType()),
            QueryParameter(drafts, InstantColumnType()),
            QueryParameter(publications, InstantColumnType()),
            QueryParameter(limit, IntegerColumnType()),
        ),
    )
}

/** A set-returning function called with its [arguments], read as a table of its own name. */
private class FunctionCall(name: String, private val arguments: List<Expression<*>>) : Table(name) {
    override fun describe(s: Transaction, queryBuilder: QueryBuilder): Unit = queryBuilder {
        append(tableName)
        arguments.appendTo(this, prefix = "(", postfix = ")") { +it }
    }
}
