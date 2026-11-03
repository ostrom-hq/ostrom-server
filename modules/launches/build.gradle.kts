// SPDX-License-Identifier: AGPL-3.0-only
// Module launches: `ostrom <agent>` asks for a launch and gets an agent token scoped to its sessions (audit A12/A13).
plugins {
    id("ostrom.native-module")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":kernel"))
            // The callers of the gates and sessions routes, which this module resolves (GateActorResolver).
            implementation(project(":modules:gates"))
            // The people behind a credential, and the agent token of a launch.
            implementation(project(":modules:identity"))
            // The callers of the MCP relay, the inference gateway and the budgets, which this module resolves
            // (CallerResolver), so that an agent token reaches them.
            implementation(project(":modules:secrets"))
            // The agent token of the waits MCP server, which this module resolves (WaitAgentResolver), so that an
            // agent asks a person through Ostrom's own MCP server.
            implementation(project(":modules:automations"))
            // The owner of a cloud session (a member), and the runner token of its execution, which opens its launch.
            implementation(project(":modules:orgs"))
            implementation(project(":modules:runners"))
        }
        commonTest.dependencies {
            implementation(project(":testing"))
            // The launch end to end: a real person, organization and token, then a session of the launch.
            implementation(project(":modules:orgs"))
            // A launch reads the inference gateway's model policy (its ModelAccess), with what the gateway needs.
            implementation(project(":modules:gateway"))
            implementation(project(":modules:sharing"))
            implementation(project(":modules:instance"))
            implementation(project(":modules:receipts"))
            implementation(project(":modules:sessions"))
            // An agent token lists and calls the organization's MCP servers through the relay.
            implementation(project(":modules:mcp-relay"))
            implementation(project(":modules:usage"))
            implementation(project(":modules:gateway"))
            implementation(project(":modules:sharing"))
            implementation(project(":modules:budgets"))
            implementation(project(":modules:reports"))
            // The load test of the launch path: the session package and the signed gates catalogue are on it.
            implementation(project(":modules:compiler"))
            implementation(libs.ktor.client.mock)
        }
    }
}
