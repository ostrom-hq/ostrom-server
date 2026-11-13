// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.sharing.presentation

import run.ostrom.server.kernel.auth.authenticated
import run.ostrom.server.kernel.database.storableInstantOrNull
import run.ostrom.server.secrets.domain.RelayScopes
import run.ostrom.server.secrets.presentation.requireCaller
import run.ostrom.server.secrets.presentation.toIdOr
import run.ostrom.server.sharing.domain.GroupChange
import run.ostrom.server.sharing.domain.GroupDraft
import run.ostrom.server.sharing.domain.GroupNotFoundException
import run.ostrom.server.sharing.domain.InvalidGroupException
import run.ostrom.server.sharing.domain.NamedGroup
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * The group routes: every member of the organization reads its groups (`accounts:read`), owners and admins change
 * them (`accounts:write`, then the use cases' role check). Another organization's group is a 404, like a missing one.
 *
 * @param dependencies The use cases.
 */
fun Route.groupRoutes(dependencies: GroupRoutesDependencies) = with(dependencies) {
    authenticated {
        get<GroupsApi> {
            val caller = call.requireCaller(callers, RelayScopes.ACCOUNTS_READ)
            call.respond(GroupsResponse(groups.list(caller).map { it.toResponse() }))
        }
        post<GroupsApi> {
            val caller = call.requireCaller(callers, RelayScopes.ACCOUNTS_WRITE)
            val request = call.receive<CreateGroupRequest>()
            val group = groups.create(caller, GroupDraft(request.name, request.members, request.accounts))
            call.respond(HttpStatusCode.Created, group.toResponse())
        }
        get<GroupsApi.Id> { params ->
            val caller = call.requireCaller(callers, RelayScopes.ACCOUNTS_READ)
            call.respond(groups.get(caller, params.id.toIdOr(::GroupNotFoundException)).toResponse())
        }
        patch<GroupsApi.Id> { params ->
            val caller = call.requireCaller(callers, RelayScopes.ACCOUNTS_WRITE)
            val request = call.receive<UpdateGroupRequest>()
            val id = params.id.toIdOr(::GroupNotFoundException)
            call.respond(groups.update(caller, id, GroupChange(request.name, request.accounts)).toResponse())
        }
        delete<GroupsApi.Id> { params ->
            val caller = call.requireCaller(callers, RelayScopes.ACCOUNTS_WRITE)
            groups.delete(caller, params.id.toIdOr(::GroupNotFoundException))
            call.respond(HttpStatusCode.NoContent)
        }
        put<GroupsApi.Id.Member> { params ->
            val caller = call.requireCaller(callers, RelayScopes.ACCOUNTS_WRITE)
            members.add(caller, params.parent.id.toIdOr(::GroupNotFoundException), params.login)
            call.respond(HttpStatusCode.NoContent)
        }
        delete<GroupsApi.Id.Member> { params ->
            val caller = call.requireCaller(callers, RelayScopes.ACCOUNTS_WRITE)
            members.remove(caller, params.parent.id.toIdOr(::GroupNotFoundException), params.login)
            call.respond(HttpStatusCode.NoContent)
        }
        post<GroupsApi.Id.Member.Pause> { params ->
            val caller = call.requireCaller(callers, RelayScopes.ACCOUNTS_WRITE)
            val until = call.receive<PauseGroupMemberRequest>().until?.let(::storableInstantOrNull)
                ?: throw InvalidGroupException("until is an ISO 8601 instant.")
            val member = params.parent
            members.pause(caller, member.parent.id.toIdOr(::GroupNotFoundException), member.login, until)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private fun NamedGroup.toResponse() = GroupResponse(
    id = id.toString(),
    name = name,
    members = members,
    accounts = accounts,
    paused = paused.map { PausedMemberDto(it.login, it.until.toString()) },
)
