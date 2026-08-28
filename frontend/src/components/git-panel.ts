import { css, html, LitElement } from 'lit';
import { GitStatusDocument } from '../api/documents';
import { LatestController } from '../api/subscriptions';
import { panelStyles } from '../styles';

export class GitPanel extends LitElement {
  static properties = { selected: { type: String }, search: { attribute: false } };

  declare selected: string | null;
  declare search: string;

  static styles = [
    panelStyles,
    css`
      .rowline {
        cursor: pointer;
      }
      .st {
        width: 74px;
        flex: none;
        color: var(--dim);
      }
      .modified .st {
        color: var(--warn);
      }
      .added .st,
      .untracked .st {
        color: var(--add);
      }
      .deleted .st {
        color: var(--del);
      }
      .selected {
        background: #222a36;
      }
    `,
  ];

  private readonly git = new LatestController(this, GitStatusDocument);

  constructor() {
    super();
    this.selected = null;
    this.search = '';
  }

  private select(path: string) {
    // A plain DOM event rather than a shared store: one parent owns the selection, and the panels
    // stay usable in isolation.
    this.dispatchEvent(
      new CustomEvent('file-selected', { detail: path, bubbles: true, composed: true }),
    );
  }

  render() {
    const snapshot = this.git.value?.gitStatus;
    const needle = (this.search ?? '').toLowerCase();
    const files = (snapshot?.files ?? []).filter(
      (file) => needle === '' || file.path.toLowerCase().includes(needle),
    );
    return html`
      <h2>
        Working tree
        ${snapshot?.repo ? html`<span class="count">${files.length}</span>` : ''}
        <span class="note">${snapshot?.headSubject ?? ''}</span>
      </h2>
      <div class="body">
        ${!snapshot
          ? html`<p class="empty">connecting…</p>`
          : !snapshot.repo
            ? html`<p class="empty">not a git repository</p>`
            : files.length === 0
              ? html`<p class="empty">${needle ? 'no match' : 'clean'}</p>`
              : files.map(
                  (file) => html`
                    <div
                      class="rowline ${file.status} ${file.path === this.selected ? 'selected' : ''}"
                      @click=${() => this.select(file.path)}
                    >
                      <span class="st">${file.status}</span>
                      <span class="ellipsis">${file.path}</span>
                    </div>
                  `,
                )}
      </div>
    `;
  }
}

customElements.define('ww-git-panel', GitPanel);
