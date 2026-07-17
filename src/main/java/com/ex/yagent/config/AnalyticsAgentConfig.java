package com.ex.yagent.config;

import com.ex.yagent.analysis.AnalyticsTools;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.dashscope.DashScopeChatModel;
import io.agentscope.extensions.model.dashscope.formatter.DashScopeChatFormatter;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class AnalyticsAgentConfig {

    private static final String ANALYTICS_SYSTEM_PROMPT = """
            你是经营分析协调 Agent，负责将用户问题编排成一次完整的数据分析协作。

            你必须严格遵循以下流程：
            1. 先委派 metric-agent，对用户问题做口径识别，拿到结构化分析意图。
            2. 再委派 sql-agent，根据原问题和分析意图生成 SQL、执行查询并整理结果。
            3. 你自己只负责整合结果并输出最终答复。

            强制规则：
            - 不要跳过 metric-agent。
            - 不要自己直接调用 runReadOnlySql、getSchemaInfo 或 listSupportedMetrics。
            - 只能基于 sql-agent 返回的结果做分析，不要编造数据。
            - 如果结果为空，必须明确写出“未查到符合条件的数据”。
            - 如果用户是追问，必须继承同一 session 中上一轮的分析口径。

            最终回答必须严格只包含下面三个一级标题，顺序不可更改：
            ## 分析结论
            ## 本次使用的 SQL
            ## 关键分析步骤

            关键分析步骤中必须明确写出：
            - 已调用 metric-agent
            - 已调用 sql-agent
            - SQL 已生成并执行
            """;

    @Bean(name = "analyticsAgent")
    public HarnessAgent analyticsAgent(
            AnalyticsTools analyticsTools,
            @Value("${spring.ai.dashscope.api-key}") String apiKey
    ) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(analyticsTools);

        return HarnessAgent.builder()
                .name("coordinator")
                .sysPrompt(ANALYTICS_SYSTEM_PROMPT)
                .model(buildModel(apiKey))
                .toolkit(toolkit)
                .workspace(Path.of(".agentscope", "analytics-workspace"))
                .stateStore(new JsonFileAgentStateStore(Path.of(".agentscope", "analytics-state")))
                .compaction(CompactionConfig.builder()
                        .triggerMessages(24)
                        .keepMessages(8)
                        .build())
                .build();
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
