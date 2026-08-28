# workspace-watcher

[![CI](https://github.com/pharacyde/workspace-watcher/actions/workflows/ci.yml/badge.svg)](https://github.com/pharacyde/workspace-watcher/actions/workflows/ci.yml)

See what an AI agent is actually doing in your project — every command it runs, every file it
touches, and the diff it just wrote — from a browser, without touching the agent's terminal.

Point it at a folder. Run Claude Code (or any other agent) in that folder from your own shell as
usual. The dashboard fills up on its own.

```
┌── Processes in workspace ────────────┬── Activity ──────────────────────────────────┐
│ 23607  /bin/zsh                      │ 18:41:02  agent →  Bash  $ mvn -B package     │
│  └─ 29883  claude                    │ 18:41:03  created  target/classes/…           │
│      └─ 29908  node                  │ 18:41:19  agent ←  Bash  BUILD SUCCESS        │
│          └─ 29933  node … mcp-jenkins│ 18:41:24  agent →  Edit  src/main/java/…      │
├── Working tree ──────────────────────┼── Diff ──────────────────────────────────────┤
│ modified  src/main/java/…/GitService │ @@ -41,6 +41,9 @@                             │
│ untracked BACKLOG.md                 │ +    public synchronized void refresh() {     │
└──────────────────────────────────────┴──────────────────────────────────────────────┘
```

## Why this exists, and why it is built this way

The obvious design is to watch the OS: catch filesystem events, then ask which process caused them.
**On macOS that does not work.** FSEvents carries no PID, and `lsof` only reports descriptors that
are still open — an agent writes a file and closes it in milliseconds, so by the time a watcher
reacts, the writer is gone. Getting a real file→PID link needs the Endpoint Security Framework: a
system extension, an Apple-granted entitlement, and root. That is a large price for one column of
data.

So workspace-watcher does not guess. It uses **two layers, and keeps them clearly separated**:

**Layer 1 — the agent's own record (exact attribution).**
Claude Code appends every turn to `~/.claude/projects/<escaped-cwd>/<session-id>.jsonl`: each tool
call with its full arguments, each result, the session id, the working directory. Tailing that file
gives you the exact command an agent ran, at the moment it ran it, with no tracing and no
privileges. Hooks can additionally push events the instant they happen. The CLI is never touched —
these are files it writes anyway.

**Layer 2 — the filesystem and git (generic, no actor claimed).**
A snapshot poller over the workspace catches every change, including from tools that have no
adapter. These events deliberately carry **no PID**, because an honest gap is more useful than a
plausible-looking wrong answer.

Every event in the feed is tagged with which layer it came from, so you always know whether an
attribution is real or absent.

## Requirements

- JDK 25 or newer
- Git on the `PATH`
- macOS (Linux works apart from the process panel — see [BACKLOG.md](BACKLOG.md), P9-05)

Node is *not* required to run a release: the frontend is built into the jar. It is downloaded
automatically by the Maven build if you build from source.

## Quick start

```bash
mvn -DskipTests package
java -jar target/workspace-watcher-0.1.0-SNAPSHOT.jar
```

No workspace argument. Install the hook once (below), then work as you normally would: the first
tool call an agent makes in a project registers it, and the watcher starts following the project
you are actually in. Switch between registered projects from the dropdown in the header, or pin one
with `--watcher.workspace=/path/to/project`. A choice is remembered across restarts — on the server
rather than in the browser, because the server is what does the watching, and the two disagreeing
would leave the panels describing a different project than the header claims.

Switching takes every panel with it: git, processes, sessions, the feed, and any file you had
open. The live feed is one workspace's chronicle, so it starts fresh; recorded history keeps the
previous workspace's events, tagged with where they belonged.

`mvn package` builds both halves: Maven downloads a pinned node, runs the frontend build, and packs
the result into the jar. Use `-DskipFrontend` to build the backend alone.

Open <http://127.0.0.1:8080>. Then, in your own terminal, `cd` to a project and start Claude Code as
you normally would.

A timeline runs directly under the header. Pick what it draws: **events** or **tokens**. They look
alike and are not — a hundred file events and one enormous prompt are indistinguishable in a count
of events, and nothing alike in what they cost. Each series draws two densities rather than one: Click a slice to scrub back into it; the activity panel becomes a replay of that moment,
read from the database rather than the buffer, so it reaches back past what the live stream still
holds. A button returns you to live.

A search box in the header filters **every panel at once** — the activity feed, the working tree and
the process list. In the process tree a match anywhere in a subtree keeps that subtree, so a hit is
never orphaned from its parent.

Clicking a row opens the event in the fourth panel: the full tool output, not the one-line headline.
Both the feed and that output have a `wrap` toggle, off by default — build output and stack traces are column-aligned,
and wrapping them destroys the alignment that makes them readable. Clicking a file in the working
tree shows its diff there instead; the last click wins, so you always know what you are looking at.

The activity panel has `follow` and `pause`. Follow is `tail -f`: scroll to the newest row as it
arrives, and it yields while you are scrolled up reading. Pause holds new events rather than
dropping them — the button shows how many are waiting, and resuming plays them in.

Two filters, in that order. The header picks the **workspace** — every project a hook has fired in
registers itself, so the list fills as you work. The activity panel then picks the **agent session**
within it, because one workspace often has several agents running in separate terminals, and every
transcript and hook event already carries the session that produced it. Selecting one agent hides
what cannot be attributed to it, filesystem events included: they carry no session, and claiming
otherwise would be the guess this project refuses to make elsewhere. The schema explorer sits at <http://127.0.0.1:8080/graphiql>.

If your default `java` is older than 25:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 25) mvn -DskipTests package
```

## API

GraphQL is the entire API — there is no REST surface. Queries go over HTTP POST to `/graphql`, and
the live feed is a `graphql-ws` subscription on the same path.

```graphql
{ status { workspace transcriptDirs
           git { branch files { path status } }
           processes { total roots { pid command children { pid command } } } } }

query { fileVersions(path: "src/main/java/be/kleisli/ww/git/GitService.java") {
          head working binary tooLarge } }

subscription { events { seq ts source type summary path agent sessionId detail } }

# Activity density for a timeline, counted in the database
query { activity(since: "2026-08-28T10:00:00Z", until: "2026-08-28T11:00:00Z", buckets: 240) {
          from count agentCount } }

# Recorded history, which outlives a restart
query { history(since: "2026-08-28T10:00:00Z", limit: 500) { ts source summary agent } }
```

`source` is the field worth reading first: `TRANSCRIPT` and `HOOK` carry real attribution, `FS`
deliberately does not.

`gitStatus` and `processTree` are separate subscriptions because they are *state*, not events. Each
emits its current value the moment you subscribe.

`detail` is a JSON string rather than a typed object. Its shape genuinely varies per source, so
typing it would either lie or drag in a scalar library for a field the UI treats as opaque.

## Hooks (optional, lower latency)

Transcript tailing already captures everything within about half a second. If you want events the
moment they happen, install the hook:

Install it globally in `~/.claude/settings.json` to observe every project you work in, or per
project in `.claude/settings.json`. Global is safe: the spool is a directory per project and prunes
itself, so one project's events can never reach another project's watcher.

```jsonc
// ~/.claude/settings.json for every project, or .claude/settings.json for one
{
  "hooks": {
    "PostToolUse": [
      { "matcher": "*", "hooks": [
          { "type": "command",
            "command": "/absolute/path/to/workspace-watcher/hooks/workspace-watcher-hook.sh" } ] }
    ]
  }
}
```

By default the script writes the payload into a spool directory and the watcher drains it. That
choice is worth explaining, because the obvious alternatives are both worse. Measured on loopback:

| transport | cost per tool call | dependencies | survives watcher being down |
|---|---|---|---|
| **spool file** (default) | **~5 ms** | none | **yes — waits on disk** |
| GraphQL mutation over HTTP | 20 ms | curl | no, event is lost |
| graphql-ws subscription | 50 ms | node or websocat | no, event is lost |

A WebSocket is the wrong shape here. A hook is a *fresh process per tool call*, so there is nothing
for a persistent connection to amortise — the handshake is paid every single time. WebSockets earn
their place where traffic is long-lived and many-messaged, which is exactly where this project does
use one: the browser subscription. Transport should follow the shape of the traffic rather than be
uniform for tidiness.

Speed is not the decisive argument anyway. A spooled event **survives the watcher being down** — it
waits on disk and is picked up whenever the watcher next starts. Sent over the network, that same
event is simply lost. For a tool whose entire purpose is not missing things, that settles it.

The script writes to a temporary name and renames into place. Rename is atomic within a filesystem,
so the watcher can never read a half-written payload — no locking, no partial JSON. Spooled files
older than an hour are discarded rather than replayed, so a watcher that was off for a week does
not dump a week of history into the feed on startup.

Set `WORKSPACE_WATCHER_URL` to post the `recordAgentEvent` mutation instead. That covers the one
case a spool cannot: a watcher running on a *different host* than the agent. On that path the script
streams the body into `curl` on stdin rather than passing it as an argument — hook payloads carry
`tool_response`, which for a large file read exceeds `ARG_MAX` (1 MB on macOS), and as an argument
that fails with *argument list too long* and the event vanishes silently. One failure also trips a
60-second circuit breaker, so a watcher that accepts connections and then stalls cannot charge its
timeout to every subsequent tool call.

Either way the script always exits 0 and discards all output. A hook blocks the agent until it
returns, and an observer must never be able to block or alter the agent it is watching.

### What it costs

The header carries what the agents in this workspace have spent. Click it for the breakdown, which
is per token kind rather than one total — because they are not priced alike, and the difference is
not small. Measured on this project: 224M cache reads against 1.2M output tokens, and the cache
reads were **71% of the bill**. A tracker that counted only input and output would have reported a
fifth of the real figure, confidently.

**It says whether it is a bill.** On a Claude subscription nobody pays per token — there is a flat
fee and usage limits — so the amount is prefixed with `≈` and labelled *what these tokens would
cost at API rates*. Detected from the local config; override with `watcher.billing`. The panel also
shows tokens used in the last five hours and seven days, which is the shape those limits take.
How much of an allowance *remains* is not shown, because it is not knowable from this machine:
Claude Code keeps no local record and fetches it live with the account credential.

Rates live in `pricing.json` beside the database, seeded from the bundled table. They are a
snapshot and they change, so the file is meant to be edited. A model with no entry is reported as
unpriced rather than free.

### Git layouts

Branches and linked worktrees work as they are: `git` reports the worktree's own branch and root,
and the watcher follows.

Submodules needed handling. `git status` in a superproject shows a submodule as one entry rather
than the files inside it, and a file inside a submodule lives in that submodule's object store —
asking the superproject for it fails with *exists on disk, but not in HEAD*, which would have
rendered the file as entirely new. The diff resolves each file against whichever repository
actually tracks it, and a submodule entry is labelled as such rather than as a modified file.

## Guard (optional)

Rules about what an agent may touch. This is the one place the project stops being a passive
observer, so it is off by default and takes two deliberate steps to enable — and even then, it
**fails open**.

Wire the guard script into `PreToolUse`, separately from the observing hook:

```jsonc
{
  "hooks": {
    "PreToolUse": [
      { "matcher": "*", "hooks": [
          { "type": "command",
            "command": "/absolute/path/to/workspace-watcher/hooks/workspace-watcher-guard.sh" } ] }
    ]
  }
}
```

With the guard installed but not enforcing, a matching rule produces an event and nothing else: the
feed shows `would block Write /Users/you/.ssh/id_rsa — ssh keys` while the call goes through. You
get to see what the rules catch before letting them catch anything. Turn enforcement on with the
`setGuard` mutation, and the same match becomes `blocked` and the tool call never runs.

Out of the box it warns on `.env` files and `.git/config`, denies `.ssh`, `.pem`, `id_rsa` and
`~/.aws/credentials`, and denies `rm -rf /`. `denyOutsideWorkspace` additionally blocks any file
outside the project.

**It is a guardrail, not a security boundary.** A hook holds the agent until it answers, so a
watcher that is slow, wedged or not running has to let the call through rather than wedge the agent
with it — measured at 30ms to allow when nothing is listening. Anyone who can stop the watcher can
bypass the guard. That is the right trade against an agent's mistakes, and the wrong one against an
adversary.

## Configuration

Any property can be passed as `--watcher.foo=bar` or set in `application.yml`.

| Property | Default | |
|---|---|---|
| `watcher.workspace` | *(empty — discover it)* | pin to one folder instead of following hooks |
| `watcher.max-stored-events` | `1000000` | hard cap on history, about half a gigabyte |
| `watcher.claude-home` | `~/.claude` | where transcripts live |
| `watcher.fs-poll-ms` | `750` | workspace rescan interval |
| `watcher.transcript-poll-ms` | `500` | transcript tail interval |
| `watcher.spool` | `~/.claude/workspace-watcher-spool` | where hooks drop payloads |
| `watcher.spool-poll-ms` | `200` | spool drain interval |
| `watcher.process-poll-ms` | `2000` | process sampling interval; `0` disables it |
| `watcher.history-size` | `2000` | events replayed to a newly opened dashboard |
| `watcher.database` | `~/.claude/workspace-watcher/events.db` | recorded history; empty disables it |
| `watcher.retention-days` | `30` | how long history is kept |
| `watcher.ignore-dirs` | `.git`, `node_modules`, `target`, … | never descended into |
| `server.address` | `127.0.0.1` | **see Security** |
| `spring.graphql.graphiql.enabled` | `true` | schema explorer at `/graphiql` |

## Security

The dashboard serves **your source diffs and full command lines**. It has no authentication.

It binds to `127.0.0.1` on purpose. To use it on a remote machine, forward the port rather than
opening it:

```bash
ssh -N -L 8080:127.0.0.1:8080 you@dev-server
```

A private overlay network such as Tailscale works equally well. Do not put this on a public
interface.

## What it does not do

Stated plainly, because a monitoring tool that overstates its coverage is worse than none:

- **File changes carry no PID.** See above. Attribution comes from layer 1 only.
- **The process panel is a sampler.** At a 2s interval, a `git status` that lives 40ms will usually
  fall between two polls. For a complete record of what an agent ran, read the activity feed.
- **Only agents with an adapter are attributed.** Today that means Claude Code, plus anything that
  can POST to the hook endpoint.
- **The live feed is in memory**, so it starts empty after a restart. Recorded history does not:
  everything is also written to SQLite and reachable through `history`. The buffer holds minutes,
  the database holds weeks.

## Developing

The dashboard reloads itself while you edit it:

```bash
java -jar target/workspace-watcher-0.1.0-SNAPSHOT.jar --watcher.workspace=/path/to/project   # 8080
cd frontend && npm run dev                                                                    # 5173
```

Open <http://127.0.0.1:5173>. Vite serves the UI with hot module replacement and proxies both
`/graphql` and its WebSocket to the backend on 8080, so the app talks to a same-origin `/graphql`
in development and in production alike — no environment switch anywhere in the code.

`npm run codegen:watch` regenerates the TypeScript types whenever the schema changes.

### The schema is the contract

`src/main/resources/graphql/schema.graphqls` is the single source of truth, and both sides are
generated from it:

- **Java** — the DGS codegen Maven plugin generates `be.kleisli.ww.generated.types` at build time.
  `ApiMapper` maps domain records onto those types, so a field renamed in the schema breaks
  compilation instead of silently returning null.
- **TypeScript** — `graphql-codegen` with the client preset generates types *and* checks every
  query in the frontend against the schema. A renamed field fails `npm run build` rather than
  showing up as `undefined` in the browser.

### Style

Java is formatted with `google-java-format` through Spotless. `mvn spotless:apply` formats; the
build fails on anything unformatted, so style never becomes review chatter.

## Architecture

```
be.kleisli.ww
├── core     WatchEvent, EventBus (ring buffer + fan-out), WatcherProperties, Shell
├── claude   TranscriptTailService (layer 1a), HookSpoolService + HookEvents (layer 1b)
├── fs       WorkspaceScanService (layer 2)
├── git      GitService — shells out to git rather than embedding JGit
├── proc     ProcessTreeService — lsof + ProcessHandle
└── web      WatchDataFetcher (DGS), ApiMapper (domain → generated wire types)
```

The API is Netflix DGS running on Spring for GraphQL — `graphql-dgs-spring-graphql-starter`, not
the classic DGS starter, which carries its own runtime and has been frozen at 9.2.2. Spring for
GraphQL does not integrate DGS itself; the integration ships from Netflix's side. Netflix's own
guidance is that the two programming models should not be mixed in one codebase, so everything here
is DGS annotations.

The frontend is **Lit** web components in TypeScript, built by Vite. Lit is 5.9 kB gzipped against
roughly 45 kB for React and DOM libraries, has no virtual DOM to diff on every event, and the whole
entry bundle comes to 82 kB (25 kB gzipped) — Monaco is code-split and only fetched when a file is
first opened.

Events arriving from the subscription are batched onto one animation frame rather than rendered
individually, and the feed is virtualised. Under a real build the bottleneck is never the
transport; it is the DOM.

There are three subscriptions, and the split between them is what keeps the dashboard readable.
`events` is a chronicle of things that happened; `gitStatus` and `processTree` are current state and
emit their present value the moment you subscribe. Mixing the two was the original design, and
measured at 91% of all events being process snapshots that said nothing — they filled the replay
buffer and pushed real history out of it.

The `events` subscription replays the buffered history before going live, so a dashboard opened
mid-session is never blank. Snapshot and subscription are taken under one lock, and overlap is
removed by sequence number: a duplicate is cheap, a gap is not.

## Roadmap

[BACKLOG.md](BACKLOG.md) holds the full feature backlog, with each item marked feasible, needing a
reframe, or blocked, and why.

## Contributing

`mvn verify` runs everything CI runs: `google-java-format` through Spotless, the tests, the frontend
build, and the jar. CI additionally builds on JDK 25 as well as 26, and runs the tests on macOS —
the platform the process layer is actually written against.

## License

MIT — see [LICENSE](LICENSE).
