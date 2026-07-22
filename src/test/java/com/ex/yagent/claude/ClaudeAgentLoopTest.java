package com.ex.yagent.claude;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaudeAgentLoopTest {

    @TempDir
    Path tempDir;

    private ClaudeRuntime runtime;

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.shutdown();
        }
    }

    /**
     * 这个测试验证最简单的情形：模型不调用任何工具时，loop 应该直接结束。
     */
    @Test
    void shouldReturnTextWhenModelDoesNotUseTools() {
        runtime = new ClaudeRuntime(tempDir, new ClaudeConfig("k", "m", "f", "https://example.com", false), new ClaudeConsole());
        ClaudeAgentLoop loop = new ClaudeAgentLoop(runtime, new ScriptedLlmClient(
                new LlmTurn(List.of(new TextBlock("final answer")), "stop")
        ));
        ClaudeSession session = new ClaudeSession("lead", ToolMode.LEAD, tempDir);

        String answer = loop.runUserTurn(session, "say hi");

        assertEquals("final answer", answer);
        assertEquals(2, session.messages().size());
    }

    /**
     * 这个测试验证：工具调用 -> tool_result 回灌 -> 下一轮继续 -> 最终回答，这条核心链能走通。
     */
    @Test
    void shouldContinueAfterToolUseUntilFinalAnswer() {
        runtime = new ClaudeRuntime(tempDir, new ClaudeConfig("k", "m", "f", "https://example.com", false), new ClaudeConsole());
        ClaudeAgentLoop loop = new ClaudeAgentLoop(runtime, new ScriptedLlmClient(
                new LlmTurn(List.of(new ToolUseBlock("todo-1", "todo_write", Map.of(
                        "todos", List.of(Map.of("content", "Inspect repo", "status", "in_progress"))
                ))), "tool_calls"),
                new LlmTurn(List.of(new TextBlock("done")), "stop")
        ));
        ClaudeSession session = new ClaudeSession("lead", ToolMode.LEAD, tempDir);

        String answer = loop.runUserTurn(session, "plan first");

        assertEquals("done", answer);
        assertEquals(1, session.todos().size());
        assertEquals("Inspect repo", session.todos().get(0).content());
        assertTrue(session.messages().stream().anyMatch(message -> message.role() == MessageRole.TOOL));
    }

    /**
     * ScriptedLlmClient 用队列脚本替代真实模型，方便确定性测试主 loop。
     */
    private static final class ScriptedLlmClient implements LlmClient {

        private final Deque<LlmTurn> turns;

        private ScriptedLlmClient(LlmTurn... turns) {
            this.turns = new ArrayDeque<>(List.of(turns));
        }

        @Override
        public LlmTurn complete(String systemPrompt, List<ClaudeMessage> messages, List<ToolDefinition> tools, int maxTokens) {
            return turns.removeFirst();
        }
    }
}
