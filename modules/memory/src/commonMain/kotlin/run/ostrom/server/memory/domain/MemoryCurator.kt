// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.domain

import kotlin.uuid.Uuid

/** An entry of a scope as curation lists it, newest first: its title only. */
data class CurationTitle(val id: Uuid, val title: String)

/**
 * An entry of a same-title group, as curation compares it.
 *
 * @property external Whether it is tagged `external`: content from outside, never merged over another automatically.
 */
data class CurationCandidate(val id: Uuid, val content: String, val origin: MemoryOrigin, val external: Boolean)

/** What curation does with an older entry of a group and the group's newest one. */
sealed interface CurationDecision {
    val olderId: Uuid
    val newerId: Uuid

    /** The older entry becomes superseded by the newer one. */
    data class Supersede(override val olderId: Uuid, override val newerId: Uuid) : CurationDecision

    /** A person decides: the pair goes to the review queue. */
    data class Queue(
        override val olderId: Uuid,
        override val newerId: Uuid,
        val kind: ReviewKind,
        val reason: String,
    ) : CurationDecision
}

/**
 * The model-free pass of TeamCode's curation (#273): entries whose normalized title is equal form a group; within a
 * group, the newest entry wins over an older one with equal text, unless a person wrote the older and an agent the
 * newer, or either is tagged `external`; a different text under the same title cannot be told an update from a
 * contradiction without a model, so a person is asked.
 */
object MemoryCurator {
    private val whitespace = Regex("\\s+")

    /** Case and runs of whitespace do not make two texts different. */
    fun normalize(text: String): String = text.lowercase().trim().replace(whitespace, " ")

    /**
     * The groups of [titles] (newest first) sharing a normalized title, at most [MemoryRules.CURATION_GROUPS], each
     * of its [MemoryRules.CURATION_GROUP_SIZE] newest entries, newest first.
     */
    fun groups(titles: List<CurationTitle>): List<List<Uuid>> =
        titles.groupBy { normalize(it.title) }.values.filter { it.size >= 2 }.take(MemoryRules.CURATION_GROUPS)
            .map { group -> group.take(MemoryRules.CURATION_GROUP_SIZE).map { it.id } }

    /** The decisions about every older entry of [group] (newest first) against the newest. */
    fun decide(group: List<CurationCandidate>): List<CurationDecision> {
        val newer = group.first()
        return group.drop(1).map { older ->
            val same = normalize(older.content) == normalize(newer.content)
            val outside = older.external || newer.external
            val overPerson = older.origin == MemoryOrigin.HUMAN && newer.origin == MemoryOrigin.AGENT
            when {
                !same -> CurationDecision.Queue(older.id, newer.id, ReviewKind.SAME_TITLE, SAME_TITLE)
                outside -> CurationDecision.Queue(older.id, newer.id, ReviewKind.DUPLICATE, EXTERNAL)
                overPerson -> CurationDecision.Queue(older.id, newer.id, ReviewKind.DUPLICATE, AGENT_OVER_PERSON)
                else -> CurationDecision.Supersede(older.id, newer.id)
            }
        }
    }

    private const val SAME_TITLE = "Same title, different text: is the newer one an update?"
    private const val EXTERNAL = "Same text; one comes from outside (tagged external)."
    private const val AGENT_OVER_PERSON = "Same text; a person wrote the older one and an agent the newer one."
}
