#!/usr/bin/env bash
# PreToolUse guard for workspace-watcher.
#
# This is the one hook that can stop the agent, so installing it is a separate, deliberate act:
# workspace-watcher-hook.sh only observes, this one decides. Wire it into PreToolUse, and turn
# enforcement on in the dashboard - until you do, rules only produce events and nothing is blocked.
#
#   {
#     "hooks": {
#       "PreToolUse": [
#         { "matcher": "*", "hooks": [
#             { "type": "command", "command": "/absolute/path/to/workspace-watcher-guard.sh" } ] }
#       ]
#     }
#   }
#
# It fails open, everywhere. A hook holds the agent until it returns, so a watcher that is slow,
# wedged or simply not running must let the call through rather than wedge the agent with it. That
# makes this a guardrail against an agent's mistakes and not a boundary against a determined one -
# anyone who can stop the watcher can bypass it - which is the right trade for a development tool.

URL="${WORKSPACE_WATCHER_URL:-http://127.0.0.1:8080/graphql}"

PAYLOAD=$(base64 | tr -d '\n')

# connect-timeout is short because the watcher is normally on loopback; max-time bounds what a
# stalled one can cost. Both expire into "allow".
RESPONSE=$(printf '{"query":"mutation($p:String!){checkToolUse(payloadBase64:$p){action reason}}","variables":{"p":"%s"}}' "$PAYLOAD" \
  | curl --silent \
         --connect-timeout 0.3 \
         --max-time 2 \
         --header 'Content-Type: application/json' \
         --data-binary @- \
         "$URL" 2>/dev/null)

case "$RESPONSE" in
  *'"action":"DENY"'*) ;;
  *) exit 0 ;;
esac

# The server strips quotes, backslashes and newlines from the reason precisely so this is safe
# without jq, which would otherwise become a dependency of every tool call.
REASON=$(printf '%s' "$RESPONSE" | sed -n 's/.*"reason":"\([^"]*\)".*/\1/p')
[ -n "$REASON" ] || REASON="blocked by a workspace-watcher guard rule"

printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"%s"}}' "$REASON"

# Exit 2 is what actually blocks; the JSON above only supplies the reason.
exit 2
