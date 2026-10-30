// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.receipts

import dev.ostrom.server.receipts.domain.services.ReceiptRule
import dev.ostrom.server.receipts.domain.services.ReceiptRules
import dev.ostrom.server.receipts.domain.services.receiptRulesJson
import dev.ostrom.server.receipts.infrastructure.NoReceiptRules
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.uuid.Uuid

class ReceiptsModuleRegistrationTest {
    @Test
    fun registersUnderItsNameWithDefinitionsThatLoad() {
        assertEquals("receipts", ReceiptsModule.name)
        koinApplication { modules(ReceiptsModule.koinModule) }.close()
    }

    @Test
    fun withoutAModuleThatDecidesRulesAReceiptCitesNone() = runTest {
        val app = koinApplication { modules(ReceiptsModule.koinModule) }
        ReceiptsModule.initialize(app.koin)
        val rules = assertIs<NoReceiptRules>(app.koin.get<ReceiptRules>())
        assertEquals(emptyList(), rules.rulesFor(Uuid.random(), "acme/api"))
        app.close()
    }

    @Test
    fun theRulesOfAModuleThatDecidesThemAreKept() = runTest {
        val decided = object : ReceiptRules {
            override suspend fun rulesFor(orgId: Uuid, repo: String?) = listOf(ReceiptRule("r1", "sha256:1"))
        }
        val app = koinApplication { modules(ReceiptsModule.koinModule, module { single<ReceiptRules> { decided } }) }
        ReceiptsModule.initialize(app.koin)
        assertSame(decided, app.koin.get<ReceiptRules>())
        assertEquals(
            Json.parseToJsonElement("""[{"id":"r1","digest":"sha256:1"}]"""),
            receiptRulesJson(decided.rulesFor(Uuid.random(), null)),
        )
        app.close()
    }
}
