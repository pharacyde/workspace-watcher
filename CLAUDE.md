# workspace-watcher

A Spring Boot web app that observes what AI agents do inside a workspace folder. Read
[README.md](README.md) for the user-facing description and [BACKLOG.md](BACKLOG.md) for planned work.

## Build and run

Requires JDK 25+. The default `java` on this machine may be older, so set `JAVA_HOME` explicitly:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 26)
mvn -B -DskipTests package
cp target/*.jar target/run/watcher.jar
$JAVA_HOME/bin/java -jar target/run/watcher.jar --watcher.workspace=/path/to/observe
```

Stack: Spring Boot 4.1.1, Java release 25, Maven, Netflix DGS on Spring for GraphQL, Lit + Vite for
the frontend. Compiled with `-Xlint:all`; keep the build warning-free.

**Read [docs/build.md](docs/build.md) before changing the build or running the app any other way.**
The copy above is not a habit: rebuilding while the app runs pulls the jar out from under the
running JVM, and it does not fail until the next refresh hangs. That file also has the dev server,
why Spring Boot 4 means Jackson 3 rather than the package every example imports, and the emptying
of `target/classes/static` without which every old bundle stays in the jar forever.

## Design invariants

Six, and they are the reason this project exists in the shape it has. In one line each:

1. **The observer never blocks or alters the agent** - one exception, `GuardService`, off by default.
1b. **"Off" must mean the hook cannot block**, not merely that the wording changes.
2. **Never invent attribution.** No PID on an `FS` event, because macOS cannot supply one.
3. **Layer 1 (`TRANSCRIPT`, `HOOK`) is exact; layer 2 (`FS`, `PROCESS`) is a safety net.**
4. **Loopback by default.** Diffs and command lines are served with no auth.
5. **The process panel is a sampler** and is documented as such.
6. **GraphQL is the whole API.** No REST controllers, hooks included.

**Read [docs/invariants.md](docs/invariants.md) before you weaken one of these, and before adding
anything that could hang an agent.** Each line there says what it already cost to learn - invariant
1b was a bug that passed its unit tests, because they asserted on the event text rather than on what
was handed back to the hook.

## Layout

Which package holds what, and where the frontend build lands.

**Read [docs/architecture.md](docs/architecture.md) before adding a class or touching the GraphQL
wiring.** It has the package tree, and the version pins and schema locations without which the
context does not start at all.

## Frontend

Lit web components in TypeScript, built by Vite into `src/main/resources/static`.

**Read [docs/frontend.md](docs/frontend.md) before changing the feed, the diff panel or the
timeline.** Every bullet there is a bug that has already happened here — following the tail alone
took three separate signals to get right — and none of it is visible to the Java tests.

## Tests and CI

The tests aim at the parsers and at the failure modes this project has actually hit, not at a
coverage number, and one browser test is the only thing that runs the bundle at all.

**Read [docs/testing.md](docs/testing.md) before running the browser test or deleting a test.** A
stale jar makes that run measure the previous version while looking green, and one named regression
test may not be removed.

## Things that are easy to get wrong

The collectors read other programs' files and output — transcripts, the spool, `lsof`, `git` — and
every one of those formats has already been misread here, quietly, in a way no test caught.

**Read [docs/collectors.md](docs/collectors.md) before touching anything under `claude`, `fs`,
`git`, `proc`, `store` or `usage`.** It is grouped by area, and each bullet is a measured mistake or
a rejected alternative rather than a description.

## Conventions

Comments say *why*, especially where an obvious-looking alternative was rejected; records for data;
constructor injection for services; no Lombok.

**Read [docs/conventions.md](docs/conventions.md) before adding a dependency or reaching for
Lombok.** Lombok is not a taste question here: version 1.18.46 silently generates nothing on JDK 26,
so `@Getter` compiles and the getter does not exist. A new dependency needs a reason that survives
the "can the JDK already do this" question.
