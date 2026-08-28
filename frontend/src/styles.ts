import { css } from 'lit';

/**
 * Shared look for every panel.
 *
 * <p>Components render into shadow DOM, so styles do not leak and cannot be leaked into. The cost
 * is that a global stylesheet does not reach inside, hence this shared block. Colour carries
 * meaning here - which layer an event came from, added or deleted in a diff - so the palette is
 * defined once as custom properties on :root and inherited through the shadow boundary.
 */
export const panelStyles = css`
  :host {
    background: var(--panel);
    display: flex;
    flex-direction: column;
    min-height: 0;
    min-width: 0;
    overflow: hidden;
  }

  h2 {
    margin: 0;
    padding: 7px 12px;
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.8px;
    color: var(--dim);
    border-bottom: 1px solid var(--line);
    display: flex;
    align-items: center;
    gap: 10px;
    font-weight: 600;
    flex: none;
  }

  .count {
    background: var(--line);
    border-radius: 9px;
    padding: 0 7px;
    color: var(--text);
    font-weight: 400;
  }

  .note {
    text-transform: none;
    letter-spacing: 0;
    color: var(--dim);
    font-weight: 400;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .body {
    flex: 1;
    overflow: auto;
    padding: 4px 0;
    /* Lets the browser skip layout for anything scrolled out of view. */
    content-visibility: auto;
  }

  .empty {
    color: var(--dim);
    padding: 8px 12px;
    margin: 0;
  }

  .rowline {
    padding: 1px 12px;
    display: flex;
    gap: 8px;
    align-items: baseline;
  }

  .rowline:hover {
    background: #1c212a;
  }

  .ellipsis {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
`;
