package com.ex.yagent.claude.tools.file;

import com.ex.yagent.claude.tools.support.AbstractClaudeTools;
import com.ex.yagent.claude.tools.support.ClaudeTool;
import com.ex.yagent.claude.tools.support.ToolExecutionContext;
import com.ex.yagent.claude.tools.support.ToolExecutionResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class FileTools extends AbstractClaudeTools {

    @ClaudeTool(name = "read_file", description = "读取文件内容。", schemaMethod = "readFileSchema")
    ToolExecutionResult readFile(Map<String, Object> input, ToolExecutionContext context) {
        try {
            Path path = safePath(context.workingDirectory(), asString(input.get("path")));
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int offset = input.containsKey("offset") ? asInt(input.get("offset")) : 0;
            int limit = input.containsKey("limit") ? asInt(input.get("limit")) : lines.size();
            List<String> sliced = lines.stream().skip(Math.max(offset, 0)).limit(Math.max(limit, 0)).toList();
            return ToolExecutionResult.of(String.join(System.lineSeparator(), sliced));
        } catch (IOException e) {
            return ToolExecutionResult.of("错误：" + e.getMessage());
        }
    }

    @ClaudeTool(name = "write_file", description = "把内容写入文件。", schemaMethod = "writeFileSchema")
    ToolExecutionResult writeFile(Map<String, Object> input, ToolExecutionContext context) {
        try {
            Path path = safePath(context.workingDirectory(), asString(input.get("path")));
            Files.createDirectories(path.getParent());
            Files.writeString(path, asString(input.get("content")), StandardCharsets.UTF_8);
            return ToolExecutionResult.of("已写入 " + path.getFileName());
        } catch (IOException e) {
            return ToolExecutionResult.of("错误：" + e.getMessage());
        }
    }

    @ClaudeTool(name = "edit_file", description = "在文件中执行一次精确文本替换。", schemaMethod = "editFileSchema")
    ToolExecutionResult editFile(Map<String, Object> input, ToolExecutionContext context) {
        try {
            Path path = safePath(context.workingDirectory(), asString(input.get("path")));
            String content = Files.readString(path, StandardCharsets.UTF_8);
            String oldText = asString(input.get("old_text"));
            String newText = asString(input.get("new_text"));
            if (!content.contains(oldText)) {
                return ToolExecutionResult.of("错误：未找到要替换的文本");
            }
            Files.writeString(path, content.replaceFirst(java.util.regex.Pattern.quote(oldText),
                    java.util.regex.Matcher.quoteReplacement(newText)), StandardCharsets.UTF_8);
            return ToolExecutionResult.of("已修改 " + path.getFileName());
        } catch (IOException e) {
            return ToolExecutionResult.of("错误：" + e.getMessage());
        }
    }

    @ClaudeTool(name = "glob", description = "查找匹配 glob 模式的文件。", schemaMethod = "globSchema")
    ToolExecutionResult glob(Map<String, Object> input, ToolExecutionContext context) {
        String pattern = asString(input.get("pattern"));
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        try {
            List<Path> matches = Files.walk(context.workingDirectory())
                    .filter(path -> matcher.matches(context.workingDirectory().relativize(path)))
                    .sorted(Comparator.naturalOrder())
                    .toList();
            if (matches.isEmpty()) {
                return ToolExecutionResult.of("（没有匹配结果）");
            }
            List<String> relativePaths = new ArrayList<>();
            for (Path match : matches) {
                relativePaths.add(context.workingDirectory().relativize(match).toString());
            }
            return ToolExecutionResult.of(String.join(System.lineSeparator(), relativePaths));
        } catch (IOException e) {
            return ToolExecutionResult.of("错误：" + e.getMessage());
        }
    }

    Map<String, Object> readFileSchema() {
        return schema(Map.of("path", type("string"), "limit", type("integer"), "offset", type("integer")), List.of("path"));
    }

    Map<String, Object> writeFileSchema() {
        return schema(Map.of("path", type("string"), "content", type("string")), List.of("path", "content"));
    }

    Map<String, Object> editFileSchema() {
        return schema(Map.of("path", type("string"), "old_text", type("string"), "new_text", type("string")), List.of("path", "old_text", "new_text"));
    }

    Map<String, Object> globSchema() {
        return schema(Map.of("pattern", type("string")), List.of("pattern"));
    }
}
