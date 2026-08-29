# Design invariants

The decisions this project exists to hold. Each one is here because giving it up looked reasonable
at some point, and the note says what it would cost. Do not quietly relax them.

1. **The observer never blocks or alters the agent**, with exactly one exception: `GuardService`,
   which is off by default, needs its own hook installed, and fails open. Anything else that could
   make a crashed dashboard hang an agent needs the same treatment: an explicit opt-in and a
   fail-open timeout.
1b. **"Off" must mean the hook cannot block**, not merely that the wording changes. `check()`
   downgrades a DENY to WARN while observing. This was a real bug found end to end after unit tests
   passed — they asserted on the event text and not on what was handed back to the hook. The hook
   script always exits 0 and swallows errors.
2. **Never invent attribution.** `FS` events carry no PID because macOS cannot supply one — FSEvents
   has no PID field, and `lsof` only shows still-open descriptors. Filling that column with a guess
   would make the whole feed untrustworthy. `WatchEvent.Source` exists so the UI can always show
   where a fact came from.
3. **Layer 1 (`TRANSCRIPT`, `HOOK`) is exact; layer 2 (`FS`, `PROCESS`) is a safety net.** When both
   could supply the same information, prefer layer 1 and say so in the code comment.
4. **Loopback by default.** This app serves source diffs and command lines with no auth. Do not
   change `server.address` or add a feature that assumes a reachable port.
5. **The process panel is a sampler and is documented as such.** Do not present it as complete.
6. **GraphQL is the whole API.** No REST controllers, including for hooks — those post the
   `recordAgentEvent` mutation. Schema lives in `src/main/resources/graphql/schema.graphqls`.

