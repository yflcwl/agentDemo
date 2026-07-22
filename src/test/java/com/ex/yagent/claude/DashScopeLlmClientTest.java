package com.ex.yagent.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DashScopeLlmClientTest {

    /**
     * 这个测试验证：内部消息和工具定义，能否被正确翻译成 DashScope 兼容请求体。
     */
    @Test
    void shouldBuildCompatibleRequestBody() {
        ClaudeConfig config = new ClaudeConfig("test-key", "qwen-test", "fallback", "https://example.com", false);
        DashScopeLlmClient client = new DashScopeLlmClient(config, HttpClient.newHttpClient(), new ObjectMapper().findAndRegisterModules());

        String body = client.buildRequestBody(
                "system",
                List.of(
                        ClaudeMessage.userText("hello"),
                        ClaudeMessage.assistant(List.of(new ToolUseBlock("tool-1", "bash", Map.of("command", "dir")))),
                        ClaudeMessage.toolResult("tool-1", "ok")
                ),
                List.of(new ToolDefinition("bash", "Run shell", Map.of("type", "object"))),
                4096
        );

        assertTrue(body.contains("\"model\":\"qwen-test\""));
        assertTrue(body.contains("\"tool_calls\""));
        assertTrue(body.contains("\"tool_call_id\":\"tool-1\""));
    }

    /**
     * 这个测试验证：DashScope 返回的 text + tool_calls 能否还原成统一的内部块协议。
     */
    @Test
    void shouldParseMixedAssistantTurn() {
        ClaudeConfig config = new ClaudeConfig("test-key", "qwen-test", "fallback", "https://example.com", false);
        DashScopeLlmClient client = new DashScopeLlmClient(config, HttpClient.newHttpClient(), new ObjectMapper().findAndRegisterModules());

        String response = """
                {
                  "choices": [
                    {
                      "finish_reason": "tool_calls",
                      "message": {
                        "content": "I will inspect the file.",
                        "tool_calls": [
                          {
                            "id": "call_123",
                            "type": "function",
                            "function": {
                              "name": "read_file",
                              "arguments": "{\\"path\\":\\"README.md\\"}"
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
                """;

        LlmTurn turn = client.parseTurn(response);

        assertEquals("tool_calls", turn.finishReason());
        assertTrue(turn.hasToolUse());
        assertEquals(2, turn.blocks().size());
        assertEquals("I will inspect the file.", ((TextBlock) turn.blocks().get(0)).text());
        assertEquals("read_file", ((ToolUseBlock) turn.blocks().get(1)).name());
    }
}
