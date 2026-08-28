#!/usr/bin/env bash
# Forwards a Claude Code hook payload to workspace-watcher.
#
# Claude Code passes the hook payload as JSON on stdin and blocks the agent until this script
# returns. That single fact drives every choice below: it must be fast, it must never fail, and it
# must never hold the agent up because something on the watcher side is unhealthy.
#
# By default the payload is written to a spool directory and the watcher picks it up. Measured on
# loopback: a spool write costs ~5 ms, an HTTP POST 20 ms, and a graphql-ws handshake 50 ms plus a
# hard dependency on node. A WebSocket is the wrong shape here - a hook is a fresh process per tool
# call, so there is nothing for a persistent connection to amortise and the handshake is paid every
# single time.
#
# Speed is not the decisive argument though. A spooled event survives the watcher being down: it
# waits on disk and is picked up whenever the watcher next starts. Sent over the network that same
# event is simply lost. For a tool whose whole purpose is not missing things, that settles it.
#
# Set WORKSPACE_WATCHER_URL to post the GraphQL mutation instead. That is for the case the spool
# cannot cover: a watcher running on a different host than the agent.
#
# The spool path is unaffected by the watcher serving HTTPS - it writes a file, not a request.
# Only WORKSPACE_WATCHER_URL needs the https:// scheme, and a self-signed certificate additionally
# needs curl's --cacert or -k, which is a good reason to prefer the spool.
#
# Install per project in .claude/settings.json, or once in ~/.claude/settings.json to observe every
# project. Global installation is safe: the spool is namespaced per project and prunes itself, so a
# watcher only ever sees events from the workspace it is watching.
#
#   {
#     "hooks": {
#       "PostToolUse": [
#         { "matcher": "*", "hooks": [
#             { "type": "command", "command": "/absolute/path/to/workspace-watcher-hook.sh" } ] }
#       ]
#     }
#   }

BASE="${WORKSPACE_WATCHER_SPOOL:-$HOME/.claude/workspace-watcher-spool}"
PROJECT="${CLAUDE_PROJECT_DIR:-$PWD}"

if [ -z "${WORKSPACE_WATCHER_URL:-}" ]; then
  # --- Local: spool to a file -----------------------------------------------------------------
  # Namespaced per project, with the same escaping Claude Code uses for its transcript directories
  # (every non-alphanumeric character becomes a dash). Installed globally, this keeps one project's
  # events out of another project's watcher.
  SPOOL="$BASE/$(printf '%s' "$PROJECT" | tr -c 'A-Za-z0-9' '-')"
  mkdir -p "$SPOOL" 2>/dev/null || exit 0

  # The directory name is escaped and therefore not reversible, so the real path is left beside it
  # once. This is what lets a watcher start with no workspace configured and discover the projects
  # that are actually being worked in.
  [ -f "$SPOOL/.workspace" ] || printf '%s\n' "$PROJECT" > "$SPOOL/.workspace" 2>/dev/null

  # Self-pruning, because nothing else will. A watcher drains and ages out the spool, but a project
  # that never has one would otherwise accumulate a file per tool call forever. Bounded to one pair
  # of cheap find calls per five minutes rather than one per tool call.
  MARK="$SPOOL/.last-prune"
  if [ ! -f "$MARK" ] || [ -z "$(find "$MARK" -mmin -5 2>/dev/null)" ]; then
    touch "$MARK" 2>/dev/null
    find "$SPOOL" -name '*.json' -mmin +60 -delete 2>/dev/null
  fi

  # Written to a temporary name and renamed into place. Rename is atomic within a filesystem, so
  # the watcher can never read a half-written payload - no locking, no partial JSON.
  TMP="$SPOOL/.tmp.$$.$RANDOM"
  if cat > "$TMP" 2>/dev/null; then
    mv -f "$TMP" "$SPOOL/$(date +%Y%m%dT%H%M%S)-$$-$RANDOM.json" 2>/dev/null || rm -f "$TMP" 2>/dev/null
  else
    rm -f "$TMP" 2>/dev/null
  fi
  exit 0
fi

# --- Remote: GraphQL mutation -----------------------------------------------------------------
# Base64 sidesteps every shell and JSON quoting problem without needing jq or python.
BREAKER="${TMPDIR:-/tmp}/.workspace-watcher-breaker"

# A watcher that is not running costs nothing: the connection is refused in about a millisecond.
# The expensive case is one that accepts the connection and then stalls - a garbage-collecting JVM,
# an unreachable host - which would otherwise charge its timeout to every single tool call. After
# one failure, stay quiet for a minute.
if [ -f "$BREAKER" ] && [ -n "$(find "$BREAKER" -mmin -1 2>/dev/null)" ]; then
  exit 0
fi

# The body is streamed into curl on stdin, never passed as an argument. Hook payloads carry
# tool_response, which for a large file read exceeds ARG_MAX (1 MB on macOS); as an argument that
# fails with "argument list too long" and the event vanishes silently.
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
         "$WORKSPACE_WATCHER_URL" >/dev/null 2>&1
then
  rm -f "$BREAKER" 2>/dev/null
else
  touch "$BREAKER" 2>/dev/null
fi

exit 0
