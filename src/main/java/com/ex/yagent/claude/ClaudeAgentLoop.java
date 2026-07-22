package com.ex.yagent.claude;

import com.ex.yagent.claude.tools.support.ClaudeToolHandler;
import com.ex.yagent.claude.tools.support.ToolExecutionContext;
import com.ex.yagent.claude.tools.support.ToolExecutionResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * ClaudeAgentLoop 是整个教学版 harness 的中心。
 * 它实现的正是教程里那条核心逻辑：
 * 1. 准备上下文
 * 2. 调模型
 * 3. 看有没有 tool_use
 * 4. 执行工具
 * 5. 把 tool_result 再喂回消息历史
 * 6. 继续下一轮
 */
final class ClaudeAgentLoop {

    /** 普通情况下每轮模型调用使用的默认 token 上限。 */
    private static final int DEFAULT_MAX_TOKENS = 8000;
    /** 遇到 length/max_tokens 截断后，第二次尝试会提升到这个上限。 */
    private static final int ESCALATED_MAX_TOKENS = 16000;
    /** 限流或上下文错误时，最多重试的次数。 */
    private static final int MAX_RETRIES = 3;
    /** 超过这个上下文大小估算值后，会触发一次主动压缩。 */
    private static final int CONTEXT_LIMIT = 50_000;

    /** 整个 harness 的共享运行时。 */
    private final ClaudeRuntime runtime;
    /** 模型调用适配器，屏蔽底层 DashScope 协议差异。 */
    private final LlmClient llmClient;
    /** 工具注册表，每轮会从这里组装当前可见工具池。 */
    private final ToolRegistry toolRegistry;
    /** 队友线程管理器，负责 spawn/stop 多个 teammate worker。 */
    private final TeammateManager teammateManager;

    /**
     * @param runtime 整个 harness 的共享运行时，里面持有 task store、cron、message bus、skills 等状态。
     * @param llmClient 模型调用适配器。主 loop 不关心底层是 DashScope 还是别的实现，只依赖这个接口。
     */
    ClaudeAgentLoop(ClaudeRuntime runtime, LlmClient llmClient) {
        this.runtime = runtime;
        this.llmClient = llmClient;
        this.toolRegistry = new ToolRegistry(runtime);
        this.teammateManager = new TeammateManager(runtime, this);
    }

    TeammateManager teammateManager() {
        return teammateManager;
    }

    /**
     * 主 CLI 输入最终都走这里。
     * 方法本身只负责把用户输入接进 history，然后把真正的 loop 交给 executeLoop。
     *
     * @param session 当前 lead 会话对象，里面已经保存了此前的多轮消息历史和 todo 状态。
     * @param prompt 用户这一次手动输入的文本。
     * @return 本轮 loop 结束后，最后一段 assistant 文本答复。
     */
    String runUserTurn(ClaudeSession session, String prompt) {
        runtime.console().println("[hook] user prompt submit");
        session.addMessage(ClaudeMessage.userText(prompt));
        return executeLoop(session);
    }

    /**
     * cron / background / inbox 触发的“被动事件”也会被包装成用户消息，再送入同一条 loop。
     * 这样就能保证：所有状态推进都经过同一套推理路径。
     *
     * @param session 当前主会话。
     * @param prompt 被动事件包装成的一条“伪用户输入”，例如某个 cron 提醒或后台任务完成通知。
     * @return 本轮被动推进后得到的 assistant 文本答复。
     */
    String runPassiveTurn(ClaudeSession session, String prompt) {
        session.addMessage(ClaudeMessage.userText(prompt));
        return executeLoop(session);
    }

    /**
     * 子代理会新建一份干净 history，只返回最终摘要，不把中间痕迹污染父上下文。
     *
     * @param description 子代理要完成的任务描述。
     * @param workingDirectory 子代理运行时应该使用的工作目录。
     * @param ownerName 子代理归属谁，通常是 lead 或某个 teammate 的名字。
     * @return 子代理最终给出的摘要文本。
     */
    String runSubagent(String description, Path workingDirectory, String ownerName) {
        ClaudeSession subSession = new ClaudeSession(ownerName + "-subagent", ToolMode.SUBAGENT, workingDirectory);
        subSession.addMessage(ClaudeMessage.userText(description));
        return executeLoop(subSession);
    }

    /**
     * @param session 当前会话。
     * @return 一直循环到当前会话“这一轮不再需要任何 tool_use”为止，然后返回最终答复文本。
     */
    private String executeLoop(ClaudeSession session) {
        int maxTokens = DEFAULT_MAX_TOKENS;
        boolean escalated = false;
        while (true) {
            injectPassiveNotifications(session);
            injectTodoReminder(session);
            CompactionService.prepare(runtime, session);

            AssembledTools assembledTools = toolRegistry.assemble(session);
            LlmTurn turn = callWithRetry(session, assembledTools.definitions(), maxTokens);
            session.addMessage(turn.asAssistantMessage());

            // length / max_tokens 类停止原因，先尝试给更多 token，再尝试 continuation。
            if ("length".equalsIgnoreCase(turn.finishReason()) || "max_tokens".equalsIgnoreCase(turn.finishReason())) {
                if (!escalated) {
                    maxTokens = ESCALATED_MAX_TOKENS;
                    escalated = true;
                    continue;
                }
                session.addMessage(ClaudeMessage.userText("Continue from the previous response. Do not repeat completed work."));
                escalated = false;
                maxTokens = DEFAULT_MAX_TOKENS;
                continue;
            }

            if (!turn.hasToolUse()) {
                runtime.hooks().runStop(session.messages(), toolRegistry.createContext(session, this));
                return session.lastAssistantText();
            }

            boolean compactRequested = false;
            for (ToolUseBlock toolUse : session.lastAssistantToolUses()) {
                ToolExecutionContext context = toolRegistry.createContext(session, this);
                var blocked = runtime.hooks().runPreToolUse(toolUse, context);
                if (blocked.isPresent()) {
                    session.addMessage(ClaudeMessage.toolResult(toolUse.id(), blocked.orElse("Permission denied")));
                    continue;
                }
                ToolExecutionResult result = executeTool(toolUse, assembledTools.handlers(), context);
                runtime.hooks().runPostToolUse(toolUse, result.content(), context);
                if ("todo_write".equals(toolUse.name())) {
                    session.resetRoundsSinceTodo();
                } else {
                    session.incrementRoundsSinceTodo();
                }
                session.addMessage(ClaudeMessage.toolResult(toolUse.id(), result.content()));
                if (result.compactRequested()) {
                    compactRequested = true;
                }
            }

            if (compactRequested) {
                CompactionService.compactHistory(runtime, session, "Manual compact requested.");
            }
        }
    }

    /**
     * 工具执行有两种模式：
     * 1. 直接执行并返回结果
     * 2. 识别为慢任务后转后台线程，立即返回占位结果
     *
     * @param toolUse 当前要执行的工具调用块。
     * @param handlers 本轮可用工具 handler 映射。
     * @param context 当前执行上下文。
     * @return 这次工具调用的统一执行结果。
     */
    private ToolExecutionResult executeTool(ToolUseBlock toolUse, Map<String, ClaudeToolHandler> handlers, ToolExecutionContext context) {
        ClaudeToolHandler handler = handlers.get(toolUse.name());
        if (handler == null) {
            return ToolExecutionResult.of("Unknown tool: " + toolUse.name());
        }
        if (shouldRunBackground(toolUse)) {
            String taskId = runtime.backgroundTasks().submit(toolUse.name(), () -> handler.handle(toolUse.input(), context).content());
            return ToolExecutionResult.of("[Background task " + taskId + " started] Result will be injected later.");
        }
        try {
            return handler.handle(toolUse.input(), context);
        } catch (Exception e) {
            return ToolExecutionResult.of("Error: " + e.getMessage());
        }
    }

    /**
     * 慢命令不应该阻塞主 loop，这里按教学版规则把明显的 build/install 命令转到后台。
     *
     * @param toolUse 当前工具调用块。
     * @return true 表示应转后台执行；false 表示前台直接执行。
     */
    private boolean shouldRunBackground(ToolUseBlock toolUse) {
        if (!"bash".equals(toolUse.name())) {
            return false;
        }
        if (ToolRegistry.asBoolean(toolUse.input().get("run_in_background"))) {
            return true;
        }
        String command = Objects.toString(toolUse.input().get("command"), "").toLowerCase();
        return command.contains("npm install")
                || command.contains("pnpm install")
                || command.contains("yarn install")
                || command.contains("mvn test")
                || command.contains("mvn package")
                || command.contains("gradle build");
    }

    /**
     * 所有异步来源的通知都在每轮开头注入消息历史。
     * 这让 cron/background/inbox 和普通用户消息获得同等地位。
     *
     * @param session 当前主会话。只有 lead 模式会注入异步通知。
     */
    private void injectPassiveNotifications(ClaudeSession session) {
        if (session.mode() != ToolMode.LEAD) {
            return;
        }
        for (ToolNotification notification : runtime.pollPassiveNotifications()) {
            session.addMessage(ClaudeMessage.userText(notification.message()));
        }
    }

    /**
     * Todo reminder 是教程里防漂移的一个小机制。
     * 连续几轮都没更新 todo，就给模型插一条提醒。
     *
     * @param session 当前会话，里面保存了 roundsSinceTodo 计数器。
     */
    private void injectTodoReminder(ClaudeSession session) {
        if (session.roundsSinceTodo() >= 3) {
            session.addMessage(ClaudeMessage.userText("<reminder>Update your todos.</reminder>"));
            session.resetRoundsSinceTodo();
        }
    }

    /**
     * 错误恢复只做教学所需的几种：
     * - 限流重试
     * - prompt too long -> reactive compact 后重试
     *
     * @param session 当前会话历史。
     * @param tools 本轮暴露给模型的工具定义列表。
     * @param maxTokens 本次模型调用允许的 token 上限。
     * @return 成功调用模型后得到的一轮统一结果。
     */
    private LlmTurn callWithRetry(ClaudeSession session, List<ToolDefinition> tools, int maxTokens) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return llmClient.complete(runtime.promptAssembler().buildSystemPrompt(), session.messages(), tools, maxTokens);
            } catch (RuntimeException e) {
                String message = Objects.toString(e.getMessage(), "");
                if (message.contains("429") || message.contains("529")) {
                    sleep(attempt);
                    continue;
                }
                if (message.toLowerCase().contains("context") || message.toLowerCase().contains("maximum context")) {
                    CompactionService.reactiveCompact(runtime, session);
                    continue;
                }
                throw e;
            }
        }
        throw new IllegalStateException("LLM 调用重试失败");
    }

    /**
     * @param attempt 当前是第几次重试。
     *                次数越大，等待时间越长，用来做最简单的指数退避。
     */
    private void sleep(int attempt) {
        try {
            TimeUnit.MILLISECONDS.sleep(500L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

/**
 * Session 把一段会话的局部状态封装起来：
 * - message history
 * - 当前 todo
 * - 当前工作目录
 * - 这是 lead / teammate / subagent 哪种模式
 */
final class ClaudeSession {

    /** 这段会话归属于谁，例如 lead、alice、bob。 */
    private final String ownerName;
    /** 当前会话模式：主会话、子代理还是队友线程。 */
    private final ToolMode mode;
    /** 这段会话里的默认工作目录。 */
    private final Path workingDirectory;
    /** 当前完整消息历史。 */
    private final List<ClaudeMessage> messages = new ArrayList<>();
    /** 当前会话中的 todo 列表。 */
    private List<TodoItem> todos = new ArrayList<>();
    /** 自上次 todo_write 以来，已经经过了多少轮工具执行。 */
    private int roundsSinceTodo;

    /**
     * @param ownerName 这段会话归属于谁，例如 lead、alice、bob。
     * @param mode 当前会话是哪种模式：主会话、子代理还是队友线程。
     * @param workingDirectory 这段会话里所有文件/命令工具默认运行的目录。
     */
    ClaudeSession(String ownerName, ToolMode mode, Path workingDirectory) {
        this.ownerName = ownerName;
        this.mode = mode;
        this.workingDirectory = workingDirectory;
    }

    String ownerName() {
        return ownerName;
    }

    ToolMode mode() {
        return mode;
    }

    Path workingDirectory() {
        return workingDirectory;
    }

    /**
     * @return 当前消息历史的副本。
     *         返回副本而不是原列表，是为了避免外部无意中绕过 session 直接改内部状态。
     */
    List<ClaudeMessage> messages() {
        return new ArrayList<>(messages);
    }

    /**
     * @param message 要追加到历史末尾的一条消息。
     */
    void addMessage(ClaudeMessage message) {
        messages.add(message);
    }

    /**
     * @param newMessages 用一整份新历史替换旧历史。
     *                    compaction 之后会用它来把会话瘦身。
     */
    void replaceMessages(List<ClaudeMessage> newMessages) {
        messages.clear();
        messages.addAll(newMessages);
    }

    /**
     * @return 从最近的 assistant 消息里提取出的纯文本答复。
     *         这是 CLI 最后打印给用户看的内容。
     */
    String lastAssistantText() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ClaudeMessage message = messages.get(i);
            if (message.role() == MessageRole.ASSISTANT) {
                String text = message.textContent();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    /**
     * @return 最近一条 assistant 消息里包含的所有工具调用块。
     *         主 loop 会据此逐个执行工具。
     */
    List<ToolUseBlock> lastAssistantToolUses() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ClaudeMessage message = messages.get(i);
            if (message.role() == MessageRole.ASSISTANT) {
                return message.toolUses();
            }
        }
        return List.of();
    }

    /**
     * @return 当前会话里的 todo 列表。
     */
    List<TodoItem> todos() {
        return todos;
    }

    /**
     * @param newTodos 最新 todo 列表。
     *                 每次 todo_write 成功后都会整表替换，并顺手把 reminder 计数清零。
     */
    void replaceTodos(List<TodoItem> newTodos) {
        this.todos = new ArrayList<>(newTodos);
        this.roundsSinceTodo = 0;
    }

    /**
     * @return 自上次更新 todo 以来，已经经过了多少轮工具执行。
     */
    int roundsSinceTodo() {
        return roundsSinceTodo;
    }

    /**
     * 普通工具执行完后，表示“又过了一轮没有更新 todo”，因此计数加一。
     */
    void incrementRoundsSinceTodo() {
        roundsSinceTodo++;
    }

    /**
     * todo_write 执行后，或者插入 reminder 后，都需要把计数重置。
     */
    void resetRoundsSinceTodo() {
        roundsSinceTodo = 0;
    }
}

enum ToolMode {
    LEAD,
    SUBAGENT,
    TEAMMATE
}

/**
 * 这里实现 S20 的“队友线程”。
 * 它不是 HTTP 服务，也不是多进程系统，而是一个本地线程 + JSONL mailbox 的教学版实现。
 */
final class TeammateManager {

    /** 共享运行时，队友线程需要通过它访问 task、mailbox、cron 等状态。 */
    private final ClaudeRuntime runtime;
    /** 主 loop 对象，队友真正干活时会借它开 subagent。 */
    private final ClaudeAgentLoop loop;
    /** 当前已经启动的所有队友线程，key 是队友名字。 */
    private final Map<String, TeammateWorker> workers = new ConcurrentHashMap<>();

    /**
     * @param runtime 共享运行时。
     * @param loop 主循环对象；队友线程需要通过它开 subagent 来做具体工作。
     */
    TeammateManager(ClaudeRuntime runtime, ClaudeAgentLoop loop) {
        this.runtime = runtime;
        this.loop = loop;
    }

    /**
     * @param name 新队友名称。
     * @param role 队友角色说明。
     * @param prompt 队友长期行为提示词。
     * @param defaultWorkingDirectory 队友默认工作目录；如果任务绑定了 worktree，后续会切换。
     * @return 创建结果文本。
     */
    String spawn(String name, String role, String prompt, Path defaultWorkingDirectory) {
        if (name == null || name.isBlank()) {
            return "Teammate name cannot be empty";
        }
        if (workers.containsKey(name)) {
            return "Teammate already exists: " + name;
        }
        TeammateWorker worker = new TeammateWorker(runtime, loop, name, role, prompt, defaultWorkingDirectory);
        workers.put(name, worker);
        worker.start();
        return "Spawned teammate " + name;
    }

    /**
     * 退出 CLI 时停止所有队友线程。
     */
    void stopAll() {
        for (TeammateWorker worker : workers.values()) {
            worker.stop();
        }
    }
}

final class TeammateWorker implements Runnable {

    /** 共享运行时，负责队友与外部世界的文件和消息交互。 */
    private final ClaudeRuntime runtime;
    /** 主 loop，对友执行具体任务时通过它开启隔离的子代理流程。 */
    private final ClaudeAgentLoop loop;
    /** 当前队友名称。 */
    private final String name;
    /** 当前队友角色说明。 */
    private final String role;
    /** 当前队友的长期提示词。 */
    private final String prompt;
    /** 没有绑定 worktree 时，队友默认工作的目录。 */
    private final Path defaultWorkingDirectory;
    /** 队友实际运行的后台线程对象。 */
    private final Thread thread;
    /** 已经通过审批、允许这个队友去认领的任务集合。 */
    private final Set<String> approvedTasks = new HashSet<>();
    /** 队友线程是否继续运行的开关。 */
    private volatile boolean running = true;

    /**
     * @param runtime 共享运行时，队友通过它收发信件、读取任务、回报结果。
     * @param loop 主循环对象，队友真正干活时会借它开一个 subagent。
     * @param name 队友名字。
     * @param role 队友角色。
     * @param prompt 队友常驻提示词。
     * @param defaultWorkingDirectory 队友默认工作目录。
     */
    TeammateWorker(ClaudeRuntime runtime, ClaudeAgentLoop loop, String name, String role, String prompt, Path defaultWorkingDirectory) {
        this.runtime = runtime;
        this.loop = loop;
        this.name = name;
        this.role = role;
        this.prompt = prompt;
        this.defaultWorkingDirectory = defaultWorkingDirectory;
        this.thread = new Thread(this, "claude-teammate-" + name);
        this.thread.setDaemon(true);
    }

    /**
     * 启动队友线程。
     */
    void start() {
        thread.start();
    }

    /**
     * 请求队友线程停止。
     */
    void stop() {
        running = false;
        thread.interrupt();
    }

    @Override
    public void run() {
        while (running) {
            handleInbox();
            autoClaimTasks();
            try {
                TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * Teammate 的 inbox 只处理教程需要的几种协议消息。
     *
     * 这里没有参数，因为处理对象就是“当前这个队友自己的信箱”。
     */
    private void handleInbox() {
        for (MailboxMessage message : runtime.messageBus().readInbox(name)) {
            String requestId = Objects.toString(message.metadata().get("requestId"), "");
            switch (message.type()) {
                case "plan_request" -> {
                    String task = Objects.toString(message.metadata().get("task"), message.content());
                    String plan = """
                            1. 阅读相关代码和任务描述
                            2. 在隔离上下文中完成实现或分析
                            3. 汇总结果并回报 lead
                            """;
                    runtime.messageBus().send(name, "lead", "plan_response",
                            "Plan for [" + task + "]\n" + plan,
                            Map.of("requestId", requestId, "task", task));
                }
                case "plan_review" -> {
                    boolean approve = ToolRegistry.asBoolean(message.metadata().get("approve"));
                    String task = Objects.toString(message.metadata().get("task"), "");
                    if (approve) {
                        approvedTasks.add(task);
                    }
                    runtime.messageBus().send(name, "lead", "plan_review_response",
                            approve ? "Plan approved" : "Plan rejected",
                            Map.of("requestId", requestId, "approve", approve, "task", task));
                }
                case "shutdown_request" -> {
                    runtime.messageBus().send(name, "lead", "shutdown_response",
                            "Teammate " + name + " stopped",
                            Map.of("requestId", requestId));
                    running = false;
                }
                case "message" -> runtime.messageBus().send(name, "lead", "message_response",
                        "Received: " + message.content(), Map.of("requestId", requestId));
                default -> {
                }
            }
        }
    }

    /**
     * 这里实现“队友在空闲时主动扫描 task board 并认领工作”。
     * 为了保证先 plan 后 claim，本地实现只认领已经被批准过的任务。
     *
     * 没有显式参数，因为它直接读取 runtime 里的 task store 和当前队友自身状态。
     */
    private void autoClaimTasks() {
        for (TaskRecord task : runtime.taskStore().list()) {
            if (!"pending".equals(task.status())) {
                continue;
            }
            if (!runtime.taskStore().canStart(task.id())) {
                continue;
            }
            if (!approvedTasks.contains(task.subject()) && !approvedTasks.contains(task.id())) {
                continue;
            }
            try {
                TaskRecord claimed = runtime.taskStore().claim(task.id(), name);
                Path workingDir = runtime.worktreeService().resolveTaskWorkingDir(claimed);
                String taskPrompt = """
                        You are teammate %s.
                        Role: %s
                        Team prompt: %s

                        Complete this task and return a concise summary:
                        - subject: %s
                        - description: %s
                        """.formatted(name, role, prompt, claimed.subject(), claimed.description());
                String summary = loop.runSubagent(taskPrompt, workingDir == null ? defaultWorkingDirectory : workingDir, name);
                runtime.taskStore().complete(claimed.id());
                runtime.messageBus().send(name, "lead", "task_update",
                        "Completed " + claimed.id() + ":\n" + summary,
                        Map.of("taskId", claimed.id()));
            } catch (Exception e) {
                runtime.messageBus().send(name, "lead", "task_update",
                        "Failed to complete task " + task.id() + ": " + e.getMessage(),
                        Map.of("taskId", task.id()));
            }
        }
    }
}

/**
 * CompactionService 把 S20 的“长会话治理”集中起来：
 * - 大 tool_result 落盘
 * - 消息裁剪
 * - 历史摘要
 * - prompt 太长时的反应式压缩
 */
final class CompactionService {

    /** 单条 tool_result 超过这个阈值就会被写入磁盘，而不是一直塞在上下文里。 */
    private static final int LARGE_TOOL_RESULT_THRESHOLD = 8_000;
    /** 压缩历史后，尾部保留多少条最新消息。 */
    private static final int KEEP_RECENT_MESSAGES = 12;
    /** 超过这个消息条数后，会先做一次 snip compact。 */
    private static final int MAX_MESSAGES = 50;
    /** 估算上下文大小超过这个值时，触发整段历史压缩。 */
    private static final int CONTEXT_LIMIT = 50_000;

    /**
     * 纯静态工具类，不允许实例化。
     */
    private CompactionService() {
    }

    /**
     * @param runtime 当前运行时，用来访问 objectMapper、输出目录等共享资源。
     * @param session 当前会话，会被原地替换成压缩后的消息历史。
     */
    static void prepare(ClaudeRuntime runtime, ClaudeSession session) {
        session.replaceMessages(toolResultBudget(runtime, session.messages()));
        session.replaceMessages(snipCompact(session.messages()));
        session.replaceMessages(microCompact(session.messages()));
        if (estimateSize(runtime, session.messages()) > CONTEXT_LIMIT) {
            compactHistory(runtime, session, "Context limit reached.");
        }
    }

    /**
     * 大工具输出不应该一直塞在 prompt 里，因此旧的超长 tool_result 会先写盘，再替换成指针。
     *
     * @param runtime 当前运行时，主要用它定位 toolResultDir。
     * @param messages 原始消息历史。
     * @return 替换过大 tool_result 后的新消息历史。
     */
    private static List<ClaudeMessage> toolResultBudget(ClaudeRuntime runtime, List<ClaudeMessage> messages) {
        List<ClaudeMessage> rewritten = new ArrayList<>();
        for (ClaudeMessage message : messages) {
            if (message.role() != MessageRole.TOOL) {
                rewritten.add(message);
                continue;
            }
            List<ClaudeBlock> blocks = new ArrayList<>();
            for (ClaudeBlock block : message.blocks()) {
                if (block instanceof ToolResultBlock toolResultBlock && toolResultBlock.content().length() > LARGE_TOOL_RESULT_THRESHOLD) {
                    String fileName = "tool-result-" + toolResultBlock.toolUseId() + "-" + Instant.now().toEpochMilli() + ".txt";
                    Path outputPath = runtime.paths().toolResultDir().resolve(fileName);
                    try {
                        Files.writeString(outputPath, toolResultBlock.content(), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        throw new IllegalStateException("写入大工具输出失败", e);
                    }
                    blocks.add(new ToolResultBlock(toolResultBlock.toolUseId(),
                            "[Large output persisted to " + outputPath + "]"));
                } else {
                    blocks.add(block);
                }
            }
            rewritten.add(new ClaudeMessage(message.role(), blocks));
        }
        return rewritten;
    }

    /**
     * snip compact 是最便宜的一层：消息太多时，保留头尾，中间插入一条摘要占位。
     *
     * @param messages 原始消息历史。
     * @return 经过“头尾保留、中间省略”后的消息历史。
     */
    private static List<ClaudeMessage> snipCompact(List<ClaudeMessage> messages) {
        if (messages.size() <= MAX_MESSAGES) {
            return messages;
        }
        List<ClaudeMessage> compacted = new ArrayList<>();
        compacted.addAll(messages.subList(0, 4));
        compacted.add(ClaudeMessage.userText("[snip_compact] Middle history omitted for brevity."));
        compacted.addAll(messages.subList(messages.size() - (MAX_MESSAGES - 5), messages.size()));
        return compacted;
    }

    /**
     * micro compact 是轻量文本截断，不动结构，只缩短单条超长文本。
     *
     * @param messages 原始消息历史。
     * @return 把超长文本块做截断后的新消息历史。
     */
    private static List<ClaudeMessage> microCompact(List<ClaudeMessage> messages) {
        List<ClaudeMessage> compacted = new ArrayList<>();
        for (ClaudeMessage message : messages) {
            List<ClaudeBlock> blocks = new ArrayList<>();
            for (ClaudeBlock block : message.blocks()) {
                if (block instanceof TextBlock textBlock && textBlock.text().length() > 4_000) {
                    String text = textBlock.text();
                    blocks.add(new TextBlock(text.substring(0, 2_000) + "\n...\n" + text.substring(text.length() - 1_500)));
                } else {
                    blocks.add(block);
                }
            }
            compacted.add(new ClaudeMessage(message.role(), blocks));
        }
        return compacted;
    }

    /**
     * @param runtime 当前运行时，用来写 transcript 文件。
     * @param session 当前会话，会被替换成压缩后的短历史。
     * @param reason 这次压缩的原因文本，会写进压缩后的占位消息里。
     */
    static void compactHistory(ClaudeRuntime runtime, ClaudeSession session, String reason) {
        try {
            Path transcript = runtime.paths().transcriptsDir().resolve("transcript-" + UUID.randomUUID().toString().substring(0, 8) + ".json");
            runtime.objectMapper().writerWithDefaultPrettyPrinter().writeValue(transcript.toFile(), session.messages());
        } catch (IOException e) {
            throw new IllegalStateException("写入 transcript 失败", e);
        }
        List<ClaudeMessage> history = session.messages();
        if (history.size() <= KEEP_RECENT_MESSAGES) {
            return;
        }
        List<ClaudeMessage> compacted = new ArrayList<>();
        compacted.add(ClaudeMessage.userText("[compact_history] " + reason + " Earlier history summarized and archived."));
        compacted.addAll(history.subList(Math.max(0, history.size() - KEEP_RECENT_MESSAGES), history.size()));
        session.replaceMessages(compacted);
    }

    /**
     * @param runtime 当前运行时。
     * @param session 当前会话。
     *                当模型报 prompt too long 时，除了压缩历史，还会额外插入一条继续提示。
     */
    static void reactiveCompact(ClaudeRuntime runtime, ClaudeSession session) {
        compactHistory(runtime, session, "Reactive compaction triggered by prompt-too-long.");
        session.addMessage(ClaudeMessage.userText("[reactive_compact] Continue with compacted context."));
    }

    /**
     * @param runtime 当前运行时，用 objectMapper 统一估算消息序列化后的字节量。
     * @param messages 要估算大小的消息历史。
     * @return 当前消息历史的大致字节数。
     */
    private static int estimateSize(ClaudeRuntime runtime, List<ClaudeMessage> messages) {
        try {
            return runtime.objectMapper().writeValueAsBytes(messages).length;
        } catch (IOException e) {
            throw new IllegalStateException("估算上下文大小失败", e);
        }
    }
}
