// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.billing

import run.ostrom.server.billing.domain.PaymentIssue
import run.ostrom.server.billing.domain.PaymentIssueKind
import run.ostrom.server.billing.domain.ProcessOutboxUseCase
import run.ostrom.server.billing.domain.StripeSettings
import run.ostrom.server.billing.fakes.NOW
import run.ostrom.server.billing.infrastructure.LoggingBillingMetrics
import run.ostrom.server.billing.infrastructure.LoggingBillingNotifier
import run.ostrom.server.billing.infrastructure.StripeAccount
import run.ostrom.server.billing.infrastructure.StripeConfig
import run.ostrom.server.kernel.logging.LogLevel
import run.ostrom.server.kernel.logging.Logger
import run.ostrom.server.kernel.settings.InstanceSettings
import run.ostrom.server.kernel.settings.SettingsValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** Records log lines. */
private class RecordingLogger(val lines: MutableList<String> = mutableListOf()) : Logger {
    override fun log(level: LogLevel, message: String, fields: Map<String, Any?>, error: Throwable?) {
        lines += "$level $message $fields"
    }

    override fun with(fields: Map<String, Any?>): Logger = this
}

class ConfigAndWorkerTest {
    private fun stored(values: Map<String, String>) = StripeAccount(
        InstanceSettings { section ->
            assertEquals("stripe", section)
            SettingsValues(values)
        },
    )

    @Test
    fun withoutAKeyAndAWebhookSecretTheInstanceIsSelfHosted() = runTest {
        assertNull(stored(emptyMap()).read())
        assertNull(stored(mapOf("secretKey" to "sk_test_x")).read(), "A key without a webhook secret is not enough")
        assertNull(stored(mapOf("secretKey" to "sk_test_x", "webhookSecret" to " , ")).read())
        assertNull(stored(mapOf("webhookSecret" to "whsec_x")).read())
    }

    @Test
    fun theStoredAccountIsTheKeyTheWebhookSecretsAndTheApi() = runTest {
        val account = stored(mapOf("secretKey" to "sk_test_x", "webhookSecret" to " whsec_a , whsec_b ,"))
        assertEquals(
            StripeConfig("sk_test_x", "https://api.stripe.com", webhookSecrets = listOf("whsec_a", "whsec_b")),
            account.read(),
        )
        val mock = stored(
            mapOf("secretKey" to "sk_test_x", "webhookSecret" to "whsec_a", "apiBase" to "http://127.0.0.1:12111"),
        )
        assertEquals("http://127.0.0.1:12111", mock.read()?.baseUrl)
    }

    @Test
    fun theConsolesStripeSectionSealsTheKeyAndTheWebhookSecret() {
        val section = StripeSettings.SECTION
        assertEquals("stripe", section.name)
        assertEquals(listOf("secretKey", "webhookSecret", "apiBase"), section.fields.map { it.name })
        assertEquals(listOf(true, true, false), section.fields.map { it.secret })
        assertEquals(listOf(true, true, false), section.fields.map { it.required })
        assertEquals(
            listOf("OSTROM_STRIPE_SECRET_KEY", "OSTROM_STRIPE_WEBHOOK_SECRET", "OSTROM_STRIPE_API_BASE"),
            section.fields.map { it.legacyVariable },
        )
        val key = checkNotNull(section.field("secretKey"))
        assertNull(key.check("sk_test_e2e"))
        assertNull(key.check("rk_live_x"))
        assertEquals("A Stripe secret key starts with sk_ or rk_.", key.check("pk_live_x"))
        val hook = checkNotNull(section.field("webhookSecret"))
        val refusal = "One or two comma-separated webhook signing secrets, each starting with whsec_."
        assertNull(hook.check("whsec_e2e_bench"))
        assertNull(hook.check("whsec_a, whsec_b"))
        assertEquals(refusal, hook.check(" , "))
        assertEquals(refusal, hook.check("whsec_a,whsec_b,whsec_c"))
        assertEquals(refusal, hook.check("whsec_a,secret"))
        val api = checkNotNull(section.field("apiBase"))
        assertNull(api.check("http://127.0.0.1:12111"))
        assertEquals("Stripe's API is an http or https URL.", api.check("api.stripe.com"))
    }

    @Test
    fun theWorkerSendsNothingWhileBillingIsOff() = runTest {
        var calls = 0
        val process = object : ProcessOutboxUseCase {
            override suspend fun invoke(limit: Int): Int {
                calls++
                return 0
            }
        }
        var enabled = false
        var checks = 0
        val worker = OutboxWorker(process, RecordingLogger(), interval = 1.seconds, passes = 2) {
            checks++
            enabled.also { enabled = true }
        }
        worker.run()
        assertEquals(2, checks)
        assertEquals(1, calls, "The second pass, once an account is stored")
    }

    @Test
    fun theWorkerSurvivesAFailedPassAndStopsAfterItsPasses() = runTest {
        val logger = RecordingLogger()
        var calls = 0
        val process = object : ProcessOutboxUseCase {
            override suspend fun invoke(limit: Int): Int {
                calls++
                if (calls == 1) error("database down")
                return 0
            }
        }
        OutboxWorker(process, logger, interval = 1.seconds, batch = 5, passes = 3).run()
        assertEquals(3, calls)
        assertEquals(listOf("ERROR billing outbox pass failed {error=IllegalStateException}"), logger.lines)
    }

    @Test
    fun theWorkerPropagatesCancellation() = runTest {
        val process = object : ProcessOutboxUseCase {
            override suspend fun invoke(limit: Int): Int = throw CancellationException("stop")
        }
        assertFailsWith<CancellationException> { OutboxWorker(process, RecordingLogger()).run() }
    }

    @Test
    fun theLoggingNotifierLogsWithoutSecrets() = runTest {
        val logger = RecordingLogger()
        val org = Uuid.parse("0195f7a2-0000-7000-8000-000000000001")
        val notifier = LoggingBillingNotifier(logger)
        notifier.agentHoursReached(org, 80, 240, 300)
        notifier.paymentIssue(org, PaymentIssue(PaymentIssueKind.FAILED, "https://pay", NOW))
        assertEquals(
            listOf(
                "INFO agent hours alert {org_id=$org, percent=80, used_hours=240, limit_hours=300}",
                "WARN payment issue {org_id=$org, kind=payment_failed}",
            ),
            logger.lines,
        )
    }

    @Test
    fun anEventForAnUnknownOrganizationIsLoggedAndCounted() {
        val logger = RecordingLogger()
        val metrics = LoggingBillingMetrics(logger)
        assertEquals(0, metrics.ignoredUnknownOrg)
        metrics.stripeEventForUnknownOrganization("evt_1", "customer.subscription.updated")
        metrics.stripeEventForUnknownOrganization("evt_2", "invoice.paid")
        assertEquals(2, metrics.ignoredUnknownOrg)
        assertEquals(
            listOf(
                "WARN stripe event ignored {outcome=ignored_unknown_org, event_id=evt_1, " +
                    "type=customer.subscription.updated, count=1}",
                "WARN stripe event ignored {outcome=ignored_unknown_org, event_id=evt_2, type=invoice.paid, count=2}",
            ),
            logger.lines,
        )
    }
}
