// SPDX-License-Identifier: Apache-2.0
// The Kotlin plugins come from build-logic (convention plugin `ostrom.native-module`), not from here.

// The server version (CONVENTIONS section 12): `-PostromVersion` in CI, the tag of a release or the
// `0.0.0-edge.<time>.<sha7>` of an image of main (image.yml); `0.0.0-dev` in a local build.
// Written into the kernel's generated BuildInfo.kt, a Kotlin string literal: only a semantic version passes.
val ostromVersion: String = providers.gradleProperty("ostromVersion").orElse("0.0.0-dev").get()
require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?").matches(ostromVersion)) {
    "ostromVersion is not a semantic version: $ostromVersion"
}

allprojects {
    group = "run.ostrom.server"
    version = ostromVersion
}
