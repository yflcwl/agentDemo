package com.ex.yagent.claude;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public record ClaudeConfig(
        String apiKey,
        String model,
        String fallbackModel,
        String baseUrl,
        boolean interactiveApproval
) {

    /**
     * 配置优先级固定为：
     * JVM -D > 环境变量 > application-local.yml > 代码默认值。
     * 这样既能直接在命令行覆盖，也能复用项目现有的本地配置文件。
     */
    static ClaudeConfig load(Path workspaceRoot) {
        Map<String, Object> yamlConfig = readLocalYaml(workspaceRoot.resolve("src/main/resources/application-local.yml"));
        String apiKey = pick(
                System.getProperty("claude.dashscope.api-key"),
                System.getenv("DASHSCOPE_API_KEY"),
                nestedString(yamlConfig, "spring", "ai", "dashscope", "api-key")
        );
        String model = pick(
                System.getProperty("claude.dashscope.model"),
                System.getenv("DASHSCOPE_MODEL"),
                nestedString(yamlConfig, "spring", "ai", "dashscope", "chat", "options", "model"),
                "qwen3.7-plus"
        );
        String fallbackModel = pick(
                System.getProperty("claude.dashscope.fallback-model"),
                System.getenv("DASHSCOPE_FALLBACK_MODEL"),
                "qwen3.7-plus"
        );
        String baseUrl = pick(
                System.getProperty("claude.dashscope.base-url"),
                System.getenv("DASHSCOPE_BASE_URL"),
                "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        );
        boolean interactiveApproval = Boolean.parseBoolean(pick(
                System.getProperty("claude.interactive-approval"),
                System.getenv("CLAUDE_INTERACTIVE_APPROVAL"),
                String.valueOf(System.console() != null)
        ));
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("缺少 DashScope API Key，请通过 -Dclaude.dashscope.api-key 或 DASHSCOPE_API_KEY 提供");
        }
        return new ClaudeConfig(apiKey, model, fallbackModel, baseUrl, interactiveApproval);
    }

    private static Map<String, Object> readLocalYaml(Path yamlPath) {
        if (!Files.exists(yamlPath)) {
            return Map.of();
        }
        Yaml yaml = new Yaml();
        try (InputStream inputStream = Files.newInputStream(yamlPath)) {
            Object loaded = yaml.load(inputStream);
            if (loaded instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> result = (Map<String, Object>) map;
                return result;
            }
            return Map.of();
        } catch (IOException e) {
            throw new IllegalStateException("读取 application-local.yml 失败", e);
        }
    }

    private static String nestedString(Map<String, Object> source, String... keys) {
        Object current = source;
        for (String key : keys) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(key);
            if (current == null) {
                return null;
            }
        }
        return Objects.toString(current, null);
    }

    private static String pick(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
