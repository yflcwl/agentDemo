package com.ex.yagent.config;

import com.ex.yagent.agentscope.WeatherTools;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;

@Configuration
public class AgentConfig {

    @Bean
    public ReActAgent reActAgent(@Value("${spring.ai.dashscope.api-key}") String apiKey) {
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
                .model(buildModel(apiKey))
                .defaultSessionId("default_seesion_id")
                .toolkit(toolkit) //将工具属性注入
                .middlewares(List.of(new OtelTracingMiddleware()))
                .stateStore(new JsonFileAgentStateStore(Path.of(".agentscope/state.json", "workspace")))
                .permissionContext(permCtx)
                .maxIters(10)
                .build();

        return agent;
    }

    private DashScopeChatModel buildModel(String apiKey) {
        return DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName("qwen-plus")
                .stream(true)
                .formatter(new DashScopeChatFormatter())
                .build();
    }
}
