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

**P1-03 Process resource usage (CPU & RAM)** ✅
*Sampled with one `ps` call per poll across the processes working in the workspace, stored beside
the events, and drawn as two more timeline series. GPU and Neural Engine are not included and
cannot be: `powermetrics` requires root and reports system-wide rather than per-process.*

**P1-03b Original item** 🟢
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

**P2-03 Auto-git snapshot / time-travel undo** 🟡 — zie P10-09, dat het schaduw-repo overbodig maakt
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

**P8-01 Sound & browser push notifications** ✅

**P8-02 Human-in-the-loop approval from the dashboard** 🟡
*Possible, and the mechanism already exists: a `PreToolUse` hook blocks until it returns, so it can
wait on a dashboard decision. But it makes the observer load-bearing for the agent — a crashed
dashboard would hang the agent. Needs a timeout that fails open, and it belongs behind the same
opt-in as P4-01.*

---

**P4-03b Workspace switching completeness** ✅
*Every panel follows a switch — git, processes, sessions, the feed, the open file — and the choice
is remembered across restarts, on the server rather than in the browser.*

## Epic 10 — Wat er nog niet gelezen wordt

Uit een onderzoek naar wat Claude Code zelf op deze machine wegschrijft. Elk item wijst naar data
die al bestaat: laag 1, exact, lokaal, zonder rechten, zonder de agent aan te raken. Metingen
hieronder zijn nagemeten op deze machine, niet overgenomen.

**P10-10 Tokens werden dubbel geteld** ✅
*Claude Code schrijft één transcriptrecord per content-blok — thinking, text, tool_use — en herhaalt
op elk daarvan het identieke, volledige usage-blok. Optellen vermenigvuldigde de tokens van een
bericht met het aantal blokken dat het toevallig had: 1063 records tegen 458 berichten, 58% te hoog.
Het dashboard toonde 528M tokens en $327 waar 224M en $140 juist is, en het cache-aandeel van de
kosten is 79% in plaats van de 71% die de README noemde. Precies de faalmodus waar dit project tegen
gebouwd is, en hij overleefde omdat hij zich niet aankondigt: de verhoudingen tussen tokensoorten
blijven kloppen, elk cijfer blijft plausibel, alleen de orde van grootte is fout. Gededupliceerd op
`message.id`.*

**P10-02 Subagent-transcripts worden niet gelezen** ✅
*`TranscriptLocator` zoekt `~/.claude/projects/*/*.jsonl` en mist daarmee een hele directorylaag:
subagents schrijven naar `<sessionId>/subagents/agent-<agentId>.jsonl`. Nagemeten op deze machine:
1111 bestanden, samen 764 MB, grootste 24 MB — waarvan de huidige glob er nul vindt. Het gevolg is
dat P9-06 half werk is: de start van een subagent is zichtbaar als chip, en alles wat die subagent
daarna doet valt buiten de feed. Voor wie subagents gebruikt is dat de grootste blinde vlek van de
tool. Dit weerlegt ook mijn eigen eerdere conclusie dat `isSidechain` nooit gezet wordt — ik keek in
de verkeerde bestanden. De koppeling is exact: `toolUseResult.agentId` van de `Agent`-call wijst het
bestand aan, en `agent-<agentId>.meta.json` draagt `toolUseId`, `agentType` en `spawnDepth`. Een
subagent-record draagt de `sessionId` van de ouder, dus het sessiefilter werkt meteen — een subagent
hoort als eigen laan onder zijn sessie, niet als losse sessie.*

*Gedaan. `TranscriptLocator.subagentTranscripts()` zoekt een laag dieper en de tail volgt sessies én
subagents. Twee dingen bleken bij het meten anders dan hierboven aangenomen: de `.meta.json` is niet
nodig, want `attributionAgent` staat op de regel zelf; en die staat alleen op de `assistant`-regels,
terwijl `agentId` overal staat — dus de soort wordt per `agentId` onthouden, anders viel een call in
een andere laan dan zijn eigen resultaat. Subagent-transcripts worden nooit opgeruimd (1111 op deze
machine), dus alleen bestanden die in de laatste twee uur geschreven zijn worden gevolgd: een agent
die al uren stil ligt, is klaar. Geverifieerd tegen een echt transcript van 24 MB.*

**P10-01 Resterend limietverbruik uit `~/.claude.json`** 🟢
*De header zegt wat er is uitgegeven en zwijgt over wat er nog over is, omdat CLAUDE.md vaststelt dat
dat lokaal niet kenbaar is. Dat klopt niet: `cachedUsageUtilization` bevat `utilization.five_hour` en
`.seven_day` met een percentage en een exact `resets_at`, plus een `limits[]`-array en de
`extra_usage`-credittoestand. Nagemeten: 7% van het 5-uursvenster, 49% van het 7-daagse, met
resetmomenten. `Billing` opent dit bestand al en leest er nu alleen `"oauthAccount"` uit als string.
Twee eerlijkheidseisen: toon `fetchedAtMs`, zodat een oud cijfer zichzelf als oud aankondigt, en toon
percentages — `limit_dollars` is `null` op een abonnement. De regel in CLAUDE.md moet herschreven
worden, niet stilzwijgend overtreden. Het principe blijft heel: er wordt niets geraden en de
accountcredential blijft onaangeroerd; er wordt een bestand gelezen dat er toch al is.*

**P10-11 Attributie gaat verloren bij opslag** ✅ (opslag) / 🟢 (attribution*-velden)
*De tabel `event` heeft geen kolommen voor `mcp_server` en `subagent`, dus `ApiMapper.toEvent(Stored)`
levert ze altijd null: de chips staan in de live feed en zijn weg zodra je een tijdlijnschijf
aanklikt en uit de database leest. Bovendien wordt de MCP-server uit de naam `mcp__server__tool`
gepeuterd terwijl Claude Code hem meelevert: assistant-records dragen `attributionMcpServer`,
`attributionMcpTool`, `attributionSkill`, `attributionPlugin` en `attributionAgent`. Die laatste drie
openen een dimensie die er nog niet is — welke skill of plugin een beurt aanstuurde.*

*De opslag is gedaan: `event` heeft nu `mcp_server` en `subagent`, met een aparte `db/migrate.sql`
die met foutentolerantie draait omdat SQLite geen `ADD COLUMN IF NOT EXISTS` kent — schema.sql zelf
blijft streng, zodat een echte fout daar nog steeds de applicatie tegenhoudt. Getest op een database
met de oude vorm. `attributionSkill` wordt nu ook gelezen. De rest van de `attribution*`-velden
blijft open.*

**P10-03 Duur per tool-call en per beurt** 🟢
*De feed zegt wát er draaide en niet wat het kostte, terwijl dat in grote Maven-projecten de vraag is
waar het uur heen ging. `TranscriptTailService` koppelt `tool_use.id` al aan `tool_result.tool_use_id`
in `pendingCalls` en gooit de tijdstempels weg. Daarnaast schrijft Claude Code
`system`/`subtype: turn_duration` met `durationMs`, en zeldzamer `cost-state` met
`totalToolDuration`, `totalLinesAdded` en `totalLinesRemoved` — die laatste zijn meteen een
onafhankelijke controle op P10-10.*

**P10-04 Limietblokkades en API-fouten** 🟢
*Als een agent midden in het werk stilvalt, weet het dashboard dat niet. Claude Code schrijft er een
synthetisch record voor weg met een gestructureerd veld ernaast — `quotaLimits` met `status`,
`resetsAt` en `rateLimitType` — plus `error` en `apiErrorStatus` (429, 529). Gestructureerd is hier
het punt: de Engelse zin ernaast is lokalisatiegevoelig en hoeft niet geparsed te worden. Iets anders
dan P10-01: een terugkijkend feit met een tijdstempel, geen voorspelling. Ook als markering op de
tijdlijn, want een gat door een limiet ziet er nu identiek uit als een gat waarin niet gewerkt werd.*

**P10-06 Afgewezen en afgebroken tool-calls** 🟢
*Het moment waarop jij ingrijpt is nergens zichtbaar, terwijl dat het interessantste moment in een
sessie is. Het staat in het transcript en heeft geen hook nodig: `toolDenialKind` op user-records
(`user-rejected`, `automode-blocked`, `permission-rule`), vaak met `userFeedback` waarin je in vrije
tekst zei waarom, plus onderbrekingen herkenbaar aan `[Request interrupted by user]`. In een
terugblik op een uur is "hier hield ik hem tegen en dit zei ik erbij" waardevoller dan de tien calls
eromheen.*

**P10-05 Contextvulling en compaction** 🟢
*Een sessie verloor 909.061 tokens context in één keer en niets in het dashboard zei dat.
`system`/`subtype: compact_boundary` draagt `compactMetadata` met `trigger`, `preTokens`,
`postTokens` en `durationMs`. De vulling zelf is per beurt af te leiden uit velden die al geparsed
worden. Een vijfde tijdreeks naast events, tokens, cpu en memory — en de enige die verklaart waarom
een agent halverwege een uur plots dommer werd. Let op: een compact-grens verbreekt de
`parentUuid`-keten; `logicalParentUuid` is de schakel eroverheen.*

**P10-07 Sessies aan processen koppelen** 🟢
*`WatchEvent.pid` bestaat in het record, in het schema en in `ApiMapper`, en wordt door geen enkele
producer ooit gevuld. Claude Code schrijft de ontbrekende helft zelf weg in
`~/.claude/sessions/<pid>.json`: `pid`, `sessionId`, `cwd`, `name` en `status`. Dat is laag 1 — een
bestand dat de agent over zichzelf schrijft — en dus geen verzonnen attributie; de regel dat
FS-events geen PID dragen blijft onaangetast. Het procespaneel en het sessiefilter zijn nu twee
lijsten die naar dezelfde `claude` wijzen; hiermee vallen ze samen. Voorbehoud: in een steekproef
stond `status` altijd op `busy`; de inactieve kant moet nagemeten worden.*

**P10-09 Terugdraaien per bewerking** 🟢 — vervangt P2-03
*P2-03 stond op 🟡 met het voorbehoud dat er een schaduw-repository nodig is om niet in de repo van de
gebruiker te schrijven. Dat is niet meer nodig: Claude Code bewaart de bytes al in
`~/.claude/file-history/<sessionId>/<hash>@vN`, met versienummers en `backupTime`. Aanvullend draagt
`toolUseResult` van elke `Edit` en `Write` een `structuredPatch` — de exacte hunk van díe bewerking.
Het diffpaneel toont alleen HEAD tegen werkkopie, dus vijf opeenvolgende bewerkingen van hetzelfde
bestand zijn niet uit elkaar te halen. Volledig lezend; het schaduw-repo-voorbehoud kan geschrapt.*

**P10-08 De opdracht in de feed** 🟢
*De feed toont wat de agent deed en nooit wat hem gevraagd werd. `TranscriptTailService` negeert
`text`-blokken als "narration, not actions" — juist voor het antwoord van de agent, verkeerd voor de
instructie van de gebruiker, die de hoogste signaaldichtheid van het bestand heeft. Terugscrubben in
een uur is nu een reeks tool-calls zonder de zin die ze veroorzaakte. Denkstappen blijven terecht
buiten de feed; een opdracht is geen narratie.*

**P10-12 statusLine als tweede bron** 🟡
*De payload die Claude Code aan een `statusLine`-commando geeft is rijker dan wat er op schijf staat:
`context_window.used_percentage` en `context_window_size` (de noemer die P10-05 mist), live
`rate_limits` waar `cachedUsageUtilization` een cache is, en `cost.total_lines_added`. Het script kan
in dezelfde spooldirectory laten vallen die er al is. 🟡 omdat het een tweede installatiestap is, en
omdat de verhouding tot invariant 1 opgeschreven moet worden: een statusLine-commando ligt niet op
het pad van een tool-call, wordt gedebouncet en wordt afgebroken — het ergste wat een traag script
doet is de statusbalk verouderen, niet de agent ophouden. Dat is een wezenlijk andere afweging dan
bij de guard en hoort naast invariant 1, niet eronder.*

**P10-13 Bredere hook-dekking** 🟡
*`HookEvents` zet `WatchEvent.type` letterlijk op `hook_event_name` en is dus al event-agnostisch;
`notify.ts` behandelt zelfs al een `Stop`-event dat de README nergens laat installeren. Na P10-02,
P10-04 en P10-06 blijven er twee over die iets toevoegen wat de transcripts niet geven:
`Notification` (matchers `idle_prompt`, `agent_needs_input`) en `SubagentStop`. 🟡 omdat het niet bij
installeren blijft: de events landen als kale strings zonder eigen weergave.*

**P10-14 Live headroom zonder Claude Code's cache** 🔴
*Het percentage van P10-01 komt uit een cache met een `fetchedAtMs`. Zelf de actuele stand ophalen kan
niet: er is geen CLI-ingang (`claude --help` kent geen `usage`- of `limits`-subcommando), `/usage` is
een slash-commando binnen een sessie dat de accountcredential gebruikt, en er is geen ander lokaal
cachebestand. De verse waarde is alleen bereikbaar langs de statusLine van P10-12. Dit item bestaat
om vast te leggen dat de directe route dood is, zodat hij niet nog eens onderzocht wordt.*

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

**P9-06 Subagent and MCP visibility** ✅
*Sessions are a first-class filter, and MCP calls and subagent launches now carry what they are: an
event knows which MCP server it went to and which kind of subagent it started.

One correction to the original plan, found by measuring rather than assuming. It said transcripts
"distinguish sidechains", and they do not - `isSidechain` is never true across sixty transcripts on
this machine. What is there, and abundantly, is the call itself: 302 Jenkins calls, 99 Jira, 143
subagent launches. Reading the launch is honest and the data supports it; reading a field that is
never set would have shipped an empty feature.*

**P9-06b (was P9-06) Original item** 🟢
Transcripts distinguish sidechains (subagents) and MCP tool calls. Showing a subagent as its own
lane, and MCP servers as their own actors, is nearly free and is exactly the "black box" the tool
exists to open.

**P9-07 Tests and CI** ✅
*91 tests covering the parsers and the bug classes this project actually hit: the git path
resolution that broke when the workspace is a subdirectory, transcript tailing across partial lines
and multi-byte characters and truncation, `lsof` output including a sibling directory that merely
shares a prefix, hook payloads that are malformed or enormous, and an event stream that replays
history then goes live without a gap, a duplicate, or an unbounded buffer. CI builds on JDK 25 and
26, runs the tests on macOS, and checks that the jar actually starts and answers a query.*

**P9-08 Packaging** 🟢
A single runnable jar exists. A Homebrew formula and a `docker run` recipe are what make it
installable for anyone else.
