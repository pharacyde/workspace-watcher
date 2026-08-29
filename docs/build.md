# Build and run

How to build, run and develop against this app, and the three traps in doing so - each one is a
mistake that was made here, not a style preference.

The commands themselves are in [CLAUDE.md](../CLAUDE.md); this is everything around them.

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
