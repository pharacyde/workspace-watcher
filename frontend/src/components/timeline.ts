import { css, html, LitElement, svg } from 'lit';
import { request } from '../api/client';
import {
  ActivityDocument,
  ResourceActivityDocument,
  TokenActivityDocument,
} from '../api/documents';

type SeriesId = 'events' | 'tokens' | 'cpu' | 'memory';

type Series = {
  id: SeriesId;
  label: string;
  colour: string;
  hint: string;
  format: (value: number) => string;
};

const compact = new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 });

/**
 * The series the timeline can draw, each independently switchable.
 *
 * <p>They are overlaid rather than chosen between, because the interesting thing is usually how two
 * of them line up: a spike in tokens against a flat CPU line says something different from both
 * rising together.
 *
 * <p>No GPU or Neural Engine, and not for want of trying: {@code powermetrics} refuses to run
 * without root, and even with it reports system-wide figures rather than per-process ones.
 */
const SERIES: Series[] = [
  {
    id: 'events',
    label: 'events',
    colour: '#7b8494',
    hint: 'Everything the watcher recorded, agent actions and file changes alike',
    format: (v) => compact.format(v),
  },
  {
    id: 'tokens',
    label: 'tokens',
    colour: '#7aa2f7',
    hint: 'Tokens consumed. A hundred file events and one enormous prompt look alike in a count of events and nothing alike here',
    format: (v) => compact.format(v),
  },
  {
    id: 'cpu',
    label: 'cpu',
    colour: '#e5c07b',
    hint: 'CPU percent summed across the processes working in this workspace, so it can exceed 100 on several cores',
    format: (v) => `${v.toFixed(0)}%`,
  },
  {
    id: 'memory',
    label: 'memory',
    colour: '#c39bf5',
    hint: 'Resident memory of those same processes',
    // Not the compact formatter: it would render 2400 megabytes as "2.4KMB".
    format: (v) => (v >= 1024 ? `${(v / 1024).toFixed(1)} GB` : `${v.toFixed(0)} MB`),
  },
];

const WINDOWS: { label: string; seconds: number }[] = [
  { label: '1h', seconds: 3600 },
  { label: '6h', seconds: 6 * 3600 },
  { label: '24h', seconds: 24 * 3600 },
  { label: '7d', seconds: 7 * 24 * 3600 },
];

const BUCKETS = 240;
const HEIGHT = 100;

export class Timeline extends LitElement {
  static properties = {
    windowSeconds: { state: true },
    data: { state: true },
    enabled: { state: true },
    selected: { state: true },
    selectedEnd: { state: true },
  };

  declare private windowSeconds: number;
  declare private data: Record<SeriesId, number[]>;
  declare private enabled: Set<SeriesId>;
  declare private selected: number | null;
  /** The other end of a dragged range; equal to selected for a single bucket. */
  declare private selectedEnd: number | null;

  /**
   * Where the window started when the drag began.
   *
   * <p>The window is relative to now, so reading the clock again at the end of a drag would map the
   * two ends from different instants and hand back a range that is off by however long the drag
   * took.
   */
  private dragOrigin = 0;

  private timer?: number;
  /** Only the newest refresh may write. Toggling twice quickly started two, and the slower one
   * finished last and overwrote the other's series with an empty one. */
  private generation = 0;

  static styles = css`
    :host {
      display: flex;
      flex-direction: column;
      border-bottom: 1px solid var(--line);
      background: var(--panel);
      flex: none;
      height: 84px;
    }
    header {
      display: flex;
      align-items: center;
      gap: 8px;
      padding: 4px 12px 0;
      font-size: 11px;
      color: var(--dim);
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
      display: inline-flex;
      align-items: center;
      gap: 5px;
    }
    button.on {
      color: var(--text);
    }
    .swatch {
      width: 7px;
      height: 7px;
      border-radius: 2px;
      background: currentColor;
      opacity: 0.35;
    }
    button.on .swatch {
      opacity: 1;
    }
    .peak {
      color: var(--dim);
    }
    .windows {
      margin-left: auto;
      display: flex;
      gap: 4px;
    }
    button.window.on {
      border-color: var(--accent);
      color: var(--text);
    }
    button.live {
      color: var(--add);
      border-color: var(--add);
    }
    .chart {
      flex: 1;
      min-height: 0;
      padding: 2px 12px 6px;
    }
    svg {
      width: 100%;
      height: 100%;
      display: block;
      overflow: visible;
      cursor: crosshair;
      /* A drag across a chart otherwise starts a text selection, which fights the pointer capture
         and leaves the page highlighted blue afterwards. */
      user-select: none;
    }
    .span {
      color: var(--dim);
      font-variant-numeric: tabular-nums;
    }
    .range {
      fill: var(--accent);
      fill-opacity: 0.18;
      stroke: var(--accent);
      stroke-opacity: 0.5;
      vector-effect: non-scaling-stroke;
    }
    .cursor {
      stroke: var(--text);
      stroke-width: 0.5;
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
    this.data = { events: [], tokens: [], cpu: [], memory: [] };
    this.enabled = new Set<SeriesId>(['events', 'tokens']);
    this.selected = null;
    this.selectedEnd = null;
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

  private get bucketSeconds() {
    return Math.max(1, Math.floor(this.windowSeconds / BUCKETS));
  }

  private async refresh() {
    const generation = ++this.generation;
    const until = new Date();
    const since = new Date(until.getTime() - this.windowSeconds * 1000);
    const range = { since: since.toISOString(), until: until.toISOString(), buckets: BUCKETS };
    // Dense from the start. Assigning only the buckets that came back leaves holes, and a hole
    // spreads into Math.max as undefined, which makes the peak NaN - and NaN > 0 is false, so the
    // whole chart silently rendered as empty while the data was there all along.
    const zeroes = () => new Array<number>(BUCKETS).fill(0);
    const next: Record<SeriesId, number[]> = {
      events: zeroes(),
      tokens: zeroes(),
      cpu: zeroes(),
      memory: zeroes(),
    };

    // Only what is switched on is fetched: an unused series costs nothing.
    const wanted = this.enabled;
    const jobs: Promise<void>[] = [];

    if (wanted.has('events')) {
      jobs.push(
        request(ActivityDocument, range).then(({ activity }) => {
          for (const b of activity) next.events[b.index] = b.count;
        }),
      );
    }
    if (wanted.has('tokens')) {
      jobs.push(
        request(TokenActivityDocument, range).then(({ tokenActivity }) => {
          for (const b of tokenActivity) next.tokens[b.index] = b.total;
        }),
      );
    }
    if (wanted.has('cpu') || wanted.has('memory')) {
      jobs.push(
        request(ResourceActivityDocument, range).then(({ resourceActivity }) => {
          for (const b of resourceActivity) {
            next.cpu[b.index] = b.cpu;
            next.memory[b.index] = b.memoryMb;
          }
        }),
      );
    }

    try {
      await Promise.all(jobs);
      if (generation !== this.generation) return;
      this.data = next;
    } catch {
      // A failed refresh keeps the previous picture rather than blanking it.
    }
  }

  private toggle(id: SeriesId) {
    const next = new Set(this.enabled);
    next.has(id) ? next.delete(id) : next.add(id);
    this.enabled = next;
    this.refresh();
  }

  private peak(id: SeriesId): number {
    return Math.max(0, ...this.data[id]);
  }

  /**
   * One polyline per series, each scaled to its own peak.
   *
   * <p>Their units have nothing in common - a count, a token total, a percentage, megabytes - so a
   * shared axis would flatten three of them into the floor. Scaling each to itself compares shapes,
   * which is the question being asked, and the legend carries the peak so the height still means
   * something.
   */
  private line(series: Series) {
    const values = this.data[series.id];
    const peak = this.peak(series.id);
    if (peak === 0) return svg``;
    const points = Array.from({ length: BUCKETS }, (_, i) => {
      const x = (i / (BUCKETS - 1)) * 100;
      const y = HEIGHT - ((values[i] ?? 0) / peak) * HEIGHT;
      return `${x.toFixed(2)},${y.toFixed(2)}`;
    }).join(' ');
    return svg`<polyline
      points=${points}
      fill="none"
      stroke=${series.colour}
      stroke-width="1"
      vector-effect="non-scaling-stroke"
    />`;
  }

  private bucketAt(event: PointerEvent): number {
    const box = (event.currentTarget as SVGElement).getBoundingClientRect();
    return Math.min(
      BUCKETS - 1,
      Math.max(0, Math.floor(((event.clientX - box.left) / box.width) * BUCKETS)),
    );
  }

  private startPick = (event: PointerEvent) => {
    // Captured, so a drag that leaves the chart - which is most of them, the chart is 100px tall -
    // keeps reporting instead of stopping wherever the pointer crossed the edge.
    (event.currentTarget as SVGElement).setPointerCapture(event.pointerId);
    this.dragOrigin = Date.now();
    this.selected = this.bucketAt(event);
    this.selectedEnd = this.selected;
  };

  private extendPick = (event: PointerEvent) => {
    if (this.selected === null || !(event.buttons & 1)) return;
    this.selectedEnd = this.bucketAt(event);
  };

  private endPick = (event: PointerEvent) => {
    if (this.selected === null) return;
    this.selectedEnd = this.bucketAt(event);
    const lo = Math.min(this.selected, this.selectedEnd);
    const hi = Math.max(this.selected, this.selectedEnd);
    const start = this.dragOrigin - this.windowSeconds * 1000;
    // hi + 1: the range covers the last bucket rather than stopping where it begins. Without it a
    // click - where lo and hi are the same - would ask for a window of zero length.
    this.dispatchEvent(
      new CustomEvent('replay-range', {
        detail: {
          since: new Date(start + lo * this.bucketSeconds * 1000).toISOString(),
          until: new Date(start + (hi + 1) * this.bucketSeconds * 1000).toISOString(),
        },
        bubbles: true,
        composed: true,
      }),
    );
  };

  /**
   * The selection: a band for a dragged range, a line for a single bucket.
   *
   * <p>Drawn as one shape rather than two edges, because the question a range answers is "what
   * happened in here" and an outline leaves the reader to hold the inside in their head.
   */
  private selectionShape() {
    if (this.selected === null || this.selectedEnd === null) return '';
    const scale = 100 / (BUCKETS - 1);
    const lo = Math.min(this.selected, this.selectedEnd);
    const hi = Math.max(this.selected, this.selectedEnd);
    if (lo === hi) {
      return svg`<line class="cursor"
        x1=${lo * scale} x2=${lo * scale}
        y1="0" y2=${HEIGHT} vector-effect="non-scaling-stroke" />`;
    }
    return svg`<rect class="range"
      x=${lo * scale} width=${(hi - lo) * scale} y="0" height=${HEIGHT} />`;
  }

  /**
   * What the selection covers, in clock time.
   *
   * <p>Without it the only clue that a drag landed where it was meant to is the feed's contents,
   * and a band drawn over a chart of a hundred buckets says nothing about which minutes it is.
   */
  private selectionLabel(): string {
    if (this.selected === null || this.selectedEnd === null) return '';
    const lo = Math.min(this.selected, this.selectedEnd);
    const hi = Math.max(this.selected, this.selectedEnd);
    const start = this.dragOrigin - this.windowSeconds * 1000;
    const clock = (bucket: number) =>
      new Date(start + bucket * this.bucketSeconds * 1000).toLocaleTimeString('en-GB', {
        hour12: false,
      });
    return lo === hi ? clock(lo) : `${clock(lo)} – ${clock(hi + 1)}`;
  }

  private goLive() {
    this.selected = null;
    this.selectedEnd = null;
    this.dispatchEvent(
      new CustomEvent('replay-range', { detail: null, bubbles: true, composed: true }),
    );
  }

  render() {
    const anything = SERIES.some((s) => this.enabled.has(s.id) && this.peak(s.id) > 0);

    return html`
      <header>
        ${SERIES.map(
          (s) => html`
            <button
              class=${this.enabled.has(s.id) ? 'on' : ''}
              style="color:${this.enabled.has(s.id) ? s.colour : 'var(--dim)'}"
              title=${s.hint}
              @click=${() => this.toggle(s.id)}
            >
              <span class="swatch"></span>${s.label}
              ${this.enabled.has(s.id) && this.peak(s.id) > 0
                ? html`<span class="peak">${s.format(this.peak(s.id))}</span>`
                : ''}
            </button>
          `,
        )}
        ${this.selected !== null
          ? html`<span class="span">${this.selectionLabel()}</span>
              <button class="live" @click=${this.goLive}>● back to live</button>`
          : ''}
        <span class="windows">
          ${WINDOWS.map(
            (w) => html`
              <button
                class="window ${this.windowSeconds === w.seconds ? 'on' : ''}"
                @click=${() => {
                  this.windowSeconds = w.seconds;
                  this.selected = null;
                  this.selectedEnd = null;
                  this.refresh();
                }}
              >
                ${w.label}
              </button>
            `,
          )}
        </span>
      </header>
      <div class="chart">
        ${anything
          ? html`
              <svg
                viewBox="0 0 100 ${HEIGHT}"
                preserveAspectRatio="none"
                @pointerdown=${this.startPick}
                @pointermove=${this.extendPick}
                @pointerup=${this.endPick}
              >
                ${SERIES.filter((s) => this.enabled.has(s.id)).map((s) => this.line(s))}
                ${this.selectionShape()}
              </svg>
            `
          : html`<p class="empty">nothing recorded in this window</p>`}
      </div>
    `;
  }
}

customElements.define('ww-timeline', Timeline);
