package com.ex.yagent.claude;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ToolRegistry 负责把“模型看到的工具定义”和“Java 侧真正执行的 handler”组装在一起。
 * 这正对应教程里的 TOOL_HANDLERS / tools 两张表。
 */
final class ToolRegistry {

    private final ClaudeRuntime runtime;
    private final Map<String, ToolDefinition> builtinDefinitions = new LinkedHashMap<>();
    private final Map<String, ClaudeToolHandler> builtinHandlers = new LinkedHashMap<>();

    ToolRegistry(ClaudeRuntime runtime) {
        this.runtime = runtime;
        registerBuiltins();
    }

    /**
     * 每一轮都要重新 assemble，一方面是为了根据 mode 控制可见工具，
     * 另一方面是为了把“本轮之前 connect 上的 MCP 工具”动态并进来。
     */
    AssembledTools assemble(ClaudeSession session, ClaudeAgentLoop loop) {
        Map<String, ToolDefinition> definitions = new LinkedHashMap<>();
        Map<String, ClaudeToolHandler> handlers = new LinkedHashMap<>();
        for (Map.Entry<String, ToolDefinition> entry : builtinDefinitions.entrySet()) {
            if (isVisible(entry.getKey(), session.mode())) {
                definitions.put(entry.getKey(), entry.getValue());
                handlers.put(entry.getKey(), builtinHandlers.get(entry.getKey()));
            }
        }
        definitions.putAll(runtime.mcpRegistry().connectedToolDefinitions());
        handlers.putAll(runtime.mcpRegistry().connectedHandlers());
        return new AssembledTools(definitions.values().stream().toList(), handlers);
    }

    /**
     * 子代理和队友线程都要“阉割”掉会继续分裂上下文的工具，避免无限递归。
     */
    private boolean isVisible(String name, ToolMode mode) {
        if (mode == ToolMode.SUBAGENT) {
            return !List.of("task", "spawn_teammate").contains(name);
        }
        if (mode == ToolMode.TEAMMATE) {
            return !"spawn_teammate".equals(name);
        }
        return true;
    }

    private void registerBuiltins() {
        register("bash", "Run a shell command.",
                schema(Map.of("command", type("string"), "run_in_background", type("boolean")), List.of("command")),
                (input, context) -> ToolExecutionResult.of(runBash(input, context.workingDirectory())));
        register("read_file", "Read file contents.",
                schema(Map.of("path", type("string"), "limit", type("integer"), "offset", type("integer")), List.of("path")),
                (input, context) -> ToolExecutionResult.of(readFile(input, context.workingDirectory())));
        register("write_file", "Write content to a file.",
                schema(Map.of("path", type("string"), "content", type("string")), List.of("path", "content")),
                (input, context) -> ToolExecutionResult.of(writeFile(input, context.workingDirectory())));
        register("edit_file", "Replace exact text in a file once.",
                schema(Map.of("path", type("string"), "old_text", type("string"), "new_text", type("string")), List.of("path", "old_text", "new_text")),
                (input, context) -> ToolExecutionResult.of(editFile(input, context.workingDirectory())));
        register("glob", "Find files matching a glob pattern.",
                schema(Map.of("pattern", type("string")), List.of("pattern")),
                (input, context) -> ToolExecutionResult.of(glob(input, context.workingDirectory())));
        register("todo_write", "Create and manage a task list for the current session.",
                schema(Map.of("todos", Map.of("type", "array")), List.of("todos")),
                this::todoWrite);
        register("task", "Launch a focused subagent. Returns only the final summary.",
                schema(Map.of("description", type("string")), List.of("description")),
                (input, context) -> ToolExecutionResult.of(context.loop().runSubagent(
                        Objects.toString(input.get("description"), ""),
                        context.workingDirectory(),
                        context.session().ownerName()
                )));
        register("load_skill", "Load the full content of a skill by name.",
                schema(Map.of("name", type("string")), List.of("name")),
                (input, context) -> ToolExecutionResult.of(runtime.skillRegistry().loadSkill(Objects.toString(input.get("name"), ""))));
        register("compact", "Summarize earlier conversation and continue with compacted context.",
                schema(Map.of(), List.of()),
                (input, context) -> ToolExecutionResult.compacted("Context compacted."));
        register("create_task", "Create a task.",
                schema(Map.of("subject", type("string"), "description", type("string"), "blockedBy", Map.of("type", "array")), List.of("subject")),
                this::createTask);
        register("list_tasks", "List all tasks.",
                schema(Map.of(), List.of()),
                (input, context) -> ToolExecutionResult.of(listTasks()));
        register("get_task", "Get full task details.",
                schema(Map.of("task_id", type("string")), List.of("task_id")),
                (input, context) -> ToolExecutionResult.of(runtime.objectMapper().writerWithDefaultPrettyPrinter()
                        .writeValueAsString(runtime.taskStore().load(Objects.toString(input.get("task_id"), "")))));
        register("claim_task", "Claim a pending task.",
                schema(Map.of("task_id", type("string")), List.of("task_id")),
                this::claimTask);
        register("complete_task", "Complete an in-progress task.",
                schema(Map.of("task_id", type("string")), List.of("task_id")),
                this::completeTask);
        register("schedule_cron", "Schedule a cron job.",
                schema(Map.of("cron", type("string"), "prompt", type("string"), "recurring", type("boolean"), "durable", type("boolean")),
                        List.of("cron", "prompt")),
                this::scheduleCron);
        register("list_crons", "List registered cron jobs.",
                schema(Map.of(), List.of()),
                (input, context) -> ToolExecutionResult.of(runtime.cronScheduler().listJobs()));
        register("cancel_cron", "Cancel a cron job by ID.",
                schema(Map.of("job_id", type("string")), List.of("job_id")),
                (input, context) -> ToolExecutionResult.of(runtime.cronScheduler().cancel(Objects.toString(input.get("job_id"), ""))));
        register("spawn_teammate", "Spawn an autonomous teammate.",
                schema(Map.of("name", type("string"), "role", type("string"), "prompt", type("string")), List.of("name", "role", "prompt")),
                (input, context) -> ToolExecutionResult.of(context.loop().teammateManager().spawn(
                        Objects.toString(input.get("name"), ""),
                        Objects.toString(input.get("role"), ""),
                        Objects.toString(input.get("prompt"), ""),
                        context.workingDirectory()
                )));
        register("send_message", "Send a message to a teammate.",
                schema(Map.of("to", type("string"), "content", type("string")), List.of("to", "content")),
                (input, context) -> {
                    runtime.messageBus().send("lead", Objects.toString(input.get("to"), ""), "message",
                            Objects.toString(input.get("content"), ""), Map.of());
                    return ToolExecutionResult.of("Sent");
                });
        register("check_inbox", "Check inbox for teammate/protocol responses.",
                schema(Map.of(), List.of()),
                (input, context) -> ToolExecutionResult.of(runtime.drainInboxBuffer()));
        register("request_shutdown", "Request a teammate to shut down.",
                schema(Map.of("teammate", type("string")), List.of("teammate")),
                (input, context) -> ToolExecutionResult.of(runtime.protocolRegistry().requestShutdown(Objects.toString(input.get("teammate"), ""))));
        register("request_plan", "Ask a teammate to submit a plan.",
                schema(Map.of("teammate", type("string"), "task", type("string")), List.of("teammate", "task")),
                (input, context) -> ToolExecutionResult.of(runtime.protocolRegistry().requestPlan(
                        Objects.toString(input.get("teammate"), ""),
                        Objects.toString(input.get("task"), "")
                )));
        register("review_plan", "Approve or reject a submitted plan.",
                schema(Map.of("request_id", type("string"), "approve", type("boolean"), "feedback", type("string")), List.of("request_id", "approve")),
                (input, context) -> ToolExecutionResult.of(runtime.protocolRegistry().reviewPlan(
                        Objects.toString(input.get("request_id"), ""),
                        asBoolean(input.get("approve")),
                        Objects.toString(input.getOrDefault("feedback", ""), "")
                )));
        register("create_worktree", "Create an isolated git worktree.",
                schema(Map.of("name", type("string"), "task_id", type("string")), List.of("name")),
                (input, context) -> ToolExecutionResult.of(runtime.worktreeService().create(
                        Objects.toString(input.get("name"), ""),
                        Objects.toString(input.getOrDefault("task_id", ""), "")
                )));
        register("remove_worktree", "Remove a worktree.",
                schema(Map.of("name", type("string"), "discard_changes", type("boolean")), List.of("name")),
                (input, context) -> ToolExecutionResult.of(runtime.worktreeService().remove(
                        Objects.toString(input.get("name"), ""),
                        asBoolean(input.get("discard_changes"))
                )));
        register("keep_worktree", "Keep a worktree for manual review.",
                schema(Map.of("name", type("string")), List.of("name")),
                (input, context) -> ToolExecutionResult.of(runtime.worktreeService().keep(Objects.toString(input.get("name"), ""))));
        register("connect_mcp", "Connect to a mock MCP server and expose its tools next round.",
                schema(Map.of("name", type("string")), List.of("name")),
                (input, context) -> ToolExecutionResult.of(runtime.mcpRegistry().connect(Objects.toString(input.get("name"), ""))));
    }

    private void register(String name, String description, Map<String, Object> schema, ClaudeToolHandler handler) {
        builtinDefinitions.put(name, new ToolDefinition(name, description, schema));
        builtinHandlers.put(name, handler);
    }

    /**
     * todo_write 的核心职责只有一个：把计划结构化地写进 session。
     */
    private ToolExecutionResult todoWrite(Map<String, Object> input, ToolExecutionContext context) {
        Object todosValue = input.get("todos");
        if (!(todosValue instanceof List<?> todoList)) {
            return ToolExecutionResult.of("Error: todos must be a list");
        }
        List<TodoItem> todos = new ArrayList<>();
        for (Object item : todoList) {
            if (!(item instanceof Map<?, ?> todoMap)) {
                return ToolExecutionResult.of("Error: every todo must be an object");
            }
            String content = Objects.toString(todoMap.get("content"), "");
            String status = Objects.toString(todoMap.get("status"), "");
            if (!List.of("pending", "in_progress", "completed").contains(status)) {
                return ToolExecutionResult.of("Error: invalid todo status " + status);
            }
            todos.add(new TodoItem(content, status));
        }
        context.session().replaceTodos(todos);
        return ToolExecutionResult.of("Updated " + todos.size() + " todos");
    }

    /**
     * 任务系统是 S20 的“跨轮次 / 跨代理协作层”，这里先把 task 文件落盘。
     */
    private ToolExecutionResult createTask(Map<String, Object> input, ToolExecutionContext context) {
        List<String> blockedBy = new ArrayList<>();
        Object dependencies = input.get("blockedBy");
        if (dependencies instanceof List<?> dependencyList) {
            for (Object dependency : dependencyList) {
                blockedBy.add(Objects.toString(dependency, ""));
            }
        }
        TaskRecord taskRecord = runtime.taskStore().create(
                Objects.toString(input.get("subject"), ""),
                Objects.toString(input.getOrDefault("description", ""), ""),
                blockedBy
        );
        return ToolExecutionResult.of("Created " + taskRecord.id() + ": " + taskRecord.subject());
    }

    private ToolExecutionResult claimTask(Map<String, Object> input, ToolExecutionContext context) {
        TaskRecord record = runtime.taskStore().claim(Objects.toString(input.get("task_id"), ""), context.session().ownerName());
        return ToolExecutionResult.of("Claimed " + record.id() + " by " + context.session().ownerName());
    }

    private ToolExecutionResult completeTask(Map<String, Object> input, ToolExecutionContext context) {
        TaskRecord record = runtime.taskStore().complete(Objects.toString(input.get("task_id"), ""));
        return ToolExecutionResult.of("Completed " + record.id());
    }

    private ToolExecutionResult scheduleCron(Map<String, Object> input, ToolExecutionContext context) {
        return ToolExecutionResult.of(runtime.cronScheduler().schedule(
                Objects.toString(input.get("cron"), ""),
                Objects.toString(input.get("prompt"), ""),
                !input.containsKey("recurring") || asBoolean(input.get("recurring")),
                !input.containsKey("durable") || asBoolean(input.get("durable"))
        ));
    }

    private String listTasks() throws IOException {
        List<TaskRecord> tasks = runtime.taskStore().list();
        if (tasks.isEmpty()) {
            return "No tasks.";
        }
        StringBuilder builder = new StringBuilder();
        for (TaskRecord task : tasks) {
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
        return builder.toString();
    }

    /**
     * bash 工具刻意保持“能力很强”，因为教程就是要演示 agent 如何通过 shell 操作环境。
     * 这里仅负责执行，真正的安全边界由 permission hook 决定。
     */
    private String runBash(Map<String, Object> input, Path workingDirectory) {
        String command = Objects.toString(input.get("command"), "");
        ProcessBuilder builder = new ProcessBuilder("powershell", "-NoProfile", "-Command", command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Error: Timeout (120s)";
            }
            return output.isBlank() ? "(no output)" : output.trim();
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: Interrupted";
        }
    }

    private String readFile(Map<String, Object> input, Path workingDirectory) {
        try {
            Path path = safePath(workingDirectory, Objects.toString(input.get("path"), ""));
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int offset = input.containsKey("offset") ? asInt(input.get("offset")) : 0;
            int limit = input.containsKey("limit") ? asInt(input.get("limit")) : lines.size();
            List<String> sliced = lines.stream().skip(Math.max(offset, 0)).limit(Math.max(limit, 0)).toList();
            return String.join(System.lineSeparator(), sliced);
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    private String writeFile(Map<String, Object> input, Path workingDirectory) {
        try {
            Path path = safePath(workingDirectory, Objects.toString(input.get("path"), ""));
            Files.createDirectories(path.getParent());
            Files.writeString(path, Objects.toString(input.get("content"), ""), StandardCharsets.UTF_8);
            return "Wrote " + path.getFileName();
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    private String editFile(Map<String, Object> input, Path workingDirectory) {
        try {
            Path path = safePath(workingDirectory, Objects.toString(input.get("path"), ""));
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String oldText = Objects.toString(input.get("old_text"), "");
            String newText = Objects.toString(input.get("new_text"), "");
            if (!content.contains(oldText)) {
                return "Error: text not found";
            }
            Files.writeString(path, content.replaceFirst(java.util.regex.Pattern.quote(oldText),
                    java.util.regex.Matcher.quoteReplacement(newText)), StandardCharsets.UTF_8);
            return "Edited " + path.getFileName();
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    private String glob(Map<String, Object> input, Path workingDirectory) {
        String pattern = Objects.toString(input.get("pattern"), "");
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        try {
            List<Path> matches = Files.walk(workingDirectory)
                    .filter(path -> matcher.matches(workingDirectory.relativize(path)))
                    .sorted(Comparator.naturalOrder())
                    .toList();
            if (matches.isEmpty()) {
                return "(no matches)";
            }
            List<String> relativePaths = new ArrayList<>();
            for (Path match : matches) {
                relativePaths.add(workingDirectory.relativize(match).toString());
            }
            return String.join(System.lineSeparator(), relativePaths);
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        }
    }

    /**
     * 文件工具统一通过 safePath 做工作区边界校验，避免写出 workspace。
     */
    static Path safePath(Path workingDirectory, String relativePath) {
        Path resolved = workingDirectory.resolve(relativePath).normalize();
        if (!resolved.startsWith(workingDirectory.normalize())) {
            throw new IllegalArgumentException("Path escapes workspace: " + relativePath);
        }
        return resolved;
    }

    static boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(Objects.toString(value, "false"));
    }

    static int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(Objects.toString(value, "0"));
    }

    private Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private Map<String, Object> type(String type) {
        return Map.of("type", type);
    }
}

/**
 * Tool handler 统一返回 ToolExecutionResult，而不是直接返回字符串。
 * 这样像 compact 这种“带额外控制语义”的工具，也能在 loop 里做特殊处理。
 */
@FunctionalInterface
interface ClaudeToolHandler {
    ToolExecutionResult handle(Map<String, Object> input, ToolExecutionContext context) throws Exception;
}

record AssembledTools(List<ToolDefinition> definitions, Map<String, ClaudeToolHandler> handlers) {
}

/**
 * 工具执行上下文把 runtime、当前 session、当前 loop 和当前工作目录绑在一起，
 * 这样单个工具不需要直接依赖全局静态状态。
 *
 * @param runtime 整个运行时容器，里面能拿到 task store、message bus、cron、MCP registry 等共享组件。
 * @param session 当前是谁在执行工具，以及当前会话历史 / todo / mode 是什么。
 * @param loop 当前这条主循环对象；像 {@code task} 这种工具需要通过它再开一个 subagent。
 * @param workingDirectory 当前工具应该在哪个目录下执行。
 *                         lead 通常是项目根目录，绑定了 worktree 的 teammate 则可能是某个 worktree 目录。
 */
record ToolExecutionContext(
        ClaudeRuntime runtime,
        ClaudeSession session,
        ClaudeAgentLoop loop,
        Path workingDirectory
) {
}
