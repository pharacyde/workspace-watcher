import '@lit-labs/virtualizer';
import { css, html, LitElement } from 'lit';
import { EventsDocument } from '../api/documents';
import { EventLogController } from '../api/subscriptions';
import type { EventsSubscription, Source } from '../gql/graphql';
import { panelStyles } from '../styles';

type Event = EventsSubscription['events'];

const MAX_EVENTS = 20_000;
const ALL_SOURCES: Source[] = ['TRANSCRIPT', 'HOOK', 'FS', 'SYSTEM'];

/** Short label per source, so the eye can tell attributed events from unattributed ones. */
function label(source: Source, type: string): string {
  switch (source) {
    case 'TRANSCRIPT':
      return type === 'TOOL_USE' ? 'agent →' : 'agent ←';
    case 'HOOK':
      return 'hook';
    case 'FS':
      return type.toLowerCase();
    default:
      return 'watcher';
  }
}

export class Feed extends LitElement {
  static properties = { hidden_: { state: true } };

  declare private hidden_: Set<Source>;

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
    `,
  ];

  private readonly log = new EventLogController<Event>(this, EventsDocument, MAX_EVENTS);
  private stuckToBottom = true;

  constructor() {
    super();
    this.hidden_ = new Set();
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

  updated() {
    if (!this.stuckToBottom) return;
    const list = this.renderRoot.querySelector('lit-virtualizer');
    if (list) list.scrollTop = list.scrollHeight;
  }

  render() {
    const visible = this.log.items.filter((event) => !this.hidden_.has(event.source));
    return html`
      <h2>
        Activity
        <span class="count">${visible.length.toLocaleString()}</span>
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
                .renderItem=${(event: Event) => html`
                  <div
                    class="rowline ${event.type === 'TOOL_ERROR' ? 'error' : ''}"
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
