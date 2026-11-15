// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.cloud.presentation

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
import io.ktor.openapi.Responses

// The runners module declares this tag: the cloud administration is part of how cloud sessions run.
private const val TAG = "runners"

private const val STORAGE_CLASS = "The workspace storage class (the profile's, else the cluster's default) is " +
    "refused on a server announcing `runners.cloud.retention.v1`: `storage_class_missing` when it does not " +
    "exist on the cluster, `storage_class_retains_volumes` when its `reclaimPolicy` is not `Delete`."

/**
 * The cloud module's operations: how cloud sessions run on the instance (its runtime and clusters), and the
 * organizations' cloud profiles and settings.
 */
internal fun ApiCatalog.cloudOperations() {
    cloudRuntimeOperations()
    cloudClusterOperations()
    cloudProfileOperations()
    cloudSettingsOperations()
}

/** The instance's cloud runtime. */
private fun ApiCatalog.cloudRuntimeOperations() {
    get("/api/v1/cloud/runtime") {
        operationId = "getCloudRuntime"
        tag(TAG)
        summary = "How cloud sessions run on this instance."
        description = "A person who administers the instance (TeamCode's cloud runtime, `/dashboard/cloud`). The " +
            "database is the source of truth: the former `OSTROM_RUNNERS_*` variables only seed it once, then " +
            "are ignored with a warning. `effectiveServerUrl` is the URL session pods call back (the stored " +
            "one, or the public URL)."
        responses {
            ok<CloudRuntimeResponse>("The runtime.")
        }
    }
    put("/api/v1/cloud/runtime") {
        operationId = "saveCloudRuntime"
        tag(TAG)
        summary = "Save how cloud sessions run on this instance."
        description = "A person who administers the instance. A `PUT` replaces the whole runtime; `updatedAt` is " +
            "the one of the runtime edited (absent while nothing is stored). Turning it off refuses only new " +
            "work: running sessions go on until they end. Every replica uses the new runtime within 30 seconds. An " +
            "absent session image or file limit keeps the stored one."
        body<SaveCloudRuntimeRequest>("The whole runtime, and the `updatedAt` of the one it replaces.")
        responses {
            ok<CloudRuntimeResponse>("The runtime saved.")
            badRequest("A value the server refuses, naming the field (`invalid_cloud_runtime`).")
            conflict("Another save came first (`cloud_runtime_conflict`).")
        }
    }
}

/** The Kubernetes clusters cloud sessions run on. */
private fun ApiCatalog.cloudClusterOperations() {
    get("/api/v1/cloud/clusters") {
        operationId = "listCloudClusters"
        tag(TAG)
        summary = "The Kubernetes clusters cloud sessions run on."
        description = "A person who administers the instance (TeamCode's `cloud_clusters`). The CA bundle and the " +
            "service-account token are write-only: sealed with the server's secrets key, never returned."
        responses {
            ok<CloudClusterListResponse>("The clusters, by name.")
        }
    }
    post("/api/v1/cloud/clusters") {
        operationId = "createCloudCluster"
        tag(TAG)
        summary = "Connect a cluster."
        description = "A person who administers the instance. `apiUrl` is an https URL whose host is never a " +
            "link-local, unspecified, loopback or cloud metadata address, in any numeric spelling (decimal, " +
            "octal, hexadecimal, short forms, IPv4-mapped IPv6). A new cluster needs its CA bundle and token; " +
            "`version` is 0."
        body<SaveCloudClusterRequest>("The cluster to connect, with its credentials.")
        responses {
            ok<CloudClusterResponse>("The cluster connected.", HttpStatusCode.Created)
            badRequest("An address or a value the server refuses (`invalid_cloud_cluster`).")
            conflict("Another cluster has this name (`cloud_cluster_conflict`).")
        }
    }
    put("/api/v1/cloud/clusters/{id}") {
        operationId = "updateCloudCluster"
        tag(TAG)
        summary = "Change a cluster."
        description = "A person who administers the instance. `version` must be the stored one. An absent CA " +
            "bundle or token keeps the stored one, except when `apiUrl` changes: a credential is never sent to " +
            "another server, so a new URL needs both again."
        parameters {
            pathId("id", "The cluster.")
        }
        body<SaveCloudClusterRequest>("The cluster as it becomes, and the `version` it replaces.")
        responses {
            ok<CloudClusterResponse>("The cluster changed.")
            badRequest("An address or a value the server refuses, or a new URL without both credentials " +
                "(`invalid_cloud_cluster`).")
            notFound("No such cluster (`cloud_cluster_not_found`).")
            conflict("The `version` is not the stored one, or another has this name (`cloud_cluster_conflict`).")
        }
    }
}

/** The organization's cloud profiles. */
private fun ApiCatalog.cloudProfileOperations() {
    get("/api/v1/cloud/profiles") {
        operationId = "listCloudProfiles"
        tag(TAG)
        summary = "The organization's cloud profiles."
        description = "A member of the organization: an owner or an admin sees every profile, a member the ones " +
            "assigned to them (TeamCode's `cloud_profiles`). A profile names the cluster and namespace a " +
            "session runs in and the session image the team chose: Ostrom imposes none. With " +
            "`runners.cloud.profiles.member.v1` (TeamCode's `cloud.profiles.member.v1`), `member` (an id or a " +
            "login) lists the profiles that member can use, every profile for an owner or an admin."
        parameters {
            organizationHeader()
            queryText("member", "The member, by id or login, whose profiles are listed; the caller when absent.")
        }
        responses {
            ok<CloudProfileListResponse>("The profiles, by name.")
            badRequest("`member` sent more than once (`invalid_query`).")
            error(HttpStatusCode.Forbidden, "Another member named by a caller who is not an owner or an admin.")
            notFound("The organization is not one the caller belongs to.")
            error(
                HttpStatusCode.UnprocessableEntity,
                "The member named is not an active member of the organization (`invalid_effective_member`).",
            )
        }
    }
    post("/api/v1/cloud/profiles") {
        operationId = "createCloudProfile"
        tag(TAG)
        summary = "Create a cloud profile."
        description = "An owner or an admin of the organization, signed in. The namespace must be one the cluster " +
            "allows and the image a valid reference. `version` is 0. $STORAGE_CLASS"
        parameters {
            organizationHeader()
        }
        body<SaveCloudProfileRequest>("The profile: its name, spec and the members allowed to use it.")
        responses {
            ok<CloudProfileResponse>("The profile created.", HttpStatusCode.Created)
            badRequest("A value the server refuses, naming the field (`invalid_cloud_profile`).")
            notFound("The organization is not one the caller belongs to.")
            conflict("A `version` other than 0, or another profile has this name (`cloud_profile_conflict`).")
            storageClass()
        }
    }
    put("/api/v1/cloud/profiles/{id}") {
        operationId = "updateCloudProfile"
        tag(TAG)
        summary = "Publish a new version of a cloud profile."
        description = "An owner or an admin of the organization, signed in. `version` is the current one; each " +
            "save publishes an immutable version, which a running session keeps. $STORAGE_CLASS"
        parameters {
            organizationHeader()
            pathId("id", "The profile.")
        }
        body<SaveCloudProfileRequest>("The profile as it becomes, and the `version` it replaces.")
        responses {
            ok<CloudProfileResponse>("The new version of the profile.")
            badRequest("A value the server refuses, naming the field (`invalid_cloud_profile`).")
            notFound("No such profile in the organization (`cloud_profile_not_found`).")
            conflict("The `version` is not the current one (`cloud_profile_conflict`).")
            storageClass()
        }
    }
}

/** The organization's cloud session settings. */
private fun ApiCatalog.cloudSettingsOperations() {
    get("/api/v1/cloud/settings") {
        operationId = "getCloudSettings"
        tag(TAG)
        summary = "The organization's cloud session settings."
        description = "A member of the organization, signed in. The defaults while nothing is stored (no " +
            "`updatedAt`)."
        parameters {
            organizationHeader()
        }
        responses {
            ok<CloudSettingsResponse>("The settings.")
            notFound("The organization is not one the caller belongs to.")
        }
    }
    put("/api/v1/cloud/settings") {
        operationId = "saveCloudSettings"
        tag(TAG)
        summary = "Save the organization's cloud session settings."
        description = "An owner or an admin of the organization, signed in as a person (TeamCode's team cloud " +
            "settings). A `PUT` replaces the settings it carries (an absent `maxRunningAutomationsPerMember` " +
            "keeps the stored one); `updatedAt` is the one of the settings edited (absent while nothing is " +
            "stored). Running sessions apply it at their next idle check."
        parameters {
            organizationHeader()
        }
        body<SaveCloudSettingsRequest>("The settings, and the `updatedAt` of the settings they replace.")
        responses {
            ok<CloudSettingsResponse>("The settings saved.")
            badRequest("A value out of range, naming the field (`invalid_cloud_settings`).")
            notFound("The organization is not one the caller belongs to.")
            conflict("Another save came first (`cloud_settings_conflict`).")
        }
    }
}

private fun Responses.Builder.storageClass() = error(
    HttpStatusCode.UnprocessableEntity,
    "The workspace storage class does not exist on the cluster (`storage_class_missing`), or retains the volumes " +
        "of deleted sessions (`storage_class_retains_volumes`).",
)
