import { css, html, LitElement } from 'lit';
import { request } from '../api/client';
import { FileVersionsDocument } from '../api/documents';
import type { EventsSubscription } from '../gql/graphql';
import { panelStyles } from '../styles';
import { languageFor, loadMonaco, monacoStyleSheet, type DiffEditor } from './monaco';

type FeedEvent = EventsSubscription['events'];

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
    message: { state: true },
    wrap: { state: true },
  };

  declare path: string | null;
  /** When set, the panel inspects this event instead of diffing a file. */
  declare event: FeedEvent | null;
  declare private message: string | null;
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
    this.wrap = false;
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    this.disposeEditor();
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
    if (!changed.has('path') && !changed.has('event')) return;
    if (this.event) {
      // Showing an event means the diff container has left the DOM. Holding on to an editor
      // attached to a detached node is what made returning to the same file show an empty panel:
      // the path had not changed, so nothing rebuilt, and the fresh container stayed empty.
      this.disposeEditor();
      return;
    }
    if (!this.path) {
      this.message = 'select a row or a file to inspect it';
      return;
    }
    const requested = this.path;

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
      })
      .catch((error: Error) => {
        if (this.path === requested) this.message = error.message;
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

  render() {
    if (this.event) {
      return html`
        <h2>Event<span class="note">${this.event.summary}</span></h2>
        <div class="body event-body">${this.renderEvent(this.event)}</div>
      `;
    }
    return html`
      <h2>Diff<span class="note">${this.path ?? ''}</span></h2>
      <div class="body diff-body">
        ${this.message ? html`<p class="empty">${this.message}</p>` : ''}
        <div class="monaco" style=${this.message ? 'display:none' : 'display:block'}></div>
      </div>
    `;
  }
}

customElements.define('ww-diff-panel', DiffPanel);
