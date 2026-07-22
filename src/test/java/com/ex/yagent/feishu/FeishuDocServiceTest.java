package com.ex.yagent.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FeishuDocServiceTest {

    @Test
    void shouldConvertMarkdownLinesToFeishuBlocks() {
        FeishuProperties properties = new FeishuProperties();
        FeishuDocService service = new FeishuDocService(properties, new ObjectMapper().findAndRegisterModules());

        List<?> children = service.buildMarkdownChildren("""
                # 一级标题
                ## 二级标题
                - 列表项
                1. 有序项

                普通文本
                """);

        assertEquals(6, children.size());
        assertEquals(3, ((com.fasterxml.jackson.databind.JsonNode) children.get(0)).path("block_type").asInt());
        assertEquals(4, ((com.fasterxml.jackson.databind.JsonNode) children.get(1)).path("block_type").asInt());
        assertEquals(12, ((com.fasterxml.jackson.databind.JsonNode) children.get(2)).path("block_type").asInt());
        assertEquals(13, ((com.fasterxml.jackson.databind.JsonNode) children.get(3)).path("block_type").asInt());
        assertEquals(2, ((com.fasterxml.jackson.databind.JsonNode) children.get(4)).path("block_type").asInt());
        assertEquals(2, ((com.fasterxml.jackson.databind.JsonNode) children.get(5)).path("block_type").asInt());
    }
}
