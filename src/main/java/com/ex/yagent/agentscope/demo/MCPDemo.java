package com.ex.yagent.agentscope.demo;


import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.agentscope.core.tracing.OtelTracingMiddleware;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;


public class MCPDemo {
    public static void main(String[] args) {

        final String msg = "请调用金融数据工具，分析今天标普500指数的走势，并说明数据时间、涨跌幅和主要影响因素。";


        // StdIO transport
        McpClientWrapper filesystem  = McpClientBuilder.create("git-mcp")
                .stdioTransport("python", "-m", "mcp_server_git")
                .buildAsync()
                .block();


        McpClientWrapper client = McpClientBuilder.create("AliyunBailianMCP_market-cmapi00073529")
                .streamableHttpTransport("https://dashscope.aliyuncs.com/api/v1/mcps/market-cmapi00073529/mcp")
                .header("Authorization", "Bearer " + System.getenv("DASHSCOPE_API_KEY"))
                .timeout(Duration.ofSeconds(60))
                .buildAsync()
                .block();

        // 注册工具
        Toolkit toolkit = new Toolkit();
//        toolkit.registerMcpClient(client).block();


        ReActAgent agent = ReActAgent.builder()
                .name("my_agent")
                .sysPrompt("你是金融分析助手。回答金融数据问题时，必须优先调用已注册的 MCP 金融工具获取最新数据，再基于工具结果进行分析。")
                .model("dashscope:qwen-plus")
                .toolkit(toolkit) //将工具属性注入
                .middlewares(List.of(new OtelTracingMiddleware()))
                .stateStore(new JsonFileAgentStateStore(Path.of(".agentscope", "state")))
                .build();

        RuntimeContext ctx = RuntimeContext.builder()
                .userId("yfl")
                .sessionId("financial-analysis")
                .build();

        /*agent.streamEvents(new UserMessage(msg), ctx)
                .doOnNext(event -> {
                    System.out.println("[event] " + event.getType());

                    if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
                        System.out.print(((TextBlockDeltaEvent) event).getDelta());
                    } else if (event.getType() == AgentEventType.TOOL_CALL_START) {
                        System.out.println("\n[tool] " + ((ToolCallStartEvent) event).getToolCallId());
                    }
                })
                .blockLast();*/


    }


}
