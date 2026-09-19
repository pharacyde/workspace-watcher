# Build and run

How to build, run and develop against this app, and the three traps in doing so - each one is a
mistake that was made here, not a style preference.

The commands themselves are in [CLAUDE.md](../CLAUDE.md); this is everything around them.

`mvn package` builds both halves - Maven downloads a pinned node (`node.version` in `pom.xml`,
v24.21.0) and runs the frontend build.
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

After pulling a change to `node.version` in `pom.xml`, `rm -rf frontend/node` once. The plugin
extracts the new node tarball *over* the old directory, and npm 11's files on top of npm 10's nested
modules crashed `npm ci` with `Class extends value undefined` (measured on the v22 → v24 bump).

Run it from a **copy** of the jar, not from `target/` itself. A Spring Boot fat jar is read lazily
- nested jars stay compressed until a class is first needed - so rebuilding while the app runs
pulls the file out from under the running JVM. It does not fail at once: the pages already served
keep working, and then a refresh hangs while the log fills with
`NoClassDefFoundError: ch/qos/logback/classic/spi/ThrowableProxy`, because even Tomcat's error path
needs a class it can no longer load. Copy it somewhere `mvn clean` does not reach -
`~/.claude/workspace-watcher/run/watcher.jar`, beside the database - and start that one: a copy
under `target/run` survived a rebuild and then went the same way on the next `mvn clean`.

The jar's manifest carries `Enable-Native-Access: ALL-UNNAMED` (`maven-jar-plugin` in `pom.xml`):
sqlite-jdbc loads a native library, and from JDK 24 (JEP 472) that prints four warnings on every
start and is announced to become a refusal. In the manifest so that no command line, launcher or
Playwright config has to remember a flag.

Start it with `-Xmx256m`. Without a cap G1 takes a quarter of the machine as its ceiling and
never gives eden back: measured 1.42 GB RSS and 1.17 GB committed for 35 MB of live data after
five minutes. With the cap, 373 MB RSS, and the heaviest query (`history(limit: 20000)`, 14 MB)
takes the same 290 ms as before - GC was 0.03% of the time either way (P11-12).
