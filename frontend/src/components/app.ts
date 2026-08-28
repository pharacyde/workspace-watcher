import { css, html, LitElement } from 'lit';
import { onConnectionState, request, type ConnectionState } from '../api/client';
import { StatusDocument } from '../api/documents';
import './diff-panel';
import './feed';
import './git-panel';
import './process-panel';

export class App extends LitElement {
  static properties = {
    workspace: { state: true },
    hasTranscripts: { state: true },
    selected: { state: true },
    connection: { state: true },
  };

  declare private workspace: string;
  declare private hasTranscripts: boolean;
  declare private selected: string | null;
  declare private connection: ConnectionState;

  private releaseConnection?: () => void;

  static styles = css`
    :host {
      height: 100vh;
      display: flex;
      flex-direction: column;
    }
    header {
      display: flex;
      align-items: baseline;
      gap: 12px;
      padding: 9px 16px;
      border-bottom: 1px solid var(--line);
      flex: none;
    }
    h1 {
      font-size: 13px;
      margin: 0;
      letter-spacing: 0.5px;
    }
    .path {
      color: var(--dim);
      font-size: 12px;
    }
    .pill {
      border: 1px solid var(--line);
      border-radius: 10px;
      padding: 1px 8px;
      font-size: 12px;
      color: var(--dim);
    }
    .pill.warn {
      color: var(--warn);
      border-color: var(--warn);
    }
    .pill.live {
      color: var(--add);
      border-color: var(--add);
    }
    .pill.reconnecting {
      color: var(--del);
      border-color: var(--del);
    }
    main {
      flex: 1;
      min-height: 0;
      display: grid;
      grid-template-columns: 1fr 1fr;
      grid-template-rows: 1fr 1fr;
      gap: 1px;
      background: var(--line);
    }
  `;

  constructor() {
    super();
    this.workspace = 'connecting…';
    this.hasTranscripts = true;
    this.selected = null;
    this.connection = 'connecting';
  }

  connectedCallback(): void {
    super.connectedCallback();
    this.addEventListener('file-selected', (event) => {
      this.selected = (event as CustomEvent<string>).detail;
    });
    this.releaseConnection = onConnectionState((state) => (this.connection = state));
    request(StatusDocument)
      .then(({ status }) => {
        this.workspace = status.workspace;
        this.hasTranscripts = status.transcriptDirs.length > 0;
      })
      .catch(() => (this.workspace = 'backend unreachable'));
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    this.releaseConnection?.();
  }

  render() {
    return html`
      <header>
        <h1>workspace-watcher</h1>
        <span class="path">${this.workspace}</span>
        <span class="pill ${this.connection}">${this.connection}</span>
        ${this.hasTranscripts
          ? ''
          : html`<span
              class="pill warn"
              title="File events still work, but nothing will be attributed to an agent."
              >no agent transcripts</span
            >`}
      </header>
      <main>
        <ww-process-panel></ww-process-panel>
        <ww-feed></ww-feed>
        <ww-git-panel .selected=${this.selected}></ww-git-panel>
        <ww-diff-panel .path=${this.selected}></ww-diff-panel>
      </main>
    `;
  }
}

customElements.define('ww-app', App);
