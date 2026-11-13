// SPDX-License-Identifier: AGPL-3.0-only
package run.ostrom.server.todos.domain

/**
 * The bounds of what an agent writes: a list's reference, an item and its checks, the dependencies of a list. Pure:
 * nothing here reads a store or a clock.
 */
internal object TodoValidation {
    private val slug = Regex("[a-z0-9][a-z0-9-]{0,${TodoRules.MAX_ID - 1}}")
    private val repo = Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")

    /** A PR or a release written in advance: `pull/12`, `pulls/12`, `PR #12`, `releases/tag/v1.2.3`. */
    private val guessed = Regex("""(?i)(pulls?/\d+|\bPR\s*#?\d+|releases/tag/v?\d)""")
    private val cited = Regex("""\$\{(?:pr|release):([^.}]+)\.""")

    /** Refuses a blank text or one longer than [max]. */
    fun text(value: String, max: Int, what: String) {
        if (value.isBlank() || value.length > max) {
            throw invalidTodo("invalid_todo", "Use $what of 1 to $max characters.")
        }
    }

    /** Checks a list's reference and its refs. */
    fun reference(reference: String, refs: List<TodoRef>) {
        text(reference, TodoRules.MAX_REFERENCE, "a reference")
        if (refs.size > TodoRules.MAX_REFS) {
            throw invalidTodo("invalid_todo", "At most ${TodoRules.MAX_REFS} references.")
        }
        refs.forEach(::ref)
    }

    private fun ref(ref: TodoRef) {
        val ok = when (ref.kind) {
            "artifact", "session" -> ref.id != null && ref.id.length in 1..MAX_REF_ID && ref.url == null
            "url" -> ref.url != null && ref.url.startsWith("https://") && ref.url.length <= MAX_URL && ref.id == null
            else -> false
        }
        if (!ok || (ref.title?.length ?: 0) > TodoRules.MAX_NAME) {
            throw invalidTodo("invalid_todo", "A reference is an artifact or a session by id, or an https url.")
        }
    }

    /** Checks one item on its own: its bounds and its checks. Dependencies and citations need the whole list. */
    fun item(item: TodoItemDefinition) {
        if (!slug.matches(item.id)) {
            throw invalidTodo(
                "invalid_todo",
                "Item id '${item.id.take(MAX_ECHO)}' must be a slug of at most ${TodoRules.MAX_ID} characters: " +
                    "lowercase letters, digits and dashes.",
            )
        }
        text(item.name, TodoRules.MAX_NAME, "a name")
        if (item.description.length !in TodoRules.MIN_DESCRIPTION..TodoRules.MAX_DESCRIPTION) {
            throw invalidTodo(
                "description_too_short",
                "Item ${item.id}: write a complete description of ${TodoRules.MIN_DESCRIPTION} to " +
                    "${TodoRules.MAX_DESCRIPTION} characters (what, where, why, what is out of scope), enough for a " +
                    "new agent to do it alone.",
            )
        }
        text(item.doneWhen.text, TodoRules.MAX_DONE_TEXT, "a done condition text")
        if (item.doneWhen.checks.isEmpty()) {
            throw invalidTodo(
                "check_required",
                "Item ${item.id} has no typed check: add one of pr_merged, release_contains, ci_green, command, " +
                    "artifact_published, wait_answered, or attested if nothing can be checked.",
            )
        }
        if (item.doneWhen.checks.size > TodoRules.MAX_CHECKS) {
            throw invalidTodo("invalid_check", "Item ${item.id}: at most ${TodoRules.MAX_CHECKS} checks.")
        }
        if (item.dependsOn.size > TodoRules.MAX_ITEMS) {
            throw invalidTodo("invalid_todo", "Item ${item.id}: at most ${TodoRules.MAX_ITEMS} dependencies.")
        }
        item.doneWhen.checks.forEach { check(item.id, it) }
    }

    private fun check(itemId: String, check: TodoCheck) {
        when (check) {
            is TodoCheck.PrMerged -> repo(itemId, check, check.repo)
            is TodoCheck.PrClosed -> {
                repo(itemId, check, check.repo)
                text(check.reason, TodoRules.MAX_REASON, "a reason")
            }
            is TodoCheck.ReleaseContains -> {
                repo(itemId, check, check.repo)
                if (check.of == null && check.commit == null) {
                    throw invalidTodo(
                        "invalid_check",
                        "Item $itemId: release_contains needs of (the id of the item whose PR is shipped) or commit.",
                    )
                }
                check.commit?.let { text(it, TodoRules.MAX_NAME, "a commit") }
            }
            is TodoCheck.CiGreen -> {
                repo(itemId, check, check.repo)
                if (check.of == null && check.ref == null) {
                    throw invalidTodo("invalid_check", "Item $itemId: ci_green needs of (the id of an item) or ref.")
                }
                check.ref?.let { noGuess(itemId, it) }
            }
            is TodoCheck.Command -> command(itemId, check)
            is TodoCheck.ArtifactPublished -> if (check.minVersion != null && check.minVersion < 1) {
                throw invalidTodo("invalid_check", "Item $itemId: minVersion starts at 1.")
            }
            is TodoCheck.WaitAnswered -> check.action?.let { text(it, TodoRules.MAX_NAME, "an action") }
            is TodoCheck.Attested -> text(check.what, TodoRules.MAX_DONE_TEXT, "what is attested")
        }
    }

    private fun command(itemId: String, check: TodoCheck.Command) {
        text(check.run, TodoRules.MAX_COMMAND, "a command")
        check.cwd?.let { text(it, TodoRules.MAX_COMMAND, "a working directory") }
        if (check.timeoutSeconds !in 1..TodoRules.MAX_COMMAND_SECONDS) {
            throw invalidTodo(
                "invalid_check",
                "Item $itemId: a command times out in 1 to ${TodoRules.MAX_COMMAND_SECONDS} seconds.",
            )
        }
        noGuess(itemId, check.run)
    }

    private fun repo(itemId: String, check: TodoCheck, value: String) {
        if (value.length > MAX_REPO || !repo.matches(value)) {
            throw invalidTodo("invalid_check", "Item $itemId: ${check.type} needs repo as owner/name.")
        }
    }

    private fun noGuess(itemId: String, value: String) {
        if (guessed.containsMatchIn(value)) {
            throw invalidTodo(
                "guessed_number",
                "Item $itemId: do not put a PR number or a version you expect: use pr_merged with the repo and put " +
                    "the marker of todo_start in the PR body.",
            )
        }
    }

    /** Items cited by a check (`of`, `${pr:x.number}`), each of which must exist in the list. */
    private fun cites(item: TodoItemDefinition): List<String> = item.doneWhen.checks.flatMap { check ->
        when (check) {
            is TodoCheck.ReleaseContains -> listOfNotNull(check.of)
            is TodoCheck.CiGreen -> listOfNotNull(check.of)
            is TodoCheck.Command -> cited.findAll(check.run).map { it.groupValues[1] }.toList()
            else -> emptyList()
        }
    }

    /** Refuses a dependency or a citation of an item that is not in [known], or of the item itself. */
    fun links(item: TodoItemDefinition, known: Set<String>) {
        item.dependsOn.forEach {
            if (it == item.id) throw invalidTodo("dependency_cycle", "Item ${item.id} depends on itself.")
            if (it !in known) {
                throw invalidTodo(
                    "unknown_dependency",
                    "Item ${item.id} depends on '${it.take(MAX_ECHO)}', which is not in the list.",
                )
            }
        }
        cites(item).forEach {
            if (it !in known) {
                throw invalidTodo(
                    "unknown_dependency",
                    "Item ${item.id} cites '${it.take(MAX_ECHO)}' in a check, which is not in the list.",
                )
            }
        }
    }

    /** Refuses dependencies that form a cycle. */
    fun acyclic(items: List<TodoItemDefinition>) {
        val byId = items.associateBy { it.id }
        // false: on the path, true: cleared.
        val state = mutableMapOf<String, Boolean>()
        fun visit(id: String) {
            if (state[id] == true) return
            if (state[id] == false) throw invalidTodo("dependency_cycle", "The dependencies of '$id' form a cycle.")
            state[id] = false
            byId.getValue(id).dependsOn.forEach(::visit)
            state[id] = true
        }
        byId.keys.forEach(::visit)
    }

    private const val MAX_REPO = 200
    private const val MAX_REF_ID = 200
    private const val MAX_URL = 2_000
}
