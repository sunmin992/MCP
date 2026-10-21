#!/usr/bin/env bash
# usage: MCP_SESSION=id mcp.sh <url> <method> [params-json]
URL="$1"; URL="${URL/localhost/127.0.0.1}"; METHOD="$2"; PARAMS="${3:-{\}}"
curl -s -m 60 -X POST "$URL" -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -H "X-Session-Id: ${MCP_SESSION:-anon}" -H "Mcp-Session-Id: ${MCP_SESSION:-anon}" \
  --data-binary "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"$METHOD\",\"params\":$PARAMS}"
echo
