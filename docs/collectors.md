# Collectors, storage and cost

Every bullet here is a mistake this project actually made, or an obvious-looking alternative that
was measured and lost. It is not a description of what the collectors do.

## Transcripts

- **Transcript directory naming.** Claude Code maps a working directory to a directory name by
  replacing every non-alphanumeric character with `-`. Sessions started in a *subdirectory* of the
  workspace get their own directory, which is why `transcriptDirs()` matches on prefix.
- **Existing transcripts are history, not activity.** On first sight of a file the tail seeks to EOF.
  Replaying a finished session would bury the live one.
- **Partial lines.** The tail only consumes up to the last newline in the chunk it read, and advances
  the byte offset by exactly that much — otherwise a half-flushed line corrupts UTF-8 decoding.
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
- **Prefix matches need a boundary.** Transcript directories are matched by escaped-path prefix; a
  bare `startsWith` makes `/Users/me/Dev` also match `/Users/me/Dev2`. The same match, and the same
  trap, is in `ProcessTreeService`, which decides from a path prefix whether a process or an open
  file is inside the workspace. Both have tests.

## Hooks and the spool

- **The hook script must stay fast and silent.** It runs on every tool call and blocks the agent
  until it returns. Default path is a spool file: ~6 ms with the bash macOS
  ships, no dependencies, and the event survives the watcher being down. That figure is what is
  left after removing four of the six processes it used to fork per call - it was measured at
  21 ms while this file claimed 5, which is the sort of number that stops being true quietly. Do not "simplify" this to a network call — that trade was measured and lost.
  A WebSocket looks tidier and is strictly worse for the same reason: a hook is a fresh process per
  tool call, so a persistent connection has nothing to amortise and pays its handshake every time,
  measured at 50 ms plus a node dependency against 5 ms for a file.
- **Spool writes must stay atomic.** Write to a temp name, then rename. A reader must never see a
  half-written payload.
- **The `WORKSPACE_WATCHER_URL` path is for a remote watcher only.** There the body is streamed into
  curl on stdin (payloads exceed `ARG_MAX`), `--max-time` is tight, and one failure trips a
  60-second circuit breaker so a stalled watcher cannot tax every subsequent call.
- **The spool is a directory per project**, named with the same escaping Claude Code uses for
  transcripts, with a `.workspace` marker holding the real path because the escaping is not
  reversible. That marker is the whole registration mechanism.

## Scanning the workspace

- **A file growing on two consecutive scans is `APPENDED`, not `MODIFIED`.** One growth is a save -
  an editor writes a file out in one jump - and only something still writing keeps growing while
  nobody touches it. That is the row worth pointing at, because it is the one where opening the
  panel gives you a log that keeps arriving. Carried in the event type rather than a new field, so
  it costs nothing in the schema or the database.
- **The scanner sets its own pace.** Workspaces are discovered, so landing on a huge tree is a
  normal accident rather than user error. The interval is derived from the measured walk duration
  at a tenth duty cycle; a fixed interval cost 30-85% of a core on a 66,000-file tree.
- **`watcher.workspace` must stay empty in application.yml.** It was `${user.dir}`, which silently
  overrode the empty Java default: discovery and the remembered workspace never ran, and every test
  that started the app from the repository directory looked like it worked.

## Processes

- **`Shell.run`'s timeout needs a watchdog; `waitFor` alone is not one.** stdout has to be read to
  EOF before waiting, or a chatty command deadlocks on a full pipe - and EOF only arrives when the
  child exits. Measured with `sleep 30` and a one-second timeout: `readAllBytes` returned after
  30.0s and the timeout was never consulted. It read as bounded and was not, which is the worst way
  for a limit to be wrong, and `processFiles` had just put `lsof` on a user-triggered request
  thread. A daemon watchdog destroys the process when the time is up; the test that covers it fails
  in 30s against the old code and passes in 1s against this one.
- **`processFiles` only answers for a process the panel is already showing.** Without that check
  anything reaching the loopback port could walk pids 1..N and read the paths of every file every
  process on the machine holds open. Invariant 4 in [CLAUDE.md](../CLAUDE.md) trades source diffs and command lines for
  convenience, not the whole machine, and a path is disclosure even when the contents are refused.
- **lsof answers in resolved paths, so a symlinked workspace matched nothing.** On macOS `/tmp` is
  a link to `/private/tmp`, and the watcher compares the path it was given. A workspace under one
  therefore filtered out every process and the panel sat empty with no error to explain it. The
  process layer now matches the workspace under both names. Every unit test here used a path that
  does not exist, where `toRealPath` fails and the configured name is the only one - which is why
  they were green throughout; the browser test found it, because its workspace is a temp directory.
- **Open files are listed per process, on numeric descriptors only.** `lsof -p <pid> -F fatn`, kept
  to type `REG` with a numeric fd: a process also holds its executable, every shared library and
  the locale files, which is a hundred rows of noise around the handful anyone means. Descriptor 1
  or 2 pointing at a file is the row worth clicking - that is the log. A file inside the workspace
  is opened in the tail view; one outside is named and not clickable, because the tail refuses
  anything outside and this app serves file contents with no auth. And it can only see a descriptor
  that stays open: a shell appending with `>>` reopens the file per line and never appears - the
  test writer had to use `exec >>` for exactly that reason, which is the sibling note below.
- **`lsof` would answer a different question here.** It says a file is open for writing, which is
  not the same as being written to: measured, a shell appending with `>>` reopens the file per line
  and never appears in `lsof` at all, while a long-running build does. It would add which process
  is writing, which growth detection cannot - worth having as a complement, not as a replacement.
- **`lsof +D` is a trap.** It walks the entire tree on every call. `lsof -a -d cwd -F pn` returns all
  processes' working directories in one cheap call; filter in Java.
- **Sample resources before comparing the process tree.** A steady build keeps the same processes
  for minutes, which is exactly when CPU is worth looking at; behind the equality check the series
  stayed empty during the only periods anyone would examine it.

## Git

- **A commit changes nothing the scanner can see.** `git.refresh()` used to run only on a scan that
  found a changed file, and a commit, checkout, stash or branch switch leaves every size and mtime
  in the tree exactly as it was - so the working tree panel went on listing what had just been
  committed, indefinitely, and clicking one of those rows opened a diff with nothing in it. It
  survives a page reload, because the stale snapshot is the server's. A quiet scan now stamps the
  index and HEAD (size and mtime) and refreshes when those moved: two stat calls, against a `git
  status` per scan, which on a large repository is the expensive call this gating exists to avoid.
- **An open diff has the same blind spot, one layer down.** Its left-hand side is
  `git show HEAD:<path>`, so a commit changes the diff completely while leaving the file's size and
  mtime exactly as they were - and `fileChanged`, which only stats the file, said nothing. The panel
  went on showing the differences against the previous commit with the `live` badge lit over a diff
  that could no longer arrive. The poll now compares the head from the snapshot `GitService` already
  holds, so noticing this costs no process.
- **A file may not belong to the repository being watched.** Submodules are separate repositories;
  `git show HEAD:<path>` from the superproject fails for anything inside one. `versions()` asks
  whichever repository actually tracks the file.
- **In a linked worktree `.git` is a file, not a directory**, so a directory-only ignore filter
  never sees it and it gets reported on every git operation.
- **Do not add JGit.** Shelling out to `git` is faster on large repositories and cannot drift from
  what the user sees in their own terminal.

## Storage and the timeline

- **The (workspace, id) index is load-bearing.** The common query is "the most recent N for this
  workspace", which orders by id; the (workspace, ts) index cannot serve that ordering. Measured
  over 500k rows: 328ms without it, 1ms with. It costs about 18% of write throughput and 28% of
  disk, which is a trade worth making twice.
- **The schema lives in `db/schema.sql`**, applied by Spring's `ScriptUtils` on every start. Every
  statement is idempotent. Do not move DDL back into Java strings.
- **`metric` needs a row cap, not only an age limit.** Resource samples are written on a schedule
  rather than when something changes - deliberately, so a steady build still produces a series - so
  the table grows whether anything happens or not: 2833 rows in 2h44m, roughly 740k a month on an
  idle watcher. `event` has had a cap for the same reason all along.
- **Recording must never slow a collector.** `EventStore` queues and flushes on a scheduler, and
  drops the newest events if the queue fills rather than blocking. It also subscribes to the bus
  instead of the bus knowing about it, so storage stays invisible to the thing being stored.
- **Timeline series must be dense arrays.** Filling only the buckets the server returned leaves
  holes, a hole spreads into `Math.max` as `undefined`, the peak becomes `NaN`, and `NaN > 0` is
  false — so the chart renders empty while the data is all there.
- **The timeline counts in SQL, never in the client.** `activity()` returns a few hundred numbers
  for what may be millions of rows. Do not "simplify" it into fetching events and counting them.
- **The timeline draws two densities.** File events dwarf agent events by orders of magnitude, so a
  single total bar hides exactly what someone opened the dashboard to see.

## Usage and cost

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
