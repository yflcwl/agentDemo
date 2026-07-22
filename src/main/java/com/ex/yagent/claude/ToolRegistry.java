package com.ex.yagent.claude;

import com.ex.yagent.claude.tools.execution.ExecutionTools;
import com.ex.yagent.claude.tools.file.FileTools;
import com.ex.yagent.claude.tools.planning.PlanningTools;
import com.ex.yagent.claude.tools.support.ClaudeTool;
import com.ex.yagent.claude.tools.support.ClaudeToolHandler;
import com.ex.yagent.claude.tools.support.ToolExecutionContext;
import com.ex.yagent.claude.tools.support.ToolExecutionResult;
import com.ex.yagent.claude.tools.support.ToolServices;
import com.ex.yagent.claude.tools.support.ToolTaskView;
import com.ex.yagent.claude.tools.support.ToolTodoItem;
import com.ex.yagent.claude.tools.task.TaskTools;
import com.ex.yagent.claude.tools.team.TeamTools;
import com.ex.yagent.claude.tools.workspace.WorkspaceTools;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ToolRegistry 现在只做两件事：
 * 1. 扫描带注解的工具方法并注册
 * 2. 每轮按 mode 装配当前可见工具
 */
final class ToolRegistry {

    private final ClaudeRuntime runtime;
    private final Map<String, RegisteredTool> builtinTools = new LinkedHashMap<>();

    ToolRegistry(ClaudeRuntime runtime) {
        this.runtime = runtime;
        registerAnnotatedTools(
                new ExecutionTools(),
                new FileTools(),
                new PlanningTools(),
                new TaskTools(),
                new TeamTools(),
                new WorkspaceTools()
        );
    }

    AssembledTools assemble(ClaudeSession session) {
        Map<String, ToolDefinition> definitions = new LinkedHashMap<>();
        Map<String, ClaudeToolHandler> handlers = new LinkedHashMap<>();
        for (RegisteredTool tool : builtinTools.values()) {
            if (!tool.isVisible(session.mode())) {
                continue;
            }
            definitions.put(tool.definition().name(), tool.definition());
            handlers.put(tool.definition().name(), tool::invoke);
        }
        definitions.putAll(runtime.mcpRegistry().connectedToolDefinitions());
        handlers.putAll(runtime.mcpRegistry().connectedHandlers());
        return new AssembledTools(definitions.values().stream().toList(), handlers);
    }

    ToolExecutionContext createContext(ClaudeSession session, ClaudeAgentLoop loop) {
        return new ToolExecutionContext(
                new RuntimeToolServices(runtime, loop),
                session.workingDirectory(),
                session.ownerName(),
                todos -> session.replaceTodos(todos.stream()
                        .map(todo -> new TodoItem(todo.content(), todo.status()))
                        .toList())
        );
    }

    private void registerAnnotatedTools(Object... toolGroups) {
        for (Object toolGroup : toolGroups) {
            Method[] methods = toolGroup.getClass().getDeclaredMethods();
            for (Method method : methods) {
                ClaudeTool annotation = method.getAnnotation(ClaudeTool.class);
                if (annotation == null) {
                    continue;
                }
                if (builtinTools.containsKey(annotation.name())) {
                    throw new IllegalStateException("Duplicate tool name: " + annotation.name());
                }
                validateToolMethod(method, annotation.name());
                Method schemaMethod = findSchemaMethod(toolGroup.getClass(), annotation.schemaMethod(), annotation.name());
                @SuppressWarnings("unchecked")
                Map<String, Object> schema = invokeSchema(toolGroup, schemaMethod, annotation.name());
                ToolDefinition definition = new ToolDefinition(annotation.name(), annotation.description(), schema);
                builtinTools.put(annotation.name(), new RegisteredTool(
                        definition,
                        toolGroup,
                        method,
                        annotation.hiddenInSubagent(),
                        annotation.hiddenInTeammate()
                ));
            }
        }
    }

    private void validateToolMethod(Method method, String toolName) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length != 2 || !Map.class.isAssignableFrom(parameterTypes[0]) || parameterTypes[1] != ToolExecutionContext.class) {
            throw new IllegalStateException("Tool method signature invalid for " + toolName + ", expected (Map<String,Object>, ToolExecutionContext)");
        }
        method.setAccessible(true);
    }

    private Method findSchemaMethod(Class<?> toolGroupClass, String schemaMethodName, String toolName) {
        try {
            Method schemaMethod = toolGroupClass.getDeclaredMethod(schemaMethodName);
            schemaMethod.setAccessible(true);
            return schemaMethod;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Schema method not found for tool " + toolName + ": " + schemaMethodName, e);
        }
    }

    private Map<String, Object> invokeSchema(Object toolGroup, Method schemaMethod, String toolName) {
        try {
            Object result = schemaMethod.invoke(toolGroup);
            if (!(result instanceof Map<?, ?> schema)) {
                throw new IllegalStateException("Schema method must return Map<String,Object> for tool " + toolName);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> casted = (Map<String, Object>) schema;
            return casted;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access schema method for tool " + toolName, e);
        } catch (InvocationTargetException e) {
            throw rethrowInvocationException("Schema build failed for tool " + toolName, e);
        }
    }

    static RuntimeException rethrowInvocationException(String message, InvocationTargetException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new IllegalStateException(message, cause);
    }

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

    private record RegisteredTool(
            ToolDefinition definition,
            Object toolGroup,
            Method method,
            boolean hiddenInSubagent,
            boolean hiddenInTeammate
    ) {

        boolean isVisible(ToolMode mode) {
            if (mode == ToolMode.SUBAGENT) {
                return !hiddenInSubagent;
            }
            if (mode == ToolMode.TEAMMATE) {
                return !hiddenInTeammate;
            }
            return true;
        }

        ToolExecutionResult invoke(Map<String, Object> input, ToolExecutionContext context) throws Exception {
            try {
                Object result = method.invoke(toolGroup, input, context);
                if (!(result instanceof ToolExecutionResult toolExecutionResult)) {
                    throw new IllegalStateException("Tool method must return ToolExecutionResult: " + definition.name());
                }
                return toolExecutionResult;
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot access tool method: " + definition.name(), e);
            } catch (InvocationTargetException e) {
                throw rethrowInvocationException("Tool execution failed: " + definition.name(), e);
            }
        }
    }
    private record RuntimeToolServices(ClaudeRuntime runtime, ClaudeAgentLoop loop) implements ToolServices {

        @Override
        public String connectMcp(String name) {
            return runtime.mcpRegistry().connect(name);
        }

        @Override
        public String loadSkill(String name) {
            return runtime.skillRegistry().loadSkill(name);
        }

        @Override
        public String runSubagent(String description, Path workingDirectory, String ownerName) {
            return loop.runSubagent(description, workingDirectory, ownerName);
        }

        @Override
        public String spawnTeammate(String name, String role, String prompt, Path workingDirectory) {
            return loop.teammateManager().spawn(name, role, prompt, workingDirectory);
        }

        @Override
        public ToolTaskView createTask(String subject, String description, List<String> blockedBy) {
            return toTaskView(runtime.taskStore().create(subject, description, blockedBy));
        }

        @Override
        public List<ToolTaskView> listTasks() {
            return runtime.taskStore().list().stream().map(RuntimeToolServices::toTaskView).toList();
        }

        @Override
        public String getTaskJson(String taskId) throws java.io.IOException {
            return runtime.objectMapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(runtime.taskStore().load(taskId));
        }

        @Override
        public ToolTaskView claimTask(String taskId, String ownerName) {
            return toTaskView(runtime.taskStore().claim(taskId, ownerName));
        }

        @Override
        public ToolTaskView completeTask(String taskId) {
            return toTaskView(runtime.taskStore().complete(taskId));
        }

        @Override
        public String scheduleCron(String cron, String prompt, boolean recurring, boolean durable) {
            return runtime.cronScheduler().schedule(cron, prompt, recurring, durable);
        }

        @Override
        public String listCrons() {
            return runtime.cronScheduler().listJobs();
        }

        @Override
        public String cancelCron(String jobId) {
            return runtime.cronScheduler().cancel(jobId);
        }

        @Override
        public void sendMessage(String from, String to, String type, String content, Map<String, Object> metadata) {
            runtime.messageBus().send(from, to, type, content, metadata);
        }

        @Override
        public String drainInboxBuffer() {
            return runtime.drainInboxBuffer();
        }

        @Override
        public String requestShutdown(String teammate) {
            return runtime.protocolRegistry().requestShutdown(teammate);
        }

        @Override
        public String requestPlan(String teammate, String task) {
            return runtime.protocolRegistry().requestPlan(teammate, task);
        }

        @Override
        public String reviewPlan(String requestId, boolean approve, String feedback) {
            return runtime.protocolRegistry().reviewPlan(requestId, approve, feedback);
        }

        @Override
        public String createWorktree(String name, String taskId) {
            return runtime.worktreeService().create(name, taskId);
        }

        @Override
        public String removeWorktree(String name, boolean discardChanges) {
            return runtime.worktreeService().remove(name, discardChanges);
        }

        @Override
        public String keepWorktree(String name) {
            return runtime.worktreeService().keep(name);
        }

        private static ToolTaskView toTaskView(TaskRecord record) {
            return new ToolTaskView(record.id(), record.subject(), record.status(), record.worktree());
        }
    }
}
