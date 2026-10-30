# Security policy

Ostrom decides what AI agents may do and keeps the proof of what they did. A flaw here can let an agent act without
the approval it needed, or let someone rewrite the record. We treat every report as urgent, and we are grateful for
each one.

## Reporting a vulnerability

**Do not open a public issue, discussion or pull request for a security problem.**

- E-mail **[security@ostrom.run](mailto:security@ostrom.run)**, or
- use **[Report a vulnerability](https://github.com/ostrom-hq/ostrom-server/security/advisories/new)** on GitHub (private
  vulnerability reporting).

Please include what you found, the version or commit, the steps to reproduce it, and the impact you expect. A short
proof of concept helps more than a long report. You can write in English or French.

## What happens next

| Step | Our commitment |
| --- | --- |
| Acknowledgement | within **2 business days** |
| First assessment (confirmed or not, severity) | within **5 business days** |
| Fix for a critical issue | target **7 days** |
| Fix for a high issue | target **30 days** |
| Fix for a medium or low issue | target **90 days**, or the next release |
| Public disclosure | coordinated with you, at the latest **90 days** after the report |

Severity follows [CVSS 4.0](https://www.first.org/cvss/v4-0/). We keep you informed at every step, publish a
GitHub Security Advisory (with a CVE when it applies) once the fix is released, and credit you by name unless you
prefer otherwise.

## Scope

In scope, and the reports we value most:

- an agent, or anyone, obtaining an approval it was not given: bypassing a gate, approving its own request, reusing a
  "yes" for another commit, repository or action;
- forging, altering or replaying a signed receipt, or making a tampered receipt verify;
- reading or changing data of another organization (tenant isolation);
- a token doing more than its scope allows (an agent token reaching the console, an expired token still accepted);
- authentication, session and SSO flaws; secrets or keys exposed in logs, responses or artifacts;
- remote code execution, injection, SSRF, path traversal in any Ostrom component.

Out of scope: denial of service by volume, reports from automated scanners without a demonstrated impact, missing
security headers without an attack, social engineering of our team, and issues in third party services we do not
control (report those to their owner).

## Supported versions

Ostrom is before 1.0. Security fixes land on `main` and ship in the next release; only the **latest release** is
supported. Ostrom Cloud is always up to date.

## Safe harbor

We will not pursue or support legal action against research done in good faith under this policy: you avoid privacy
violations, data destruction and service disruption, you only use accounts you own or have permission to test, and
you give us reasonable time to fix before any disclosure.
