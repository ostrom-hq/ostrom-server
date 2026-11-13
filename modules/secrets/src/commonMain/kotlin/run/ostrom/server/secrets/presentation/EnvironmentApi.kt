// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.secrets.presentation

import run.ostrom.server.secrets.domain.CallerResolver
import run.ostrom.server.secrets.domain.ManageEnvironmentAssignmentsUseCase
import run.ostrom.server.secrets.domain.ManageEnvironmentVariantsUseCase
import run.ostrom.server.secrets.domain.PersonalEnvironmentVariantsUseCase
import run.ostrom.server.secrets.domain.ResolveEnvironmentUseCase
import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/** `GET /api/v1/environment` (contract `getEnvironment`): the caller's own environment values. */
@Serializable
@Resource("/api/v1/environment")
class EnvironmentApi {
    /** `/api/v1/environment/variants` (`listEnvironmentVariants`, `createEnvironmentVariant`). */
    @Serializable
    @Resource("variants")
    class Variants(val parent: EnvironmentApi = EnvironmentApi()) {
        /** `GET /api/v1/environment/variants/personal` (`listPersonalEnvironmentVariants`). */
        @Serializable
        @Resource("personal")
        class Personal(val parent: Variants = Variants())

        /**
         * `/api/v1/environment/variants/{id}` (`updateEnvironmentVariant`, `deleteEnvironmentVariant`).
         *
         * @property parent The collection.
         * @property id The variant.
         */
        @Serializable
        @Resource("{id}")
        class Id(val parent: Variants = Variants(), val id: String) {
            /** `POST …/{id}/bulk-assign` (`bulkAssignEnvironmentVariant`). */
            @Serializable
            @Resource("bulk-assign")
            class BulkAssign(val parent: Id)

            /** `POST …/{id}/promote` (`promoteEnvironmentVariant`). */
            @Serializable
            @Resource("promote")
            class Promote(val parent: Id)
        }
    }

    /**
     * `GET /api/v1/environment/assignments` (`listEnvironmentAssignments`).
     *
     * @property parent The root.
     * @property member Keeps one member.
     */
    @Serializable
    @Resource("assignments")
    class Assignments(val parent: EnvironmentApi = EnvironmentApi(), val member: String? = null) {
        /**
         * `/api/v1/environment/assignments/{member}/{variable}` (`assignEnvironmentVariant`,
         * `unassignEnvironmentVariant`).
         *
         * @property parent The collection.
         * @property member The member's id.
         * @property variable The variable.
         */
        @Serializable
        @Resource("{member}/{variable}")
        class Slot(val parent: Assignments = Assignments(), val member: String, val variable: String)
    }

    /** `/api/v1/environment/exclusions`. */
    @Serializable
    @Resource("exclusions")
    class Exclusions(val parent: EnvironmentApi = EnvironmentApi()) {
        /**
         * `/api/v1/environment/exclusions/{member}/{variable}` (`excludeEnvironmentVariable`,
         * `includeEnvironmentVariable`).
         *
         * @property parent The collection.
         * @property member The member's id.
         * @property variable The variable.
         */
        @Serializable
        @Resource("{member}/{variable}")
        class Slot(val parent: Exclusions = Exclusions(), val member: String, val variable: String)
    }
}

/** `/api/v1/me/environment/variants` (`listMyEnvironmentVariants`, `createMyEnvironmentVariant`). */
@Serializable
@Resource("/api/v1/me/environment/variants")
class MyEnvironmentVariantsApi {
    /**
     * `/api/v1/me/environment/variants/{id}` (`updateMyEnvironmentVariant`, `deleteMyEnvironmentVariant`).
     *
     * @property parent The collection.
     * @property id The variant.
     */
    @Serializable
    @Resource("{id}")
    class Id(val parent: MyEnvironmentVariantsApi = MyEnvironmentVariantsApi(), val id: String)
}

/**
 * The contract's `EnvironmentVariantCreate`.
 *
 * @property variable The variable.
 * @property name The variant's name.
 * @property binding `shared` (default) or `perMember`.
 * @property value The shared value; never returned.
 */
@Serializable
data class EnvironmentVariantCreateRequest(
    val variable: String,
    val name: String,
    val binding: String? = null,
    val value: String? = null,
) {
    /** Never the value. */
    override fun toString(): String = "EnvironmentVariantCreateRequest(variable=$variable, name=$name)"
}

/**
 * The contract's `EnvironmentVariantUpdate`.
 *
 * @property expectedVersion The version read.
 * @property name A new name.
 * @property value A new shared value; never returned.
 */
@Serializable
data class EnvironmentVariantUpdateRequest(
    val expectedVersion: Int,
    val name: String? = null,
    val value: String? = null,
) {
    /** Never the value. */
    override fun toString(): String = "EnvironmentVariantUpdateRequest(expectedVersion=$expectedVersion, name=$name)"
}

/**
 * The contract's `EnvironmentAssign`.
 *
 * @property variantId The variant.
 * @property value The member's own value, for a per-member variant; never returned.
 */
@Serializable
data class EnvironmentAssignRequest(val variantId: String, val value: String? = null) {
    /** Never the value. */
    override fun toString(): String = "EnvironmentAssignRequest(variantId=$variantId)"
}

/** The contract's `EnvironmentVariant`: never a value. */
@Serializable
data class EnvironmentVariantResponse(
    val id: String,
    val variable: String,
    val name: String,
    val binding: String,
    val owner: String? = null,
    val hasValue: Boolean,
    val version: Int,
    val createdAt: String,
    val updatedAt: String,
)

/** The contract's `EnvironmentVariantsPage`. */
@Serializable
data class EnvironmentVariantsPageResponse(val items: List<EnvironmentVariantResponse>)

/** The contract's `EnvironmentAssignment`. */
@Serializable
data class EnvironmentAssignmentResponse(
    val member: String,
    val variable: String,
    val variantId: String,
    val variantName: String,
    val version: Int,
    val source: String,
    val personal: Boolean,
    val hasMemberValue: Boolean,
    val assignedAt: String,
)

/** The contract's `EnvironmentExclusion`. */
@Serializable
data class EnvironmentExclusionResponse(val member: String, val variable: String)

/** The contract's `EnvironmentAssignmentsPage`. */
@Serializable
data class EnvironmentAssignmentsPageResponse(
    val items: List<EnvironmentAssignmentResponse>,
    val exclusions: List<EnvironmentExclusionResponse>,
)

/** The contract's `EnvironmentBulkAssignment`. */
@Serializable
data class EnvironmentBulkAssignmentResponse(val assigned: Int, val keptDirect: Int, val excluded: Int)

/**
 * The contract's `EnvironmentValues`: values, for their member only.
 *
 * @property variables The values by name.
 * @property unavailable The variables withheld, by name.
 */
@Serializable
data class EnvironmentValuesResponse(val variables: Map<String, String>, val unavailable: List<String>) {
    /** Names only. */
    override fun toString(): String = "EnvironmentValuesResponse(names=${variables.keys.sorted()})"
}

/**
 * What the environment routes need.
 *
 * @property callers Resolves the caller.
 * @property variants The administrators' variants.
 * @property assignments The administrators' assignments and exclusions.
 * @property personal The members' own variants.
 * @property resolve The caller's own values.
 */
data class EnvironmentRoutesDependencies(
    val callers: CallerResolver,
    val variants: ManageEnvironmentVariantsUseCase,
    val assignments: ManageEnvironmentAssignmentsUseCase,
    val personal: PersonalEnvironmentVariantsUseCase,
    val resolve: ResolveEnvironmentUseCase,
)
