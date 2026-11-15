// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.compiler.presentation

import kotlinx.serialization.json.JsonObject
import run.ostrom.core.api.models.PackageEnvelope
import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId
import run.ostrom.server.kernel.http.docs.queryNumber
import run.ostrom.server.kernel.http.docs.queryText

private const val GATES_TAG = "gates"

private const val PACKAGES_TAG = "packages"

private const val DIGEST = "The package's envelope digest or content digest."

private const val PACKAGE_NOT_FOUND = "No such package in the caller's organization (`not_found`)."

/** The repository a catalogue, a package list or a graph is read for. */
private const val REPO = "The repository; the organization's own when absent."

/**
 * The compiler module's operations: the gates catalogue, the signed packages the repositories' sessions receive, how
 * an organization compiles them, and the workflows they are compiled from. The bodies are the module's own mirrors of
 * ostrom-core's `Package*`, `Workflow*` and `CompilerSettings` models.
 */
internal fun ApiCatalog.compilerOperations() {
    tag(PACKAGES_TAG, "The signed packages compiled from the organization's rules and workflows, and how they are " +
        "made.")
    gateCatalogOperations()
    packageReadOperations()
    packageAuditOperations()
    packageRevocationOperations()
    packageSettingsOperations()
    workflowOperations()
}

/** What the gates guard, read from the workflows. */
private fun ApiCatalog.gateCatalogOperations() {
    get("/api/v1/gates/catalog") {
        operationId = "getGateCatalog"
        tag(GATES_TAG)
        summary = "Gates of a repository and the actions they cover."
        description = "Read with `packages:read` or `workflows:read`, by an agent session or a browser session."
        parameters {
            organizationHeader()
            queryText("repo", REPO)
        }
        responses {
            ok<JsonObject>("The gates and the action classes each covers.")
        }
    }
    get("/api/v1/gates/catalog/signed") {
        operationId = "getSignedGatePolicy"
        tag(GATES_TAG)
        summary = "The gate policy of a repository signed by the instance."
        description = "Signed with the instance's Ed25519 key (`/api/v1/instance/keys`), valid 10 minutes " +
            "(`expiresAt`). The hooks trust only this list of guarded actions; a local file may add guarded " +
            "actions, never remove one; without a valid policy every action is held."
        parameters {
            organizationHeader()
            queryText("repo", "The repository the policy is signed for; required.")
        }
        responses {
            ok<JsonObject>("The policy, with its signature.")
            badRequest("No repository, or one that is too long (`invalid_request`).")
        }
    }
    get("/api/v1/gates/approvers") {
        operationId = "getApproversGraph"
        tag(GATES_TAG)
        summary = "Who approves what in a repository."
        description = "Read with `packages:read`, `workflows:read`, an agent session or a browser session: every " +
            "approver with the gates they answer, the gates that name nobody, and the actions no gate covers. Read " +
            "from the same catalogue the gates decide on."
        parameters {
            organizationHeader()
            queryText("repo", REPO)
        }
        responses {
            ok<ApproversGraphResponse>("The approvers, the unanswered gates and the ungated actions.")
        }
    }
}

/** Reading the versions. */
private fun ApiCatalog.packageReadOperations() {
    get("/api/v1/packages/current") {
        operationId = "getCurrentPackage"
        tag(PACKAGES_TAG)
        summary = "The pinned package of a repository (served as signed, bytes untouched)."
        description = "The version the repository's sessions receive: compiled when none is stored, or when the " +
            "sources changed and the organization pins automatically."
        parameters {
            organizationHeader()
            queryText("repo", REPO)
        }
        responses {
            ok<PackageEnvelope>("The signed package.")
        }
    }
    get("/api/v1/packages") {
        operationId = "listPackages"
        tag(PACKAGES_TAG)
        summary = "Package versions."
        parameters {
            organizationHeader()
            queryText("repo", "Only this repository's versions; the organization's own when absent.")
        }
        responses {
            ok<PackagesPage>("The versions, newest first.")
        }
    }
    post("/api/v1/packages/preview") {
        operationId = "previewPackage"
        tag(PACKAGES_TAG)
        summary = "Compile without signing."
        description = "Compiled now from the current sources, neither stored nor signed."
        parameters {
            organizationHeader()
        }
        body<PreviewRequest>("The repository to compile for; the organization's own when absent.")
        responses {
            ok<JsonObject>("The package, as it would be compiled.")
        }
    }
}

/** The audit and the versions by digest. */
private fun ApiCatalog.packageAuditOperations() {
    get("/api/v1/packages/audit") {
        operationId = "listPackageAudit"
        tag(PACKAGES_TAG)
        summary = "Audit of compilations, pins and revocations."
        parameters {
            organizationHeader()
            queryNumber("limit", "The number of lines, 1 to 200 (200 by default); a value outside is brought into it.")
        }
        responses {
            ok<AuditPage>("The lines, newest first.")
        }
    }
    get("/api/v1/packages/{digest}") {
        operationId = "getPackage"
        tag(PACKAGES_TAG)
        summary = "A package by digest."
        parameters {
            organizationHeader()
            pathId("digest", DIGEST)
        }
        responses {
            ok<PackageEnvelope>("The signed package, with whether it is the pinned one.")
            notFound(PACKAGE_NOT_FOUND)
        }
    }
    get("/api/v1/packages/{digest}/status") {
        operationId = "getPackageStatus"
        tag(PACKAGES_TAG)
        summary = "Pinned or revoked."
        parameters {
            organizationHeader()
            pathId("digest", DIGEST)
        }
        responses {
            ok<PackageSummary>("The version's summary.")
            notFound(PACKAGE_NOT_FOUND)
        }
    }
}

/** Taking a version out of force, and putting it back. */
private fun ApiCatalog.packageRevocationOperations() {
    post("/api/v1/packages/{digest}/revoke") {
        operationId = "revokePackage"
        tag(PACKAGES_TAG)
        summary = "Revoke a version: launchers refuse it."
        description = "An owner or an admin, signed in as a person."
        parameters {
            organizationHeader()
            pathId("digest", DIGEST)
        }
        body<RevokeRequest>("Why the version is revoked.")
        responses {
            ok<PackageSummary>("The version, revoked.")
            notFound(PACKAGE_NOT_FOUND)
        }
    }
    post("/api/v1/packages/{digest}/restore") {
        operationId = "restorePackage"
        tag(PACKAGES_TAG)
        summary = "Restore a revoked version."
        description = "Puts a revoked version back in force, so only a person who decides does it: an owner or an " +
            "admin signed in to the console, never the CLI's token."
        parameters {
            organizationHeader()
            pathId("digest", DIGEST)
        }
        responses {
            ok<PackageSummary>("The version, in force again.")
            notFound(PACKAGE_NOT_FOUND)
        }
    }
}

/** How the organization compiles. */
private fun ApiCatalog.packageSettingsOperations() {
    get("/api/v1/packages/settings") {
        operationId = "getCompilerSettings"
        tag(PACKAGES_TAG)
        summary = "How the organization compiles its packages (pin, code publishers, production approver)."
        description = "A member of the organization. The defaults while nothing is stored (no `updatedAt`): `pin` " +
            "`auto`, every person who decides may publish code, no production approver."
        parameters {
            organizationHeader()
        }
        responses {
            ok<CompilerSettingsResponse>("The settings in force.")
        }
    }
    put("/api/v1/packages/settings") {
        operationId = "saveCompilerSettings"
        tag(PACKAGES_TAG)
        summary = "Save how the organization compiles its packages."
        description = "An owner or an admin of the organization, signed in as a person (the console). A `PUT` " +
            "replaces every setting (an omitted `codePublishers` or `prodApprover` is removed); `updatedAt` is the " +
            "one of the settings edited, absent while nothing is stored. Audited `compiler.settings.changed`. The " +
            "next compilation applies it."
        parameters {
            organizationHeader()
        }
        body<SaveCompilerSettingsRequest>("The settings, and the `updatedAt` of those edited.")
        responses {
            ok<CompilerSettingsResponse>("The settings saved.")
            badRequest("A value the server refuses, naming the field (`invalid_compiler_settings`).")
            conflict("Another save came first, including a first save when one is stored " +
                "(`compiler_settings_conflict`).")
        }
    }
}
