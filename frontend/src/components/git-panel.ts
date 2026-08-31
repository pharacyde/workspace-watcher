import { css, html, LitElement } from 'lit';
import { GitStatusDocument } from '../api/documents';
import { LatestController } from '../api/subscriptions';
import { panelStyles } from '../styles';

type File = { path: string; status: string };
type Group = {
  module: string | null;
  depth: number;
  files: File[];
  total: number;
};

export class GitPanel extends LitElement {
  static properties = {
    selected: { type: String },
    search: { attribute: false },
    collapsed: { state: true },
  };

  declare selected: string | null;
  declare search: string;
  /** Submodules the reader has folded away, by their path in the superproject. */
  declare private collapsed: Set<string>;
  /**
   * True while the list runs past the bottom of the panel.
   *
   * <p>A plain field, not reactive state: render() never reads it - it only drives the host
   * attribute the fade hangs off - and setting reactive state from updated() makes Lit schedule a
   * second update pass, with a second forced reflow, for every push from the server.
   */
  private more = false;

  static styles = [
    panelStyles,
    css`
      :host {
        position: relative;
      }
      /* macOS hides its overlay scrollbar until something scrolls, so a list that runs past the
         bottom looks exactly like one that ends there - measured here at 610px of rows in a 405px
         body, scrollable the whole time with nothing on screen saying so. A fade is the one cue
         that does not depend on how the platform chooses to draw scrollbars. */
      :host([data-more])::after {
        content: '';
        position: absolute;
        left: 0;
        right: 0;
        bottom: 0;
        height: 22px;
        pointer-events: none;
        background: linear-gradient(transparent, var(--panel));
      }
      .rowline {
        cursor: pointer;
      }
      /* A row with nothing of its own to diff. Clicking one opened two empty Monaco panes and said
         nothing about why, which is the whole reason this class exists. */
      .rowline.inert {
        cursor: default;
      }
      .rowline.nested .st,
      .rowline.symlink .st {
        color: var(--dim);
      }
      /* A submodule names a repository, not a file: clicking it asked git to diff a directory and
         produced an empty panel with no explanation. It folds its own changes away instead. */
      .rowline.submodule {
        color: var(--dim);
      }
      .rowline.submodule .st {
        color: var(--hook);
      }
      .twisty {
        width: 12px;
        flex: none;
        color: var(--dim);
      }
      /* Indented rather than boxed: the whole panel is one column of monospace paths, and a border
         per module would cost more width than the nesting it shows. The depth is set inline,
         because a class here replaces the shared 12px rather than adding to it - measured, a file
         sat 2px in from its own module header. */
      .module-count {
        color: var(--dim);
        flex: none;
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
    this.collapsed = new Set();
  }

  private select(path: string) {
    // A plain DOM event rather than a shared store: one parent owns the selection, and the panels
    // stay usable in isolation.
    this.dispatchEvent(
      new CustomEvent('file-selected', {
        detail: path,
        bubbles: true,
        composed: true,
      }),
    );
  }

  private toggle(module: string) {
    const collapsed = new Set(this.collapsed);
    if (!collapsed.delete(module)) {
      collapsed.add(module);
    }
    // A new Set rather than a mutated one: Lit compares by identity and would not re-render.
    this.collapsed = collapsed;
  }

  /**
   * Groups the flat list git gives back under the submodule each file belongs to.
   *
   * <p>The server already reports paths relative to the superproject root, in the order git listed
   * them with each submodule's own files directly under it. Grouping here rather than there keeps
   * that one shape on the wire and leaves the paths usable as identifiers everywhere else - the
   * selection, the diff query and the title all still speak full paths.
   */
  private group(files: readonly File[]): Group[] {
    // Longest first, so a file inside a nested submodule is attributed to the nearest one.
    const modules = files
      .filter((file) => file.status === 'submodule')
      .map((file) => file.path)
      .sort((left, right) => right.length - left.length);
    const ownerOf = (path: string) =>
      modules.find((module) => path.startsWith(module + '/')) ?? null;

    const groups: Group[] = [{ module: null, depth: 0, files: [], total: 0 }];
    const byModule = new Map<string, Group>();
    for (const file of files) {
      if (file.status === 'submodule') {
        const group: Group = {
          module: file.path,
          depth: modules.filter((other) => file.path.startsWith(other + '/')).length,
          files: [],
          // Everything the fold hides, not just this module's own rows: a module whose changes all
          // sit in a submodule of its own read "0" while folding three rows away.
          total: files.filter((other) => other.path.startsWith(file.path + '/')).length,
        };
        groups.push(group);
        byModule.set(file.path, group);
        continue;
      }
      const owner = ownerOf(file.path);
      (owner ? (byModule.get(owner) ?? groups[0]) : groups[0]).files.push(file);
    }
    return groups.filter((group) => group.module !== null || group.files.length > 0);
  }

  /** True when this module, or one it sits inside, is folded away. */
  private isFolded(module: string): boolean {
    for (const folded of this.collapsed) {
      if (module === folded || module.startsWith(folded + '/')) return true;
    }
    return false;
  }

  private renderFile(file: File, module: string | null, depth = 0) {
    // Shown relative to its module, because the module is named on the row above it. The full path
    // stays in the title and in everything the click carries.
    const label = module ? file.path.slice(module.length + 1) : file.path;
    // Neither has content of its own to diff. Clicking them produced two empty panes and no
    // explanation, which is the thing an inert row exists to prevent.
    const inert = file.status === 'nested' || file.status === 'symlink';
    return html`
      <div
        class="rowline ${file.status} ${
          file.path === this.selected ? 'selected' : ''
        } ${inert ? 'inert' : ''}"
        style="padding-left: calc(12px + ${module ? (depth + 1) * 14 : 0}px)"
        title=${
          file.status === 'nested'
            ? 'A repository of its own that this project does not track; watch it directly to see inside'
            : file.status === 'symlink'
              ? 'A symlink; open what it points at to see its contents'
              : file.path
        }
        @click=${() => !inert && this.select(file.path)}
      >
        <span class="st">${file.status}</span>
        <span class="ellipsis">${label}</span>
      </div>
    `;
  }

  /**
   * @param folded what is on screen, not what the fold set says. While a filter is running the two
   *     differ: the module is shown expanded so its matches are visible, and an arrow reporting
   *     the set instead would point the wrong way over its own listed files - and clicking it
   *     would silently change the fold for after the search, with nothing moving on screen.
   * @param foldable false while a filter is running, because folding is overridden then and an
   *     arrow that does nothing is worse than no arrow
   */
  private renderModule(group: Group, folded: boolean, foldable: boolean) {
    const module = group.module as string;
    return html`
      <div
        class="rowline submodule ${foldable ? '' : 'inert'}"
        style="padding-left: calc(12px + ${group.depth * 14}px)"
        title=${
          !foldable
            ? 'A submodule; folding is off while a filter is running'
            : folded
              ? 'A submodule is a repository of its own; expand to see its changes'
              : 'A submodule is a repository of its own; its changes are listed under it'
        }
        @click=${() => foldable && this.toggle(module)}
      >
        <span class="twisty">${foldable ? (folded ? '▸' : '▾') : ''}</span>
        <span class="st">submodule</span>
        <span class="ellipsis">${module}</span>
        <span class="module-count">${group.total}</span>
      </div>
    `;
  }

  render() {
    const snapshot = this.git.value?.gitStatus;
    const needle = (this.search ?? '').toLowerCase();
    const files = (snapshot?.files ?? []).filter(
      (file) => needle === '' || file.path.toLowerCase().includes(needle),
    );
    const groups = this.group(files);
    return html`
      <h2>
        Working tree ${snapshot?.repo ? html`<span class="count">${files.length}</span>` : ''}
        <span class="note">${snapshot?.headSubject ?? ''}</span>
      </h2>
      <div class="body" @scroll=${this.measure}>
        ${
          !snapshot
            ? html`<p class="empty">connecting…</p>`
            : !snapshot.repo
              ? html`<p class="empty">not a git repository</p>`
              : files.length === 0
                ? html`<p class="empty">${needle ? 'no match' : 'clean'}</p>`
                : groups.map((group) => {
                    if (group.module === null) {
                      return group.files.map((file) => this.renderFile(file, null));
                    }
                    if (this.isFolded(group.module) && !needle) {
                      // While a search is running, a fold that hides its own matches would be a
                      // filter that lies about what it found.
                      return group.depth === 0 ||
                        !this.isFolded(group.module.replace(/\/[^/]*$/, ''))
                        ? this.renderModule(group, true, true)
                        : '';
                    }
                    return html`
                      ${this.renderModule(group, false, !needle)}
                      ${group.files.map((file) => this.renderFile(file, group.module, group.depth))}
                    `;
                  })
        }
      </div>
    `;
  }

  private resize?: ResizeObserver;

  connectedCallback(): void {
    super.connectedCallback();
    // A window resize changes how much fits without either a render or a scroll, so the fade
    // stayed lit over a list that now fits until the next push from the server happened to arrive.
    this.resize = new ResizeObserver(() => this.measure());
    this.resize.observe(this);
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    this.resize?.disconnect();
    this.resize = undefined;
  }

  updated() {
    this.measure();
  }

  /** Says whether the list runs past the bottom, for the fade that is the only cue macOS gives. */
  private measure = () => {
    const body = this.renderRoot.querySelector<HTMLElement>('.body');
    if (!body) return;
    const more = body.scrollHeight - body.scrollTop - body.clientHeight > 2;
    if (more !== this.more) {
      this.more = more;
      this.toggleAttribute('data-more', more);
    }
  };
}

customElements.define('ww-git-panel', GitPanel);
