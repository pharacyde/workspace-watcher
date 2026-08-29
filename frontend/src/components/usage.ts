import { css, html, LitElement } from 'lit';
import { request } from '../api/client';
import { UsageDocument } from '../api/documents';

type LimitWindow = {
  kind: string;
  group: string;
  percent: number;
  severity: string;
  resetsAt: string | null;
  scope: string | null;
  active: boolean;
  expired: boolean;
};

type UsageData = {
  costUsd: number | null;
  unpricedModels: string[];
  billedPerToken: boolean;
  billingMode: string;
  plan: string | null;
  limits: { fetchedAt: string | null; windows: LimitWindow[] } | null;
  last5h: { total: number };
  last7d: { total: number };
  tokens: {
    input: number;
    output: number;
    cacheWrite5m: number;
    cacheWrite1h: number;
    cacheRead: number;
    total: number;
  };
  models: { model: string; costUsd: number | null; tokens: { total: number } }[];
};

const compact = new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 });
const full = new Intl.NumberFormat('en');

// What is painted as trouble is listed, rather than everything that is not the word "normal": a
// severity this does not know - a new one, or a missing field the server reports as empty - would
// otherwise turn every bar amber and tell someone at 7% that their limit is spent.
const ALARMING = new Set(['warning', 'warn', 'locked', 'critical']);

/**
 * What the agents in this workspace have spent.
 *
 * <p>Reported per token kind rather than as one total, because they are not priced alike: measured
 * on this project, cache reads were 71% of the bill. A tracker that counted only input and output
 * would have been wrong by fivefold, and confidently so.
 */
export class UsagePill extends LitElement {
  static properties = { usage: { state: true }, open: { state: true } };

  declare private usage: UsageData | null;
  declare private open: boolean;

  private timer?: number;

  static styles = css`
    :host {
      position: relative;
    }
    .pill {
      border: 1px solid var(--line);
      border-radius: 10px;
      padding: 1px 8px;
      font-size: 12px;
      color: var(--dim);
      cursor: pointer;
      user-select: none;
    }
    .pill:hover {
      color: var(--text);
    }
    .cost {
      color: var(--warn);
    }
    .detail {
      position: absolute;
      top: 22px;
      right: 0;
      z-index: 10;
      background: var(--panel);
      border: 1px solid var(--line);
      border-radius: 6px;
      padding: 8px 10px;
      font-size: 12px;
      white-space: nowrap;
      box-shadow: 0 6px 20px #0008;
    }
    table {
      border-collapse: collapse;
    }
    td {
      padding: 1px 0;
    }
    td.n {
      text-align: right;
      padding-left: 14px;
      font-variant-numeric: tabular-nums;
    }
    td.k {
      color: var(--dim);
    }
    tr.sum td {
      border-top: 1px solid var(--line);
      padding-top: 3px;
    }
    .note {
      color: var(--warn);
      margin-bottom: 4px;
      max-width: 40ch;
      white-space: normal;
    }
    .limits {
      margin-top: 6px;
      padding-top: 5px;
      border-top: 1px solid var(--line);
    }
    .limit {
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 1px 0;
    }
    .limit .name {
      color: var(--dim);
      min-width: 11ch;
    }
    .bar {
      width: 60px;
      height: 5px;
      border-radius: 3px;
      background: var(--line);
      overflow: hidden;
    }
    .bar i {
      display: block;
      height: 100%;
      background: var(--accent, #6aa9ff);
    }
    .bar.warn i {
      background: var(--warn);
    }
    .pct {
      font-variant-numeric: tabular-nums;
      min-width: 4ch;
      text-align: right;
    }
    .stale {
      color: var(--dim);
      font-style: italic;
    }
    /* td.k covers the table above; the limit rows are not a table and were reading at full
       foreground colour, which made the reset moment shout louder than the percentage. */
    span.k {
      color: var(--dim);
    }
    .fetched {
      color: var(--dim);
      margin-top: 3px;
      white-space: normal;
      max-width: 40ch;
    }
    .limit-pill {
      color: var(--dim);
    }
    .models {
      margin-top: 6px;
      padding-top: 5px;
      border-top: 1px solid var(--line);
      color: var(--dim);
    }
  `;

  constructor() {
    super();
    this.usage = null;
    this.open = false;
  }

  connectedCallback(): void {
    super.connectedCallback();
    this.refresh();
    this.timer = window.setInterval(() => this.refresh(), 15_000);
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    if (this.timer) clearInterval(this.timer);
  }

  private async refresh() {
    try {
      const { usage } = await request(UsageDocument, { sessionId: null });
      this.usage = usage as UsageData;
    } catch {
      this.usage = null;
    }
  }

  /**
   * The window worth putting in the header: the fullest one that still describes now.
   *
   * <p>Expired windows are left out here rather than shown at 0 - the cache says what it said, and
   * how much of the new window is gone is not in it.
   */
  private headline(): LimitWindow | null {
    const live = (this.usage?.limits?.windows ?? []).filter((w) => !w.expired);
    return live.length ? live.reduce((a, b) => (b.percent > a.percent ? b : a)) : null;
  }

  private static label(window: LimitWindow) {
    const name =
      window.kind === 'session'
        ? '5h'
        : window.kind === 'weekly_all'
          ? '7d'
          : window.kind.replace(/_/g, ' ');
    return window.scope ? `${name} · ${window.scope}` : name;
  }

  /**
   * The plan as a person would say it, from the identifier the config stores.
   *
   * <p>"default_claude_max_20x" is what Claude Code writes down; it is not what anyone calls their
   * subscription. Mechanical rather than a lookup table, so a tier that does not exist yet still
   * reads as something, and the raw value survives if the shape ever changes.
   */
  private static planName(plan: string) {
    const words = plan
      .replace(/^default_/, '')
      .replace(/^claude_/, '')
      .split('_')
      .filter(Boolean)
      .map((w) => (/^\d+x$/.test(w) ? w.replace('x', '×') : w[0].toUpperCase() + w.slice(1)));
    return words.length ? `Claude ${words.join(' ')}` : plan;
  }

  private static ago(iso: string | null) {
    if (!iso) return 'at a moment it does not record';
    const minutes = Math.round((Date.now() - Date.parse(iso)) / 60_000);
    if (!Number.isFinite(minutes)) return 'at a moment it does not record';
    if (minutes < 60) return `${minutes}m ago`;
    if (minutes < 2880) return `${Math.round(minutes / 60)}h ago`;
    return `${Math.round(minutes / 1440)}d ago`;
  }

  private limitRow(window: LimitWindow) {
    return html`
      <div class="limit">
        <span class="name">${UsagePill.label(window)}</span>
        <span class="bar ${ALARMING.has(window.severity) ? 'warn' : ''}"
          ><i style="width: ${Math.min(100, window.percent)}%"></i
        ></span>
        <span class="pct">${Math.round(window.percent)}%</span>
        ${
          window.expired
            ? html`<span class="stale">window has since reset</span>`
            : window.resetsAt
              ? html`<span class="k">resets ${UsagePill.until(window.resetsAt)}</span>`
              : ''
        }
      </div>
    `;
  }

  private static until(iso: string) {
    const minutes = Math.round((Date.parse(iso) - Date.now()) / 60_000);
    if (!Number.isFinite(minutes)) return '';
    if (minutes < 60) return `in ${minutes}m`;
    if (minutes < 2880) return `in ${Math.round(minutes / 60)}h`;
    return `in ${Math.round(minutes / 1440)}d`;
  }

  private row(label: string, tokens: number) {
    return html`
      <tr>
        <td class="k">${label}</td>
        <td class="n">${full.format(tokens)}</td>
      </tr>
    `;
  }

  render() {
    if (!this.usage) return html``;
    const { tokens, costUsd, models } = this.usage;

    // A dollar amount on a subscription is not a bill - nobody pays per token there - so it is
    // prefixed with "≈" and spelled out in the detail. Showing it bare would be a confident lie.
    const approx = !this.usage.billedPerToken;
    const headline = this.headline();
    return html`
      <span class="pill" @click=${() => (this.open = !this.open)}>
        ${
          costUsd === null
            ? html`${compact.format(tokens.total)} tokens`
            : html`<span class="cost">${approx ? '≈' : ''}$${costUsd.toFixed(2)}</span> ·
                ${compact.format(tokens.total)}`
        }${
          headline
            ? html` ·
                <span class="limit-pill"
                  >${Math.round(headline.percent)}% ${UsagePill.label(headline)}</span
                >`
            : ''
        }
      </span>
      ${
        this.open
          ? html`
              <div class="detail">
                <table>
                  ${this.row('input', tokens.input)} ${this.row('output', tokens.output)}
                  ${this.row('cache write 5m', tokens.cacheWrite5m)}
                  ${this.row('cache write 1h', tokens.cacheWrite1h)}
                  ${this.row('cache read', tokens.cacheRead)}
                  <tr class="sum">
                    <td>total</td>
                    <td class="n">${full.format(tokens.total)}</td>
                  </tr>
                </table>
                <div class="models">
                  <div class="note">
                    ${
                      approx
                        ? html`${
                            this.usage.plan
                              ? html`On ${UsagePill.planName(this.usage.plan)} you do not pay per
                                token.`
                              : html`On a subscription you do not pay per token.`
                          }
                          This is what this much work would have cost at API rates: a measure of
                          weight, not a bill. What you are spending is the allowance below.`
                        : html`Billed per token at API rates.`
                    }
                  </div>
                  ${
                    this.usage.unpricedModels.length
                      ? html`<div class="note">
                          ${this.usage.unpricedModels.join(', ')}: tokens counted, cost not. No
                          rate is known for it — a model run locally has none to know — so whatever
                          it cost is not in the figure above.
                        </div>`
                      : ''
                  }
                  <div>last 5h · ${compact.format(this.usage.last5h.total)} tokens</div>
                  <div>last 7d · ${compact.format(this.usage.last7d.total)} tokens</div>
                </div>
                ${
                  this.usage.limits
                    ? html`
                        <div class="limits">
                          ${this.usage.limits.windows.map((w) => this.limitRow(w))}
                          <div class="fetched">
                            account limits, as Claude Code last fetched them ·
                            ${UsagePill.ago(this.usage.limits.fetchedAt)}. not measured here and not
                            live.
                          </div>
                        </div>
                      `
                    : ''
                }
                <div class="models">
                  ${models
                    // A model that carried no tokens has nothing to say here: <synthetic> is
                    // Claude Code's own placeholder and appeared as a row reading "unpriced",
                    // which is a fact about a model nobody used.
                    .filter((m) => m.tokens.total > 0)
                    .map(
                    (m) => html`
                      <div>
                        ${m.model} · ${m.costUsd === null ? 'unpriced' : `$${m.costUsd.toFixed(2)}`}
                      </div>
                    `,
                  )}
                </div>
              </div>
            `
          : ''
      }
    `;
  }
}

customElements.define('ww-usage', UsagePill);
