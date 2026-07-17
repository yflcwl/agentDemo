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
                    if (event instanceof ToolCallStartEvent) {
                        return Flux.just("\n[子Agent开始协作]\n");
                    }
                    if (event instanceof TextBlockDeltaEvent deltaEvent) {
                        String delta = deltaEvent.getDelta();
                        streamedText.append(delta);
                        if (!sqlMarkerEmitted.get() && containsSqlSection(streamedText)) {
                            sqlMarkerEmitted.set(true);
                            return Flux.just("\n[SQL已生成]\n", delta);
                        }
                        return Flux.just(delta);
                    }
                    return Flux.empty();
                });
    }

    @GetMapping("/once")
    public String once(String message, String sessionId) {
        return analyticsAgent.call(new UserMessage(message), runtimeContext(sessionId))
                .block()
                .getTextContent();
    }

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
                .sessionId(sessionId == null || sessionId.isBlank() ? "analysis-default" : sessionId)
                .build();
    }

    private boolean containsSqlSection(StringBuilder streamedText) {
        String content = streamedText.toString();
        return content.contains("## 本次使用的 SQL") || content.contains("## 本次使用的SQL");
    }
}
