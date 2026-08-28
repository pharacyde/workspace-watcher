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

export class Feed extends LitElement {
  static properties = {
    hidden_: { state: true },
    session: { state: true },
    replay: { attribute: false },
    follow: { state: true },
    workspace: { attribute: false },
    replayed: { state: true },
  };

  declare private hidden_: Set<Source>;
  /** Auto-scroll to the newest row, like tail -f. */
  declare private follow: boolean;
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
      .agent {
        color: var(--dim);
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
  private stuckToBottom = true;

  // Filtering runs on every render, and render runs once per animation frame. Recomputing over
  // MAX_EVENTS items each time is exactly the per-frame work the batching exists to avoid, so the
  // result is cached against the two inputs that can change it.
  private cache: {
    items: Event[];
    hidden: Set<Source>;
    session: string;
    result: Event[];
  } | null = null;

  constructor() {
    super();
    this.hidden_ = new Set();
    this.follow = true;
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

  private onScroll(event: globalThis.Event) {
    // Follow the tail only while the reader is already at the bottom; yanking the viewport away
    // from someone reading history is the fastest way to make a live feed useless.
    const el = event.target as HTMLElement;
    this.stuckToBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
  }

  private followTail() {
    // Two conditions, deliberately: the button is the intent, being scrolled to the bottom is the
    // moment. Following while someone has scrolled up to read would yank the view away from them.
    if (!this.follow || !this.stuckToBottom || this.replay) return;
    const list = this.renderRoot.querySelector('lit-virtualizer');
    if (list) list.scrollTop = list.scrollHeight;
  }

  updated(changed: Map<string, unknown>) {
    if (changed.has('workspace') && changed.get('workspace') != null) {
      // A switch starts a new chronicle. Keeping the old events, or a session filter naming a
      // session from the previous project, would describe the wrong workspace.
      this.log.reset();
      this.session = '';
      this.cache = null;
    }
    this.followTail();
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

  private visibleEvents(): Event[] {
    const items = this.replay ? this.replayed : this.log.items;
    if (
      this.cache &&
      this.cache.items === items &&
      this.cache.hidden === this.hidden_ &&
      this.cache.session === this.session
    ) {
      return this.cache.result;
    }
    // Picking one agent hides everything that cannot be attributed to it, filesystem events
    // included: they carry no session, so claiming they belong to the selected one would be a
    // guess of exactly the kind this project refuses to make elsewhere.
    const source = this.replay ? this.replayed : this.log.items;
    const result = source.filter(
      (event) =>
        !this.hidden_.has(event.source) &&
        (this.session === '' || event.sessionId === this.session),
    );
    this.cache = { items: source, hidden: this.hidden_, session: this.session, result };
    return result;
  }

  private sessionPicker() {
    const entries = this.sessions.value?.sessions ?? [];
    if (entries.length < 2) {
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
        <span class="count">${visible.length.toLocaleString()}</span>
        ${this.replay
          ? html`<span class="replaying"
              >${new Date(this.replay.since).toLocaleTimeString('en-GB', { hour12: false })}</span
            >`
          : ''}
        <button
          class=${this.follow ? 'on' : ''}
          title="Scroll to the newest row as it arrives, like tail -f"
          @click=${() => {
            this.follow = !this.follow;
            if (this.follow) this.stuckToBottom = true;
          }}
        >
          ${this.follow ? '⤓ follow' : '⤓ follow off'}
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
      <div class="body" style="padding:0">
        ${visible.length === 0
          ? html`<p class="empty">waiting for activity…</p>`
          : html`
              <lit-virtualizer
                @scroll=${this.onScroll}
                .items=${visible}
                .renderItem=${(event: Event | undefined) =>
                  // The virtualizer can ask for an index the list no longer has, in the frame
                  // where switching between live and replay swaps the array underneath it.
                  !event
                    ? html``
                    : html`
                  <div
                    class="rowline ${event.type === 'TOOL_ERROR' ? 'error' : ''} ${event.source}"
                    style=${event.path ? 'cursor:pointer' : ''}
                    @click=${() =>
                      event.path &&
                      this.dispatchEvent(
                        new CustomEvent('file-selected', {
                          detail: event.path,
                          bubbles: true,
                          composed: true,
                        }),
                      )}
                  >
                    <span class="ts"
                      >${new Date(event.ts).toLocaleTimeString('en-GB', { hour12: false })}</span
                    >
                    <span class="tag ${event.source}">${label(event.source, event.type)}</span>
                    <span class="msg ellipsis">
                      ${event.agent ? html`<span class="agent">${event.agent} </span>` : ''}${event.summary}
                    </span>
                        </div>
                      `}
              ></lit-virtualizer>
            `}
      </div>
    `;
  }
}

customElements.define('ww-feed', Feed);
