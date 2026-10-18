#!/bin/sh
# A fake MCP server for samizdat.mcp-test: one JSON-RPC message per line on
# stdin, canned answers on stdout. No JSON parser — it matches the method
# and copies the id, which is all the client's framing needs.
while IFS= read -r line; do
  id=$(printf '%s' "$line" | sed -n 's/.*"id":\([0-9][0-9]*\).*/\1/p')
  case "$line" in
    *'"method":"initialize"'*)
      printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{"listChanged":true}},"serverInfo":{"name":"fake","version":"1"}}}\n' "$id" ;;
    *'"method":"tools/list"'*)
      printf '{"jsonrpc":"2.0","id":%s,"result":{"tools":[{"name":"lookup","description":"Look a word up.","inputSchema":{"type":"object","properties":{"word":{"type":"string"}},"required":["word"]}},{"name":"boom","description":"Always fails.","inputSchema":{"type":"object"}}]}}\n' "$id" ;;
    *'"method":"tools/call"'*'"name":"boom"'*)
      printf '{"jsonrpc":"2.0","id":%s,"result":{"isError":true,"content":[{"type":"text","text":"it blew up"}]}}\n' "$id" ;;
    *'"method":"tools/call"'*)
      printf '{"jsonrpc":"2.0","id":%s,"result":{"content":[{"type":"text","text":"a lookup result"},{"type":"text","text":"second part"}]}}\n' "$id" ;;
    *'"method":"notifications/'*) : ;;
    *)
      [ -n "$id" ] && printf '{"jsonrpc":"2.0","id":%s,"error":{"code":-32601,"message":"no such method"}}\n' "$id" ;;
  esac
done
