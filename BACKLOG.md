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

**P4-02 Filesystem watchdog & protected paths** 🟢 — highest-value item in the list
Warn when an agent touches files outside the project, or sensitive files like `.env` or
`.git/config`.
*Note: as a warning this arrives after the fact. For Claude Code the same rule can run as a
`PreToolUse` hook, which can actually **block** the write. Same rule set, real enforcement.*

**P4-03 Multi-workspace support** 🟢
Switch between project folders from a dropdown.
*Requires making the workspace a per-session runtime value instead of one startup property.*

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

**P7-02 Cost & token tracker** 🟢
*Note: no log scraping needed. Token usage is on the assistant messages in the transcript, and
Claude Code also exports OpenTelemetry metrics natively.*

**P7-03 Execution timeline / session replay** 🟢
*Needs persistence first — see P9-01. This is arguably the strongest feature in the whole list:
scrubbing back through what an agent did over an hour is something no existing tool offers.*

## Epic 8 — Notifications & interactivity

**P8-01 Sound & browser push notifications** 🟢

**P8-02 Human-in-the-loop approval from the dashboard** 🟡
*Possible, and the mechanism already exists: a `PreToolUse` hook blocks until it returns, so it can
wait on a dashboard decision. But it makes the observer load-bearing for the agent — a crashed
dashboard would hang the agent. Needs a timeout that fails open, and it belongs behind the same
opt-in as P4-01.*

---

## Epic 9 — Added: what the original list did not cover

**P9-01 Event persistence (SQLite)** 🟢
Events live in a 2000-entry ring buffer and die with the process. Persistence is the prerequisite
for replay (P7-03), for cross-session history, and for surviving a restart.

**P9-02 Backpressure and throttling** 🟢
A `npm install` produces tens of thousands of file events. The feed needs coalescing per path and a
throughput cap before it meets a real build. `EventBus.stream()` currently uses an unbounded
`BUFFER` overflow strategy, which is the wrong answer for a slow client — replace it with a bounded
buffer plus `Flux.bufferTimeout` coalescing.

Also worth revisiting on the client: once the UI grows past a plain feed, RxJS for stream operators
and a virtualised list are the two libraries that actually earn their weight here.

**P9-03 Agent adapter interface** 🟢
Layer 1 is Claude-Code-specific today. Make it an interface so Aider, Codex, OpenClaw, Gemini CLI
and custom scripts each get an adapter, with the hook endpoint as the generic fallback for anything
that can run a shell command.

**P9-04 Endpoint Security Framework backend (research spike)** 🔴 today
The only real route to file→PID attribution for non-agent processes. Requires a system extension,
an Apple-granted entitlement and root. Worth a spike to size, not worth blocking anything on.

**P9-05 Linux parity** 🟢
`/proc/<pid>/cwd` replaces `lsof`, `inotify` replaces the poller. Needed for the remote-server use
case that motivated the webapp in the first place.

**P9-06 Subagent and MCP visibility** 🟢
Transcripts distinguish sidechains (subagents) and MCP tool calls. Showing a subagent as its own
lane, and MCP servers as their own actors, is nearly free and is exactly the "black box" the tool
exists to open.

**P9-07 Tests and CI** 🟢
There are none. The transcript parser and the `lsof` parser both have fixture-shaped inputs and
should be covered before the format shifts under them.

**P9-08 Packaging** 🟢
A single runnable jar exists. A Homebrew formula and a `docker run` recipe are what make it
installable for anyone else.
