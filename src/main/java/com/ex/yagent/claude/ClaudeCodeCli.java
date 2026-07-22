package com.ex.yagent.claude;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 教学版 CLI 入口。
 * 这里不依赖 Spring，不走 HTTP，目的就是让你直接在控制台观察 agent loop 如何自己运转。
 */
public class ClaudeCodeCli {

    public static void main(String[] args) throws IOException {
        Path workspaceRoot = Path.of("").toAbsolutePath().normalize();
        ClaudeConfig config = ClaudeConfig.load(workspaceRoot);
        ClaudeConsole console = new ClaudeConsole();
        ClaudeRuntime runtime = new ClaudeRuntime(workspaceRoot, config, console);
        ClaudeAgentLoop loop = new ClaudeAgentLoop(runtime, new DashScopeLlmClient(config));
        ClaudeSession session = new ClaudeSession("lead", ToolMode.LEAD, workspaceRoot);
        ReentrantLock turnLock = new ReentrantLock();

        // 独立的通知线程会轮询 cron / background / inbox，并在有消息时自动推进一轮 loop。
        Thread notificationThread = new Thread(() -> runNotificationLoop(loop, session, runtime, turnLock), "claude-notifications");
        notificationThread.setDaemon(true);
        notificationThread.start();

        printBanner(runtime, config, console);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            while (true) {
                System.out.print("claude-java >> ");
                String input = reader.readLine();
                if (input == null || input.isBlank() || "q".equalsIgnoreCase(input) || "exit".equalsIgnoreCase(input)) {
                    break;
                }
                turnLock.lock();
                try {
                    String answer = loop.runUserTurn(session, input);
                    if (answer != null && !answer.isBlank()) {
                        console.println(answer);
                    }
                } finally {
                    turnLock.unlock();
                }
            }
        } finally {
            loop.teammateManager().stopAll();
            runtime.shutdown();
        }
    }

    /**
     * 通知线程的职责只有一个：有异步事件时，把它们重新塞回主会话。
     */
    private static void runNotificationLoop(ClaudeAgentLoop loop, ClaudeSession session, ClaudeRuntime runtime, ReentrantLock turnLock) {
        while (true) {
            try {
                TimeUnit.SECONDS.sleep(1);
                if (!turnLock.tryLock()) {
                    continue;
                }
                try {
                    var notifications = runtime.pollPassiveNotifications();
                    if (notifications.isEmpty()) {
                        continue;
                    }
                    // 这里把异步通知逐条灌回 session，再走同一条 loop 继续推理。
                    for (ToolNotification notification : notifications) {
                        session.addMessage(ClaudeMessage.userText(notification.message()));
                    }
                    String answer = loop.runPassiveTurn(session, "[Passive notifications received]");
                    if (answer != null && !answer.isBlank()) {
                        runtime.console().println(answer);
                    }
                } finally {
                    turnLock.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                runtime.console().println("[notification-loop] Error: " + e.getMessage());
            }
        }
    }

    private static void printBanner(ClaudeRuntime runtime, ClaudeConfig config, ClaudeConsole console) {
        console.println("Claude Java Harness (S20 teaching edition)");
        console.println("model      : " + config.model());
        console.println("workspace  : " + runtime.workspaceRoot());
        console.println("skills     : " + String.join(", ", runtime.skillRegistry().names()));
        console.println("cron       : started");
        console.println("type q / exit to quit");
        console.println("");
    }
}
