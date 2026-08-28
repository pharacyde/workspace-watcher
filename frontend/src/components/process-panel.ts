import { css, html, LitElement, type TemplateResult } from 'lit';
import { ProcessTreeDocument } from '../api/documents';
import { LatestController } from '../api/subscriptions';
import { panelStyles } from '../styles';

type Node = { pid: string; command: string; cwd: string; children?: readonly Node[] | null };

export class ProcessPanel extends LitElement {
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
          : snapshot.roots.flatMap((root) => this.row(root as Node, 0))}
      </div>
    `;
  }
}

customElements.define('ww-process-panel', ProcessPanel);
