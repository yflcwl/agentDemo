package com.ex.yagent.agentscope.demo;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class subAgent {
    public static void main(String[] args) {


        HarnessAgent build = HarnessAgent.builder()
                .name("orchestrator")
                .model("dashscope:qwen3.7-plus")
                .workspace(".agentscope/workspace")
                .subagent(SubagentDeclaration.builder()
                        .name("reviewer")
                        .description("代码审查专家")
                        .workspace(Path.of("./defs/reviewer"))
                        .workspaceMode(WorkspaceMode.ISOLATED)
                        .model("qwen3-max")
                        .steps(8)
                        .tools(List.of("read_file", "grep_files"))
                        .build())
                .subagent(SubagentDeclaration.builder()
                        .name("remote-researcher")
                        .description("远端调研子 agent")
                        .url("http://agent-task-server:8080")     // 远程子 agent
                        .headers(Map.of("Authorization", "Bearer xxx"))
                        .build())
                .build();


        String reply = build.call(
                new UserMessage("请帮我审查一下项目中的 Main.java 代码，并调研业界最新的类似实践。"),
                RuntimeContext.empty() // 或指定 sessionId
        ).block().getTextContent();

        System.out.println(reply);
    }
}
