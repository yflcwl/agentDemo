package com.ex.yagent.claude.tools.support;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public abstract class AbstractClaudeTools {

    protected Map<String, Object> schema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    protected Map<String, Object> type(String type) {
        return Map.of("type", type);
    }

    protected Path safePath(Path workingDirectory, String relativePath) {
        Path resolved = workingDirectory.resolve(relativePath).normalize();
        if (!resolved.startsWith(workingDirectory.normalize())) {
            throw new IllegalArgumentException("Path escapes workspace: " + relativePath);
        }
        return resolved;
    }

    protected boolean asBoolean(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(Objects.toString(value, "false"));
    }

    protected int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(Objects.toString(value, "0"));
    }

    protected String asString(Object value) {
        return Objects.toString(value, "");
    }
}
