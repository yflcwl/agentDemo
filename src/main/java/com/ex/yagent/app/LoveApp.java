package com.ex.yagent.app;

import com.ex.yagent.advisor.MyLoggerAdvisor;
import com.ex.yagent.advisor.ReReadingAdvisor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class LoveApp {

    private final ChatClient chatClient;

    private static final String SYSTEM_PROJECT = "扮演深耕恋爱心理领域的专家。开场向用户表明身份，告知用户可倾诉恋爱难题。" +
            "围绕单身、恋爱、已婚三种状态提问：单身状态询问社交圈拓展及追求心仪对象的困扰；" +
            "恋爱状态询问沟通、习惯差异引发的矛盾；已婚状态询问家庭责任与亲属关系处理的问题。" +
            "引导用户详诉事情的经过、对方反应及自身想法，以便给出专属解决方案";

    /**
     * 初始化
     * @param dashscopeChatClient
     */
    public LoveApp(ChatClient.Builder dashscopeChatClient) {
        //初始化基于内存的对话记忆
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory
                .builder()
                .maxMessages(10)
                .build();

        chatClient = dashscopeChatClient
                .defaultSystem(SYSTEM_PROJECT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new MyLoggerAdvisor(),
                        new ReReadingAdvisor()
                )
                .build();
    }

    /**
     * AI基础对话（支持多轮记忆）
     * @param message
     * @param chatId
     * @return
     */
    public String doChat(String message, String chatId) {

        String content = chatClient.prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .content();
        log.info("ai回复:{}", content);
        return content;
    }

    record LoveReport(String title, List<String> suggestions) {

    }

    /**
     * AI恋爱报告功能(结构化输出)
     * @param message
     * @param chatId
     * @return
     */
    public LoveReport doChatWithReport(String message, String chatId) {

        LoveReport loveReport = chatClient.prompt()
                .user(message)
                .system(SYSTEM_PROJECT + "每次对话后都要生成恋爱报告，标题为{用户名}的恋爱报告，内容为建议列表")
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, chatId))
                .call()
                .entity(LoveReport.class);
        log.info("loveReport:{}", loveReport);
        return loveReport;
    }

}
