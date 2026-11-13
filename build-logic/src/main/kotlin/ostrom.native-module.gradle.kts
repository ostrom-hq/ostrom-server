// SPDX-License-Identifier: Apache-2.0
// The name the foundation's modules apply: exactly ostrom.kmp (docs/CONVENTIONS.md section 9), with the server's
// targets from gradle.properties (ostrom.nativeTargets). Kept so that the module build files written before the
// convention plugin was shared do not change; a new module may apply either id.
plugins {
    id("ostrom.kmp")
}
