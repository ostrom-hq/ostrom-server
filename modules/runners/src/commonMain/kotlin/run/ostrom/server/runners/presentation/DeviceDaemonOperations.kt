// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import io.ktor.http.HttpStatusCode
import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.empty
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId

private const val DAEMON = "With `runners.cloud.remote.daemon.v1`. The CLI's sign-in, bound to a device (422 " +
    "`daemon_requires_device` otherwise)."

private const val NOT_A_DEVICE = "The credential is not a CLI sign-in bound to a device (`daemon_requires_device`)."

/**
 * The device daemon's routes (`runners.cloud.remote.daemon.v1`): mounted with the daemon's wiring only, hence
 * conditional.
 */
internal fun ApiCatalog.deviceDaemonOperations() = conditionally {
    daemonChannelOperations()
    daemonAnswerOperations()
    deviceFolderOperations()
}

/** The daemon's long poll, its stop and its refusals. */
private fun ApiCatalog.daemonChannelOperations() {
    post("/api/v1/devices/current/daemon/poll") {
        operationId = "pollDeviceDaemon"
        tag(TAG)
        summary = "The device daemon's long poll for the sessions to host."
        description = "$DAEMON Marks the device's daemon online with its version and features. Answers the " +
            "device's sessions that carry a folder, wait for a host and should run (`creating`, `resuming`), oldest " +
            "first, and the listings members wait for; waits up to `waitMillis` (at most 20 s) for one. Poll again " +
            "at once."
        parameters { organizationHeader() }
        body<DaemonPollRequest>("How long to wait, the CLI's version and the features the daemon announces.")
        responses {
            ok<DaemonPollResponse>("The orders and listings, possibly none, and the timings to poll by.")
            error(HttpStatusCode.UnprocessableEntity, NOT_A_DEVICE)
        }
    }
    delete("/api/v1/devices/current/daemon") {
        operationId = "stopDeviceDaemon"
        tag(TAG)
        summary = "The device daemon stops, the device is offline at once."
        description = DAEMON
        parameters { organizationHeader() }
        responses {
            empty("The device is offline.")
            error(HttpStatusCode.UnprocessableEntity, NOT_A_DEVICE)
        }
    }
    post("/api/v1/devices/current/daemon/sessions/{id}/refuse") {
        operationId = "refuseDaemonOrder"
        tag(TAG)
        summary = "The device daemon refuses to host a session."
        description = "$DAEMON The session ends with `failure.code` `host_refused` and the reason code " +
            "(`[a-z_]{1,64}`) in its message."
        parameters {
            organizationHeader()
            pathId("id", SESSION_ID)
        }
        body<RefuseDaemonOrderRequest>("A stable reason code.")
        responses {
            ok<CloudSessionResponse>("The session, ended.")
            badRequest("A reason that is not `[a-z_]{1,64}` (`invalid_reason`).")
            notFound(NO_SESSION)
            conflict("The session no longer waits for a host (`not_waiting_host`), or waits for another device " +
                "(`remote_host_mismatch`).")
            error(HttpStatusCode.UnprocessableEntity, NOT_A_DEVICE)
        }
    }
}

/** What the daemon sends: its folders and its listings' answers. */
private fun ApiCatalog.daemonAnswerOperations() {
    post("/api/v1/devices/current/daemon/listings/{id}") {
        operationId = "answerDaemonListing"
        tag(TAG)
        summary = "The device daemon answers a listing of a folder's conversations."
        description = "With `runners.cloud.remote.resume.v1`. The page (at most the listing's limit, titles at most " +
            "80 characters) or a refusal code. Titles are never logged."
        parameters {
            organizationHeader()
            pathId("id", "The listing's id.")
        }
        body<DaemonListingAnswerRequest>("The page, or why the daemon does not list the conversations.")
        responses {
            empty("Answered.")
            badRequest("A conversation whose last activity is not a time (`invalid_listing`), or a refusal code " +
                "that is not `[a-z_]{1,64}` (`invalid_reason`).")
            notFound("Nobody waits for this listing any more, after 30 s (`listing_not_found`).")
            error(HttpStatusCode.PayloadTooLarge, "A body over 64 KiB (`payload_too_large`).")
            error(HttpStatusCode.UnprocessableEntity, NOT_A_DEVICE)
        }
    }
    put("/api/v1/devices/current/folders") {
        operationId = "publishDeviceFolders"
        tag(TAG)
        summary = "The folders the caller's device offers for sessions started elsewhere."
        description = "$DAEMON Replaces the list, at most 64 folders (ids `[A-Za-z0-9_-]{1,64}`, unique; names a " +
            "directory's last segment)."
        parameters { organizationHeader() }
        body<PublishDeviceFoldersRequest>("The folders, replacing the previous list.")
        responses {
            ok<List<DeviceFolderDto>>("The folders the device now offers.")
            badRequest("Too many folders, or an id that is malformed or repeated (`invalid_folders`).")
            error(HttpStatusCode.PayloadTooLarge, "A body over 128 KiB (`payload_too_large`).")
            error(HttpStatusCode.UnprocessableEntity, NOT_A_DEVICE)
        }
    }
}

/** The owner's view of a device. */
private fun ApiCatalog.deviceFolderOperations() {
    get("/api/v1/devices/{id}/folders") {
        operationId = "listDeviceFolders"
        tag(TAG)
        summary = "A device's daemon and published folders."
        description = "With `runners.cloud.remote.daemon.v1`. The caller's own device only, whoever the caller is."
        parameters {
            organizationHeader()
            pathId("id", "The device's id.")
        }
        responses {
            ok<DeviceFoldersResponse>("The device's daemon, absent when it never polled, and its folders.")
            notFound("Not one of the caller's devices (`device_not_found`).")
        }
    }
}
