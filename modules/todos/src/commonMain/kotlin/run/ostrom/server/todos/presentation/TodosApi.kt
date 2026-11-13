// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.todos.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * `GET` and `POST /api/v1/todos/session` (`getSessionTodo`, `createSessionTodo`), and the routes of the session's
 * items, context, stops and hook errors, as in ostrom-core's `api/openapi.yaml` (tag `todos`). A read names its
 * session in the query, a change in its body.
 *
 * @property sessionId The session a read names.
 */
@Serializable
@Resource("/api/v1/todos/session")
class TodoSessionApi(val sessionId: String? = null) {
    /**
     * `POST /api/v1/todos/session/items` (`addTodoItem`).
     *
     * @property parent The session's todo.
     */
    @Serializable
    @Resource("items")
    class Items(val parent: TodoSessionApi = TodoSessionApi()) {
        /**
         * An item of the list.
         *
         * @property parent The items.
         * @property item The item's id.
         */
        @Serializable
        @Resource("{item}")
        class Item(val parent: Items = Items(), val item: String) {
            /**
             * `POST …/start` (`startTodoItem`).
             *
             * @property parent The item.
             */
            @Serializable
            @Resource("start")
            class Start(val parent: Item)

            /**
             * `POST …/notes` (`noteTodoItem`).
             *
             * @property parent The item.
             */
            @Serializable
            @Resource("notes")
            class Notes(val parent: Item)

            /**
             * `POST …/proof` (`completeTodoItem`).
             *
             * @property parent The item.
             */
            @Serializable
            @Resource("proof")
            class Proof(val parent: Item)

            /**
             * `POST …/link` (`linkTodoItem`).
             *
             * @property parent The item.
             */
            @Serializable
            @Resource("link")
            class Link(val parent: Item)

            /**
             * `POST …/drop` (`requestTodoDrop`).
             *
             * @property parent The item.
             */
            @Serializable
            @Resource("drop")
            class Drop(val parent: Item)
        }
    }

    /**
     * `GET /api/v1/todos/session/context?sessionId=` (`readTodoContext`).
     *
     * @property parent The session's todo, with the session named.
     */
    @Serializable
    @Resource("context")
    class Context(val parent: TodoSessionApi = TodoSessionApi())

    /**
     * `POST /api/v1/todos/session/stop` (`stopTodoSession`).
     *
     * @property parent The session's todo.
     */
    @Serializable
    @Resource("stop")
    class Stop(val parent: TodoSessionApi = TodoSessionApi())

    /**
     * `POST /api/v1/todos/session/hook-error` (`recordTodoHookError`).
     *
     * @property parent The session's todo.
     */
    @Serializable
    @Resource("hook-error")
    class HookError(val parent: TodoSessionApi = TodoSessionApi())

    /**
     * `POST /api/v1/todos/session/reset` (`resetTodo`).
     *
     * @property parent The session's todo.
     */
    @Serializable
    @Resource("reset")
    class Reset(val parent: TodoSessionApi = TodoSessionApi())
}

/** `GET /api/v1/todos/stop-hook` (`listTodoStopHooks`): the members whose Stop hook is off. */
@Serializable
@Resource("/api/v1/todos/stop-hook")
class TodoStopHookApi {
    /**
     * `PUT /api/v1/todos/stop-hook/members/{memberId}` (`setMemberTodoStopHook`).
     *
     * @property parent The switches.
     * @property memberId The member's id.
     */
    @Serializable
    @Resource("members/{memberId}")
    class Member(val parent: TodoStopHookApi = TodoStopHookApi(), val memberId: String)
}
