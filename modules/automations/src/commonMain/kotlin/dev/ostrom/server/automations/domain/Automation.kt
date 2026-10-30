// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** What starts an automation. */
@Serializable
enum class TriggerKind(val wire: String) {
    @SerialName("cron")
    CRON("cron"),
    @SerialName("webhook")
    WEBHOOK("webhook"),
}

/** What a cron does with a slot it finds late (after an outage). */
@Serializable
enum class Catchup(val wire: String) {
    /** Runs the latest missed slot once, if it is at most the grace old. */
    @SerialName("once")
    ONCE("once"),

    /** Runs nothing. */
    @SerialName("skip")
    SKIP("skip"),
}

/** What a trigger does while a run of the automation still works. */
@Serializable
enum class Overlap(val wire: String) {
    /** Records the trigger `skipped_overlap`. */
    @SerialName("skip")
    SKIP("skip"),

    /** Starts another run. */
    @SerialName("allow")
    ALLOW("allow"),
}

/**
 * When an automation runs.
 *
 * @property kind Cron or webhook.
 * @property cron The schedule (cron only).
 * @property timezone The IANA zone of the schedule (cron only).
 * @property catchup What a late slot does (cron only).
 * @property catchupGraceMinutes How old a missed slot may be and still run with [Catchup.ONCE].
 */
@Serializable
data class AutomationTrigger(
    val kind: TriggerKind,
    val cron: String? = null,
    val timezone: String? = null,
    val catchup: Catchup = Catchup.ONCE,
    val catchupGraceMinutes: Int = DEFAULT_GRACE_MINUTES,
) {
    /** Constants and readers of the enclosing type. */
    companion object {
        /** Six hours. */
        const val DEFAULT_GRACE_MINUTES = 360
    }
}

/**
 * What an automation starts: a cloud agent session on [prompt], whose `{{…}}` are the run's variables.
 *
 * @property prompt The agent's first message.
 * @property agent The agent (`claude`, `codex`…).
 * @property model The model, the agent's default when null.
 * @property effort The reasoning effort, the agent's default when null.
 * @property maxRuntimeMinutes How long a run may work.
 */
@Serializable
data class AutomationAction(
    val prompt: String,
    val agent: String = DEFAULT_AGENT,
    val model: String? = null,
    val effort: String? = null,
    val maxRuntimeMinutes: Int = DEFAULT_RUNTIME_MINUTES,
) {
    /** Constants and readers of the enclosing type. */
    companion object {
        /** The default agent. */
        const val DEFAULT_AGENT = "claude"

        /** One hour. */
        const val DEFAULT_RUNTIME_MINUTES = 60
    }
}

/** The type of a payload variable. */
@Serializable
enum class VariableType(val wire: String) {
    @SerialName("string")
    STRING("string"),
    @SerialName("int")
    INT("int"),
    @SerialName("number")
    NUMBER("number"),
    @SerialName("boolean")
    BOOLEAN("boolean"),
    @SerialName("enum")
    ENUM("enum"),
}

/**
 * A value read from a webhook payload.
 *
 * @property name Its name in the prompt (`ticket.number`).
 * @property path Where it is (`$.ticket.number`, `$['a key'][0]`).
 * @property type Its type.
 * @property values The allowed values of an enum.
 * @property maxLength The longest rendered text.
 * @property required Whether a missing value refuses the run.
 * @property sensitive Whether a dry run masks it.
 */
@Serializable
data class AutomationVariable(
    val name: String,
    val path: String,
    val type: VariableType = VariableType.STRING,
    val values: List<String> = emptyList(),
    val maxLength: Int = DEFAULT_MAX_LENGTH,
    val required: Boolean = true,
    val sensitive: Boolean = false,
) {
    /** Constants and readers of the enclosing type. */
    companion object {
        /** The default longest text. */
        const val DEFAULT_MAX_LENGTH = 256
    }
}

/**
 * The part of an automation its owner writes.
 *
 * @property name Unique per owner.
 * @property trigger When it runs.
 * @property action What it starts.
 * @property variables The payload variables (webhook only).
 * @property samplePayload A payload the variables are checked against at save (webhook only).
 * @property overlap What a trigger does while a run works.
 * @property maxRunsPerDay The most cron and webhook runs over any 24 hours.
 */
@Serializable
data class AutomationDefinition(
    val name: String,
    val trigger: AutomationTrigger,
    val action: AutomationAction,
    val variables: List<AutomationVariable> = emptyList(),
    val samplePayload: JsonObject? = null,
    val overlap: Overlap = Overlap.SKIP,
    val maxRunsPerDay: Int = DEFAULT_MAX_RUNS_PER_DAY,
) {
    /** Constants and readers of the enclosing type. */
    companion object {
        /** One an hour. */
        const val DEFAULT_MAX_RUNS_PER_DAY = 24
    }
}

/**
 * An automation: a member's definition, run as that member.
 *
 * @property id Its id.
 * @property orgId Its organization.
 * @property owner The member it runs as.
 * @property definition What the owner wrote.
 * @property enabled Whether triggers start runs.
 * @property version Grows with every saved change of the definition (not with enabling or rotating).
 * @property secretGeneration Grows with every rotation of the webhook secret.
 * @property previousSecretValidUntil Until when the secret before the last rotation is still accepted.
 * @property nextRunAt The next slot of an enabled cron.
 * @property createdAt When it was created.
 * @property updatedAt When it last changed.
 */
data class Automation(
    val id: Uuid,
    val orgId: Uuid,
    val owner: String,
    val definition: AutomationDefinition,
    val enabled: Boolean,
    val version: Int,
    val secretGeneration: Int,
    val previousSecretValidUntil: Instant?,
    val nextRunAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** How a run started. */
enum class RunSource(val wire: String) {
    CRON("cron"),
    WEBHOOK("webhook"),
    TEST("test"),
}

/** Where a run is. */
enum class RunStatus(val wire: String) {
    /** Recorded, its session not created yet. */
    QUEUED("queued"),

    /** Its session was created. */
    STARTED("started"),

    /** Its session could not be created ([AutomationRun.reason] says why). */
    FAILED("failed"),

    /** A run of the automation still worked. */
    SKIPPED_OVERLAP("skipped_overlap"),

    /** The automation had used its runs of the day. */
    SKIPPED_BUDGET("skipped_budget"),

    /** The payload did not fit the variables ([AutomationRun.problems]). */
    INVALID_PAYLOAD("invalid_payload"),
}

/**
 * A payload that does not fit a variable.
 *
 * @property path The variable's path.
 * @property rule `required`, `type:<type>`, `max_length`, `enum` or `not_scalar`.
 */
@Serializable
data class PayloadProblem(val path: String, val rule: String)

/**
 * One trigger of an automation, kept 14 days.
 *
 * @property id Its id.
 * @property automationId Its automation.
 * @property orgId Its organization.
 * @property source How it started.
 * @property status Where it is.
 * @property version The automation's version it ran.
 * @property deliveryKey What deduplicates a webhook delivery or a test.
 * @property sessionId The cloud session it created.
 * @property reason Why it failed.
 * @property problems Why its payload was refused.
 * @property slotAt The cron slot it ran for.
 * @property createdAt When it was recorded.
 * @property updatedAt When it last changed.
 */
data class AutomationRun(
    val id: Uuid,
    val automationId: Uuid,
    val orgId: Uuid,
    val source: RunSource,
    val status: RunStatus,
    val version: Int,
    val deliveryKey: String? = null,
    val sessionId: String? = null,
    val reason: String? = null,
    val problems: List<PayloadProblem> = emptyList(),
    val slotAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * The member a request acts for.
 *
 * @property orgId Their organization.
 * @property memberId Their member id.
 * @property admin Whether they administer the organization.
 * @property interactive Whether they signed in themselves (not a key).
 */
data class AutomationCaller(
    val orgId: Uuid,
    val memberId: String,
    val admin: Boolean,
    val interactive: Boolean,
)
