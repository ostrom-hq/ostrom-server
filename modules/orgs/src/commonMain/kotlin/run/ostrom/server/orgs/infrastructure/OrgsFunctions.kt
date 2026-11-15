// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.orgs.infrastructure

import run.ostrom.server.kernel.database.InstantColumnType
import run.ostrom.server.kernel.database.instant
import run.ostrom.server.orgs.domain.Member
import run.ostrom.server.orgs.domain.Org
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.Cast
import org.jetbrains.exposed.v1.core.ColumnSet
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.UuidColumnType
import org.jetbrains.exposed.v1.core.appendTo
import org.jetbrains.exposed.v1.core.stringLiteral
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.time.Instant
import kotlin.uuid.Uuid

// The functions of `ostrom_router` (and the member search's) the module calls across organizations (OrgsMigrations
// versions 1, 3 and 7), their arguments bound by Exposed.

/** What `orgs_claim_holders(claim)` returns, read as a table: the live organizations that hold a free-plan claim. */
internal object OrgsClaimHolders : OrgRows("orgs_claim_holders") {
    /**
     * The function called for [claim].
     *
     * @param claim The claim.
     */
    fun of(claim: String): ColumnSet = FunctionCall(tableName, listOf(QueryParameter(claim, TextColumnType())))
}

/** What `orgs_find_by_slug(slug)` returns, read as a table: the live organization of a short name. */
internal object OrgsBySlug : OrgRows("orgs_find_by_slug") {
    /**
     * The function called for [slug].
     *
     * @param slug The short name.
     */
    fun of(slug: String): ColumnSet = FunctionCall(tableName, listOf(QueryParameter(slug, TextColumnType())))
}

/** What `orgs_memberships_of(user)` returns, read as a table: a person's organizations and their member rows. */
internal object OrgsMembershipsOf : OrgRows("orgs_memberships_of") {
    val memberId = uuid("member_id")
    val orgId = uuid("org_id")
    val userId = uuid("user_id")
    val role = text("role")
    val joinedAt = instant("joined_at")

    /** The member row, in the function's names. */
    val member get() = MemberColumns(memberId, orgId, userId, role, joinedAt)

    /**
     * The function called for [userId].
     *
     * @param userId The person.
     */
    fun of(userId: Uuid): ColumnSet = FunctionCall(tableName, listOf(QueryParameter(userId, UuidColumnType())))
}

/** What `orgs_live_invitation(hash, now)` returns, read as a table: the live invitation of a token. */
internal object OrgsLiveInvitation : InvitationRows("orgs_live_invitation") {
    /**
     * The function called for a token's [hash] at [now].
     *
     * @param hash The hash of the token.
     * @param now The time the invitation must still be live at.
     */
    fun of(hash: String, now: Instant): ColumnSet = FunctionCall(
        tableName,
        listOf(QueryParameter(hash, TextColumnType()), QueryParameter(now, InstantColumnType())),
    )
}

/** What `orgs_deletions_to_settle(now, limit)` returns (a set of UUIDs), as a table. */
internal object OrgsDeletionsToSettle : Table("orgs_deletions_to_settle") {
    val id = uuid("orgs_deletions_to_settle")

    /**
     * The function called.
     *
     * @param now The time a postponed deletion must be due at.
     * @param limit The most organizations.
     */
    fun of(now: Instant, limit: Int): ColumnSet = FunctionCall(
        tableName,
        listOf(QueryParameter(now, InstantColumnType()), QueryParameter(limit, IntegerColumnType())),
    )
}

/** What `orgs_due_purges(now, limit)` returns (a set of UUIDs), as a table. */
internal object OrgsDuePurges : Table("orgs_due_purges") {
    val id = uuid("orgs_due_purges")

    /**
     * The function called.
     *
     * @param now The time a purge must be due at.
     * @param limit The most organizations.
     */
    fun of(now: Instant, limit: Int): ColumnSet = FunctionCall(
        tableName,
        listOf(QueryParameter(now, InstantColumnType()), QueryParameter(limit, IntegerColumnType())),
    )
}

/**
 * `orgs_search_members(org, prefix, after, limit) WITH ORDINALITY s(member_id, n)`: the member ids of a page of the
 * search, in order, each with its place `n`. [after] goes as text, `''` for none, cast to a UUID as the function's
 * argument.
 *
 * @param orgId The session's organization.
 * @param prefix What the login or the display name starts with.
 * @param after The member the page starts after.
 * @param limit The page's size.
 */
internal class MemberSearch(
    private val orgId: Uuid,
    private val prefix: String,
    private val after: Uuid?,
    private val limit: Int,
) : Table("s") {
    val memberId = uuid("member_id")
    val n = long("n")

    override fun describe(s: Transaction, queryBuilder: QueryBuilder): Unit = queryBuilder {
        val text = QueryParameter(after?.toString().orEmpty(), TextColumnType())
        val cursor = CustomFunction("NULLIF", TextColumnType(), text, stringLiteral(""))
        val arguments = listOf(
            QueryParameter(orgId, UuidColumnType()),
            QueryParameter(prefix, TextColumnType()),
            Cast(cursor, UuidColumnType()),
            QueryParameter(limit, IntegerColumnType()),
        )
        append("orgs_search_members")
        arguments.appendTo(this, prefix = "(", postfix = ")") { +it }
        append(" WITH ORDINALITY $tableName(${memberId.name}, ${n.name})")
    }
}

/**
 * Whether `orgs_slug_taken(slug)` says a short name is taken, a deleted organization's included.
 *
 * @param slug The short name.
 *
 * @return Whether it is taken.
 */
internal fun JdbcTransaction.slugIsTaken(slug: String): Boolean {
    val taken = CustomFunction("orgs_slug_taken", BooleanColumnType(), QueryParameter(slug, TextColumnType()))
    return Table.Dual.select(taken).single()[taken]
}

/**
 * Creates an organization and its owner through `orgs_create_organization`: before the organization exists no query
 * can be confined to it.
 *
 * @param org The organization.
 * @param owner Its first member.
 * @param ownerRole The stored name of the owner's role.
 */
internal fun JdbcTransaction.createOrganization(org: Org, owner: Member, ownerRole: String) {
    val call = CustomFunction(
        "orgs_create_organization",
        TextColumnType(),
        QueryParameter(org.id, UuidColumnType()),
        QueryParameter(org.slug, TextColumnType()),
        QueryParameter(org.name, TextColumnType()),
        QueryParameter(org.githubOrgId, LongColumnType()),
        QueryParameter(org.freeClaim, TextColumnType()),
        QueryParameter(org.createdAt, InstantColumnType()),
        QueryParameter(owner.id, UuidColumnType()),
        QueryParameter(owner.userId, UuidColumnType()),
        QueryParameter(ownerRole, TextColumnType()),
        QueryParameter(owner.joinedAt, InstantColumnType()),
    )
    Table.Dual.select(call).toList()
}

/** A set-returning function called with its [arguments], read as a table of its own name. */
private class FunctionCall(name: String, private val arguments: List<Expression<*>>) : Table(name) {
    override fun describe(s: Transaction, queryBuilder: QueryBuilder): Unit = queryBuilder {
        append(tableName)
        arguments.appendTo(this, prefix = "(", postfix = ")") { +it }
    }
}
