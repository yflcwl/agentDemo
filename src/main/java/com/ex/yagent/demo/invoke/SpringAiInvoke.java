package com.ex.yagent.demo.invoke;

import jakarta.annotation.Resource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Springai框架调用ai大模型
 */
@Component
@ConditionalOnProperty(
        name = {"spring.ai.dashscope.enabled", "yagent.demo.spring-ai-runner.enabled"},
        havingValue = "true"
)
public class SpringAiInvoke implements CommandLineRunner {
    @Resource
    private ChatModel dashscopeChatModel;

    @Override
    public void run(String... args) throws Exception {
        AssistantMessage assistantMessage = dashscopeChatModel.call(new Prompt("你好，晚上好"))
                .getResult()
                .getOutput();
        System.out.println(assistantMessage.getText());
    }
}
