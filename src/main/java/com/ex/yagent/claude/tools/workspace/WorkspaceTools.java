package com.ex.yagent.claude.tools.workspace;

import com.ex.yagent.claude.tools.support.AbstractClaudeTools;
import com.ex.yagent.claude.tools.support.ClaudeTool;
import com.ex.yagent.claude.tools.support.ToolExecutionContext;
import com.ex.yagent.claude.tools.support.ToolExecutionResult;

import java.util.List;
import java.util.Map;

public final class WorkspaceTools extends AbstractClaudeTools {

    @ClaudeTool(name = "create_worktree", description = "创建一个隔离的 git worktree。", schemaMethod = "createWorktreeSchema")
    ToolExecutionResult createWorktree(Map<String, Object> input, ToolExecutionContext context) {
        return ToolExecutionResult.of(context.services().createWorktree(
                asString(input.get("name")),
                asString(input.getOrDefault("task_id", ""))
        ));
    }

    @ClaudeTool(name = "remove_worktree", description = "删除一个 worktree。", schemaMethod = "removeWorktreeSchema")
    ToolExecutionResult removeWorktree(Map<String, Object> input, ToolExecutionContext context) {
        return ToolExecutionResult.of(context.services().removeWorktree(
                asString(input.get("name")),
                asBoolean(input.get("discard_changes"))
        ));
    }

    @ClaudeTool(name = "keep_worktree", description = "保留一个 worktree 以供人工复查。", schemaMethod = "keepWorktreeSchema")
    ToolExecutionResult keepWorktree(Map<String, Object> input, ToolExecutionContext context) {
        return ToolExecutionResult.of(context.services().keepWorktree(asString(input.get("name"))));
    }

    Map<String, Object> createWorktreeSchema() {
        return schema(Map.of("name", type("string"), "task_id", type("string")), List.of("name"));
    }

    Map<String, Object> removeWorktreeSchema() {
        return schema(Map.of("name", type("string"), "discard_changes", type("boolean")), List.of("name"));
    }

    Map<String, Object> keepWorktreeSchema() {
        return schema(Map.of("name", type("string")), List.of("name"));
    }
}
