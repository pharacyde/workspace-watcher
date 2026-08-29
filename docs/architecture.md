# Architecture

Where the code lives, and the wiring decisions that have already cost a startup failure or a silent
hole in the feed — lessons, not a tour of the design.

## Layout

```
be.kleisli.ww
├── core     WatchEvent, EventBus, StateStream, ActiveWorkspace, WatcherProperties, Shell, Text
├── claude   TranscriptTailService (tails ~/.claude/projects/**/*.jsonl), HookSpoolService (drains
│            the spool dir), HookEvents (shared parsing), TranscriptLocator, SessionRegistry,
│            WorkspaceRegistry
├── fs       WorkspaceScanService (mtime+size snapshot poller), FileTailService, FileChangeService
├── git      GitService (shells out to git; no JGit)
├── proc     ProcessTreeService (lsof -a -d cwd + ProcessHandle)
├── guard    GuardService (the one hook that may block, off by default)
├── store    EventStore (queues and flushes to SQLite)
├── usage    Billing, Pricing, TokenUsage, AccountLimits, UsageService
└── web      WatchDataFetcher (DGS queries, subscriptions, hook mutation), ApiMapper, HttpsConfig,
             StaticCacheConfig
```

Frontend lives in `frontend/` and is built into `src/main/resources/static/`, which is generated
output and gitignored.

## GraphQL and core wiring

Lessons, not description: each of these is a startup failure or a silent hole that has happened.

- **DGS looks for its schema in `classpath:schema/**`** while Spring Boot and the codegen plugin
  use `classpath:graphql/**`. `dgs.graphql.schema-locations` points it at the one real file; without
  it the context fails with "Parent type Query not found".
- **json-path is pinned to 3.0.0.** DGS 12 wires `Jackson3JsonProvider`, which first ships there,
  while Spring Boot 4.1.1 manages 2.10.0. Without the override the context fails to start.
- **Do not mix DGS and Spring for GraphQL annotations.** Netflix's own guidance is explicit that
  some features do not work across both models. Everything here is DGS.
- **The subscription must not have a gap.** `EventBus.stream()` snapshots history and registers the
  live subscriber under the same lock `publish` holds while appending, then filters the overlap by
  sequence number. Reordering that introduces a silent hole in the feed.
- **`detail` is a JSON string, not a scalar.** Adding `graphql-java-extended-scalars` for one opaque
  field is not worth it; see invariant 2 about not implying more structure than exists.
