package com.ex.yagent.claude;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShellSupportTest {

    @Test
    void shouldTimeoutLongRunningCommand() {
        ShellResult result = ShellSupport.runCommand(
                new ProcessBuilderArgs(Path.of(System.getProperty("user.dir")),
                        List.of("powershell", "-NoProfile", "-Command", "Start-Sleep -Seconds 2")),
                1
        );

        assertFalse(result.success());
        assertTrue(result.output().contains("Timeout"));
    }
}
