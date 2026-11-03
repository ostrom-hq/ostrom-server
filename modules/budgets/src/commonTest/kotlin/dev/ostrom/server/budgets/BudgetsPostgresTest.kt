// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.budgets

import dev.ostrom.server.budgets.domain.BudgetAction
import dev.ostrom.server.budgets.domain.BudgetAlert
import dev.ostrom.server.budgets.domain.BudgetAlerts
import dev.ostrom.server.budgets.domain.BudgetDecision
import dev.ostrom.server.budgets.domain.BudgetGuard
import dev.ostrom.server.budgets.domain.BudgetLimit
import dev.ostrom.server.budgets.domain.BudgetPeriod
import dev.ostrom.server.budgets.domain.BudgetScope
import dev.ostrom.server.budgets.domain.BudgetTarget
import dev.ostrom.server.budgets.domain.BudgetWindow
import dev.ostrom.server.budgets.domain.BudgetsRepository
import dev.ostrom.server.budgets.domain.ExceededBudget
import dev.ostrom.server.budgets.presentation.BudgetDto
import dev.ostrom.server.budgets.presentation.BudgetsPage
import dev.ostrom.server.budgets.presentation.MyBudgetResponse
import dev.ostrom.server.gateway.domain.SpendDecision
import dev.ostrom.server.gateway.domain.SpendGuard
import dev.ostrom.server.gateway.domain.SpendRequest
import dev.ostrom.server.kernel.audit.OrgAuditRecord
import dev.ostrom.server.kernel.audit.OrgAuditTrail
import dev.ostrom.server.kernel.workflows.WorkflowDirectory
import dev.ostrom.server.kernel.workflows.WorkflowRemovals
import dev.ostrom.server.secrets.SecretsModule
import dev.ostrom.server.secrets.domain.SecretCipher
import dev.ostrom.server.secrets.infrastructure.AesGcmSecretCipher
import dev.ostrom.server.secrets.infrastructure.executeInOrg
import dev.ostrom.server.secrets.infrastructure.fetchInOrg
import dev.ostrom.server.testing.TestServer
import dev.ostrom.server.testing.serverTest
import dev.ostrom.server.usage.UsageModule
import dev.ostrom.server.usage.domain.UsageEvent
import dev.ostrom.server.usage.domain.UsageKind
import dev.ostrom.server.usage.domain.UsageRecorder
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Real PostgreSQL and real HTTP: the limits, the guard on the real ledger, alerts once, roles and org isolation. */
class BudgetsPostgresTest {
    private val clock = TestClock()
    private val alerts = RecordingAlerts()
    private val audit = mutableListOf<OrgAuditRecord>()
    private val overrides: Module = module {
        includes(TestCallers.overrides(clock))
        single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        single<BudgetAlerts> { alerts }
        single<OrgAuditTrail> { OrgAuditTrail { audit += it } }
    }
    private val modules = listOf(SecretsModule, UsageModule, BudgetsModule)

    // The org comes from the token: admin-a and member-a are in org A, admin-b in org B.
    private val base = "/api/v1/budgets"
    private val member = TestCallers.memberA.memberId

    private suspend fun TestServer.putBudget(token: String, budget: BudgetDto): HttpResponse = client.put(base) {
        bearerAuth(token)
        contentType(ContentType.Application.Json)
        setBody(budget)
    }

    private suspend fun TestServer.budgets(token: String = "admin-a") =
        client.get(base) { bearerAuth(token) }.body<BudgetsPage>()

    private suspend fun TestServer.spend(
        costMicros: Long,
        memberId: Uuid?,
        at: Instant = TestCallers.epoch,
        orgId: Uuid = TestCallers.orgA,
        workflowId: String? = null,
    ) {
        koin.get<UsageRecorder>().record(
            UsageEvent(
                Uuid.random(),
                orgId,
                memberId,
                UsageKind.MODEL,
                "claude-sonnet-4-5",
                costMicros = costMicros,
                at = at,
                workflowId = workflowId,
            ),
        )
    }

    @Test
    fun withoutAnAlertsModuleTheGuardStillDecidesOnAMemberOverride() = serverTest(
        modules,
        module {
            includes(TestCallers.overrides(clock))
            single<SecretCipher> { AesGcmSecretCipher(ByteArray(32) { 7 }) }
        },
    ) { server ->
        val repository = server.koin.get<BudgetsRepository>()
        val half = listOf(BudgetLimit(500_000))
        repository.replaceLimits(TestCallers.orgA, BudgetTarget.DefaultMember, half, TestCallers.epoch)
        val own = listOf(BudgetLimit(2_000_000))
        repository.replaceLimits(TestCallers.orgA, BudgetTarget.Member(member), own, TestCallers.epoch)
        assertEquals(2_000_000, repository.limits(TestCallers.orgA, member).memberLimitMicros, "The override wins")
        server.spend(1_900_000, member)
        assertEquals(
            BudgetDecision.Allowed(100_000),
            server.koin.get<BudgetGuard>().check(TestCallers.orgA, member),
        )
    }

    @Test
    fun anAdminManagesTheLimitsAndTheOverrides() = serverTest(modules, overrides) { server ->
        val org = BudgetDto("organization", usdPerMonth = 100.0)
        val saved = server.putBudget("admin-a", org)
        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
        assertEquals(org, saved.body<BudgetDto>())
        assertEquals(
            BudgetDto("member", usdPerMonth = 20.0),
            server.putBudget("admin-a", BudgetDto("MEMBER", usdPerMonth = 20.0)).body<BudgetDto>(),
            "The scope is answered as the API lists it",
        )
        server.putBudget("admin-a", BudgetDto("member", member.toString(), 50.0))
        server.putBudget("admin-a", BudgetDto("member", member.toString(), 40.0))
        server.spend(3_000_000, member)
        server.spend(9_000_000, member, at = TestCallers.monthStart - 1.days)

        val budgets = server.budgets()
        assertEquals(
            listOf(
                BudgetDto("organization", usdPerMonth = 100.0),
                BudgetDto("member", usdPerMonth = 20.0),
                BudgetDto("member", member.toString(), 40.0),
            ),
            budgets.items,
            "An override is replaced",
        )
        assertEquals(3.0, budgets.orgSpentUsd, "Only this month counts")
        assertEquals("2026-10-01T00:00:00Z", budgets.periodStart)
        assertEquals("2026-11-01T00:00:00Z", budgets.resetsAt)

        repeat(2) {
            val removed = server.putBudget("admin-a", BudgetDto("member", member.toString()))
            assertEquals(HttpStatusCode.OK, removed.status, "Removing an absent override is a no-op")
        }
        server.putBudget("admin-a", BudgetDto("org"))
        assertEquals(listOf(BudgetDto("member", usdPerMonth = 20.0)), server.budgets().items)
        server.putBudget("admin-a", BudgetDto("member"))
        assertEquals(emptyList(), server.budgets().items)
    }

    @Test
    fun aMemberSeesTheirOwnBudgetButCannotChangeAny() = serverTest(modules, overrides) { server ->
        server.putBudget("admin-a", BudgetDto("member", usdPerMonth = 1.0))
        server.spend(400_000, member)
        server.spend(900_000, TestCallers.adminA.memberId)

        val mine = server.client.get("$base/me") { bearerAuth("member-a") }.body<MyBudgetResponse>()
        assertEquals(MyBudgetResponse(1_000_000, 400_000, 600_000, "2026-11-01T00:00:00Z", exhausted = false), mine)

        assertEquals(HttpStatusCode.Forbidden, server.client.get(base) { bearerAuth("member-a") }.status)
        assertEquals(
            HttpStatusCode.Forbidden,
            server.putBudget("member-a", BudgetDto("organization", null, 1.0)).status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            server.putBudget("member-a", BudgetDto("member", member.toString(), 9.0)).status,
        )
        assertEquals(HttpStatusCode.Unauthorized, server.client.get("$base/me").status)
        val refused = listOf(
            BudgetDto("organization", usdPerMonth = -1.0),
            BudgetDto("organization", usdPerDay = -1.0, hardStop = false),
            BudgetDto("workflow", null, 1.0),
            BudgetDto("workflow", " ", 1.0),
            BudgetDto("team", "deploy", 1.0),
            BudgetDto("member", "not-a-uuid", 1.0),
        )
        for (budget in refused) {
            assertEquals(HttpStatusCode.BadRequest, server.putBudget("admin-a", budget).status, budget.toString())
        }
        assertEquals(
            HttpStatusCode.NotFound,
            server.putBudget("admin-a", BudgetDto("workflow", "deploy", 1.0)).status,
            "A server without workflows knows none",
        )
        assertEquals(
            listOf(BudgetDto("member", usdPerMonth = 1.0)),
            server.budgets().items,
            "A refused budget changes nothing",
        )
    }

    @Test
    fun eachRouteChecksTheCredentialAndItsScope() = serverTest(modules, overrides) { server ->
        val budget = BudgetDto("organization", usdPerMonth = 5.0)
        assertEquals(HttpStatusCode.Unauthorized, server.client.get(base).status)
        for (token in listOf("stranger")) {
            assertEquals(HttpStatusCode.NotFound, server.client.get(base) { bearerAuth(token) }.status)
            assertEquals(HttpStatusCode.NotFound, server.putBudget(token, budget).status)
            assertEquals(HttpStatusCode.NotFound, server.client.get("$base/me") { bearerAuth(token) }.status)
        }
        for (token in listOf("device-a", "agent-a", "cli-write", "cli-other")) {
            assertEquals(HttpStatusCode.Forbidden, server.client.get(base) { bearerAuth(token) }.status, token)
        }
        for (token in listOf("device-a", "agent-a", "cli-read", "cli-other")) {
            assertEquals(HttpStatusCode.Forbidden, server.putBudget(token, budget).status, token)
        }
        for (token in listOf("device-a", "cli-write", "cli-other")) {
            assertEquals(HttpStatusCode.Forbidden, server.client.get("$base/me") { bearerAuth(token) }.status, token)
        }
        // S2-52: a personal token holding budgets:write is still not a console session.
        assertEquals(HttpStatusCode.Forbidden, server.putBudget("cli-write", budget).status)
        assertEquals(HttpStatusCode.OK, server.putBudget("admin-a", budget).status)
        assertEquals(listOf(budget), server.budgets("cli-read").items)
        assertEquals(HttpStatusCode.OK, server.client.get("$base/me") { bearerAuth("agent-a") }.status)
        assertEquals(HttpStatusCode.OK, server.client.get("$base/me") { bearerAuth("cli-read") }.status)
    }

    @Test
    fun theGuardStopsSpendingAtTheLimitAndAlertsOnce() = serverTest(modules, overrides) { server ->
        server.putBudget("admin-a", BudgetDto("organization", usdPerMonth = 1.0))
        val guard = server.koin.get<BudgetGuard>()
        assertEquals(BudgetDecision.Allowed(1_000_000), guard.check(TestCallers.orgA, member))

        server.spend(850_000, member)
        clock.advance(5.seconds)
        assertEquals(BudgetDecision.Allowed(150_000), guard.check(TestCallers.orgA, member))
        assertEquals(listOf(80), alerts.alerts.map { it.thresholdPercent })

        server.spend(150_000, TestCallers.adminA.memberId)
        clock.advance(5.seconds)
        val exhausted = BudgetDecision.Exhausted(BudgetScope.ORG, 1_000_000, TestCallers.nextMonth)
        assertEquals(exhausted, guard.check(TestCallers.orgA, member))
        assertEquals(exhausted, guard.check(TestCallers.orgA, TestCallers.adminA.memberId))
        assertEquals(
            listOf(80, 100),
            alerts.alerts.map { it.thresholdPercent },
            "Each threshold once, for the whole org",
        )

        val mine = server.client.get("$base/me") { bearerAuth("member-a") }.body<MyBudgetResponse>()
        assertEquals(true, mine.exhausted)
        assertEquals("org", mine.exhaustedScope)

        server.putBudget("admin-a", BudgetDto("organization", usdPerMonth = 2.0))
        assertEquals(
            BudgetDecision.Allowed(1_000_000),
            guard.check(TestCallers.orgA, member),
            "A raised limit applies at once",
        )
    }

    @Test
    fun aMembersBudgetIsByTheDayOrTheMonthStopsOrWarnsAndIsReplacedWhole() = serverTest(modules, overrides) { server ->
        val daily = BudgetDto("member", member.toString(), usdPerDay = 20.0, hardStop = false)
        val saved = server.putBudget("admin-a", daily)
        assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
        assertEquals(daily, saved.body<BudgetDto>())
        val org = BudgetDto("organization", usdPerMonth = 100.0, usdPerDay = 10.0)
        assertEquals(HttpStatusCode.OK, server.putBudget("admin-a", org).status)
        assertEquals(listOf(org, daily), server.budgets().items)

        val monthly = BudgetDto("member", member.toString(), usdPerMonth = 5.0)
        server.putBudget("admin-a", monthly)
        assertEquals(listOf(org, monthly), server.budgets().items, "Setting a budget replaces the subject's whole")
        server.putBudget("admin-a", BudgetDto("member", member.toString()))
        assertEquals(listOf(org), server.budgets().items)

        val changed = audit.filter { it.event == "budget.changed" }.map { it.details }
        assertEquals(
            mapOf("scope" to "member", "usd_per_day" to "20.0", "action" to "warn"),
            changed.first(),
            "A daily amount and a budget that only warns are in the audit log",
        )
        assertEquals(mapOf("scope" to "organization", "usd_per_month" to "100.0", "usd_per_day" to "10.0"), changed[1])
    }

    @Test
    fun aDailyBudgetThatStopsRefusesUntilTomorrowAndOneThatWarnsLetsTheCallGoAndIsAudited() =
        serverTest(modules, overrides) { server ->
            server.putBudget("admin-a", BudgetDto("member", member.toString(), usdPerDay = 1.0))
            server.spend(5_000_000, member, at = TestCallers.epoch - 1.days)
            server.spend(400_000, member)
            val guard = server.koin.get<BudgetGuard>()
            assertEquals(
                BudgetDecision.Allowed(600_000),
                guard.check(TestCallers.orgA, member),
                "Yesterday's spend does not count for today",
            )

            server.spend(600_000, member)
            clock.advance(5.seconds)
            val stopped = guard.check(TestCallers.orgA, member)
            assertIs<BudgetDecision.Exhausted>(stopped)
            assertEquals(BudgetScope.MEMBER, stopped.scope)
            assertEquals(1_000_000L, stopped.limitMicros)
            assertEquals(Instant.parse("2026-10-31T00:00:00Z"), stopped.resetsAt, "Tomorrow, 00:00 UTC")

            assertEquals(
                HttpStatusCode.OK,
                server.putBudget("admin-a", BudgetDto("member", member.toString(), usdPerDay = 1.0, hardStop = false))
                    .status,
            )
            assertEquals(
                BudgetDecision.Allowed(null),
                guard.check(TestCallers.orgA, member),
                "A budget that warns never stops a call",
            )
            guard.check(TestCallers.orgA, member)
            val exceeded = audit.filter { it.event == "budget.exceeded" }
            assertEquals(1, exceeded.size, "Once a day for the limit, however many calls")
            val line = exceeded.single()
            assertEquals(TestCallers.orgA, line.orgId)
            assertEquals("budget" to member.toString(), line.subjectType to line.subjectId)
            assertEquals(
                mapOf(
                    "scope" to "member",
                    "period" to "day",
                    "action" to "warn",
                    "limit_usd" to "1",
                    "spent_usd" to "1",
                    "period_start" to "2026-10-30T00:00:00Z",
                ),
                line.details,
            )
            assertEquals(listOf(80, 100, 80, 100), alerts.alerts.map { it.thresholdPercent }, "Stopping, then warning")

            clock.advance(1.days)
            assertEquals(
                BudgetDecision.Allowed(null),
                guard.check(TestCallers.orgA, member),
                "A new day starts from nothing",
            )
            assertEquals(1, audit.count { it.event == "budget.exceeded" })
        }

    @Test
    fun aWorkflowsBudgetStopsOrWarnsTheCallsOfItsLaunchesInItsOrgOnly() = serverTest(
        modules,
        module {
            includes(overrides)
            single<WorkflowDirectory> {
                val known = setOf(
                    TestCallers.orgA to "review",
                    TestCallers.orgA to "nightly",
                    TestCallers.orgB to "review",
                )
                WorkflowDirectory { org, id -> id.takeIf { org to id in known } }
            }
        },
    ) { server ->
        val orgA = TestCallers.orgA
        assertEquals(HttpStatusCode.OK, server.putBudget("admin-a", BudgetDto("workflow", "review", 1.0)).status)
        assertEquals(
            HttpStatusCode.NotFound,
            server.putBudget("admin-a", BudgetDto("workflow", "ghost", 1.0)).status,
            "Not one of the org's workflows",
        )
        assertEquals(HttpStatusCode.OK, server.putBudget("admin-b", BudgetDto("workflow", "review", 5.0)).status)
        assertEquals(
            HttpStatusCode.NotFound,
            server.putBudget("admin-b", BudgetDto("workflow", "nightly", 5.0)).status,
            "Org A's workflow is unknown to org B",
        )
        assertEquals(listOf(BudgetDto("workflow", "review", 1.0)), server.budgets().items)
        assertEquals(listOf(BudgetDto("workflow", "review", 5.0)), server.budgets("admin-b").items, "Org B's own")
        val changed = audit.last { it.event == "budget.changed" && it.orgId == orgA }
        assertEquals("review" to "workflow", changed.subjectId to changed.details["scope"])

        server.spend(600_000, member, workflowId = "review")
        server.spend(400_000, TestCallers.adminA.memberId, workflowId = "review")
        server.spend(9_000_000, member)
        val guard = server.koin.get<BudgetGuard>()
        val stopped = guard.check(orgA, member, "review")
        assertIs<BudgetDecision.Exhausted>(stopped)
        assertEquals(BudgetScope.WORKFLOW to 1_000_000L, stopped.scope to stopped.limitMicros)
        assertEquals(BudgetDecision.Allowed(null), guard.check(orgA, member), "A call that plays no workflow")
        assertEquals(
            BudgetDecision.Allowed(5_000_000),
            guard.check(TestCallers.orgB, TestCallers.adminB.memberId, "review"),
            "Org B's workflow of the same id spent nothing",
        )
        val refused = server.koin.get<SpendGuard>().check(SpendRequest(orgA, member, Uuid.random(), 1, "review"))
        assertIs<SpendDecision.Exhausted>(refused)
        assertTrue("this workflow's limit of $1.00" in refused.message, refused.message)
        val exceeded = server.koin.get<BudgetsRepository>().exceeded(
            orgA,
            null,
            BudgetPeriod.day(clock.now()).start,
            BudgetPeriod.of(clock.now()).start,
            10,
        )
        assertEquals(listOf<String?>("review"), exceeded.map { it.workflowId })

        val nightly = BudgetDto("workflow", "nightly", usdPerDay = 0.5, hardStop = false)
        assertEquals(HttpStatusCode.OK, server.putBudget("admin-a", nightly).status)
        server.spend(500_000, member, workflowId = "nightly")
        assertEquals(BudgetDecision.Allowed(null), guard.check(orgA, member, "nightly"), "Warns, never stops")
        val line = audit.single { it.event == "budget.exceeded" }
        assertEquals("nightly", line.subjectId)
        assertEquals(listOf("workflow", "day", "warn"), listOf("scope", "period", "action").map { line.details[it] })

        assertEquals(HttpStatusCode.OK, server.putBudget("admin-a", BudgetDto("workflow", "review")).status)
        assertEquals(HttpStatusCode.OK, server.putBudget("admin-a", BudgetDto("workflow", "ghost")).status)
        assertEquals(listOf(nightly), server.budgets().items, "Removed; a removal never asks for the workflow")
        assertEquals(listOf(BudgetDto("workflow", "review", 5.0)), server.budgets("admin-b").items)

        // A deleted workflow's budget goes with it, both windows, and its alerts: org B's of the same id stays.
        val both = BudgetDto("workflow", "nightly", usdPerMonth = 9.0, usdPerDay = 0.5, hardStop = false)
        assertEquals(HttpStatusCode.OK, server.putBudget("admin-a", both).status)
        assertEquals(listOf(both), server.budgets().items, "Listed whole, both windows")
        server.koin.get<WorkflowRemovals>().removed(orgA, "nightly")
        server.koin.get<WorkflowRemovals>().removed(orgA, "review")
        assertEquals(emptyList(), server.budgets().items)
        assertEquals(BudgetDecision.Allowed(null), guard.check(orgA, member, "nightly"), "Its cached decision too")
        val alertsLeft = Statement.create("SELECT COUNT(*) AS n FROM budgets_alerts WHERE workflow_id IS NOT NULL")
        assertEquals(0L, server.driver.fetchInOrg(orgA, alertsLeft).getOrThrow().first().get("n").asLong())
        assertEquals(listOf(BudgetDto("workflow", "review", 5.0)), server.budgets("admin-b").items)
    }

    @Test
    fun anAlertIsMarkedOnceInTheDatabase() = serverTest(modules, overrides) { server ->
        val repository = server.koin.get<BudgetsRepository>()
        val alert = BudgetAlert(TestCallers.orgA, null, BudgetScope.ORG, 80, 1_000, 900, TestCallers.monthStart)
        val memberAlert = alert.copy(memberId = member, scope = BudgetScope.MEMBER)

        assertEquals(true, repository.markAlerted(alert, TestCallers.epoch))
        assertEquals(false, repository.markAlerted(alert, TestCallers.epoch), "Another replica sees it was sent")
        assertEquals(true, repository.markAlerted(memberAlert, TestCallers.epoch))
        assertEquals(false, repository.markAlerted(memberAlert, TestCallers.epoch))
        assertEquals(true, repository.markAlerted(alert.copy(thresholdPercent = 100), TestCallers.epoch))
        assertEquals(true, repository.markAlerted(alert.copy(limitMicros = 2_000), TestCallers.epoch), "A new limit")

        repository.unmarkAlerted(alert)
        repository.unmarkAlerted(memberAlert)
        assertEquals(true, repository.markAlerted(alert, TestCallers.epoch), "A failed alert is sent again")
        assertEquals(true, repository.markAlerted(memberAlert, TestCallers.epoch))
    }

    @Test
    fun theLimitsReachedAreThoseTheGuardSawThatStillHoldInTheirOrgOnly() = serverTest(modules, overrides) { server ->
        val repository = server.koin.get<BudgetsRepository>()
        val at = TestCallers.epoch
        val warn = BudgetLimit(1_000_000, BudgetWindow.DAY, BudgetAction.WARN)
        repository.replaceLimits(TestCallers.orgA, BudgetTarget.DefaultMember, listOf(warn), at)
        repository.replaceLimits(TestCallers.orgA, BudgetTarget.Org, listOf(BudgetLimit(1_000_000)), at)
        server.spend(1_000_000, member)
        server.koin.get<BudgetGuard>().check(TestCallers.orgA, member)

        val day = BudgetPeriod.day(at).start
        suspend fun exceeded(memberId: Uuid?, max: Int = 10, org: Uuid = TestCallers.orgA) =
            repository.exceeded(org, memberId, day, TestCallers.monthStart, max)
        val org = ExceededBudget(BudgetScope.ORG, null, BudgetLimit(1_000_000), at)
        val mine = ExceededBudget(BudgetScope.MEMBER, member, warn, at)
        assertEquals(listOf(org, mine), exceeded(null), "The org's first")
        assertEquals(listOf(mine), exceeded(member))
        assertEquals(listOf(org), exceeded(null, max = 1))
        assertEquals(emptyList(), exceeded(null, org = TestCallers.orgB), "Another org sees none")
        val tomorrow = repository.exceeded(TestCallers.orgA, null, day + 1.days, TestCallers.monthStart, 10)
        assertEquals(listOf(org), tomorrow, "Tomorrow, today's are past; the month's still holds")

        // Limits of their own replace the default: its alert no longer holds for the member.
        repository.replaceLimits(TestCallers.orgA, BudgetTarget.Member(member), listOf(BudgetLimit(5_000_000)), at)
        assertEquals(listOf(org), exceeded(null))
    }

    @Test
    fun oneSubjectsLimitsAreReplacedWholeWithoutTheOthers() = serverTest(modules, overrides) { server ->
        val repository = server.koin.get<BudgetsRepository>()
        repository.putOrgLimits(5_000, 1_000)

        val org = listOf(BudgetLimit(7_000), BudgetLimit(300, BudgetWindow.DAY, BudgetAction.WARN))
        repository.replaceLimits(TestCallers.orgA, BudgetTarget.Org, org, TestCallers.epoch)
        repository.replaceLimits(TestCallers.orgA, BudgetTarget.DefaultMember, emptyList(), TestCallers.epoch)

        val settings = repository.settings(TestCallers.orgA, maxOverrides = 0)
        assertEquals(org to emptyList(), settings.org to settings.defaultMember)
        assertEquals(org, repository.limits(TestCallers.orgA, member).org, "The month's before the day's")

        val day = org.last()
        repository.replaceLimits(TestCallers.orgA, BudgetTarget.Org, listOf(day), TestCallers.epoch)
        assertEquals(listOf(day), repository.settings(TestCallers.orgA, maxOverrides = 0).org)
    }

    @Test
    fun twoAdminsSettingOneSubjectAtOnceLeaveOneOfTheirBudgetsNeverAMix() = serverTest(modules, overrides) { server ->
        val repository = server.koin.get<BudgetsRepository>()
        val monthly = listOf(BudgetLimit(10_000_000))
        val daily = listOf(BudgetLimit(5_000_000, BudgetWindow.DAY, BudgetAction.WARN))
        val members = List(SUBJECTS) { Uuid.random() }
        // Each subject starts with limits in both windows: the second writer's delete waits on the first one's rows.
        val start = listOf(BudgetLimit(1), BudgetLimit(1, BudgetWindow.DAY))
        for (each in members) {
            repository.replaceLimits(TestCallers.orgA, BudgetTarget.Member(each), start, TestCallers.epoch)
        }

        coroutineScope {
            members.flatMap { each ->
                val target = BudgetTarget.Member(each)
                listOf(monthly, daily).map {
                    async { repository.replaceLimits(TestCallers.orgA, target, it, TestCallers.epoch) }
                }
            }.awaitAll()
        }

        val mixed = members.map { repository.limits(TestCallers.orgA, it).member.toSet() }
            .filter { it != monthly.toSet() && it != daily.toSet() }
        assertEquals(emptyList(), mixed, "Each subject keeps the limits of one of the two writes, whole")
    }

    @Test
    fun theOrgLimitsSurviveManyOverridesAndNoMemberIsCutBetweenWindows() = serverTest(modules, overrides) { server ->
        val repository = server.koin.get<BudgetsRepository>()
        repository.putOrgLimits(5_000, 1_000)
        val both = listOf(BudgetLimit(10), BudgetLimit(1, BudgetWindow.DAY))
        repeat(3) {
            repository.replaceLimits(TestCallers.orgA, BudgetTarget.Member(Uuid.random()), both, TestCallers.epoch)
        }

        val settings = repository.settings(TestCallers.orgA, maxOverrides = 2)
        assertEquals(5_000L, settings.orgLimitMicros, "The org-wide rows are read before the overrides")
        assertEquals(1_000L, settings.defaultMemberLimitMicros)
        val windows = settings.overrides.groupBy({ it.memberId }, { it.window })
        assertEquals(2, windows.size, "Two members, not two rows")
        assertEquals(setOf(BudgetWindow.MONTH, BudgetWindow.DAY), windows.values.map { it.toSet() }.toSet().single())
    }

    @Test
    fun anotherOrgNeitherSeesNorChangesTheBudgets() = serverTest(modules, overrides) { server ->
        server.putBudget("admin-a", BudgetDto("organization", usdPerMonth = 7.0))
        server.putBudget("admin-a", BudgetDto("member", member.toString(), 3.0))

        val own = server.budgets("admin-b")
        assertEquals(emptyList(), own.items, "Org B's admin reads org B, which has no budget")
        assertEquals(
            HttpStatusCode.OK,
            server.putBudget("admin-b", BudgetDto("member", member.toString())).status,
            "Removing org A's override from org B is a no-op",
        )
        server.putBudget("admin-b", BudgetDto("organization", usdPerMonth = 1.0))
        server.spend(5_000_000, TestCallers.adminB.memberId, orgId = TestCallers.orgB)
        assertEquals(listOf(BudgetDto("organization", usdPerMonth = 1.0)), server.budgets("admin-b").items)

        val budgets = server.budgets()
        assertEquals(
            listOf(BudgetDto("organization", usdPerMonth = 7.0), BudgetDto("member", member.toString(), 3.0)),
            budgets.items,
            "Unchanged by org B",
        )
        assertEquals(0.0, budgets.orgSpentUsd, "Org B's spend never counts for org A")
    }

    @Test
    fun rowLevelSecurityConfinesEveryQueryToItsOrg() = serverTest(modules, overrides) { server ->
        val repository = server.koin.get<BudgetsRepository>()
        repository.putOrgLimits(5_000, 1_000)
        val own = listOf(BudgetLimit(10))
        repository.replaceLimits(TestCallers.orgA, BudgetTarget.Member(member), own, TestCallers.epoch)
        val alert = BudgetAlert(TestCallers.orgA, null, BudgetScope.ORG, 80, 1_000, 900, TestCallers.monthStart)
        repository.markAlerted(alert, TestCallers.epoch)
        for (table in listOf("budgets_limits", "budgets_alerts")) {
            val count = Statement.create("SELECT COUNT(*) AS n FROM $table")
            suspend fun visibleTo(org: Uuid) =
                server.driver.fetchInOrg(org, count).getOrThrow().first().get("n").asLong()
            assertEquals(0L, visibleTo(TestCallers.orgB), "$table: a query without an org filter sees only its org")
            assertEquals(
                0L,
                server.driver.executeInOrg(TestCallers.orgB, Statement.create("DELETE FROM $table")).getOrThrow(),
                "$table: nor deleted by another org",
            )
            assertTrue(visibleTo(TestCallers.orgA) > 0, table)
        }
        val foreign = Statement.create(
            "INSERT INTO budgets_limits (org_id, member_id, scope, limit_micros, updated_at) " +
                "VALUES (:org, :member, 'MEMBER', 1, now())",
        ).bind("org", TestCallers.orgA).bind("member", Uuid.random())
        assertFails("A row of another org is refused") {
            server.driver.executeInOrg(TestCallers.orgB, foreign).getOrThrow()
        }
    }
}

/** The org's monthly limit and the default member's, both stopping, in org A. */
private suspend fun BudgetsRepository.putOrgLimits(org: Long, defaultMember: Long) {
    replaceLimits(TestCallers.orgA, BudgetTarget.Org, listOf(BudgetLimit(org)), TestCallers.epoch)
    replaceLimits(TestCallers.orgA, BudgetTarget.DefaultMember, listOf(BudgetLimit(defaultMember)), TestCallers.epoch)
}

/** How many subjects two admins set at once: enough for the writes to overlap. */
private const val SUBJECTS = 40
