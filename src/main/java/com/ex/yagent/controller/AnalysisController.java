package com.ex.yagent.controller;

import com.ex.yagent.analysis.AnalyticsTools;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@RequiredArgsConstructor
@RestController
@RequestMapping("/analysis")
public class AnalysisController {

    @Qualifier("analyticsAgent")
    private final HarnessAgent analyticsAgent;

    private final AnalyticsTools analyticsTools;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public Flux<String> stream(String message, String sessionId) {
        RuntimeContext ctx = runtimeContext(sessionId);
        AtomicBoolean sqlMarkerEmitted = new AtomicBoolean(false);
        StringBuilder streamedText = new StringBuilder();

        return analyticsAgent.streamEvents(new UserMessage(message), ctx)
                .flatMap(event -> {
                    // 父 Agent 触发工具调用时，前端先给一个“子 Agent 开始协作”的阶段提示。
                    if (event instanceof ToolCallStartEvent) {
                        return Flux.just("\n[子Agent开始协作]\n");
                    }
                    if (event instanceof TextBlockDeltaEvent deltaEvent) {
                        String delta = deltaEvent.getDelta();
                        streamedText.append(delta);
                        // 一旦文本里出现 SQL 段标题，就补一个显式标记，方便前端观察阶段切换。
                        if (!sqlMarkerEmitted.get() && containsSqlSection(streamedText)) {
                            sqlMarkerEmitted.set(true);
                            return Flux.just("\n[SQL已生成]\n", delta);
                        }
                        return Flux.just(delta);
                    }
                    return Flux.empty();
                });
    }

    /**
     * 一次性返回完整分析结果，适合后端调试或直接用浏览器访问。
     */
    @GetMapping("/once")
    public String once(String message, String sessionId) {
        return analyticsAgent.call(new UserMessage(message), runtimeContext(sessionId))
                .block()
                .getTextContent();
    }

    /**
     * 暴露样例库 schema 和支持指标，便于前端初始化和人工调试。
     */
    @GetMapping("/schema")
    public Map<String, String> schema() {
        Map<String, String> response = new LinkedHashMap<>();
        response.put("schemaInfo", analyticsTools.getSchemaInfo());
        response.put("supportedMetrics", analyticsTools.listSupportedMetrics());
        return response;
    }

    private RuntimeContext runtimeContext(String sessionId) {
        return RuntimeContext.builder()
                .userId("analysis-demo")
                // sessionId 相同表示同一段分析会话，可用于多轮追问；为空时落到默认会话。
                .sessionId(sessionId == null || sessionId.isBlank() ? "analysis-default" : sessionId)
                .build();
    }

    private boolean containsSqlSection(StringBuilder streamedText) {
        String content = streamedText.toString();
        return content.contains("## 本次使用的 SQL") || content.contains("## 本次使用的SQL");
    }
}
