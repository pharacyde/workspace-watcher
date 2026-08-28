# Backlog

Source of the original item list: two Gemini brainstorm sessions (Aug 28, 2026). Everything from
those sessions is preserved below under its original ID. What has been added is a **status** and,
where the original item rests on an assumption that does not hold, a **note** saying so.

Status legend:

| | meaning |
|---|---|
| ✅ | in the PoC today |
| 🟢 | feasible as written |
| 🟡 | feasible, but the original framing needs correcting first |
| 🔴 | not possible as written on macOS without special privileges |

---

## Epic 1 — Core system & process monitoring

**P1-01 Workspace process discovery engine** ✅
Backend scans the workspace and finds active process trees (PIDs, names, arguments) via
`ProcessHandle` and `lsof`.
*Implemented in `ProcessTreeService`. One `lsof -a -d cwd -F pn` call every 2s, filtered to
processes whose working directory sits inside the workspace, with parent/child links rebuilt
through `ProcessHandle`.*

**P1-02 Live subprocess spawning tree** 🟡
Tree that updates in real time as an agent spawns a short-lived subprocess.
*Note: a 2s sampler will miss most of them — a `git status` lives ~40ms. The complete record of
what an agent ran comes from the transcript/hook layer, which logs every command with its exact
arguments. Keep the sampler for long-running processes; render short-lived ones from agent events.*

**P1-03 Process resource usage (CPU & RAM)** 🟢
CPU and memory per process and per subtree.
*`ProcessHandle.Info.totalCpuDuration()` gives cumulative CPU time, so a percentage can be derived
across two samples. RSS is not in the JDK API — read it from `ps -o rss=` in the same poll.*

## Epic 2 — Realtime file system & git tracking

**P2-01 Native file activity feed** 🟡
Chronological `CREATED` / `MODIFIED` / `DELETED` log, *linked to the PID that touched the file*.
*Implemented without the PID link (`WorkspaceScanService`), and that is deliberate. macOS FSEvents
does not report a PID, and `lsof` only shows descriptors that are still open — by the time a change
is noticed the writer has closed the file. Attribution comes from the agent layer instead. Getting
a real file→PID link needs Endpoint Security Framework: a system extension, an Apple entitlement
and root. See P9-04.*

**P2-02 Live side-by-side git diff viewer (Monaco)** ✅ (unified diff) / 🟢 (Monaco)
*`GitService` + `/api/diff` serve the diff today, rendered as a coloured unified diff with no
external dependency. Monaco side-by-side is a straight upgrade whenever a build step is acceptable.*

**P2-03 Auto-git snapshot / time-travel undo** 🟡
Micro-snapshots before a large agent action, so it can be rolled back in one click.
*Note: an observer writing into the user's own repository is a bad trade. Use a shadow repository
(`GIT_DIR` pointing outside the workspace) so snapshots can never touch the user's index, stash or
reflog. Also worth knowing: Claude Code already records `file-history-snapshot` entries in its
transcript, which covers part of this for free.*

## Epic 3 — Terminal & output capture

**P3-01 Captured subprocess output stream (xterm.js)** 🔴 as written / ✅ via the agent layer
Capture stdout/stderr of subprocesses the agent starts.
*Note: you cannot attach to the stdout of an already-running process you did not spawn — that needs
ptrace/dtrace and root, and SIP blocks it. But it is not needed: the transcript records every tool
result, so the output of a `pytest` or `mvn` run an agent started is already in the feed. Only
processes the user starts outside an agent stay invisible.*

**P3-02 Central log tailing** 🟢
Tail known log files in the workspace and in agent config directories.
*Note: the path in the original list, `~/.config/claude/logs/`, does not exist. Claude Code lives in
`~/.claude/`, and the useful file is
`~/.claude/projects/<escaped-cwd>/<session-id>.jsonl` — which is exactly what layer 1 tails.*

## Epic 4 — Control, guardrails & security

**P4-01 Emergency kill switch** 🟡
A red button that sends `SIGTERM`/`SIGKILL` to a PID or process tree.
*Note: this turns a passive observer into a controller, which is the one property that currently
makes the tool safe to point at anything. Ship it opt-in and off by default, behind a token, and
never expose the port. Design the config so the read-only build stays a supported mode.*

**P4-02 Filesystem watchdog & protected paths** ✅
Warn when an agent touches files outside the project, or sensitive files like `.env` or
`.git/config`.
*Done as a `PreToolUse` hook, so it blocks rather than warns after the fact. Two deliberate steps to
enable — install the guard script, then switch enforcement on — and in between, rules produce events
without stopping anything, so you see what they catch first. Fails open everywhere: a hook holds the
agent until it answers, which makes this a guardrail against mistakes rather than a boundary
against an adversary, and the README says so.*

**P4-03 Multi-workspace support** ✅
Switch between project folders from a dropdown.
*Done, and better than proposed: workspaces are not configured at all. The hook writes a spool
directory per project and leaves a marker with the real path, so a project registers itself the
first time an agent does anything in it. The watcher starts with no argument, adopts the most
recently active project, and the header dropdown switches between registered ones. Collectors read
the active workspace on every poll, so a switch takes effect within one interval with no restart.*

## Epic 5 — UX, multi-device & remote access

**P5-01 WebSockets / SSE live updates with throttling** ✅
*Implemented as a GraphQL subscription over `graphql-ws`. The client is hand-rolled (about sixty
lines) to keep the frontend build-step free. Throttling under heavy file churn is still to do — see
P9-02.*

**P5-02 Multi-monitor layout / detachable panels** 🟢

**P5-03 Auth & tunnel support** 🟡 partially done
*The app binds to `127.0.0.1` today, which is the important half. Token auth is still to add.
Note that a tunnel (SSH, Tailscale) is the recommendation, not an extra: this dashboard serves
source diffs and full command lines, so an open port is not something to secure later.*

## Epic 6 — Visual dashboard & IDE-grade experience

**P6-01 Modern dark theme** ✅ (as a dense IDE theme, not glassmorphism)
*Opinion, take it or leave it: blur and translucency cost contrast, and this is a monospace wall of
paths and diffs meant to be readable at a glance from a second monitor. Colour is spent on meaning
here — source of an event, add/delete in a diff — rather than on surface.*

**P6-02 Interactive node-based process graph** 🟢
*Worth doing on top of agent events rather than the sampler, so short-lived nodes actually appear.*

**P6-03 Multi-tab / split-screen canvas** 🟢

## Epic 7 — Intelligent agent insights & analytics

**P7-01 Agent action heatmap** 🟢
*Now genuinely accurate, because read/write counts per file come from the transcript rather than
from guessing at FS events.*

**P7-02 Cost & token tracker** ✅
*Read from the transcripts, so a figure is a session's whole total rather than only what happened
since the watcher started. Counted per token kind, which turned out to matter more than expected:
on this project cache reads were 71% of the bill, so counting only input and output would have
understated it fivefold. Rates live in an editable `pricing.json`; an unpriced model is reported as
unpriced rather than free.*

**P7-03 Execution timeline / session replay** ✅
*A timeline along the bottom over a selectable window, with a slice you can click to replay that
moment out of the database. Density is counted in SQL rather than by returning rows - a month is
millions of events and a timeline needs a few hundred numbers. Agent-caused events are counted
apart from the rest, because a thousand file events during a checkout is noise while ten tool calls
is the story, and one combined bar would hide the second behind the first. This is arguably the strongest feature in the whole list:
scrubbing back through what an agent did over an hour is something no existing tool offers.*

## Epic 8 — Notifications & interactivity

**P8-01 Sound & browser push notifications** 🟢

**P8-02 Human-in-the-loop approval from the dashboard** 🟡
*Possible, and the mechanism already exists: a `PreToolUse` hook blocks until it returns, so it can
wait on a dashboard decision. But it makes the observer load-bearing for the agent — a crashed
dashboard would hang the agent. Needs a timeout that fails open, and it belongs behind the same
opt-in as P4-01.*

---

**P4-03b Workspace switching completeness** ✅
*Every panel follows a switch — git, processes, sessions, the feed, the open file — and the choice
is remembered across restarts, on the server rather than in the browser.*

## Epic 9 — Added: what the original list did not cover

**P9-01 Event persistence (SQLite)** ✅
*Everything the buffer holds is also written to SQLite, tagged with the workspace it belonged to,
and reachable through `history(workspace, since, until, limit)`. Writes are queued and flushed in
batches so a burst of file events never makes a collector wait on disk; if the queue fills, the
newest are dropped and logged, because stalling a collector to protect the archive would be the
wrong way round. Plain JDBC, one table: there is no object graph here and an ORM would be more
machinery than the thing it manages. Verified against a SIGKILL rather than a clean shutdown.
Measured at 500k rows: 251k inserts/s, 1ms for the most recent 500, 94ms for a large range scan,
490 bytes a row. Retention is by age and by row count, because age alone does not bound a file.*

**P9-02 Backpressure and throttling** ✅
A `npm install` produces tens of thousands of file events. The feed needs coalescing per path and a
throughput cap before it meets a real build.

*Done in three places. `EventBus.stream()` uses a bounded per-subscriber buffer that drops the
oldest events and logs the loss, so a paused tab cannot grow the heap. A scan that changes more
than `maxFileEventsPerScan` files collapses into one summary event rather than thousands of rows.
And the scanner now derives its own interval from how long a walk actually takes, holding itself to
a tenth of wall-clock time — a 66,000-file tree was costing 30-85% of a core at the configured
750ms.*

Also worth revisiting on the client: once the UI grows past a plain feed, RxJS for stream operators
and a virtualised list are the two libraries that actually earn their weight here.

**P9-03 Agent adapter interface** 🟢
Layer 1 is Claude-Code-specific today. Make it an interface so Aider, Codex, OpenClaw, Gemini CLI
and custom scripts each get an adapter. The spool directory is already the generic fallback: any
agent that can write a JSON file gets picked up, with no network and no client library.

**P9-04 Endpoint Security Framework backend (research spike)** 🔴 today
The only real route to file→PID attribution for non-agent processes. Requires a system extension,
an Apple-granted entitlement and root. Worth a spike to size, not worth blocking anything on.

**P9-05 Linux parity** 🟢
`/proc/<pid>/cwd` replaces `lsof`, `inotify` replaces the poller. Needed for the remote-server use
case that motivated the webapp in the first place.

**P9-06 Subagent and MCP visibility** 🟡 partially done
*Sessions are now a first-class filter: the register reads them from the transcript files, picks up
the title Claude Code generates, and marks a session live while its transcript is still being
written. Sidechains (subagents) and MCP servers are still not broken out as their own lanes.*

**P9-06b (was P9-06) Original item** 🟢
Transcripts distinguish sidechains (subagents) and MCP tool calls. Showing a subagent as its own
lane, and MCP servers as their own actors, is nearly free and is exactly the "black box" the tool
exists to open.

**P9-07 Tests and CI** ✅
*27 tests covering the parsers and the bug classes this project actually hit: the git path
resolution that broke when the workspace is a subdirectory, transcript tailing across partial lines
and multi-byte characters and truncation, `lsof` output including a sibling directory that merely
shares a prefix, hook payloads that are malformed or enormous, and an event stream that replays
history then goes live without a gap, a duplicate, or an unbounded buffer. CI builds on JDK 25 and
26, runs the tests on macOS, and checks that the jar actually starts and answers a query.*

**P9-08 Packaging** 🟢
A single runnable jar exists. A Homebrew formula and a `docker run` recipe are what make it
installable for anyone else.
