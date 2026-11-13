# 0002. Apache-2.0 for the server, the web console, the app and the Helm chart

- Date: 2026-11-13
- Question: raised and decided by Nathan Fallet: which license should the Ostrom repositories carry?
- Decision: made by Nathan Fallet on 2026-11-13. `ostrom-server`, `ostrom-web`, `ostrom-app` and `ostrom-helm` move
  from AGPL-3.0-only to Apache-2.0, like `ostrom-core`, `ostrom-cli` and TeamCode. The `ee/` folders keep the Ostrom
  Enterprise License, untouched.
- Status: accepted. The four repositories are relicensed in one pull request each, "chore(license)!: Apache-2.0, like
  TeamCode and the CLI". The authors are Lyes (Lyesbcb) and Nathan; there are no outside contributors, so no consent
  is missing for the change.

## Context

AGPL-3.0 was picked on 2026-10-30, when the repositories were created from a template. The choice was never
discussed or documented. The other repositories (core, CLI) and TeamCode were already Apache-2.0.

## Reasons (Nathan)

1. Many companies, Google among them, forbid AGPL software internally. That would keep many companies from using
   Ostrom, which hurts the business.
2. Open-source contributors and integrators are wary of AGPL, so it slows the community.
3. TeamCode is Apache-2.0. Ostrom ports a lot of TeamCode code, and Apache code can enter an AGPL project but AGPL
   code can't go back. Mixing licenses made porting messy.
4. The business lever is `ee/`, the hosted offer and support, not the core's license. Without a CLA, AGPL dual
   licensing was not really available anyway.

## Consequence accepted

Anyone may host the core as a closed SaaS without publishing their changes.

## What changes

- `LICENSE` is the Apache-2.0 text and `NOTICE` credits The Ostrom Authors and the code ported from TeamCode.
- Every SPDX header outside `ee/` is `Apache-2.0`; `.github/spdx.json` and `.github/scripts/check_spdx.py` require
  Apache-2.0 outside `ee/` and `LicenseRef-Ostrom-Enterprise` inside it.
- The exception for files ported from TeamCode is gone: everything is Apache-2.0, a ported file may still name its
  origin in its header.
- Contributions are made under Apache-2.0 (inbound = outbound), with the DCO and no CLA.
