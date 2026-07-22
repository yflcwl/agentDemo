package com.ex.yagent.claude;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

record ClaudePaths(
        Path root,
        Path tasksDir,
        Path mailboxesDir,
        Path worktreesDir,
        Path memoryDir,
        Path transcriptsDir,
        Path toolResultDir,
        Path cronDir
) {

    /**
     * 所有运行时副作用统一收敛到 .claude-java 下。
     * 这保证了教学代码不会把中间产物写回源码目录。
     */
    static ClaudePaths init(Path workspaceRoot) {
        Path root = workspaceRoot.resolve(".claude-java");
        ClaudePaths paths = new ClaudePaths(
                root,
                root.resolve("tasks"),
                root.resolve("mailboxes"),
                root.resolve("worktrees"),
                root.resolve("memory"),
                root.resolve("transcripts"),
                root.resolve("task-outputs").resolve("tool-results"),
                root.resolve("cron")
        );
        paths.ensureDirectories();
        return paths;
    }

    void ensureDirectories() {
        create(root);
        create(tasksDir);
        create(mailboxesDir);
        create(worktreesDir);
        create(memoryDir);
        create(transcriptsDir);
        create(toolResultDir);
        create(cronDir);
    }

    private void create(Path path) {
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new IllegalStateException("创建运行目录失败: " + path, e);
        }
    }
}
