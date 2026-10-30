// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.automations.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** The checks of a wait's question and of its answer (TeamCode's `waits.md`, ported). */
@Suppress("TooManyFunctions") // One small check per field of the question and per kind of answer.
internal object WaitRules {
    const val MAX_TITLE = 200
    const val MAX_BODY = 32_000
    const val MAX_REFS = 10
    const val MAX_FIELDS = 32
    const val MAX_ACTIONS = 8
    const val MAX_OPTIONS = 50
    const val MAX_LABEL = 200
    const val MAX_HELP = 2_000
    const val MAX_URL = 2_000
    const val DEFAULT_TEXT = 8_000
    const val MAX_TEXT = 32_000
    const val MAX_OTHER = 500

    /** The action a wait gets when its asker gives none. */
    val DONE = WaitAction("done", "Done")

    private val ID = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
    private val REF_ID = Regex("[A-Za-z0-9._:-]{1,200}")
    private val STYLES = setOf("primary", "secondary", "danger")
    private val REF_KINDS = setOf("artifact", "session", "url")

    /**
     * Checks a question and returns it ready to store: titles trimmed, one `done` action when none is given.
     *
     * @throws InvalidWaitException With what to fix.
     */
    fun question(spec: WaitSpec): WaitSpec {
        val title = spec.title.trim()
        requireWait(title.length in 1..MAX_TITLE && title.none { it.isISOControl() }) {
            "title is 1 to $MAX_TITLE characters, without control characters."
        }
        requireWait((spec.body?.length ?: 0) <= MAX_BODY) { "body is at most $MAX_BODY characters." }
        requireWait(spec.refs.size <= MAX_REFS) { "At most $MAX_REFS refs." }
        spec.refs.forEach(::checkRef)
        requireWait(spec.fields.size <= MAX_FIELDS) { "At most $MAX_FIELDS fields." }
        requireWait(spec.fields.map { it.id }.toSet().size == spec.fields.size) { "Field ids are unique." }
        spec.fields.forEach(::checkField)
        requireWait(spec.actions.size <= MAX_ACTIONS) { "At most $MAX_ACTIONS actions." }
        requireWait(spec.actions.map { it.id }.toSet().size == spec.actions.size) { "Action ids are unique." }
        val fillable = spec.fields.filter { it.type != WaitFieldType.INFO }.map { it.id }.toSet()
        spec.actions.forEach { checkAction(it, fillable) }
        return spec.copy(title = title, actions = spec.actions.ifEmpty { listOf(DONE) })
    }

    private fun checkRef(ref: WaitRef) {
        requireWait(ref.kind in REF_KINDS) { "A ref is an artifact, a session or a url." }
        requireWait((ref.title?.length ?: 0) <= MAX_LABEL) { "A ref's title is at most $MAX_LABEL characters." }
        if (ref.kind == "url") {
            val url = ref.url.orEmpty()
            requireWait(url.startsWith("https://") && url.length <= MAX_URL && url.none { it.isWhitespace() }) {
                "A url ref is an https address."
            }
        } else {
            requireWait(REF_ID.matches(ref.id.orEmpty())) { "An ${ref.kind} ref names its id." }
        }
    }

    private fun checkField(field: WaitField) {
        requireWait(ID.matches(field.id)) { "A field id is a letter then letters, digits or _ (64 at most)." }
        requireWait(field.label.trim().length in 1..MAX_LABEL) { "${field.id}: label is 1 to $MAX_LABEL characters." }
        requireWait((field.help?.length ?: 0) <= MAX_HELP) { "${field.id}: help is at most $MAX_HELP characters." }
        val choice = field.type == WaitFieldType.CHOICE
        requireWait(choice == field.options.isNotEmpty() && field.options.size <= MAX_OPTIONS) {
            "${field.id}: a choice field has 1 to $MAX_OPTIONS options, and only a choice field has options."
        }
        val distinct = field.options.toSet().size == field.options.size
        requireWait(distinct && field.options.all { it.length in 1..MAX_LABEL }) {
            "${field.id}: options are unique, 1 to $MAX_LABEL characters."
        }
        requireWait(choice || (!field.multiple && !field.allowOther)) {
            "${field.id}: only a choice field is multiple or allows another answer."
        }
        requireWait(field.maxLength == null || (field.type == WaitFieldType.TEXT && field.maxLength in 1..MAX_TEXT)) {
            "${field.id}: maxLength is for text fields, 1 to $MAX_TEXT."
        }
        requireWait(field.type != WaitFieldType.INFO || !field.required) { "${field.id}: an info field has no value." }
    }

    private fun checkAction(action: WaitAction, fillable: Set<String>) {
        requireWait(ID.matches(action.id)) { "An action id is a letter then letters, digits or _ (64 at most)." }
        requireWait(action.label.trim().length in 1..MAX_LABEL) {
            "${action.id}: label is 1 to $MAX_LABEL characters."
        }
        requireWait(action.style == null || action.style in STYLES) {
            "${action.id}: style is primary, secondary or danger."
        }
        requireWait(action.requires.all { it in fillable }) { "${action.id}: requires names fields of this wait." }
    }

    /**
     * Checks an answer against the question it answers.
     *
     * @throws InvalidWaitAnswerException With what to fix.
     */
    fun answer(spec: WaitSpec, action: String, values: Map<String, JsonElement>) {
        val chosen = spec.actions.firstOrNull { it.id == action }
            ?: throw InvalidWaitAnswerException("Unknown action: choose one of ${spec.actions.joinToString { it.id }}.")
        val fields = spec.fields.associateBy { it.id }
        values.forEach { (id, value) ->
            val field = fields[id] ?: throw InvalidWaitAnswerException("This wait has no field named $id.")
            requireAnswer(value is JsonNull || fits(field, value)) { "$id: ${expected(field)}" }
        }
        val needed = spec.fields.filter { it.required }.map { it.id } + chosen.requires
        needed.forEach { id -> requireAnswer(filled(values[id])) { "$id must be filled." } }
    }

    private fun fits(field: WaitField, value: JsonElement): Boolean = when (field.type) {
        WaitFieldType.TEXT -> value.string()?.let { it.length <= (field.maxLength ?: DEFAULT_TEXT) } == true
        WaitFieldType.CHOICE -> fitsChoice(field, value)
        WaitFieldType.CONFIRM -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        WaitFieldType.NUMBER -> value is JsonPrimitive && !value.isString && value.doubleOrNull?.isFinite() == true
        WaitFieldType.DATE -> value.string()?.let(CalendarDates::isDate) == true
        WaitFieldType.INFO -> false
    }

    private fun fitsChoice(field: WaitField, value: JsonElement): Boolean = if (field.multiple) {
        value is JsonArray && value.size <= field.options.size + 1 && value.all { option(field, it) }
    } else {
        option(field, value)
    }

    private fun option(field: WaitField, value: JsonElement): Boolean {
        val text = value.string() ?: return false
        return text in field.options || (field.allowOther && text.length in 1..MAX_OTHER)
    }

    private fun expected(field: WaitField): String = when (field.type) {
        WaitFieldType.TEXT -> "a string of at most ${field.maxLength ?: DEFAULT_TEXT} characters."
        WaitFieldType.CHOICE -> if (field.multiple) "a list of its options." else "one of its options."
        WaitFieldType.CONFIRM -> "true or false."
        WaitFieldType.NUMBER -> "a finite number."
        WaitFieldType.DATE -> "a date, YYYY-MM-DD."
        WaitFieldType.INFO -> "nothing: it is an info field."
    }

    /** Whether a value that fits its field fills it: neither absent, null, blank nor an empty list. */
    private fun filled(value: JsonElement?): Boolean = when (value) {
        null -> false
        is JsonPrimitive -> value != JsonNull && (!value.isString || value.content.isNotBlank())
        else -> (value as JsonArray).isNotEmpty()
    }

    private fun JsonElement.string(): String? = if (this is JsonPrimitive && isString) content else null
}

/** Calendar dates of the `date` field, `YYYY-MM-DD`, without a date library. */
internal object CalendarDates {
    private val DATE = Regex("(\\d{4})-(\\d{2})-(\\d{2})")

    /** Whether [text] is a real calendar date, `YYYY-MM-DD`. */
    fun isDate(text: String): Boolean {
        val match = DATE.matchEntire(text) ?: return false
        val (year, month, day) = match.destructured.toList().map(String::toInt)
        return month in 1..MONTHS && day in 1..daysIn(year, month)
    }

    // A condition per kind of month, not a switch on the month: Kover counts every month of a switch as a branch.
    private fun daysIn(year: Int, month: Int): Int = when {
        month == FEBRUARY -> if (isLeap(year)) LEAP_FEBRUARY else FEBRUARY_DAYS
        month in SHORT_MONTHS -> SHORT_MONTH
        else -> LONG_MONTH
    }

    private fun isLeap(year: Int): Boolean = year % LEAP == 0 && (year % CENTURY != 0 || year % LEAP_CENTURY == 0)

    private const val MONTHS = 12
    private const val FEBRUARY = 2
    private val SHORT_MONTHS = setOf(4, 6, 9, 11)
    private const val LEAP = 4
    private const val CENTURY = 100
    private const val LEAP_CENTURY = 400
    private const val FEBRUARY_DAYS = 28
    private const val LEAP_FEBRUARY = 29
    private const val SHORT_MONTH = 30
    private const val LONG_MONTH = 31
}
