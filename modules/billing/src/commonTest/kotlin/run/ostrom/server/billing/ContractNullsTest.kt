// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.billing

import run.ostrom.server.billing.presentation.BillingOverviewResponse
import run.ostrom.server.billing.presentation.InvoiceResponse
import run.ostrom.server.billing.presentation.InvoicesResponse
import run.ostrom.server.billing.presentation.PaymentIssueResponse
import run.ostrom.server.billing.presentation.PlanLimitsResponse
import run.ostrom.server.billing.presentation.UsageResponse
import run.ostrom.server.kernel.http.OstromJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** The billing answers write the fields `openapi.yaml` declares required and nullable as `null` (never absent). */
class ContractNullsTest {

    private val limits = PlanLimitsResponse(
        null,
        null,
        null,
        sso = false,
        policies = false,
        receiptRetentionDays = null,
    )
    private val usage = UsageResponse(0, 0, 0, null)

    private fun JsonObject.nulls(vararg keys: String) = keys.forEach { assertEquals(JsonNull, this[it], it) }

    @Test
    fun theOverviewItsLimitsUsageAndPaymentIssueWriteTheirNulls() {
        val overview = BillingOverviewResponse(
            "org", "free", "free", null, null, false, "2026-11-01", "2026-12-01", 1, 1, limits, usage, emptyList(),
            null, purchasable = true, prices = emptyList(),
        )
        val written = OstromJson.encodeToJsonElement(BillingOverviewResponse.serializer(), overview).jsonObject
        written.nulls("interval", "status", "paymentIssue")
        written.getValue("limits").jsonObject
            .nulls("maxPeople", "maxConcurrentCloudAgents", "agentHoursPerPeriod", "receiptRetentionDays")
        written.getValue("usage").jsonObject.nulls("percentOfHours")
        val issue = PaymentIssueResponse("payment_failed", null, "2026-11-02")
        OstromJson.encodeToJsonElement(PaymentIssueResponse.serializer(), issue).jsonObject.nulls("hostedInvoiceUrl")
    }

    @Test
    fun anInvoiceInItsListWritesItsNulls() {
        val invoice = InvoiceResponse("in_1", null, "draft", 0, 0, "eur", "2026-11-02", null, null)
        val written = OstromJson.encodeToJsonElement(InvoicesResponse.serializer(), InvoicesResponse(listOf(invoice)))
        val listed = written.jsonObject.getValue("invoices").jsonArray.single().jsonObject
        listed.nulls("number", "hostedInvoiceUrl", "pdfUrl")
    }
}
