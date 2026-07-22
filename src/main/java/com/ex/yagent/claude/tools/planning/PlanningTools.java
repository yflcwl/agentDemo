package com.ex.yagent.claude.tools.planning;

import com.ex.yagent.claude.tools.support.AbstractClaudeTools;
import com.ex.yagent.claude.tools.support.ClaudeTool;
import com.ex.yagent.claude.tools.support.ToolExecutionContext;
import com.ex.yagent.claude.tools.support.ToolExecutionResult;
import com.ex.yagent.claude.tools.support.ToolTodoItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PlanningTools extends AbstractClaudeTools {

    @ClaudeTool(name = "todo_write", description = "为当前会话创建并维护任务清单。", schemaMethod = "todoWriteSchema")
    ToolExecutionResult todoWrite(Map<String, Object> input, ToolExecutionContext context) {
        Object todosValue = input.get("todos");
        if (!(todosValue instanceof List<?> todoList)) {
            return ToolExecutionResult.of("错误：todos 必须是列表");
        }
        List<ToolTodoItem> todos = new ArrayList<>();
        for (Object item : todoList) {
            if (!(item instanceof Map<?, ?> todoMap)) {
                return ToolExecutionResult.of("错误：每个 todo 都必须是对象");
            }
            String content = asString(todoMap.get("content"));
            String status = asString(todoMap.get("status"));
            if (!List.of("pending", "in_progress", "completed").contains(status)) {
                return ToolExecutionResult.of("错误：非法的 todo 状态 " + status);
            }
            todos.add(new ToolTodoItem(content, status));
        }
        context.replaceTodos(todos);
        return ToolExecutionResult.of("已更新 " + todos.size() + " 条 todo");
    }

    @ClaudeTool(name = "task", description = "启动一个聚焦型子代理，只返回最终摘要。", schemaMethod = "taskSchema", hiddenInSubagent = true)
    ToolExecutionResult task(Map<String, Object> input, ToolExecutionContext context) {
        return ToolExecutionResult.of(context.runSubagent(asString(input.get("description"))));
    }

    @ClaudeTool(name = "load_skill", description = "按名称加载某个 skill 的完整内容。", schemaMethod = "loadSkillSchema")
    ToolExecutionResult loadSkill(Map<String, Object> input, ToolExecutionContext context) {
        return ToolExecutionResult.of(context.services().loadSkill(asString(input.get("name"))));
    }

    Map<String, Object> todoWriteSchema() {
        return schema(Map.of("todos", Map.of("type", "array")), List.of("todos"));
    }

    Map<String, Object> taskSchema() {
        return schema(Map.of("description", type("string")), List.of("description"));
    }

    Map<String, Object> loadSkillSchema() {
        return schema(Map.of("name", type("string")), List.of("name"));
    }
}
