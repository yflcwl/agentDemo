package com.ex.yagent.claude.tools.task;

import com.ex.yagent.claude.tools.support.AbstractClaudeTools;
import com.ex.yagent.claude.tools.support.ClaudeTool;
import com.ex.yagent.claude.tools.support.ToolExecutionContext;
import com.ex.yagent.claude.tools.support.ToolExecutionResult;
import com.ex.yagent.claude.tools.support.ToolTaskView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class TaskTools extends AbstractClaudeTools {

    @ClaudeTool(name = "create_task", description = "创建一个任务。", schemaMethod = "createTaskSchema")
    ToolExecutionResult createTask(Map<String, Object> input, ToolExecutionContext context) {
        List<String> blockedBy = new ArrayList<>();
        Object dependencies = input.get("blockedBy");
        if (dependencies instanceof List<?> dependencyList) {
            for (Object dependency : dependencyList) {
                blockedBy.add(asString(dependency));
            }
        }
        ToolTaskView taskRecord = context.services().createTask(
                asString(input.get("subject")),
                asString(input.getOrDefault("description", "")),
                blockedBy
        );
        return ToolExecutionResult.of("已创建任务 " + taskRecord.id() + "：" + taskRecord.subject());
    }

    @ClaudeTool(name = "list_tasks", description = "列出所有任务。", schemaMethod = "emptySchema")
    ToolExecutionResult listTasks(Map<String, Object> input, ToolExecutionContext context) throws IOException {
        List<ToolTaskView> tasks = context.services().listTasks();
        if (tasks.isEmpty()) {
            return ToolExecutionResult.of("当前没有任务。");
        }
        StringBuilder builder = new StringBuilder();
        for (ToolTaskView task : tasks) {
            if (!builder.isEmpty()) {
                builder.append(System.lineSeparator());
            }
            builder.append(task.id())
                    .append(": ")
                    .append(task.subject())
                    .append(" [")
                    .append(task.status())
                    .append("]");
            if (task.worktree() != null && !task.worktree().isBlank()) {
                builder.append(" (wt:").append(task.worktree()).append(")");
            }
        }
        return ToolExecutionResult.of(builder.toString());
    }

    @ClaudeTool(name = "get_task", description = "获取任务完整详情。", schemaMethod = "taskIdSchema")
    ToolExecutionResult getTask(Map<String, Object> input, ToolExecutionContext context) throws IOException {
        return ToolExecutionResult.of(context.services().getTaskJson(asString(input.get("task_id"))));
    }

    @ClaudeTool(name = "claim_task", description = "认领一个待处理任务。", schemaMethod = "taskIdSchema")
    ToolExecutionResult claimTask(Map<String, Object> input, ToolExecutionContext context) {
        ToolTaskView record = context.services().claimTask(asString(input.get("task_id")), context.ownerName());
        return ToolExecutionResult.of("已由 " + context.ownerName() + " 认领任务 " + record.id());
    }

    @ClaudeTool(name = "complete_task", description = "完成一个进行中的任务。", schemaMethod = "taskIdSchema")
    ToolExecutionResult completeTask(Map<String, Object> input, ToolExecutionContext context) {
        ToolTaskView record = context.services().completeTask(asString(input.get("task_id")));
        return ToolExecutionResult.of("已完成任务 " + record.id());
    }

    @ClaudeTool(name = "schedule_cron", description = "注册一个 cron 定时任务。", schemaMethod = "scheduleCronSchema")
    ToolExecutionResult scheduleCron(Map<String, Object> input, ToolExecutionContext context) {
        return ToolExecutionResult.of(context.services().scheduleCron(
                asString(input.get("cron")),
                asString(input.get("prompt")),
                !input.containsKey("recurring") || asBoolean(input.get("recurring")),
                !input.containsKey("durable") || asBoolean(input.get("durable"))
        ));
    }

    @ClaudeTool(name = "list_crons", description = "列出已注册的 cron 任务。", schemaMethod = "emptySchema")
    ToolExecutionResult listCrons(Map<String, Object> input, ToolExecutionContext context) {
        return ToolExecutionResult.of(context.services().listCrons());
    }

    @ClaudeTool(name = "cancel_cron", description = "按 ID 取消一个 cron 任务。", schemaMethod = "jobIdSchema")
    ToolExecutionResult cancelCron(Map<String, Object> input, ToolExecutionContext context) {
        return ToolExecutionResult.of(context.services().cancelCron(asString(input.get("job_id"))));
    }

    Map<String, Object> createTaskSchema() {
        return schema(Map.of("subject", type("string"), "description", type("string"), "blockedBy", Map.of("type", "array")), List.of("subject"));
    }

    Map<String, Object> taskIdSchema() {
        return schema(Map.of("task_id", type("string")), List.of("task_id"));
    }

    Map<String, Object> scheduleCronSchema() {
        return schema(Map.of("cron", type("string"), "prompt", type("string"), "recurring", type("boolean"), "durable", type("boolean")),
                List.of("cron", "prompt"));
    }

    Map<String, Object> jobIdSchema() {
        return schema(Map.of("job_id", type("string")), List.of("job_id"));
    }

    Map<String, Object> emptySchema() {
        return schema(Map.of(), List.of());
    }
}
