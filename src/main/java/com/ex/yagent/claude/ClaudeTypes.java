package com.ex.yagent.claude;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ClaudeTypes {

    private ClaudeTypes() {
    }
}

enum MessageRole {
    USER,
    ASSISTANT,
    TOOL
}

/**
 * ClaudeBlock 是内部统一内容块协议。
 * 无论底层厂商返回什么格式，进入 loop 之前都会被映射成这三种块。
 */
sealed interface ClaudeBlock permits TextBlock, ToolUseBlock, ToolResultBlock {
    String type();
}

record TextBlock(String text) implements ClaudeBlock {

    @Override
    public String type() {
        return "text";
    }
}

/**
 * ToolUseBlock 表示“模型要求 Java 侧执行一个工具”。
 *
 * @param id 工具调用的唯一 ID。后续把结果回灌给模型时，必须带回这个 ID 才能让模型知道
 *           “这条 tool_result 是对应哪一次 tool_use 的”。
 * @param name 工具名，例如 {@code bash}、{@code read_file}、{@code create_task}。
 * @param input 工具参数，来自模型生成的 JSON 对象。
 */
record ToolUseBlock(String id, String name, Map<String, Object> input) implements ClaudeBlock {

    @Override
    public String type() {
        return "tool_use";
    }
}

/**
 * ToolResultBlock 表示“Java 已经把工具执行完，并把结果返回给模型”。
 *
 * @param toolUseId 对应哪一次工具调用，值必须等于先前 {@link ToolUseBlock#id()}。
 * @param content 工具执行结果文本。模型下一轮推理会读取这段内容。
 */
record ToolResultBlock(String toolUseId, String content) implements ClaudeBlock {

    @Override
    public String type() {
        return "tool_result";
    }
}

/**
 * ClaudeMessage 是会话历史里的“一条消息”。
 *
 * @param role 这条消息是谁发出的：
 *             {@code USER} 表示用户/系统注入的输入，
 *             {@code ASSISTANT} 表示模型回复，
 *             {@code TOOL} 表示工具执行结果。
 * @param blocks 这条消息包含的内容块列表。一个消息里可以同时有文本块和工具调用块。
 */
record ClaudeMessage(MessageRole role, List<ClaudeBlock> blocks) {

    ClaudeMessage {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }

    static ClaudeMessage userText(String text) {
        return new ClaudeMessage(MessageRole.USER, List.of(new TextBlock(text)));
    }

    static ClaudeMessage assistant(List<ClaudeBlock> blocks) {
        return new ClaudeMessage(MessageRole.ASSISTANT, blocks);
    }

    static ClaudeMessage toolResult(String toolUseId, String content) {
        return new ClaudeMessage(MessageRole.TOOL, List.of(new ToolResultBlock(toolUseId, content)));
    }

    /**
     * 这里把一条消息里的文本块拼成纯文本，方便做：
     * - 打印最终答复
     * - 拼 OpenAI/DashScope 兼容格式的 content
     */
    @JsonIgnore
    String textContent() {
        StringBuilder builder = new StringBuilder();
        for (ClaudeBlock block : blocks) {
            if (block instanceof TextBlock textBlock) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(textBlock.text());
            }
        }
        return builder.toString();
    }

    /**
     * assistant 消息中如果带了工具调用，这里抽出来交给主 loop 执行。
     */
    @JsonIgnore
    List<ToolUseBlock> toolUses() {
        List<ToolUseBlock> toolUses = new ArrayList<>();
        for (ClaudeBlock block : blocks) {
            if (block instanceof ToolUseBlock toolUseBlock) {
                toolUses.add(toolUseBlock);
            }
        }
        return toolUses;
    }
}

/**
 * ToolDefinition 是暴露给模型看的工具说明书。
 *
 * @param name 工具名，模型调用时会直接引用这个名字。
 * @param description 工具用途说明，帮助模型判断“什么时候该用这个工具”。
 * @param inputSchema 工具参数的 JSON Schema，告诉模型参数结构应该长什么样。
 */
record ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
}

/**
 * ToolExecutionResult 是 Java 侧执行工具后的统一返回值。
 *
 * @param content 返回给模型的文本结果。
 * @param compactRequested 是否请求主 loop 立刻做一次上下文压缩。
 *                         普通工具通常是 false，{@code compact} 这类控制型工具会是 true。
 */
/**
 * LlmTurn 表示“模型完成了一轮推理后的产物”。
 *
 * @param blocks 模型这一轮生成的内容块，可以是纯文本，也可以包含 tool_use。
 * @param finishReason 底层模型返回的停止原因，例如 {@code stop}、{@code tool_calls}、
 *                     {@code length}。教学版不会完全信任它，只把它当辅助信息。
 */
record LlmTurn(List<ClaudeBlock> blocks, String finishReason) {

    LlmTurn {
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        finishReason = finishReason == null ? "stop" : finishReason;
    }

    /**
     * 教学版 loop 不完全信任 finishReason，而是以“是否真有 tool_use block”为继续条件。
     */
    boolean hasToolUse() {
        for (ClaudeBlock block : blocks) {
            if (block instanceof ToolUseBlock) {
                return true;
            }
        }
        return false;
    }

    ClaudeMessage asAssistantMessage() {
        return ClaudeMessage.assistant(blocks);
    }
}

/**
 * TodoItem 是当前会话中的一个计划项。
 *
 * @param content 任务内容，例如“先读 README，再列出 Java 文件”。
 * @param status 当前状态，只允许是 {@code pending}、{@code in_progress}、{@code completed}。
 */
record TodoItem(String content, String status) {
}

/**
 * TaskRecord 是持久化到 `.claude-java/tasks/` 里的跨轮次任务。
 *
 * @param id 任务唯一 ID，也是落盘文件名的一部分。
 * @param subject 任务标题，给人和模型快速识别“这是干什么的”。
 * @param description 任务的补充说明，比 subject 更详细。
 * @param status 任务状态，典型值为 {@code pending}、{@code in_progress}、{@code completed}。
 * @param owner 当前认领这个任务的代理名；没人认领时为 null。
 * @param blockedBy 这个任务依赖哪些前置任务 ID；只有这些依赖都完成后，本任务才能开始。
 * @param worktree 如果任务绑定了 git worktree，这里记录 worktree 名称；没有则为 null。
 * @param createdAt 任务创建时间，主要用于排查和调试任务流转。
 */
record TaskRecord(
        String id,
        String subject,
        String description,
        String status,
        String owner,
        List<String> blockedBy,
        String worktree,
        Instant createdAt
) {

    TaskRecord {
        blockedBy = blockedBy == null ? List.of() : List.copyOf(blockedBy);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    /**
     * TaskRecord 保持不可变，状态变化都通过复制生成新对象，
     * 这样落盘后的行为更容易推导和测试。
     */
    TaskRecord withStatus(String newStatus, String newOwner) {
        return new TaskRecord(id, subject, description, newStatus, newOwner, blockedBy, worktree, createdAt);
    }

    TaskRecord withWorktree(String newWorktree) {
        return new TaskRecord(id, subject, description, status, owner, blockedBy, newWorktree, createdAt);
    }
}

/**
 * ProtocolState 是“队友协作协议”的本地状态记录。
 * 它不直接给模型看，主要用于 lead 和 teammate 之间跟踪一条请求目前走到哪一步。
 *
 * @param requestId 协议请求的唯一 ID。
 *                  例如 lead 发起一次“请提交计划”请求时，会生成一个 requestId，
 *                  后续 teammate 的 response 也必须带回同一个 requestId。
 * @param type 请求类型，例如 {@code shutdown}、{@code plan}。
 *             这个字段决定这条协议记录到底属于哪一种协作流程。
 * @param sender 谁发起了这条协议请求，通常是 {@code lead}，也可以是别的 agent。
 * @param target 这条协议请求发给谁，例如某个 teammate 名字。
 * @param payload 这次协议真正携带的业务内容。
 *                例如 plan 请求里，这里通常会放“要做哪项任务”的文本或 task 信息。
 * @param status 当前协议状态。
 *               例如刚创建时通常是 {@code pending}，之后可能变成 {@code approved}、
 *               {@code rejected} 或某种 response 类型。
 * @param createdAt 这条协议记录是什么时候创建的，方便调试和排查超时问题。
 */
record ProtocolState(
        String requestId,
        String type,
        String sender,
        String target,
        String payload,
        String status,
        Instant createdAt
) {

    ProtocolState {
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    ProtocolState withStatus(String newStatus) {
        return new ProtocolState(requestId, type, sender, target, payload, newStatus, createdAt);
    }
}

/**
 * MailboxMessage 是写进 `.claude-java/mailboxes/*.jsonl` 的真实信件。
 *
 * @param from 这封消息是谁发出的。
 * @param to 这封消息要投递给谁。
 * @param type 消息类型，例如普通 {@code message}、{@code plan_request}、{@code shutdown_response}。
 * @param content 消息正文，也就是这封“信”的可读内容。
 * @param metadata 附加元数据，常用来携带 {@code requestId}、{@code taskId}、{@code approve} 等协议字段。
 * @param timestamp 这封消息写入信箱的时间。
 */
record MailboxMessage(
        String from,
        String to,
        String type,
        String content,
        Map<String, Object> metadata,
        Instant timestamp
) {

    MailboxMessage {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }
}

/**
 * CronJob 是持久化到 `.claude-java/cron/jobs.json` 的定时任务记录。
 *
 * @param jobId 定时任务唯一 ID。
 * @param cron 五段式 cron 表达式，例如 {@code 30 9 * * *}。
 * @param prompt 定时触发后要注入主会话的提示词。
 * @param recurring 触发后是否继续保留；false 表示一次性任务。
 * @param durable 是否需要落盘保存；true 表示重启后仍然能恢复。
 * @param createdAt 任务创建时间。
 * @param lastFireKey 上一次触发的时间键，用来避免同一分钟内重复触发多次。
 */
record CronJob(
        String jobId,
        String cron,
        String prompt,
        boolean recurring,
        boolean durable,
        Instant createdAt,
        String lastFireKey
) {

    CronJob {
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    CronJob withLastFireKey(String fireKey) {
        return new CronJob(jobId, cron, prompt, recurring, durable, createdAt, fireKey);
    }
}

/**
 * ToolNotification 是异步子系统回灌主 loop 的轻量通知。
 *
 * @param source 通知来源，例如 {@code background}、{@code cron}、{@code inbox}。
 * @param message 真正要塞回消息历史里的文本内容。
 */
record ToolNotification(String source, String message) {
}
