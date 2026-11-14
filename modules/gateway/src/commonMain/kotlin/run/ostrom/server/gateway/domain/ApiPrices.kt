// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway.domain

import run.ostrom.server.kernel.errors.ErrorKind
import run.ostrom.server.kernel.errors.OstromException
import run.ostrom.server.secrets.domain.Caller
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * The table that prices an org's model calls. Bound in Koin by the gateway module: [PriceTable] itself prices every org
 * with the built-in rows, [DatabaseOrgPrices] reads the org's own.
 */
interface OrgPrices {
    /** The table that prices the org's calls today. */
    suspend fun of(orgId: Uuid): PriceTable
}

/** The org's price table (TeamCode's `api_prices`): at most [ApiPrices.MAX_ROWS] rows, replaced whole. */
interface ApiPriceRepository {
    /** The org's rows, by provider, model and day. */
    suspend fun rows(orgId: Uuid): List<ModelPrice>

    /**
     * Changes the rows under a lock of the org, so that two administrators cannot drop each other's rows.
     *
     * @param orgId The org.
     * @param change The rows to store, from the rows stored.
     *
     * @return The rows before and after.
     */
    suspend fun mutate(orgId: Uuid, change: (List<ModelPrice>) -> List<ModelPrice>): ApiPriceChange
}

/**
 * [OrgPrices] on the stored table: an org without a row is priced by the built-in rows, an org with any only by its
 * own, so that a model it left out is unpriced as TeamCode's. The rows of a later day wait for it.
 *
 * @param repository The rows.
 * @param clock The time.
 */
class DatabaseOrgPrices(private val repository: ApiPriceRepository, private val clock: Clock) : OrgPrices {
    override suspend fun of(orgId: Uuid): PriceTable {
        val rows = repository.rows(orgId)
        if (rows.isEmpty()) return PriceTable()
        val today = ApiPrices.dayOf(clock)
        return PriceTable(rows.filter { it.effectiveFromDay <= today })
    }
}

/** A change of the table: the rows before and after. */
data class ApiPriceChange(val before: List<ModelPrice>, val after: List<ModelPrice>)

/** An org's table, as `getApiPrices` shows it. */
data class ApiPriceTableView(val prices: List<ModelPrice>, val defaults: Boolean)

/** Reads the org's price table (any member). */
interface GetApiPricesUseCase {
    /** The rows, or the built-in ones when the org has none. */
    suspend operator fun invoke(caller: Caller): ApiPriceTableView
}

/** Replaces the org's price table (administrators). */
interface PutApiPricesUseCase {
    /** Validates and stores [prices]; the rows before and after. */
    suspend operator fun invoke(caller: Caller, prices: List<ModelPrice>): ApiPriceChange
}

/** Adds the built-in rows the org's table lacks (administrators). */
interface LoadApiPriceDefaultsUseCase {
    /** The rows before and after. */
    suspend operator fun invoke(caller: Caller): ApiPriceChange
}

/** [GetApiPricesUseCase] on the repository. */
class GetApiPricesUseCaseImpl(private val repository: ApiPriceRepository) : GetApiPricesUseCase {
    override suspend fun invoke(caller: Caller): ApiPriceTableView {
        val rows = repository.rows(caller.orgId)
        return if (rows.isEmpty()) {
            ApiPriceTableView(PriceTable.ANTHROPIC_LIST_PRICES, true)
        } else {
            ApiPriceTableView(rows, false)
        }
    }
}

/** [PutApiPricesUseCase] on the repository. */
class PutApiPricesUseCaseImpl(private val repository: ApiPriceRepository) : PutApiPricesUseCase {
    override suspend fun invoke(caller: Caller, prices: List<ModelPrice>): ApiPriceChange {
        caller.requireAdmin()
        val next = ApiPrices.validate(prices)
        return repository.mutate(caller.orgId) { next }
    }
}

/** [LoadApiPriceDefaultsUseCase] on the repository. */
class LoadApiPriceDefaultsUseCaseImpl(private val repository: ApiPriceRepository) : LoadApiPriceDefaultsUseCase {
    override suspend fun invoke(caller: Caller): ApiPriceChange {
        caller.requireAdmin()
        return repository.mutate(caller.orgId) { current ->
            ApiPrices.validate(ApiPrices.withMissing(current, PriceTable.ANTHROPIC_LIST_PRICES))
        }
    }
}

/** A malformed price table (`invalid_api_price`, 400). */
class InvalidApiPriceException(message: String) :
    OstromException(ErrorKind.INVALID_REQUEST, message, "invalid_api_price")

/** Validation of an org's price table (TeamCode's `ApiPrices`). */
object ApiPrices {
    /** Rows an org may keep. */
    const val MAX_ROWS = 200

    /** $1 000 000 a million tokens: far above any list price, low enough that a day of tokens cannot overflow. */
    const val MAX_PRICE_MICROS = 1_000_000_000_000L

    private val PROVIDERS = setOf("anthropic", "openai")
    private val MODEL = Regex("[a-z0-9_.:/-]{1,128}")
    private const val MAX_DAY = 2_932_896L

    /** The UTC day number of now. */
    fun dayOf(clock: Clock): Long = clock.now().toLocalDateTime(TimeZone.UTC).date.toEpochDays()

    /** The UTC day number of a `YYYY-MM-DD` date, null when it is not one. */
    fun dayOf(date: String): Long? = runCatching { LocalDate.parse(date).toEpochDays() }.getOrNull()

    /** The date of a UTC day number, `YYYY-MM-DD`. */
    fun dateOf(day: Long): String = LocalDate.fromEpochDays(day).toString()

    /**
     * Refuses a malformed table.
     *
     * @return The rows, sorted by provider, model and day.
     */
    fun validate(prices: List<ModelPrice>): List<ModelPrice> {
        fun refuse(message: String): Nothing = throw InvalidApiPriceException(message)
        if (prices.size > MAX_ROWS) refuse("At most $MAX_ROWS price rows.")
        for (price in prices) {
            if (price.provider !in PROVIDERS) refuse("Use a provider of anthropic or openai.")
            if (!MODEL.matches(price.model)) {
                refuse("Use a lowercase model or family prefix of letters, digits, '.', '-', '_', ':' or '/'.")
            }
            if (price.effectiveFromDay !in 0..MAX_DAY) refuse("Use an effective date written YYYY-MM-DD.")
            val all = listOf(price.input, price.cacheWrite, price.cacheRead, price.output)
            if (all.any { it !in 0..MAX_PRICE_MICROS }) {
                refuse("Prices are USD millionths per million tokens, from 0 to $MAX_PRICE_MICROS.")
            }
            // A free model would count tokens at no cost: input and output need a price.
            if (price.input == 0L || price.output == 0L) refuse("Input and output prices must be above zero.")
        }
        if (prices.groupBy { Triple(it.provider, it.model, it.effectiveFromDay) }.any { it.value.size > 1 }) {
            refuse("Each provider, model and effective date has one row.")
        }
        return prices.sortedWith(ORDER)
    }

    /** [current] plus the rows of [list] it lacks: a row there for the same provider, model and day stays as typed. */
    fun withMissing(current: List<ModelPrice>, list: List<ModelPrice>): List<ModelPrice> =
        current + list.filterNot { row -> current.any { it.sameKey(row) } }

    /** The order of the stored rows. */
    val ORDER: Comparator<ModelPrice> = compareBy({ it.provider }, { it.model }, { it.effectiveFromDay })
}

private fun ModelPrice.sameKey(other: ModelPrice) =
    provider == other.provider && model == other.model && effectiveFromDay == other.effectiveFromDay
