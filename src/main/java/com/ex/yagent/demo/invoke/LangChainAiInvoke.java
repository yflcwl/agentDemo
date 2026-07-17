package com.ex.yagent.demo.invoke;

import dev.langchain4j.community.model.dashscope.QwenChatModel;

public class LangChainAiInvoke {
    public static void main(String[] args) {
        QwenChatModel qwenChatModel = QwenChatModel.builder()
                .apiKey("sk-186c89c3c8af4aeb863e1d6754d8d459")
                .modelName("qwen3.7-max")
                .build();
        String chat = qwenChatModel.chat("你好，晚上好");
        System.out.println(chat);
    }
}
