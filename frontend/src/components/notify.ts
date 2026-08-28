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

const BASE_TITLE = 'workspace-watcher';

/**
 * A short two-note tone as a WAV data URI.
 *
 * <p>Built here rather than shipped as a file: it is forty lines of arithmetic against an asset
 * that has to be served, cached and kept in step with the bundle.
 */
const TONE = (() => {
  const rate = 44100;
  const seconds = 0.22;
  const samples = Math.floor(rate * seconds);
  const bytes = new Uint8Array(44 + samples * 2);
  const view = new DataView(bytes.buffer);
  const ascii = (offset: string, at: number) => {
    for (let i = 0; i < offset.length; i++) view.setUint8(at + i, offset.charCodeAt(i));
  };

  ascii('RIFF', 0);
  view.setUint32(4, 36 + samples * 2, true);
  ascii('WAVE', 8);
  ascii('fmt ', 12);
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, rate, true);
  view.setUint32(28, rate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  ascii('data', 36);
  view.setUint32(40, samples * 2, true);

  for (let i = 0; i < samples; i++) {
    const t = i / rate;
    // Two notes, and a short fade so it ends rather than clicks.
    const frequency = t < seconds / 2 ? 660 : 880;
    const fade = Math.min(1, (seconds - t) * 12);
    const value = Math.sin(2 * Math.PI * frequency * t) * 0.4 * fade;
    view.setInt16(44 + i * 2, value * 0x7fff, true);
  }

  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return `data:audio/wav;base64,${btoa(binary)}`;
})();

/**
 * Draws the tab icon, with a dot when something is waiting.
 *
 * <p>Drawn rather than shipped, so there is no asset to load or get wrong, and so the badge is a
 * property of the same state the title carries. In Safari a background tab is mostly its icon - the
 * title is truncated to a few characters - which makes this the more visible half of the fallback,
 * not the decoration on top of it.
 */
function paintIcon(badge: boolean) {
  const size = 32;
  const canvas = document.createElement('canvas');
  canvas.width = size;
  canvas.height = size;
  const context = canvas.getContext('2d');
  if (!context) return;

  context.fillStyle = '#161a21';
  context.fillRect(0, 0, size, size);

  // An eye: the whole point of the thing is watching.
  context.strokeStyle = '#7aa2f7';
  context.lineWidth = 2.5;
  context.beginPath();
  context.ellipse(16, 16, 12, 7.5, 0, 0, Math.PI * 2);
  context.stroke();
  context.fillStyle = '#7aa2f7';
  context.beginPath();
  context.arc(16, 16, 4, 0, Math.PI * 2);
  context.fill();

  if (badge) {
    context.fillStyle = '#e06c75';
    context.beginPath();
    context.arc(25, 7, 6, 0, Math.PI * 2);
    context.fill();
  }

  let link = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
  if (!link) {
    link = document.createElement('link');
    link.rel = 'icon';
    document.head.appendChild(link);
  }
  link.href = canvas.toDataURL('image/png');
}

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
  /** Counted while the tab is in the background, shown in the title, cleared on return. */
  private missed = 0;
  /** Created and unlocked during a click, kept for the life of the page. */
  private audio: HTMLAudioElement | null = null;

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
      color: var(--warn);
      border-color: var(--warn);
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
    document.addEventListener('visibilitychange', this.onVisibility);
    // Sound survives a reload as a preference, but an audio context cannot: it may only be opened
    // during a gesture. Without this the setting would say "sound" and stay silent after every
    // reload, which is the worst kind of broken - the one that looks configured correctly.
    if (this.mode === 'sound') {
      document.addEventListener('click', this.openAudio, { once: true });
      document.addEventListener('keydown', this.openAudio, { once: true });
    }
    // There is no favicon file; the tab shows a blank page icon until this runs.
    paintIcon(false);
  }

  disconnectedCallback(): void {
    super.disconnectedCallback();
    this.release?.();
    document.removeEventListener('visibilitychange', this.onVisibility);
  }

  private openAudio = () => {
    if (this.audio) return;
    const element = new Audio(TONE);
    element.volume = 0.35;
    // Played once during the gesture to unlock it. Safari allows later plays from a background tab
    // only if the element has already played while the page was interactive.
    element.muted = true;
    void element
      .play()
      .then(() => {
        element.pause();
        element.muted = false;
        element.currentTime = 0;
      })
      .catch(() => undefined);
    this.audio = element;
  };

  private onVisibility = () => {
    if (document.hidden) return;
    this.missed = 0;
    document.title = BASE_TITLE;
    paintIcon(false);
  };

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
    if (!title) return;

    // The title badge works everywhere and needs no permission, so it happens regardless. Safari
    // in particular will not grant notifications on a plain http origin, and a feature that only
    // works in some browsers is worse than one that always does something.
    this.missed += 1;
    document.title = `(${this.missed}) ${BASE_TITLE}`;
    paintIcon(true);

    const now = Date.now();
    if (now - this.lastAt < QUIET_MS) {
      this.suppressed += 1;
      return;
    }
    this.lastAt = now;

    const extra = this.suppressed > 0 ? ` (+${this.suppressed} more)` : '';
    this.suppressed = 0;

    if (typeof Notification !== 'undefined' && Notification.permission === 'granted') {
      const notification = new Notification(`${title}${extra}`, {
        body: event.summary ?? '',
        tag: 'workspace-watcher',
      });
      notification.onclick = () => {
        window.focus();
        notification.close();
      };
    }

    if (this.mode === 'sound') this.beep();
  }

  /**
   * A short tone, played through an audio element.
   *
   * <p>Not an AudioContext, which is where this started: Safari suspends those in background tabs,
   * and a background tab is exactly the situation this feature exists for. The counter in the title
   * kept rising while nothing was audible - the events were arriving fine, the audio was asleep.
   *
   * <p>Still synthesised rather than shipped: the WAV is built as a data URI, so there is no asset
   * to load or get wrong.
   */
  private beep() {
    const element = this.audio;
    if (!element) return;
    element.currentTime = 0;
    void element.play().catch(() => {
      // A browser that will not make a sound is not a reason to lose the notification.
    });
  }

  private async cycle() {
    const next = MODES[(MODES.indexOf(this.mode) + 1) % MODES.length];

    // Opened here, inside the click, because this is the gesture Safari requires.
    if (next === 'sound') this.openAudio();
    if (next !== 'off' && typeof Notification !== 'undefined') {
      if (Notification.permission === 'default') {
        // Permission has to be asked from a click; asking on load is what gets a site blocked.
        await Notification.requestPermission().catch(() => 'denied');
      }
      // Refused is not a failure any more. Safari will not grant notifications on a plain http
      // origin, so the mode stays on and the title badge carries it instead.
      this.denied = Notification.permission !== 'granted';
    }
    this.mode = next;
    localStorage.setItem(STORAGE_KEY, next);
  }

  render() {
    return html`
      <button
        class=${this.denied ? 'denied' : this.mode !== 'off' ? 'on' : ''}
        title=${this.denied
          ? 'Your browser refused notifications, so the tab title carries the count instead. Safari will not grant them on a plain http origin.'
          : 'Announce failures, blocks and finished agents - but only while this tab is in the background'}
        @click=${() => this.cycle()}
      >
        ${this.mode === 'off'
          ? LABELS.off
          : this.denied
            ? `📛 title only${this.mode === 'sound' ? ' + sound' : ''}`
            : LABELS[this.mode]}
      </button>
    `;
  }
}

customElements.define('ww-notify', Notify);
