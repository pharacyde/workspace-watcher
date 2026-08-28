import { css, html, LitElement } from 'lit';
import { request } from '../api/client';
import { UsageDocument } from '../api/documents';

type UsageData = {
  costUsd: number | null;
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

    return html`
      <span class="pill" @click=${() => (this.open = !this.open)}>
        ${costUsd === null
          ? html`${compact.format(tokens.total)} tokens`
          : html`<span class="cost">$${costUsd.toFixed(2)}</span> ·
              ${compact.format(tokens.total)}`}
      </span>
      ${this.open
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
                ${models.map(
                  (m) => html`
                    <div>
                      ${m.model} ·
                      ${m.costUsd === null ? 'unpriced' : `$${m.costUsd.toFixed(2)}`}
                    </div>
                  `,
                )}
              </div>
            </div>
          `
        : ''}
    `;
  }
}

customElements.define('ww-usage', UsagePill);
