package com.netessx.watchdashboard.data;

/** One tappable answer supplied by the server for a permission or question. */
public final class AgentOption {

    public final String id;
    public final String label;
    public final String style;

    public AgentOption(String id, String label, String style) {
        this.id = id;
        this.label = label;
        this.style = style == null ? "neutral" : style;
    }

    public boolean isApprove() {
        return "approve".equalsIgnoreCase(style);
    }

    public boolean isDeny() {
        return "deny".equalsIgnoreCase(style);
    }
}
