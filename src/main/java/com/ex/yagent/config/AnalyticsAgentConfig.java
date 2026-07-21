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

    /**
     * 主控 Agent 的系统提示词。
     * 这里把多智能体协作顺序、输出结构和约束一次性写死，保证第一版 Demo 稳定可观察。
     */
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
        // 这些工具由主控 Agent 和子 Agent 共用，负责 schema 查询、指标说明和只读 SQL 执行。
        toolkit.registerTool(analyticsTools);

        return HarnessAgent.builder()
                .name("coordinator")
                .sysPrompt(ANALYTICS_SYSTEM_PROMPT)
                .model(buildModel(apiKey))
                .toolkit(toolkit)
                // 子 Agent 声明文件放在 workspace/subagents 下，HarnessAgent 启动时会自动加载。
                .workspace(Path.of(".agentscope", "analytics-workspace"))
                // 每个 session 的分析上下文单独落盘，方便多轮追问时恢复状态。
                .stateStore(new JsonFileAgentStateStore(Path.of(".agentscope", "analytics-state")))
                // 控制长会话的上下文膨胀，避免多轮追问后 prompt 无限增长。
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
