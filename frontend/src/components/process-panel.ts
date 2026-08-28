import { css, html, LitElement, type TemplateResult } from 'lit';
import { ProcessTreeDocument } from '../api/documents';
import { LatestController } from '../api/subscriptions';
import { panelStyles } from '../styles';

type Node = { pid: string; command: string; cwd: string; children?: readonly Node[] | null };

export class ProcessPanel extends LitElement {
  static properties = { search: { attribute: false } };

  declare search: string;

  static styles = [
    panelStyles,
    css`
      .pid {
        color: var(--dim);
        flex: none;
        white-space: pre;
      }
    `,
  ];

  private readonly tree = new LatestController(this, ProcessTreeDocument);

  constructor() {
    super();
    this.search = '';
  }

  /** A match anywhere in a subtree keeps that subtree, so a hit is never orphaned from its parent. */
  private subtreeMatches(node: Node, needle: string): boolean {
    if (needle === '') return true;
    if (node.command.toLowerCase().includes(needle) || node.pid.includes(needle)) return true;
    return (node.children ?? []).some((child) => this.subtreeMatches(child, needle));
  }

  private row(node: Node, depth: number): TemplateResult[] {
    const indent = '  '.repeat(depth) + (depth > 0 ? '└─ ' : '');
    return [
      html`<div class="rowline">
        <span class="pid">${indent}${node.pid}</span>
        <span class="ellipsis">${node.command}</span>
      </div>`,
      ...(node.children ?? []).flatMap((child) => this.row(child, depth + 1)),
    ];
  }

  render() {
    const snapshot = this.tree.value?.processTree;
    return html`
      <h2>
        Processes
        ${snapshot ? html`<span class="count">${snapshot.total}</span>` : ''}
        <span
          class="note"
          title="Sampled every 2s via lsof. Short-lived commands are missed by design; the activity feed is the complete record."
          >sampled</span
        >
      </h2>
      <div class="body">
        ${!snapshot || snapshot.roots.length === 0
          ? html`<p class="empty">no processes with a working directory inside this workspace</p>`
          : snapshot.roots
              .filter((root) => this.subtreeMatches(root as Node, (this.search ?? '').toLowerCase()))
              .flatMap((root) => this.row(root as Node, 0))}
      </div>
    `;
  }
}

customElements.define('ww-process-panel', ProcessPanel);
