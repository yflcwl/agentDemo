package com.ex.yagent.claude;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

final class DashScopeLlmClient implements LlmClient {

    private final ClaudeConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    DashScopeLlmClient(ClaudeConfig config) {
        this(config, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build(), new ObjectMapper());
    }

    DashScopeLlmClient(ClaudeConfig config, HttpClient httpClient, ObjectMapper objectMapper) {
        this.config = config;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public LlmTurn complete(String systemPrompt, List<ClaudeMessage> messages, List<ToolDefinition> tools, int maxTokens) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .timeout(Duration.ofSeconds(120))
                .POST(HttpRequest.BodyPublishers.ofString(buildRequestBody(systemPrompt, messages, tools, maxTokens)))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("DashScope 请求失败: " + response.statusCode() + " " + response.body());
            }
            return parseTurn(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("DashScope 调用失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DashScope 调用被中断", e);
        }
    }

    String buildRequestBody(String systemPrompt, List<ClaudeMessage> messages, List<ToolDefinition> tools, int maxTokens) {
        // 这里显式把内部协议翻译成 DashScope 兼容的 OpenAI chat.completions 格式。
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", config.model());
        root.put("temperature", 0.2);
        root.put("max_tokens", maxTokens);
        ArrayNode messageNodes = root.putArray("messages");

        ObjectNode systemNode = messageNodes.addObject();
        systemNode.put("role", "system");
        systemNode.put("content", systemPrompt);

        for (ClaudeMessage message : messages) {
            appendMessageNode(messageNodes, message);
        }
        ArrayNode toolNodes = root.putArray("tools");
        for (ToolDefinition tool : tools) {
            ObjectNode toolNode = toolNodes.addObject();
            toolNode.put("type", "function");
            ObjectNode function = toolNode.putObject("function");
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.set("parameters", objectMapper.valueToTree(tool.inputSchema()));
        }
        root.put("tool_choice", "auto");
        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 DashScope 请求失败", e);
        }
    }

    private void appendMessageNode(ArrayNode messageNodes, ClaudeMessage message) {
        // TOOL 消息要被转换成 role=tool 的单独消息，这正是工具结果回灌模型的关键桥梁。
        if (message.role() == MessageRole.TOOL) {
            for (ClaudeBlock block : message.blocks()) {
                if (block instanceof ToolResultBlock toolResultBlock) {
                    ObjectNode toolNode = messageNodes.addObject();
                    toolNode.put("role", "tool");
                    toolNode.put("tool_call_id", toolResultBlock.toolUseId());
                    toolNode.put("content", toolResultBlock.content());
                }
            }
            return;
        }
        ObjectNode node = messageNodes.addObject();
        node.put("role", message.role() == MessageRole.USER ? "user" : "assistant");
        String text = message.textContent();
        if (!text.isBlank()) {
            node.put("content", text);
        } else {
            node.putNull("content");
        }
        if (message.role() == MessageRole.ASSISTANT) {
            ArrayNode toolCalls = node.putArray("tool_calls");
            for (ToolUseBlock toolUse : message.toolUses()) {
                ObjectNode toolCall = toolCalls.addObject();
                toolCall.put("id", toolUse.id());
                toolCall.put("type", "function");
                ObjectNode function = toolCall.putObject("function");
                function.put("name", toolUse.name());
                try {
                    function.put("arguments", objectMapper.writeValueAsString(toolUse.input()));
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException("序列化 tool call 参数失败", e);
                }
            }
        }
    }

    LlmTurn parseTurn(String responseBody) {
        try {
            // 这里把 DashScope 返回的 content/tool_calls 再收敛回内部块协议。
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (!choices.isArray() || choices.isEmpty()) {
                throw new IllegalStateException("DashScope 响应缺少 choices");
            }
            JsonNode first = choices.get(0);
            JsonNode message = first.path("message");
            List<ClaudeBlock> blocks = new ArrayList<>();
            String content = message.path("content").asText("");
            if (!content.isBlank()) {
                blocks.add(new TextBlock(content));
            }
            JsonNode toolCalls = message.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode toolCall : toolCalls) {
                    JsonNode function = toolCall.path("function");
                    String arguments = function.path("arguments").asText("{}");
                    Map<String, Object> input = objectMapper.readValue(arguments, objectMapper.getTypeFactory()
                            .constructMapType(Map.class, String.class, Object.class));
                    blocks.add(new ToolUseBlock(
                            toolCall.path("id").asText(),
                            function.path("name").asText(),
                            input
                    ));
                }
            }
            return new LlmTurn(blocks, first.path("finish_reason").asText("stop"));
        } catch (IOException e) {
            throw new IllegalStateException("解析 DashScope 响应失败: " + responseBody, e);
        }
    }
}
