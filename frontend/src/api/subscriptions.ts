import type { TypedDocumentNode } from '@graphql-typed-document-node/core';
import type { ReactiveController, ReactiveControllerHost } from 'lit';
import { subscribe } from './client';

/**
 * Latest value of a state subscription.
 *
 * <p>A ReactiveController rather than a mixin or a store: it hooks the host's own lifecycle, so the
 * socket is subscribed when the element connects and released when it leaves the DOM, with no
 * bookkeeping in the component.
 */
export class LatestController<TResult> implements ReactiveController {
  value: TResult | null = null;
  private unsubscribe?: () => void;

  constructor(
    private readonly host: ReactiveControllerHost,
    private readonly document: TypedDocumentNode<TResult, Record<string, never>>,
  ) {
    host.addController(this);
  }

  hostConnected(): void {
    this.unsubscribe = subscribe(this.document, (data) => {
      this.value = data;
      this.host.requestUpdate();
    });
  }

  hostDisconnected(): void {
    this.unsubscribe?.();
  }
}

/**
 * An append-only log fed by a subscription, capped and batched.
 *
 * <p>Batching is the point. A build can produce thousands of events per second, and updating per
 * event would spend the whole frame budget on layout. Arrivals accumulate in an array and trigger
 * at most one update per animation frame, which caps rendering at the display refresh rate no
 * matter how fast events arrive. The transport is never the bottleneck here - the DOM is.
 *
 * <p>Arrivals are deduplicated on the server-assigned sequence number. The client reconnects
 * forever, and every reconnect re-runs the subscription, which replays the server's whole buffer -
 * without this, one dropped socket would paste up to two thousand duplicate rows into the feed.
 */
export class EventLogController<TItem extends { seq: string }> implements ReactiveController {
  items: TItem[] = [];
  private pending: TItem[] = [];
  private frame: number | null = null;
  private unsubscribe?: () => void;
  private lastSeq = 0;

  constructor(
    private readonly host: ReactiveControllerHost,
    private readonly document: TypedDocumentNode<{ events: TItem }, Record<string, never>>,
    private readonly limit: number,
  ) {
    host.addController(this);
  }

  hostConnected(): void {
    this.unsubscribe = subscribe(this.document, (data) => {
      const seq = Number(data.events.seq);
      if (seq <= this.lastSeq) return;
      this.lastSeq = seq;
      this.pending.push(data.events);
      this.frame ??= requestAnimationFrame(() => this.flush());
    });
  }

  hostDisconnected(): void {
    this.unsubscribe?.();
    if (this.frame !== null) cancelAnimationFrame(this.frame);
    this.frame = null;
    // Dropped rather than kept: they would otherwise arrive in the feed out of order after a
    // later reconnect.
    this.pending = [];
  }

  private flush(): void {
    this.frame = null;
    if (this.pending.length === 0) return;
    this.items = this.items.concat(this.pending);
    this.pending = [];
    if (this.items.length > this.limit) {
      this.items = this.items.slice(this.items.length - this.limit);
    }
    this.host.requestUpdate();
  }
}
