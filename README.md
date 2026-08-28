# workspace-watcher

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

## Quick start

```bash
mvn -DskipTests package
java -jar target/workspace-watcher-0.1.0-SNAPSHOT.jar --watcher.workspace=/path/to/your/project
```

Open <http://127.0.0.1:8080>. Then, in your own terminal, `cd` to that project and start Claude Code
as you normally would.

If your default `java` is older than 25:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 25) mvn -DskipTests package
```

## Hooks (optional, lower latency)

Transcript tailing already captures everything within about half a second. If you want events the
moment they happen, install the hook:

```jsonc
// .claude/settings.json in your project
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

The script always exits 0 and ignores errors. An observer must never be able to block or alter the
agent it is watching.

## Configuration

Any property can be passed as `--watcher.foo=bar` or set in `application.yml`.

| Property | Default | |
|---|---|---|
| `watcher.workspace` | current directory | folder to observe |
| `watcher.claude-home` | `~/.claude` | where transcripts live |
| `watcher.fs-poll-ms` | `750` | workspace rescan interval |
| `watcher.transcript-poll-ms` | `500` | transcript tail interval |
| `watcher.process-poll-ms` | `2000` | process sampling interval; `0` disables it |
| `watcher.history-size` | `2000` | events replayed to a newly opened dashboard |
| `watcher.ignore-dirs` | `.git`, `node_modules`, `target`, … | never descended into |
| `server.address` | `127.0.0.1` | **see Security** |

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
- **Events are in memory.** A restart loses history. Persistence is [P9-01](BACKLOG.md).

## Architecture

```
be.kleisli.ww
├── core     WatchEvent, EventBus (ring buffer + fan-out), WatcherProperties, Shell
├── claude   TranscriptTailService (layer 1a), HookController (layer 1b)
├── fs       WorkspaceScanService (layer 2)
├── git      GitService — shells out to git rather than embedding JGit
├── proc     ProcessTreeService — lsof + ProcessHandle
└── web      EventStreamController (SSE), ApiController (/api/status, /api/diff)
```

Server-Sent Events rather than WebSockets: the stream is one-directional, it reconnects by itself,
and it passes through SSH tunnels and ordinary proxies without configuration.

The frontend is plain HTML, CSS and JavaScript with no build step and no CDN — it works offline and
stays readable.

## Roadmap

[BACKLOG.md](BACKLOG.md) holds the full feature backlog, with each item marked feasible, needing a
reframe, or blocked, and why.

## License

MIT — see [LICENSE](LICENSE).
