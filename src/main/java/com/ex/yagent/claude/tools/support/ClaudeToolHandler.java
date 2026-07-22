package com.ex.yagent.claude.tools.support;

import java.util.Map;

@FunctionalInterface
public interface ClaudeToolHandler {
    ToolExecutionResult handle(Map<String, Object> input, ToolExecutionContext context) throws Exception;
}
