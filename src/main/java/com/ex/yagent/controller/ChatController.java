package com.ex.yagent.controller;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.UserMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RequiredArgsConstructor
@RestController
public class ChatController {


    private final ReActAgent reActAgent;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public Flux<String> stream(String message, String sessionId) {
        return reActAgent.streamEvents(
                new UserMessage(message),
                RuntimeContext.builder()
                        .userId("test1")
                        .sessionId(sessionId)
                        .build()
        ).filter(agentEvent -> agentEvent instanceof TextBlockDeltaEvent)
                .map(agentEvent -> ((TextBlockDeltaEvent)agentEvent).getDelta());
    }

    @GetMapping("/intercept")
    public void intercept(String sessionId) {
        reActAgent.interrupt(RuntimeContext.builder()
                .userId("test1")
                .sessionId(sessionId)
                .build(), new UserMessage("用户已取消操作"));
    }
}
