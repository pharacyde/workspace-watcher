import { css, html, LitElement } from 'lit';
import { onConnectionState, request, type ConnectionState } from '../api/client';
import {
  ActiveWorkspaceDocument,
  StatusDocument,
  WatchWorkspaceDocument,
  WorkspacesDocument,
} from '../api/documents';
import { LatestController } from '../api/subscriptions';
import './diff-panel';
import './feed';
import './git-panel';
import './process-panel';
import './timeline';
import './usage';

export class App extends LitElement {
  static properties = {
    workspace: { state: true },
    hasTranscripts: { state: true },
    selected: { state: true },
    connection: { state: true },
    replay: { state: true },
  };

  declare private workspace: string;
  declare private hasTranscripts: boolean;
  declare private selected: string | null;
  declare private connection: ConnectionState;
  declare private replay: { since: string; until: string } | null;

  private releaseConnection?: () => void;
  private readonly workspaces = new LatestController(this, WorkspacesDocument);
  private readonly active = new LatestController(this, ActiveWorkspaceDocument);

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
      position: relative;
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
    select {
      background: var(--panel);
      color: var(--text);
      border: 1px solid var(--line);
      border-radius: 4px;
      font: inherit;
      font-size: 12px;
      padding: 1px 4px;
      max-width: 46ch;
    }
    .pending {
      color: var(--warn);
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
    this.replay = null;
  }

  connectedCallback(): void {
    super.connectedCallback();
    this.addEventListener('file-selected', (event) => {
      this.selected = (event as CustomEvent<string>).detail;
    });
    this.releaseConnection = onConnectionState((state) => (this.connection = state));
    this.addEventListener('replay-range', (event) => {
      this.replay = (event as CustomEvent<{ since: string; until: string } | null>).detail;
    });
    // Anything naming something in the old workspace has to go: a selected file, a replay window.
    let previous: string | null = null;
    this.addController({
      hostUpdate: () => {
        const current = this.active.value?.activeWorkspace ?? null;
        if (current !== null && previous !== null && current !== previous) {
          this.selected = null;
          this.replay = null;
        }
        previous = current;
      },
    });
    request(StatusDocument)
      .then(({ status }) => {
        // Null until a hook reveals a project: the watcher no longer needs to be told what to
        // look at, it waits to be shown.
        this.workspace = status.workspace ?? 'waiting for an agent to show a workspace…';
        this.hasTranscripts = status.transcriptDirs.length > 0;
      })
      .catch(() => (this.workspace = 'backend unreachable'));
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    this.releaseConnection?.();
  }

  /**
   * Workspaces register themselves: the hook writes a spool directory the first time it fires in a
   * project, so this list fills up as you work rather than being configured anywhere.
   */
  private workspacePicker() {
    const entries = this.workspaces.value?.workspaces ?? [];
    const current = this.active.value?.activeWorkspace ?? this.workspace;
    if (entries.length < 2) {
      return html`<span class="path">${current}</span>`;
    }
    return html`
      <select
        title="Workspaces that have registered themselves through an agent hook"
        @change=${(event: Event) =>
          request(WatchWorkspaceDocument, { path: (event.target as HTMLSelectElement).value })}
      >
        ${entries.map(
          (entry) => html`
            <option value=${entry.path} ?selected=${entry.path === current}>
              ${entry.path}${entry.exists ? '' : ' (gone)'}${entry.pendingEvents
                ? ` · ${entry.pendingEvents} pending`
                : ''}
            </option>
          `,
        )}
      </select>
    `;
  }

  render() {
    return html`
      <header>
        <h1>workspace-watcher</h1>
        ${this.workspacePicker()}
        <span class="pill ${this.connection}">${this.connection}</span>
        <ww-usage></ww-usage>
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
        <ww-feed .replay=${this.replay} .workspace=${this.active.value?.activeWorkspace ?? null}></ww-feed>
        <ww-git-panel .selected=${this.selected}></ww-git-panel>
        <ww-diff-panel .path=${this.selected}></ww-diff-panel>
      </main>
      <ww-timeline></ww-timeline>
    `;
  }
}

customElements.define('ww-app', App);
