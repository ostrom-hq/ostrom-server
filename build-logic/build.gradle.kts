// SPDX-License-Identifier: Apache-2.0
plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.serialization.plugin)
    implementation(libs.kover.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
    implementation(libs.ktor.gradle.plugin)
}
