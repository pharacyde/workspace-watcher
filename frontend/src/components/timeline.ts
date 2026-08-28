import { css, html, LitElement } from 'lit';
import { request } from '../api/client';
import { ActivityDocument, TokenActivityDocument } from '../api/documents';

type Bucket = { index: number; from: string; count: number; agentCount: number };

/**
 * Which series the timeline draws.
 *
 * <p>Two questions that look alike and are not: a hundred file events and one enormous prompt are
 * indistinguishable in a count of events, and nothing alike in what they cost.
 */
type Metric = 'events' | 'tokens';

const METRICS: { id: Metric; label: string; hint: string }[] = [
  { id: 'events', label: 'events', hint: 'All events, with the share an agent caused drawn on top' },
  { id: 'tokens', label: 'tokens', hint: 'Tokens consumed, with output tokens drawn on top' },
];

const WINDOWS: { label: string; seconds: number }[] = [
  { label: '1h', seconds: 3600 },
  { label: '6h', seconds: 6 * 3600 },
  { label: '24h', seconds: 24 * 3600 },
  { label: '7d', seconds: 7 * 24 * 3600 },
];

const BUCKETS = 240;

/**
 * Activity over time, and a way to scrub back into it.
 *
 * <p>Two densities are drawn, not one. File events dwarf everything else - a checkout is thousands
 * of them - so drawing only a total would say "something happened here" for every branch switch and
 * nothing about the agent. The agent-caused share is drawn on top in its own colour, and the height
 * is scaled to whichever is larger, so ten tool calls stay visible next to a thousand file writes.
 */
export class Timeline extends LitElement {
  static properties = {
    windowSeconds: { state: true },
    buckets: { state: true },
    selected: { state: true },
    metric: { state: true },
  };

  declare private windowSeconds: number;
  declare private buckets: Bucket[];
  declare private selected: number | null;
  declare private metric: Metric;

  private timer?: number;

  static styles = css`
    :host {
      display: flex;
      flex-direction: column;
      border-top: 1px solid var(--line);
      background: var(--panel);
      flex: none;
      height: 76px;
    }
    header {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 4px 12px 0;
      font-size: 11px;
      color: var(--dim);
      text-transform: uppercase;
      letter-spacing: 0.8px;
      font-weight: 600;
    }
    .metrics {
      display: flex;
      gap: 4px;
      text-transform: none;
      letter-spacing: 0;
      font-weight: 400;
    }
    .windows {
      margin-left: auto;
      display: flex;
      gap: 4px;
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
    }
    button.on {
      color: var(--text);
      border-color: var(--accent);
    }
    button.live {
      color: var(--add);
      border-color: var(--add);
    }
    .bars {
      flex: 1;
      display: flex;
      align-items: flex-end;
      gap: 1px;
      padding: 4px 12px 6px;
      min-height: 0;
    }
    .slot {
      flex: 1;
      height: 100%;
      display: flex;
      flex-direction: column;
      justify-content: flex-end;
      cursor: pointer;
      min-width: 0;
    }
    .slot:hover .total {
      background: var(--dim);
    }
    .slot.selected .total,
    .slot.selected .agent {
      outline: 1px solid var(--text);
    }
    .total {
      background: var(--line);
    }
    .agent {
      background: var(--accent);
    }
    .empty {
      color: var(--dim);
      padding: 6px 12px;
      margin: 0;
      font-size: 12px;
    }
  `;

  constructor() {
    super();
    this.windowSeconds = 3600;
    this.buckets = [];
    this.selected = null;
    this.metric = 'events';
  }

  connectedCallback(): void {
    super.connectedCallback();
    this.refresh();
    this.timer = window.setInterval(() => this.refresh(), 5000);
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    if (this.timer) clearInterval(this.timer);
  }

  private async refresh() {
    const until = new Date();
    const since = new Date(until.getTime() - this.windowSeconds * 1000);
    const range = { since: since.toISOString(), until: until.toISOString(), buckets: BUCKETS };
    try {
      if (this.metric === 'tokens') {
        const { tokenActivity } = await request(TokenActivityDocument, range);
        // Mapped onto the same shape so one renderer draws both: a total, and a highlighted share
        // of it. For events that share is what an agent caused; for tokens it is output, which is
        // the expensive half.
        this.buckets = tokenActivity.map((b) => ({
          index: b.index,
          from: b.from,
          count: b.total,
          agentCount: b.output,
        }));
      } else {
        const { activity } = await request(ActivityDocument, range);
        this.buckets = activity as Bucket[];
      }
    } catch {
      this.buckets = [];
    }
  }

  private get bucketSeconds() {
    return Math.max(1, Math.floor(this.windowSeconds / BUCKETS));
  }

  private select(bucket: Bucket) {
    this.selected = bucket.index;
    const from = new Date(bucket.from);
    const to = new Date(from.getTime() + this.bucketSeconds * 1000);
    this.dispatchEvent(
      new CustomEvent('replay-range', {
        detail: { since: from.toISOString(), until: to.toISOString() },
        bubbles: true,
        composed: true,
      }),
    );
  }

  private goLive() {
    this.selected = null;
    this.dispatchEvent(new CustomEvent('replay-range', { detail: null, bubbles: true, composed: true }));
  }

  render() {
    const peak = Math.max(1, ...this.buckets.map((b) => b.count));
    const byIndex = new Map(this.buckets.map((b) => [b.index, b]));
    const start = Date.now() - this.windowSeconds * 1000;

    return html`
      <header>
        <span class="metrics">
          ${METRICS.map(
            (m) => html`
              <button
                class=${this.metric === m.id ? 'on' : ''}
                title=${m.hint}
                @click=${() => {
                  this.metric = m.id;
                  this.buckets = [];
                  this.refresh();
                }}
              >
                ${m.label}
              </button>
            `,
          )}
        </span>
        ${this.selected !== null
          ? html`<button class="live" @click=${this.goLive}>● back to live</button>`
          : ''}
        <span class="windows">
          ${WINDOWS.map(
            (w) => html`
              <button
                class=${this.windowSeconds === w.seconds ? 'on' : ''}
                @click=${() => {
                  this.windowSeconds = w.seconds;
                  this.selected = null;
                  this.refresh();
                }}
              >
                ${w.label}
              </button>
            `,
          )}
        </span>
      </header>
      ${this.buckets.length === 0
        ? html`<p class="empty">no recorded ${this.metric} in this window</p>`
        : html`
            <div class="bars">
              ${Array.from({ length: BUCKETS }, (_, index) => {
                const bucket =
                  byIndex.get(index) ??
                  ({
                    index,
                    from: new Date(start + index * this.bucketSeconds * 1000).toISOString(),
                    count: 0,
                    agentCount: 0,
                  } satisfies Bucket);
                const total = Math.round((bucket.count / peak) * 100);
                const agent = Math.round((bucket.agentCount / peak) * 100);
                return html`
                  <div
                    class="slot ${this.selected === index ? 'selected' : ''}"
                    title="${new Date(bucket.from).toLocaleTimeString('en-GB', { hour12: false })} — ${this.metric === 'tokens' ? `${bucket.count.toLocaleString()} tokens, ${bucket.agentCount.toLocaleString()} output` : `${bucket.count} events, ${bucket.agentCount} by an agent`}"
                    @click=${() => this.select(bucket)}
                  >
                    <div class="total" style="height:${Math.max(total - agent, bucket.count ? 1 : 0)}%"></div>
                    <div class="agent" style="height:${agent}%"></div>
                  </div>
                `;
              })}
            </div>
          `}
    `;
  }
}

customElements.define('ww-timeline', Timeline);
