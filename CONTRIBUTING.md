# Contributing to ostrom-server

Thank you for helping. Ostrom is built in the open, and every contribution, from a typo fix to a new agent adapter,
goes through the same short path. This guide takes ten minutes to read and saves days of back and forth.

- [Ground rules](#ground-rules)
- [Set up your machine](#set-up-your-machine)
- [Tests: the bar](#tests-the-bar)
- [Commits and pull requests](#commits-and-pull-requests)
- [Developer Certificate of Origin](#developer-certificate-of-origin)
- [License headers](#license-headers)
- [AI-assisted contributions](#ai-assisted-contributions)
- [Releases](#releases)

## Ground rules

- Be kind, and follow the [Code of Conduct](CODE_OF_CONDUCT.md).
- **Security problems are never reported in public**: see [SECURITY.md](SECURITY.md).
- For anything larger than a bug fix, open a [discussion](https://github.com/ostrom-hq/ostrom-server/discussions) or an
  issue first, so we agree on the direction before you write code.
- Read the engineering conventions shared by every Ostrom repository, [`ostrom-core/docs/CONVENTIONS.md`](https://github.com/ostrom-hq/ostrom-core/blob/main/docs/CONVENTIONS.md). They bind humans and
  agents alike.

## Set up your machine

Prerequisites: JDK 21 (only for Gradle and the test target) and Git. Kotlin/Native downloads its own toolchain on the
first build.

```sh
./gradlew jvmTest koverVerify     # every common test, and the 100 % coverage gate
./gradlew macosArm64Test          # the same tests in native code (linuxX64Test on Linux)
./gradlew check                   # everything CI runs on your platform
```

The product is native only. The `jvm()` target exists to run the common tests fast and to measure coverage with
Kover; it is never shipped.

## Tests: the bar

Every change ships with its tests, and CI enforces it:

- **unit tests** for every line added;
- **integration tests** against real dependencies (a real PostgreSQL, real HTTP), never a mock of our own server;
- **end-to-end tests** of the journey the change touches, run in CI from the first commit
  ([`ostrom-e2e`](https://github.com/ostrom-hq/ostrom-e2e) holds the full-stack journeys);
- **100 % line and branch coverage** of the code you add. The coverage job fails below. An exclusion needs a one-line
  justification next to it, and the reviewer reads it.

A bug fix starts with a test that fails without the fix.

## Commits and pull requests

- Work on a branch, open a pull request against `main`. Keep it small: one subject per pull request.
- The pull request title follows [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/):
  `feat(gates): bind an approval to the commits it showed`, `fix(cli): …`, `docs: …`. Allowed types: `feat`, `fix`,
  `perf`, `refactor`, `docs`, `test`, `build`, `ci`, `chore`, `style`, `revert`. A breaking change adds `!` after the type and
  explains the migration in the body. The `pr-title` check verifies it.
- We squash merge: the pull request title becomes the commit on `main`, and the changelog line.
- Fill the pull request template: what changes for a person, how you tested it.
- Every check must be green. Nobody, maintainers included, merges around a red check.

## Developer Certificate of Origin

We use the [Developer Certificate of Origin](https://developercertificate.org) (DCO) instead of a CLA: you certify
that you wrote the change or have the right to submit it under the project's license. Sign off every commit:

```sh
git commit -s -m "fix(cli): keep the exit code of the agent"
```

This adds `Signed-off-by: Your Name <you@example.com>` to the message. Forgot it? `git rebase --signoff main` then
push with `--force-with-lease`. The `dco` check verifies every commit of a pull request from a contributor
without write access; maintainers signed it once for all.

Contributions are made under the [GNU AGPL v3.0](LICENSE) (inbound = outbound).

## License headers

Every source file starts with its SPDX identifier, checked in CI by the `spdx` job:

```text
// SPDX-License-Identifier: AGPL-3.0-only
```

Add it to the files you create, or let the script do it: `python3 .github/scripts/check_spdx.py --fix <files>`.

## AI-assisted contributions

Ostrom exists so that teams can trust work done with AI agents, and we hold our own repositories to that standard.
Using an agent is welcome. In return:

- **You own what you submit.** You understand every line and can explain it without asking the agent again.
- **Prove it works.** Tests, and for a visible change a screenshot or a short recording. "It compiles" is not proof.
- **Say it.** The pull request template has an *Agent context* section: the tool, the model, and what you checked
  yourself. An Ostrom receipt link is the best answer.
- **No unreviewed AI output in issues or reviews.** If an agent found a bug, send the fix.

Pull requests that clearly were not run or read are closed with a link to this section.

## Releases

[release-please](https://github.com/googleapis/release-please) keeps a release pull request open on `main`, built from
the Conventional Commit titles. Merging it tags the version, writes [CHANGELOG.md](CHANGELOG.md) and publishes the
release. Do not edit the changelog or the version by hand. Until the release GitHub App is configured, a release pull
request opened by `GITHUB_TOKEN` does not start CI: close and reopen it to run the checks.
