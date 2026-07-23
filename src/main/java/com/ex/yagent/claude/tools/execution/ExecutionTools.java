package com.ex.yagent.claude.tools.execution;

import com.ex.yagent.claude.CommandSupport;
import com.ex.yagent.claude.tools.support.AbstractClaudeTools;
import com.ex.yagent.claude.tools.support.ClaudeTool;
import com.ex.yagent.claude.tools.support.ToolExecutionContext;
import com.ex.yagent.claude.tools.support.ToolExecutionResult;

import java.util.List;
import java.util.Map;

public final class ExecutionTools extends AbstractClaudeTools {

    @ClaudeTool(name = "bash", description = "执行一条 shell 命令。", schemaMethod = "bashSchema")
    ToolExecutionResult bash(Map<String, Object> input, ToolExecutionContext context) {
        String command = asString(input.get("command"));
        ProcessBuilder builder = new ProcessBuilder("powershell", "-NoProfile", "-Command", command);
        builder.directory(context.workingDirectory().toFile());
        builder.redirectErrorStream(true);
        CommandSupport.CommandResult result = CommandSupport.run(builder, 120);
        if (result.timedOut()) {
            return ToolExecutionResult.of("错误：执行超时（120 秒）");
        }
        return ToolExecutionResult.of(result.output().isBlank() ? "(no output)" : result.output());
    }

    @ClaudeTool(name = "compact", description = "压缩较早对话并在精简后的上下文中继续。", schemaMethod = "emptySchema")
    ToolExecutionResult compact(Map<String, Object> input, ToolExecutionContext context) {
        return ToolExecutionResult.compacted("上下文已压缩。");
    }

    @ClaudeTool(name = "connect_mcp", description = "连接一个模拟 MCP 服务，并在下一轮暴露它的工具。", schemaMethod = "connectMcpSchema")
    ToolExecutionResult connectMcp(Map<String, Object> input, ToolExecutionContext context) {
        return ToolExecutionResult.of(context.services().connectMcp(asString(input.get("name"))));
    }

    Map<String, Object> bashSchema() {
        return schema(Map.of("command", type("string"), "run_in_background", type("boolean")), List.of("command"));
    }

    Map<String, Object> connectMcpSchema() {
        return schema(Map.of("name", type("string")), List.of("name"));
    }

    Map<String, Object> emptySchema() {
        return schema(Map.of(), List.of());
    }
}
