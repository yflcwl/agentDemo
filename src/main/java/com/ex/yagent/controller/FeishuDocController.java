package com.ex.yagent.controller;

import com.ex.yagent.feishu.FeishuDocService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/feishu/documents")
public class FeishuDocController {

    private final FeishuDocService feishuDocService;

    @GetMapping("/blocks")
    public JsonNode listBlocks(String documentId, Integer pageSize, String pageToken) {
        return feishuDocService.listDocumentBlocks(documentId, pageSize, pageToken);
    }

    @PostMapping("/append-markdown")
    public JsonNode appendMarkdown(@RequestBody AppendMarkdownRequest request) {
        return feishuDocService.appendMarkdown(request.documentId(), request.parentBlockId(), request.markdown());
    }

    public record AppendMarkdownRequest(String documentId, String parentBlockId, String markdown) {
    }
}
