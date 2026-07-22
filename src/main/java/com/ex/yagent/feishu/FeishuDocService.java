package com.ex.yagent.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FeishuDocService {

    private static final int MAX_CHILDREN_PER_REQUEST = 50;

    private final FeishuProperties properties;
    private final ObjectMapper objectMapper;

    private volatile String cachedToken;
    private volatile Instant tokenExpireAt = Instant.EPOCH;
    private HttpClient httpClient;

    public JsonNode listDocumentBlocks(String documentId, Integer pageSize, String pageToken) {
        String resolvedDocumentId = requireDocumentId(documentId);
        StringBuilder uri = new StringBuilder(properties.getBaseUrl())
                .append("/docx/v1/documents/")
                .append(urlEncode(resolvedDocumentId))
                .append("/blocks?page_size=")
                .append(pageSize == null ? 500 : Math.min(pageSize, 500))
                .append("&document_revision_id=-1");
        if (StringUtils.hasText(pageToken)) {
            uri.append("&page_token=").append(urlEncode(pageToken));
        }
        return send(buildAuthorizedRequest(URI.create(uri.toString())).GET().build());
    }

    public JsonNode appendMarkdown(String documentId, String parentBlockId, String markdown) {
        String resolvedDocumentId = requireDocumentId(documentId);
        List<ObjectNode> children = buildMarkdownChildren(markdown);
        if (children.isEmpty()) {
            throw new IllegalArgumentException("markdown 不能为空");
        }

        ArrayNode responses = objectMapper.createArrayNode();
        for (int start = 0; start < children.size(); start += MAX_CHILDREN_PER_REQUEST) {
            int end = Math.min(start + MAX_CHILDREN_PER_REQUEST, children.size());
            responses.add(appendChildren(resolvedDocumentId, parentBlockId, children.subList(start, end)));
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.put("documentId", resolvedDocumentId);
        result.put("batchCount", responses.size());
        result.set("responses", responses);
        return result;
    }

    List<ObjectNode> buildMarkdownChildren(String markdown) {
        if (!StringUtils.hasText(markdown)) {
            return List.of();
        }

        List<ObjectNode> children = new ArrayList<>();
        String normalized = markdown.replace("\r\n", "\n");
        String[] lines = normalized.split("\n", -1);
        int limit = lines.length;
        if (limit > 0 && lines[limit - 1].isEmpty()) {
            limit--;
        }
        for (int i = 0; i < limit; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                children.add(emptyTextBlock());
                continue;
            }
            if (line.startsWith("### ")) {
                children.add(textBlock(5, line.substring(4)));
                continue;
            }
            if (line.startsWith("## ")) {
                children.add(textBlock(4, line.substring(3)));
                continue;
            }
            if (line.startsWith("# ")) {
                children.add(textBlock(3, line.substring(2)));
                continue;
            }
            if (line.startsWith("- ") || line.startsWith("* ")) {
                children.add(textBlock(12, line.substring(2)));
                continue;
            }
            if (line.matches("^\\d+\\.\\s+.*$")) {
                int firstSpace = line.indexOf(' ');
                children.add(textBlock(13, line.substring(firstSpace + 1)));
                continue;
            }
            children.add(textBlock(2, line));
        }
        return children;
    }

    private JsonNode appendChildren(String documentId, String parentBlockId, List<ObjectNode> children) {
        String blockId = StringUtils.hasText(parentBlockId) ? parentBlockId : documentId;
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode childArray = body.putArray("children");
        children.forEach(childArray::add);

        String uri = properties.getBaseUrl()
                + "/docx/v1/documents/" + urlEncode(documentId)
                + "/blocks/" + urlEncode(blockId)
                + "/children?document_revision_id=-1&client_token=" + urlEncode(UUID.randomUUID().toString());
        HttpRequest request = buildAuthorizedRequest(URI.create(uri))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(writeJson(body)))
                .build();
        return send(request);
    }

    private HttpRequest.Builder buildAuthorizedRequest(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + getTenantAccessToken());
    }

    private String getTenantAccessToken() {
        Instant refreshAt = tokenExpireAt.minus(Duration.ofMinutes(5));
        if (StringUtils.hasText(cachedToken) && Instant.now().isBefore(refreshAt)) {
            return cachedToken;
        }
        synchronized (this) {
            refreshAt = tokenExpireAt.minus(Duration.ofMinutes(5));
            if (StringUtils.hasText(cachedToken) && Instant.now().isBefore(refreshAt)) {
                return cachedToken;
            }

            ObjectNode body = objectMapper.createObjectNode();
            body.put("app_id", requireText(properties.getAppId(), "feishu.app-id 未配置"));
            body.put("app_secret", requireText(properties.getAppSecret(), "feishu.app-secret 未配置"));

            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getBaseUrl() + "/auth/v3/tenant_access_token/internal"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(writeJson(body)))
                    .build();
            JsonNode response = send(request);
            cachedToken = response.path("tenant_access_token").asText();
            int expireSeconds = response.path("expire").asInt(7200);
            tokenExpireAt = Instant.now().plusSeconds(expireSeconds);
            return cachedToken;
        }
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("飞书接口调用失败: HTTP " + response.statusCode() + " " + response.body());
            }
            JsonNode json = objectMapper.readTree(response.body());
            if (json.path("code").asInt() != 0) {
                throw new IllegalStateException("飞书接口调用失败: code=" + json.path("code").asInt() + ", msg=" + json.path("msg").asText());
            }
            return json;
        } catch (IOException e) {
            throw new IllegalStateException("飞书接口返回解析失败", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("飞书接口调用被中断", e);
        }
    }

    private HttpClient httpClient() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();
        }
        return httpClient;
    }

    private String requireDocumentId(String documentId) {
        if (StringUtils.hasText(documentId)) {
            return documentId;
        }
        return requireText(properties.getDefaultDocumentId(), "documentId 未提供，且 feishu.default-document-id 未配置");
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private String writeJson(JsonNode body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalStateException("飞书请求体序列化失败", e);
        }
    }

    private ObjectNode textBlock(int blockType, String content) {
        ObjectNode block = objectMapper.createObjectNode();
        block.put("block_type", blockType);
        ObjectNode text = block.putObject("text");
        ArrayNode elements = text.putArray("elements");
        ObjectNode element = elements.addObject();
        element.putObject("text_run").put("content", content);
        return block;
    }

    private ObjectNode emptyTextBlock() {
        ObjectNode block = objectMapper.createObjectNode();
        block.put("block_type", 2);
        block.putObject("text").putObject("style");
        return block;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
