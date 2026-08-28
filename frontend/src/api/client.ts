import type { TypedDocumentNode } from '@graphql-typed-document-node/core';
import { print } from 'graphql';
import { createClient, type Client } from 'graphql-ws';

const HTTP_ENDPOINT = '/graphql';
const WS_ENDPOINT = `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/graphql`;

/**
 * One socket for the whole app. graphql-ws handles reconnect and lazy connect, so panels can
 * subscribe and unsubscribe freely without each one opening its own connection.
 */
export const wsClient: Client = createClient({
  url: WS_ENDPOINT,
  retryAttempts: Infinity,
  shouldRetry: () => true,
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
      error: (error) => console.error('subscription failed', error),
      complete: () => {},
    },
  );
}
