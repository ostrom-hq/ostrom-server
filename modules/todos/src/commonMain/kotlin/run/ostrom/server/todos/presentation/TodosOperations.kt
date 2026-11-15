// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.todos.presentation

import run.ostrom.core.api.models.AddTodoItemRequest
import run.ostrom.core.api.models.CompleteTodoItemRequest
import run.ostrom.core.api.models.CreateTodoRequest
import run.ostrom.core.api.models.DropTodoItemRequest
import run.ostrom.core.api.models.LinkTodoItemRequest
import run.ostrom.core.api.models.NoteTodoItemRequest
import run.ostrom.core.api.models.ResetTodoRequest
import run.ostrom.core.api.models.SessionTodo
import run.ostrom.core.api.models.StartTodoItemRequest
import run.ostrom.core.api.models.StartTodoItemResponse
import run.ostrom.core.api.models.TodoContext
import run.ostrom.core.api.models.TodoHookErrorRequest
import run.ostrom.core.api.models.TodoStopDecision
import run.ostrom.core.api.models.TodoStopHook
import run.ostrom.core.api.models.TodoStopHookRequest
import run.ostrom.core.api.models.TodoStopHooks
import run.ostrom.core.api.models.TodoStopRequest
import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId
import run.ostrom.server.kernel.http.docs.queryText
import io.ktor.http.HttpStatusCode

private const val TAG = "todos"

private const val CALLERS = "Callers: the session's agent token, confined to its own session, which it " +
    "names in `sessionId` (400 `invalid_request` without it; a cloud session's agent token acts on its cloud " +
    "session whatever it names, and needs none), and the session's owner, a person, who names `sessionId` too. " +
    "An owner or an administrator of the organization reads any session's todo but never changes it " +
    "(`forbidden`). A service key, a device or a runner token gets `forbidden`. Another person's session, a " +
    "session of another organization or an unknown one answers `not_found`."

private const val CLOSED = "The list is done or abandoned (`todo_closed`)."

private const val FORBID = "A service key, a device or a runner token, an owner or an administrator who is " +
    "not the session's person, or a token that may not write here (`forbidden`)."

private const val ITEM = "The item's id: a slug of at most 40 characters; any other value answers 404 " +
    "`unknown_item`."

private const val LIST_400 = "An invalid list: `invalid_todo` (a text too long, a bad slug, more than 10 " +
    "refs), `description_too_short` (a description outside 80 to 4000 characters), `check_required` (an item " +
    "without a typed check), `invalid_check`, `guessed_number` (a check naming a PR number or a version), " +
    "`too_many_items` (more than 500), `duplicate_item`, `unknown_dependency` or `dependency_cycle`; or a call " +
    "that names no session (`invalid_request`)."

private const val NEEDS_SESSION = "A person's call that names no session (`invalid_request`)."

private const val SESSION = "The session (its id, or one of its segments `~r<n>`), as its owner or its " +
    "agent token names it; a cloud session's agent token needs none."

private const val S_NF = "No such session for the caller, or none the caller may reach (`not_found`)."

/**
 * The todos module's operations: a session's todo list and its items, the reminder block and the Stop hook's
 * questions, and the administrators' switch of the Stop hook per member. The answers are ostrom-core's models.
 */
internal fun ApiCatalog.todosOperations() {
    tag(TAG, "A session's todo: the plan an agent works through, the proof of each item, and the Stop hook " +
        "that holds the agent to it.")
    listOperations()
    contextOperations()
    itemOperations()
    itemNoteOperations()
    itemLinkOperations()
    stopOperations()
    resetOperations()
    stopHookOperations()
}

/** Reading and creating a session's todo. */
private fun ApiCatalog.listOperations() {
    get("/api/v1/todos/session") {
        operationId = "getSessionTodo"
        tag(TAG)
        summary = "Read a session's todo."
        description = "The list with its items in order, the last proof of each, its hook events and " +
            "`nextItemId`; `todo` is null while the session has none. Answers to drop requests are applied on " +
            "this read: yes drops the item, no or a cancelled wait puts it back to `todo`. " +
            CALLERS
        parameters {
            organizationHeader()
            queryText("sessionId", SESSION)
        }
        responses {
            ok<SessionTodo>("The session's todo, null while it has none.")
            badRequest(NEEDS_SESSION)
            error(HttpStatusCode.Forbidden, FORBID)
            notFound(S_NF)
        }
    }
    post("/api/v1/todos/session") {
        operationId = "createSessionTodo"
        tag(TAG)
        summary = "Create a session's todo."
        description = "Once per session: the plan `reference`, optional `refs` and every item in order, " +
            "each with a complete description and at least one typed check. No check names a PR number or a " +
            "version: the PR is found by the marker `startTodoItem` returns. `resetTodo` clears a closed list " +
            "so that another can be created. " +
            CALLERS
        parameters {
            organizationHeader()
        }
        body<CreateTodoRequest>("The session, the plan's reference and refs, and the items with their " +
            "dependencies and checks.")
        responses {
            ok<SessionTodo>("The todo, created.", HttpStatusCode.Created)
            badRequest(LIST_400)
            error(HttpStatusCode.Forbidden, FORBID)
            notFound(S_NF)
            conflict("The session already has a todo (`todo_exists`).")
        }
    }
}

/** The reminder block of a session's todo. */
private fun ApiCatalog.contextOperations() {
    get("/api/v1/todos/session/context") {
        operationId = "readTodoContext"
        tag(TAG)
        summary = "Read the reminder block of a session's todo."
        description = "The plan reference, the item in progress (else the next one) in full with its last " +
            "check, then the list one line per item, with no instruction to go on. Read by the `SessionStart` " +
            "hook (`ostrom hook todo-context`) after a compaction and on resume; `text` is null without an " +
            "active list. " +
            CALLERS
        parameters {
            organizationHeader()
            queryText("sessionId", SESSION)
        }
        responses {
            ok<TodoContext>("The reminder block, null without an active list.")
            badRequest(NEEDS_SESSION)
            error(HttpStatusCode.Forbidden, FORBID)
            notFound(S_NF)
        }
    }
}

/** Adding an item and starting it. */
private fun ApiCatalog.itemOperations() {
    post("/api/v1/todos/session/items") {
        operationId = "addTodoItem"
        tag(TAG)
        summary = "Add an item found on the way."
        description = "Marked `discovered` with its `reason`, placed after the item in progress and after " +
            "its own dependencies. The same bounds as the creation. " +
            CALLERS
        parameters {
            organizationHeader()
        }
        body<AddTodoItemRequest>("The session, the item with its checks, and why it is added.")
        responses {
            ok<SessionTodo>("The todo, with the item added.")
            badRequest("An invalid item or reason, a list of 500 items already, an id already used " +
                "(`duplicate_item`), a dependency that does not exist or forms a cycle, or a call that names no " +
                "session (`invalid_request`); see `createSessionTodo` for the keys.")
            error(HttpStatusCode.Forbidden, FORBID)
            notFound("No such session for the caller (`not_found`), or the session has no todo " +
                "(`todo_not_found`).")
            conflict(CLOSED)
        }
    }
    post("/api/v1/todos/session/items/{item}/start") {
        operationId = "startTodoItem"
        tag(TAG)
        summary = "Start an item, and get the marker for its pull request."
        description = "Puts the item in progress; another item in progress goes back to `todo`, unless this " +
            "one is given to a background agent (`background`). `marker` (`<!-- ostrom-todo: <list8>/<item> " +
            "-->`, `list8` the first 8 characters of the list's id) goes in the body of the item's pull " +
            "request, which the checks find by it. " +
            CALLERS
        parameters {
            organizationHeader()
            pathId("item", ITEM)
        }
        body<StartTodoItemRequest>("The session, and whether the item is given to a background agent, with " +
            "its name.")
        responses {
            ok<StartTodoItemResponse>("The todo, and the marker for the item's pull request.")
            badRequest(NEEDS_SESSION)
            error(HttpStatusCode.Forbidden, FORBID)
            notFound("No such session for the caller (`not_found`), the session has no todo " +
                "(`todo_not_found`), or no such item (`unknown_item`).")
            conflict("The list is done or abandoned (`todo_closed`), a dependency is neither done nor " +
                "dropped (`dependency_open`), the item is done or dropped (`item_closed`), or a drop waits for " +
                "the owner (`drop_pending`).")
        }
    }
}

/** Noting and proving an item. */
private fun ApiCatalog.itemNoteOperations() {
    post("/api/v1/todos/session/items/{item}/notes") {
        operationId = "noteTodoItem"
        tag(TAG)
        summary = "Append a dated note to an item."
        description = "Notes are only appended, never rewritten: at most 2000 characters each and 100 per " +
            "item. " +
            CALLERS
        parameters {
            organizationHeader()
            pathId("item", ITEM)
        }
        body<NoteTodoItemRequest>("The session and the note.")
        responses {
            ok<SessionTodo>("The todo, with the note appended.")
            badRequest("A note over 2000 characters (`invalid_todo`), an item with 100 notes " +
                "(`too_many_notes`), or a call that names no session (`invalid_request`).")
            error(HttpStatusCode.Forbidden, FORBID)
            notFound("No such session for the caller (`not_found`), the session has no todo " +
                "(`todo_not_found`), or no such item (`unknown_item`).")
            conflict(CLOSED)
        }
    }
    post("/api/v1/todos/session/items/{item}/proof") {
        operationId = "completeTodoItem"
        tag(TAG)
        summary = "Report what an item's checks observed."
        description = "`checks` gives, by position in the item's stored condition, whether each check " +
            "passed, what was observed and https URLs. The condition is the stored one: the caller neither " +
            "chooses nor changes the checks. `artifact_published` and `wait_answered` are verified by the " +
            "server through the item's links, whatever the report says. The item is done only if every check " +
            "passed: a failed, unknown or unreported check refuses the call and the proof is kept on the item. " +
            CALLERS
        parameters {
            organizationHeader()
            pathId("item", ITEM)
        }
        body<CompleteTodoItemRequest>("The session, and the report of each check by its position.")
        responses {
            ok<SessionTodo>("The todo, with the item done.")
            badRequest("A position outside the condition or given twice, an `observed` over 2000 " +
                "characters, more than 10 URLs or one that is not https (`invalid_proof`), or a call that names " +
                "no session (`invalid_request`).")
            error(HttpStatusCode.Forbidden, FORBID)
            notFound("No such session for the caller (`not_found`), the session has no todo " +
                "(`todo_not_found`), or no such item (`unknown_item`).")
            conflict("A check did not pass, or was not reported, and the message says what is missing " +
                "(`not_done`); the item is dropped or a drop is requested (`item_closed`); or the list is done " +
                "or abandoned (`todo_closed`).")
        }
    }
}

/** Linking an item and asking to drop it. */
private fun ApiCatalog.itemLinkOperations() {
    post("/api/v1/todos/session/items/{item}/link") {
        operationId = "linkTodoItem"
        tag(TAG)
        summary = "Bind a pull request, a release, an artifact or a wait to an item."
        description = "`kind` is `pr` or `release` (their https address in `ref`), `artifact` or `wait` " +
            "(their id). A link is frozen: the same `ref` may be completed with `number`, `sha` (a PR's merge " +
            "commit) or `tag`; another one of the same kind is refused, except to replace a pull request closed " +
            "without merge (`replaceClosed`), which a note records. " +
            CALLERS
        parameters {
            organizationHeader()
            pathId("item", ITEM)
        }
        body<LinkTodoItemRequest>("The session, the kind of link, its reference and what completes it.")
        responses {
            ok<SessionTodo>("The todo, with the link bound.")
            badRequest("An unknown kind, a reference outside 1 to 2000 characters, an address that is not " +
                "https for a `pr` or a `release` (`invalid_link`), or a call that names no session " +
                "(`invalid_request`).")
            error(HttpStatusCode.Forbidden, FORBID)
            notFound("No such session for the caller (`not_found`), the session has no todo " +
                "(`todo_not_found`), or no such item (`unknown_item`).")
            conflict("The item has another link of that kind (`already_linked`), or the list is done or " +
                "abandoned (`todo_closed`).")
        }
    }
    post("/api/v1/todos/session/items/{item}/drop") {
        operationId = "requestTodoDrop"
        tag(TAG)
        summary = "Ask the session's owner to drop an item."
        description = "Opens an ordinary wait for the session's owner (actions `yes` and `no`) and puts the " +
            "item in `drop_requested`; it is dropped only on the owner's yes, applied on the next read or " +
            "change. Nothing is ever deleted. " +
            CALLERS
        parameters {
            organizationHeader()
            pathId("item", ITEM)
        }
        body<DropTodoItemRequest>("The session, and why the item should be dropped.")
        responses {
            ok<SessionTodo>("The todo, with the item in `drop_requested`.")
            badRequest("A reason over 1000 characters (`invalid_todo`), or a call that names no session " +
                "(`invalid_request`).")
            error(HttpStatusCode.Forbidden, FORBID)
            notFound("No such session for the caller (`not_found`), the session has no todo " +
                "(`todo_not_found`), or no such item (`unknown_item`).")
            conflict("The item is done or dropped (`item_closed`), a drop already waits (`drop_pending`), " +
                "or the list is done or abandoned (`todo_closed`).")
        }
    }
}

/** The Stop hook's questions and failures. */
private fun ApiCatalog.stopOperations() {
    post("/api/v1/todos/session/stop") {
        operationId = "stopTodoSession"
        tag(TAG)
        summary = "Decide what a stop of the agent leads to."
        description = "Asked by the Stop hook (`ostrom hook todo-stop`) up to three times per stop. `begin` " +
            "answers `allow` (`why`: `no_list`; `hook_disabled` when an administrator turned the hook off for " +
            "the session's owner, before anything is run or written; `finished`; `background_task`; `wait_open` " +
            "when any wait of the session is open) or `verify`. On `verify` the hook runs the checks it can, " +
            "reads the transcript for the pull requests the session opened (`orphans`), and sends their " +
            "outcome: the server ticks what passed, verifies `artifact_published` and `wait_answered` itself, " +
            "adds each orphan PR no item covers as a `discovered` item, counts the stop and answers `settle` " +
            "(every item is done or dropped), `block` (`reason`: the resume message for the agent) or `allow`. " +
            "On `settle` the hook re-runs every check of the done items: all hold, the list is `done` and the " +
            "answer `allow` (`settled`); else a regressed item is reopened and the stop blocked. The third " +
            "blocked stop in a row with no item finished, added or noted opens a wait for the owner, resets the " +
            "counter and answers `allow` (`escalated`). The hook fails open and reports its failures with " +
            "`recordTodoHookError`. " +
            CALLERS
        parameters {
            organizationHeader()
        }
        body<TodoStopRequest>("The session, the phase (`begin`, `verify` or `settle`), and what the hook " +
            "observed.")
        responses {
            ok<TodoStopDecision>("What the stop leads to, and the todo.")
            badRequest("An unknown phase, an item reported twice, more than 500 items or 5 orphans " +
                "(`invalid_stop`), a report that is out of range or too long (`invalid_proof`), or a call that " +
                "names no session (`invalid_request`).")
            error(HttpStatusCode.Forbidden, FORBID)
            notFound(S_NF)
        }
    }
    post("/api/v1/todos/session/hook-error") {
        operationId = "recordTodoHookError"
        tag(TAG)
        summary = "Record a failure of the Stop hook on the list."
        description = "The hook fails open (a broken hook never holds the agent) and says so here: the " +
            "event `hook_error`, its text kept to 500 characters, joins `hookEvents` (the newest 20). " +
            CALLERS
        parameters {
            organizationHeader()
        }
        body<TodoHookErrorRequest>("The session and what failed.")
        responses {
            ok<SessionTodo>("The todo, with the event recorded.")
            badRequest(NEEDS_SESSION)
            error(HttpStatusCode.Forbidden, FORBID)
            notFound("No such session for the caller (`not_found`), or the session has no todo " +
                "(`todo_not_found`).")
        }
    }
}

/** Clearing a closed todo. */
private fun ApiCatalog.resetOperations() {
    post("/api/v1/todos/session/reset") {
        operationId = "resetTodo"
        tag(TAG)
        summary = "Clear a closed todo."
        description = "Deletes the session's todo and its items once it is closed (`done` or `abandoned`), " +
            "as if the session never had one, so `createSessionTodo` can create a new one; the answer's `todo` " +
            "is null. The deletion is written to the organization's audit log (`todo.reset`: the list's id, its " +
            "status and its number of items, not its content). The session's agent token and its owner, a " +
            "person, call it; an owner or an administrator gets `forbidden`, as does a service key, a device or " +
            "a runner token. Another person's session, a session of another organization or an unknown one " +
            "answers `not_found`."
        parameters {
            organizationHeader()
        }
        body<ResetTodoRequest>("The session.")
        responses {
            ok<SessionTodo>("The todo is cleared: `todo` is null.")
            badRequest(NEEDS_SESSION)
            error(HttpStatusCode.Forbidden, FORBID)
            notFound("No such session for the caller (`not_found`), or the session has no todo " +
                "(`todo_not_found`).")
            conflict("The list is still active: a reset is no way to skip an unfinished item " +
                "(`todo_active`).")
        }
    }
}

/** The per-member switch of the Stop hook, in the console. */
private fun ApiCatalog.stopHookOperations() {
    get("/api/v1/todos/stop-hook") {
        operationId = "listTodoStopHooks"
        tag(TAG)
        summary = "List the members whose todo Stop hook is off."
        description = "A member has the hook on until an administrator turns it off. An owner or an " +
            "administrator of the organization, with a browser session only: any token (a personal or agent " +
            "token, a service key) gets `forbidden`, as does a member."
        parameters {
            organizationHeader()
        }
        responses {
            ok<TodoStopHooks>("The members whose hook is off.")
            error(HttpStatusCode.Forbidden, "Not an owner or an administrator with a browser session " +
                "(`forbidden`).")
            notFound("The caller acts for no organization (`not_found`).")
        }
    }
    put("/api/v1/todos/stop-hook/members/{memberId}") {
        operationId = "setMemberTodoStopHook"
        tag(TAG)
        summary = "Turn the todo Stop hook on or off for a member."
        description = "When off, every `begin` of a stop of the sessions this member owns answers `allow` " +
            "(`hook_disabled`) before anything is run, counted or written; their lists are still kept, verified " +
            "by `completeTodoItem` and shown. An owner or an administrator of the organization, with a browser " +
            "session only: any token gets `forbidden`."
        parameters {
            organizationHeader()
            pathId("memberId", "The member's id (`Member.id`), not a login.")
        }
        body<TodoStopHookRequest>("Whether the hook is on for the member.")
        responses {
            ok<TodoStopHook>("The member's switch, as saved.")
            error(HttpStatusCode.Forbidden, "Not an owner or an administrator with a browser session " +
                "(`forbidden`).")
            notFound("The caller acts for no organization, or the member is not one of the organization " +
                "(`not_found`).")
        }
    }
}
