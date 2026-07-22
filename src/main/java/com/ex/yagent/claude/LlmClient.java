package com.ex.yagent.claude;

import java.util.List;

interface LlmClient {

    LlmTurn complete(String systemPrompt, List<ClaudeMessage> messages, List<ToolDefinition> tools, int maxTokens);
}
