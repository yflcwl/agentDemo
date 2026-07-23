package com.ex.yagent.agentscope.demo;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tracing.OtelTracingMiddleware;

import java.nio.file.Path;
import java.util.List;

public class ReActAgentDemo {
    public static void main(String[] args) {

        // 注册工具
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WeatherTools());

        // 权限
        PermissionContextState permCtx = PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT)
                .build();

        ReActAgent agent = ReActAgent.builder()
                .name("my_agent")
                .sysPrompt("你是一个用于回答任何问题的助手。")
                // 由 ModelRegistry 解析；自动读取 DASHSCOPE_API_KEY
                // 切换其他厂商时改成 "openai:gpt-5.5" / "anthropic:claude-sonnet-4-5"
                // / "gemini:gemini-2.0-flash" / "ollama:llama3" 即可。
                .model("dashscope:qwen-plus") //defaultSessionId默认为modelname
                .toolkit(toolkit) //将工具属性注入
                .middlewares(List.of(new OtelTracingMiddleware()))
                .stateStore(new JsonFileAgentStateStore(Path.of(".agentscope/state.json", "workspace")))
                .permissionContext(permCtx)
                .maxIters(10)
                .build();


        agent.call(List.of(new UserMessage("你好")),
                RuntimeContext.builder().userId("alice").sessionId("session-1").build()).block();

        agent.call(List.of(new UserMessage("Hi there")),
                RuntimeContext.builder().userId("bob").sessionId("session-2").build()).block();

        System.out.println(agent.call(new UserMessage("四川的天气怎么样？")).block().getTextContent());



    }
}

