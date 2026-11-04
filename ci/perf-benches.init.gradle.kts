// SPDX-License-Identifier: AGPL-3.0-only
// The benches only, for perf.yml (`./gradlew --init-script ci/perf-benches.init.gradle.kts jvmTest`): the read benches
// and the load tests, and the two timed tests of a module's suite. Every test that measures a time calls checkTime
// (testing module, TimeBudget.kt); a module none of them is in runs no test, which is no failure here.
allprojects {
    tasks.withType<Test>().configureEach {
        filter {
            isFailOnNoMatchingTests = false
            includeTestsMatching("*BenchTest")
            includeTestsMatching("*LoadPostgresTest")
            includeTestsMatching("*.GatesPostgresTest.aGateDecision*")
            includeTestsMatching("*.BudgetGuardTest.tenThousandCachedChecks*")
        }
    }
}
