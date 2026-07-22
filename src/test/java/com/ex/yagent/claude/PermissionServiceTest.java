package com.ex.yagent.claude;

import com.ex.yagent.claude.tools.support.ToolExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionServiceTest {

    @TempDir
    Path tempDir;

    /**
     * 非交互模式下，危险命令应该直接拒绝，而不是等待控制台确认。
     */
    @Test
    void shouldBlockDangerousShellCommand() {
        PermissionService permissionService = new PermissionService(
                tempDir,
                new ClaudeConfig("k", "m", "f", "https://example.com", false),
                new ClaudeConsole()
        );

        var denied = permissionService.check(
                new ToolUseBlock("1", "bash", Map.of("command", "git reset --hard")),
                new ToolExecutionContext(null, tempDir, "", null)
        );

        assertTrue(denied.isPresent());
        assertTrue(denied.get().contains("deny list"));
    }

    /**
     * 越界写路径应该在 permission 阶段就被挡掉。
     */
    @Test
    void shouldRejectPathTraversal() {
        PermissionService permissionService = new PermissionService(
                tempDir,
                new ClaudeConfig("k", "m", "f", "https://example.com", false),
                new ClaudeConsole()
        );

        var denied = permissionService.check(
                new ToolUseBlock("2", "write_file", Map.of("path", "../secret.txt", "content", "x")),
                new ToolExecutionContext(null, tempDir, "", null)
        );

        assertTrue(denied.isPresent());
        assertTrue(denied.get().contains("path traversal"));
    }
}
