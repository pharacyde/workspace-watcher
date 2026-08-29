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

**P10-01 Resterend limietverbruik uit `~/.claude.json`** ✅
*De header zegt wat er is uitgegeven en zwijgt over wat er nog over is, omdat CLAUDE.md vaststelt dat
dat lokaal niet kenbaar is. Dat klopt niet: `cachedUsageUtilization` bevat `utilization.five_hour` en
`.seven_day` met een percentage en een exact `resets_at`, plus een `limits[]`-array en de
`extra_usage`-credittoestand. Nagemeten: 7% van het 5-uursvenster, 49% van het 7-daagse, met
resetmomenten. `Billing` opent dit bestand al en leest er nu alleen `"oauthAccount"` uit als string.
Twee eerlijkheidseisen: toon `fetchedAtMs`, zodat een oud cijfer zichzelf als oud aankondigt, en toon
percentages — `limit_dollars` is `null` op een abonnement. De regel in CLAUDE.md moet herschreven
worden, niet stilzwijgend overtreden. Het principe blijft heel: er wordt niets geraden en de
accountcredential blijft onaangeroerd; er wordt een bestand gelezen dat er toch al is.*

*Gedaan. `AccountLimits` leest `cachedUsageUtilization` en levert per venster een percentage, een
`resets_at`, de severity en de scope; de header toont het volste nog lopende venster (49% 7d) en het
paneel alle drie met een balk. Bij het meten kwam er één ding bij dat hierboven niet staat: het
gecachte vijfuursvenster stond op 7% terwijl zijn resetmoment veertien uur in het verleden lag — dat
cijfer als "waar je nu staat" tonen zou precies de zelfverzekerde leugen zijn waar dit project tegen
gebouwd is, dus een verlopen venster wordt als verlopen gemarkeerd en telt niet mee voor de pill.
Die verval-berekening gebeurt óók bij een cache-hit: het bestand verandert niet, de klok wel.
Gelezen wordt de `limits[]`-array, met terugval op de losse `five_hour`/`seven_day`-velden voor een
oudere vorm. De regel in CLAUDE.md is herschreven in plaats van stilzwijgend overtreden.*

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

**P10-16 Doorklikken van een proces naar zijn open bestanden** ✅ *(niet uit de brainstorm; gevraagd
tijdens gebruik)*
*Een procesregel toont de commandoregel afgekapt, en daar hield het op. Klikken opent nu het proces
in het inspectiepaneel: volledige commandoregel, werkdirectory en `lsof -p` met alleen reguliere
bestanden op een genummerde descriptor - `txt` en `mem` zijn honderd regels ruis rond de handvol die
iemand bedoelt. Descriptor 1 of 2 op een bestand is precies de rij die je wil aanklikken, want daar
staat het log; binnen de workspace opent die in de tail-weergave die blijft binnenlopen, daarbuiten
wordt hij wel genoemd maar niet geserveerd. Bij het bouwen viel een echte bug om: lsof antwoordt met
opgeloste paden, dus een workspace onder `/tmp` (link naar `/private/tmp`) filterde élk proces weg
en het paneel bleef leeg zonder foutmelding. De browsertest vond het, omdat die in zo'n map draait;
de unittests niet, omdat die met paden werken die niet bestaan.*

**P10-19 De feed stopt af en toe met volgen bij wrap aan** 🟡 *(gevonden door de browsertest)*
*Eén op de negen runs blijft de feed 391 px van het einde staan met volgen aan, en komt daar in
dertig seconden niet meer weg - dus er komt geen `rangeChanged` meer waarop opnieuw vastgezet kan
worden. Zelfde familie als de bug die er al zit: de virtualizer meet zijn rijen asynchroon en de
hoogte groeit in stappen. Reproduceerbaar met de suite in een lus; `toggling wrap does not break
following` is de test die valt.*

**P10-17 In een jar of zip kijken vanuit het procesdetail** 🟢 *(gevraagd tijdens gebruik)*
*Een proces houdt zijn eigen jar of zip open, en daar houdt het paneel nu op: de tail antwoordt
`binary`. Erin kunnen kijken zoals in een map - de entries, en doorklikken naar wat erin zit.*

**P10-18 Het actieve paneel groter maken, als in een tiling window manager** 🟡
*(gevraagd tijdens gebruik)*
*Eén toets die het paneel waar je naar kijkt het hele venster laat innemen, en weer terug. Vraagt
wel een begrip van focus, dat er nu niet is - selectie is iets anders.*

**P10-15 Bestandsinhoud en live logs in het inspectiepaneel** ✅
*Een bestand kiezen in de activity toonde tot nu alleen het event-record; de inhoud kwam er nooit in.
`fileTail` is één subscription voor twee vragen: een statisch bestand komt in één chunk en zwijgt,
een log blijft komen — dus is er geen regel nodig die de twee uit elkaar houdt op basis van de
extensie, wat een regel zou zijn die soms fout is over iets wat de server gewoon kan antwoorden. Een
bestand dat bij twee opeenvolgende scans groeit krijgt het type `APPENDED` en een `live`-chip in de
feed. Opeenvolgende identieke rijen vouwen samen tot één met een teller, want 25 losse regels over
hetzelfde logbestand duwen alles anders van het scherm. Paden buiten de workspace worden geweigerd:
loopback-only is geen reden om te serveren wat er gevraagd wordt.*

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
*Noot bij een tweede lijst: dit wordt elders "context window heatmap" genoemd met de aanname dat
Claude Code bij een volle context overgaat op FIM (fill-in-the-middle). Dat klopt niet — het
compacteert, en dat laat een ander spoor na in het transcript. De vraag eronder is wel de goede: aan
een agent die minder scherp reageert is nu niet te zien dat hij net samengevat is.*

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

## Epic 11 — De dag in commits

*Er gebeurt van alles in een dag, en bij de commit gaat het verloren. Dat is niet een gevoel maar
letterlijk wat het dashboard doet: het toont uitsluitend het verschil tussen `HEAD` en de schijf, dus
op het moment dat je commit worden die twee gelijk en is alles weg. Nagemeten op `feed.ts` direct na
de commit van 29-08: `git diff HEAD` geeft 0 regels, terwijl er in die commit 72 regels bij kwamen en
11 verdwenen. De feed weet per seconde wat er gebeurde en git bewaart wat ervan overbleef; er is
alleen nog niets dat die twee aan elkaar knoopt, en na de commit toont het dashboard van geen van
beide iets.*

**P11-01 Git-tijdlijn met het werk per commit** 🟢
*`GitService` kent alleen de working tree — `status`, `branch`, `HEAD` — en geen enkele commit. Er is
dus geen enkele manier om te zien wat er over een dag gebeurd is. Het bouwsteentje ontbreekt maar de
gegevens zijn er allemaal: `git log --numstat` levert per commit hash, tijd, auteur, onderwerp en de
bestanden met hun aantallen, en de `event`-tabel heeft ts, path, session_id en subagent met een index
op (workspace, ts). Nagemeten op de commit van 29-08 00:24 hier: het venster tussen de vorige commit
en deze bevat 761 events, waarvan 318 van de agent, over 2 sessies — en van de 12 bestanden in de
commit noemen er 6 zichzelf in de events, `frontend/src/components/feed.ts` vier keer en
`diff-panel.ts` drie keer.*

*Twee dingen die dit item moet respecteren. Ten eerste: dit is correlatie, geen oorzaak. Het venster
tussen twee commits bevat het werk dat eraan voorafging, en dat is iets anders dan bewijzen dat die
commit erdoor ontstond. De koppeling per bestandsnaam is wél exact en moet daarom de kern zijn — "dit
bestand in deze commit is om 00:14 door sessie X bewerkt" — met het venster als context eromheen. Dat
6 van de 12 bestanden geen eigen event hebben (gegenereerde `gql`-bestanden, het schema) hoort
zichtbaar te zijn en niet weggepoetst: onvolledig en eerlijk is beter dan volledig en verzonnen.*

*Ten tweede, gemeten en bijna misgelopen: git rapporteert tijden met een offset
(`2026-08-29T00:24:35+02:00`) en de `event`-tabel bewaart UTC met een `Z`
(`2026-08-28T22:26:53Z`). Die twee als string vergelijken in SQL geeft geen fout maar nul rijen — mijn
eerste meting zei letterlijk "0 events" voor een venster waarin er 761 zaten. Alles wat git aanlevert
moet naar UTC voor het de database in gaat.*

*Vorm: markers op de bestaande tijdlijn, want die kan al een venster aanklikken om terug te spelen
(P7-03), plus een lijst waarin je een commit kiest en ziet wat eraan voorafging.*

**P11-02 Het dashboard vergeet alles zodra je commit** 🟢 — defect
*Het scherpste deel van het probleem hierboven, en los van de tijdlijn al de moeite waard.
`fileVersions` vergelijkt altijd `HEAD:<pad>` met de schijf, dus na een commit zijn beide kanten
identiek en toont het diff-paneel een leeg scherm voor een bestand waar een uur werk in zit. Alles
wat de dag opleverde is onzichtbaar op precies het moment dat het af is. Git heeft het gewoon nog:
`git show HEAD:<pad>` en `git show HEAD~1:<pad>` zijn er allebei, en `git show --numstat` zegt wat er
veranderde. Wat ontbreekt is een revisie-argument op `fileVersions` — nu impliciet altijd
`HEAD..working` — zodat het paneel ook `HEAD~1..HEAD` kan tonen, oftewel de diff van een commit in
plaats van alleen die van niet-vastgelegd werk. Let op de bestaande valkuil: `git show <rev>:<pad>`
wordt vanaf de repository-root opgelost en een bestand in een submodule hoort bij een andere
repository — `versions()` heeft daar al een regressietest voor die niet mag sneuvelen.*

**P11-03 Welk deel van een commit kwam van de agent en welk deel met de hand** 🟢
*Volgt direct uit P11-01 en is misschien het interessantste ervan. Een bestand dat door de agent
bewerkt is draagt een `TOOL_USE` met `Edit`/`Write` en het pad; een bestand dat iemand zelf in de
editor aanpaste verschijnt alleen als `FS`-event zonder actor. Per commit is dat dus te splitsen: "9
van de 12 bestanden door de agent, 3 met de hand". Let op de grens van de bewering — het omgekeerde
geldt niet: geen event betekent niet "met de hand", het kan ook betekenen dat het bestand
gegenereerd is of dat de watcher toen niet draaide. Een derde categorie "onbekend" is hier geen
zwakte maar precies wat invariant 2 vraagt.*

**P11-04 Push als eigen gebeurtenis** 🟢
*Een push is nu volstrekt onzichtbaar, terwijl het het moment is waarop werk de machine verlaat — het
enige moment in de hele keten dat naar buiten treedt. `git rev-list --count @{u}..HEAD` geeft het
aantal commits dat nog niet gepusht is (hier nu 0) en is goedkoop genoeg om mee te liften op de
bestaande git-refresh. Daarmee kan een commit in de tijdlijn gemarkeerd worden als lokaal of gepusht,
en wordt de overgang zelf een event. Een `pre-push`-hook zou het exacter maken maar valt onder
invariant 1: dat is een hook die de agent kan ophouden, dus die moet dezelfde behandeling krijgen als
`GuardService` of het moet bij pollen blijven.*

**P11-05 Wat een commit gekost heeft** 🟢
*Zodra P11-01 er is, is dit een venster verder. `UsageService.inLastSeconds` telt al tokens over een
tijdvenster en `activity()` bucket ze; per commit is dat dezelfde som over het venster ertussen. Dat
maakt de vraag "wat heeft deze wijziging gekost" voor het eerst beantwoordbaar. Met dezelfde
voorbehouden als overal: het is wat die tokens bij API-tarieven zouden kosten en geen rekening, en
het venster is correlatie — een commit die twee dagen na het werk gemaakt wordt sleept alles mee wat
er ondertussen gebeurde. Een bovengrens per venster, of terugvallen op "onbekend" bij een gat, is
eerlijker dan een getal dat te groot is.*

**P11-06 Dagverslag** 🟡
*"Wat heeft de agent vandaag gedaan" in één scherm: commits, sessies, tokens, de bestanden die het
vaakst geraakt zijn. Aantrekkelijk en grotendeels een presentatielaag over P11-01 en P11-05. 🟡 omdat
de verleiding groot is er een samenvatting in natuurlijke taal van te maken, en dit project heeft
geen model in de lus — een verzonnen samenvatting is precies de fout waartegen de rest gebouwd is.
Tellen en groeperen kan wel, en is waarschijnlijk genoeg.*

---

## Epic 13 — Uit een tweede Gemini-lijst, nagekeken

*Tien suggesties. Twee zijn al gebouwd, drie stonden er al, en van de rest klopt de richting maar
niet de omschrijving. Hieronder alleen wat overblijft, met de correctie erbij — dezelfde behandeling
als de oorspronkelijke lijst kreeg.*

**Al gebouwd, geen item nodig.** *"Interactive Guard Policies: waarschuw of blokkeer wanneer een
subagent bestanden buiten de workspace aanpast, of wanneer `rm -rf` wordt aangeroepen" beschrijft
`GuardService` zoals die vandaag draait. `denyOutsideWorkspace` bestaat als schakelaar,
`rm -rf /` staat als DENY-regel en `git push --force` als WARN, naast regels voor `.ssh`, `.pem`,
`id_rsa*`, `.aws/credentials` en `.env`. En "dry-run" is niet iets om toe te voegen: het is de
standaard — met de guard uit downgradet `check()` een DENY naar WARN, wat als invariant 1b vastligt
omdat "uit" moet betekenen dat de hook niet kan blokkeren, niet dat de tekst anders luidt.*

**Al op de backlog.** *"Human-in-the-Loop Approval" is P8-02. "Context Window Heatmap" is P10-05 —
met één correctie: Claude Code doet geen FIM (fill-in-the-middle) als het vol raakt, het compacteert,
en dat is een ander verschijnsel met een ander signaal in het transcript. "Token-Efficiency per
commit" is P11-05. "Time-Travel Diff Replay" is grotendeels P10-09, maar de vorm die daar voorgesteld
wordt is beter dan wat er stond: scrubben met de tijdlijnslider en het bestand in Monaco zien zoals
het op dát moment was, in plaats van een undo-knop per bewerking. De gegevens liggen er al —
`~/.claude/file-history/<sessionId>/<hash>@vN` met `backupTime` — dus dat is een UI-keuze, geen
nieuwe bron.*

**P13-01 Stamboom van subagents** 🟢
*Het beste voorstel uit de lijst, en sinds vanavond exact te bouwen. `agent-<id>.meta.json` draagt
`parentAgentId`, `agentType`, `spawnDepth` en `toolUseId` — hier gemeten met `spawnDepth: 2`, dus
agents die op hun beurt agents starten komen echt voor. Daarmee is de boom niet af te leiden maar
letterlijk opgeschreven: geen heuristiek nodig. Per knoop is bekend wat hij deed (zijn eigen
transcript), wat hij kostte (P10-10 telt zijn tokens al mee) en hoe lang hij bezig was. De feed toont
subagents nu als losse rijen met een chip, wat de vorm van het werk verbergt: welke tak vastliep,
welke tak het duurst was.*

**P13-02 Herhaald falen en retry-lussen** 🟢
*Goed idee onder een verkeerde naam: "hallucinatie-tracker" meet iets anders dan wat hier
waarneembaar is. Wat wél waarneembaar is: dezelfde `Edit` op hetzelfde bestand kort achter elkaar,
of een reeks `TOOL_ERROR`-events op dezelfde opdracht. Dat is herhaling en falen, niet hallucinatie,
en dat onderscheid hoort in de naam te staan — een dashboard dat beweert hallucinaties te zien
verzint een oordeel dat het niet kan onderbouwen, en dat is de fout waartegen dit hele project
gebouwd is. De gegevens zijn er: `path`, `type` en `TOOL_ERROR` staan in de tabel en de
rij-samenvouwing van vanavond doet feitelijk al de helft van het werk. Let op de valse positief:
drie keer hetzelfde bestand bewerken is ook gewoon hoe iemand een refactor doet.*

**P13-03 MCP-diagnostiek per server** 🟢
*Grotendeels een groepering van twee items die er al staan: duur per tool-call (P10-03) en afgebroken
of afgewezen calls (P10-06), gesorteerd op `mcpServer` — dat veld ligt er al en wordt al getoond. De
vraag die het beantwoordt is echt en wordt nu niet beantwoord: ligt de traagheid bij Claude of bij de
integratie. Klein bovenop die twee, maar niet zinvol daarvoor.*

**P13-04 Guard-regels bewerken vanuit het dashboard** 🟢
*Wat er van "Interactive Guard Policies" wél overblijft. De regels komen nu uit een configuratie­-
bestand; ze zijn niet te zien of te wijzigen zonder een editor en een herstart. Zichtbaar maken welke
regels gelden en wat ze de afgelopen tijd geraakt hebben is het meeste van de waarde, en dat is
lezen. Schrijven vanuit het dashboard raakt invariant 1: alles wat een agent kan ophouden moet
expliciet aangezet worden, een eigen hook hebben en fail-open zijn — de guard voldoet daaraan, een
knop die hem strenger zet mag dat niet stilletjes ondermijnen.*

**P13-05 Iets meegeven aan een lopende sessie** 🟡
*"Push extra documentatie of een instructie in de actieve sessie" kan, maar niet zoals beschreven.
Er is geen ingang om op een willekeurig moment iets een sessie in te duwen; wat er wel is, is
`additionalContext` — een hook mag bij `UserPromptSubmit` en `PreToolUse` tekst teruggeven die in
Claude's context terechtkomt. Het verschil is wezenlijk: je kunt niet duwen, je kunt alleen antwoorden
op het eerstvolgende moment dat de agent langskomt. Voor "ik zie hem de verkeerde kant op gaan" is
dat meestal snel genoeg, want de volgende tool-call komt binnen seconden.*

*🟡 om twee redenen. Ten eerste raakt dit invariant 1 harder dan wat dan ook op deze lijst: dit is
per definitie de agent beïnvloeden, dus het vraagt dezelfde behandeling als `GuardService` — uit
tenzij expliciet aangezet, eigen hook, strakke timeout, fail-open. Ten tweede verandert het wat deze
app ís. Alles hierboven is observatie; dit is sturing, en dat hoort een bewuste keuze te zijn en geen
feature die er ongemerkt bij komt.*

---

## Epic 12 — Wat de watcher over zichzelf zwijgt

*Deze app bestaat om te zeggen wat er gebeurt en waar dat vandaan komt. Over zijn eigen gedrag doet
hij dat niet: hij verandert van workspace zonder te zeggen wie dat vroeg, en hij gooit events weg
zonder dat iemand het ziet. Dat is dezelfde fout als een kostentotaal dat er compleet uitziet en het
niet is.*

**P12-01 Zeg wie de workspace omzette, en laat `--watcher.workspace` echt pinnen** 🟢 — defect
*Waargenomen, niet bedacht: de app werd gestart met
`--watcher.workspace=/Users/cdeblend/Development/workspace-watcher` en keek achttien seconden later
naar `/Users/cdeblend/Development/omv-master`. De feed meldt netjes `now watching …`, maar zegt
nergens wie erom vroeg — en uit de code alleen viel het niet met zekerheid te herleiden, want
`active.set` heeft meerdere aanroepers (de `watchWorkspace`-mutatie en het adopteren door
`WorkspaceRegistry`). Voor een tool die om attributie draait is dat precies het gat dat hij bij
anderen wél dichttimmert: het `WORKSPACE`-event hoort te dragen wat het omzette — een mutatie van de
UI, adoptie na discovery, of de opdrachtregel.*

*Daaronder zit een tweede vraag die eerst beantwoord moet worden. `application.yml` zegt letterlijk
"pass `--watcher.workspace=/path/to/project`, to pin it to one instead", maar `props.getWorkspace()`
wordt precies één keer gelezen, bij het opstarten in `ActiveWorkspace` regel 35. Daarna kan alles het
overschrijven. Het is dus geen pin maar een beginwaarde. Óf het gedrag klopt en de tekst moet weg, óf
de tekst klopt en een expliciet gezette workspace hoort latere wissels te weigeren. Nu is het geen
van beide, en dat is de variant die iemand een uur zoeken kost.*

**P12-02 Laat zien wat de watcher zelf laat vallen** 🟢
*`EventStore` laat de nieuwste events vallen als de wachtrij vol loopt in plaats van een collector op
te houden — dat is de juiste keuze en staat als invariant. Maar de teller gaat alleen naar het
logbestand: `dropped` komt nul keer voor in `schema.graphqls`, dus het dashboard kan het niet weten.
Tijdens een zware build kan de feed dus stilletjes onvolledig zijn en er volledig uitzien. Dat is
dezelfde fout als een kostentotaal dat één model overslaat en dat niet zegt, en die is elders in dit
project bewust wél opgelost. `Status` heeft al `transcriptDirs` om te zeggen of laag 1 leeft; een
`droppedEvents` ernaast is dezelfde soort waarheid. De feed hoort het zichtbaar te maken op het
moment dat het gebeurt, niet pas als iemand het logbestand opent.*

---

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

**P9-09 Een browsertest, want er is er geen enkele** ✅
*De backend heeft 142 tests, de frontend nul. Dat is niet waar de bugs zitten: CLAUDE.md documenteert
inmiddels vier verschillende fouten in alleen al `followTail` — de virtualizer die zijn eigen
`scroller` nodig heeft, `scrollToIndex` dat een shim bleek, het eigen scrollen dat de handler
afvuurde en follow permanent uitzette, en de generatiewacht die tijdens een burst iedereen
verdrong — plus Monaco dat losgekoppeld moet worden voor je zijn model weggooit, en een `<pre>` die
groeit in plaats van scrollt. Stuk voor stuk met de hand gevonden in headless Chrome via CDP-scripts
die daarna weggegooid worden: 61 daarvan in één sessie. Die scripts zijn de test die er al is, alleen
niet bewaard.*

*Het hoeft geen testpiramide te worden. Eén smoke-test die de app start, de pagina laadt, een rij in
de feed aanklikt, een bestand aanklikt, tussen die twee heen en weer wisselt en controleert dat de
console leeg blijft en de feed onderaan staat, had elk van bovenstaande gevangen. Playwright of
gewoon CDP — het bestaande script is al geschreven. Let op de valkuil die dit project al kent: de
build moet eerst draaien, want de assetnamen dragen een hash en een test tegen een oude bundel meet
de vorige versie.*

*Gedaan. Zeven Playwright-tests, negen seconden, tegen de verpakte jar met een eigen workspace,
database en Claude-home. Hij verdiende zijn plaats op de eerste run: met wrap aan en 160 rijen bleef
`scrollTop` op 7164 staan terwijl de hoogte naar 8796 gegroeid was — de virtualizer meet asynchroon
door, dus wie onderaan stond staat dat daarna niet meer, zonder event of render die dat opmerkt.
Opgelost door opnieuw vast te pinnen op `rangeChanged`. Geverifieerd in plaats van aangenomen: het
scrollen in `followTail` neutraliseren laat test 3 falen, dus de suite vangt wat hij beweert te
vangen. Drie schone runs op rij.*

**P9-08 Packaging** 🟢
A single runnable jar exists. A Homebrew formula and a `docker run` recipe are what make it
installable for anyone else.
