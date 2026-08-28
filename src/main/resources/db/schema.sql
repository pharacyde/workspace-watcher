-- Schema for the recorded history. Applied on every start; every statement is idempotent.
--
-- Kept as SQL rather than as strings in Java: it is SQL, it reads better with SQL syntax
-- highlighting, and a schema change no longer means editing a text block inside a try block.

-- WAL so a long read cannot block the writer, and vice versa.
PRAGMA journal_mode = WAL;
-- Without incremental vacuum the file keeps space it no longer needs after pruning.
PRAGMA auto_vacuum = INCREMENTAL;
PRAGMA synchronous = NORMAL;

CREATE TABLE IF NOT EXISTS event (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  seq        TEXT    NOT NULL,
  ts         TEXT    NOT NULL,
  source     TEXT    NOT NULL,
  type       TEXT    NOT NULL,
  summary    TEXT,
  path       TEXT,
  agent      TEXT,
  session_id TEXT,
  mcp_server TEXT,
  subagent   TEXT,
  detail     TEXT,
  workspace  TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS event_workspace_ts ON event (workspace, ts);

-- The common query is "the most recent N for this workspace", which orders by id. The
-- (workspace, ts) index cannot serve that ordering, so without this one SQLite filtered by
-- workspace and then sorted the whole partition: measured at 328ms over 500k rows against 1ms.
CREATE INDEX IF NOT EXISTS event_workspace_id ON event (workspace, id);

-- Resource samples live beside the events: same file, same retention, same lifecycle.
CREATE TABLE IF NOT EXISTS metric (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  ts        TEXT    NOT NULL,
  workspace TEXT    NOT NULL,
  cpu       REAL    NOT NULL,
  rss_kb    INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS metric_workspace_ts ON metric (workspace, ts);
