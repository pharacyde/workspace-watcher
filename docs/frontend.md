# Frontend

Every bullet here is a bug that has already happened in this UI, or an obvious-looking approach
that was tried and rejected. None of it is a description of how the components work.

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
- **Whether to *stop* following is decided by wheel/touch/key, never by `scroll`.** A scroll event
  cannot say whose scroll it was: assigning `scrollTop` fires one, and measuring a layout the
  virtualizer was still growing made an earlier version decide "not at the bottom" and switch
  following off permanently — one row in, and the feed silently stopped. There *is* a `scroll`
  handler, added later and covered below, but it may only put the view back, never turn following
  off.
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
- **Re-pinning needs three signals; each one alone leaves the feed stuck.** They were found in this
  order, and none of them replaces the ones before it.
  (1) `rangeChanged`, not only an arriving event: the virtualizer measures rows asynchronously and
  its total height grows in stages, so a scroll that was at the bottom stops being at the bottom
  with no event and no render to notice it — measured with wrap on and 160 rows, `scrollTop` sat at
  7164 while the height had reached 8796, and stayed there. A settle loop cannot fix this however
  many rounds it gets: the only bound it can pick is a guess at how long measuring takes, and that
  grows with the list. Found by the browser test on its first run, which is the whole argument for
  having one.
  (2) A MutationObserver on the `transform` of its `[virtualizer-sizer]` child, because the height
  can grow without a `rangeChanged`: a re-measurement that makes rows already on screen taller
  changes nothing about *which* items belong there, so the virtualizer fires no event at all while
  the scroll area grows underneath the feed — measured at one run in nine to fifteen, the feed stopped 391 px from
  the end with following on and stayed there for thirty seconds. It does state the new height in
  that attribute. Remember the previous value: it is rewritten with an identical one on every
  update, and re-pinning on those spins against the update it causes itself.
  (3) `scroll`, because the layout scrolls against the pin and nothing else says so: the flow layout
  keeps its anchor item in place when estimated rows turn out to be a different size, which is right
  for a reader and exactly wrong for following the tail, and it is applied after our pin — the feed
  reached the bottom, was pulled back 692 px and stayed there, with no `rangeChanged` and no change
  of height, because from the layout's point of view nothing had happened. A `scroll` handler may
  only ever re-pin, never stop following: that stays with wheel, touch and key, which fire first and
  have already answered the question by then. Being at the bottom writes nothing, so the pin does
  not feed itself.
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
- **The tab title is composed in one place (`src/title.ts`).** Two things want to speak in it - the
  workspace this tab is watching, and how many notable events arrived while it was hidden - and both
  used to assign `document.title`, which works only while there is one of them. It reads
  `WW <folder>`: an abbreviation and the last path segment. A tab strip gives a title a dozen
  characters before it truncates, so spelling out the name of the app - the same in every one of
  these tabs - would spend them all on the half that cannot tell them apart. No logo in the string
  either: the tab already draws the eye as its icon immediately to the left of it.
- **`frontend/.npmrc` pins the public registry** so a corporate mirror, which typically lags npmjs
  by a patch, cannot make the lockfile unresolvable on someone else's machine.
