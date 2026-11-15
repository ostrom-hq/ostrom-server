// SPDX-License-Identifier: Apache-2.0
package run.ostrom.server.memory.presentation

import run.ostrom.core.api.models.CreateMemoryRequest
import run.ostrom.core.api.models.MemoryCuration
import run.ostrom.core.api.models.MemoryEntry
import run.ostrom.core.api.models.MemoryIndexPage
import run.ostrom.core.api.models.MemoryProposals
import run.ostrom.core.api.models.MemoryReview
import run.ostrom.core.api.models.MemoryReviews
import run.ostrom.core.api.models.MemorySearchResult
import run.ostrom.core.api.models.SetMemoryCurationRequest
import run.ostrom.core.api.models.SupersededMemories
import run.ostrom.core.api.models.UpdateMemoryRequest
import run.ostrom.server.kernel.http.docs.ApiCatalog
import run.ostrom.server.kernel.http.docs.badRequest
import run.ostrom.server.kernel.http.docs.body
import run.ostrom.server.kernel.http.docs.conflict
import run.ostrom.server.kernel.http.docs.empty
import run.ostrom.server.kernel.http.docs.error
import run.ostrom.server.kernel.http.docs.headerText
import run.ostrom.server.kernel.http.docs.notFound
import run.ostrom.server.kernel.http.docs.ok
import run.ostrom.server.kernel.http.docs.organizationHeader
import run.ostrom.server.kernel.http.docs.pathId
import run.ostrom.server.kernel.http.docs.queryNumber
import run.ostrom.server.kernel.http.docs.queryText
import io.ktor.http.HttpStatusCode

private const val TAG = "memory"

private const val ADMIN_ONLY = "Not an administrator, as a person (`forbidden`)."

private const val BAD_MEMORY = "A field out of bounds, or an unusable value (`invalid_memory`)."

private const val FORBIDDEN_TEAM = "The caller may not write this team entry (`memory_team_forbidden`), or " +
    "the credential lacks `memory:use` (`credential_scope`)."

private const val LIMIT_FULL = "The memory is full (`memory_limit`)."

private const val MNF = "No such entry in the caller's memory, or another member's personal one " +
    "(`not_found`)."

private const val NOT_PROPOSED = "The entry is not waiting for an answer (`memory_not_proposed`)."

private const val PERSON_ONLY = "Not allowed for this caller (`forbidden`)."

private const val PROPOSAL_NF = "No such proposal in the caller's organization (`not_found`)."

private const val REVIEW_NF = "No such review in the caller's organization (`not_found`)."

private const val SECRET = "The text looks like a credential, so nothing is stored (`memory_secret`)."

/**
 * The memory module's operations: the entries an organization and its people remember, the proposals agents make,
 * and the curation that supersedes duplicates. The schemas are ostrom-core's memory models.
 */
internal fun ApiCatalog.memoryOperations() {
    tag(TAG, "What an organization and its people remember: entries, the team entries agents propose, and " +
        "the curation that keeps them tidy.")
    entryOperations()
    entryOperations2()
    entryOperations3()
    entryOperations4()
    proposalOperations()
    proposalOperations2()
    curationOperations()
    curationOperations2()
    curationOperations3()
}

/** Searching, writing, reading and changing entries. */
private fun ApiCatalog.entryOperations() {
    get("/api/v1/memory/index") {
        operationId = "listMemory"
        tag(TAG)
        summary = "The memory index."
        description = "Active, unexpired entries as id, scope, title, one-line summary and tags, newest " +
            "first (by `updatedAt`, then id), never the content: the organization's and the caller's own " +
            "personal ones. The memory is the organization's entries, which every member reads, and the " +
            "caller's own personal entries, which nobody else reads, administrators included: another member's " +
            "personal entry answers 404 as an unknown one. Callers: a person (a browser session, the unlocked " +
            "app, the CLI's sign-in), an agent token holding `memory:use`, or a personal token or service key " +
            "holding `memory:use` (the key acts as its creator); any other credential, 403 `credential_scope`. " +
            "A caller who is no member of the organization, 404."
        parameters {
            organizationHeader()
            queryText("scope", "`team` or `personal`; both when absent (400 `invalid_memory` otherwise).")
            queryText("tags", "Comma-separated tags: entries having any of them.")
            queryText("after", "The `next` of the previous page (400 `invalid_memory` for any other value).")
            queryNumber("limit", "The page size, 1 to 50 (50 by default).")
        }
        responses {
            ok<MemoryIndexPage>("A page of entries without their content, `next` set when another follows.")
            badRequest(BAD_MEMORY)
            notFound("The caller is no member of the organization (`not_found`).")
        }
    }
    get("/api/v1/memory/search") {
        operationId = "searchMemory"
        tag(TAG)
        summary = "Search the memory."
        description = "Entries whose title, summary or a tag contains every word of `q` (at most 5 words of " +
            "2 to 40 characters, case ignored), newest first, at most 20; the content is not searched. 400 " +
            "`invalid_memory` without a usable word. The memory is the organization's entries, which every " +
            "member reads, and the caller's own personal entries, which nobody else reads, administrators " +
            "included: another member's personal entry answers 404 as an unknown one. Callers: a person (a " +
            "browser session, the unlocked app, the CLI's sign-in), an agent token holding `memory:use`, or a " +
            "personal token or service key holding `memory:use` (the key acts as its creator); any other " +
            "credential, 403 `credential_scope`. A caller who is no member of the organization, 404."
        parameters {
            organizationHeader()
            queryText("q", "The words to look for.")
            queryText("tags", "Comma-separated tags: entries having any of them.")
            queryNumber("limit", "At most this many entries, 1 to 20 (20 by default).")
        }
        responses {
            ok<MemorySearchResult>("The entries that match, newest first.")
            badRequest("No usable word in `q` (`invalid_memory`).")
            notFound("The caller is no member of the organization (`not_found`).")
        }
    }
}

/** Searching, writing, reading and changing entries. */
private fun ApiCatalog.entryOperations2() {
    post("/api/v1/memory") {
        operationId = "createMemory"
        tag(TAG)
        summary = "Remember something."
        description = "A personal entry by default, written directly by any caller (an agent's has origin " +
            "`agent`, a person's `human`). A `team` entry written by a person is direct. One written by an " +
            "agent (an agent token, or a call the CLI's local relay forwards with `x-ostrom-origin: agent`, " +
            "whoever's sign-in it carries) is stored `proposed` and the owner of the session it names " +
            "(`sessionId`, or the agent token's own; a cloud session's agent token proposes from its cloud " +
            "session whatever it names) is asked through a wait: the answer is the entry with status " +
            "`proposed`, which joins the organization's memory only once accepted, within about ten seconds of " +
            "the answer. 400 `memory_proposal_session` without a session (a cloud session's agent token needs " +
            "none), 404 for a session that is not the caller's; a key that is no agent's session cannot propose " +
            "(403 `memory_team_forbidden`). 400 `invalid_memory` for a field out of bounds: a title of 1 to 160 " +
            "characters and a summary of 1 to 300 on one line, content of 1 to 16,000 characters, at most 8 " +
            "distinct tags `[a-z0-9][a-z0-9._-]*` of at most 40 characters, at most 10 references of 1 to 300 " +
            "characters, an expiry in the future. 422 `memory_secret` when the title, summary, content or a " +
            "reference looks like a credential (provider and Ostrom token prefixes, private keys, bearer " +
            "headers, `password=value`): nothing is stored. 409 `memory_limit` past 2,000 personal entries of " +
            "the caller or 5,000 entries of the organization, 409 `memory_proposals_limit` while 20 proposals " +
            "of the caller wait. Audited `memory.created` or `memory.proposed`, with the entry's id, scope and " +
            "origin, never its text. The memory is the organization's entries, which every member reads, and " +
            "the caller's own personal entries, which nobody else reads, administrators included: another " +
            "member's personal entry answers 404 as an unknown one. Callers: a person (a browser session, the " +
            "unlocked app, the CLI's sign-in), an agent token holding `memory:use`, or a personal token or " +
            "service key holding `memory:use` (the key acts as its creator); any other credential, 403 " +
            "`credential_scope`."
        parameters {
            organizationHeader()
            headerText("x-ostrom-origin", "`agent`: set by the CLI's local relay on every memory call it " +
                "forwards for an agent, whoever's sign-in it carries (`memory.v1`). A team entry such a call " +
                "writes is a proposal. Any other value is ignored.")
        }
        body<CreateMemoryRequest>("The entry: scope, title, summary, content, and optionally tags, " +
            "references, an expiry and the session it comes from.")
        responses {
            ok<MemoryEntry>("The entry, stored: `proposed` when an agent wrote a team entry.", HttpStatusCode.Created)
            badRequest("A field out of bounds (`invalid_memory`), or a proposal without a session " +
                "(`memory_proposal_session`).")
            error(HttpStatusCode.Forbidden, FORBIDDEN_TEAM)
            notFound("A session that is not the caller's (`not_found`).")
            conflict("The caller holds 2,000 personal entries or the organization 5,000 (`memory_limit`), " +
                "or 20 proposals of the caller wait (`memory_proposals_limit`).")
            error(HttpStatusCode.UnprocessableEntity, SECRET)
        }
    }
}

/** Searching, writing, reading and changing entries. */
private fun ApiCatalog.entryOperations3() {
    get("/api/v1/memory/{id}") {
        operationId = "readMemory"
        tag(TAG)
        summary = "Read an entry."
        description = "The entry with its content and references; counts as a read. The memory is the " +
            "organization's entries, which every member reads, and the caller's own personal entries, which " +
            "nobody else reads, administrators included: another member's personal entry answers 404 as an " +
            "unknown one. Callers: a person (a browser session, the unlocked app, the CLI's sign-in), an agent " +
            "token holding `memory:use`, or a personal token or service key holding `memory:use` (the key acts " +
            "as its creator); any other credential, 403 `credential_scope`."
        parameters {
            organizationHeader()
            pathId("id", "The entry's id.")
        }
        responses {
            ok<MemoryEntry>("The entry, with its content and references.")
            notFound(MNF)
        }
    }
    patch("/api/v1/memory/{id}") {
        operationId = "updateMemory"
        tag(TAG)
        summary = "Change an entry."
        description = "Replaces the entry; `expectedVersion` must be the version read (409 " +
            "`memory_conflict` otherwise). A personal entry is its owner's; an organization entry is its " +
            "author's or an administrator's, as a person only (403 `memory_team_forbidden` otherwise). Same " +
            "bounds and credential filter as `createMemory`. Audited `memory.updated`. The memory is the " +
            "organization's entries, which every member reads, and the caller's own personal entries, which " +
            "nobody else reads, administrators included: another member's personal entry answers 404 as an " +
            "unknown one. Callers: a person (a browser session, the unlocked app, the CLI's sign-in), an agent " +
            "token holding `memory:use`, or a personal token or service key holding `memory:use` (the key acts " +
            "as its creator); any other credential, 403 `credential_scope`."
        parameters {
            organizationHeader()
            pathId("id", "The entry's id.")
        }
        body<UpdateMemoryRequest>("The new entry and the version being replaced.")
        responses {
            ok<MemoryEntry>("The entry, changed.")
            badRequest(BAD_MEMORY)
            error(HttpStatusCode.Forbidden, FORBIDDEN_TEAM)
            notFound(MNF)
            conflict("The entry changed since `expectedVersion` (`memory_conflict`).")
            error(HttpStatusCode.UnprocessableEntity, SECRET)
        }
    }
}

/** Searching, writing, reading and changing entries. */
private fun ApiCatalog.entryOperations4() {
    delete("/api/v1/memory/{id}") {
        operationId = "forgetMemory"
        tag(TAG)
        summary = "Forget an entry."
        description = "Deletes it for good. Same rights as changing it. Audited `memory.forgotten`. The " +
            "memory is the organization's entries, which every member reads, and the caller's own personal " +
            "entries, which nobody else reads, administrators included: another member's personal entry answers " +
            "404 as an unknown one. Callers: a person (a browser session, the unlocked app, the CLI's sign-in), " +
            "an agent token holding `memory:use`, or a personal token or service key holding `memory:use` (the " +
            "key acts as its creator); any other credential, 403 `credential_scope`."
        parameters {
            organizationHeader()
            pathId("id", "The entry's id.")
        }
        responses {
            empty("The entry is deleted for good.")
            error(HttpStatusCode.Forbidden, FORBIDDEN_TEAM)
            notFound(MNF)
        }
    }
}

/** The team entries agents propose. */
private fun ApiCatalog.proposalOperations() {
    get("/api/v1/memory/proposals") {
        operationId = "listMemoryProposals"
        tag(TAG)
        summary = "Team entries proposed by agents, waiting for an answer."
        description = "Oldest first, at most 100: every member's for an administrator, the caller's own " +
            "otherwise. A person only (403 `memory_team_forbidden` for a token). The memory is the " +
            "organization's entries, which every member reads, and the caller's own personal entries, which " +
            "nobody else reads, administrators included: another member's personal entry answers 404 as an " +
            "unknown one. Callers: a person (a browser session, the unlocked app, the CLI's sign-in), an agent " +
            "token holding `memory:use`, or a personal token or service key holding `memory:use` (the key acts " +
            "as its creator); any other credential, 403 `credential_scope`. A caller who is no member of the " +
            "organization, 404."
        parameters {
            organizationHeader()
        }
        responses {
            ok<MemoryProposals>("The proposals, oldest first.")
            error(HttpStatusCode.Forbidden, FORBIDDEN_TEAM)
        }
    }
    post("/api/v1/memory/proposals/{id}/accept") {
        operationId = "acceptMemoryProposal"
        tag(TAG)
        summary = "Accept a proposal."
        description = "An administrator adds the proposed entry to the organization's memory and closes its " +
            "wait (the session's owner normally answers the wait instead, where the text can be edited first). " +
            "Audited `memory.accepted`. 409 `memory_not_proposed` for an entry not waiting; 403 `forbidden` for " +
            "a member. A person only. The memory is the organization's entries, which every member reads, and " +
            "the caller's own personal entries, which nobody else reads, administrators included: another " +
            "member's personal entry answers 404 as an unknown one. Callers: a person (a browser session, the " +
            "unlocked app, the CLI's sign-in), an agent token holding `memory:use`, or a personal token or " +
            "service key holding `memory:use` (the key acts as its creator); any other credential, 403 " +
            "`credential_scope`."
        parameters {
            organizationHeader()
            pathId("id", "The proposal's id.")
        }
        responses {
            ok<MemoryEntry>("The entry, now in the organization's memory.")
            error(HttpStatusCode.Forbidden, ADMIN_ONLY)
            notFound(PROPOSAL_NF)
            conflict(NOT_PROPOSED)
        }
    }
}

/** The team entries agents propose. */
private fun ApiCatalog.proposalOperations2() {
    post("/api/v1/memory/proposals/{id}/reject") {
        operationId = "rejectMemoryProposal"
        tag(TAG)
        summary = "Reject a proposal."
        description = "An administrator, or the proposal's author, drops it and closes its wait. Audited " +
            "`memory.rejected`; a rejected proposal is deleted after 30 days. 409 `memory_not_proposed` for an " +
            "entry not waiting; 403 `forbidden` for anyone else. A person only. The memory is the " +
            "organization's entries, which every member reads, and the caller's own personal entries, which " +
            "nobody else reads, administrators included: another member's personal entry answers 404 as an " +
            "unknown one. Callers: a person (a browser session, the unlocked app, the CLI's sign-in), an agent " +
            "token holding `memory:use`, or a personal token or service key holding `memory:use` (the key acts " +
            "as its creator); any other credential, 403 `credential_scope`."
        parameters {
            organizationHeader()
            pathId("id", "The proposal's id.")
        }
        responses {
            ok<MemoryEntry>("The entry, rejected.")
            error(HttpStatusCode.Forbidden, "Neither an administrator nor the proposal's author " +
                "(`forbidden`).")
            notFound(PROPOSAL_NF)
            conflict(NOT_PROPOSED)
        }
    }
}

/** The curation setting, the reviews it queues and the entries it supersedes. */
private fun ApiCatalog.curationOperations() {
    get("/api/v1/memory/curation") {
        operationId = "getMemoryCuration"
        tag(TAG)
        summary = "The memory curation setting."
        description = "Whether the organization's memory is curated and what the last run did. An " +
            "administrator, as a person only (403 `forbidden` otherwise). Off until an administrator turns it " +
            "on; it never calls a model. A caller who is no member of the organization, 404."
        parameters {
            organizationHeader()
        }
        responses {
            ok<MemoryCuration>("Whether the memory is curated, and what the last run did.")
            error(HttpStatusCode.Forbidden, ADMIN_ONLY)
        }
    }
    put("/api/v1/memory/curation") {
        operationId = "setMemoryCuration"
        tag(TAG)
        summary = "Turn the memory curation on or off."
        description = "On: about once an hour the server reads each scope that changed since its last pass " +
            "or was curated over a week ago (the organization's entries, and each member's personal entries " +
            "alone, at most 20 scopes a run), supersedes the older of two entries with the same title and text, " +
            "and queues for a person every pair it does not decide alone (different text under one title; a " +
            "person's entry against a newer agent's; an entry tagged `external`). Audited " +
            "`memory.curation_enabled` or `memory.curation_disabled`. An administrator, as a person only. A " +
            "caller who is no member of the organization, 404."
        parameters {
            organizationHeader()
        }
        body<SetMemoryCurationRequest>("Whether the curation is on.")
        responses {
            ok<MemoryCuration>("The setting, changed.")
            error(HttpStatusCode.Forbidden, ADMIN_ONLY)
        }
    }
    get("/api/v1/memory/reviews") {
        operationId = "listMemoryReviews"
        tag(TAG)
        summary = "Pairs of entries for a person to decide."
        description = "Open reviews, oldest first, at most 100: the caller's own personal ones, plus the " +
            "organization's for an administrator. A person only. The memory is the organization's entries, " +
            "which every member reads, and the caller's own personal entries, which nobody else reads, " +
            "administrators included: another member's personal entry answers 404 as an unknown one. Callers: a " +
            "person (a browser session, the unlocked app, the CLI's sign-in), an agent token holding " +
            "`memory:use`, or a personal token or service key holding `memory:use` (the key acts as its " +
            "creator); any other credential, 403 `credential_scope`. A caller who is no member of the " +
            "organization, 404."
        parameters {
            organizationHeader()
        }
        responses {
            ok<MemoryReviews>("The open reviews, oldest first.")
            error(HttpStatusCode.Forbidden, PERSON_ONLY)
        }
    }
}

/** The curation setting, the reviews it queues and the entries it supersedes. */
private fun ApiCatalog.curationOperations2() {
    post("/api/v1/memory/reviews/{id}/apply") {
        operationId = "applyMemoryReview"
        tag(TAG)
        summary = "Supersede the older entry of a pair."
        description = "The older entry becomes superseded by the newer one (restorable), audited " +
            "`memory.superseded`. 409 `memory_review_closed` when already answered, `memory_review_stale` when " +
            "an entry is no longer active. A person only. The memory is the organization's entries, which every " +
            "member reads, and the caller's own personal entries, which nobody else reads, administrators " +
            "included: another member's personal entry answers 404 as an unknown one. Callers: a person (a " +
            "browser session, the unlocked app, the CLI's sign-in), an agent token holding `memory:use`, or a " +
            "personal token or service key holding `memory:use` (the key acts as its creator); any other " +
            "credential, 403 `credential_scope`."
        parameters {
            organizationHeader()
            pathId("id", "The review's id.")
        }
        responses {
            ok<MemoryReview>("The review, answered.")
            error(HttpStatusCode.Forbidden, PERSON_ONLY)
            notFound(REVIEW_NF)
            conflict("The review is already answered (`memory_review_closed`), or an entry is no longer " +
                "active (`memory_review_stale`).")
        }
    }
    post("/api/v1/memory/reviews/{id}/dismiss") {
        operationId = "dismissMemoryReview"
        tag(TAG)
        summary = "Keep both entries of a pair."
        description = "Both entries stay and the pair is never queued again. 409 `memory_review_closed` " +
            "when already answered. A person only. The memory is the organization's entries, which every member " +
            "reads, and the caller's own personal entries, which nobody else reads, administrators included: " +
            "another member's personal entry answers 404 as an unknown one. Callers: a person (a browser " +
            "session, the unlocked app, the CLI's sign-in), an agent token holding `memory:use`, or a personal " +
            "token or service key holding `memory:use` (the key acts as its creator); any other credential, 403 " +
            "`credential_scope`."
        parameters {
            organizationHeader()
            pathId("id", "The review's id.")
        }
        responses {
            ok<MemoryReview>("The review, answered.")
            error(HttpStatusCode.Forbidden, PERSON_ONLY)
            notFound(REVIEW_NF)
            conflict("The review is already answered (`memory_review_closed`).")
        }
    }
}

/** The curation setting, the reviews it queues and the entries it supersedes. */
private fun ApiCatalog.curationOperations3() {
    get("/api/v1/memory/superseded") {
        operationId = "listSupersededMemory"
        tag(TAG)
        summary = "Superseded entries."
        description = "Entries curation replaced, newest first, at most 100: the caller's own personal ones " +
            "(nobody else sees them), and the organization's for an administrator or their author. Kept 90 " +
            "days. A person only. A caller who is no member of the organization, 404."
        parameters {
            organizationHeader()
        }
        responses {
            ok<SupersededMemories>("The entries curation replaced, newest first.")
            error(HttpStatusCode.Forbidden, PERSON_ONLY)
        }
    }
    post("/api/v1/memory/{id}/restore") {
        operationId = "restoreMemory"
        tag(TAG)
        summary = "Restore a superseded entry."
        description = "The entry is active again and the one that replaced it, if still active, becomes the " +
            "superseded one. Audited `memory.restored`. A personal entry by its owner; an organization entry by " +
            "its author or an administrator (403 `forbidden` otherwise). 409 `memory_limit` when the memory is " +
            "full. A person only."
        parameters {
            organizationHeader()
            pathId("id", "The entry's id.")
        }
        responses {
            ok<MemoryEntry>("The entry, active again.")
            error(HttpStatusCode.Forbidden, "Neither the entry's owner nor its author nor an administrator " +
                "(`forbidden`).")
            notFound(MNF)
            conflict(LIMIT_FULL)
        }
    }
}
