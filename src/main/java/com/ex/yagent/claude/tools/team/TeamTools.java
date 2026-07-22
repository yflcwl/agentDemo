package com.ex.yagent.claude.tools.team;

import com.ex.yagent.claude.tools.support.AbstractClaudeTools;
import com.ex.yagent.claude.tools.support.ClaudeTool;
import com.ex.yagent.claude.tools.support.ToolExecutionContext;
import com.ex.yagent.claude.tools.support.ToolExecutionResult;

import java.util.List;
import java.util.Map;

public final class TeamTools extends AbstractClaudeTools {

    @ClaudeTool(name = "spawn_teammate", description = "创建一个自治队友代理。", schemaMethod = "spawnTeammateSchema", hiddenInSubagent = true, hiddenInTeammate = true)
    ToolExecutionResult spawnTeammate(Map<String, Object> input, ToolExecutionContext context) {
        return ToolExecutionResult.of(context.spawnTeammate(
                asString(input.get("name")),
                asString(input.get("role")),
                asString(input.get("prompt"))
        ));
    }

    @ClaudeTool(name = "send_message", description = "给队友发送一条消息。", schemaMethod = "sendMessageSchema")
    ToolExecutionResult sendMessage(Map<String, Object> input, ToolExecutionContext context) {
        context.services().sendMessage("lead", asString(input.get("to")), "message",
                asString(input.get("content")), Map.of());
        return ToolExecutionResult.of("已发送");
    }

    @ClaudeTool(name = "check_inbox", description = "查看队友或协议响应的收件箱内容。", schemaMethod = "emptySchema")
    ToolExecutionResult checkInbox(Map<String, Object> input, ToolExecutionContext context) {
        return ToolExecutionResult.of(context.services().drainInboxBuffer());
    }

    @ClaudeTool(name = "request_shutdown", description = "请求某个队友关闭。", schemaMethod = "teammateSchema")
    ToolExecutionResult requestShutdown(Map<String, Object> input, ToolExecutionContext context) {
        return ToolExecutionResult.of(context.services().requestShutdown(asString(input.get("teammate"))));
    }

    @ClaudeTool(name = "request_plan", description = "请求队友提交一份计划。", schemaMethod = "requestPlanSchema")
    ToolExecutionResult requestPlan(Map<String, Object> input, ToolExecutionContext context) {
        return ToolExecutionResult.of(context.services().requestPlan(
                asString(input.get("teammate")),
                asString(input.get("task"))
        ));
    }

    @ClaudeTool(name = "review_plan", description = "批准或拒绝一份已提交的计划。", schemaMethod = "reviewPlanSchema")
    ToolExecutionResult reviewPlan(Map<String, Object> input, ToolExecutionContext context) {
        return ToolExecutionResult.of(context.services().reviewPlan(
                asString(input.get("request_id")),
                asBoolean(input.get("approve")),
                asString(input.getOrDefault("feedback", ""))
        ));
    }

    Map<String, Object> spawnTeammateSchema() {
        return schema(Map.of("name", type("string"), "role", type("string"), "prompt", type("string")), List.of("name", "role", "prompt"));
    }

    Map<String, Object> sendMessageSchema() {
        return schema(Map.of("to", type("string"), "content", type("string")), List.of("to", "content"));
    }

    Map<String, Object> teammateSchema() {
        return schema(Map.of("teammate", type("string")), List.of("teammate"));
    }

    Map<String, Object> requestPlanSchema() {
        return schema(Map.of("teammate", type("string"), "task", type("string")), List.of("teammate", "task"));
    }

    Map<String, Object> reviewPlanSchema() {
        return schema(Map.of("request_id", type("string"), "approve", type("boolean"), "feedback", type("string")), List.of("request_id", "approve"));
    }

    Map<String, Object> emptySchema() {
        return schema(Map.of(), List.of());
    }
}
