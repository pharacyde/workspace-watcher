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

Stack: Spring Boot 4.1.1, Java release 25, Maven, Spring for GraphQL. Compiled with `-Xlint:all`;
keep the build warning-free.

Note Spring Boot 4 ships **Jackson 3**: the package is `tools.jackson.databind`, not
`com.fasterxml.jackson.databind`. `asText()` and `isTextual()` are deprecated in favour of
`asString()` and `isString()`, and parse failures are unchecked exceptions.

## Design invariants

These are the decisions the project exists to hold. Do not quietly relax them.

1. **The observer never blocks or alters the agent.** The hook script always exits 0 and swallows
   errors. Anything that could make a crashed dashboard hang an agent needs an explicit opt-in and a
   fail-open timeout.
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

```
be.kleisli.ww
├── core     WatchEvent, EventBus, WatcherProperties, Shell
├── claude   TranscriptTailService (tails ~/.claude/projects/**/*.jsonl), HookController
├── fs       WorkspaceScanService (mtime+size snapshot poller)
├── git      GitService (shells out to git; no JGit)
├── proc     ProcessTreeService (lsof -a -d cwd + ProcessHandle)
└── web      WatchGraphQlController (queries, subscription, hook mutation), GqlEvent
```

Frontend lives in `src/main/resources/static/` and is deliberately plain HTML/CSS/JS: no build step,
no CDN, works offline.

## Things that are easy to get wrong

- **Transcript directory naming.** Claude Code maps a working directory to a directory name by
  replacing every non-alphanumeric character with `-`. Sessions started in a *subdirectory* of the
  workspace get their own directory, which is why `transcriptDirs()` matches on prefix.
- **Existing transcripts are history, not activity.** On first sight of a file the tail seeks to EOF.
  Replaying a finished session would bury the live one.
- **Partial lines.** The tail only consumes up to the last newline in the chunk it read, and advances
  the byte offset by exactly that much — otherwise a half-flushed line corrupts UTF-8 decoding.
- **`lsof +D` is a trap.** It walks the entire tree on every call. `lsof -a -d cwd -F pn` returns all
  processes' working directories in one cheap call; filter in Java.
- **The subscription must not have a gap.** `EventBus.stream()` snapshots history and registers the
  live subscriber under the same lock `publish` holds while appending, then filters the overlap by
  sequence number. Reordering that introduces a silent hole in the feed.
- **`detail` is a JSON string, not a scalar.** Adding `graphql-java-extended-scalars` for one opaque
  field is not worth it; see invariant 2 about not implying more structure than exists.
- **Do not add JGit.** Shelling out to `git` is faster on large repositories and cannot drift from
  what the user sees in their own terminal.

## Conventions

- Comments explain *why*, especially where an obvious-looking alternative was rejected. Do not add
  comments that restate the code.
- Records for data, constructor injection for services, no Lombok.
- No new dependencies without a reason that survives the "can the JDK already do this" question.
