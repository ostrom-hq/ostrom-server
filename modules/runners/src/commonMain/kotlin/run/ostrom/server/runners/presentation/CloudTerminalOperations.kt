// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.runners.presentation

import io.ktor.http.HttpStatusCode
import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.empty
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId
import run.ostrom.server.kernel.http.docs.queryNumber
import run.ostrom.server.kernel.http.docs.queryText

private const val FRAMES = "Binary frames carry the terminal's bytes; text frames are JSON controls: " +
    "`{\"type\":\"resize\",\"columns\":c,\"rows\":r}` from a client (1 to 1000), " +
    "`{\"type\":\"runner\",\"connected\":b}` from the server. Refusals close the socket with 4000 plus the HTTP " +
    "status (4400, 4401, 4403, 4404, 4408, 4409)."

private const val NOT_AN_UPGRADE = "A GET that does not ask for the WebSocket upgrade, whoever asks and whatever the " +
    "session, before any credential is read (`invalid_request`)."

/** The terminal of a cloud session: the browser's ticket, the member's socket and the runner's socket. */
internal fun ApiCatalog.cloudTerminalOperations() {
    terminalTicket()
    memberTerminal()
    runnerTerminal()
}

private fun ApiCatalog.terminalTicket() {
    post("/api/v1/cloud/sessions/{id}/terminal/ticket") {
        operationId = "issueCloudTerminalTicket"
        tag(TAG)
        summary = "A single-use ticket to attach a browser to the session's terminal."
        description = "A browser cannot send a bearer token on a WebSocket upgrade: it trades its sign-in for a " +
            "ticket here, then attaches with `?ticket=` (`attachCloudTerminal`). The ticket is used once and " +
            "expires at `expiresAt`. $ORG_RESOLUTION"
        parameters {
            organizationHeader()
            pathId("id", SESSION_ID)
        }
        responses {
            ok<TerminalTicketResponse>("The ticket and when it expires.")
            notFound(NO_SESSION)
        }
    }
}

private fun ApiCatalog.memberTerminal() {
    get("/api/v1/cloud/sessions/{id}/terminal") {
        operationId = "attachCloudTerminal"
        tag(TAG)
        summary = "WebSocket: the session's terminal."
        description = "A member attaches with a bearer token on the upgrade, or with `?ticket=` from a browser " +
            "(`issueCloudTerminalTicket`). $FRAMES A session that runs in mode `conversation` has no " +
            "terminal. Without the upgrade the route answers 400."
        parameters {
            organizationHeader()
            pathId("id", SESSION_ID)
            queryText("ticket", "A single-use ticket from `issueCloudTerminalTicket`, in place of a bearer token.")
        }
        responses {
            empty("Switching protocols: the terminal's frames follow.", HttpStatusCode.SwitchingProtocols)
            badRequest(NOT_AN_UPGRADE)
        }
    }
}

private fun ApiCatalog.runnerTerminal() {
    get("/api/v1/cloud/runner/terminal") {
        operationId = "attachRunnerTerminal"
        tag(TAG)
        summary = "WebSocket: the runner in a session's pod attaches its PTY."
        description = "$RUNNER_TOKEN Same frames as `attachCloudTerminal`. $FRAMES The socket closes 4401 without " +
            "a valid runner token and 4409 when a newer execution holds the session or the session runs in " +
            "mode `conversation`. Without the upgrade the route answers 400."
        parameters {
            queryText("sessionId", "The cloud session the runner was started for.")
            queryNumber("epoch", "The execution the runner was started for.")
        }
        responses {
            empty("Switching protocols: the terminal's frames follow.", HttpStatusCode.SwitchingProtocols)
            badRequest(NOT_AN_UPGRADE)
        }
    }
}
