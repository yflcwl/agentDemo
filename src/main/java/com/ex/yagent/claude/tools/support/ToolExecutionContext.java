package com.ex.yagent.claude.tools.support;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class ToolExecutionContext {

    private final ToolServices services;
    private final Path workingDirectory;
    private final String ownerName;
    private final Consumer<List<ToolTodoItem>> todoUpdater;

    public ToolExecutionContext(
            ToolServices services,
            Path workingDirectory,
            String ownerName,
            Consumer<List<ToolTodoItem>> todoUpdater
    ) {
        this.services = services;
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        this.ownerName = ownerName == null ? "" : ownerName;
        this.todoUpdater = todoUpdater;
    }

    public ToolServices services() {
        if (services == null) {
            throw new IllegalStateException("Tool services unavailable");
        }
        return services;
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    public String ownerName() {
        return ownerName;
    }

    public void replaceTodos(List<ToolTodoItem> todos) {
        if (todoUpdater == null) {
            throw new IllegalStateException("Todo updater unavailable");
        }
        todoUpdater.accept(List.copyOf(todos));
    }

    public String runSubagent(String description) {
        return services().runSubagent(description, workingDirectory, ownerName);
    }

    public String spawnTeammate(String name, String role, String prompt) {
        return services().spawnTeammate(name, role, prompt, workingDirectory);
    }
}
