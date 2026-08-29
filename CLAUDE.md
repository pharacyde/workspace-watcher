# workspace-watcher

A Spring Boot web app that observes what AI agents do inside a workspace folder. Read
[README.md](README.md) for the user-facing description and [BACKLOG.md](BACKLOG.md) for planned work.

## Build and run

Requires JDK 25+. The default `java` on this machine may be older, so set `JAVA_HOME` explicitly:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 26)
mvn -B -DskipTests package
$JAVA_HOME/bin/java -jar target/workspace-watcher-0.1.0-SNAPSHOT.jar \
    --watcher.workspace=/path/to/observe
```

Stack: Spring Boot 4.1.1, Java release 25, Maven, Netflix DGS on Spring for GraphQL, Lit + Vite for
the frontend. Compiled with `-Xlint:all`; keep the build warning-free.

`mvn package` builds both halves - Maven downloads a pinned node and runs the frontend build.
`-DskipFrontend` skips that. Java is formatted by `mvn spotless:apply` (google-java-format, Google
style); the build fails on anything unformatted.

While developing the UI, run the backend on 8080 and `cd frontend && npm run dev` on 5173. Vite
serves with hot module replacement and proxies `/graphql` and its WebSocket to 8080, so the app
talks to a same-origin `/graphql` in both development and production.

Note Spring Boot 4 ships **Jackson 3**: the package is `tools.jackson.databind`, not
`com.fasterxml.jackson.databind`. `asText()` and `isTextual()` are deprecated in favour of
`asString()` and `isString()`, and parse failures are unchecked exceptions.

`target/classes/static` is emptied at the start of every build. Vite empties the *source* static
directory, but Maven only ever copies into `target/classes`, so a bundle that disappeared from the
source stayed there and was packaged forever after: measured at 1294 asset files for the 30 that
belong, and a 97 MB jar that is 52 MB once they are gone.

Run it from a **copy** of the jar, not from `target/` itself. A Spring Boot fat jar is read lazily
- nested jars stay compressed until a class is first needed - so rebuilding while the app runs
pulls the file out from under the running JVM. It does not fail at once: the pages already served
keep working, and then a refresh hangs while the log fills with
`NoClassDefFoundError: ch/qos/logback/classic/spi/ThrowableProxy`, because even Tomcat's error path
needs a class it can no longer load. `cp target/*.jar target/run/watcher.jar` and start that one.

## Design invariants

These are the decisions the project exists to hold. Do not quietly relax them.

1. **The observer never blocks or alters the agent**, with exactly one exception: `GuardService`,
   which is off by default, needs its own hook installed, and fails open. Anything else that could
   make a crashed dashboard hang an agent needs the same treatment: an explicit opt-in and a
   fail-open timeout.
1b. **"Off" must mean the hook cannot block**, not merely that the wording changes. `check()`
   downgrades a DENY to WARN while observing. This was a real bug found end to end after unit tests
   passed — they asserted on the event text and not on what was handed back to the hook. The hook
   script always exits 0 and swallows errors.
2. **Never invent attribution.** `FS` events carry no PID because macOS cannot supply one — FSEvents
   has no PID field, and `lsof` only shows still-open descriptors. Filling that column with a guess
   would make the whole feed untrustworthy. `WatchEvent.Source` exists so the UI can always show
   where a fact came from.
3. **Layer 1 (`TRANSCRIPT`, `HOOK`) is exact; layer 2 (`FS`, `PROCESS`) is a safety net.** When both
   could supply the same information, prefer layer 1 and say so in the code comment.
4. **Loopback by default.** This app serves source diffs and command lines with no auth. Do not
   change `server.address` or add a feature that assumes a reachable port.
5. **The process panel is a sampler and is documented as such.** Do not present it as complete.
6. **GraphQL is the whole API.** No REST controllers, including for hooks — those post the
   `recordAgentEvent` mutation. Schema lives in `src/main/resources/graphql/schema.graphqls`.

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

- Comments explain *why*, especially where an obvious-looking alternative was rejected. Do not add
  comments that restate the code.
- Records for data, constructor injection for services.
- **No Lombok, deliberately.** It was considered and measured: version 1.18.46, the latest, silently
  generates nothing on JDK 26 - `@Getter` compiles and the getter simply does not exist. It works on
  17 and 21, so this is a JDK-26 incompatibility, not a configuration mistake. Lombok hooks into
  javac internals and has lagged every recent JDK release, so adopting it would mean pinning the
  compiler to an older JDK indefinitely. Records and constructor injection already cover most of
  what it would save here.
- No new dependencies without a reason that survives the "can the JDK already do this" question.
