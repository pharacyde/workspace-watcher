#!/usr/bin/env bash
# Forwards a Claude Code hook payload to a running workspace-watcher.
#
# Claude Code passes the hook payload as JSON on stdin. It is base64-encoded and sent as a GraphQL
# mutation: base64 sidesteps every shell and JSON quoting problem without needing jq or python.
#
# This always exits 0 and discards all output. A hook blocks the agent until it returns, and an
# observer must never be able to block or alter the agent it is watching.
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

PAYLOAD=$(base64 | tr -d '\n')

curl --silent --max-time 2 \
     --header 'Content-Type: application/json' \
     --data "{\"query\":\"mutation(\$p:String!){recordAgentEvent(payloadBase64:\$p)}\",\"variables\":{\"p\":\"${PAYLOAD}\"}}" \
     "$URL" >/dev/null 2>&1

exit 0
