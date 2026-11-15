// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.artifacts.presentation

import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.empty
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import io.ktor.http.HttpStatusCode

private const val TAG = "artifacts"

private const val ADMIN_403 = "Not a person who owns or administers the organization: no key or token " +
    "reaches it, whatever its scopes (`forbidden`)."

private const val ORG_NF = "The caller acts for no organization (`not_found`)."

private const val READ_403 = "The credential lacks the `artifacts:read` scope (`forbidden`)."

/**
 * The organization's artifact bucket, settings and usage, for the people who own or administer it, and the
 * publication policy any member credential reads.
 */
internal fun ApiCatalog.artifactStorageOperations() {
    bucketOperations()
    bucketRemovalOperations()
    settingsOperations()
    usageOperations()
}

/** The bucket the files live in. */
private fun ApiCatalog.bucketOperations() {
    get("/api/v1/artifacts/storage") {
        operationId = "getArtifactStorage"
        tag(TAG)
        summary = "The organization's artifact bucket."
        description = "A person only (a browser session, the unlocked app or the CLI's sign-in), an owner " +
            "or an administrator of the organization; no key reaches it, whatever its scopes. The secret access " +
            "key is never returned. Artifact files live only in this bucket: PostgreSQL keeps their metadata " +
            "(artifacts, versions, members, manifests), never their bytes."
        parameters {
            organizationHeader()
        }
        responses {
            ok<ArtifactStorageResponse>("The bucket; `configured` is false while none is stored.")
            error(HttpStatusCode.Forbidden, ADMIN_403)
            notFound(ORG_NF)
        }
    }
    put("/api/v1/artifacts/storage") {
        operationId = "saveArtifactStorage"
        tag(TAG)
        summary = "Save the artifact bucket."
        description = "A person, an owner or an administrator. `version` is the one replaced (0 for the " +
            "first save). The endpoint is `https://host[:port]` without path, credentials, query or fragment; " +
            "link-local, unspecified and cloud metadata hosts are refused in every numeric spelling, private " +
            "and loopback addresses are allowed (MinIO). Region `[a-z0-9-]`; bucket 3 to 63 lowercase letters, " +
            "digits, dots and dashes; prefix up to 8 `[a-z0-9._-]` segments, none `.` or `..`; secret access " +
            "key 8 to 512 characters without spaces. An omitted secret keeps the stored one; changing the " +
            "endpoint or the bucket needs a new one. The secret is sealed with the instance key and never shown " +
            "again. Once artifacts may have files in the bucket, the endpoint, bucket and prefix are fixed; " +
            "keys can still be rotated. Any save makes artifacts inactive until a connection test of the new " +
            "version passes."
        parameters {
            organizationHeader()
        }
        body<SaveArtifactStorageRequest>("The version replaced, the endpoint, region, bucket and prefix, " +
            "the addressing style, the access key id and secret access key, and the server-side encryption " +
            "(`none` or `AES256`).")
        responses {
            ok<ArtifactStorageResponse>("The bucket, saved, without its secret.")
            badRequest("A malformed endpoint, region, bucket, prefix, access key id, secret or server-side " +
                "encryption (`invalid_artifact_storage`), an endpoint whose host is not allowed " +
                "(`artifact_storage_host_not_allowed`), or a first save, or a new endpoint or bucket, without a " +
                "secret (`artifact_storage_secret_required`).")
            error(HttpStatusCode.Forbidden, ADMIN_403)
            notFound(ORG_NF)
            conflict("`version` is not the stored one (`artifact_storage_conflict`), or the endpoint, " +
                "bucket or prefix changes while artifacts may have files in the bucket " +
                "(`artifact_storage_in_use`).")
        }
    }
}

/** Removing and testing the bucket. */
private fun ApiCatalog.bucketRemovalOperations() {
    delete("/api/v1/artifacts/storage") {
        operationId = "deleteArtifactStorage"
        tag(TAG)
        summary = "Remove the artifact bucket."
        description = "A person, an owner or an administrator. Removes the bucket and its secret, which " +
            "turns artifacts off."
        parameters {
            organizationHeader()
        }
        responses {
            empty("The bucket is removed.")
            error(HttpStatusCode.Forbidden, ADMIN_403)
            notFound("The caller acts for no organization (`not_found`), or no bucket is stored " +
                "(`artifact_storage_missing`).")
            conflict("Artifacts may have files in the bucket (`artifact_storage_in_use`).")
        }
    }
    post("/api/v1/artifacts/storage/test") {
        operationId = "testArtifactStorage"
        tag(TAG)
        summary = "Test the connection to the artifact bucket."
        description = "A person, an owner or an administrator. Writes " +
            "`<prefix>/<org>/.ostrom-probe-<uuid>`, reads it back and compares its SHA-256, lists the prefix, " +
            "deletes it and checks it is gone. Each step reports its HTTP status and the S3 error code " +
            "(`AccessDenied`, `NoSuchBucket`, `SignatureDoesNotMatch`...) or a short local code (`unreachable`, " +
            "`timeout`, `dns`, `host_not_allowed`, `digest_mismatch`, `not_listed`, `not_deleted`), never a " +
            "response body. Bounded to 80 seconds, one at a time. The result is recorded only for the version " +
            "tested: a save meanwhile gives `ok` false with a last step `record` and the code " +
            "`storage_changed`."
        parameters {
            organizationHeader()
        }
        responses {
            ok<ArtifactStorageTestResponse>("The test: whether it passed, and each step.")
            error(HttpStatusCode.Forbidden, ADMIN_403)
            notFound("The caller acts for no organization (`not_found`), or no bucket is stored " +
                "(`artifact_storage_missing`).")
            conflict("Another test is running (`artifact_storage_test_running`).")
        }
    }
}

/** The settings of artifacts. */
private fun ApiCatalog.settingsOperations() {
    get("/api/v1/artifacts/settings") {
        operationId = "getArtifactSettings"
        tag(TAG)
        summary = "The organization's artifact settings."
        description = "A person, an owner or an administrator. Artifacts are active only while a bucket is " +
            "stored, its current version passed the connection test, and `enabled` is on; otherwise " +
            "`inactiveReason` is `artifact_storage_missing`, `artifact_storage_unverified` or " +
            "`artifacts_disabled`. While inactive, every publication, read and sharing route answers 409 with " +
            "that reason as its error key (deletion still works) and the content origin answers 404."
        parameters {
            organizationHeader()
        }
        responses {
            ok<ArtifactSettingsResponse>("The settings, and whether artifacts are active.")
            error(HttpStatusCode.Forbidden, ADMIN_403)
            notFound(ORG_NF)
        }
    }
    put("/api/v1/artifacts/settings") {
        operationId = "saveArtifactSettings"
        tag(TAG)
        summary = "Save every artifact setting."
        description = "A person, an owner or an administrator. Every field, with the `version` it replaces. " +
            "`enabled` needs a bucket whose current version passed a test. Ranges: quotas in bytes 1 MiB to 100 " +
            "TiB, artifacts per member 1 to 100000, publications per hour 1 to 10000, retention 1 to 3650 days " +
            "or null (kept), versions per artifact 1 to 1000. A retention change applies to artifacts published " +
            "afterwards."
        parameters {
            organizationHeader()
        }
        body<SaveArtifactSettingsRequest>("Every setting, with the `version` replaced.")
        responses {
            ok<ArtifactSettingsResponse>("The settings, saved.")
            badRequest("A value out of its range, or a `whoCanPublish` or `publicSharing` that is not one " +
                "of its values (`invalid_artifact_settings`).")
            error(HttpStatusCode.Forbidden, ADMIN_403)
            notFound(ORG_NF)
            conflict("`version` is not the stored one (`artifact_settings_conflict`), or `enabled` without " +
                "a bucket (`artifact_storage_missing`) or without a passed test " +
                "(`artifact_storage_unverified`).")
        }
    }
}

/** What the organization stores, and whether the caller may publish. */
private fun ApiCatalog.usageOperations() {
    get("/api/v1/artifacts/usage") {
        operationId = "getArtifactUsage"
        tag(TAG)
        summary = "What the organization stores against its quota."
        description = "A person, an owner or an administrator. Each file is counted once per artifact, as " +
            "the quotas count it; drafts and deleted artifacts are not counted. The 200 largest owners, largest " +
            "first."
        parameters {
            organizationHeader()
        }
        responses {
            ok<ArtifactUsageResponse>("The organization's bytes, and its largest owners.")
            error(HttpStatusCode.Forbidden, ADMIN_403)
            notFound(ORG_NF)
        }
    }
    get("/api/v1/artifacts/policy") {
        operationId = "getArtifactPolicy"
        tag(TAG)
        summary = "Whether the caller may publish artifacts now."
        description = "Any member credential with `artifacts:read` or `artifacts:write`. It answers even " +
            "while artifacts are inactive: `reason` is then `artifact_storage_missing`, " +
            "`artifact_storage_unverified` or `artifacts_disabled`; `artifact_publish_forbidden` when only " +
            "administrators publish and the caller is not one; `credential_scope` for a credential without " +
            "`artifacts:write`. `ostrom <agent>` and the cloud runner ask it with the launch's agent token and " +
            "connect the artifacts MCP server only when `canPublish` is true."
        parameters {
            organizationHeader()
        }
        responses {
            ok<ArtifactPolicyResponse>("Whether the caller may publish now, and why not.")
            error(HttpStatusCode.Forbidden, READ_403)
            notFound(ORG_NF)
        }
    }
}
