// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.compiler.domain.services

import run.ostrom.server.compiler.domain.models.CompileInputs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The packages this instance compiled, kept by everything their compilation read: the organization and its inputs
 * whole (workflows, rules, decisions, rule gates, repositories, settings, repository, server address), the time
 * aside. A write that changes any input changes the key, so a kept package is never stale, and an input added to
 * [CompileInputs] joins the key by itself. No invalidation travels between replicas: each reads the inputs from the
 * database at every call and keeps its own packages. The pin is not an input: it is read at every call.
 *
 * One compilation at a time per key: callers that ask for the same key at once wait for the first. Bounded for the
 * pod's memory (server#266): at most [maxEntries] packages, least recently used first out, and an estimated
 * [maxWeight] (characters of the body's and the inputs' JSON, twice for the models read from them); a package
 * heavier than the whole is served but not kept.
 *
 * @param maxEntries The packages kept at most.
 * @param maxWeight The estimated weight kept at most.
 */
@OptIn(ExperimentalAtomicApi::class)
class CompiledPackages(private val maxEntries: Int = MAX_ENTRIES, private val maxWeight: Long = MAX_WEIGHT) {
    // Data class equality: every field of the inputs, deep.
    private data class Key(val orgId: Uuid, val inputs: CompileInputs)

    private class Entry {
        val lock = Mutex()
        var body: CompiledBody? = null
        var weight = 0L
    }

    private val lock = Mutex()
    private val entries = LinkedHashMap<Key, Entry>()
    private var weight = 0L
    private val compiled = AtomicLong(0)

    /** The compilations this instance ran; a package served from the cache is not one. */
    val compilations: Long get() = compiled.load()

    // The estimated weight kept, for the tests of the bounds.
    internal val kept: Long get() = weight

    /**
     * The package these inputs compile to: the kept one, or the one [compile] returns, then kept.
     *
     * @param orgId The organization.
     * @param inputs What the compilation reads.
     * @param compile Compiles them.
     * @return The body.
     */
    suspend fun of(orgId: Uuid, inputs: CompileInputs, compile: suspend () -> CompiledBody): CompiledBody {
        val key = Key(orgId, inputs.copy(now = Instant.DISTANT_PAST))
        // The most recently used last: a hit moves its entry to the end.
        val entry = lock.withLock { (entries.remove(key) ?: Entry()).also { entries[key] = it } }
        return entry.lock.withLock {
            entry.body ?: compile().also { body ->
                compiled.incrementAndFetch()
                entry.body = body
                keep(key, entry, weightOf(key.inputs, body))
            }
        }
    }

    private suspend fun keep(key: Key, entry: Entry, of: Long) = lock.withLock {
        when {
            // An entry pushed out while it compiled is served to its callers, not put back.
            entries[key] !== entry -> Unit
            // Heavier than the whole: served, not kept, and the others stay.
            of > maxWeight -> entries.remove(key)
            else -> {
                entry.weight = of
                weight += of
                while (weight > maxWeight || entries.size > maxEntries) {
                    val oldest = entries.entries.first()
                    weight -= oldest.value.weight
                    entries.remove(oldest.key)
                }
            }
        }
    }

    private fun weightOf(inputs: CompileInputs, body: CompiledBody): Long {
        val json = chars(body.body) + inputs.workflows.sumOf { chars(it.raw) } + inputs.rules.sumOf { chars(it.raw) }
        return MODELS * json + DECISION * (inputs.decisions.size + inputs.ruleGates.size)
    }

    private fun chars(element: JsonElement): Long = when (element) {
        is JsonObject -> element.entries.sumOf { (name, value) -> name.length + chars(value) }
        is JsonArray -> element.sumOf { chars(it) }
        is JsonPrimitive -> element.content.length.toLong()
    }

    /** The bounds. */
    companion object {
        /** The packages kept at most. */
        const val MAX_ENTRIES: Int = 256

        /** The estimated weight kept at most: a few tens of MiB of the pod's 512 Mi. */
        const val MAX_WEIGHT: Long = 8_000_000

        // The JSON once, and the models read from it.
        private const val MODELS = 2L

        // A decision or a rule gate, as a fixed weight.
        private const val DECISION = 256L
    }
}
