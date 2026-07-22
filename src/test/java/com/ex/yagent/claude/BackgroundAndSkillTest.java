package com.ex.yagent.claude;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundAndSkillTest {

    @TempDir
    Path tempDir;

    /**
     * 后台任务完成后，结果必须能被轮询到，否则 loop 就无法收到 task_notification。
     */
    @Test
    void shouldCollectBackgroundTaskResult() throws Exception {
        BackgroundTaskService service = new BackgroundTaskService(new ClaudeConsole());
        service.submit("demo", () -> "background done");

        TimeUnit.MILLISECONDS.sleep(300);
        var notifications = service.drainNotifications();

        assertFalse(notifications.isEmpty());
        assertTrue(notifications.get(0).message().contains("background done"));
        service.stop();
    }

    /**
     * skills catalog 是 prompt 组装的一部分，默认资源必须能被扫描到。
     */
    @Test
    void shouldLoadBundledSkills() {
        SkillRegistry registry = new SkillRegistry();
        registry.scan();

        assertTrue(registry.names().contains("agent-builder"));
        assertTrue(registry.loadSkill("code-review").contains("Code Review"));
    }
}
