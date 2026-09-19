import { css, html, LitElement, type PropertyValues } from 'lit';
import { request } from '../api/client';
import { RedactHistoryDocument, SensitiveEventsDocument } from '../api/documents';
import type { SensitiveEventsQuery } from '../gql/graphql';
import { panelStyles } from '../styles';

type SensitiveEvent = SensitiveEventsQuery['sensitiveEvents'][number];

/** What the scanner puts in `detail`: names and a count, never the matched text. */
type Detail = {
  rule?: string;
  tool?: string;
  host?: string;
  count?: number;
  sourceType?: string;
};

type Row = { event: SensitiveEvent; detail: Detail };

const LIMIT = 500;
const REFRESH_MS = 15_000;
/** How long the redact button stays armed after the first click. */
const CONFIRM_MS = 5_000;
const SCANNED_KEY = 'ww-history-scanned-at';

const CLOCK = new Intl.DateTimeFormat('en-GB', {
  hour12: false,
  hour: 'numeric',
  minute: 'numeric',
  second: 'numeric',
});

function toRow(event: SensitiveEvent): Row {
  try {
    return { event, detail: event.detail ? (JSON.parse(event.detail) as Detail) : {} };
  } catch {
    return { event, detail: {} };
  }
}

function matches(row: Row, needle: string): boolean {
  return (
    needle === '' ||
    (row.detail.rule ?? '').toLowerCase().includes(needle) ||
    (row.detail.host ?? '').toLowerCase().includes(needle)
  );
}

/**
 * Secrets and personal data an agent sent or received (Epic 18), newest first, from the archive.
 *
 * <p>Polled rather than subscribed: the rows come from the archive so a hit from last week is still
 * here, and the header already knows the moment a new one arrives - it hands the count down, and a
 * changed count is what triggers the immediate refetch.
 */
export class Sensitive extends LitElement {
  static properties = {
    count: { type: Number },
    rows: { state: true },
    filter: { state: true },
    error: { state: true },
    armed: { state: true },
    redacting: { state: true },
    redacted: { state: true },
    scannedAt: { state: true },
  };

  /** Hits since start, as the header counts them. */
  declare count: number;
  declare private rows: Row[] | null;
  declare private filter: string;
  declare private error: string | null;
  /** True between the first click on "redact history" and the confirming second one. */
  declare private armed: boolean;
  declare private redacting: boolean;
  /** Rows the last redaction changed, or null while none has run this session. */
  declare private redacted: number | null;
  declare private scannedAt: string | null;

  private timer: ReturnType<typeof setInterval> | null = null;
  private disarm: ReturnType<typeof setTimeout> | null = null;
  /** Bumped per refresh so a slow answer cannot overwrite a newer one (see docs/frontend.md). */
  private generation = 0;

  static styles = [
    panelStyles,
    css`
      h2 input[type='search'] {
        background: var(--panel);
        color: var(--text);
        border: 1px solid var(--line);
        border-radius: 4px;
        font: inherit;
        font-size: 12px;
        text-transform: none;
        letter-spacing: 0;
        padding: 1px 6px;
        width: 18ch;
        margin-left: auto;
      }
      h2 input[type='search']:focus {
        outline: none;
        border-color: var(--accent);
      }
      button {
        background: none;
        border: 1px solid var(--line);
        border-radius: 10px;
        color: var(--dim);
        font: inherit;
        font-size: 11px;
        padding: 1px 8px;
        cursor: pointer;
      }
      button.armed {
        color: var(--del);
        border-color: var(--del);
      }
      button:disabled {
        cursor: default;
        opacity: 0.6;
      }
      .status {
        display: flex;
        gap: 10px;
        align-items: baseline;
        padding: 4px 12px;
        color: var(--dim);
        border-bottom: 1px solid var(--line);
        flex: none;
      }
      .status .error {
        color: var(--del);
      }
      .pill {
        border: 1px solid var(--line);
        border-radius: 10px;
        padding: 0 8px;
        font-size: 11px;
        color: var(--dim);
      }
      .rowline {
        cursor: pointer;
      }
      .ts {
        color: var(--dim);
        flex: none;
      }
      .rule {
        width: 16ch;
        flex: none;
        color: var(--del);
      }
      .tool {
        width: 10ch;
        flex: none;
        color: var(--hook);
      }
      .host {
        flex: 1;
        min-width: 0;
      }
      .host.local {
        color: var(--dim);
      }
      .chip {
        border: 1px solid currentColor;
        border-radius: 3px;
        padding: 0 3px;
        font-size: 11px;
        opacity: 0.9;
        color: var(--dim);
        flex: none;
      }
      .n {
        flex: none;
        min-width: 3ch;
        text-align: right;
        color: var(--dim);
      }
    `,
  ];

  constructor() {
    super();
    this.count = 0;
    this.rows = null;
    this.filter = '';
    this.error = null;
    this.armed = false;
    this.redacting = false;
    this.redacted = null;
    this.scannedAt = localStorage.getItem(SCANNED_KEY);
  }

  connectedCallback(): void {
    super.connectedCallback();
    // The first fetch comes from updated(): `count` always changes on the first update, and a
    // second fetch here would race it for the same rows.
    this.timer = setInterval(() => this.refresh(), REFRESH_MS);
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    if (this.timer !== null) clearInterval(this.timer);
    this.timer = null;
    if (this.disarm !== null) clearTimeout(this.disarm);
    this.disarm = null;
    this.generation += 1;
  }

  updated(changed: PropertyValues<this>): void {
    if (changed.has('count')) this.refresh();
  }

  private async refresh() {
    const generation = ++this.generation;
    try {
      const { sensitiveEvents } = await request(SensitiveEventsDocument, { limit: LIMIT });
      if (generation !== this.generation) return;
      this.rows = sensitiveEvents.map(toRow);
      this.error = null;
    } catch (e) {
      if (generation !== this.generation) return;
      this.error = e instanceof Error ? e.message : String(e);
    }
  }

  private select(event: SensitiveEvent) {
    // The same event the feed's rows dispatch, so the inspector shows this record the same way.
    this.dispatchEvent(
      new CustomEvent('event-selected', { detail: event, bubbles: true, composed: true }),
    );
  }

  private hide() {
    this.dispatchEvent(new CustomEvent('sensitive-hide', { bubbles: true, composed: true }));
  }

  /**
   * Two clicks within five seconds, inline. Not window.confirm: a modal blocks the page, and this
   * page has a feed that has to keep moving.
   */
  private async redact() {
    if (this.redacting) return;
    if (!this.armed) {
      this.armed = true;
      this.disarm = setTimeout(() => (this.armed = false), CONFIRM_MS);
      return;
    }
    if (this.disarm !== null) clearTimeout(this.disarm);
    this.disarm = null;
    this.armed = false;
    this.redacting = true;
    try {
      const { redactHistory } = await request(RedactHistoryDocument);
      this.redacted = redactHistory;
      this.scannedAt = new Date().toISOString();
      localStorage.setItem(SCANNED_KEY, this.scannedAt);
      this.error = null;
      await this.refresh();
    } catch (e) {
      this.error = e instanceof Error ? e.message : String(e);
    } finally {
      this.redacting = false;
    }
  }

  private renderRow({ event, detail }: Row) {
    const host = detail.host ?? null;
    const who = event.subagent ? `agent:${event.subagent}` : (event.agent ?? '');
    const session = event.sessionId ? event.sessionId.slice(0, 8) : '';
    return html`
      <div class="rowline" title=${event.summary ?? ''} @click=${() => this.select(event)}>
        <span class="ts">${CLOCK.format(new Date(event.ts))}</span>
        <span class="rule ellipsis">${detail.rule ?? event.type.toLowerCase()}</span>
        <span class="tool ellipsis">${detail.tool ?? detail.sourceType ?? ''}</span>
        <span class="host ellipsis ${host ? '' : 'local'}">${host ?? 'local'}</span>
        ${who || session
          ? html`<span class="chip">${who}${who && session ? ' · ' : ''}${session}</span>`
          : ''}
        <span class="n">${detail.count ?? 1}</span>
      </div>
    `;
  }

  render() {
    const needle = this.filter.trim().toLowerCase();
    const rows = (this.rows ?? []).filter((row) => matches(row, needle));
    const scanned = this.scannedAt
      ? new Date(this.scannedAt).toLocaleString('en-GB', { hour12: false })
      : 'not yet';
    return html`
      <h2>
        Sensitive <span class="count">${this.count}</span>
        <input
          type="text"
          placeholder="filter rule or host…"
          .value=${this.filter}
          @input=${(e: Event) => (this.filter = (e.target as HTMLInputElement).value)}
        />
        <button
          title="Fold the panel away; the header pill brings it back"
          @click=${() => this.hide()}
        >
          hide
        </button>
      </h2>
      <div class="status">
        <span
          >${this.count} hit${this.count === 1 ? '' : 's'} since start · history scanned:
          ${scanned}</span
        >
        <button
          class=${this.armed ? 'armed' : ''}
          ?disabled=${this.redacting}
          title="Replaces every secret in the recorded history by its redacted form. Irreversible: click twice."
          @click=${() => this.redact()}
        >
          ${this.redacting
            ? 'redacting…'
            : this.armed
              ? 'click again to redact - irreversible'
              : 'redact history'}
        </button>
        ${this.redacted !== null
          ? html`<span class="pill">${this.redacted} rows changed</span>`
          : ''}
        ${this.error ? html`<span class="error">${this.error}</span>` : ''}
      </div>
      <div class="body">
        ${this.rows === null
          ? html`<p class="empty">loading…</p>`
          : rows.length === 0
            ? html`<p class="empty">${needle ? 'no match' : 'nothing sensitive seen'}</p>`
            : rows.map((row) => this.renderRow(row))}
      </div>
    `;
  }
}

customElements.define('ww-sensitive', Sensitive);
