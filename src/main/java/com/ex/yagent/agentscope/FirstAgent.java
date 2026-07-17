package com.ex.yagent.agentscope;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.nio.file.Paths;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;

public class FirstAgent {

    private final static String msg = "我今天很开心";


    // 系统提示词
    private static final String SYSTEM_PROMPT =  "你是一个帮助用户做笔记的助手。";

    public static void main(String[] args) {
//        call();
//        stream();


    }

    //流式
    private static void stream() {
        HarnessAgent agent = HarnessAgent.builder()
                .name("note-taker")
                .sysPrompt(SYSTEM_PROMPT)
                // 字符串形式由 ModelRegistry 解析 —— 自动读取 DASHSCOPE_API_KEY；
                // 切换其他厂商时改用 "openai:gpt-5.5"、"anthropic:claude-sonnet-4-5"、
                // "gemini:gemini-2.0-flash" 或 "ollama:llama3"。
                .model("dashscope:qwen-plus")
                .workspace(Paths.get(".agentscope/workspace"))
                .compaction(CompactionConfig.builder()
                        .triggerMessages(30)
                        .keepMessages(10)
                        .build())
                .build();

        agent.streamEvents(new UserMessage("帮我把今天的关键点列三条。"))
                .doOnNext(event -> {
                    if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
                        // 模型返回的流式文本片段 —— 追加到界面或标准输出
                        System.out.print(((TextBlockDeltaEvent) event).getDelta());
                    } else if (event.getType() == AgentEventType.TOOL_CALL_START) {
                        // 智能体即将调用工具 —— 展示调用信息
                        System.out.println("\n[tool] " + ((ToolCallStartEvent) event).getToolCallName());
                    }
                    // 其他事件：思考块、工具结果、回复结束等
                })
                .blockLast();
    }

    private static void call() {
        HarnessAgent agent = HarnessAgent.builder()
                .name("note-taker")
                .sysPrompt(SYSTEM_PROMPT)
                // 字符串形式由 ModelRegistry 解析 —— 自动读取 DASHSCOPE_API_KEY；
                // 切换其他厂商时改用 "openai:gpt-5.5"、"anthropic:claude-sonnet-4-5"、
                // "gemini:gemini-2.0-flash" 或 "ollama:llama3"。
                .model("dashscope:qwen-plus")
                .workspace(Paths.get(".agentscope/workspace"))
                .stateStore(new JsonFileAgentStateStore(
                        Paths.get(".agentscope/state")
                ))
                .compaction(CompactionConfig.builder()
                        .triggerMessages(30)
                        .keepMessages(10)
                        .build())
                .build();

        AgentState build = AgentState.builder()
                .userId("aa")
                .sessionId("cc")
                .build();

        // 运行的对话
        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("demo-session") //会话
                .userId("yfl")
                .agentState(build)
                .build();

        agent.call(msg, RuntimeContext.builder()
                .sessionId("alice-1").userId("alice").build()).block();

        agent.call(msg, RuntimeContext.builder()
                .sessionId("bob-1").userId("bob").build()).block();

//        // 第一轮：自我介绍 + 当天的事
//        agent.call(new UserMessage("我叫yfl，今天准备一个关于 ReAct 的技术分享。"), ctx).block();
//
//        // 第二轮：同 sessionId，自动恢复上一轮状态后回答
//        agent.call(new UserMessage("我叫什么？我今天要干什么？"), ctx).block();
    }

    private static void writeState() {

        // 注册工具
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WeatherTools());

        HarnessAgent agent = HarnessAgent.builder()
                .name("note-taker")
                .sysPrompt(SYSTEM_PROMPT)
                // 字符串形式由 ModelRegistry 解析 —— 自动读取 DASHSCOPE_API_KEY；
                // 切换其他厂商时改用 "openai:gpt-5.5"、"anthropic:claude-sonnet-4-5"、
                // "gemini:gemini-2.0-flash" 或 "ollama:llama3"。
                .model("dashscope:qwen-plus")
                .toolkit(toolkit)
                .workspace(Paths.get(".agentscope/workspace"))
                .compaction(CompactionConfig.builder()
                        .triggerMessages(30)
                        .keepMessages(10)
                        .build())
                .build();

//        AgentState state = agent.getAgentState("alice", "session-001");
//        System.out.println("messages: " + state.getContext().size());

//        String json = state.toJson();
//        AgentState restored = AgentState.fromJsonString(json);
    }
}