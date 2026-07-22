package com.ex.yagent.claude.tools.support;

public record ToolExecutionResult(String content, boolean compactRequested) {

    public static ToolExecutionResult of(String content) {
        return new ToolExecutionResult(content, false);
    }

    public static ToolExecutionResult compacted(String content) {
        return new ToolExecutionResult(content, true);
    }
}
