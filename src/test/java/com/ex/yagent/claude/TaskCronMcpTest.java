package com.ex.yagent.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskCronMcpTest {

    @TempDir
    Path tempDir;

    /**
     * 任务系统最关键的三个动作：创建、claim、complete。
     */
    @Test
    void shouldCreateClaimAndCompleteTask() {
        TaskStore taskStore = new TaskStore(tempDir.resolve("tasks"), new ObjectMapper().findAndRegisterModules());
        try {
            Files.createDirectories(tempDir.resolve("tasks"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        TaskRecord task = taskStore.create("Inspect README", "Read repository docs", List.of());
        assertEquals("pending", taskStore.load(task.id()).status());

        TaskRecord claimed = taskStore.claim(task.id(), "alice");
        assertEquals("in_progress", claimed.status());

        TaskRecord completed = taskStore.complete(task.id());
        assertEquals("completed", completed.status());
    }

    /**
     * cron 校验和匹配逻辑必须是确定的，否则调试自动触发会非常痛苦。
     */
    @Test
    void shouldValidateAndMatchCronExpression() {
        CronSchedulerService cronScheduler = new CronSchedulerService(tempDir.resolve("cron"), new ObjectMapper().findAndRegisterModules(), new ClaudeConsole());
        try {
            Files.createDirectories(tempDir.resolve("cron"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertEquals("Cron must have 5 fields", cronScheduler.validate("* * * *"));
        assertTrue(cronScheduler.matches("30 9 * * *", LocalDateTime.of(2026, 7, 21, 9, 30)));
        assertFalse(cronScheduler.matches("30 9 * * *", LocalDateTime.of(2026, 7, 21, 9, 31)));
        cronScheduler.stop();
    }

    /**
     * MCP teaching version 至少要验证一件事：连接前不可见，连接后工具池出现。
     */
    @Test
    void shouldExposeMockMcpToolsAfterConnect() {
        McpRegistry registry = new McpRegistry();

        assertTrue(registry.connectedToolDefinitions().isEmpty());
        assertEquals("Connected MCP server: docs", registry.connect("docs"));
        assertTrue(registry.connectedToolDefinitions().containsKey("mcp__docs__search"));
    }
}
