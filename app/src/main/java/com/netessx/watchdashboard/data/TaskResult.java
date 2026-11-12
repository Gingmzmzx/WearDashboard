package com.netessx.watchdashboard.data;

/** A finished agent task shown in the "recent" list. */
public final class TaskResult {

    public final String taskId;
    public final String agentId;
    public final String agentName;
    public final String title;
    public final String summary;
    public final String outcome;
    public final long completedAt;
    public final long durationMs;

    public TaskResult(String taskId, String agentId, String agentName, String title,
                      String summary, String outcome, long completedAt, long durationMs) {
        this.taskId = taskId == null ? "" : taskId;
        this.agentId = agentId == null ? "" : agentId;
        this.agentName = agentName == null ? "" : agentName;
        this.title = title == null ? "" : title;
        this.summary = summary == null ? "" : summary;
        this.outcome = outcome == null ? "success" : outcome;
        this.completedAt = completedAt;
        this.durationMs = durationMs;
    }

    public boolean isSuccess() {
        return "success".equalsIgnoreCase(outcome);
    }
}
