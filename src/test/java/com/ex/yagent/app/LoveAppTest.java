package com.ex.yagent.app;

import cn.hutool.core.lang.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class LoveAppTest {

    @Autowired
    private LoveApp loveApp;

    @Test
    void doChat() {
        String chatId = UUID.randomUUID().toString();
        //1
        String message = "你好，我是张三";
        String answer = loveApp.doChat(message,chatId);
        loveApp.doChat(message,chatId);
        //2
        message = "我想要我更有钱,我的对象是123";
        answer = loveApp.doChat(message,chatId);
        Assertions.assertNotNull(answer);
        //3
        message = "我是谁，我的对象是谁，帮我回忆一下";
        answer = loveApp.doChat(message,chatId);
        Assertions.assertNotNull(answer);
    }

    @Test
    void doChatWithReport() {
        String chatId = UUID.randomUUID().toString();
        String message = "你好，我是实习生yfl，我想要我的Java技术更好，我应该怎么做";
        loveApp.doChatWithReport(message,chatId);
    }
}