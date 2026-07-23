package com.ex.yagent.agentscope.demo;

import io.agentscope.core.message.*;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.agentscope.harness.agent.HarnessAgent;

import java.util.List;

public class SkillDemo {
    public static void main(String[] args) {


        // 用户消息 —— 文本
        UserMessage userText = new UserMessage("user", "怎么一天赚到1000元？");

//        new AssistantMessage()

        // 多模态用户消息
        UserMessage userMulti = new UserMessage("user", TextBlock.builder().text("怎么一天赚到1000元？").build());

        HarnessAgent agent = HarnessAgent.builder()
                .name("SkillCreator")
                .sysPrompt("你是测试的agent的demo")
                .model("dashscope:qwen3.7-plus")
//                .skillRepository(new FileSystemSkillRepository(Paths.get(".agentscope/skills/"), false))
                .middlewares(List.of(new OtelTracingMiddleware()))
                .build();

        System.out.println(agent.call(userMulti).block().getTextContent());


    }
}
