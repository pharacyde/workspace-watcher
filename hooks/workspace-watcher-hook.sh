#!/usr/bin/env bash
# Forwards a Claude Code hook payload to a running workspace-watcher.
#
# Claude Code passes the hook payload as JSON on stdin. We hand it straight through and always
# exit 0: an observer must never be able to block or alter the agent it is watching.
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

URL="${WORKSPACE_WATCHER_URL:-http://127.0.0.1:8080/api/hook}"

curl --silent --show-error --max-time 2 \
     --header 'Content-Type: application/json' \
     --data-binary @- \
     "$URL" >/dev/null 2>&1

exit 0
