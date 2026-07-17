package com.ex.yagent.agentscope;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.*;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import org.w3c.dom.Text;

import java.nio.file.Path;
import java.util.List;

public class MessageDemo {
    public static void main(String[] args) {

        // StdIO transport
        McpClientWrapper filesystem  = McpClientBuilder.create("git-mcp")
                .stdioTransport("python", "-m", "mcp_server_git")
                .buildAsync()
                .block();

        // 注册工具
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WeatherTools());
        toolkit.registerTool(filesystem);


        // 用户消息 —— 文本
        UserMessage userText = new UserMessage("user", "这张图片里有什么？");

        // 多模态用户消息
        UserMessage userMulti =
                new UserMessage(
                        "user",
                        TextBlock.builder().text("请描述今天成都今天的天气").build());
//                        DataBlock.builder()
//                                .source(Base64Source.builder()
//                                        .data("...")
//                                        .mediaType("txt")
//                                        .build())
//                                .build());

        ReActAgent agent = ReActAgent.builder()
                .name("my_agent")
                .sysPrompt("你是一个用于回答任何问题的助手。")
                .model("dashscope:qwen-plus")
                .toolkit(toolkit) //将工具属性注入
                .middlewares(List.of(new OtelTracingMiddleware()))
                .stateStore(new JsonFileAgentStateStore(Path.of(".agentscope/state.json", "workspace")))
                .build();

        Msg block = agent.call(userMulti).block();
        List<ContentBlock> content = block.getContent();
        TextBlock first = (TextBlock) content.getFirst();

        RuntimeContext ctx =
                RuntimeContext.builder()
                        .userId("alice")                           // 可选；null 表示匿名
                        .sessionId("session-001")                  // 选择状态槽位
                        .put("request_id", "req-abc-123")          // 字符串层
                        .put(UserContext.class, new UserContext("alice", "en"))  // 类型层（业务 POJO）
                        .build();

        System.out.println(block.getTextContent());

        // 系统消息 —— 仅文本
        SystemMessage systemMsg = new SystemMessage("system", "你是一个有用的助手。");

        // 助手消息 —— 允许所有块类型
        AssistantMessage assistantMsg = new AssistantMessage("agent", "结果如下...");

    }
}
