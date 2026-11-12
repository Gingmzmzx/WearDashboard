package com.netessx.watchdashboard.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A permission request or a question waiting for the user's answer. */
public final class AgentRequest {

    public final String requestId;
    public final String kind;
    public final String agentId;
    public final String agentName;
    public final String title;
    public final String detail;
    public final String risk;
    public final String question;
    public final String context;
    public final long createdAt;
    public final long expiresAt;
    public final boolean allowFreeText;
    public final List<AgentOption> options;

    public AgentRequest(String requestId, String kind, String agentId, String agentName,
                        String title, String detail, String risk, String question,
                        String context, long createdAt, long expiresAt, boolean allowFreeText,
                        List<AgentOption> options) {
        this.requestId = requestId;
        this.kind = kind == null ? "permission" : kind;
        this.agentId = agentId == null ? "" : agentId;
        this.agentName = agentName == null ? "" : agentName;
        this.title = title == null ? "" : title;
        this.detail = detail == null ? "" : detail;
        this.risk = risk == null ? "" : risk;
        this.question = question == null ? "" : question;
        this.context = context == null ? "" : context;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.allowFreeText = allowFreeText;
        this.options = options == null
                ? Collections.<AgentOption>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(options));
    }

    public boolean isPermission() {
        return "permission".equalsIgnoreCase(kind);
    }

    public boolean isHighRisk() {
        return "high".equalsIgnoreCase(risk);
    }

    public boolean isExpired(long now) {
        return expiresAt > 0 && now >= expiresAt;
    }

    public String displayTitle() {
        if (!title.isEmpty()) {
            return title;
        }
        if (!question.isEmpty()) {
            return question;
        }
        return detail;
    }

    public String displayBody() {
        if (isPermission()) {
            return !detail.isEmpty() ? detail : context;
        }
        return !context.isEmpty() ? context : detail;
    }

    /** UI fallback if a permission request arrives without explicit options. */
    public List<AgentOption> effectiveOptions() {
        if (!options.isEmpty()) {
            return options;
        }
        if (isPermission()) {
            List<AgentOption> fallback = new ArrayList<>(2);
            fallback.add(new AgentOption("allow_once", "允许一次", "approve"));
            fallback.add(new AgentOption("deny", "拒绝", "deny"));
            return fallback;
        }
        return Collections.emptyList();
    }
}
