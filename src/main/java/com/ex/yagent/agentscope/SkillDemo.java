package com.ex.yagent.agentscope;

import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.agentscope.harness.agent.HarnessAgent;

import java.nio.file.Paths;
import java.util.List;

public class SkillDemo {
    public static void main(String[] args) {
        HarnessAgent agent = HarnessAgent.builder()
                .name("SkillCreator")
                .sysPrompt("你是测试skill的agent的demo")
                .model("dashscope:qwen-plus")
                .skillRepository(new FileSystemSkillRepository(Paths.get(".agentscope/skills/"), false))
                .middlewares(List.of(new OtelTracingMiddleware()))
                .build();
    }
}
