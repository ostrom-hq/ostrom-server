// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.gateway

import run.ostrom.server.gateway.domain.ApiPriceChange
import run.ostrom.server.gateway.domain.ApiPriceRepository
import run.ostrom.server.gateway.domain.ApiPrices
import run.ostrom.server.gateway.domain.DatabaseOrgPrices
import run.ostrom.server.gateway.domain.GetApiPricesUseCaseImpl
import run.ostrom.server.gateway.domain.InferenceProvider
import run.ostrom.server.gateway.domain.InvalidApiPriceException
import run.ostrom.server.gateway.domain.LoadApiPriceDefaultsUseCaseImpl
import run.ostrom.server.gateway.domain.ModelPrice
import run.ostrom.server.gateway.domain.PriceTable
import run.ostrom.server.gateway.domain.PutApiPricesUseCaseImpl
import run.ostrom.server.gateway.domain.TokenUsage
import run.ostrom.server.secrets.domain.AdminRequiredException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.uuid.Uuid

/** The org's price table: administrators replace it, an org with rows is priced by them alone, rows are dated. */
class ApiPricesTest {
    private val repository = InMemoryPrices()
    private val clock = TestClock()
    private val today = ApiPrices.dayOf(clock)

    private fun row(model: String, day: Long = today, input: Long = 3_000_000, output: Long = 15_000_000) =
        ModelPrice("anthropic", model, input, 3_750_000, 300_000, output, day)

    @Test
    fun anOrgWithoutRowsIsShownTheBuiltInPricesAndPricedByThem() = runTest {
        val view = GetApiPricesUseCaseImpl(repository)(Gw.memberA)
        assertTrue(view.defaults)
        assertEquals(PriceTable.ANTHROPIC_LIST_PRICES, view.prices)
        val table = DatabaseOrgPrices(repository, clock).of(Gw.orgA)
        assertNotNull(table.priceOf(InferenceProvider.ANTHROPIC, "claude-sonnet-4-6"))
    }

    @Test
    fun anOrgWithRowsIsPricedByThemAloneSoAModelItLeftOutIsUnpriced() = runTest {
        PutApiPricesUseCaseImpl(repository)(Gw.adminA, listOf(row("claude-sonnet-4")))
        val view = GetApiPricesUseCaseImpl(repository)(Gw.memberA)
        assertFalse(view.defaults)
        val table = DatabaseOrgPrices(repository, clock).of(Gw.orgA)
        assertEquals(3_000_000, table.priceOf(InferenceProvider.CLAUDE_SHARED, "claude-sonnet-4-6")?.input)
        assertNull(table.priceOf(InferenceProvider.ANTHROPIC, "claude-opus-5"))
        assertNull(table.costMicros(InferenceProvider.ANTHROPIC, "claude-opus-5", TokenUsage(input = 1)))
        // Another org keeps the built-in prices.
        val otherOrg = DatabaseOrgPrices(repository, clock).of(Gw.orgB)
        assertNotNull(otherOrg.priceOf(InferenceProvider.ANTHROPIC, "claude-opus-5"))
    }

    @Test
    fun aRowAppliesFromItsDayAndTheLatestRowOfAModelWins() = runTest {
        PutApiPricesUseCaseImpl(repository)(
            Gw.adminA,
            listOf(
                row("claude-sonnet-4", today - 10, input = 1_000_000),
                row("claude-sonnet-4", today, input = 2_000_000),
                row("claude-sonnet-4", today + 1, input = 9_000_000),
                row("claude-opus", today + 1),
            ),
        )
        val table = DatabaseOrgPrices(repository, clock).of(Gw.orgA)
        assertEquals(2_000_000, table.priceOf(InferenceProvider.ANTHROPIC, "claude-sonnet-4-6")?.input)
        assertNull(table.priceOf(InferenceProvider.ANTHROPIC, "claude-opus-5"), "Not from today yet")
        clock.advance(1.days)
        val tomorrow = DatabaseOrgPrices(repository, clock).of(Gw.orgA)
        assertEquals(9_000_000, tomorrow.priceOf(InferenceProvider.ANTHROPIC, "claude-sonnet-4-6")?.input)
        // An exact model beats a longer-dated prefix row.
        val exact = PriceTable(listOf(row("claude-sonnet-4", today), row("claude-sonnet-4-6", today - 5, input = 7)))
        assertEquals(7, exact.priceOf(InferenceProvider.ANTHROPIC, "claude-sonnet-4-6")?.input)
    }

    @Test
    fun aTableWhoseRowsAllStartLaterLeavesTheBuiltInPricesInForce() = runTest {
        PutApiPricesUseCaseImpl(repository)(Gw.adminA, listOf(row("claude-sonnet-4", today + 3, input = 9_000_000)))
        val table = DatabaseOrgPrices(repository, clock).of(Gw.orgA)
        assertEquals(2_000_000, table.priceOf(InferenceProvider.ANTHROPIC, "claude-sonnet-5")?.input)
        assertNull(table.priceOf(InferenceProvider.ANTHROPIC, "claude-sonnet-4-6")?.takeIf { it.input == 9_000_000L })
    }

    @Test
    fun onlyAdministratorsChangeTheTable() = runTest {
        assertFailsWith<AdminRequiredException> { PutApiPricesUseCaseImpl(repository)(Gw.memberA, emptyList()) }
        assertFailsWith<AdminRequiredException> { LoadApiPriceDefaultsUseCaseImpl(repository)(Gw.memberA) }
    }

    @Test
    fun aMalformedTableIsRefusedWhole() = runTest {
        val put = PutApiPricesUseCaseImpl(repository)
        val bad = listOf(
            listOf(row("a").copy(provider = "gemini")),
            listOf(row("Claude-Sonnet")),
            listOf(row("")),
            listOf(row("a", day = -1)),
            listOf(row("a", day = 3_000_000)),
            listOf(row("a", input = 0)),
            listOf(row("a", output = 0)),
            listOf(row("a").copy(cacheRead = -1)),
            listOf(row("a").copy(cacheWrite = ApiPrices.MAX_PRICE_MICROS + 1)),
            listOf(row("a"), row("a").copy(input = 5)),
            List(ApiPrices.MAX_ROWS + 1) { row("m$it") },
        )
        for (rows in bad) assertFailsWith<InvalidApiPriceException> { put(Gw.adminA, rows) }
        assertEquals(emptyList(), repository.rows(Gw.orgA), "Nothing was stored")
        assertEquals(ApiPrices.MAX_ROWS, put(Gw.adminA, List(ApiPrices.MAX_ROWS) { row("m$it") }).after.size)
        assertEquals(emptyList(), put(Gw.adminA, emptyList()).after, "An empty table goes back to the built-in prices")
    }

    @Test
    fun aRowOfAnotherDayOrProviderIsNotTheSameRowAsADefault() = runTest {
        val earlier = ModelPrice("anthropic", "claude-opus-5", 1, 1, 1, 1, PriceTable.LIST_PRICES_DAY - 1)
        val openai = ModelPrice("openai", "claude-opus-5", 1, 1, 1, 1, PriceTable.LIST_PRICES_DAY)
        PutApiPricesUseCaseImpl(repository)(Gw.adminA, listOf(earlier, openai))
        val after = LoadApiPriceDefaultsUseCaseImpl(repository)(Gw.adminA).after
        assertEquals(PriceTable.ANTHROPIC_LIST_PRICES.size + 2, after.size, "Both are kept and the default is added")
        assertTrue(earlier in after && openai in after)
    }

    @Test
    fun loadingTheDefaultsAddsTheMissingRowsAndKeepsWhatWasTyped() = runTest {
        val typed = ModelPrice("anthropic", "claude-opus-5", 1, 1, 1, 1, PriceTable.LIST_PRICES_DAY)
        PutApiPricesUseCaseImpl(repository)(Gw.adminA, listOf(typed))
        val load = LoadApiPriceDefaultsUseCaseImpl(repository)
        val first = load(Gw.adminA)
        assertEquals(1, first.before.size)
        assertEquals(PriceTable.ANTHROPIC_LIST_PRICES.size, first.after.size)
        assertTrue(typed in first.after)
        assertEquals(first.after, load(Gw.adminA).after, "Loading twice changes nothing")
        assertEquals(ApiPrices.dateOf(PriceTable.LIST_PRICES_DAY), "2026-10-21")
        assertEquals(PriceTable.LIST_PRICES_DAY, ApiPrices.dayOf("2026-10-21"))
        assertNull(ApiPrices.dayOf("2026-13-45"))
    }
}

/** [ApiPriceRepository] in memory. */
class InMemoryPrices : ApiPriceRepository {
    private val tables = mutableMapOf<Uuid, List<ModelPrice>>()

    override suspend fun rows(orgId: Uuid): List<ModelPrice> = tables[orgId].orEmpty()

    override suspend fun mutate(orgId: Uuid, change: (List<ModelPrice>) -> List<ModelPrice>): ApiPriceChange {
        val before = rows(orgId)
        val after = change(before).sortedWith(ApiPrices.ORDER)
        tables[orgId] = after
        return ApiPriceChange(before, after)
    }
}
