package com.ex.yagent.claude;

import com.ex.yagent.claude.tools.support.ClaudeToolHandler;
import com.ex.yagent.claude.tools.support.ToolExecutionContext;
import com.ex.yagent.claude.tools.support.ToolExecutionResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class ClaudeRuntime {

    /** 项目根目录。文件工具、worktree 和运行时目录都会相对它展开。 */
    private final Path workspaceRoot;
    /** CLI 启动时解析出的配置，例如 DashScope key、模型名、审批模式。 */
    private final ClaudeConfig config;
    /** `.claude-java/` 下面各个运行时目录的统一入口。 */
    private final ClaudePaths paths;
    /** 全局 JSON 序列化器，负责读写 task、mailbox、cron 等本地状态文件。 */
    private final ObjectMapper objectMapper;
    /** 统一控制台输出器，避免多线程直接打印时输出互相穿插。 */
    private final ClaudeConsole console;
    /** 技能注册表，负责扫描 classpath 下的 skills 并生成 catalog。 */
    private final SkillRegistry skillRegistry;
    /** 任务仓库，负责把 TaskRecord 持久化到 `.claude-java/tasks/`。 */
    private final TaskStore taskStore;
    /** worktree 管理器，负责创建、删除、保留以及 task-worktree 绑定。 */
    private final WorktreeService worktreeService;
    /** 本地 JSONL 信箱总线，lead 和 teammate 之间都通过它通信。 */
    private final MessageBus messageBus;
    /** 协议状态管理器，用来跟踪 plan/shutdown 这类协作请求的生命周期。 */
    private final ProtocolRegistry protocolRegistry;
    /** 后台任务池，慢命令会转到这里异步执行。 */
    private final BackgroundTaskService backgroundTasks;
    /** cron 调度器，负责扫描定时任务并生成待注入会话的通知。 */
    private final CronSchedulerService cronScheduler;
    /** 教学版 MCP 注册表，维护当前已连接的 mock MCP server。 */
    private final McpRegistry mcpRegistry;
    /** hook 注册表，统一管理 PreToolUse / PostToolUse / Stop 等扩展点。 */
    private final HookRegistry hooks;
    /** 权限系统，负责在真正执行工具前做 deny/approval 判断。 */
    private final PermissionService permissionService;
    /** system prompt 组装器，每轮根据 skills、memory、MCP 状态动态拼 prompt。 */
    private final PromptAssembler promptAssembler;
    /** lead 收到但还没展示给模型的信箱消息缓冲区。 */
    private final Queue<MailboxMessage> inboxBuffer;

    /**
     * @param workspaceRoot 当前项目根目录。所有文件工具、worktree、运行时产物目录都相对它展开。
     * @param config CLI 启动时解析出的配置，包括 DashScope key/model 和审批模式。
     * @param console 教学版统一控制台输出器，后台线程和主线程都通过它打印，避免输出打架。
     */
    ClaudeRuntime(Path workspaceRoot, ClaudeConfig config, ClaudeConsole console) {
        this.workspaceRoot = workspaceRoot;
        this.config = config;
        this.console = console;
        this.paths = ClaudePaths.init(workspaceRoot);
        // 统一注册 JSR-310 等模块，保证 Instant 这类时间字段可以直接落盘。
        this.objectMapper = new ObjectMapper().findAndRegisterModules();
        this.skillRegistry = new SkillRegistry();
        this.taskStore = new TaskStore(paths.tasksDir(), objectMapper);
        this.worktreeService = new WorktreeService(workspaceRoot, paths.worktreesDir(), taskStore);
        this.messageBus = new MessageBus(paths.mailboxesDir(), objectMapper);
        this.protocolRegistry = new ProtocolRegistry(messageBus);
        this.backgroundTasks = new BackgroundTaskService(console);
        this.cronScheduler = new CronSchedulerService(paths.cronDir(), objectMapper, console);
        this.mcpRegistry = new McpRegistry();
        this.hooks = new HookRegistry(console);
        this.permissionService = new PermissionService(workspaceRoot, config, console);
        this.promptAssembler = new PromptAssembler(this);
        this.inboxBuffer = new ConcurrentLinkedQueue<>();
        hooks.registerPreToolUseHook((toolUse, context) -> permissionService.check(toolUse, context));
        hooks.registerPostToolUseHook((toolUse, output, context) -> {
            if (output != null && output.length() > 4000) {
                console.println("[hook] large output from " + toolUse.name() + ": " + output.length() + " chars");
            }
        });
        hooks.registerStopHook((messages, context) -> console.println("[hook] stop, messages=" + messages.size()));
        skillRegistry.scan();
        cronScheduler.start();
    }

    Path workspaceRoot() {
        return workspaceRoot;
    }

    ClaudeConfig config() {
        return config;
    }

    ClaudePaths paths() {
        return paths;
    }

    ObjectMapper objectMapper() {
        return objectMapper;
    }

    ClaudeConsole console() {
        return console;
    }

    SkillRegistry skillRegistry() {
        return skillRegistry;
    }

    TaskStore taskStore() {
        return taskStore;
    }

    WorktreeService worktreeService() {
        return worktreeService;
    }

    MessageBus messageBus() {
        return messageBus;
    }

    ProtocolRegistry protocolRegistry() {
        return protocolRegistry;
    }

    BackgroundTaskService backgroundTasks() {
        return backgroundTasks;
    }

    CronSchedulerService cronScheduler() {
        return cronScheduler;
    }

    McpRegistry mcpRegistry() {
        return mcpRegistry;
    }

    HookRegistry hooks() {
        return hooks;
    }

    PromptAssembler promptAssembler() {
        return promptAssembler;
    }

    /**
     * 轮询所有“不是用户手打输入”的异步事件。
     *
     * @return 一个通知列表。
     *         这些通知会在下一轮被包装成用户消息重新注入主 loop，
     *         让 cron / background / inbox 和普通对话走同一条推理链路。
     */
    List<ToolNotification> pollPassiveNotifications() {
        List<ToolNotification> notifications = new ArrayList<>();
        notifications.addAll(backgroundTasks.drainNotifications());
        notifications.addAll(cronScheduler.drainNotifications());
        List<MailboxMessage> leadMessages = messageBus.readInbox("lead");
        for (MailboxMessage leadMessage : leadMessages) {
            protocolRegistry.onLeadInbox(leadMessage);
            inboxBuffer.add(leadMessage);
            notifications.add(new ToolNotification("inbox", formatInboxMessage(leadMessage)));
        }
        return notifications;
    }

    String drainInboxBuffer() {
        if (inboxBuffer.isEmpty()) {
            return "(inbox empty)";
        }
        List<String> lines = new ArrayList<>();
        MailboxMessage next;
        while ((next = inboxBuffer.poll()) != null) {
            String requestId = Objects.toString(next.metadata().get("requestId"), "");
            String requestSuffix = requestId.isBlank() ? "" : " req:" + requestId;
            lines.add("[" + next.from() + "] [" + next.type() + requestSuffix + "] " + next.content());
        }
        return String.join(System.lineSeparator(), lines);
    }

    void shutdown() {
        cronScheduler.stop();
        backgroundTasks.stop();
    }

    private String formatInboxMessage(MailboxMessage message) {
        return "[Inbox][" + message.from() + "/" + message.type() + "] " + message.content();
    }
}

final class ClaudeConsole {

    /**
     * @param text 要打印到控制台的文本。
     *             这里做 synchronized，是为了让多个线程写日志时不要相互穿插。
     */
    synchronized void println(String text) {
        System.out.println(text);
    }
}

final class SkillRegistry {

    /** 已加载技能的内存索引，key 是技能名，value 是完整技能文档。 */
    private final Map<String, SkillDocument> skills = new LinkedHashMap<>();

    /**
     * 扫描 classpath 下的默认 skills 资源。
     * 这样 CLI 启动时就能立即拿到 skill catalog，而不是硬编码在 Java 常量里。
     */
    void scan() {
        skills.clear();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            Resource[] resources = resolver.getResources("classpath*:claude/skills/*.md");
            Arrays.sort(resources, Comparator.comparing(Resource::getFilename, Comparator.nullsLast(String::compareTo)));
            for (Resource resource : resources) {
                String raw = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                SkillDocument skillDocument = SkillDocument.parse(raw, resource.getFilename());
                skills.put(skillDocument.name(), skillDocument);
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描 skills 失败", e);
        }
    }

    /**
     * @return 所有已加载技能的一行摘要列表。
     *         这段文本会直接拼进 system prompt，让模型先知道“有哪些技能可以按需加载”。
     */
    String catalog() {
        if (skills.isEmpty()) {
            return "(no skills found)";
        }
        List<String> lines = new ArrayList<>();
        for (SkillDocument skill : skills.values()) {
            lines.add("- " + skill.name() + ": " + skill.description());
        }
        return String.join(System.lineSeparator(), lines);
    }

    /**
     * @param name 技能名，例如 {@code code-review}。
     * @return 对应技能的完整原文；如果找不到，返回可读错误字符串而不是抛异常。
     */
    String loadSkill(String name) {
        SkillDocument skill = skills.get(name);
        if (skill == null) {
            return "Skill not found: " + name;
        }
        return skill.raw();
    }

    /**
     * @return 当前扫描到的所有 skill 名称，主要用于 CLI 启动时展示。
     */
    Set<String> names() {
        return skills.keySet();
    }
}

record SkillDocument(String name, String description, String raw) {

    /**
     * @param raw 技能文件的完整原文。
     * @param fallbackFileName 当 YAML frontmatter 里没有 name 时，退回使用文件名。
     * @return 一个已解析好的 SkillDocument，里面包含名字、描述和原始文本。
     */
    static SkillDocument parse(String raw, String fallbackFileName) {
        if (raw.startsWith("---")) {
            String[] parts = raw.split("---", 3);
            if (parts.length == 3) {
                Yaml yaml = new Yaml();
                @SuppressWarnings("unchecked")
                Map<String, Object> meta = yaml.load(parts[1]);
                String name = Objects.toString(meta.getOrDefault("name", fallbackName(fallbackFileName)), fallbackName(fallbackFileName));
                String description = Objects.toString(meta.getOrDefault("description", firstTitle(parts[2])), firstTitle(parts[2]));
                return new SkillDocument(name, description, raw);
            }
        }
        return new SkillDocument(fallbackName(fallbackFileName), firstTitle(raw), raw);
    }

    private static String fallbackName(String fileName) {
        if (fileName == null) {
            return "unknown-skill";
        }
        return fileName.replace(".md", "");
    }

    private static String firstTitle(String text) {
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                return line.replace("#", "").trim();
            }
        }
        return "skill";
    }
}

final class TaskStore {

    private static final TypeReference<List<TaskRecord>> TASK_LIST_TYPE = new TypeReference<>() {
    };
    /** task JSON 文件所在目录。 */
    private final Path tasksDir;
    /** 读写 TaskRecord 的 JSON 序列化器。 */
    private final ObjectMapper objectMapper;

    /**
     * @param tasksDir task JSON 文件落盘目录。
     * @param objectMapper 统一 JSON 序列化器，用来读写 TaskRecord。
     */
    TaskStore(Path tasksDir, ObjectMapper objectMapper) {
        this.tasksDir = tasksDir;
        this.objectMapper = objectMapper;
    }

    /**
     * @param subject 任务标题，给人和 agent 快速识别任务用途。
     * @param description 任务详细说明。
     * @param blockedBy 当前任务依赖的前置 task ID 列表。
     * @return 新建并已落盘的任务记录。
     */
    TaskRecord create(String subject, String description, List<String> blockedBy) {
        String taskId = "task_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 6);
        TaskRecord record = new TaskRecord(taskId, subject, description, "pending", null, blockedBy, null, Instant.now());
        save(record);
        return record;
    }

    /**
     * @param taskRecord 要写入磁盘的任务记录。
     *                   调用者通常已经在内存里完成状态变更，这里只负责持久化。
     */
    void save(TaskRecord taskRecord) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(taskPath(taskRecord.id()).toFile(), taskRecord);
        } catch (IOException e) {
            throw new IllegalStateException("保存 task 失败: " + taskRecord.id(), e);
        }
    }

    /**
     * @param taskId 要读取的任务 ID。
     * @return 对应 task JSON 反序列化后的任务记录。
     */
    TaskRecord load(String taskId) {
        try {
            return objectMapper.readValue(taskPath(taskId).toFile(), TaskRecord.class);
        } catch (IOException e) {
            throw new IllegalStateException("读取 task 失败: " + taskId, e);
        }
    }

    /**
     * @return 当前任务目录下所有 task 文件对应的任务列表。
     */
    List<TaskRecord> list() {
        try {
            List<TaskRecord> tasks = new ArrayList<>();
            if (!Files.exists(tasksDir)) {
                return tasks;
            }
            Files.list(tasksDir)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .forEach(path -> {
                        try {
                            tasks.add(objectMapper.readValue(path.toFile(), TaskRecord.class));
                        } catch (IOException e) {
                            throw new IllegalStateException("读取 task 文件失败: " + path, e);
                        }
                    });
            return tasks;
        } catch (IOException e) {
            throw new IllegalStateException("列出 task 失败", e);
        }
    }

    /**
     * @param taskId 要检查的任务 ID。
     * @return 当前任务是否已经满足所有依赖条件，可以进入 claim 流程。
     */
    boolean canStart(String taskId) {
        TaskRecord taskRecord = load(taskId);
        for (String dependency : taskRecord.blockedBy()) {
            Path dependencyPath = taskPath(dependency);
            if (!Files.exists(dependencyPath)) {
                return false;
            }
            if (!"completed".equals(load(dependency).status())) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param taskId 要认领的任务 ID。
     * @param owner 认领者名称，通常是 lead 或某个 teammate 名。
     * @return 状态已变成 {@code in_progress} 的任务记录。
     */
    TaskRecord claim(String taskId, String owner) {
        TaskRecord taskRecord = load(taskId);
        if (!"pending".equals(taskRecord.status())) {
            throw new IllegalStateException("Task " + taskId + " 当前状态不是 pending");
        }
        if (taskRecord.owner() != null && !taskRecord.owner().isBlank()) {
            throw new IllegalStateException("Task " + taskId + " 已被 " + taskRecord.owner() + " 认领");
        }
        if (!canStart(taskId)) {
            throw new IllegalStateException("Task " + taskId + " 依赖尚未完成");
        }
        TaskRecord updated = taskRecord.withStatus("in_progress", owner);
        save(updated);
        return updated;
    }

    /**
     * @param taskId 要完成的任务 ID。
     * @return 状态已变成 {@code completed} 的任务记录。
     */
    TaskRecord complete(String taskId) {
        TaskRecord taskRecord = load(taskId);
        if (!"in_progress".equals(taskRecord.status())) {
            throw new IllegalStateException("Task " + taskId + " 当前状态不是 in_progress");
        }
        TaskRecord updated = taskRecord.withStatus("completed", taskRecord.owner());
        save(updated);
        return updated;
    }

    /**
     * @param taskId 要绑定 worktree 的任务 ID。
     * @param worktree 绑定到这个任务的 worktree 名称，而不是完整路径。
     */
    void bindWorktree(String taskId, String worktree) {
        save(load(taskId).withWorktree(worktree));
    }

    private Path taskPath(String taskId) {
        return tasksDir.resolve(taskId + ".json");
    }
}

final class WorktreeService {

    /** 允许的 worktree 名字模式，避免出现危险路径或非法分支名。 */
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    /** 项目根目录，git worktree 命令都从这里发起。 */
    private final Path workspaceRoot;
    /** 教学版统一管理的 worktree 目录。 */
    private final Path worktreesDir;
    /** task 仓库，用来把 worktree 绑定关系写回对应任务。 */
    private final TaskStore taskStore;

    /**
     * @param workspaceRoot 项目根目录，git worktree 命令会在这里执行。
     * @param worktreesDir 教学版统一管理的 worktree 目录。
     * @param taskStore 用来把 task 和 worktree 关系落盘。
     */
    WorktreeService(Path workspaceRoot, Path worktreesDir, TaskStore taskStore) {
        this.workspaceRoot = workspaceRoot;
        this.worktreesDir = worktreesDir;
        this.taskStore = taskStore;
    }

    /**
     * @param name 用户传入的 worktree 名称。
     * @return 校验错误时返回错误文本；合法时返回 null。
     */
    String validateName(String name) {
        if (name == null || name.isBlank()) {
            return "Worktree name cannot be empty";
        }
        if (!VALID_NAME.matcher(name).matches() || ".".equals(name) || "..".equals(name)) {
            return "Invalid worktree name: " + name;
        }
        return null;
    }

    /**
     * @param name 新建 worktree 的名字。
     * @param taskId 如果不为空，表示创建成功后要把该 worktree 绑定到指定任务。
     * @return 可读结果文本，而不是结构化对象，方便直接作为 tool_result 返回给模型。
     */
    String create(String name, String taskId) {
        String validation = validateName(name);
        if (validation != null) {
            return "Error: " + validation;
        }
        Path path = worktreesDir.resolve(name);
        if (Files.exists(path)) {
            return "Worktree '" + name + "' already exists";
        }
        if (taskId != null && !taskId.isBlank()) {
            taskStore.load(taskId);
        }
        ShellResult shellResult = runGit(List.of("worktree", "add", path.toString(), "-b", "wt/" + name, "HEAD"));
        if (!shellResult.success()) {
            return "Git error: " + shellResult.output();
        }
        if (taskId != null && !taskId.isBlank()) {
            taskStore.bindWorktree(taskId, name);
        }
        return "Worktree '" + name + "' created at " + path;
    }

    /**
     * @param name 要删除的 worktree 名。
     * @param discardChanges 是否忽略未提交修改直接强删。
     * @return 删除结果说明。
     */
    String remove(String name, boolean discardChanges) {
        String validation = validateName(name);
        if (validation != null) {
            return validation;
        }
        Path path = worktreesDir.resolve(name);
        if (!Files.exists(path)) {
            return "Worktree '" + name + "' not found";
        }
        if (!discardChanges) {
            ShellResult statusResult = runGit(List.of("-C", path.toString(), "status", "--porcelain"));
            if (!statusResult.output().isBlank()) {
                return "Worktree '" + name + "' has uncommitted changes. Use discard_changes=true";
            }
        }
        ShellResult removeResult = runGit(List.of("worktree", "remove", path.toString(), "--force"));
        if (!removeResult.success()) {
            return "Failed to remove worktree '" + name + "': " + removeResult.output();
        }
        runGit(List.of("branch", "-D", "wt/" + name));
        return "Worktree '" + name + "' removed";
    }

    /**
     * @param name 要保留审查的 worktree 名。
     * @return 一句可读提示，告诉模型这个 worktree 现在会继续留在磁盘上。
     */
    String keep(String name) {
        String validation = validateName(name);
        if (validation != null) {
            return validation;
        }
        return "Worktree '" + name + "' kept for review (branch: wt/" + name + ")";
    }

    /**
     * @param taskRecord 某个 task 的完整记录。
     * @return 这个 task 实际应该在哪个目录里执行：
     *         没绑定 worktree 时返回项目根目录，绑定后返回对应 worktree 目录。
     */
    Path resolveTaskWorkingDir(TaskRecord taskRecord) {
        if (taskRecord.worktree() == null || taskRecord.worktree().isBlank()) {
            return workspaceRoot;
        }
        return worktreesDir.resolve(taskRecord.worktree());
    }

    /**
     * @param args 传给 git 的参数列表，例如 {@code ["worktree","add",...]}。
     * @return git 命令执行结果。
     */
    private ShellResult runGit(List<String> args) {
        return ShellSupport.runCommand(new ProcessBuilderArgs(workspaceRoot, prependGit(args)));
    }

    /**
     * @param args git 子命令参数。
     * @return 以 {@code git} 开头的完整命令数组。
     */
    private List<String> prependGit(List<String> args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(args);
        return command;
    }
}

record ShellResult(boolean success, String output) {
}

record ProcessBuilderArgs(Path workingDir, List<String> command) {
}

final class ShellSupport {

    private ShellSupport() {
    }

    /**
     * @param args 进程启动参数对象，里面同时包含工作目录和完整命令列表。
     * @return 进程是否成功退出，以及合并后的 stdout/stderr 文本。
     */
    static ShellResult runCommand(ProcessBuilderArgs args) {
        ProcessBuilder builder = new ProcessBuilder(args.command());
        builder.directory(args.workingDir().toFile());
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            String output;
            try (InputStream inputStream = process.getInputStream()) {
                output = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ShellResult(false, "Error: Timeout (120s)");
            }
            return new ShellResult(process.exitValue() == 0, output.trim());
        } catch (IOException e) {
            return new ShellResult(false, "Error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ShellResult(false, "Error: Interrupted");
        }
    }
}

final class MessageBus {

    /** 所有 JSONL 信箱文件所在目录。 */
    private final Path mailboxDir;
    /** MailboxMessage 的 JSON 序列化器。 */
    private final ObjectMapper objectMapper;

    /**
     * @param mailboxDir 所有 JSONL 信箱文件所在目录。
     * @param objectMapper 用来把 MailboxMessage 序列化成一行 JSON。
     */
    MessageBus(Path mailboxDir, ObjectMapper objectMapper) {
        this.mailboxDir = mailboxDir;
        this.objectMapper = objectMapper;
    }

    /**
     * @param from 消息发送者名称。
     * @param to 消息接收者名称，对应某个 mailbox 文件名。
     * @param type 消息类型，例如 {@code message}、{@code plan_request}。
     * @param content 消息正文。
     * @param metadata 附加字段，比如 requestId、taskId、approve 等协议信息。
     */
    synchronized void send(String from, String to, String type, String content, Map<String, Object> metadata) {
        MailboxMessage mailboxMessage = new MailboxMessage(from, to, type, content, metadata, Instant.now());
        Path path = mailboxDir.resolve(to + ".jsonl");
        try {
            Files.writeString(path, objectMapper.writeValueAsString(mailboxMessage) + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    Files.exists(path) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE);
        } catch (IOException e) {
            throw new IllegalStateException("写入 mailbox 失败: " + path, e);
        }
    }

    /**
     * @param name 要读取哪个信箱，例如 {@code lead} 或某个 teammate 名。
     * @return 当前信箱内的所有消息。读取成功后会清空对应 JSONL 文件。
     */
    synchronized List<MailboxMessage> readInbox(String name) {
        Path path = mailboxDir.resolve(name + ".jsonl");
        if (!Files.exists(path)) {
            return List.of();
        }
        try {
            List<MailboxMessage> messages = new ArrayList<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    messages.add(objectMapper.readValue(line, MailboxMessage.class));
                }
            }
            Files.deleteIfExists(path);
            return messages;
        } catch (IOException e) {
            throw new IllegalStateException("读取 mailbox 失败: " + path, e);
        }
    }
}

final class ProtocolRegistry {

    /** 协议消息底层实际投递依赖的消息总线。 */
    private final MessageBus messageBus;
    /** 当前所有 requestId 对应的协议状态快照。 */
    private final Map<String, ProtocolState> states = new ConcurrentHashMap<>();

    /**
     * @param messageBus 协议层底层用的消息总线。
     *                   ProtocolRegistry 自己不直接跑线程，只负责生成协议状态和收发协议消息。
     */
    ProtocolRegistry(MessageBus messageBus) {
        this.messageBus = messageBus;
    }

    /**
     * @param teammate 要求关闭的队友名称。
     * @return 本次 shutdown 请求的可读结果文本，其中会带上 requestId。
     */
    String requestShutdown(String teammate) {
        String requestId = requestId();
        states.put(requestId, new ProtocolState(requestId, "shutdown", "lead", teammate, "", "pending", Instant.now()));
        messageBus.send("lead", teammate, "shutdown_request", "请安全退出", Map.of("requestId", requestId));
        return "Requested shutdown from " + teammate + " (req:" + requestId + ")";
    }

    /**
     * @param teammate 要求提交计划的队友名称。
     * @param task 需要该队友先给计划的任务描述或任务标题。
     * @return 本次 plan 请求的可读结果文本。
     */
    String requestPlan(String teammate, String task) {
        String requestId = requestId();
        states.put(requestId, new ProtocolState(requestId, "plan", "lead", teammate, task, "pending", Instant.now()));
        messageBus.send("lead", teammate, "plan_request", "请先提交计划: " + task, Map.of("requestId", requestId, "task", task));
        return "Requested plan from " + teammate + " (req:" + requestId + ")";
    }

    /**
     * @param requestId 要审批的那次 plan 请求 ID。
     * @param approve true 表示批准，false 表示拒绝。
     * @param feedback 给队友的额外反馈文本，可以为空。
     * @return 审批结果说明。
     */
    String reviewPlan(String requestId, boolean approve, String feedback) {
        ProtocolState state = states.get(requestId);
        if (state == null) {
            return "Request not found: " + requestId;
        }
        String status = approve ? "approved" : "rejected";
        states.put(requestId, state.withStatus(status));
        messageBus.send("lead", state.target(), "plan_review", feedback == null ? "" : feedback,
                Map.of("requestId", requestId, "approve", approve, "task", state.payload()));
        return "Plan " + status + " for " + requestId;
    }

    /**
     * @param mailboxMessage lead 信箱里收到的一封消息。
     *                       如果它带 requestId 且属于 response，这里会同步更新本地协议状态。
     */
    void onLeadInbox(MailboxMessage mailboxMessage) {
        String requestId = Objects.toString(mailboxMessage.metadata().get("requestId"), "");
        if (requestId.isBlank()) {
            return;
        }
        ProtocolState state = states.get(requestId);
        if (state == null) {
            return;
        }
        if (mailboxMessage.type().endsWith("_response")) {
            states.put(requestId, state.withStatus(mailboxMessage.type()));
        }
    }

    private String requestId() {
        return "req_" + UUID.randomUUID().toString().substring(0, 8);
    }
}

final class BackgroundTaskService {

    /** 后台任务线程池，所有慢任务都在这里异步执行。 */
    private final ExecutorService executorService = Executors.newCachedThreadPool();
    /** 已完成后台任务生成的通知队列，等待主 loop 取走。 */
    private final Queue<ToolNotification> notifications = new ConcurrentLinkedQueue<>();
    /** 控制台输出器，用来打印后台完成日志。 */
    private final ClaudeConsole console;

    /**
     * @param console 用来打印后台任务完成日志。
     */
    BackgroundTaskService(ClaudeConsole console) {
        this.console = console;
    }

    /**
     * @param label 后台任务标签，通常直接用工具名，方便日志里辨认。
     * @param callable 真正要在后台线程执行的逻辑。
     * @return 后台任务 ID，主 loop 会立刻把这个 ID 回给模型做占位。
     */
    String submit(String label, java.util.concurrent.Callable<String> callable) {
        String taskId = "bg_" + UUID.randomUUID().toString().substring(0, 8);
        executorService.submit(() -> {
            try {
                String result = callable.call();
                notifications.add(new ToolNotification("background",
                        "[Background " + taskId + "/" + label + "] " + abbreviate(result, 500)));
                console.println("[background] completed " + taskId);
            } catch (Exception e) {
                notifications.add(new ToolNotification("background",
                        "[Background " + taskId + "/" + label + "] Error: " + e.getMessage()));
            }
        });
        return taskId;
    }

    /**
     * @return 当前所有已经完成、等待主 loop 注回的后台通知。
     */
    List<ToolNotification> drainNotifications() {
        List<ToolNotification> drained = new ArrayList<>();
        ToolNotification next;
        while ((next = notifications.poll()) != null) {
            drained.add(next);
        }
        return drained;
    }

    /**
     * 退出 CLI 时统一打断所有后台任务线程。
     */
    void stop() {
        executorService.shutdownNow();
    }

    /**
     * @param content 原始工具输出。
     * @param max 最多保留多少字符。
     * @return 截断后的简短文本，避免后台通知把控制台刷爆。
     */
    private String abbreviate(String content, int max) {
        if (content == null) {
            return "";
        }
        if (content.length() <= max) {
            return content;
        }
        return content.substring(0, max) + "...";
    }
}

final class CronSchedulerService {

    /** 用“年月日时分”生成去重键，避免同一分钟重复触发同一任务。 */
    private static final DateTimeFormatter FIRE_KEY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    /** durable cron job 落盘文件。 */
    private final Path jobsFile;
    /** 读写 CronJob 的 JSON 序列化器。 */
    private final ObjectMapper objectMapper;
    /** 控制台输出器，用来打印 cron 触发日志。 */
    private final ClaudeConsole console;
    /** 当前内存中的全部 cron job。 */
    private final Map<String, CronJob> jobs = new ConcurrentHashMap<>();
    /** 已触发但尚未被主 loop 消费的 cron 通知。 */
    private final Queue<ToolNotification> notifications = new ConcurrentLinkedQueue<>();
    /** 每秒扫描一次 cron 的单线程调度器。 */
    private final ScheduledExecutorService scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();

    /**
     * @param cronDir cron 持久化目录，里面会保存 jobs.json。
     * @param objectMapper 读写 CronJob 的 JSON 序列化器。
     * @param console 用来打印 cron 触发日志。
     */
    CronSchedulerService(Path cronDir, ObjectMapper objectMapper, ClaudeConsole console) {
        this.jobsFile = cronDir.resolve("jobs.json");
        this.objectMapper = objectMapper;
        this.console = console;
        loadJobs();
    }

    /**
     * 启动一个每秒 tick 一次的守护线程，用于扫描哪些 cron job 应该触发。
     */
    void start() {
        scheduledExecutorService.scheduleAtFixedRate(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * 停止 cron 守护线程。
     */
    void stop() {
        scheduledExecutorService.shutdownNow();
    }

    /**
     * @param cron 五段式 cron 表达式。
     * @param prompt 触发时要注入主会话的文本。
     * @param recurring 是否为循环任务。
     * @param durable 是否需要落盘持久化。
     * @return 调度结果文本。
     */
    String schedule(String cron, String prompt, boolean recurring, boolean durable) {
        String validation = validate(cron);
        if (validation != null) {
            return validation;
        }
        String jobId = "cron_" + UUID.randomUUID().toString().substring(0, 8);
        CronJob cronJob = new CronJob(jobId, cron, prompt, recurring, durable, Instant.now(), null);
        jobs.put(jobId, cronJob);
        saveJobs();
        return "Scheduled " + jobId;
    }

    /**
     * @return 当前所有已注册 cron job 的可读列表。
     */
    String listJobs() {
        if (jobs.isEmpty()) {
            return "(no cron jobs)";
        }
        List<String> lines = new ArrayList<>();
        jobs.values().stream()
                .sorted(Comparator.comparing(CronJob::jobId))
                .forEach(job -> lines.add(job.jobId() + " " + job.cron() + " -> " + job.prompt()));
        return String.join(System.lineSeparator(), lines);
    }

    /**
     * @param jobId 要取消的定时任务 ID。
     * @return 取消结果文本。
     */
    String cancel(String jobId) {
        CronJob removed = jobs.remove(jobId);
        saveJobs();
        return removed == null ? "Job not found: " + jobId : "Cancelled " + jobId;
    }

    /**
     * @return 所有已经触发、等待注回主 loop 的 cron 通知。
     */
    List<ToolNotification> drainNotifications() {
        List<ToolNotification> drained = new ArrayList<>();
        ToolNotification next;
        while ((next = notifications.poll()) != null) {
            drained.add(next);
        }
        return drained;
    }

    /**
     * @param cron 要校验的五段式 cron 表达式。
     * @return 合法返回 null，不合法返回错误说明。
     */
    String validate(String cron) {
        String[] fields = cron.split("\\s+");
        if (fields.length != 5) {
            return "Cron must have 5 fields";
        }
        int[][] ranges = new int[][]{{0, 59}, {0, 23}, {1, 31}, {1, 12}, {0, 6}};
        for (int i = 0; i < fields.length; i++) {
            String error = validateField(fields[i], ranges[i][0], ranges[i][1]);
            if (error != null) {
                return error;
            }
        }
        return null;
    }

    /**
     * @param cron 已通过基础格式校验的 cron 表达式。
     * @param time 当前时间点。
     * @return 这个 cron 表达式在当前时间点是否命中。
     */
    boolean matches(String cron, LocalDateTime time) {
        String[] fields = cron.split("\\s+");
        int[] values = {time.getMinute(), time.getHour(), time.getDayOfMonth(), time.getMonthValue(), time.getDayOfWeek().getValue() % 7};
        for (int i = 0; i < fields.length; i++) {
            if (!matchesField(fields[i], values[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * 每秒执行一次的扫描逻辑。
     * 它会检查哪些 cron 在“这一分钟”应该触发，并用 lastFireKey 防重。
     */
    private void tick() {
        LocalDateTime now = LocalDateTime.now();
        String fireKey = now.format(FIRE_KEY_FORMATTER);
        for (CronJob job : new ArrayList<>(jobs.values())) {
            if (fireKey.equals(job.lastFireKey())) {
                continue;
            }
            if (matches(job.cron(), now)) {
                notifications.add(new ToolNotification("cron", "[Scheduled] " + job.prompt()));
                console.println("[cron] fired " + job.jobId());
                if (job.recurring()) {
                    jobs.put(job.jobId(), job.withLastFireKey(fireKey));
                } else {
                    jobs.remove(job.jobId());
                }
                saveJobs();
            }
        }
    }

    /**
     * @param field cron 的某一段字段，例如 minute 段。
     * @param value 当前时间在这一段上的真实值。
     * @return 该字段是否匹配当前值。
     */
    private boolean matchesField(String field, int value) {
        if ("*".equals(field)) {
            return true;
        }
        return Integer.parseInt(field) == value;
    }

    /**
     * @param field cron 单字段文本。
     * @param lower 该字段允许的最小值。
     * @param upper 该字段允许的最大值。
     * @return 合法返回 null，不合法返回错误说明。
     */
    private String validateField(String field, int lower, int upper) {
        if ("*".equals(field)) {
            return null;
        }
        try {
            int value = Integer.parseInt(field);
            if (value < lower || value > upper) {
                return "Cron field out of range: " + field;
            }
            return null;
        } catch (NumberFormatException e) {
            return "Cron field must be * or integer: " + field;
        }
    }

    /**
     * 只把 durable=true 的任务写回 jobs.json。
     */
    private void saveJobs() {
        List<CronJob> durableJobs = jobs.values().stream().filter(CronJob::durable).toList();
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(jobsFile.toFile(), durableJobs);
        } catch (IOException e) {
            throw new IllegalStateException("保存 cron jobs 失败", e);
        }
    }

    /**
     * CLI 启动时从磁盘恢复 durable cron jobs。
     */
    private void loadJobs() {
        if (!Files.exists(jobsFile)) {
            return;
        }
        try {
            List<CronJob> persisted = objectMapper.readValue(jobsFile.toFile(), new TypeReference<>() {
            });
            for (CronJob job : persisted) {
                jobs.put(job.jobId(), job);
            }
        } catch (IOException e) {
            throw new IllegalStateException("加载 cron jobs 失败", e);
        }
    }
}

final class McpRegistry {

    /** 教学版内置的所有可连接 mock MCP server。 */
    private final Map<String, MockMcpServer> availableServers = new LinkedHashMap<>();
    /** 当前已经 connect 成功的 server 名集合。 */
    private final Set<String> connectedServers = ConcurrentHashMap.newKeySet();

    /**
     * 教学版只内置两个 mock server：
     * - docs
     * - deploy
     * 这里的重点是演示“工具池动态装配”，不是实现真实远程 MCP transport。
     */
    McpRegistry() {
        availableServers.put("docs", new DocsMcpServer());
        availableServers.put("deploy", new DeployMcpServer());
    }

    /**
     * @param name 要连接的 mock MCP server 名称。
     * @return 连接结果文本。连接成功后，对应工具要等到下一轮 assemble 才会暴露给模型。
     */
    String connect(String name) {
        String normalized = name == null ? "" : name.trim().toLowerCase();
        if (!availableServers.containsKey(normalized)) {
            return "Unknown MCP server: " + name;
        }
        connectedServers.add(normalized);
        return "Connected MCP server: " + normalized;
    }

    /**
     * @return 当前已经 connect 的 server 名列表。
     */
    Set<String> connectedServers() {
        return Set.copyOf(connectedServers);
    }

    /**
     * @return 所有已连接 server 暴露出来的工具定义。
     *         这一步只组装“模型可见的工具说明”，不执行工具。
     */
    Map<String, ToolDefinition> connectedToolDefinitions() {
        Map<String, ToolDefinition> definitions = new LinkedHashMap<>();
        for (String server : connectedServers) {
            availableServers.get(server).toolDefinitions(server).forEach(tool -> definitions.put(tool.name(), tool));
        }
        return definitions;
    }

    /**
     * @return 所有已连接 server 的真实 handler。
     *         这一步组装的是 Java 侧执行逻辑。
     */
    Map<String, ClaudeToolHandler> connectedHandlers() {
        Map<String, ClaudeToolHandler> handlers = new LinkedHashMap<>();
        for (String server : connectedServers) {
            handlers.putAll(availableServers.get(server).handlers(server));
        }
        return handlers;
    }
}

interface MockMcpServer {
    /**
     * @param serverName 当前 server 的注册名，例如 docs。
     * @return 这个 server 暴露给模型看的工具定义列表。
     */
    List<ToolDefinition> toolDefinitions(String serverName);

    /**
     * @param serverName 当前 server 的注册名。
     * @return 对应工具名到 handler 的映射。
     */
    Map<String, ClaudeToolHandler> handlers(String serverName);
}

final class DocsMcpServer implements MockMcpServer {

    @Override
    public List<ToolDefinition> toolDefinitions(String serverName) {
        return List.of(
                new ToolDefinition("mcp__" + serverName + "__search", "Search mock docs", schema("query")),
                new ToolDefinition("mcp__" + serverName + "__read", "Read mock doc page", schema("page"))
        );
    }

    @Override
    public Map<String, ClaudeToolHandler> handlers(String serverName) {
        Map<String, ClaudeToolHandler> handlers = new LinkedHashMap<>();
        handlers.put("mcp__" + serverName + "__search", (input, context) -> ToolExecutionResult.of(
                "Mock docs results for '" + Objects.toString(input.get("query"), "") + "': agent loop, tool dispatch, cron scheduler"));
        handlers.put("mcp__" + serverName + "__read", (input, context) -> ToolExecutionResult.of(
                "Mock doc page '" + Objects.toString(input.get("page"), "") + "': The agent loop keeps calling the model until tool use stops."));
        return handlers;
    }

    private Map<String, Object> schema(String requiredField) {
        return Map.of(
                "type", "object",
                "properties", Map.of(requiredField, Map.of("type", "string")),
                "required", List.of(requiredField)
        );
    }
}

final class DeployMcpServer implements MockMcpServer {

    @Override
    public List<ToolDefinition> toolDefinitions(String serverName) {
        return List.of(
                new ToolDefinition("mcp__" + serverName + "__list_targets", "List mock deployment targets", Map.of("type", "object", "properties", Map.of())),
                new ToolDefinition("mcp__" + serverName + "__deploy_preview", "Mock deploy preview", schema("artifact"))
        );
    }

    @Override
    public Map<String, ClaudeToolHandler> handlers(String serverName) {
        Map<String, ClaudeToolHandler> handlers = new LinkedHashMap<>();
        handlers.put("mcp__" + serverName + "__list_targets", (input, context) ->
                ToolExecutionResult.of("Targets: preview, staging"));
        handlers.put("mcp__" + serverName + "__deploy_preview", (input, context) ->
                ToolExecutionResult.of("Preview deployed for artifact: " + Objects.toString(input.get("artifact"), "")));
        return handlers;
    }

    private Map<String, Object> schema(String requiredField) {
        return Map.of(
                "type", "object",
                "properties", Map.of(requiredField, Map.of("type", "string")),
                "required", List.of(requiredField)
        );
    }
}

final class HookRegistry {

    /** 工具执行前触发的 hook 列表，通常放权限检查。 */
    private final List<PreToolUseHook> preToolUseHooks = new ArrayList<>();
    /** 工具执行后触发的 hook 列表，通常放日志或输出审计。 */
    private final List<PostToolUseHook> postToolUseHooks = new ArrayList<>();
    /** 会话停止前触发的 hook 列表。 */
    private final List<StopHook> stopHooks = new ArrayList<>();
    /** 控制台输出器，用来打印 hook 触发日志。 */
    private final ClaudeConsole console;

    /**
     * @param console 用来打印 hook 触发日志。
     */
    HookRegistry(ClaudeConsole console) {
        this.console = console;
    }

    /**
     * @param hook 一个前置工具 hook。
     *             它可以在真正执行工具之前阻断本次调用，例如权限检查。
     */
    void registerPreToolUseHook(PreToolUseHook hook) {
        preToolUseHooks.add(hook);
    }

    /**
     * @param hook 一个后置工具 hook。
     *             它会在工具执行完成后运行，常见用途是日志、审计、大输出提示。
     */
    void registerPostToolUseHook(PostToolUseHook hook) {
        postToolUseHooks.add(hook);
    }

    /**
     * @param hook 一个 stop hook。
     *             当本轮对话没有更多 tool_use、即将结束时触发。
     */
    void registerStopHook(StopHook hook) {
        stopHooks.add(hook);
    }

    /**
     * @param block 模型当前请求执行的工具调用块。
     * @param context 当前工具执行上下文。
     * @return 如果某个 hook 拦截了工具调用，就返回阻断原因；否则返回 empty。
     */
    Optional<String> runPreToolUse(ToolUseBlock block, ToolExecutionContext context) {
        console.println("[hook] pre " + block.name());
        for (PreToolUseHook hook : preToolUseHooks) {
            Optional<String> blocked = hook.apply(block, context);
            if (blocked.isPresent()) {
                return blocked;
            }
        }
        return Optional.empty();
    }

    /**
     * @param block 刚执行完的工具调用块。
     * @param output 工具执行结果文本。
     * @param context 本次执行的上下文。
     */
    void runPostToolUse(ToolUseBlock block, String output, ToolExecutionContext context) {
        for (PostToolUseHook hook : postToolUseHooks) {
            hook.apply(block, output, context);
        }
    }

    /**
     * @param messages 本次会话当前的完整消息历史快照。
     * @param context 停止时的执行上下文。
     */
    void runStop(List<ClaudeMessage> messages, ToolExecutionContext context) {
        for (StopHook stopHook : stopHooks) {
            stopHook.apply(messages, context);
        }
    }
}

@FunctionalInterface
interface PreToolUseHook {
    /**
     * @param block 即将执行的工具调用块。
     * @param context 当前执行上下文。
     * @return 返回阻断原因时，主 loop 会停止这次工具执行；返回 empty 表示放行。
     */
    Optional<String> apply(ToolUseBlock block, ToolExecutionContext context);
}

@FunctionalInterface
interface PostToolUseHook {
    /**
     * @param block 已执行完成的工具调用块。
     * @param output 工具返回文本。
     * @param context 当前执行上下文。
     */
    void apply(ToolUseBlock block, String output, ToolExecutionContext context);
}

@FunctionalInterface
interface StopHook {
    /**
     * @param messages 当前会话消息历史。
     * @param context 本次停止时的执行上下文。
     */
    void apply(List<ClaudeMessage> messages, ToolExecutionContext context);
}

final class PermissionService {

    /** 永远不允许执行的危险命令片段。 */
    private static final List<String> DENY_PATTERNS = List.of(
            "rm -rf /",
            "sudo",
            "shutdown",
            "reboot",
            "mkfs",
            "dd if=",
            "git reset --hard",
            "git clean -fd"
    );
    /** 当前运行配置，主要读取 interactiveApproval 这类权限行为开关。 */
    private final ClaudeConfig config;
    /** 需要人工审批时，用它把提示打印到控制台。 */
    private final ClaudeConsole console;

    /**
     * @param workspaceRoot 当前项目根目录。这里保留这个参数是为了强调权限系统和工作区边界的关系。
     * @param config 当前运行配置，主要用其中的 interactiveApproval 判断是否允许交互审批。
     * @param console 需要人工审批时，用来在控制台提示用户。
     */
    PermissionService(Path workspaceRoot, ClaudeConfig config, ClaudeConsole console) {
        this.config = config;
        this.console = console;
    }

    /**
     * @param block 模型请求执行的工具调用块。
     * @param context 当前工具执行上下文。
     * @return 如果这次调用被拒绝，则返回拒绝原因；否则返回 empty 表示允许执行。
     */
    Optional<String> check(ToolUseBlock block, ToolExecutionContext context) {
        if ("bash".equals(block.name())) {
            String command = Objects.toString(block.input().get("command"), "");
            for (String denyPattern : DENY_PATTERNS) {
                if (command.contains(denyPattern)) {
                    return Optional.of("Permission denied: blocked by deny list (" + denyPattern + ")");
                }
            }
            if (command.contains("rm ") || command.contains("del /") || command.contains("chmod 777")) {
                return askApproval("Potentially destructive command: " + command);
            }
        }
        if (List.of("write_file", "edit_file").contains(block.name())) {
            String path = Objects.toString(block.input().get("path"), "");
            if (path.contains("..")) {
                return Optional.of("Permission denied: path traversal not allowed");
            }
        }
        if (block.name().startsWith("mcp__deploy__")) {
            return askApproval("Deploy MCP action requires approval");
        }
        return Optional.empty();
    }

    /**
     * @param reason 需要向用户解释的审批原因。
     * @return 用户允许时返回 empty；用户拒绝或当前是非交互模式时返回拒绝原因。
     */
    private Optional<String> askApproval(String reason) {
        if (!config.interactiveApproval() || System.console() == null) {
            return Optional.of("Permission denied: " + reason + " (non-interactive mode)");
        }
        console.println("[approval] " + reason + " Allow? [y/N]");
        String answer = System.console().readLine();
        if (answer == null || (!"y".equalsIgnoreCase(answer.trim()) && !"yes".equalsIgnoreCase(answer.trim()))) {
            return Optional.of("Permission denied by user");
        }
        return Optional.empty();
    }
}

final class PromptAssembler {

    /** 当前共享运行时，prompt 所需的 skills、memory、MCP 状态都从这里取。 */
    private final ClaudeRuntime runtime;

    /**
     * @param runtime 当前运行时容器。
     *                PromptAssembler 通过它读取 skills、memory、MCP 连接状态等动态上下文。
     */
    PromptAssembler(ClaudeRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * @return 本轮要发给模型的 system prompt。
     *         这段 prompt 不是常量，而是按当前 runtime 状态动态拼出来的。
     */
    String buildSystemPrompt() {
        List<String> sections = new ArrayList<>();
        sections.add("You are a coding agent. Act directly and use tools when needed.");
        sections.add("Workspace: " + runtime.workspaceRoot());
        sections.add("Current time: " + LocalDateTime.now());
        sections.add("Skills catalog:\n" + runtime.skillRegistry().catalog() + "\nUse load_skill(name) when needed.");
        Path memoryFile = runtime.paths().memoryDir().resolve("MEMORY.md");
        if (Files.exists(memoryFile)) {
            try {
                sections.add("Relevant memories:\n" + Files.readString(memoryFile, StandardCharsets.UTF_8));
            } catch (IOException e) {
                sections.add("Relevant memories unavailable: " + e.getMessage());
            }
        }
        if (!runtime.mcpRegistry().connectedServers().isEmpty()) {
            sections.add("Connected MCP servers: " + String.join(", ", runtime.mcpRegistry().connectedServers()));
        }
        return String.join("\n\n", sections);
    }
}
