-- Columns added to `event` after the table already existed on disk, where CREATE TABLE alone
-- never reaches it. SQLite has no ADD COLUMN IF NOT EXISTS, so this script is run separately
-- from schema.sql with errors tolerated: on a database that already has the column the statement
-- fails and the next one is tried. Only migrations go here, so a genuine mistake in schema.sql
-- still stops the application rather than being silently swallowed.
ALTER TABLE event ADD COLUMN mcp_server TEXT;
ALTER TABLE event ADD COLUMN subagent TEXT;
