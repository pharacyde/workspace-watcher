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

  /* macOS hides overlay scrollbars until something scrolls, so a panel holding more than it can
     show looks exactly like one holding all of it - measured on the process panel: 288 px of rows
     in a 155 px body, scrollable the whole time, with nothing on screen saying so. Both spellings
     are here because they reach different engines: scrollbar-width and scrollbar-color are what
     Firefox reads, ::-webkit-scrollbar is what Chromium and Safari read. Neither could be verified
     headless - Chromium there keeps its overlay scrollbar and reports a zero-width gutter either
     way - so this is not yet known to be visible on macOS. */
  .body {
    scrollbar-width: thin;
    scrollbar-color: var(--line) transparent;
  }

  .body::-webkit-scrollbar {
    width: 9px;
    height: 9px;
  }
  .body::-webkit-scrollbar-thumb {
    background: var(--line);
    border-radius: 5px;
  }
  .body::-webkit-scrollbar-thumb:hover {
    background: var(--dim);
  }
  .body::-webkit-scrollbar-track {
    background: transparent;
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
