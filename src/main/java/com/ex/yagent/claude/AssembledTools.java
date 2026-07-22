package com.ex.yagent.claude;

import com.ex.yagent.claude.tools.support.ClaudeToolHandler;

import java.util.List;
import java.util.Map;

record AssembledTools(List<ToolDefinition> definitions, Map<String, ClaudeToolHandler> handlers) {
}
