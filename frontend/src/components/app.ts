import { css, html, LitElement } from 'lit';
import { onConnectionState, request, type ConnectionState } from '../api/client';
import {
  ActiveWorkspaceDocument,
  SensitiveCountDocument,
  StatusDocument,
  WatchWorkspaceDocument,
  WorkspacesDocument,
} from '../api/documents';
import { LatestController } from '../api/subscriptions';
import { titleWorkspace } from '../title';
import './diff-panel';
import './feed';
import './git-panel';
import './process-panel';
import './reload';
import './sensitive';
import './timeline';
import './notify';
import './usage';

export class App extends LitElement {
  static properties = {
    workspace: { state: true },
    hasTranscripts: { state: true },
    lost: { state: true },
    selected: { state: true },
    selectedEvent: { state: true },
    selectedProcess: { state: true },
    connection: { state: true },
    search: { state: true },
    replay: { state: true },
    sensitive: { state: true },
    sensitiveOpen: { state: true },
  };

  declare private workspace: string;
  declare private hasTranscripts: boolean;
  declare private lost: number;
  declare private selected: string | null;
  declare private selectedEvent: unknown | null;
  declare private selectedProcess: unknown | null;
  declare private connection: ConnectionState;
  declare private search: string;
  declare private replay: { since: string; until: string } | null;
  /** Secrets and personal data seen since start, as the server counts them. */
  declare private sensitive: number;
  declare private sensitiveOpen: boolean;

  private releaseConnection?: () => void;
  private recount: ReturnType<typeof setTimeout> | null = null;
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
    /* A button, so it can be reached by keyboard, dressed as the pills beside it. */
    button.pill {
      background: none;
      font: inherit;
      cursor: pointer;
    }
    .pill.sensitive {
      color: var(--del);
      border-color: var(--del);
    }
    .pill.sensitive.open {
      background: var(--del);
      color: var(--panel);
    }
    .pill.live {
      color: var(--add);
      border-color: var(--add);
    }
    input[type='search'] {
      background: var(--panel);
      color: var(--text);
      border: 1px solid var(--line);
      border-radius: 4px;
      font: inherit;
      font-size: 12px;
      padding: 1px 6px;
      width: 22ch;
    }
    input[type='search']:focus {
      outline: none;
      border-color: var(--accent);
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
    /* A third row only while it is open: with nothing sensitive seen the grid is the familiar
       four panels, and the pill is the only trace of the feature. */
    main.with-sensitive {
      grid-template-rows: 1fr 1fr minmax(120px, 28%);
    }
    ww-sensitive {
      grid-column: 1 / -1;
    }
  `;

  constructor() {
    super();
    this.workspace = 'connecting…';
    this.hasTranscripts = true;
    this.lost = 0;
    this.selected = null;
    this.selectedEvent = null;
    this.selectedProcess = null;
    this.connection = 'connecting';
    this.search = '';
    this.replay = null;
    this.sensitive = 0;
    this.sensitiveOpen = false;
  }

  connectedCallback(): void {
    super.connectedCallback();
    // One inspector, two things to inspect. The last click wins rather than the panel trying to
    // show both, which would leave you guessing which one you are looking at.
    this.addEventListener('file-selected', (event) => {
      this.selected = (event as CustomEvent<string>).detail;
      this.selectedEvent = null;
      this.selectedProcess = null;
    });
    this.addEventListener('process-selected', (event) => {
      this.selectedProcess = (event as CustomEvent<unknown>).detail;
      this.selected = null;
      this.selectedEvent = null;
    });
    this.addEventListener('event-selected', (event) => {
      this.selectedEvent = (event as CustomEvent<unknown>).detail;
      this.selectedProcess = null;
      // Symmetry with the other direction: without this the working tree kept a file highlighted
      // as being viewed while the inspector showed an unrelated event.
      this.selected = null;
    });
    this.releaseConnection = onConnectionState((state) => (this.connection = state));
    this.addEventListener('replay-range', (event) => {
      this.replay = (event as CustomEvent<{ since: string; until: string } | null>).detail;
    });
    // The live count reaches the header as a DOM event from ww-notify, which already holds an
    // `events` subscription and already classifies GUARD events; the feed's log is paused, filtered
    // and reset, and a third subscription here would send every event over the socket once more.
    // The event is only the trigger: the number is re-asked from the server, because the stream
    // replays its buffer on every reconnect and counting arrivals would count those twice.
    this.addEventListener('sensitive-seen', () => {
      this.recount ??= setTimeout(() => {
        this.recount = null;
        request(SensitiveCountDocument)
          .then(({ status }) => (this.sensitive = status.sensitive))
          .catch(() => undefined);
      }, 250);
    });
    this.addEventListener('sensitive-hide', () => (this.sensitiveOpen = false));
    // Anything naming something in the old workspace has to go: a selected file, a replay window.
    let previous: string | null = null;
    this.addController({
      hostUpdate: () => {
        const current = this.active.value?.activeWorkspace ?? null;
        // Several watchers on several projects are otherwise identical tabs. Only while there is
        // an answer: `this.workspace` holds a sentence like "connecting…" until there is one, and
        // a tab reading "connecting… · workspace-watcher" says less than the plain name.
        if (current !== null) titleWorkspace(current);
        if (current !== null && previous !== null && current !== previous) {
          this.selected = null;
          this.selectedEvent = null;
          this.selectedProcess = null;
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
        titleWorkspace(status.workspace ?? null);
        this.hasTranscripts = status.transcriptDirs.length > 0;
        this.lost = status.loss.history + status.loss.stream;
        this.sensitive = status.sensitive;
      })
      .catch(() => (this.workspace = 'backend unreachable'));
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    this.releaseConnection?.();
    if (this.recount !== null) clearTimeout(this.recount);
    this.recount = null;
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
        <input
          type="search"
          placeholder="search all panels…"
          .value=${this.search}
          title="Filters the activity feed, the working tree and the process list at once"
          @input=${(e: Event) => (this.search = (e.target as HTMLInputElement).value)}
        />
        <ww-reload></ww-reload>
        <ww-notify></ww-notify>
        <ww-usage></ww-usage>
        ${this.hasTranscripts
          ? ''
          : html`<span
              class="pill warn"
              title="File events still work, but nothing will be attributed to an agent."
              >no agent transcripts</span
            >`}
        ${this.lost > 0
          ? html`<span
              class="pill warn"
              title="Events the watcher dropped since it started rather than hold a collector up: the history and the live feed are incomplete. The feed marks where."
              >${this.lost} dropped</span
            >`
          : ''}
        ${this.sensitive > 0
          ? html`<button
              class="pill warn sensitive ${this.sensitiveOpen ? 'open' : ''}"
              title="Secrets or personal data seen in what an agent sent or received since start. Click for which rule, which tool, which host."
              @click=${() => (this.sensitiveOpen = !this.sensitiveOpen)}
            >
              ${this.sensitive} sensitive
            </button>`
          : ''}
      </header>
      <ww-timeline></ww-timeline>
      <main class=${this.sensitiveOpen ? 'with-sensitive' : ''}>
        <ww-process-panel
          .search=${this.search}
          .selectedPid=${(this.selectedProcess as { pid: string } | null)?.pid ?? null}
        ></ww-process-panel>
        <ww-feed .search=${this.search} .replay=${this.replay} .workspace=${this.active.value?.activeWorkspace ?? null}></ww-feed>
        <ww-git-panel .selected=${this.selected} .search=${this.search}></ww-git-panel>
        <ww-diff-panel
          .path=${this.selected}
          .event=${this.selectedEvent}
          .process=${this.selectedProcess}
        ></ww-diff-panel>
        ${this.sensitiveOpen
          ? html`<ww-sensitive .count=${this.sensitive}></ww-sensitive>`
          : ''}
      </main>
    `;
  }
}

customElements.define('ww-app', App);
