import '@lit-labs/virtualizer';
import { css, html, LitElement } from 'lit';
import { request } from '../api/client';
import { EventsDocument, HistoryDocument, SessionsDocument } from '../api/documents';
import { EventLogController, LatestController } from '../api/subscriptions';
import type { EventsSubscription, Source } from '../gql/graphql';
import { panelStyles } from '../styles';

type Event = EventsSubscription['events'];

const MAX_EVENTS = 20_000;
const ALL_SOURCES: Source[] = ['TRANSCRIPT', 'HOOK', 'GUARD', 'FS', 'SYSTEM'];

/** Short label per source, so the eye can tell attributed events from unattributed ones. */
function label(source: Source, type: string): string {
  switch (source) {
    case 'TRANSCRIPT':
      return type === 'TOOL_USE' ? 'agent →' : 'agent ←';
    case 'HOOK':
      return 'hook';
    case 'GUARD':
      return type === 'DENIED' ? 'blocked' : 'flagged';
    case 'FS':
      return type.toLowerCase();
    default:
      return 'watcher';
  }
}

/**
 * One row of the feed: an event, and how many identical ones followed it without interruption.
 *
 * <p>A file being written to produces one event per scan, all saying the same thing about the same
 * file. Twenty rows of "appended demo-build.log" push everything else off the screen while adding
 * nothing to what the first one said. A counter says the same thing in one line and keeps the row
 * where the reader last saw it.
 */
type Row = { event: Event; repeats: number };

/**
 * Folds a run of identical events into one row.
 *
 * <p>Only consecutive ones: any other event ends the run and the next repeat starts a new row. That
 * keeps the order of the chronicle intact - a collapsed row still sits exactly where its first
 * event happened, and never absorbs something that happened after a different event.
 */
function collapse(events: Event[]): Row[] {
  const rows: Row[] = [];
  for (const event of events) {
    const last = rows[rows.length - 1];
    if (
      last &&
      last.event.source === event.source &&
      last.event.type === event.type &&
      last.event.path === event.path &&
      last.event.summary === event.summary &&
      // Attribution is part of what makes two rows the same. Without this, two subagents each
      // reading the same file fold into one row carrying the second one's name - a row that then
      // states an attribution which is wrong for half of what it stands for, which is the one
      // thing this project is built not to do. It also hid that the session filter would have
      // separated them.
      last.event.sessionId === event.sessionId &&
      last.event.agent === event.agent &&
      last.event.subagent === event.subagent &&
      last.event.mcpServer === event.mcpServer
    ) {
      // The newest one is kept, so the timestamp on the row is when it last happened.
      last.event = event;
      last.repeats++;
    } else {
      rows.push({ event, repeats: 1 });
    }
  }
  return rows;
}

export class Feed extends LitElement {
  static properties = {
    hidden_: { state: true },
    session: { state: true },
    replay: { attribute: false },
    follow: { state: true },
    wrap: { state: true },
    search: { attribute: false },
    workspace: { attribute: false },
    replayed: { state: true },
  };

  declare private hidden_: Set<Source>;
  /** Auto-scroll to the newest row, like tail -f. */
  declare private follow: boolean;
  /** Let a long command spill onto several lines instead of being cut off. */
  declare private wrap: boolean;
  /** Shared across every panel; empty means no filtering. */
  declare search: string;
  /** Empty means every agent in this workspace. */
  declare private session: string;
  /** Set by the timeline to a recorded window; null means follow the live stream. */
  declare replay: { since: string; until: string } | null;
  /** Only used to notice a switch; the feed itself is always about the watched workspace. */
  declare workspace: string | null;
  declare private replayed: Event[];

  static styles = [
    panelStyles,
    css`
      .filters {
        margin-left: auto;
        display: flex;
        gap: 9px;
        text-transform: none;
        letter-spacing: 0;
        font-weight: 400;
      }
      .filters label {
        cursor: pointer;
        user-select: none;
      }
      .ts {
        color: var(--dim);
        flex: none;
      }
      .tag {
        flex: none;
        width: 74px;
        color: var(--dim);
      }
      .tag.TRANSCRIPT {
        color: var(--accent);
      }
      .tag.HOOK {
        color: var(--hook);
      }
      .tag.GUARD {
        color: var(--del);
      }
      /* A blocked call is the loudest thing this dashboard has to say. */
      .GUARD .msg {
        color: var(--warn);
      }
      .error .msg {
        color: var(--del);
      }
      .msg {
        flex: 1;
        min-width: 0;
      }
      /* Wrapped rows are taller, and the virtualizer measures them, so the timestamp and tag are
         pinned to the top rather than floating to the middle of a three-line command. */
      .rowline.wrapped {
        align-items: flex-start;
      }
      .rowline.wrapped .msg {
        white-space: pre-wrap;
        word-break: break-word;
        overflow: visible;
        text-overflow: clip;
      }
      .agent {
        color: var(--dim);
      }
      /* A call out to another system, or a handover to another agent, should not read the same as
         editing a file two lines above it. */
      .chip {
        border: 1px solid currentColor;
        border-radius: 3px;
        padding: 0 3px;
        margin-right: 6px;
        font-size: 11px;
        opacity: 0.9;
      }
      .chip.mcp {
        color: var(--hook);
      }
      .chip.sub {
        color: var(--add);
      }
      /* A file something is still writing to. Worth the colour: it is the row where opening the
         panel gives you a log that keeps arriving rather than a file that sits there. */
      .chip.live {
        color: var(--warn);
      }
      .repeats {
        margin-left: 6px;
        padding: 0 4px;
        border-radius: 8px;
        background: var(--line);
        color: var(--dim);
        font-size: 11px;
      }
      lit-virtualizer {
        height: 100%;
      }
      .replaying {
        color: var(--warn);
        text-transform: none;
        letter-spacing: 0;
        font-weight: 400;
      }
      button {
        background: none;
        border: 1px solid var(--line);
        color: var(--dim);
        border-radius: 4px;
        font: inherit;
        font-size: 11px;
        padding: 0 6px;
        cursor: pointer;
        text-transform: none;
        letter-spacing: 0;
        font-weight: 400;
      }
      button.on {
        color: var(--add);
        border-color: var(--add);
      }
      button.paused {
        color: var(--warn);
        border-color: var(--warn);
      }
      select {
        background: var(--panel);
        color: var(--text);
        border: 1px solid var(--line);
        border-radius: 4px;
        font: inherit;
        font-size: 11px;
        padding: 0 3px;
        max-width: 26ch;
      }
    `,
  ];

  private readonly log = new EventLogController<Event>(this, EventsDocument, MAX_EVENTS);
  private readonly sessions = new LatestController(this, SessionsDocument);


  // Filtering runs on every render, and render runs once per animation frame. Recomputing over
  // MAX_EVENTS items each time is exactly the per-frame work the batching exists to avoid, so the
  // result is cached against the two inputs that can change it.
  private cache: {
    items: Event[];
    hidden: Set<Source>;
    session: string;
    search: string;
    result: Row[];
    events: number;
  } | null = null;

  constructor() {
    super();
    this.hidden_ = new Set();
    this.follow = true;
    this.wrap = false;
    this.search = '';
    this.session = '';
    this.replay = null;
    this.replayed = [];
    this.workspace = null;
  }

  private toggle(source: Source) {
    const next = new Set(this.hidden_);
    next.has(source) ? next.delete(source) : next.add(source);
    this.hidden_ = next;
  }

  /**
   * Scrolling up by hand turns following off, the way every tail-like view does.
   *
   * <p>Bound to wheel, touch and keys rather than to the scroll event, because a scroll event
   * cannot tell whose scroll it was. Assigning scrollTop fires one too, and measuring a layout the
   * virtualizer was still growing made an earlier version conclude "not at the bottom" and switch
   * following off permanently - one row in, and the feed silently stopped following.
   */
  private onUserScroll = (event: Event) => {
    if (!this.follow) return;
    // Intent, not position. wheel and keydown both fire before the browser has scrolled, so at the
    // bottom the measurement still reads a gap of zero and follow survives - it used to take a
    // second notch to notice. That window was harmless until re-pinning on rangeChanged closed it
    // for good: measured, five notches up moved scrollTop by nothing at all and the feed could no
    // longer be scrolled back by hand. Scrolling up is a request to stop following, and that is
    // readable at the moment it is asked.
    if (event instanceof WheelEvent) {
      if (event.deltaY < 0) this.follow = false;
      return;
    }
    if (event instanceof KeyboardEvent) {
      if (['ArrowUp', 'PageUp', 'Home'].includes(event.key)) this.follow = false;
      return;
    }
    // A touch drag carries no direction worth trusting, so this one still measures.
    const list = this.list;
    if (list && list.scrollHeight - list.scrollTop - list.clientHeight > 80) {
      this.follow = false;
    }
  };

  private pin(list: HTMLElement) {
    list.scrollTop = list.scrollHeight;
  }

  private get list(): (HTMLElement & { layoutComplete?: Promise<void> }) | null {
    return this.renderRoot.querySelector('lit-virtualizer');
  }

  /**
   * Re-pins to the bottom whenever the virtualizer changes what it has rendered.
   *
   * <p>The measurement is what makes this necessary. Rows are measured asynchronously and the total
   * height grows in stages, so a scroll that was at the bottom stops being at the bottom without
   * anything else happening - no event, no render, nothing to trigger followTail again. Measured
   * with wrap on and a hundred and sixty rows: scrollTop sat at 7164 while the height had grown to
   * 8796, and stayed there. A settle loop cannot fix that, however many rounds it is given: it can
   * only guess how long measuring will take, and the answer grows with the list. The library says
   * when it has done more work, so that is what to listen to.
   */
  private onRangeChanged = () => {
    if (!this.follow || this.replay) return;
    const list = this.list;
    if (!list) return;
    // No guard on the position here. Comparing against where we last pinned looked safer and was
    // measured wrong: changing every row's height moves scrollTop by itself - the browser keeps the
    // reader's anchor - so a wrap toggle read as "a person scrolled" and switched following off.
    // Whether the reader wants to follow is answered by onUserScroll at the moment they ask, and by
    // then this handler is already returning early on `!this.follow`.
    this.pin(list);
  };

  /**
   * The other half of that signal: the scrollable height grew, but the rendered range did not
   * change.
   *
   * <p>`rangeChanged` only fires when the virtualizer decides different items belong on screen. A
   * re-measurement that only makes the rows it already renders taller leaves the range alone, so
   * the total height grows with no event at all - and the feed then sits short of the end with
   * nothing left to nudge it. Measured across suite runs at one in nine when it was found and one in
   * fifteen when it was re-measured: the feed stopped 391 px from the bottom with following still
   * on and stayed there for thirty seconds.
   *
   * <p>The virtualizer states that height as a `transform` on its sizer element, rewritten on every
   * DOM update, so a MutationObserver on that one attribute is the library saying "the scroll area
   * is now this tall" - the same argument as listening to `rangeChanged` rather than guessing with
   * a settle loop. The previous value is remembered because the attribute is also rewritten with an
   * identical value, and re-pinning on those would spin against the update they themselves cause.
   */
  private readonly sizeObserver = new MutationObserver(() => {
    const sizer = this.sizer;
    if (!sizer || sizer.style.transform === this.scrollSize) return;
    this.scrollSize = sizer.style.transform;
    this.onRangeChanged();
  });
  private sizer: HTMLElement | null = null;
  private scrollSize = '';

  /**
   * Attaches the observer to the sizer, which the virtualizer creates lazily and only once it has
   * a size to report - so the element does not exist on the first render and this is re-checked
   * every update rather than set up once.
   */
  private watchScrollSize() {
    const sizer = this.list?.querySelector<HTMLElement>('[virtualizer-sizer]') ?? null;
    if (sizer === this.sizer) return;
    this.sizeObserver.disconnect();
    this.sizer = sizer;
    this.scrollSize = sizer?.style.transform ?? '';
    if (sizer) this.sizeObserver.observe(sizer, { attributes: true, attributeFilter: ['style'] });
  }

  disconnectedCallback() {
    super.disconnectedCallback();
    this.sizeObserver.disconnect();
    this.sizer = null;
  }

  /**
   * Undoes a scroll the virtualizer performed against us.
   *
   * <p>The flow layout keeps its anchor item in place across a re-measurement: when rows that were
   * estimated turn out to be a different size, it scrolls the DOM by the difference so what you
   * were reading stays under your eyes. Following the tail is the one case where that is exactly
   * wrong, and it is applied *after* our own pin - measured, the feed sat at the bottom, was pulled
   * back 692 px and stayed there, with no rangeChanged and no change in height afterwards, because
   * from the layout's point of view nothing had changed at all. Nothing else can notice that: the
   * only thing that happened is a scroll.
   *
   * <p>This is not the handler CLAUDE.md warns about. A scroll event cannot say whose scroll it
   * was, so it may never decide to stop following - that stays with wheel, touch and key, which
   * fire before the scroll and have already set `follow` to false by the time this runs. All it
   * does is put the view back where following says it belongs, and being already there is the
   * common case and writes nothing, so our own pin does not feed itself.
   */
  private onScroll = () => {
    if (!this.follow || this.replay) return;
    const list = this.list;
    if (!list) return;
    // A pixel of slack: scrollTop is fractional on a scaled display and an exact comparison would
    // rewrite it forever.
    if (list.scrollHeight - list.scrollTop - list.clientHeight <= 1) return;
    this.pin(list);
  };

  /**
   * Supersedes an in-flight followTail. updated() fires one per frame while events stream, and the
   * settle loop below spans several frames, so without this they overlap and each one forces its
   * own layout - on exactly the frame budget the batching in this component exists to protect.
   */
  private followGeneration = 0;

  private async followTail(count: number) {
    // Two conditions, deliberately: the button is the intent, being scrolled to the bottom is the
    // moment. Following while someone has scrolled up to read would yank the view away from them.
    if (!this.follow || this.replay || count === 0) return;
    const list = this.list;
    if (!list) return;
    const generation = ++this.followGeneration;

    // Awaiting layoutComplete is the part that matters: the virtualizer measures its rows
    // asynchronously, so before it resolves scrollHeight does not yet include the rows that just
    // arrived and scrolling to it lands short - the feed then silently stops following.
    //
    // scrollTop rather than scrollToIndex: that method is a documented shim the library plans to
    // remove, and it threw "Cannot set properties of null" on a row it had not laid out yet.
    // With the scroller attribute set, this element is the scroll container, so this is direct.
    await list.layoutComplete;
    // The sizer exists once the virtualizer has laid out, which is here rather than in updated():
    // on a feed that then goes quiet the next update may be minutes away, and the observer has to
    // be watching before the re-measurement it exists to catch.
    this.watchScrollSize();
    if (!this.follow) return;

    // Deliberately not checking the generation here. layoutComplete routinely takes longer than a
    // frame, and updated() fires once per frame while events stream, so every call would find
    // itself superseded before it ever scrolled - the feed would stop following during exactly the
    // burst this exists for. Every call scrolls at least once; only the settling below gives way.

    // Scrolling once is not enough. The virtualizer re-measures rows after layoutComplete has
    // resolved, so the height we just scrolled to can already be stale - most visibly when the
    // wrap toggle changes every row's height at once, which left the feed hundreds of pixels
    // short of the end. So scroll, let a frame pass, and scroll again until the height settles.
    // Bounded, and it stops on its own as soon as two frames agree.
    // A few frames of settling for the height the rows already have. Anything measured later is
    // caught by onRangeChanged instead - a bounded loop here cannot be the answer to that, because
    // the only bound it can pick is a guess at how long measuring takes, and that grows with the
    // list.
    let previous = -1;
    for (let attempt = 0; attempt < 5 && list.scrollHeight !== previous; attempt++) {
      previous = list.scrollHeight;
      this.pin(list);
      await new Promise(requestAnimationFrame);
      if (!this.follow || generation !== this.followGeneration) return;
    }
  }

  updated(changed: Map<string, unknown>) {
    this.watchScrollSize();
    void this.followTail(this.visibleEvents().length);
    if (changed.has('workspace') && changed.get('workspace') != null) {
      // A switch starts a new chronicle. Keeping the old events, or a session filter naming a
      // session from the previous project, would describe the wrong workspace.
      this.log.reset();
      this.session = '';
      this.cache = null;
    }
    if (!changed.has('replay')) return;
    if (!this.replay) {
      this.replayed = [];
      return;
    }
    // Recorded events come from the database, not the buffer: the point of scrubbing back is to
    // reach what the live stream no longer holds.
    request(HistoryDocument, { ...this.replay, limit: 2000 })
      .then(({ history }) => (this.replayed = history as Event[]))
      .catch(() => (this.replayed = []));
  }

  private visibleEvents(): Row[] {
    const items = this.replay ? this.replayed : this.log.items;
    if (
      this.cache &&
      this.cache.items === items &&
      this.cache.hidden === this.hidden_ &&
      this.cache.session === this.session &&
      this.cache.search === this.search
    ) {
      return this.cache.result;
    }
    // Picking one agent hides everything that cannot be attributed to it, filesystem events
    // included: they carry no session, so claiming they belong to the selected one would be a
    // guess of exactly the kind this project refuses to make elsewhere.
    const source = this.replay ? this.replayed : this.log.items;
    const needle = this.search.toLowerCase();
    const result = source.filter(
      (event) =>
        !this.hidden_.has(event.source) &&
        (this.session === '' || event.sessionId === this.session) &&
        (needle === '' ||
          (event.summary ?? '').toLowerCase().includes(needle) ||
          (event.path ?? '').toLowerCase().includes(needle)),
    );
    const rows = collapse(result);
    this.cache = {
      items: source,
      hidden: this.hidden_,
      session: this.session,
      search: this.search,
      result: rows,
      events: result.length,
    };
    return rows;
  }

  private sessionPicker() {
    const entries = this.sessions.value?.sessions ?? [];
    // Shown even for a single session: hiding it made the filter invisible on a fresh project,
    // and "1 agent" is information too.
    if (entries.length === 0) {
      return '';
    }
    return html`
      <select
        title="Agent sessions in this workspace"
        @change=${(event: globalThis.Event) =>
          (this.session = (event.target as HTMLSelectElement).value)}
      >
        <option value="" ?selected=${this.session === ''}>all agents (${entries.length})</option>
        ${entries.map(
          (entry) => html`
            <option value=${entry.id} ?selected=${entry.id === this.session}>
              ${entry.live ? '● ' : ''}${entry.title ?? entry.id.slice(0, 8)}
            </option>
          `,
        )}
      </select>
    `;
  }

  render() {
    const visible = this.visibleEvents();
    return html`
      <h2>
        ${this.replay ? 'Replay' : 'Activity'}
        <span class="count">${(this.cache?.events ?? visible.length).toLocaleString()}</span>
        ${this.replay
          ? html`<span class="replaying"
              >${new Date(this.replay.since).toLocaleTimeString('en-GB', { hour12: false })}</span
            >`
          : ''}
        <button
          class=${this.follow ? 'on' : ''}
          title="Scroll to the newest row as it arrives, like tail -f"
          @click=${() => (this.follow = !this.follow)}
        >
          ${this.follow ? '⤓ follow' : '⤓ follow off'}
        </button>
        <button
          class=${this.wrap ? 'on' : ''}
          title="Let a long command spill onto several lines instead of being cut off"
          @click=${() => (this.wrap = !this.wrap)}
        >
          ${this.wrap ? '⏎ wrap on' : '⏎ wrap off'}
        </button>
        <button
          class=${this.log.paused ? 'paused' : ''}
          title="Hold new events. Nothing is lost - they arrive when you resume."
          @click=${() => this.log.setPaused(!this.log.paused)}
        >
          ${this.log.paused
            ? html`▶ resume${this.log.held ? html` (${this.log.held})` : ''}`
            : '⏸ pause'}
        </button>
        ${this.sessionPicker()}
        <span class="filters">
          ${ALL_SOURCES.map(
            (source) => html`
              <label>
                <input
                  type="checkbox"
                  .checked=${!this.hidden_.has(source)}
                  @change=${() => this.toggle(source)}
                />${source.toLowerCase()}
              </label>
            `,
          )}
        </span>
      </h2>
      <div class="body" style="padding:0;overflow:hidden">
        ${visible.length === 0
          ? html`<p class="empty">waiting for activity…</p>`
          : html`
              <lit-virtualizer
                scroller
                @wheel=${this.onUserScroll}
                @touchmove=${this.onUserScroll}
                @keydown=${this.onUserScroll}
                @rangeChanged=${this.onRangeChanged}
                @scroll=${this.onScroll}
                .items=${visible}
                .renderItem=${(row: Row | undefined) => {
                  // The virtualizer can ask for an index the list no longer has, in the frame
                  // where switching between live and replay swaps the array underneath it.
                  if (!row) return html``;
                  const event = row.event;
                  return html`
                  <div
                    class="rowline ${event.type === 'TOOL_ERROR' ? 'error' : ''} ${event.source} ${this.wrap ? 'wrapped' : ''}"
                    style="cursor:pointer"
                    @click=${() =>
                      this.dispatchEvent(
                        new CustomEvent('event-selected', {
                          detail: event,
                          bubbles: true,
                          composed: true,
                        }),
                      )}
                  >
                    <span class="ts"
                      >${new Date(event.ts).toLocaleTimeString('en-GB', { hour12: false })}</span
                    >
                    <span class="tag ${event.source}">${label(event.source, event.type)}</span>
                    <span class="msg ${this.wrap ? '' : 'ellipsis'}">
                      ${event.mcpServer
                        ? html`<span class="chip mcp">mcp:${event.mcpServer}</span>`
                        : ''}${event.type === 'APPENDED'
                        ? html`<span class="chip live">live</span>`
                        : ''}${event.subagent
                        ? html`<span class="chip sub">agent:${event.subagent}</span>`
                        : ''}${event.agent
                        ? html`<span class="agent">${event.agent} </span>`
                        : ''}${event.summary}${row.repeats > 1
                        ? html`<span class="repeats" title="the same thing, this many times in a row"
                            >×${row.repeats}</span
                          >`
                        : ''}
                    </span>
                        </div>
                      `;
                }}
              ></lit-virtualizer>
            `}
      </div>
    `;
  }
}

customElements.define('ww-feed', Feed);
