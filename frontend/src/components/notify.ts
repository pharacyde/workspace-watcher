import { css, html, LitElement } from 'lit';
import { subscribe } from '../api/client';
import { EventsDocument } from '../api/documents';
import type { EventsSubscription } from '../gql/graphql';

type FeedEvent = EventsSubscription['events'];

type Mode = 'off' | 'notify' | 'sound';

const MODES: Mode[] = ['off', 'notify', 'sound'];
const LABELS: Record<Mode, string> = {
  off: '🔕 quiet',
  notify: '🔔 notify',
  sound: '🔔 notify + sound',
};

const STORAGE_KEY = 'ww-notify-mode';

/** Nothing older than this is announced, so replayed history cannot set off a burst on connect. */
const FRESH_SECONDS = 30;

/** One announcement at a time; the rest are counted rather than queued into a pile-up. */
const QUIET_MS = 5000;

/**
 * Tells you when something happened while you were not looking.
 *
 * <p>This is what makes the dashboard useful when it is not on screen: you start an agent, go do
 * something else, and hear about the failure rather than discovering it twenty minutes later.
 *
 * <p>Only while the page is hidden. A notification for something you are already watching happen
 * is noise, and noise is how people end up turning the feature off.
 */
export class Notify extends LitElement {
  static properties = { mode: { state: true }, denied: { state: true } };

  declare private mode: Mode;
  declare private denied: boolean;

  private release?: () => void;
  private lastAt = 0;
  private suppressed = 0;

  static styles = css`
    button {
      background: none;
      border: 1px solid var(--line);
      border-radius: 10px;
      color: var(--dim);
      font: inherit;
      font-size: 12px;
      padding: 1px 8px;
      cursor: pointer;
    }
    button.on {
      color: var(--accent);
      border-color: var(--accent);
    }
    button.denied {
      color: var(--del);
      border-color: var(--del);
    }
  `;

  constructor() {
    super();
    this.mode = (localStorage.getItem(STORAGE_KEY) as Mode) ?? 'off';
    this.denied = false;
  }

  connectedCallback(): void {
    super.connectedCallback();
    this.release = subscribe(EventsDocument, (data) => this.consider(data.events));
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    this.release?.();
  }

  /** What is worth interrupting someone for. Everything else stays in the feed. */
  private notable(event: FeedEvent): string | null {
    if (event.type === 'TOOL_ERROR') return 'Tool failed';
    if (event.source === 'GUARD') return event.type === 'DENIED' ? 'Blocked' : 'Flagged';
    if (event.source === 'HOOK' && event.type === 'Stop') return 'Agent finished';
    return null;
  }

  private consider(event: FeedEvent) {
    if (this.mode === 'off' || !document.hidden) return;
    // The subscription replays buffered history on connect; without this the first hidden tab
    // would announce everything that already happened.
    if (Date.now() - new Date(event.ts).getTime() > FRESH_SECONDS * 1000) return;

    const title = this.notable(event);
    if (!title || Notification.permission !== 'granted') return;

    const now = Date.now();
    if (now - this.lastAt < QUIET_MS) {
      this.suppressed += 1;
      return;
    }
    this.lastAt = now;

    const extra = this.suppressed > 0 ? ` (+${this.suppressed} more)` : '';
    this.suppressed = 0;

    const notification = new Notification(`${title}${extra}`, {
      body: event.summary ?? '',
      tag: 'workspace-watcher',
    });
    notification.onclick = () => {
      window.focus();
      notification.close();
    };

    if (this.mode === 'sound') this.beep();
  }

  /** A short tone from the audio context, so there is no asset to ship or fail to load. */
  private beep() {
    try {
      const context = new AudioContext();
      const oscillator = context.createOscillator();
      const gain = context.createGain();
      oscillator.frequency.value = 660;
      gain.gain.setValueAtTime(0.05, context.currentTime);
      gain.gain.exponentialRampToValueAtTime(0.0001, context.currentTime + 0.25);
      oscillator.connect(gain).connect(context.destination);
      oscillator.start();
      oscillator.stop(context.currentTime + 0.25);
      oscillator.onended = () => void context.close();
    } catch {
      // A browser that will not make a sound is not a reason to lose the notification.
    }
  }

  private async cycle() {
    const next = MODES[(MODES.indexOf(this.mode) + 1) % MODES.length];
    if (next !== 'off' && Notification.permission !== 'granted') {
      // Permission has to be asked from a click; doing it on load is what gets a site blocked.
      const result = await Notification.requestPermission();
      if (result !== 'granted') {
        this.denied = true;
        this.mode = 'off';
        localStorage.setItem(STORAGE_KEY, 'off');
        return;
      }
    }
    this.denied = false;
    this.mode = next;
    localStorage.setItem(STORAGE_KEY, next);
  }

  render() {
    return html`
      <button
        class=${this.denied ? 'denied' : this.mode !== 'off' ? 'on' : ''}
        title="Announce failures, blocks and finished agents - but only while this tab is in the background"
        @click=${() => this.cycle()}
      >
        ${this.denied ? '🔕 blocked by browser' : LABELS[this.mode]}
      </button>
    `;
  }
}

customElements.define('ww-notify', Notify);
