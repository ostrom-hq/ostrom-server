// SPDX-License-Identifier: AGPL-3.0-only
package dev.ostrom.server.testing

/**
 * The drifts between the server's answers and the ostrom-core contract that are known and waiting for their fix: the
 * server's, then the contract's by an additive change. Each fix removes its lines; the list only gets shorter. A drift
 * absent from it fails the test that met it ([ContractObserver.finish]); a line no drift meets any more is reported by
 * `scripts/check-contract-coverage.py`. Found by the first run of the check on every served operation (#246).
 */
object KnownDrifts {
    private const val STEP_UP = "Contract: StepUpRequired is ApiError's shape: a 403 matches both oneOf branches."
    private const val STATUS = "Contract: the server answers this status, the operation does not declare it."
    private const val SERVER_500 = "Server: an unforeseen error answers 500 in a test."
    private const val SUCCESS = "The server's success status is not the contract's (200 against 201)."
    private const val CONTENT = "The content origin refuses without the contract's JSON error."
    private const val AUDIT = "The package audit's meta holds counts where the contract says strings."
    private const val STRIPE = "Contract: ignored_unknown_org is missing from the webhook outcome's enum."
    private const val JSON_RPC = "Contract: the MCP relay refuses with a JSON-RPC error, the contract says ApiError."
    private const val RECEIPT = "The approval's receipt lacks properties the contract requires."
    private const val PROPERTY = "Contract: the server sends this property, the contract does not declare it."

    /** Each drift's key ([Drift.key]) and why it waits. */
    val drifts: Map<String, String> = listOf(
        waiting(
            STEP_UP,
            "answerDeviceLogin 403 $ TYPE",
            "answerGovernanceConfirmation 403 $ TYPE",
            "assignMcpServer 403 $ TYPE",
            "createInvitationLink 403 $ TYPE",
            "createMcpArgumentPolicy 403 $ TYPE",
            "createMcpServer 403 $ TYPE",
            "createSecret 403 $ TYPE",
            "createServiceKey 403 $ TYPE",
            "createSignInLink 403 $ TYPE",
            "createToken 403 $ TYPE",
            "deleteMcpArgumentPolicy 403 $ TYPE",
            "deleteMcpServer 403 $ TYPE",
            "deleteSecret 403 $ TYPE",
            "inviteOrgMembers 403 $ TYPE",
            "putMcpSettings 403 $ TYPE",
            "removeOrgMember 403 $ TYPE",
            "rotateSecret 403 $ TYPE",
            "rotateToken 403 $ TYPE",
            "saveMcpServer 403 $ TYPE",
            "setInferenceSettings 403 $ TYPE",
            "transferOrg 403 $ TYPE",
            "unassignMcpServer 403 $ TYPE",
            "updateOrgMember 403 $ TYPE",
            "verifySignInEmail 403 $ TYPE",
        ),
        waiting(
            STATUS,
            "askGate 503 $ STATUS",
            "cloudRunnerHeartbeat 413 $ STATUS",
            "createCloudSession 402 $ STATUS",
            "createOrg 402 $ STATUS",
            "getBilling 403 $ STATUS",
            "getGate 429 $ STATUS",
            "inviteOrgMembers 402 $ STATUS",
            "launcherLogin 401 $ STATUS",
            "listRepos 503 $ STATUS",
            "relayMcp 202 $ STATUS",
            "relayMcp 413 $ STATUS",
            "startDeviceLogin 429 $ STATUS",
        ),
        waiting(
            SERVER_500,
            "answerGovernanceConfirmation 500 $ STATUS",
        ),
        waiting(
            SUCCESS,
            "createArtifactTicket 201 $ STATUS",
            "createWait 200 $ STATUS",
        ),
        waiting(
            CONTENT,
            "getArtifactContent 404 $ BODY",
        ),
        waiting(
            AUDIT,
            "listPackageAudit 200 $.items[*].meta.enforcedScripts TYPE",
            "listPackageAudit 200 $.items[*].meta.files TYPE",
            "listPackageAudit 200 $.items[*].meta.hooks TYPE",
            "listPackageAudit 200 $.items[*].meta.nodes TYPE",
            "listPackageAudit 200 $.items[*].meta.notifications TYPE",
            "listPackageAudit 200 $.items[*].meta.scripts TYPE",
            "listPackageAudit 200 $.items[*].meta.workflows TYPE",
        ),
        waiting(
            STRIPE,
            "receiveStripeWebhook 200 $.outcome TYPE",
        ),
        waiting(
            JSON_RPC,
            "relayMcp 400 $.error.code UNDECLARED",
            "relayMcp 400 $.error.type MISSING",
            "relayMcp 400 $.id UNDECLARED",
            "relayMcp 400 $.jsonrpc UNDECLARED",
            "relayMcp 404 $.error.code UNDECLARED",
            "relayMcp 404 $.error.type MISSING",
            "relayMcp 404 $.id UNDECLARED",
            "relayMcp 404 $.jsonrpc UNDECLARED",
        ),
        waiting(
            RECEIPT,
            "answerApproval 200 $.receipt.createdAt MISSING",
            "answerApproval 200 $.receipt.decisions MISSING",
            "answerApproval 200 $.receipt.initiator MISSING",
            "answerApproval 200 $.receipt.rules MISSING",
            "answerApproval 200 $.receipt.signature MISSING",
            "answerApproval 200 $.receipt.subject MISSING",
        ),
        waiting(
            PROPERTY,
            "acceptInvitation 200 $.timeZone UNDECLARED",
            "createOrg 201 $.timeZone UNDECLARED",
            "endSession 200 $.session.ended UNDECLARED",
            "endSession 200 $.session.lost UNDECLARED",
            "enrollSharedAccount 201 $.createdAt UNDECLARED",
            "enrollSharedAccount 201 $.email UNDECLARED",
            "enrollSharedAccount 201 $.inFlight UNDECLARED",
            "enrollSharedAccount 201 $.limits UNDECLARED",
            "enrollSharedAccount 201 $.mine UNDECLARED",
            "enrollSharedAccount 201 $.priority UNDECLARED",
            "enrollSharedAccount 201 $.providerAccountId UNDECLARED",
            "enrollSharedAccount 201 $.spentMicros UNDECLARED",
            "enrollSharedAccount 201 $.windows UNDECLARED",
            "getMcpUsage 200 $.rows UNDECLARED",
            "getMe 200 $.csrfToken UNDECLARED",
            "getOrg 200 $.timeZone UNDECLARED",
            "getOrgMember 200 $.id UNDECLARED",
            "getSession 200 $.session.ended UNDECLARED",
            "getSession 200 $.session.lost UNDECLARED",
            "listMcpCalls 200 $.items[*].outcome UNDECLARED",
            "listMcpCalls 200 $.items[*].responseBytes UNDECLARED",
            "listOrgMembers 200 $.items[*].id UNDECLARED",
            "listOrgs 200 $.items[*].timeZone UNDECLARED",
            "listSessions 200 $.items[*].ended UNDECLARED",
            "listSessions 200 $.items[*].lost UNDECLARED",
            "listSharedAccounts 200 $.items[*].cooldownUntil UNDECLARED",
            "listSharedAccounts 200 $.items[*].createdAt UNDECLARED",
            "listSharedAccounts 200 $.items[*].email UNDECLARED",
            "listSharedAccounts 200 $.items[*].inFlight UNDECLARED",
            "listSharedAccounts 200 $.items[*].limits UNDECLARED",
            "listSharedAccounts 200 $.items[*].mine UNDECLARED",
            "listSharedAccounts 200 $.items[*].priority UNDECLARED",
            "listSharedAccounts 200 $.items[*].providerAccountId UNDECLARED",
            "listSharedAccounts 200 $.items[*].spentMicros UNDECLARED",
            "listSharedAccounts 200 $.items[*].windows UNDECLARED",
            "renameOrg 200 $.timeZone UNDECLARED",
            "reopenSession 200 $.reopened UNDECLARED",
            "reopenSession 200 $.session.ended UNDECLARED",
            "reopenSession 200 $.session.lost UNDECLARED",
            "rotateAutomationSecret 200 $.previousSecretValidUntil UNDECLARED",
            "sessionAlive 200 $.ended UNDECLARED",
            "sessionAlive 200 $.lost UNDECLARED",
            "startDeviceLogin 200 $.verificationUriComplete UNDECLARED",
            "startSession 200 $.session.ended UNDECLARED",
            "startSession 200 $.session.lost UNDECLARED",
            "updateOrgMember 200 $.id UNDECLARED",
            "updateSharedAccount 200 $.createdAt UNDECLARED",
            "updateSharedAccount 200 $.email UNDECLARED",
            "updateSharedAccount 200 $.inFlight UNDECLARED",
            "updateSharedAccount 200 $.limits UNDECLARED",
            "updateSharedAccount 200 $.mine UNDECLARED",
            "updateSharedAccount 200 $.priority UNDECLARED",
            "updateSharedAccount 200 $.providerAccountId UNDECLARED",
            "updateSharedAccount 200 $.spentMicros UNDECLARED",
            "updateSharedAccount 200 $.windows UNDECLARED",
        ),
    ).flatten().toMap()

    private fun waiting(reason: String, vararg keys: String) = keys.map { it to reason }
}
