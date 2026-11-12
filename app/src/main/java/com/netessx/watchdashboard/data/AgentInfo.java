package com.netessx.watchdashboard.data;

/** Current state of one connected agent. */
public final class AgentInfo {

    public final String id;
    public final String name;
    public final String status;
    public final String detail;
    public final String task;
    public final long updatedAt;

    public AgentInfo(String id, String name, String status, String detail, String task, long updatedAt) {
        this.id = id;
        this.name = name;
        this.status = status == null ? "idle" : status;
        this.detail = detail == null ? "" : detail;
        this.task = task == null ? "" : task;
        this.updatedAt = updatedAt;
    }

    public boolean isWaiting() {
        return "waiting".equalsIgnoreCase(status);
    }
}
