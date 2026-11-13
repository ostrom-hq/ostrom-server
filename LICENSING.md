# Licensing

| What | Path | License | File |
| --- | --- | --- | --- |
| The server | everything not listed below | Apache License 2.0 | [`LICENSE`](LICENSE) |
| Enterprise features, unlocked by a key | `ee/` | Ostrom Enterprise License | [`ee/LICENSE`](ee/LICENSE) |
| The API package: the OpenAPI document and its TypeScript types (`@ostrom-hq/openapi`) | `api-package/` | Apache 2.0 | [`api-package/LICENSE`](api-package/LICENSE) |
| Third party components | dependencies | their own license | in each component |

A file can state its license in a header (`SPDX-License-Identifier: Apache-2.0` or
`SPDX-License-Identifier: LicenseRef-Ostrom-Enterprise`); a header wins over its folder. Code outside `ee/` never
depends on code inside `ee/`: a build without `ee/` is complete and fully Apache-2.0.

Every Ostrom repository is Apache 2.0 outside `ee/`, so you can embed the code anywhere.

Why Apache-2.0: [decision 0002](docs/decisions/0002-apache-2-license.md).
