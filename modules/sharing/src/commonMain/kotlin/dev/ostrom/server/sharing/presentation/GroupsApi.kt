// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.sharing.presentation

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * `/api/v1/groups`: the groups of the caller's organization, as in ostrom-core's `openapi.yaml` (`listGroups`,
 * `createGroup`).
 */
@Serializable
@Resource("/api/v1/groups")
class GroupsApi {
    /**
     * `/api/v1/groups/{id}`: one group (`getGroup`, `updateGroup`, `deleteGroup`).
     *
     * @property id The group.
     */
    @Serializable
    @Resource("{id}")
    class Id(val parent: GroupsApi = GroupsApi(), val id: String) {
        /**
         * `/api/v1/groups/{id}/members/{login}`: one member (`addGroupMember`, `removeGroupMember`).
         *
         * @property login The member's login.
         */
        @Serializable
        @Resource("members/{login}")
        class Member(val parent: Id, val login: String) {
            /** `/api/v1/groups/{id}/members/{login}/pause` (`pauseGroupMember`). */
            @Serializable
            @Resource("pause")
            class Pause(val parent: Member)
        }
    }
}

/** Makes a group: members by login, accounts by id. */
@Serializable
data class CreateGroupRequest(
    val name: String,
    val members: List<String> = emptyList(),
    val accounts: List<String> = emptyList(),
)

/** Changes a group: each field given replaces the current value. */
@Serializable
data class UpdateGroupRequest(val name: String? = null, val accounts: List<String>? = null)

/** Pauses a member until an ISO 8601 instant. */
@Serializable
data class PauseGroupMemberRequest(val until: String? = null)

/** A member paused now, and until when. */
@Serializable
data class PausedMemberDto(val login: String, val until: String)

/** The contract's `Group`, with the members paused now. */
@Serializable
data class GroupResponse(
    val id: String,
    val name: String,
    val members: List<String>,
    val accounts: List<String>,
    val paused: List<PausedMemberDto>,
)

/** The organization's groups. */
@Serializable
data class GroupsResponse(val items: List<GroupResponse>)
