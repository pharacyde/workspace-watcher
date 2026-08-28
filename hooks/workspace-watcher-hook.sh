#!/usr/bin/env bash
# Forwards a Claude Code hook payload to a running workspace-watcher.
#
# Claude Code passes the hook payload as JSON on stdin. It is base64-encoded and sent as the
# recordAgentEvent GraphQL mutation: base64 sidesteps every shell and JSON quoting problem without
# needing jq or python on the host.
#
# A hook blocks the agent until it returns, so this script's one hard requirement is that it is
# never slow and never fails. It always exits 0 and discards all output.
#
# Install by adding to .claude/settings.json (project) or ~/.claude/settings.json (global):
#
#   {
#     "hooks": {
#       "PostToolUse": [
#         { "matcher": "*", "hooks": [
#             { "type": "command", "command": "/absolute/path/to/workspace-watcher-hook.sh" } ] }
#       ]
#     }
#   }

URL="${WORKSPACE_WATCHER_URL:-http://127.0.0.1:8080/graphql}"
BREAKER="${TMPDIR:-/tmp}/.workspace-watcher-breaker"

# --- Circuit breaker -----------------------------------------------------------------------
# A watcher that is simply not running costs nothing: the connection is refused in about a
# millisecond. The expensive case is a watcher that accepts the connection and then stalls — a
# garbage-collecting JVM, a wedged thread, an unreachable host in WORKSPACE_WATCHER_URL. Without
# this, that charges the connect timeout to *every single tool call* for as long as it lasts.
#
# So after one failure, stay quiet for a minute. Losing a minute of hook events is nothing: the
# transcript tail is the authoritative record and catches up on its own. Slowing the agent down is
# the only outcome that actually matters.
if [ -f "$BREAKER" ] && [ -n "$(find "$BREAKER" -mmin -1 2>/dev/null)" ]; then
  exit 0
fi

# --- Send ----------------------------------------------------------------------------------
# The request body is streamed into curl on stdin rather than passed as an argument. Hook payloads
# include tool_response, which for a large file read or a verbose build easily exceeds ARG_MAX
# (1 MB on macOS) — as an argument that fails with "argument list too long" and the event is lost
# silently, which is the worst possible failure for an observability tool.
#
# awk, not a shell variable, for the same reason: nothing ever holds the whole payload in memory.
#
# max-time 2 is roughly twenty times what a 1.5 MB payload actually needs on loopback (measured at
# 90 ms), so it leaves ample room for a tunnelled remote watcher while capping what a stalled one
# can cost before the breaker takes over.
if base64 \
  | tr -d '\n' \
  | awk '{ printf "{\"query\":\"mutation($p:String!){recordAgentEvent(payloadBase64:$p)}\",\"variables\":{\"p\":\"%s\"}}", $0 }' \
  | curl --silent --show-error \
         --connect-timeout 0.3 \
         --max-time 2 \
         --header 'Content-Type: application/json' \
         --data-binary @- \
         "$URL" >/dev/null 2>&1
then
  rm -f "$BREAKER" 2>/dev/null
else
  touch "$BREAKER" 2>/dev/null
fi

exit 0
