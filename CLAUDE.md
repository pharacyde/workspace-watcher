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
- **A drag on the chart needs pointer capture and `user-select: none`.** The chart is 54 px tall, so
  most drags leave it; without capture the selection stops wherever the pointer crossed the edge.
  Without `user-select: none` the same drag starts a text selection that fights the capture and
  leaves the page highlighted. The window is relative to now, so the origin is captured at
  pointerdown - reading the clock again at the end maps the two ends from different instants and
  hands back a range off by however long the drag took.
- **A timeline selection is stored as clock times, not bucket numbers.** The chart is refetched
  every five seconds and scrolls left, so a band held at fixed bucket coordinates slides off the
  data it was drawn around - select 13:30-13:40 and five minutes later it covers 13:35-13:45 while
  the label still says 13:30. Place it from the times against the window as currently drawn.
- **Timeline refreshes are generation-guarded.** Toggling twice quickly starts two refreshes, and
  the slower one finishing last overwrote the other's series with an empty one.
- **Wheel and key handlers must read intent, not position.** They fire *before* the browser
  scrolls, so at the bottom the measurement still says "at the bottom" and following survives the
  notch. That was harmless until re-pinning on `rangeChanged` closed the window: measured, five
  notches up moved `scrollTop` by nothing at all and the feed could not be scrolled back by hand.
  `deltaY < 0`, ArrowUp/PageUp/Home. Touch has no trustworthy direction, so that one still measures.
- **Do not guard the re-pin by comparing against where you last pinned.** It looks safer and is
  wrong: changing every row's height moves `scrollTop` by itself, because the browser keeps the
  reader's anchor, so a wrap toggle read as "a person scrolled" and switched following off.
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
- **An open diff has to keep up with the file, or it is a photograph.** It was fetched once when
  the file was selected and never again, so watching an agent edit meant clicking away and back to
  see anything - the one thing nobody does while reading. The tail says *when* the file changed and
  `fileVersions` says *what* the two sides are; rebuilding the working copy from the tail's chunks
  here would put the same fact in two places. Debounced at 600 ms, because a file being written to
  reports several times a second and every refresh is a `git show` for the left-hand side. Update
  the models in place rather than replacing the pair: a new pair scrolls the editor back to the top,
  which for an appending file means losing your place on every write. A file that grows past the
  diff limit stops the syncing with a note, because both sides then come back empty and blanking a
  diff that was right a second ago claims the file is empty.
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
- **Following re-pins on `rangeChanged`, not only after an event.** The virtualizer measures rows
  asynchronously and its total height grows in stages, so a scroll that was at the bottom stops
  being at the bottom with no event and no render to notice it. Measured with wrap on and 160 rows:
  `scrollTop` sat at 7164 while the height had reached 8796, and stayed there. A settle loop cannot
  fix this however many rounds it gets — the only bound it can pick is a guess at how long measuring
  takes, and that grows with the list. The library says when it has done more work; listen to that.
  Found by the browser test on its first run, which is the whole argument for having one.
- **The Playwright config is evaluated in the runner and again in every worker.** Anything random in
  it answers differently per process: `mkdtemp` there produced two directories, the runner started
  the watcher on one and the test wrote its files into the other, and the feed stayed empty for a
  reason no assertion could describe. Decide once, put it in the environment, let the workers
  inherit it.
- **The tail and the diff do not resolve a path the same way.** `fileVersions` resolves against the
  repository root, because `git status --porcelain` reports repository-root-relative paths whatever
  directory it ran in; `fileTail` resolves against the workspace. Those are the same directory only
  when the workspace *is* the repository root, which is the case in development and in the browser
  test, so a mismatch is invisible there. The live diff was first built on the tail and would have
  gone silently dead in a subdirectory workspace - resolving to a file that does not exist, one
  `gone` chunk, no further notification, and the `live` badge still lit. It now has its own
  `fileChanged` subscription resolved the way `fileVersions` is, which also stops shipping the whole
  file over the socket to be used as a boolean.
- **A `<pre>` in a scrolling parent grows instead of scrolling.** `.event-body` is the scroll
  container, so the file view's `<pre>` reached full content height and following it had nothing to
  scroll - the toggle was on and did nothing. The body becomes a flex column and the child gets
  `min-height: 0`. Same shape as the Monaco case below: constrain the child, not the page.
- **`Flux.map` may not return null.** A poll that found nothing new has nothing to emit, and
  returning null there throws inside Reactor - which `onErrorResume` then reported as "the file is
  gone". Every quiet file announced its own disappearance after one poll. Use `handle` and only
  call `sink.next` when there is something.
- **A collapsed row may only fold events with the same attribution.** Source, type, path and summary
  are not enough: two subagents each reading the same file folded into one row carrying the second
  one's name, so the row stated an attribution that was wrong for half of what it stood for. The
  session, agent, subagent and MCP server are part of what makes two rows the same.
- **A subscription for a file that is not there must end.** `more()` returned a `gone` chunk on every
  poll, so clicking a `DELETED` row re-rendered the panel 2.5 times a second for as long as it
  stayed open. `takeUntil` on `gone` or `binary`. The unit test hid this with `.take(1)` — a test
  that bounds what it reads cannot see that the source never stops.
- **Consecutive identical rows collapse into one with a counter.** A file being written to produces
  one event per scan, all saying the same thing; twenty of them push everything else off the screen
  while adding nothing. Only consecutive ones fold, so a collapsed row still sits where its first
  event happened. The header keeps counting events, not rows.
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

`mvn test`. 150 tests, deliberately aimed at the parsers and at the failure modes this project has
actually hit rather than at a coverage number. `GitServiceTest.resolvesVersionsFromASubdirectoryWorkspace`
is the regression test for the worst bug so far and should not be deleted.

Fixtures are built in `@TempDir`, including real git repositories. Nothing touches the developer's
own `~/.claude`.

One Playwright smoke test sits beside those, not among them: `cd frontend && npm run test:e2e`. It
starts the app from `target/workspace-watcher-0.1.0-SNAPSHOT.jar` over plain HTTP and drives
Chromium against it, which is the only check that the bundle Vite produced actually boots in a
browser. Everything above it passes on a bundle that throws on load — a bad module specifier, a
stylesheet that never reaches the shadow root, a chunk that fails its dynamic import — because none
of the Java tests ever execute the JavaScript.

**Build the frontend before running it.** The jar has to be the current one: asset names carry a
content hash and `emptyOutDir` removes the old ones, so a jar built before your change serves a
different bundle than the one you are testing, and the test measures the previous version while
looking green. `mvn -DskipTests package` first, or the run means nothing. CI takes the jar the
JDK 26 build uploaded for exactly that reason, and runs the browser test once on one runner rather
than in every matrix combination — the bundle does not vary by JDK, and the browser download does
not need paying for twice.

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
- **A file growing on two consecutive scans is `APPENDED`, not `MODIFIED`.** One growth is a save -
  an editor writes a file out in one jump - and only something still writing keeps growing while
  nobody touches it. That is the row worth pointing at, because it is the one where opening the
  panel gives you a log that keeps arriving. Carried in the event type rather than a new field, so
  it costs nothing in the schema or the database.
- **`lsof` would answer a different question here.** It says a file is open for writing, which is
  not the same as being written to: measured, a shell appending with `>>` reopens the file per line
  and never appears in `lsof` at all, while a long-running build does. It would add which process
  is writing, which growth detection cannot - worth having as a complement, not as a replacement.
- **The scanner sets its own pace.** Workspaces are discovered, so landing on a huge tree is a
  normal accident rather than user error. The interval is derived from the measured walk duration
  at a tenth duty cycle; a fixed interval cost 30-85% of a core on a 66,000-file tree.
- **The spool is a directory per project**, named with the same escaping Claude Code uses for
  transcripts, with a `.workspace` marker holding the real path because the escaping is not
  reversible. That marker is the whole registration mechanism.
- **A subagent's work is in its own transcript**, under
  `<session-id>/subagents/agent-<id>.jsonl`, not in the session transcript. A glob over the project
  directory misses a whole directory level - 1111 files and 5.5% of the tokens on this machine.
  There, `isSidechain` is set and `attributionAgent` (or `attributionSkill`, for a skill-started
  agent) names the kind. An earlier note here claimed `isSidechain` is never set; that reading only
  ever looked at session transcripts, where a subagent's records do not appear at all.
- **The `subagent` field carries two facts, deliberately.** On a `Task`/`Agent` call it is the kind
  launched; on a record from inside a subagent it is the kind that made the call. One field, so
  filtering on a kind shows the delegation and its consequences together.
- **`attributionAgent` is only on the agent's own turns.** `agentId` is on all of them, so the kind
  is remembered per id in an access-ordered map - otherwise a call and the result coming back to it
  land in different lanes.
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
  input token, a one-hour cache write double one.   Measured here, cache reads are 79% of the
  figure — summing the kinds into one number makes the total meaningless.
- **A cost figure must say what it is.** On a Claude subscription nobody pays per token, so the
  amount is what those tokens would have cost at API rates: a measure of how heavy a session was,
  and not a bill. `Billing` detects this from the local config; the UI prefixes it with `≈` and
  spells it out. Showing it bare would be a confident lie, which is the failure mode this whole
  project is built against.
- **Count each assistant message once, by `message.id`.** Claude Code writes one transcript record
  per content block — thinking, text, tool_use — and repeats the identical, complete usage block on
  every one. Summing the records inflated this project's total by 58%, and it is the kind of error
  that stays plausible: the ratios between token kinds survive intact and only the magnitude is
  wrong.
- **An unpriced model is named, not zeroed and not fatal.** A model with no entry appears in
  `unpricedModels` while everything else is still priced. Voiding the whole total over one unknown
  model hid the cost of every other — and a locally run model, which is the common case here, costs
  nothing anyway. A model with no tokens is ignored entirely (`<synthetic>`).
- **Sample resources before comparing the process tree.** A steady build keeps the same processes
  for minutes, which is exactly when CPU is worth looking at; behind the equality check the series
  stayed empty during the only periods anyone would examine it.
- **Consumption against subscription limits is knowable locally, as a cache.** This file used to
  say it was not, and re-measuring is what found it: Claude Code writes what its own `/usage`
  fetched into `cachedUsageUtilization` in `~/.claude.json` - the file `Billing` already opens -
  with a percent and an exact `resets_at` per window, plus a `limits[]` array. Measured here: 7% of
  the five-hour window, 49% of the seven-day one. `limit_dollars` is null on a subscription, so
  percentages are the only honest form. Two things keep it honest: `fetchedAt` is shown, so an old
  number announces itself (15h old on the first run), and a window whose `resets_at` has passed is
  marked as rolled over instead of being presented as where you stand now - the five-hour figure
  was already fourteen hours stale when it was read. Still do not reach for the account credential
  to fetch a fresh figure; read the file that is there anyway.
- **`metric` needs a row cap, not only an age limit.** Resource samples are written on a schedule
  rather than when something changes - deliberately, so a steady build still produces a series - so
  the table grows whether anything happens or not: 2833 rows in 2h44m, roughly 740k a month on an
  idle watcher. `event` has had a cap for the same reason all along.
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
