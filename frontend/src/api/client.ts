import type { TypedDocumentNode } from '@graphql-typed-document-node/core';
import { print } from 'graphql';
import { createClient, type Client } from 'graphql-ws';

const HTTP_ENDPOINT = '/graphql';
const WS_ENDPOINT = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/graphql`;

/**
 * One socket for the whole app. graphql-ws handles reconnect and lazy connect, so panels can
 * subscribe and unsubscribe freely without each one opening its own connection.
 */
export type ConnectionState = 'connecting' | 'live' | 'reconnecting';

let connectionState: ConnectionState = 'connecting';
const connectionListeners = new Set<(state: ConnectionState) => void>();

function setConnectionState(state: ConnectionState) {
  if (state === connectionState) return;
  connectionState = state;
  for (const listener of connectionListeners) listener(state);
}

/**
 * Subscribes to socket health, and reports the current value immediately.
 *
 * <p>Worth surfacing rather than only logging: when the socket drops, the feed simply stops
 * updating, which is indistinguishable from a quiet workspace. A monitoring tool that can silently
 * stop monitoring is worse than one that is visibly down.
 */
export function onConnectionState(listener: (state: ConnectionState) => void): () => void {
  listener(connectionState);
  connectionListeners.add(listener);
  return () => connectionListeners.delete(listener);
}

export const wsClient: Client = createClient({
  url: WS_ENDPOINT,
  retryAttempts: Infinity,
  shouldRetry: () => true,
  on: {
    connected: () => setConnectionState('live'),
    closed: () => setConnectionState('reconnecting'),
    error: () => setConnectionState('reconnecting'),
  },
});

export async function request<TResult, TVariables>(
  document: TypedDocumentNode<TResult, TVariables>,
  variables?: TVariables,
): Promise<TResult> {
  const response = await fetch(HTTP_ENDPOINT, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ query: print(document), variables }),
  });
  if (!response.ok) {
    // Without this, a 502 from the dev proxy or a Spring error page reaches JSON.parse and the
    // user is shown "Unexpected token '<'" instead of what actually went wrong.
    throw new Error(`${response.status} ${response.statusText}`);
  }
  const body = await response.json();
  if (body.errors?.length) {
    throw new Error(body.errors.map((e: { message: string }) => e.message).join('; '));
  }
  return body.data as TResult;
}

export function subscribe<TResult, TVariables>(
  document: TypedDocumentNode<TResult, TVariables>,
  onNext: (data: TResult) => void,
  variables?: TVariables,
): () => void {
  return wsClient.subscribe<TResult>(
    { query: print(document), variables: variables as Record<string, unknown> },
    {
      next: ({ data }) => data && onNext(data),
      error: (error) => {
        setConnectionState('reconnecting');
        console.error('subscription failed', error);
      },
      complete: () => {},
    },
  );
}
