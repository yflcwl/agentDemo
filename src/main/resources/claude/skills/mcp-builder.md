---
name: mcp-builder
description: Add or extend MCP-style tools while keeping the tool pool assembly explicit.
---

# MCP Builder

When adding MCP tools:
- keep server registration explicit
- expose tools with `mcp__{server}__{tool}` names
- avoid mixing mock implementations with transport concerns
- keep permission checks outside the transport stub
