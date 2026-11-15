// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.infrastructure

import run.ostrom.server.gateway.domain.ApiPriceChange
import run.ostrom.server.gateway.domain.ApiPriceRepository
import run.ostrom.server.gateway.domain.ApiPrices
import run.ostrom.server.gateway.domain.ModelPrice
import run.ostrom.server.kernel.database.JdbcDatabase
import run.ostrom.server.kernel.database.lockUntilCommit
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Query
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import kotlin.uuid.Uuid

/**
 * [ApiPriceRepository] on PostgreSQL through Exposed ([GatewayApiPrices]). Every query runs confined to the org it
 * names ([JdbcDatabase.inOrg]) and filters on it.
 *
 * @param database The JDBC pool.
 */
class ApiPriceRepositoryImpl(private val database: JdbcDatabase) : ApiPriceRepository {
    override suspend fun rows(orgId: Uuid): List<ModelPrice> = database.inOrg(orgId) { read(orgId) }

    override suspend fun mutate(orgId: Uuid, change: (List<ModelPrice>) -> List<ModelPrice>): ApiPriceChange =
        database.inOrg(orgId) {
            lockUntilCommit("gateway_api_prices:$orgId")
            val before = read(orgId)
            val after = change(before).sortedWith(ApiPrices.ORDER)
            if (before != after) {
                GatewayApiPrices.deleteWhere { GatewayApiPrices.orgId eq orgId }
                GatewayApiPrices.batchInsert(after) { price ->
                    this[GatewayApiPrices.orgId] = orgId
                    this[GatewayApiPrices.provider] = price.provider
                    this[GatewayApiPrices.model] = price.model
                    this[GatewayApiPrices.effectiveFromDay] = price.effectiveFromDay
                    this[GatewayApiPrices.inputMicros] = price.input
                    this[GatewayApiPrices.cacheWriteMicros] = price.cacheWrite
                    this[GatewayApiPrices.cacheReadMicros] = price.cacheRead
                    this[GatewayApiPrices.outputMicros] = price.output
                }
            }
            ApiPriceChange(before, after)
        }

    private fun read(orgId: Uuid): List<ModelPrice> =
        pricesRead(orgId).map { it.toPrice() }.sortedWith(ApiPrices.ORDER)
}

/** The org's price table: at most [ApiPrices.MAX_ROWS] rows, by the primary key's prefix; read before every call. */
internal fun pricesRead(orgId: Uuid): Query {
    val t = GatewayApiPrices
    return t.select(
        t.provider,
        t.model,
        t.effectiveFromDay,
        t.inputMicros,
        t.cacheWriteMicros,
        t.cacheReadMicros,
        t.outputMicros,
    )
        .where { t.orgId eq orgId }
        .orderBy(t.provider to SortOrder.ASC, t.model to SortOrder.ASC, t.effectiveFromDay to SortOrder.ASC)
        .limit(ApiPrices.MAX_ROWS)
}

private fun ResultRow.toPrice(): ModelPrice {
    val t = GatewayApiPrices
    return ModelPrice(
        provider = this[t.provider],
        model = this[t.model],
        input = this[t.inputMicros],
        cacheWrite = this[t.cacheWriteMicros],
        cacheRead = this[t.cacheReadMicros],
        output = this[t.outputMicros],
        effectiveFromDay = this[t.effectiveFromDay],
    )
}
