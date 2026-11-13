// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.automations

import run.ostrom.server.automations.domain.AutomationForbiddenException
import run.ostrom.server.automations.domain.AutomationLimitException
import run.ostrom.server.automations.domain.AutomationNotFoundException
import run.ostrom.server.automations.domain.AutomationQuery
import run.ostrom.server.automations.domain.InvalidAutomationException
import run.ostrom.server.automations.domain.ManageAutomationsUseCaseImpl
import run.ostrom.server.automations.domain.StaleAutomationException
import run.ostrom.server.automations.fakes.InMemoryAutomations
import run.ostrom.server.automations.fakes.PlainSecrets
import run.ostrom.server.automations.fakes.TestClock
import run.ostrom.server.automations.fakes.aliceAuto
import run.ostrom.server.automations.fakes.aliceKey
import run.ostrom.server.automations.fakes.bobAuto
import run.ostrom.server.automations.fakes.carolAuto
import run.ostrom.server.automations.fakes.cronDefinition
import run.ostrom.server.automations.fakes.daveAuto
import run.ostrom.server.automations.fakes.daveAutoKey
import run.ostrom.server.automations.fakes.webhookDefinition
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ManageAutomationsTest {
    private val repository = InMemoryAutomations()
    private val clock = TestClock(Instant.parse("2026-10-30T10:00:00.123456Z"))
    private val manage = ManageAutomationsUseCaseImpl(repository, PlainSecrets, clock)

    @Test
    fun aCreatedAutomationIsPausedAndOnlyAWebhookHasASecret() = runTest {
        val cron = manage.create(aliceAuto, cronDefinition())
        assertFalse(cron.automation.enabled)
        assertNull(cron.secret)
        assertNull(cron.automation.nextRunAt)
        assertEquals(Instant.parse("2026-10-30T10:00:00.123Z"), cron.automation.createdAt)
        val hook = manage.create(aliceAuto, webhookDefinition())
        assertEquals("whsec_${hook.automation.id}_1", hook.secret)
        assertEquals(aliceAuto.memberId, hook.automation.owner)
        assertFailsWith<InvalidAutomationException> { manage.create(aliceAuto, cronDefinition(name = "")) }
    }

    @Test
    fun aMemberOwnsAtMostTwentyAutomations() = runTest {
        repeat(20) { manage.create(aliceAuto, cronDefinition(name = "n$it")) }
        assertFailsWith<AutomationLimitException> { manage.create(aliceAuto, cronDefinition(name = "one more")) }
        manage.create(bobAuto, cronDefinition())
    }

    @Test
    fun anotherMembersAutomationIsSeenOnlyByAnAdministrator() = runTest {
        val id = manage.create(aliceAuto, cronDefinition()).automation.id
        assertEquals(id, manage.get(aliceAuto, id).id)
        assertEquals(id, manage.get(daveAuto, id).id)
        assertFailsWith<AutomationNotFoundException> { manage.get(bobAuto, id) }
        assertFailsWith<AutomationNotFoundException> { manage.get(carolAuto, id) }
        assertFailsWith<AutomationNotFoundException> { manage.get(aliceAuto, Uuid.random()) }
    }

    @Test
    fun aListingIsTheCallersOrForAnAdministratorAnyonesPageByPage() = runTest {
        val first = manage.create(aliceAuto, cronDefinition(name = "a")).automation
        val second = manage.create(aliceAuto, cronDefinition(name = "b")).automation
        val bobs = manage.create(bobAuto, cronDefinition(name = "c")).automation
        assertEquals(listOf(second.id, first.id), manage.list(aliceAuto, AutomationQuery()).items.map { it.id })
        assertEquals(2, manage.list(aliceAuto, AutomationQuery(owner = aliceAuto.memberId)).items.size)
        assertFailsWith<AutomationForbiddenException> { manage.list(aliceAuto, AutomationQuery(owner = "all")) }
        assertEquals(3, manage.list(daveAuto, AutomationQuery(owner = "all")).items.size)
        val ofBob = manage.list(daveAuto, AutomationQuery(owner = bobAuto.memberId))
        assertEquals(listOf(bobs.id), ofBob.items.map { it.id })
        val page = manage.list(aliceAuto, AutomationQuery(limit = 1))
        assertEquals(listOf(second.id), page.items.map { it.id })
        assertEquals(second.id, page.next)
        val last = manage.list(aliceAuto, AutomationQuery(before = page.next, limit = 1))
        assertEquals(listOf(first.id), last.items.map { it.id })
        assertNull(last.next)
        assertFailsWith<InvalidAutomationException> { manage.list(aliceAuto, AutomationQuery(limit = 0)) }
        assertFailsWith<InvalidAutomationException> { manage.list(aliceAuto, AutomationQuery(limit = 101)) }
    }

    @Test
    fun onlyTheOwnerChangesTheDefinitionAtTheVersionTheyRead() = runTest {
        val created = manage.create(aliceAuto, cronDefinition()).automation
        clock.now += 1.minutes
        val changed = manage.update(aliceAuto, created.id, 1, cronDefinition(name = "Renamed"))
        assertEquals(2, changed.version)
        assertEquals("Renamed", changed.definition.name)
        assertNull(changed.nextRunAt)
        assertFailsWith<StaleAutomationException> { manage.update(aliceAuto, created.id, 1, cronDefinition()) }
        assertFailsWith<AutomationForbiddenException> { manage.update(daveAuto, created.id, 2, cronDefinition()) }
        assertFailsWith<AutomationNotFoundException> { manage.update(bobAuto, created.id, 2, cronDefinition()) }
        manage.setEnabled(aliceAuto, created.id, true)
        val armed = manage.update(aliceAuto, created.id, 2, cronDefinition(name = "Armed"))
        assertEquals(Instant.parse("2026-10-30T20:00:00Z"), armed.nextRunAt)
        repository.loseNextUpdate = true
        assertFailsWith<StaleAutomationException> { manage.update(aliceAuto, created.id, 3, cronDefinition()) }
    }

    @Test
    fun theOwnerArmsAndAnAdministratorMayPause() = runTest {
        val id = manage.create(aliceAuto, cronDefinition()).automation.id
        assertFailsWith<AutomationForbiddenException> { manage.setEnabled(daveAuto, id, true) }
        val armed = manage.setEnabled(aliceAuto, id, true)
        assertTrue(armed.enabled)
        assertEquals(Instant.parse("2026-10-30T20:00:00Z"), armed.nextRunAt)
        assertEquals(1, armed.version)
        assertEquals(armed, manage.setEnabled(aliceAuto, id, true))
        val paused = manage.setEnabled(daveAuto, id, false)
        assertFalse(paused.enabled)
        assertNull(paused.nextRunAt)
        repository.loseNextUpdate = true
        assertFailsWith<StaleAutomationException> { manage.setEnabled(aliceAuto, id, true) }
    }

    @Test
    fun theOwnerOrAnAdministratorSignedInDeletes() = runTest {
        val id = manage.create(aliceAuto, cronDefinition()).automation.id
        assertFailsWith<AutomationNotFoundException> { manage.delete(bobAuto, id) }
        assertFailsWith<AutomationForbiddenException> { manage.delete(daveAutoKey, id) }
        repository.refuseNextDelete = true
        assertFailsWith<AutomationNotFoundException> { manage.delete(aliceAuto, id) }
        manage.delete(daveAuto, id)
        assertTrue(repository.automations.isEmpty())
        val own = manage.create(aliceAuto, cronDefinition()).automation.id
        manage.delete(aliceKey, own)
        assertTrue(repository.automations.isEmpty())
    }

    @Test
    fun theOwnerSignedInRotatesAWebhookSecret() = runTest {
        val hook = manage.create(aliceAuto, webhookDefinition()).automation
        val cron = manage.create(aliceAuto, cronDefinition()).automation
        assertFailsWith<InvalidAutomationException> { manage.rotateSecret(aliceAuto, cron.id) }
        assertFailsWith<AutomationForbiddenException> { manage.rotateSecret(aliceKey, hook.id) }
        assertFailsWith<AutomationForbiddenException> { manage.rotateSecret(daveAuto, hook.id) }
        val rotated = manage.rotateSecret(aliceAuto, hook.id)
        assertEquals("whsec_${hook.id}_2", rotated.secret)
        assertEquals(2, rotated.automation.secretGeneration)
        assertEquals(1, rotated.automation.version)
        assertEquals(Instant.parse("2026-10-30T10:10:00.123Z"), rotated.automation.previousSecretValidUntil)
        repository.loseNextUpdate = true
        assertFailsWith<StaleAutomationException> { manage.rotateSecret(aliceAuto, hook.id) }
    }
}
