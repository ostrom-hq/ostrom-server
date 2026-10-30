# Licensing

| What | Path | License | File |
| --- | --- | --- | --- |
| The server | everything not listed below | GNU AGPL v3.0 | [`LICENSE`](LICENSE) |
| Enterprise features, unlocked by a key | `ee/` | Ostrom Enterprise License | [`ee/LICENSE`](ee/LICENSE) |
| Third party components | dependencies | their own license | in each component |

A file can state its license in a header (`SPDX-License-Identifier: AGPL-3.0-only` or
`SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise`); a header wins over its folder. Code outside `ee/` never
depends on code inside `ee/`: a build without `ee/` is complete and fully AGPL.

`ostrom-core` and `ostrom-cli` are Apache 2.0, so you can embed them anywhere.
