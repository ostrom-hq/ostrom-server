// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations.domain

import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * An automation and, once, its webhook secret.
 *
 * @property automation The automation.
 * @property secret The webhook secret, only at creation and rotation of a webhook automation.
 */
data class AutomationWithSecret(val automation: Automation, val secret: String?)

/**
 * Who a listing covers.
 *
 * @property owner `all` (an administrator), a member id (an administrator, or the caller's own), or null (the caller).
 * @property before The cursor, an automation id.
 * @property limit 1 to 100, 50 by default.
 */
data class AutomationQuery(val owner: String? = null, val before: Uuid? = null, val limit: Int? = null)

/**
 * A page of automations.
 *
 * @property items The automations, newest first.
 * @property next The cursor of the next page.
 */
data class AutomationPage(val items: List<Automation>, val next: Uuid?)

/** Creating, reading, changing and deleting automations. An automation is its owner's: others never see it. */
interface ManageAutomationsUseCase {
    /** Creates a paused automation owned by the caller. */
    suspend fun create(caller: AutomationCaller, definition: AutomationDefinition): AutomationWithSecret

    /** One automation the caller owns or administers. */
    suspend fun get(caller: AutomationCaller, id: Uuid): Automation

    /** The automations of [query]. */
    suspend fun list(caller: AutomationCaller, query: AutomationQuery): AutomationPage

    /** Saves a new definition over [version] (the owner only). */
    suspend fun update(caller: AutomationCaller, id: Uuid, version: Int, definition: AutomationDefinition): Automation

    /** Deletes it with its history (the owner, or an administrator signed in). */
    suspend fun delete(caller: AutomationCaller, id: Uuid)

    /** Arms (the owner only) or pauses (the owner or an administrator) it. */
    suspend fun setEnabled(caller: AutomationCaller, id: Uuid, enabled: Boolean): Automation

    /** A new webhook secret; the former one is accepted 10 more minutes (the owner, signed in). */
    suspend fun rotateSecret(caller: AutomationCaller, id: Uuid): AutomationWithSecret
}

/**
 * [ManageAutomationsUseCase] on the repository.
 *
 * @param repository The automations.
 * @param secrets The webhook secrets.
 * @param clock The time.
 */
class ManageAutomationsUseCaseImpl(
    private val repository: AutomationsRepository,
    private val secrets: WebhookSecrets,
    private val clock: Clock,
) : ManageAutomationsUseCase {
    override suspend fun create(caller: AutomationCaller, definition: AutomationDefinition): AutomationWithSecret {
        val now = clock.now().truncatedToMillis()
        val automation = Automation(
            id = Uuid.random(),
            orgId = caller.orgId,
            owner = caller.memberId,
            definition = AutomationRules.check(definition),
            enabled = false,
            version = 1,
            secretGeneration = 1,
            previousSecretValidUntil = null,
            nextRunAt = null,
            createdAt = now,
            updatedAt = now,
        )
        if (!repository.createWithinLimit(automation, MAX_PER_MEMBER)) throw AutomationLimitException(MAX_PER_MEMBER)
        return AutomationWithSecret(automation, secretOf(automation))
    }

    override suspend fun get(caller: AutomationCaller, id: Uuid): Automation = repository.visibleTo(caller, id)

    override suspend fun list(caller: AutomationCaller, query: AutomationQuery): AutomationPage {
        val limit = query.limit ?: DEFAULT_PAGE
        requireAutomation(limit in 1..MAX_PAGE) { "limit is between 1 and $MAX_PAGE." }
        val owner = when (query.owner) {
            null, caller.memberId -> caller.memberId
            else -> if (caller.admin) query.owner.takeUnless { it == ALL_OWNERS } else throw notAdmin()
        }
        val items = repository.list(caller.orgId, owner, query.before, limit + 1)
        return AutomationPage(items.take(limit), items.getOrNull(limit)?.let { items[limit - 1].id })
    }

    override suspend fun update(
        caller: AutomationCaller,
        id: Uuid,
        version: Int,
        definition: AutomationDefinition,
    ): Automation {
        val current = repository.ownedBy(caller, id)
        if (current.version != version) throw StaleAutomationException()
        val checked = AutomationRules.check(definition)
        val now = clock.now().truncatedToMillis()
        val updated = current.copy(
            definition = checked,
            version = version + 1,
            nextRunAt = if (current.enabled) AutomationRules.nextRun(checked, now) else null,
            updatedAt = now,
        )
        return repository.update(updated, current) ?: throw StaleAutomationException()
    }

    override suspend fun delete(caller: AutomationCaller, id: Uuid) {
        val current = repository.visibleTo(caller, id)
        if (current.owner != caller.memberId && !caller.interactive) {
            throw AutomationForbiddenException("An administrator deletes another member's automation signed in.")
        }
        if (!repository.delete(caller.orgId, id)) throw AutomationNotFoundException()
    }

    override suspend fun setEnabled(caller: AutomationCaller, id: Uuid, enabled: Boolean): Automation {
        val current = if (enabled) repository.ownedBy(caller, id) else repository.visibleTo(caller, id)
        if (current.enabled == enabled) return current
        val now = clock.now().truncatedToMillis()
        val changed = current.copy(
            enabled = enabled,
            nextRunAt = if (enabled) AutomationRules.nextRun(current.definition, now) else null,
            updatedAt = now,
        )
        return repository.update(changed, current) ?: throw StaleAutomationException()
    }

    override suspend fun rotateSecret(caller: AutomationCaller, id: Uuid): AutomationWithSecret {
        val current = repository.ownedBy(caller, id)
        if (current.definition.trigger.kind != TriggerKind.WEBHOOK) {
            throw InvalidAutomationException("Only a webhook automation has a secret.")
        }
        if (!caller.interactive) throw AutomationForbiddenException("The webhook secret is rotated signed in.")
        val now = clock.now().truncatedToMillis()
        val rotated = current.copy(
            secretGeneration = current.secretGeneration + 1,
            previousSecretValidUntil = now + PREVIOUS_SECRET_GRACE,
            updatedAt = now,
        )
        val saved = repository.update(rotated, current) ?: throw StaleAutomationException()
        return AutomationWithSecret(saved, secretOf(saved))
    }

    private suspend fun secretOf(automation: Automation): String? =
        if (automation.definition.trigger.kind == TriggerKind.WEBHOOK) {
            secrets.secret(automation.id, automation.secretGeneration)
        } else {
            null
        }

    private fun notAdmin() = AutomationForbiddenException("Only an administrator lists another member's automations.")

    /** Constants and readers of the enclosing type. */
    companion object {
        /** The most automations a member owns. */
        const val MAX_PER_MEMBER = 20

        /** How long the former secret is accepted after a rotation. */
        val PREVIOUS_SECRET_GRACE = 10.minutes

        /** `?owner=all`: every member's (an administrator). */
        const val ALL_OWNERS = "all"

        private const val DEFAULT_PAGE = 50
        private const val MAX_PAGE = 100
    }
}

/** The instant at millisecond precision, the precision the store keeps (so a compare-and-set compares equals). */
internal fun Instant.truncatedToMillis(): Instant = Instant.fromEpochMilliseconds(toEpochMilliseconds())
