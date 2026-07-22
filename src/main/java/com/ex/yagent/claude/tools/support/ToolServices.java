package com.ex.yagent.claude.tools.support;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface ToolServices {

    String connectMcp(String name);

    String loadSkill(String name);

    String runSubagent(String description, Path workingDirectory, String ownerName);

    String spawnTeammate(String name, String role, String prompt, Path workingDirectory);

    ToolTaskView createTask(String subject, String description, List<String> blockedBy);

    List<ToolTaskView> listTasks();

    String getTaskJson(String taskId) throws IOException;

    ToolTaskView claimTask(String taskId, String ownerName);

    ToolTaskView completeTask(String taskId);

    String scheduleCron(String cron, String prompt, boolean recurring, boolean durable);

    String listCrons();

    String cancelCron(String jobId);

    void sendMessage(String from, String to, String type, String content, Map<String, Object> metadata);

    String drainInboxBuffer();

    String requestShutdown(String teammate);

    String requestPlan(String teammate, String task);

    String reviewPlan(String requestId, boolean approve, String feedback);

    String createWorktree(String name, String taskId);

    String removeWorktree(String name, boolean discardChanges);

    String keepWorktree(String name);
}
