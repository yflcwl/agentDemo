package com.ex.yagent.agentscope.test;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.harness.agent.HarnessAgent;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicReference;

public class planMode {
    public static void main(String[] args) {
        HarnessAgent agent = HarnessAgent.builder()
                .name("planner")
                .model("dashscope:qwen3.7-plus")
                .sysPrompt("你是一个计划模式的model")
                .workspace(Paths.get(".agentscope/workspace"))
                .enablePlanMode(true)                          // 装 PlanMode 三件套
                .planFileDirectory("plans")                             // 可选；默认 "plans"
                .build();

        RuntimeContext ctx = RuntimeContext.builder()
                .userId("yfl")
                .sessionId("plan-loop-session")
                .build();

        System.out.println("sessionId = " + ctx.getSessionId());
        System.out.println("输入问题开始多轮对话，输入 exit 结束。");

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("\n你> ");
                if (!scanner.hasNextLine()) {
                    break;
                }
                String input = scanner.nextLine();
                if (input == null || input.isBlank()) {
                    continue;
                }
                if ("exit".equalsIgnoreCase(input.trim())) {
                    break;
                }

                AtomicReference<List<ToolUseBlock>> pendingToolCalls = new AtomicReference<>(List.of());
                System.out.print("助手> ");
                agent.streamEvents(new UserMessage(input), ctx)
                        .doOnNext(event -> {
                            AgentEventType type = event.getType();
                            if (type == AgentEventType.TEXT_BLOCK_DELTA) {
                                System.out.print(((TextBlockDeltaEvent) event).getDelta());
                                return;
                            }
                            if (type == AgentEventType.REQUIRE_USER_CONFIRM) {
                                RequireUserConfirmEvent confirmEvent = (RequireUserConfirmEvent) event;
                                pendingToolCalls.set(confirmEvent.getToolCalls());
                                System.out.println("\n[confirm] replyId=" + confirmEvent.getReplyId());
                                for (ToolUseBlock toolCall : confirmEvent.getToolCalls()) {
                                    System.out.println("[confirm] tool=" + toolCall.getName()
                                            + ", id=" + toolCall.getId()
                                            + ", state=" + toolCall.getState()
                                            + ", input=" + toolCall.getInput());
                                }
                            }
                        })
                        .blockLast();
                System.out.println();

                if (pendingToolCalls.get().isEmpty()) {
                    continue;
                }

                System.out.println("[resume] 自动批准待确认工具调用，仅用于本地调试。");
                UserMessage confirmMsg = UserMessage.builder()
                        .metadata(Map.of(
                                Msg.METADATA_CONFIRM_RESULTS,
                                pendingToolCalls.get().stream()
                                        .map(toolCall -> new ConfirmResult(true, toolCall))
                                        .toList()
                        ))
                        .build();
                String resumedText = agent.call(confirmMsg, ctx)
                        .block()
                        .getTextContent();
                if (resumedText != null && !resumedText.isBlank()) {
                    System.out.println("助手> " + resumedText);
                }
            }
        }
    }
}
