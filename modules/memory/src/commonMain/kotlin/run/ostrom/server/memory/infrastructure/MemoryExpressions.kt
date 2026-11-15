// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.infrastructure

import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.TextColumnType

/** `column ILIKE pattern`: the match without regard to case, PostgreSQL's (`\` escapes `%` and `_`). */
internal infix fun Expression<String>.ilike(pattern: String): Op<Boolean> = ILikeOp(this, pattern)

private class ILikeOp(private val expression: Expression<String>, private val pattern: String) : Op<Boolean>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        append(expression)
        append(" ILIKE ")
        registerArgument(TextColumnType(), pattern)
    }
}
