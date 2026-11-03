// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.compiler.domain.services

/**
 * The pattern of a `matches` condition, compiled to an automaton matched in linear time: every state the text can
 * reach is followed at once, one character after another (Thompson's construction), never one path after another, so
 * no text makes it backtrack. Its syntax: literal characters, `.`, bracket classes (`[a-z]`, `[^0-9]`), `\d`, `\w`,
 * `\s` and escaped punctuation, `*`, `+`, `?`, groups `( )`, alternation `|`, anchors `^` and `$`. No backreference,
 * no lookaround, no counted repetition: a pattern that uses them is refused when compiled.
 */
class LinearPattern internal constructor(private val states: List<PatternState>, private val start: Int) {
    /**
     * Whether the pattern matches a part of a text (anchors to match it whole).
     *
     * @param text The text, at most [MAX_TEXT] characters (the caller checks it): the time is linear in its length.
     *
     * @return True when it matches.
     */
    fun find(text: String): Boolean {
        var current = BooleanArray(states.size).also { close(it, start, 0, text.length) }
        var at = 0
        while (!current[MATCH] && at < text.length) {
            current = step(current, text, at)
            at++
        }
        return current[MATCH]
    }

    // The states after one character, and a match that starts right after it.
    private fun step(current: BooleanArray, text: String, at: Int): BooleanArray {
        val next = BooleanArray(states.size)
        states.forEachIndexed { id, state ->
            if (current[id] && state.accepts(text[at])) close(next, state.out, at + 1, text.length)
        }
        close(next, start, at + 1, text.length)
        return next
    }

    // Every state reached from `from` without reading a character, at position `at` of a text of `length`.
    private fun close(set: BooleanArray, from: Int, at: Int, length: Int) {
        val stack = ArrayDeque(listOf(from))
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            val state = states[id]
            val passes = when (state.kind) {
                PatternKind.SPLIT, PatternKind.EMPTY -> true
                PatternKind.START -> at == 0
                PatternKind.END -> at == length
                else -> false
            }
            if (!set[id] && passes) stack += listOf(state.out, state.alt).filter { it != NONE }
            set[id] = true
        }
    }

    /** The bounds of a pattern and of the text it reads. */
    companion object {
        /** The longest pattern. */
        const val MAX_PATTERN: Int = 200

        /** The longest text a pattern reads. */
        const val MAX_TEXT: Int = 10_000

        /**
         * Compiles a pattern.
         *
         * @param pattern The pattern, at most [MAX_PATTERN] characters.
         *
         * @return The pattern, compiled.
         * @throws IllegalArgumentException When the pattern is too long or out of its syntax, with the reason.
         */
        fun compile(pattern: String): LinearPattern {
            require(pattern.length <= MAX_PATTERN) { "the pattern is at most $MAX_PATTERN characters." }
            return PatternParser(pattern).parse()
        }
    }
}

/** What a state of the automaton does: read a character, split, nothing, an anchor, or the match. */
internal enum class PatternKind { CHAR, SPLIT, EMPTY, START, END, MATCH }

/**
 * A state of the automaton.
 *
 * @property kind What it does.
 * @property accepts The characters a [PatternKind.CHAR] state reads.
 */
internal class PatternState(val kind: PatternKind, val accepts: (Char) -> Boolean = NEVER) {
    /** The state after it. */
    var out: Int = NONE

    /** The other state after a split. */
    var alt: Int = NONE
}

// A piece of the automaton under construction: its first state and its last, an empty state whose `out` is not set.
internal class PatternPiece(val start: Int, val end: Int)

// Thompson's construction: each piece has one start and one end, joined as the parser reads the pattern.
internal class PatternBuilder {
    private val states = mutableListOf(PatternState(PatternKind.MATCH))

    fun one(kind: PatternKind, accepts: (Char) -> Boolean = NEVER): PatternPiece {
        val piece = empty()
        val first = add(PatternState(kind, accepts))
        states[first].out = piece.start
        return PatternPiece(first, piece.end)
    }

    fun empty(): PatternPiece {
        val id = add(PatternState(PatternKind.EMPTY))
        return PatternPiece(id, id)
    }

    fun then(first: PatternPiece, second: PatternPiece): PatternPiece {
        states[first.end].out = second.start
        return PatternPiece(first.start, second.end)
    }

    fun either(first: PatternPiece, second: PatternPiece): PatternPiece {
        val split = add(PatternState(PatternKind.SPLIT))
        val end = empty()
        states[split].out = first.start
        states[split].alt = second.start
        states[first.end].out = end.start
        states[second.end].out = end.start
        return PatternPiece(split, end.end)
    }

    // `*` loops through a split before the piece, `+` enters the piece first, `?` passes it at most once.
    fun repeat(piece: PatternPiece, operator: Char): PatternPiece {
        val split = add(PatternState(PatternKind.SPLIT))
        val end = empty()
        states[split].out = piece.start
        states[split].alt = end.start
        states[piece.end].out = if (operator == '?') end.start else split
        return PatternPiece(if (operator == '+') piece.start else split, end.end)
    }

    fun build(piece: PatternPiece): LinearPattern {
        states[piece.end].out = MATCH
        return LinearPattern(states.toList(), piece.start)
    }

    private fun add(state: PatternState): Int {
        states += state
        return states.lastIndex
    }
}

// A recursive descent over the pattern: alternation, then sequence, then repetition, then atom.
private class PatternParser(private val pattern: String) {
    private val builder = PatternBuilder()
    private var at = 0

    fun parse(): LinearPattern {
        val whole = alternation()
        require(at == pattern.length) { "a ) at position ${at + 1} closes no group." }
        return builder.build(whole)
    }

    private fun alternation(): PatternPiece {
        var piece = sequence()
        while (peek('|')) {
            at++
            piece = builder.either(piece, sequence())
        }
        return piece
    }

    private fun sequence(): PatternPiece {
        var piece = builder.empty()
        while (at < pattern.length && pattern[at] != '|' && pattern[at] != ')') {
            piece = builder.then(piece, repetition())
        }
        return piece
    }

    private fun repetition(): PatternPiece {
        var piece = atom()
        while (at < pattern.length && pattern[at] in REPEATS) {
            piece = builder.repeat(piece, pattern[at])
            at++
        }
        return piece
    }

    private fun atom(): PatternPiece {
        val c = pattern[at++]
        require(c !in REFUSED) { "'$c' at position $at repeats nothing or is not allowed (no counted repetition)." }
        return when (c) {
            '(' -> group()
            '[' -> builder.one(PatternKind.CHAR, bracket())
            '\\' -> builder.one(PatternKind.CHAR, escape())
            '.' -> builder.one(PatternKind.CHAR, ANY)
            '^' -> builder.one(PatternKind.START)
            '$' -> builder.one(PatternKind.END)
            else -> builder.one(PatternKind.CHAR, literal(c))
        }
    }

    private fun group(): PatternPiece {
        require(!peek('?')) { "(? at position $at: no lookaround nor special group." }
        val inner = alternation()
        require(peek(')')) { "a ( is never closed." }
        at++
        return inner
    }

    private fun escape(): (Char) -> Boolean {
        require(at < pattern.length) { "the pattern ends with a \\." }
        val c = pattern[at++]
        val known = CLASSES[c]
        require(known != null || c in ESCAPED) { "\\$c at position $at is not allowed (no backreference)." }
        return known ?: literal(c)
    }

    private fun bracket(): (Char) -> Boolean {
        val negated = peek('^')
        if (negated) at++
        val members = mutableListOf<(Char) -> Boolean>()
        while (!peek(']')) {
            require(at < pattern.length) { "a [ is never closed." }
            members += member()
        }
        at++
        require(members.isNotEmpty()) { "a class [] reads no character." }
        return { c -> members.any { it(c) } != negated }
    }

    // A character of a class, a range of two plain characters, or an escape.
    private fun member(): (Char) -> Boolean {
        val c = pattern[at++]
        val range = c != '\\' && peek('-') && at + 1 < pattern.length && pattern[at + 1] != ']'
        return when {
            c == '\\' -> escape()
            range -> {
                val to = pattern[at + 1]
                at += 2
                require(c <= to) { "the range $c-$to is reversed." }
                { x -> x in c..to }
            }
            else -> literal(c)
        }
    }

    private fun peek(c: Char): Boolean = at < pattern.length && pattern[at] == c
}

private fun literal(c: Char): (Char) -> Boolean = { it == c }

// The match is always the first state; NONE is a state not set.
private const val MATCH = 0
private const val NONE = -1
private const val REPEATS = "*+?"
private const val REFUSED = "*+?{}]"
private const val ESCAPED = "\\.^$|?*+()[]{}-/"
private val NEVER: (Char) -> Boolean = { false }
private val ANY: (Char) -> Boolean = { it != '\n' }
private val CLASSES: Map<Char, (Char) -> Boolean> = mapOf(
    'd' to { c -> c in '0'..'9' },
    'w' to { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' },
    's' to { c -> c == ' ' || c in '\t'..'\r' },
)
