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

## Design invariants

These are the decisions the project exists to hold. Do not quietly relax them.

1. **The observer never blocks or alters the agent**, with exactly one exception: `GuardService`,
   which is off by default, needs its own hook installed, and fails open. Anything else that could
   make a crashed dashboard hang an agent needs the same treatment.
1b. **"Off" must mean the hook cannot block**, not merely that the wording changes. `check()`
   downgrades a DENY to WARN while observing. This was a real bug found end to end after unit tests
   passed — they asserted on the event text and not on what was handed back to the hook. The hook script always exits 0 and swallows
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
├── claude   TranscriptTailService (tails ~/.claude/projects/**/*.jsonl),
│            HookSpoolService (drains the spool dir), HookEvents (shared parsing)
├── fs       WorkspaceScanService (mtime+size snapshot poller)
├── git      GitService (shells out to git; no JGit)
├── proc     ProcessTreeService (lsof -a -d cwd + ProcessHandle)
└── web      WatchGraphQlController (queries, subscription, hook mutation), GqlEvent
```

Frontend lives in `frontend/` and is built into `src/main/resources/static/`, which is generated
output and gitignored. See the Frontend section below.

## Frontend

Lit web components in TypeScript, built by Vite into `src/main/resources/static`. No React: Lit is
5.9 kB gzipped against roughly 45 kB, has no virtual DOM to diff on every event, and the entry
bundle is 82 kB (25 kB gzipped) with Monaco code-split.

- **Subscriptions are ReactiveControllers** (`src/api/subscriptions.ts`), so they follow the host
  element's lifecycle and need no cleanup bookkeeping in components.
- **Events are batched onto one animation frame.** A build produces thousands of events per second;
  updating per event spends the whole frame budget on layout. The feed is virtualised for the same
  reason. The transport is never the bottleneck here - the DOM is.
- **HTTPS is switched on by a keystore existing**, not by a flag - one act, nothing to forget.
  `scripts/dev-cert.sh` writes it. A self-signed certificate encrypts fine and is trusted by
  nothing; only mkcert's makes Safari treat the origin as one it will grant notifications on.
- **Notifications must degrade, not fail.** Safari refuses them on a plain http origin, so the
  title badge runs regardless and the button says which you are getting. A feature that works in
  some browsers is worse than one that always does something.
- **Timeline refreshes are generation-guarded.** Toggling twice quickly starts two refreshes, and
  the slower one finishing last overwrote the other's series with an empty one.
- **Following the tail listens for wheel/touch/key, never for `scroll`.** A scroll event cannot say
  whose scroll it was: assigning `scrollTop` fires one, and measuring a layout the virtualizer was
  still growing made an earlier version decide "not at the bottom" and switch following off
  permanently — one row in, and the feed silently stopped.
- **`lit-virtualizer` needs the `scroller` attribute to be its own scroll container.** Without it,
  it scrolls the nearest scrolling ancestor and setting `scrollTop` on the element does nothing.
  Scroll after awaiting `layoutComplete`; rows are measured asynchronously. Avoid `scrollToIndex` —
  the library documents it as a shim it plans to remove, and it threw on an unlaid-out row.
- **Detach Monaco from its models before disposing them.** `setModel(null)` first, or attach the
  new pair first — disposing a model the editor still points at raises "TextModel got disposed
  before DiffEditorWidget model got reset". It does not throw straight away, which is why it only
  surfaced after switching back and forth a few times.
- **Rebuild the editor whenever the panel returns from an event to a diff.** The container is a
  fresh node by then; guarding only on `path` changing left an editor attached to a detached node
  and an empty panel for a file that had worked a moment earlier.
- **A stale tab fails at the dynamic import, not at the poll.** Asset names carry a content hash
  and `emptyOutDir` removes the old ones, so a tab holding the previous page only finds out when it
  reaches for a chunk it has not loaded yet — Monaco, on the first click of a file. Polling cannot
  prevent it because the click can come first, so `vite:preloadError` reloads immediately, guarded
  by a sessionStorage flag against looping when the failure is not staleness.
- **`index.html` is served `no-store`, assets `max-age=31536000`.** Asset names carry a content
  hash so they can be cached hard; the file that names them cannot, or a browser keeps loading an
  old bundle after a rebuild — which is indistinguishable from a broken feature and cost one real
  bug report that a hard refresh "fixed".
- **Monaco needs its stylesheet adopted into the shadow root.** Vite bundles the CSS Monaco's
  modules import into the *document* stylesheet, which a shadow root cannot see. Without
  `monacoStyleSheet()` the editor renders unstyled and the panel grows to full content height -
  measured at 2136 px in a 456 px grid cell. Do not "fix" this by dropping to light DOM; a light-DOM
  child of a shadow root still cannot see document styles, so it does not help.
- **Monaco's module specifiers are not the ones in most examples.** The package exports map is
  `"./*": "./esm/vs/*.js"`, so it is `monaco-editor/editor/editor.worker.js`, never
  `monaco-editor/esm/vs/...`. Languages are imported individually; importing the package root pulls
  in every language it ships (4.1 MB against 2.9 MB).
- **`frontend/.npmrc` pins the public registry** so a corporate mirror, which typically lags npmjs
  by a patch, cannot make the lockfile unresolvable on someone else's machine.

## Tests and CI

`mvn verify` runs what CI runs: Spotless, the tests, the frontend build, and the jar. CI
(`.github/workflows/ci.yml`) builds on JDK 25 and 26 — 26 is what development happens on, 25 is the
LTS the build targets and the minimum the README promises — and runs the tests on macOS as well,
because that is the platform the process layer is written against and a green Linux build says
less here than it usually would. Standard runners are free on public repositories.

`mvn test`. 91 tests, deliberately aimed at the parsers and at the failure modes this project has
actually hit rather than at a coverage number. `GitServiceTest.resolvesVersionsFromASubdirectoryWorkspace`
is the regression test for the worst bug so far and should not be deleted.

Fixtures are built in `@TempDir`, including real git repositories. Nothing touches the developer's
own `~/.claude`.

## Things that are easy to get wrong

- **Transcript directory naming.** Claude Code maps a working directory to a directory name by
  replacing every non-alphanumeric character with `-`. Sessions started in a *subdirectory* of the
  workspace get their own directory, which is why `transcriptDirs()` matches on prefix.
- **Existing transcripts are history, not activity.** On first sight of a file the tail seeks to EOF.
  Replaying a finished session would bury the live one.
- **Partial lines.** The tail only consumes up to the last newline in the chunk it read, and advances
  the byte offset by exactly that much — otherwise a half-flushed line corrupts UTF-8 decoding.
- **The hook script must stay fast and silent.** It runs on every tool call and blocks the agent
  until it returns. Default path is a spool file: ~5 ms, no dependencies, and the event survives the
  watcher being down. Do not "simplify" this to a network call — that trade was measured and lost.
- **Do not move hooks onto the WebSocket.** It looks tidier and is strictly worse: a hook is a fresh
  process per tool call, so a persistent connection has nothing to amortise and pays its handshake
  every time. Measured at 50 ms plus a node dependency, against 5 ms for a file.
- **Spool writes must stay atomic.** Write to a temp name, then rename. A reader must never see a
  half-written payload.
- **The `WORKSPACE_WATCHER_URL` path is for a remote watcher only.** There the body is streamed into
  curl on stdin (payloads exceed `ARG_MAX`), `--max-time` is tight, and one failure trips a
  60-second circuit breaker so a stalled watcher cannot tax every subsequent call.
- **DGS looks for its schema in `classpath:schema/**`** while Spring Boot and the codegen plugin
  use `classpath:graphql/**`. `dgs.graphql.schema-locations` points it at the one real file; without
  it the context fails with "Parent type Query not found".
- **json-path is pinned to 3.0.0.** DGS 12 wires `Jackson3JsonProvider`, which first ships there,
  while Spring Boot 4.1.1 manages 2.10.0. Without the override the context fails to start.
- **Do not mix DGS and Spring for GraphQL annotations.** Netflix's own guidance is explicit that
  some features do not work across both models. Everything here is DGS.
- **The scanner sets its own pace.** Workspaces are discovered, so landing on a huge tree is a
  normal accident rather than user error. The interval is derived from the measured walk duration
  at a tenth duty cycle; a fixed interval cost 30-85% of a core on a 66,000-file tree.
- **The spool is a directory per project**, named with the same escaping Claude Code uses for
  transcripts, with a `.workspace` marker holding the real path because the escaping is not
  reversible. That marker is the whole registration mechanism.
- **Do not reach for `isSidechain` to detect subagents.** It sounds like the field for it and is
  never set - measured across sixty transcripts. The `Task`/`Agent` call carries `subagent_type`,
  and that is what the UI shows.
- **MCP tools are `mcp__server__tool`.** The server is a field of its own; the summary carries only
  the tool, because the raw name is mostly punctuation.
- **A session title cannot come from the tail alone.** The tail skips whatever a transcript already
  contained when the watcher started, and Claude Code writes the `ai-title` near the beginning of a
  session, so `SessionRegistry` searches each transcript once for it. Without that, every session
  predating the watcher shows as an opaque identifier forever.
- **The (workspace, id) index is load-bearing.** The common query is "the most recent N for this
  workspace", which orders by id; the (workspace, ts) index cannot serve that ordering. Measured
  over 500k rows: 328ms without it, 1ms with. It costs about 18% of write throughput and 28% of
  disk, which is a trade worth making twice.
- **The schema lives in `db/schema.sql`**, applied by Spring's `ScriptUtils` on every start. Every
  statement is idempotent. Do not move DDL back into Java strings.
- **Timeline series must be dense arrays.** Filling only the buckets the server returned leaves
  holes, a hole spreads into `Math.max` as `undefined`, the peak becomes `NaN`, and `NaN > 0` is
  false — so the chart renders empty while the data is all there.
- **The timeline counts in SQL, never in the client.** `activity()` returns a few hundred numbers
  for what may be millions of rows. Do not "simplify" it into fetching events and counting them.
- **The timeline draws two densities.** File events dwarf agent events by orders of magnitude, so a
  single total bar hides exactly what someone opened the dashboard to see.
- **Token kinds are priced differently and must stay apart.** A cache read costs a tenth of an
  input token, a one-hour cache write double one. Measured here, cache reads were 71% of the
  figure — summing the kinds into one number makes the total meaningless.
- **A cost figure must say what it is.** On a Claude subscription nobody pays per token, so the
  amount is what those tokens would have cost at API rates: a measure of how heavy a session was,
  and not a bill. `Billing` detects this from the local config; the UI prefixes it with `≈` and
  spells it out. Showing it bare would be a confident lie, which is the failure mode this whole
  project is built against.
- **An unpriced model is named, not zeroed and not fatal.** A model with no entry appears in
  `unpricedModels` while everything else is still priced. Voiding the whole total over one unknown
  model hid the cost of every other — and a locally run model, which is the common case here, costs
  nothing anyway. A model with no tokens is ignored entirely (`<synthetic>`).
- **Sample resources before comparing the process tree.** A steady build keeps the same processes
  for minutes, which is exactly when CPU is worth looking at; behind the equality check the series
  stayed empty during the only periods anyone would examine it.
- **Consumption against subscription limits is not knowable locally.** Claude Code keeps no record
  of it; its own `/usage` fetches it live with the account credential. Rolling-window token totals
  are the honest local approximation. Do not reach for that credential to fill the gap.
- **Recording must never slow a collector.** `EventStore` queues and flushes on a scheduler, and
  drops the newest events if the queue fills rather than blocking. It also subscribes to the bus
  instead of the bus knowing about it, so storage stays invisible to the thing being stored.
- **A file may not belong to the repository being watched.** Submodules are separate repositories;
  `git show HEAD:<path>` from the superproject fails for anything inside one. `versions()` asks
  whichever repository actually tracks the file.
- **In a linked worktree `.git` is a file, not a directory**, so a directory-only ignore filter
  never sees it and it gets reported on every git operation.
- **Prefix matches need a boundary.** Transcript directories are matched by escaped-path prefix; a
  bare `startsWith` makes `/Users/me/Dev` also match `/Users/me/Dev2`. Same class of bug as the
  `lsof` sibling-directory case, and both have tests.
- **`watcher.workspace` must stay empty in application.yml.** It was `${user.dir}`, which silently
  overrode the empty Java default: discovery and the remembered workspace never ran, and every test
  that started the app from the repository directory looked like it worked.
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
- Records for data, constructor injection for services.
- **No Lombok, deliberately.** It was considered and measured: version 1.18.46, the latest, silently
  generates nothing on JDK 26 - `@Getter` compiles and the getter simply does not exist. It works on
  17 and 21, so this is a JDK-26 incompatibility, not a configuration mistake. Lombok hooks into
  javac internals and has lagged every recent JDK release, so adopting it would mean pinning the
  compiler to an older JDK indefinitely. Records and constructor injection already cover most of
  what it would save here.
- No new dependencies without a reason that survives the "can the JDK already do this" question.
