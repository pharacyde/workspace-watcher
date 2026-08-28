import { css, html, LitElement } from 'lit';
import { request } from '../api/client';
import { StatusDocument } from '../api/documents';

const CHECK_MS = 5000;
export const RELOADED_KEY = 'ww-reloaded-after-preload-error';

/**
 * Called when a lazily-loaded chunk has actually arrived.
 *
 * <p>The guard used to be cleared on the first status poll, which only proves the GraphQL endpoint
 * answers - it says nothing about whether the chunk loads. A chunk that is genuinely missing rather
 * than stale would then have reloaded the page on every click, forever.
 */
export function chunkLoadedSuccessfully() {
  sessionStorage.removeItem(RELOADED_KEY);
}

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
    window.addEventListener('vite:preloadError', this.onPreloadError);
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    if (this.timer) clearInterval(this.timer);
    document.removeEventListener('visibilitychange', this.onVisibility);
    window.removeEventListener('vite:preloadError', this.onPreloadError);
  }

  /**
   * A lazily-loaded chunk that is no longer on the server.
   *
   * <p>Asset names carry a content hash, so a rebuild replaces them. A tab holding the old page
   * only finds out when it reaches for a chunk it has not loaded yet - Monaco, on the first click
   * of a file - and fails with "Importing a module script failed". Polling cannot prevent that: the
   * click can come before the next check.
   *
   * <p>Reloading immediately is right here, because the page is already broken. Guarded against
   * looping: if a reload does not fix it, the problem is not staleness and reloading forever would
   * only hide the real error.
   */
  private onPreloadError = (event: Event) => {
    event.preventDefault();
    if (sessionStorage.getItem(RELOADED_KEY)) {
      this.stale = true;
      return;
    }
    sessionStorage.setItem(RELOADED_KEY, '1');
    location.reload();
  };

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
