# Decisions

One file per decision that changes the server's direction: what was decided, when, who raised the question and who
made the choice, why, and the options set aside. A decision is never edited afterwards; a later one replaces it and
links back.

| # | Decision | Date |
| --- | --- | --- |
| [0001](0001-jvm-and-teamcode-stack.md) | Run the server on the JVM, with TeamCode's server libraries (Exposed, HikariCP, Flyway, Netty, koin-ktor, logback); the CLI and the session runner stay Kotlin/Native | 2026-11-13 |
