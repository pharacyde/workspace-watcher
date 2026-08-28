import { css, html, LitElement } from 'lit';
import { request } from '../api/client';
import { StatusDocument } from '../api/documents';

const CHECK_MS = 5000;

/**
 * Notices when the frontend has been rebuilt.
 *
 * <p>A tab left open keeps running the bundle it started with. Caching headers make a plain refresh
 * enough, but only once someone thinks to refresh - and while you are waiting to see a change land,
 * "why is my change not there" is the wrong question to be stuck on.
 *
 * <p>Two behaviours, because there are two situations. Watching the tab: a button appears, because
 * reloading a page someone is reading throws away their scroll position, their selection and their
 * filters mid-thought. Away from it: it reloads itself, so coming back shows the current build
 * without anyone having to notice anything.
 */
export class Reload extends LitElement {
  static properties = { stale: { state: true } };

  declare private stale: boolean;

  private known: string | null = null;
  private timer?: number;

  static styles = css`
    button {
      background: none;
      border: 1px solid var(--add);
      border-radius: 10px;
      color: var(--add);
      font: inherit;
      font-size: 12px;
      padding: 1px 8px;
      cursor: pointer;
    }
  `;

  constructor() {
    super();
    this.stale = false;
  }

  connectedCallback(): void {
    super.connectedCallback();
    this.check();
    this.timer = window.setInterval(() => this.check(), CHECK_MS);
    document.addEventListener('visibilitychange', this.onVisibility);
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    if (this.timer) clearInterval(this.timer);
    document.removeEventListener('visibilitychange', this.onVisibility);
  }

  private onVisibility = () => {
    if (this.stale && document.hidden) location.reload();
  };

  private async check() {
    try {
      const { status } = await request(StatusDocument);
      if (this.known === null) {
        this.known = status.buildId;
        return;
      }
      if (status.buildId === this.known || this.stale) return;

      this.stale = true;
      if (document.hidden) location.reload();
    } catch {
      // A watcher being restarted is the likeliest reason a check fails, and it is about to answer
      // again. Nothing to do.
    }
  }

  render() {
    return this.stale
      ? html`<button title="The dashboard was rebuilt while this tab was open" @click=${() => location.reload()}>
          ↻ new build
        </button>`
      : html``;
  }
}

customElements.define('ww-reload', Reload);
