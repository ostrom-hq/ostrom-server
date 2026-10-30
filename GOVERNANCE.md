# Governance

Ostrom is an open source project led by the company that builds it, in the way of
[PostHog](https://posthog.com/handbook), [Supabase](https://supabase.com/open-source) and
[GitLab](https://handbook.gitlab.com/handbook/company/stewardship/). This page says who decides, how, and what we
promise to the community.

## Roles

| Role | Who | Can |
| --- | --- | --- |
| **Contributor** | anyone who opens an issue, a discussion or a pull request | propose anything |
| **Maintainer** | listed in [MAINTAINERS.md](MAINTAINERS.md) | review, merge, triage, release |
| **Lead** | the Ostrom founders | set the direction, break ties, own the brand, the keys and the security process |

A contributor becomes a maintainer after sustained, high quality contributions, on the proposal of a maintainer and
with no objection from the leads within a week.

## How decisions are made

- **Day to day**: lazy consensus. A pull request that passes every check and has a maintainer's approval is merged.
- **Larger changes** (a new module, a change to the receipt format, the workflow model or the public API): open a
  discussion in the **Ideas** category first. A lead writes the decision and its reasons in the discussion before
  work starts.
- **Formats that others depend on** (receipts, packages, the API contract in `ostrom-core`) only change in a
  backward compatible way, or with a new version and a migration path.

## Stewardship: what stays open

We follow the buyer-based open core model of [GitLab](https://handbook.gitlab.com/handbook/company/stewardship/):

- everything an individual developer needs to run agents under rules, gates and receipts is open source and stays
  open source;
- features that only a larger organization needs (SSO, advanced policies, retention, audit exports) live in `ee/`,
  under the Ostrom Enterprise License, and are unlocked by a key;
- we never move an open feature into `ee/`;
- the code of `ee/` is public too: you can read it, test it and send patches.

## Security and conduct

Security reports follow [SECURITY.md](SECURITY.md). Conduct follows the [Code of Conduct](CODE_OF_CONDUCT.md); the
leads handle reports at [conduct@ostrom.run](mailto:conduct@ostrom.run).

## Changing this document

Through a pull request, approved by a lead.
