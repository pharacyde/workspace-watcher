import { css, html, LitElement } from 'lit';
import { request, subscribe } from '../api/client';
import { FileChangedDocument, FileTailDocument, FileVersionsDocument } from '../api/documents';
import type { EventsSubscription } from '../gql/graphql';
import { panelStyles } from '../styles';
import { languageFor, loadMonaco, monacoStyleSheet, type DiffEditor } from './monaco';

type FeedEvent = EventsSubscription['events'];

/** How much of a file to keep in the browser. The end is the part being watched. */
const CONTENT_LIMIT = 400_000;

/**
 * Keeps the last CONTENT_LIMIT characters, cut at a line boundary where there is one.
 *
 * <p>The line boundary is a nicety, not the rule. Written as one indexOf it was a no-op on the file
 * it mattered for: a log with no newline in it - a progress bar drawn with \r, minified output, one
 * long JSON blob - returns -1, and slicing from there returns the whole string, so the cap held
 * nothing back on exactly the input it existed for.
 */
function trimToLimit(text: string): string {
  if (text.length <= CONTENT_LIMIT) return text;
  const cut = text.length - CONTENT_LIMIT;
  const boundary = text.indexOf('\n', cut);
  return boundary === -1 ? text.slice(cut) : text.slice(boundary + 1);
}

/**
 * Pretty-prints and colours a body that turns out to be JSON, and leaves everything else alone.
 *
 * <p>Much of what an agent hands back is JSON on one enormous line - a hook payload, a tool's
 * arguments - which is technically the whole answer and practically unreadable. Anything that is
 * not JSON is left exactly as it came: build output and stack traces are already formatted, and
 * "improving" them would only destroy their alignment.
 */
function renderBody(raw: string) {
  const trimmed = raw.trim();
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) {
    return raw;
  }
  let pretty: string;
  try {
    pretty = JSON.stringify(JSON.parse(trimmed), null, 2);
  } catch {
    return raw;
  }
  // Tokenised rather than parsed a second time: this only needs to colour, not to understand.
  const parts: unknown[] = [];
  const pattern = /("(?:\\.|[^"\\])*")(\s*:)?|\b(true|false|null)\b|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g;
  let last = 0;
  for (const match of pretty.matchAll(pattern)) {
    parts.push(pretty.slice(last, match.index));
    const [text, str, colon, literal, num] = match;
    if (str && colon) {
      parts.push(html`<span class="j-key">${str}</span><span>${colon}</span>`);
    } else if (str) {
      parts.push(html`<span class="j-str">${str}</span>`);
    } else if (literal) {
      parts.push(html`<span class="j-lit">${literal}</span>`);
    } else {
      parts.push(html`<span class="j-num">${num}</span>`);
    }
    last = match.index + text.length;
  }
  parts.push(pretty.slice(last));
  return parts;
}

/**
 * One panel, two things to inspect: the diff of a file, or the full record of an event.
 *
 * <p>The feed shows a headline - `Bash $ mvn test`, then `BUILD FAILURE` - and the output behind it
 * used to go nowhere, though it was already stored and streamed. A tool whose premise is seeing
 * what an agent did has to let you see what it got back.
 *
 * <p>The last click wins rather than the panel trying to show both, which would leave you guessing
 * which one you are looking at.
 */
export class DiffPanel extends LitElement {
  static properties = {
    path: { type: String },
    event: { attribute: false },
    view: { state: true },
    content: { state: true },
    contentNote: { state: true },
    following: { state: true },
    message: { state: true },
    diffNote: { state: true },
    diffLive: { state: true },
    wrap: { state: true },
  };

  declare path: string | null;
  /** When set, the panel inspects this event instead of diffing a file. */
  declare event: FeedEvent | null;
  /** For an event that names a file: its content, or the event's own record. */
  declare private view: 'content' | 'record';
  declare private content: string;
  declare private contentNote: string | null;
  declare private following: boolean;

  private fileSubscription?: () => void;
  /** The path the running subscription is for, so an unchanged selection is not resubscribed. */
  private followed: string | null = null;

  private diffSubscription?: () => void;
  /** The path the open diff is being kept in step with, for the same reason. */
  private diffWatched: string | null = null;
  private diffRefresh?: number;
  private diffPending = false;
  /** Something changed while a refresh was in flight, so one more is owed. */
  private diffDirty = false;
  declare private message: string | null;
  /** Why the diff stopped keeping up, when it did. Null while it is in step. */
  declare private diffNote: string | null;
  declare private diffLive: boolean;
  declare private wrap: boolean;

  private editor: DiffEditor | null = null;

  static styles = [
    panelStyles,
    css`
      .body {
        /* Monaco measures its own viewport, so the container must be bounded rather than grow. */
        padding: 0;
        overflow: hidden;
        content-visibility: visible;
      }
      .monaco {
        height: 100%;
        width: 100%;
      }
      .live {
        border: 1px solid var(--add);
        border-radius: 10px;
        padding: 0 7px;
        color: var(--add);
        letter-spacing: 0.4px;
      }
      /* The body is the scroll container by default, which makes a <pre> inside it grow to its
         full content height instead of scrolling - so following it had nothing to scroll and the
         toggle looked broken. Same shape as the Monaco case: constrain the child, not the page. */
      .event-body.file {
        display: flex;
        flex-direction: column;
        overflow: hidden;
      }
      .content {
        flex: 1;
        min-height: 0;
        margin: 0;
        padding: 6px 10px;
        overflow: auto;
        font: 12px/1.45 var(--mono);
        color: var(--fg);
      }
      .content.wrap {
        white-space: pre-wrap;
        word-break: break-word;
      }
      .content.nowrap {
        white-space: pre;
      }
      h2 .switch {
        margin-left: auto;
      }
      .event-body {
        overflow: auto;
        padding: 8px 12px;
      }
      .meta {
        display: flex;
        gap: 10px;
        align-items: center;
        color: var(--dim);
        margin-bottom: 6px;
        flex-wrap: wrap;
      }
      .meta .tag {
        color: var(--accent);
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
        text-transform: none;
        letter-spacing: 0;
        font-weight: 400;
      }
      button.on {
        color: var(--accent);
        border-color: var(--accent);
      }
      .summary {
        margin-bottom: 8px;
        white-space: pre-wrap;
        word-break: break-word;
      }
      pre.detail {
        margin: 0;
        color: var(--dim);
      }
      .j-key {
        color: var(--accent);
      }
      .j-str {
        color: var(--add);
      }
      .j-num {
        color: var(--warn);
      }
      .j-lit {
        color: var(--hook);
      }
      /* Off by default: build output and stack traces are column-aligned, and wrapping them
         destroys the alignment that makes them readable. On demand for long single lines. */
      pre.detail.wrap {
        white-space: pre-wrap;
        word-break: break-word;
      }
      pre.detail.nowrap {
        white-space: pre;
        overflow-x: auto;
      }
    `,
  ];

  constructor() {
    super();
    this.path = null;
    this.event = null;
    this.message = 'select a row or a file to inspect it';
    this.diffNote = null;
    this.diffLive = false;
    this.wrap = false;
    this.view = 'content';
    this.content = '';
    this.contentNote = null;
    this.following = true;
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    this.disposeEditor();
    this.stopFollowing();
    this.stopWatchingDiff();
  }

  private stopFollowing() {
    this.fileSubscription?.();
    this.fileSubscription = undefined;
    this.followed = null;
  }

  /**
   * Follows the file an event names.
   *
   * <p>One subscription covers both kinds of file, which is why there is no rule here for telling
   * them apart: a source file arrives in one chunk and nothing follows it, a log keeps arriving.
   * Guessing from the extension would be a rule that is sometimes wrong about a file the server can
   * simply answer for.
   */
  private follow(path: string) {
    if (this.followed === path) return;
    this.stopFollowing();
    this.followed = path;
    this.content = '';
    this.contentNote = null;
    // A new file starts followed: opening a log to watch it is the reason to open it, and having
    // to switch that back on for every file is the kind of state nobody wants remembered.
    this.following = true;
    this.fileSubscription = subscribe(
      FileTailDocument,
      ({ fileTail }) => {
        if (this.followed !== path) return;
        if (fileTail.binary) {
          this.content = '';
          this.contentNote = 'not a text file';
          return;
        }
        if (fileTail.gone) {
          this.content = '';
          this.contentNote = 'no such file in this workspace';
          return;
        }
        this.contentNote = fileTail.truncated ? 'showing the end of the file' : null;
        const next = fileTail.reset ? fileTail.text : this.content + fileTail.text;
        // Capped from the front: a build log outgrows any browser, and it is the end that is
        // being watched. Cutting at a line boundary keeps the first visible line whole.
        this.content = trimToLimit(next);
        void this.scrollContentToEnd();
      },
      { path },
    );
  }

  private stopWatchingDiff() {
    this.diffSubscription?.();
    this.diffSubscription = undefined;
    this.diffWatched = null;
    this.diffLive = false;
    // Cleared here rather than only on the next successful watch: a note about the file you just
    // left was rendered under the new file's heading while the new one was still loading.
    this.diffNote = null;
    if (this.diffRefresh) clearTimeout(this.diffRefresh);
    this.diffRefresh = undefined;
  }

  /**
   * Keeps an open diff in step with the file on disk.
   *
   * <p>The diff used to be a photograph: fetched once when the file was selected, and then wrong
   * for as long as the agent kept writing - you had to click away and back to see anything. The
   * tail says *when* the file changed and the server says *what* the two sides are, rather than
   * this side rebuilding the working copy out of the chunks it was handed. One source of truth for
   * the content, and the tail is only the notification.
   *
   * <p>Debounced, because a file being written to reports several times a second and every refresh
   * is a `git show` for the left-hand side. Coalescing them costs a moment of staleness and saves a
   * process per notification.
   */
  private watchDiff(path: string) {
    if (this.diffWatched === path) return;
    this.stopWatchingDiff();
    this.diffWatched = path;
    this.diffLive = true;
    this.diffNote = null;
    // The first message is the file's present state, delivered on subscribe - which is what was
    // just fetched. Refreshing on it would read every file a second time the moment it opens.
    let primed = false;
    this.diffSubscription = subscribe(
      FileChangedDocument,
      ({ fileChanged }) => {
        if (this.diffWatched !== path) return;
        // Before the primed guard: a file that is already gone says so in that very first message,
        // and swallowing it left the badge lit over a diff that would never change again.
        if (fileChanged.gone) {
          this.stopWatchingDiff();
          this.diffNote = 'the file is gone; showing the last version read';
          return;
        }
        if (!primed) {
          primed = true;
          return;
        }
        if (this.diffRefresh) return;
        this.diffRefresh = window.setTimeout(() => {
          this.diffRefresh = undefined;
          void this.refreshDiff(path);
        }, 600);
      },
      { path },
    );
  }

  /**
   * Re-reads both sides and puts them into the models that are already on screen.
   *
   * <p>The models are updated rather than replaced: a new pair scrolls the editor back to the top,
   * which for a file being appended to means losing your place every time the agent writes. The
   * scroll position is carried across the edit for the same reason.
   */
  private async refreshDiff(path: string) {
    if (this.path !== path || !this.editor) return;
    if (this.diffPending) {
      // A change that lands while the previous read is in flight used to be dropped, so the last
      // write to a file could be missing from a diff the badge still called live.
      this.diffDirty = true;
      return;
    }
    this.diffPending = true;
    try {
      const { fileVersions } = await request(FileVersionsDocument, { path });
      if (this.path !== path || !this.editor) return;
      if (fileVersions.binary || fileVersions.tooLarge) {
        // Both sides come back empty then, and blanking a diff that was right a second ago says
        // the file is empty - a different claim than "it outgrew what this can show".
        this.stopWatchingDiff();
        this.diffNote = 'the file outgrew the diff limit; showing the last version read';
        return;
      }
      const model = this.editor.getModel();
      if (!model) return;
      const modified = this.editor.getModifiedEditor();
      const scroll = modified.getScrollTop();
      if (model.original.getValue() !== fileVersions.head) {
        model.original.setValue(fileVersions.head);
      }
      if (model.modified.getValue() !== fileVersions.working) {
        model.modified.setValue(fileVersions.working);
        modified.setScrollTop(scroll);
      }
    } catch {
      // A refresh that fails leaves the last good diff standing; the next change tries again.
    } finally {
      this.diffPending = false;
      if (this.diffDirty) {
        this.diffDirty = false;
        void this.refreshDiff(path);
      }
    }
  }

  private async scrollContentToEnd() {
    if (!this.following) return;
    await this.updateComplete;
    const pre = this.renderRoot.querySelector<HTMLElement>('.content');
    if (pre) pre.scrollTop = pre.scrollHeight;
  }

  /**
   * Detaches the editor from its models before disposing them.
   *
   * <p>Order matters and Monaco says so out loud: disposing a model the editor still points at
   * raises "TextModel got disposed before DiffEditorWidget model got reset". It does not throw
   * immediately, which is why switching back and forth between a row and a file only broke after
   * a while.
   */
  private disposeEditor() {
    const model = this.editor?.getModel();
    this.editor?.setModel(null);
    model?.original.dispose();
    model?.modified.dispose();
    this.editor?.dispose();
    this.editor = null;
  }

  updated(changed: Map<string, unknown>) {
    // Anything other than "we are showing this file" stops the stream. Testing only for !event
    // left it running when the selection moved to an event with no path at all - a Bash call, say -
    // so a log kept streaming into a panel that was showing something else entirely.
    if (this.event?.path && this.view === 'content') {
      this.follow(this.event.path);
    } else {
      this.stopFollowing();
    }
    if (!changed.has('path') && !changed.has('event')) return;
    if (changed.has('event')) {
      // A new selection starts on the file, not on the record: selecting a file is a request to
      // see what is in it.
      this.view = 'content';
    }
    if (this.event) {
      this.stopWatchingDiff();
      // Showing an event means the diff container has left the DOM. Holding on to an editor
      // attached to a detached node is what made returning to the same file show an empty panel:
      // the path had not changed, so nothing rebuilt, and the fresh container stayed empty.
      this.disposeEditor();
      return;
    }
    if (!this.path) {
      this.stopWatchingDiff();
      this.message = 'select a row or a file to inspect it';
      return;
    }
    const requested = this.path;
    // A different file is a different subscription; the old one would otherwise keep refreshing a
    // diff that is no longer on screen.
    if (this.diffWatched !== requested) this.stopWatchingDiff();

    // Monaco is fetched the first time a file is opened, not on page load. It is by far the
    // heaviest thing here, and a dashboard that is mostly watched rather than clicked should not
    // pay for it up front.
    Promise.all([loadMonaco(), request(FileVersionsDocument, { path: requested })])
      .then(async ([monaco, { fileVersions }]) => {
        if (!this.isConnected || this.path !== requested) return;
        if (fileVersions.binary) return void (this.message = 'binary file');
        if (fileVersions.tooLarge) return void (this.message = 'file too large to diff');
        this.message = null;
        await this.updateComplete;

        const container = this.renderRoot.querySelector<HTMLElement>('.monaco');
        if (!container) return;

        const shadow = this.renderRoot as ShadowRoot;
        const sheet = await monacoStyleSheet();
        if (!this.isConnected || this.path !== requested) return;
        if (!shadow.adoptedStyleSheets.includes(sheet)) {
          shadow.adoptedStyleSheets = [...shadow.adoptedStyleSheets, sheet];
        }

        this.editor ??= monaco.editor.createDiffEditor(container, {
          theme: 'watcher',
          readOnly: true,
          renderSideBySide: true,
          automaticLayout: true,
          fontSize: 12,
          minimap: { enabled: false },
          scrollBeyondLastLine: false,
        });

        // Attach the new pair first and only then dispose the old one, for the same reason: a
        // model the editor still references must not be disposed underneath it.
        const language = languageFor(requested);
        const previous = this.editor.getModel();
        this.editor.setModel({
          original: monaco.editor.createModel(fileVersions.head, language),
          modified: monaco.editor.createModel(fileVersions.working, language),
        });
        previous?.original.dispose();
        previous?.modified.dispose();
        this.watchDiff(requested);
      })
      .catch((error: Error) => {
        if (this.path !== requested) return;
        // The editor is loaded on demand, so a rebuild can leave its chunk missing. Saying so is
        // more use than "Importing a module script failed", which names no cause and no cure.
        this.message = /import|module script|Failed to fetch/i.test(error.message)
          ? 'the dashboard was rebuilt; reload the page to load the editor'
          : error.message;
      });
  }

  private renderEvent(event: FeedEvent) {
    let detail: Record<string, unknown> | null = null;
    try {
      detail = event.detail ? (JSON.parse(event.detail) as Record<string, unknown>) : null;
    } catch {
      detail = null;
    }
    const raw =
      (detail?.output as string) ??
      (detail?.payload as string) ??
      (detail?.input as string) ??
      (detail ? JSON.stringify(detail) : null);

    return html`
      <div class="meta">
        <span>${new Date(event.ts).toLocaleTimeString('en-GB', { hour12: false })}</span>
        <span class="tag">${event.source} · ${event.type}</span>
        ${event.agent ? html`<span>${event.agent}</span>` : ''}
        ${event.sessionId ? html`<span>${event.sessionId.slice(0, 8)}</span>` : ''}
        <button class=${this.wrap ? 'on' : ''} @click=${() => (this.wrap = !this.wrap)}>
          ${this.wrap ? '⏎ wrap on' : '⏎ wrap off'}
        </button>
        ${event.path
          ? html`<button @click=${() => this.showDiffFor(event.path!)}>diff this file</button>`
          : ''}
      </div>
      <div class="summary">${event.summary}</div>
      ${raw
        ? html`<pre class="detail ${this.wrap ? 'wrap' : 'nowrap'}">${renderBody(raw)}</pre>`
        : html`<p class="empty">no further detail</p>`}
    `;
  }

  private showDiffFor(path: string) {
    this.dispatchEvent(
      new CustomEvent('file-selected', { detail: path, bubbles: true, composed: true }),
    );
  }

  private renderContent(path: string) {
    return html`
      <div class="meta">
        <span class="tag">file</span>
        <span>${path}</span>
        <button class=${this.following ? 'on' : ''} @click=${() => this.toggleFollowing()}>
          ${this.following ? '⤓ follow' : '⤓ follow off'}
        </button>
        <button class=${this.wrap ? 'on' : ''} @click=${() => (this.wrap = !this.wrap)}>
          ${this.wrap ? '⏎ wrap on' : '⏎ wrap off'}
        </button>
        <button @click=${() => this.showDiffFor(path)}>diff this file</button>
      </div>
      ${this.contentNote ? html`<p class="empty">${this.contentNote}</p>` : ''}
      ${this.content
        ? html`<pre class="content ${this.wrap ? 'wrap' : 'nowrap'}">${this.content}</pre>`
        : this.contentNote
          ? ''
          : html`<p class="empty">reading…</p>`}
    `;
  }

  private toggleFollowing() {
    this.following = !this.following;
    void this.scrollContentToEnd();
  }

  render() {
    if (this.event) {
      const path = this.event.path;
      return html`
        <h2>
          ${this.view === 'content' && path ? 'File' : 'Event'}
          <span class="note">${this.view === 'content' && path ? path : this.event.summary}</span>
          ${path
            ? html`<button
                class="switch"
                @click=${() => (this.view = this.view === 'content' ? 'record' : 'content')}
              >
                ${this.view === 'content' ? 'show record' : 'show file'}
              </button>`
            : ''}
        </h2>
        <div class="body event-body ${this.view === 'content' && path ? 'file' : ''}">
          ${this.view === 'content' && path ? this.renderContent(path) : this.renderEvent(this.event)}
        </div>
      `;
    }
    return html`
      <h2>
        Diff<span class="note">${this.path ?? ''}</span>
        ${this.diffLive ? html`<span class="live">live</span>` : ''}
      </h2>
      <div class="body diff-body">
        ${this.message ? html`<p class="empty">${this.message}</p>` : ''}
        ${this.diffNote && !this.message ? html`<p class="empty">${this.diffNote}</p>` : ''}
        <div class="monaco" style=${this.message ? 'display:none' : 'display:block'}></div>
      </div>
    `;
  }
}

customElements.define('ww-diff-panel', DiffPanel);
