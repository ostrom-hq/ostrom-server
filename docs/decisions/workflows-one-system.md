---
status: coming-soon
---

# Decision: one workflow system (onboarding processes, TeamCode workflows and automations)

- **Goal, set by Nathan (2026-11-13):** the processes the Ostrom onboarding finds become the workflows, and that
  matches TeamCode's workflow system. Nothing from TeamCode's workflow system is lost; everything is merged into one
  system on the Ostrom side, and everything works together.
- **Decided by Claude (2026-11-13)**, from TeamCode `docs/workflows.md`, `docs/automations.md`, its domain
  (`domain/…/workflows`: `Workflows.kt`, `WorkflowRules.kt`, `WorkflowEngine.kt`, `WorkflowTemplate.kt`,
  `Deliverables.kt`), its MySQL schema (`V20261028220000.*`, `V20261030*`, `V20261031120000*`, `V20261101140000`) at
  commit `a286c992`, and Ostrom's `Workflow.kt` (ostrom-core), the `compiler`, `workflow-runs`, `automations` and
  `rules` modules, the CLI's `ostrom import teamcode` and the console's workflow, run, automation and import screens.
- **Parity rows:** TC-WFL-1 to TC-WFL-21, TC-AUT-5, TC-AUT-9, TC-AUT-17, TC-AUT-20, and the workflow and automation
  part of the TeamCode import.

## The decision in five lines

1. **One format: Ostrom's `Workflow`** (ostrom-core `Workflow.kt`, `/api/v1/workflows`). TeamCode's definition is not
   a second format: each of its fields lands on an existing field of `Workflow`, or on an optional field added to it
   (additive contract). A TeamCode `agent` node is an Ostrom `agent` node, a `human` node is a `wait` node, a
   `workflow` node is a `workflow` node, a transition is an edge.
2. **The onboarding analysis proposes process workflows**, not only one-gate rules: from the repository's CI, release
   scripts, agent instructions (`CLAUDE.md`, `AGENTS.md`), skills, `CONTRIBUTING.md` and pull request templates. They
   are stored `proposed`, never run, never compiled into an agent's harness and never accepted by themselves; a
   person reviews and accepts each one in the console. A rule's gate stays a gate: inside a process workflow it is a
   `gate` node that names the rule.
3. **An agent node ends by delivering** what it was asked for (`artifact`, `url`, `text`, `json` with a schema),
   through the `ostrom-workflow` MCP server's `deliver` tool, the `deliver` routes, and a Stop hook that sends the
   agent back once; templates `{{inputs.x}}` and `{{nodes.id.output}}`; TeamCode's limits, failure codes and caps.
4. **Automations start workflow runs** (`action.workflowId` with templated `inputs`, idempotent), webhooks have
   versioned URLs, and a run can be cancelled.
5. **`ostrom import teamcode` brings every workflow, the editing rule and every automation**, read-only on TeamCode,
   idempotent, through the member map, `accepted` with a signed import decision, and reports in its dry run anything
   it cannot carry.

## 1. TeamCode workflow definition → Ostrom `Workflow`

Legend: **kept** (Ostrom already has it, same meaning), **mapped** (Ostrom has it under another name or shape; the
mapping is exact), **added** (Ostrom gains it, additively, with TeamCode's rules), **narrowed** (Ostrom keeps a
stricter rule, with the reason). Nothing is dropped.

| TeamCode | Ostrom | Verdict |
| --- | --- | --- |
| `id` (UUID) | `id` (string chosen at creation). An imported workflow is `tc-<TeamCode id>` | mapped |
| `name` (1–120, unique per team) | `name` (unique per organization); the 1–120 bound and the uniqueness are checked at save under the organization's workflow lock | kept, check added |
| `description` (≤ 2,000) | `description`, same bound | kept |
| `version`, `expectedVersion`, `409 workflow_conflict` | `version`, `expectedVersion`, `workflow_conflict` | kept |
| `start` | `start` | kept |
| `sessionMode` `same` / `handoff` | `sessionMode`, optional, `handoff` when absent (Ostrom's behaviour until now: each agent node starts a session) | added |
| `inputs[]` (≤ 16, id `[A-Za-z][A-Za-z0-9_]{0,63}`, `label`, `required` default **true**, `default`, `maxLength`) | `inputs[]`, same fields; Ostrom's `required` defaults to false, so an import always writes it; the 16 and id bounds are added to the save checks | kept, bounds added |
| `nodes[]` (1 to `limits.maxNodes`, unique ids) | `nodes[]` | kept |
| `limits.maxNodes` (50, ≤ 100), `maxVisits` (100, ≤ 500), `maxDepth` (3, ≤ 5) | `limits`, optional, same defaults and bounds; the engine reads them instead of its constants (100 visits, depth 5) | added |
| `createdAt`, `updatedAt`, `updatedById` | `foundAt`, `updatedAt`, `updatedBy` (the member's login; through the member map on import) | mapped |
| — | `domain`, `repos`, `sources`, `status`, `edges[].label`, node `position`, gates and receipts | Ostrom only, kept |

### Nodes

| TeamCode node field | Ostrom `WorkflowNode` | Verdict |
| --- | --- | --- |
| `id`, `title` | `id`, `label` | mapped |
| `kind: agent` | `kind: agent` | mapped |
| `instruction` | `run.instructions` (`detail` stays the human description) | mapped |
| `deliverable {type, label, domain, schema}` | `run.deliverable {type, label, schema}` + `domain` | kept, `domain` added |
| `profileId` | `run.repository`: the repository the session works in, the first of the profile's recipe (see [Profiles](#profiles)) | mapped |
| `agent`, `model` | `run.agent`, `run.model` (`claude`, `codex`) | kept |
| `kind: human` | `kind: wait` | mapped |
| `instruction` (the wait's body) | `wait.body` | mapped |
| `fields`, `actions` (the wait rules) | `wait.fields`, `wait.actions` (same wait model, ostrom-core `WaitSpec`) | kept |
| `assignee {kind: owner}` | no `approvers`: the run's starter is asked | mapped |
| `assignee {kind: member, memberId}` | `approvers: [<login>]` (the wait player asks the first approver) | mapped |
| `kind: workflow`, `workflowId`, `arguments` | `kind: workflow`, `workflowId` (rewritten to the imported id), `arguments` | kept |
| `maxVisits` (1–20, default 3) | `maxVisits`; the 1–20 bound and the default 3 are added for server-run kinds | kept, bound added |
| `transitions[] {on, to}` (`to` null ends) | `edges[] {from, to, on}` (`to` null ends) | mapped |
| every outcome has a transition | same rule, checked at save (below) | added |

**Templates.** `{{inputs.x}}` already renders in agent instructions and child arguments. Added: `{{nodes.<id>.output}}`
(the output of that node's latest finished visit), the same renderer everywhere (instructions, wait body, child
arguments, automation inputs), unknown names render empty at run time and are refused at save, a node never reads its
own output, control characters other than line breaks and tabs are removed, and the result is cut at the session's
first-message bound with `[truncated]` (TeamCode `WorkflowTemplate`).

**Checks at save (TC-WFL-5).** Ostrom checks only the JSON shape and a check node's condition today. Added, refused
with `400 invalid_workflow` naming the node: the start node does not exist; an edge goes to a missing node; a node
cannot be reached from the start; no edge ends the workflow; ids repeat; an outcome of a server-run node has no edge or
an unknown one (`delivered` for agent and workflow nodes, the action ids of a wait, `true`/`false` of a check); a
template names an unknown input or node, or the node itself; an agent node run by the server has no instruction; a
deliverable has a `domain` without being `url` or a `schema` without being `json`; a wait breaks the wait rules; a
sub-workflow does not exist, leads back to the one saved (refused with the path), is deeper than `limits.maxDepth`,
or its arguments miss a required input or name an unknown one. These checks apply to workflows that the server runs
(those with an agent, wait or workflow node); a harness-only workflow (trigger, step, check, gate, notify) keeps its
current, looser rules, because its nodes are compiled for a session, not walked by the engine.

**Rights and errors of definitions (TC-WFL-6, TC-WFL-8).** TeamCode: reads by every member, writes by the editors
(`all`, `admins` by default, or `members` with a list), interactive only. Ostrom keeps its stronger model and adds the
setting:

- Every member reads, and proposes (`proposed` workflows), as today.
- **Editing rights (added):** `GET`/`PUT /api/v1/workflows/settings` with `mode` `all` | `admins` | `members` and
  `memberIds`, default `admins`, changed by an owner or an admin in the console, audited
  (`workflow.editors.changed`). An editor accepts, edits and deletes workflows as an owner or admin does today.
- **Narrowed:** a workflow that holds a `gate` node stays an owner's or an admin's decision whatever the mode. A gate is
  Ostrom's security boundary (who says yes before a push to main or a deploy); TeamCode has no gates, so this loses
  nothing TeamCode had.
- Decisions stay signed (receipts) and a token's decision still waits for its confirmation in the console; a service
  key never decides. This is TeamCode's "writes are interactive only", kept.
- Errors: `invalid_workflow`, `invalid_editors`, `workflow_editors_only` (403), `workflow_conflict`,
  `workflow_name_taken`, `workflow_in_use` (409, now also while another workflow calls it or an automation starts it,
  naming it), `workflow_limit`. **Narrowed:** Ostrom keeps 1,000 workflows per organization rather than 200, because
  rule gates and analysis proposals live in the same list.
- **Concurrency (TC-WFL-7):** a save or a delete takes the organization's workflow lock (a PostgreSQL advisory
  transaction lock on the organization), so two replicas cannot both create a call loop, take one name or pass the cap;
  the version update stays conditional. Every decision is already a signed, audited receipt.

## 2. TeamCode runs → Ostrom runs

| TeamCode | Ostrom | Verdict |
| --- | --- | --- |
| The definition copied into the run | `RunGraph` copied at start | kept |
| Started by any member, as the starter | Owners and admins only today. **Changed to any member** for an `accepted` or `edited` workflow, as themselves (their sessions, quota, GitHub link); an administrator still reads every run | mapped |
| `profileId` at start (`400 profile_required`) | `repository` at start, for agent nodes that name none (`400 repository_required` when a node needs one and none is given) | mapped |
| `400 invalid_inputs` | `InvalidRunException` → `invalid_inputs` | kept |
| At most 20 unfinished runs per member, `409 run_limit` | same | added |
| `Idempotency-Key` → the first run, `200` + `Idempotency-Replayed: true` | same, unique `(org, starter, key)` | added |
| start node is never a human node | same check at save | added |
| visits stored with outcome and output | `NodeVisit` | kept |
| worker every 2 s, 30 s lease | every 2 s, 60 s lease | kept (Ostrom's lease is longer, not weaker) |
| `same`: agent nodes are messages of one session; `handoff`: each node a new session with the earlier deliverables | `sessionMode` (above). `handoff` is played now; `same` needs a message to an existing cloud session (`sendCloudMessage`, owned by the cloud-conversation family): until the server serves it, a `same` workflow runs as `handoff` and says so in the run | added (same: after `sendCloudMessage`) |
| `human` → a wait of kind `workflow`, its answer moves the run | `wait` node → a wait of the run (`kind: workflow`) | kept |
| `workflow` → a child run, depth + 1, `delivered` with the child's output | `workflow` node, child run | kept |
| failure codes | `failureCode` (added, stable) beside `failure` (the sentence): `session_failed`, `session_deleted`, `session_missing`, `ended_without_delivery`, `repository_missing` (TeamCode `profile_missing`), `starter_unavailable`, `wait_cancelled`, `wait_missing`, `max_visits:<node>`, `max_total_visits`, `max_depth`, `workflow_missing`, `invalid_arguments`, `sub_workflow_failed`, `sub_workflow_cancelled`, `expired` (two weeks), `no_transition` | added |
| cancel: the starter or an administrator; withdraws the wait, cancels the child run | `POST /api/v1/runs/{id}/cancel`; now also the starter | kept, starter added |
| `GET /workflows/runs` (`all`, `workflow`, `status`, `before`, `limit`), `currentNodeId`, `currentNodeKind` | `GET /api/v1/runs` (`status`, `before`, `limit`) and `GET /api/v1/workflows/{id}/runs`; `all` (administrators), `currentNodeKind` and a visit's `nodeTitle` added | mapped, fields added |
| `GET /workflows/runs/{id}` | `GET /api/v1/runs/{id}` | mapped |
| run credential minted for the run | the engine creates the session as the starter through the cloud-session use case (no stored token) | mapped |
| `POST /workflows/runs/{id}/deliver` | `POST /api/v1/runs/{id}/deliver` | added |
| `POST /workflows/session/deliver`, `GET /workflows/session/pending` | same paths under `/api/v1/workflows/session/…` | added |
| `cloud.workflow.run.updated` feed event, `workflows.live.v1` | published once the event feed exists (the outgoing-events family); clients poll until then | added (after the feed) |
| Ostrom only: `trigger`, `gate`, `check` (condition) nodes in runs, run receipts | kept |

### Delivery (TC-WFL-4, 12, 13, 16, 17, 18, 19)

- **What an agent node waits for.** An agent node with `run.deliverable` ends only when it is delivered. One without
  (an Ostrom workflow written before) keeps ending when its session stops working, so nothing saved changes meaning.
  Imported TeamCode agent nodes always carry their deliverable.
- **Validation (`Deliverables`).** `artifact` (an artifact id), `url` (https, on `domain` or a subdomain of it when
  set), `text`, `json` checked against the schema subset (`type`, an object's `properties` and `required`, an array's
  `items`, a string's `enum`). A wrong one is `400 invalid_deliverable` and stores nothing; nothing to deliver is
  `409 nothing_to_deliver`; a session whose todo has open items is `409 todo_open` (once the todos module is on main;
  the port is called only when it is bound). The visit's completion is one conditional update: of two deliveries, one
  wins.
- **Who delivers.** `…/runs/{id}/deliver`: the starter (a person, or their key with `workflows:write`). The session
  route: the starter naming `sessionId`, or **the session's agent token with the scope `workflow:session`**: Ostrom's
  agent token is already confined to its launch's session; it now carries `workflow:session`, which grants only these
  two routes for that session, never a read of a run, never another route. A service key carrying the scope is
  refused `403 credential_scope` (the scope is not offered when creating one), and so is an agent token issued before
  the scope existed. Another member's or an unknown session is `404 not_found`.
- **The agent's tool.** The `ostrom-workflow` MCP server, served at `POST /api/v1/workflows/mcp` (like the waits MCP
  server) and bridged to the agent by the CLI's session relay (`ostrom mcp --session … --server ostrom-workflow`), one
  tool `deliver {output}`, with TeamCode's instructions (call it once, with exactly what the step asked for, then end
  the turn; never for an ordinary session). The engine's message for the step ends with the sentence naming the form
  of the deliverable. Offered only when the server announces `workflows.deliver.v1`.
- **Stop hook.** `ostrom hook workflow-stop` (Claude Code `Stop`, beside the gate, wake and todo hooks, none replacing
  another): exits 0 silently when `stop_hook_active` is true, on any error or after 2 s; otherwise asks
  `GET /api/v1/workflows/session/pending` through the relay and, when `needsAction`, prints
  `{"decision":"block","reason":…}` naming the step and the deliverable, telling the agent to call `deliver` of
  `ostrom-workflow`, or `ask_human` if it needs a person. It reads nothing but `hook_event_name` and
  `stop_hook_active`.
- **Server-side reminder (Codex, and a second nudge for Claude Code).** Once per visit (`reminded_at`, conditional
  update), the first turn that ends without a delivery and without an open wait gets one message on the session; the
  next such end fails the run `ended_without_delivery`. It needs `sendCloudMessage` and turn events (cloud-conversation
  family); until they are served, the end of the session is the end of the turn: the run fails
  `ended_without_delivery` when the session stops with nothing delivered, and the Stop hook is the reminder.

## 3. TeamCode automations → Ostrom automations

Ostrom's automations already have TeamCode's cron, webhook, variables, rendering, dry run, budget and rotation
(TC-AUT-6, 7, 10, 12 identical). This family adds:

| TeamCode | Ostrom | Verdict |
| --- | --- | --- |
| `action.kind: cloud` (prompt, profile, model, effort, runtime) | `action.prompt`, `agent`, `model`, `effort`, `maxRuntimeMinutes`; the profile → `action.repository` | mapped |
| `action.kind: workflow`, `workflowId`, `inputs` (templates), `profileId` | `action.workflowId` (already in the contract), `action.inputs` (added), `action.repository`; the prompt must be empty; inputs that render empty are left out; checked at save against the workflow's inputs (`400 invalid_automation` naming the input) | added (TC-AUT-5) |
| the workflow run starts with `Idempotency-Key: automation-run:<runId>`, the run records `workflowRunId` | same, through `StartWorkflowRunUseCase` | added |
| reasons `run_limit`, `workflow_missing`, `invalid_inputs`, `workflow_failed`, `workflow_cancelled` | same | added |
| `DELETE /workflows/{id}` → `409 workflow_in_use` naming the automation | same, one indexed lookup on the automation's `workflow_id` | added |
| `action.kind: remote` refused `422 not_available_yet` | Ostrom has no remote action either; refused the same way | kept |
| `/v/<n>/trigger`, `/v/latest/trigger`, `409 automation_version_changed` | same paths under `/api/v1/automations/{id}` | added (TC-AUT-9) |
| secret URL `…/trigger/<secret>` | the parity row TC-AUT-8 (another family) | out of this scope, not dropped |
| `maxRunningAutomationsPerMember` (2), queued up to 30 min then `expired` | an organization setting, same default; Ostrom already expires queued runs after 30 min | added (TC-AUT-17) |
| `POST …/runs/{runId}/cancel` (a queued run at once; a working one: its session paused, or its workflow run cancelled) | same | added (TC-AUT-17) |
| `teamcode automations …` CLI | `ostrom automations list/show/create/edit/enable/disable/delete/rotate-secret/runs/run`, same options (`--var`, `--dry-run`, `--url-mode`, `--url-version`, `--workflow`, `--input`) | added (TC-AUT-20) |

## 4. How onboarding processes become proposed workflows

**Sources read** (the analysis reads only listed files, never clones or runs anything; each file at most 64 KiB):

| Source | What becomes a workflow |
| --- | --- |
| `.github/workflows/*.yml`, `.gitlab-ci.yml` | A job set triggered on pull requests → "Pull request checks": `trigger` (pull request), one `check` per job (its `run:` commands as `suggested.command`, never `run.command`), then the merge `gate` when a rule gates merges. Jobs triggered on tags or releases → "Release", with the `push-tag` or `deploy` gate of the rule |
| `scripts/release*`, `Makefile` targets `release`/`deploy`/`publish`, `package.json` scripts of those names, `fastlane/Fastfile` lanes | "Release" / "Deploy": `trigger`, `check` (the test command seen in CI), `gate` (the rule's), a `step` per script, `notify` when the CI names a channel |
| `CLAUDE.md`, `AGENTS.md`, `.github/copilot-instructions.md`, `CONTRIBUTING.md` | each heading followed by a numbered list of at least two items → a workflow named after the heading, one `step` per item; a command in backticks that the CI also runs becomes a `check` suggestion |
| `.claude/skills/*/SKILL.md`, `.claude/commands/*.md` | one workflow per skill or command: a `step` named after it, its description as the workflow's description |
| `.github/pull_request_template.md` | its checklist → the `step`s of "Pull request checks" before the merge gate |

**Node kinds produced:** `trigger`, `step`, `check`, `gate`, `notify`. Never `agent`, `wait` or `workflow`: those are
run by the server and only a person adds them.

**Untrusted content.** Everything a repository holds is untrusted:

- A proposal is stored `proposed`, under an id derived from what it was found from (`wf_learned_<sha256>`), with
  `sources` (file and line, linked). It is never accepted by the analysis, never run (a run
  refuses a proposed workflow), and a later analysis never rewrites a stored workflow, even a proposed one.
- **A proposed workflow compiles nothing that an agent reads.** Its `step` and `agent` nodes produce no skill and no
  sub-agent until a person accepts it (until now a proposed workflow's steps reached the harness); its gates and
  checks stay shadow records, as today. So no text read from a repository reaches an agent before a person reads it.
- Every name or text taken from a repository is cleaned as the rules analysis cleans names (one line, no control or
  bidirectional character, no backtick) and cut at 120 characters.
- **No secret is copied:** an `env:` value, a `${{ secrets.… }}` expression, a `with:` input and anything after `=` on
  a command line of a CI file are never read into a proposal; a command is masked with the server's secret masks
  before it is stored, and one that still holds a masked part is left out.

**Review and acceptance.** The console's Workflows screen lists proposed workflows apart (amber) with their sources
and evidence; a person edits, then accepts (the save is a signed decision, `accept`), or rejects. **Added:** the status
`rejected`, so a rejected proposal is kept and never proposed again, and compiles nothing. Accepting follows the
editing rights (section 1).

**Coexistence with gate rules.** A rule stays the source of its gate (its one-gate workflow `rule:<id>` keeps feeding
the gates catalogue). A process workflow that passes the same boundary holds a `gate` node with `ruleIds: [<rule id>]`
and the rule's actions and approvers: the gates catalogue already merges gates by action class, so the rule's gate
and the process's are one gate to the agent, and accepting the process never weakens the rule (a proposed process
never turns an enforced gate into a shadow, as `GatesCatalog` already guarantees).

## 5. How imported TeamCode workflows and automations land

`ostrom import teamcode` reads, in its one read-only transaction, the tables `workflows`, `workflow_settings`,
`automations` (and the `cloud_profile_versions` of the profiles they name) besides those it reads already, and sends
them in `TeamCodeImportRequest.workflows`, `.workflowEditors` and `.automations`. Secrets are never read from these
tables (the automation's webhook secret stays in TeamCode).

- **Ids.** A workflow is `tc-<TeamCode id>`, an automation keeps a deterministic id derived from TeamCode's
  (`Uuid` v5-like digest of the organization and TeamCode's id). A second import finds them: **idempotent**.
- **Versions.** Created at version 1; a later import of a changed definition saves a new version (an `edit` decision
  signed by the importer); an unchanged one does nothing. TeamCode's version is kept in `sources`
  (`{kind: teamcode, ref: workflow/<id>@v<version>}`).
- **Status.** `accepted`: TeamCode's team decided them. The decision is signed by the person who runs the import
  (`action: import`). Sub-workflow ids and automation workflow ids are rewritten to the imported ids.
- **Owners.** `updatedById`, a human node's assignee and an automation's owner go through the member map (TeamCode
  member → Ostrom member, the same map as for MCP servers and accounts). An automation whose owner has no Ostrom
  member is not imported and is reported; a human node whose assignee has none falls back to the run's starter and is
  reported.
- **Editing rights.** `workflow_settings` → `PUT /workflows/settings` (members through the map; an unmapped member is
  reported).
- **Automations land disabled**, so TeamCode and Ostrom never both run a cron while both serve; the cutover pass
  (`--cutover`) enables those TeamCode had enabled. A webhook automation gets a new secret and a new URL: the report
  says to rotate its secret in Ostrom (shown once) and change the sender.
- **Profiles.** A profile becomes the node's or the action's `repository` (the first repository of the profile's
  recipe); its image, resources and setup commands are the organization's runner settings and environment preparation
  in Ostrom. A profile with several repositories or setup commands is reported, not silently cut.
- **Dry run.** The plan lists every workflow and automation as `create`, `update`, `unchanged` or `skipped` with the
  reason (an unmapped owner, a definition Ostrom refuses with the node named, an overlap `queue` before TC-AUT-4,
  a feature named above as "after …"); nothing is written. The report never repeats a prompt, an instruction or a
  payload.

## 6. Delivery plan

Contract first, each step its own pull request, merged on green CI:

1. ostrom-core: contract (planned): `sessionMode`, `limits`, `Deliverable.domain`, `NodeRun.repository`, `rejected`,
   `failureCode`, `currentNodeKind`, `nodeTitle`, `all`; deliver, session deliver, pending, settings, MCP routes;
   `Idempotency-Key` on start; automation `inputs`, `repository`, versioned trigger, run cancel,
   `maxRunningAutomationsPerMember`; import request fields; `workflow:session`; feature keys `workflows.deliver.v1`,
   `workflows.settings.v1`.
2. ostrom-server, in slices: delivery and templates; save checks and editing rights; run caps, idempotency, failure
   codes; automation workflow action, versioned URLs, cancel and capacity; process proposals in the analysis; the
   import. Then ostrom-core marks the routes served.
3. ostrom-cli: `ostrom workflows` (TeamCode's paths: `list`, `show`, `create`, `update`, `delete`, `settings`, `run
   [--wait]`, `runs`, `run-show`, `cancel`), `ostrom automations`, the `ostrom-workflow` bridge and the
   `workflow-stop` hook, the import's new tables.
4. ostrom-web: deliver from a run's page, editing rights, the automation's workflow action, rejected proposals.

What depends on another family is named where it is used: `same` session mode and the server-side reminder
(cloud conversation), live run events (event feed), `todo_open` (todos), the secret webhook URL (TC-AUT-8), the
`queue` overlap (TC-AUT-4).
