import { css, html, LitElement } from 'lit';
import { request } from '../api/client';
import { FileVersionsDocument } from '../api/documents';
import { panelStyles } from '../styles';
import { languageFor, loadMonaco, monacoStyleSheet, type DiffEditor } from './monaco';

export class DiffPanel extends LitElement {
  static properties = { path: { type: String }, message: { state: true } };

  declare path: string | null;
  declare private message: string | null;

  private editor: DiffEditor | null = null;

  constructor() {
    super();
    this.path = null;
    this.message = 'select a file to see the diff';
  }

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
    `,
  ];

  disconnectedCallback(): void {
    super.disconnectedCallback();
    this.disposeModels();
    this.editor?.dispose();
    this.editor = null;
  }

  private disposeModels() {
    // setModel does not dispose the previous models; leaking them leaks whole documents.
    const model = this.editor?.getModel();
    model?.original.dispose();
    model?.modified.dispose();
  }

  updated(changed: Map<string, unknown>) {
    if (!changed.has('path')) return;
    if (!this.path) {
      this.message = 'select a file to see the diff';
      return;
    }
    const requested = this.path;

    // Monaco is fetched the first time a file is opened, not on page load. It is by far the
    // heaviest thing here, and a dashboard that is mostly watched rather than clicked should not
    // pay for it up front.
    Promise.all([loadMonaco(), request(FileVersionsDocument, { path: requested })])
      .then(async ([monaco, { fileVersions }]) => {
        if (this.path !== requested) return;
        if (fileVersions.binary) return void (this.message = 'binary file');
        if (fileVersions.tooLarge) return void (this.message = 'file too large to diff');
        this.message = null;

        const container = this.renderRoot.querySelector<HTMLElement>('.monaco');
        if (!container) return;

        const shadow = this.renderRoot as ShadowRoot;
        const sheet = await monacoStyleSheet();
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

        const language = languageFor(requested);
        this.disposeModels();
        this.editor.setModel({
          original: monaco.editor.createModel(fileVersions.head, language),
          modified: monaco.editor.createModel(fileVersions.working, language),
        });
      })
      .catch((error: Error) => {
        if (this.path === requested) this.message = error.message;
      });
  }

  render() {
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
