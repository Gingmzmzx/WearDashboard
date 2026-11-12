package com.netessx.watchdashboard.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.netessx.watchdashboard.R;
import com.netessx.watchdashboard.data.AgentInfo;
import com.netessx.watchdashboard.data.AgentRequest;
import com.netessx.watchdashboard.data.AgentStore;
import com.netessx.watchdashboard.data.TaskResult;
import com.netessx.watchdashboard.net.Protocol;

import java.util.List;

/** Main watch face: connection state, actionable requests, agents and recent results. */
public class DashboardScreen extends ScrollView {

    public interface Listener {
        void onOpenSettings();

        void onOpenRequest(AgentRequest request);

        void onConfigure();

        void onReconnect();
    }

    private final Context context;
    private final TextView clockView;

    public DashboardScreen(Context context, AgentStore.Snapshot snapshot, Listener listener) {
        super(context);
        this.context = context;
        setFillViewport(true);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setVerticalScrollBarEnabled(false);

        LinearLayout root = UiKit.column(context);
        root.setPadding(UiKit.dp(context, 12), UiKit.dp(context, 10),
                UiKit.dp(context, 12), UiKit.dp(context, 20));
        addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        clockView = UiKit.medium(context, UiKit.clock(System.currentTimeMillis()), 13f,
                UiKit.textColor(context));
        clockView.setTypeface(Typeface.MONOSPACE);

        root.addView(buildHeader(snapshot, listener));
        addTop(root, buildHero(snapshot, listener), 12);

        if (snapshot.outboxCount > 0) {
            TextView note = UiKit.text(context,
                    context.getString(R.string.outbox_pending, snapshot.outboxCount),
                    9.5f, UiKit.color(context, R.color.warn));
            note.setPadding(UiKit.dp(context, 9), UiKit.dp(context, 5),
                    UiKit.dp(context, 9), UiKit.dp(context, 5));
            note.setBackground(UiKit.rounded(context,
                    UiKit.color(context, R.color.warn_dim), 0, 0f, 10f));
            addTop(root, note, 8);
        }

        if (!snapshot.pending.isEmpty()) {
            addSection(root, context.getString(R.string.label_waiting),
                    String.valueOf(snapshot.pending.size()));
            for (AgentRequest request : snapshot.pending) {
                addTop(root, buildRequestCard(request, listener), 8);
            }
        }

        if (!snapshot.agents.isEmpty()) {
            addSection(root, context.getString(R.string.label_agents), null);
            addTop(root, buildAgentsCard(snapshot.agents), 8);
        }

        if (!snapshot.recent.isEmpty()) {
            addSection(root, context.getString(R.string.label_recent), null);
            addTop(root, buildRecentCard(snapshot.recent), 8);
        }

        TextView footer = UiKit.text(context,
                context.getString(R.string.label_protocol, Protocol.VERSION,
                        snapshot.serverName.isEmpty()
                                ? context.getString(R.string.unknown_server)
                                : snapshot.serverName),
                8.5f, UiKit.mutedColor(context));
        footer.setGravity(Gravity.CENTER);
        addTop(root, footer, 18);
    }

    public void tick(long now) {
        clockView.setText(UiKit.clock(now));
    }

    private LinearLayout buildHeader(AgentStore.Snapshot snapshot, Listener listener) {
        LinearLayout header = UiKit.row(context);
        int statusColor = UiKit.statusColor(context, snapshot.connection);
        header.addView(UiKit.dot(context, statusColor, 7f));
        TextView status = UiKit.medium(context,
                UiKit.statusLabel(context, snapshot.connection), 9.5f, statusColor);
        status.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.leftMargin = UiKit.dp(context, 6);
        header.addView(status, statusParams);

        View spacer = new View(context);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1f));
        header.addView(clockView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ImageView settings = UiKit.iconButton(context, R.drawable.ic_settings,
                UiKit.mutedColor(context), 17);
        settings.setContentDescription(context.getString(R.string.content_desc_settings));
        settings.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onOpenSettings();
            }
        });
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(
                UiKit.dp(context, 27), UiKit.dp(context, 27));
        settingsParams.leftMargin = UiKit.dp(context, 6);
        header.addView(settings, settingsParams);
        return header;
    }

    private View buildHero(AgentStore.Snapshot snapshot, Listener listener) {
        final boolean hasPending = !snapshot.pending.isEmpty();
        boolean online = snapshot.connection == AgentStore.Connection.ONLINE;
        int fill;
        int stroke;
        if (!snapshot.configured) {
            fill = UiKit.color(context, R.color.surface);
            stroke = UiKit.color(context, R.color.border);
        } else if (hasPending) {
            fill = UiKit.color(context, R.color.warn_dim);
            stroke = 0xFF4A3A19;
        } else if (online) {
            fill = UiKit.color(context, R.color.accent_dim);
            stroke = 0xFF1D4A33;
        } else {
            fill = UiKit.color(context, R.color.surface_alt);
            stroke = UiKit.color(context, R.color.border);
        }

        LinearLayout card = hasPending
                ? UiKit.clickableCard(context, fill, stroke)
                : UiKit.card(context, fill, stroke);

        if (!snapshot.configured) {
            card.addView(UiKit.label(context, context.getString(R.string.status_unconfigured)));
            TextView title = UiKit.medium(context, context.getString(R.string.setup_title),
                    18f, UiKit.textColor(context));
            addTop(card, title, 5);
            TextView body = UiKit.text(context, context.getString(R.string.setup_body),
                    10.5f, UiKit.mutedColor(context));
            addTop(card, body, 5);
            TextView button = UiKit.button(context,
                    context.getString(R.string.action_configure),
                    UiKit.accentColor(context), 0, UiKit.color(context, R.color.bg));
            button.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    listener.onConfigure();
                }
            });
            button.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(context, 44)));
            addTop(card, button, 11);
            return card;
        }

        if (hasPending) {
            final AgentRequest first = snapshot.pending.get(0);
            card.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    listener.onOpenRequest(first);
                }
            });
            LinearLayout row = UiKit.row(context);
            TextView count = UiKit.medium(context, String.valueOf(snapshot.pending.size()),
                    34f, UiKit.color(context, R.color.warn));
            row.addView(count);
            LinearLayout column = UiKit.column(context);
            TextView pending = UiKit.medium(context,
                    context.getString(R.string.label_pending), 11f,
                    UiKit.color(context, R.color.warn));
            TextView earliest = UiKit.text(context, first.displayTitle(), 10.5f,
                    UiKit.mutedColor(context));
            earliest.setMaxLines(2);
            earliest.setEllipsize(TextUtils.TruncateAt.END);
            column.addView(pending);
            addTop(column, earliest, 2);
            LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            columnParams.leftMargin = UiKit.dp(context, 10);
            row.addView(column, columnParams);
            card.addView(row);
            return card;
        }

        if (online) {
            LinearLayout row = UiKit.row(context);
            row.addView(UiKit.icon(context, R.drawable.ic_check,
                    UiKit.accentColor(context), 22));
            LinearLayout column = UiKit.column(context);
            TextView title = UiKit.medium(context,
                    context.getString(R.string.label_all_clear), 17f,
                    UiKit.accentColor(context));
            TextView body = UiKit.text(context,
                    context.getString(R.string.label_online_idle), 10f,
                    UiKit.mutedColor(context));
            column.addView(title);
            addTop(column, body, 3);
            LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            columnParams.leftMargin = UiKit.dp(context, 9);
            row.addView(column, columnParams);
            card.addView(row);
            return card;
        }

        int accent = snapshot.connection == AgentStore.Connection.AUTH_FAILED
                ? UiKit.color(context, R.color.danger)
                : UiKit.color(context, R.color.warn);
        LinearLayout row = UiKit.row(context);
        row.addView(UiKit.icon(context, R.drawable.ic_warning, accent, 20));
        LinearLayout column = UiKit.column(context);
        TextView title = UiKit.medium(context,
                UiKit.statusLabel(context, snapshot.connection), 15f,
                UiKit.textColor(context));
        String detail = snapshot.lastError.isEmpty()
                ? context.getString(R.string.cached_hint)
                : snapshot.lastError;
        TextView body = UiKit.text(context, detail, 9.5f, UiKit.mutedColor(context));
        body.setMaxLines(2);
        body.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(title);
        addTop(column, body, 3);
        LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        columnParams.leftMargin = UiKit.dp(context, 9);
        row.addView(column, columnParams);

        final boolean needSettings = snapshot.connection == AgentStore.Connection.AUTH_FAILED;
        TextView action = UiKit.pill(context,
                context.getString(needSettings
                        ? R.string.action_settings
                        : R.string.action_reconnect),
                accent, UiKit.color(context, R.color.bg));
        action.setClickable(true);
        action.setFocusable(true);
        action.setPadding(UiKit.dp(context, 10), UiKit.dp(context, 6),
                UiKit.dp(context, 10), UiKit.dp(context, 6));
        action.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (needSettings) {
                    listener.onOpenSettings();
                } else {
                    listener.onReconnect();
                }
            }
        });
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionParams.leftMargin = UiKit.dp(context, 6);
        row.addView(action, actionParams);
        card.addView(row);
        return card;
    }

    private View buildRequestCard(final AgentRequest request, final Listener listener) {
        boolean highRisk = request.isHighRisk();
        int stroke = highRisk ? 0xFF4A2020 : UiKit.color(context, R.color.border);
        LinearLayout card = UiKit.clickableCard(context,
                UiKit.color(context, R.color.surface), stroke);
        card.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onOpenRequest(request);
            }
        });

        LinearLayout top = UiKit.row(context);
        int typeFill;
        int typeColor;
        int typeLabel;
        if (request.isPermission()) {
            typeLabel = R.string.type_permission;
            if (highRisk) {
                typeFill = UiKit.color(context, R.color.danger_dim);
                typeColor = UiKit.color(context, R.color.danger);
            } else {
                typeFill = UiKit.color(context, R.color.warn_dim);
                typeColor = UiKit.color(context, R.color.warn);
            }
        } else {
            typeLabel = R.string.type_question;
            typeFill = UiKit.color(context, R.color.info_dim);
            typeColor = UiKit.color(context, R.color.info);
        }
        top.addView(UiKit.pill(context, context.getString(typeLabel), typeFill, typeColor));
        View spacer = new View(context);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1f));
        top.addView(UiKit.text(context,
                UiKit.relativeTime(context, request.createdAt), 9f,
                UiKit.mutedColor(context)));
        card.addView(top);

        String titleText = request.displayTitle();
        if (titleText.isEmpty()) {
            titleText = context.getString(typeLabel);
        }
        TextView title = UiKit.medium(context, titleText, 13.5f,
                UiKit.textColor(context));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        addTop(card, title, 7);

        String bodyText = request.displayBody();
        if (!bodyText.isEmpty()) {
            TextView body = UiKit.text(context, bodyText, 9.5f,
                    UiKit.mutedColor(context));
            body.setMaxLines(2);
            body.setEllipsize(TextUtils.TruncateAt.END);
            addTop(card, body, 4);
        }

        LinearLayout bottom = UiKit.row(context);
        String agent = request.agentName.isEmpty()
                ? context.getString(R.string.unknown_agent)
                : request.agentName;
        bottom.addView(UiKit.text(context, agent, 9f, UiKit.mutedColor(context)));
        if (request.isPermission() && !request.risk.isEmpty()) {
            int riskLabel;
            int riskFill;
            int riskColor;
            if (highRisk) {
                riskLabel = R.string.risk_high;
                riskFill = UiKit.color(context, R.color.danger_dim);
                riskColor = UiKit.color(context, R.color.danger);
            } else if ("medium".equalsIgnoreCase(request.risk)) {
                riskLabel = R.string.risk_medium;
                riskFill = UiKit.color(context, R.color.warn_dim);
                riskColor = UiKit.color(context, R.color.warn);
            } else {
                riskLabel = R.string.risk_low;
                riskFill = UiKit.color(context, R.color.surface_alt);
                riskColor = UiKit.mutedColor(context);
            }
            TextView risk = UiKit.pill(context, context.getString(riskLabel),
                    riskFill, riskColor);
            LinearLayout.LayoutParams riskParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            riskParams.leftMargin = UiKit.dp(context, 6);
            bottom.addView(risk, riskParams);
        }
        View bottomSpacer = new View(context);
        bottom.addView(bottomSpacer, new LinearLayout.LayoutParams(0, 0, 1f));
        if (request.expiresAt > 0) {
            long remaining = request.expiresAt - System.currentTimeMillis();
            int expiryColor = remaining > 0 && remaining <= 60_000L
                    ? UiKit.color(context, R.color.danger)
                    : UiKit.mutedColor(context);
            bottom.addView(UiKit.text(context,
                    UiKit.expiry(context, request, System.currentTimeMillis()),
                    9f, expiryColor));
        }
        ImageView chevron = UiKit.icon(context, R.drawable.ic_chevron_right,
                UiKit.mutedColor(context), 14);
        LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(
                UiKit.dp(context, 14), UiKit.dp(context, 14));
        chevronParams.leftMargin = UiKit.dp(context, 2);
        bottom.addView(chevron, chevronParams);
        addTop(card, bottom, 8);
        return card;
    }

    private View buildAgentsCard(List<AgentInfo> agents) {
        LinearLayout card = UiKit.card(context,
                UiKit.color(context, R.color.surface), UiKit.color(context, R.color.border));
        int limit = Math.min(agents.size(), 4);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                card.addView(UiKit.divider(context));
            }
            AgentInfo agent = agents.get(i);
            LinearLayout row = UiKit.row(context);
            row.addView(UiKit.dot(context, UiKit.agentStatusColor(context, agent.status), 6f));
            LinearLayout column = UiKit.column(context);
            TextView name = UiKit.medium(context, agent.name, 11.5f,
                    UiKit.textColor(context));
            column.addView(name);
            String sub = !agent.task.isEmpty() ? agent.task : agent.detail;
            if (!sub.isEmpty()) {
                TextView detail = UiKit.text(context, sub, 9f, UiKit.mutedColor(context));
                detail.setMaxLines(1);
                detail.setEllipsize(TextUtils.TruncateAt.END);
                addTop(column, detail, 2);
            }
            LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            columnParams.leftMargin = UiKit.dp(context, 8);
            row.addView(column, columnParams);
            row.addView(UiKit.medium(context,
                    UiKit.agentStatusLabel(context, agent.status), 9f,
                    UiKit.agentStatusColor(context, agent.status)));
            card.addView(row);
        }
        if (agents.size() > limit) {
            TextView more = UiKit.text(context, "…", 10f, UiKit.mutedColor(context));
            addTop(card, more, 4);
        }
        return card;
    }

    private View buildRecentCard(List<TaskResult> recent) {
        LinearLayout card = UiKit.card(context,
                UiKit.color(context, R.color.surface), UiKit.color(context, R.color.border));
        int limit = Math.min(recent.size(), 4);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                card.addView(UiKit.divider(context));
            }
            TaskResult task = recent.get(i);
            int tint = task.isSuccess()
                    ? UiKit.accentColor(context)
                    : UiKit.color(context, R.color.warn);
            LinearLayout row = UiKit.row(context);
            row.addView(UiKit.icon(context,
                    task.isSuccess() ? R.drawable.ic_check : R.drawable.ic_warning,
                    tint, 14));
            LinearLayout column = UiKit.column(context);
            TextView title = UiKit.medium(context,
                    task.title.isEmpty()
                            ? context.getString(R.string.task_summary_fallback)
                            : task.title,
                    11f, UiKit.textColor(context));
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            column.addView(title);
            if (!task.summary.isEmpty()) {
                TextView summary = UiKit.text(context, task.summary, 9f,
                        UiKit.mutedColor(context));
                summary.setMaxLines(1);
                summary.setEllipsize(TextUtils.TruncateAt.END);
                addTop(column, summary, 2);
            }
            LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            columnParams.leftMargin = UiKit.dp(context, 7);
            row.addView(column, columnParams);
            row.addView(UiKit.text(context,
                    UiKit.relativeTime(context, task.completedAt), 8.5f,
                    UiKit.mutedColor(context)));
            card.addView(row);
        }
        return card;
    }

    private void addSection(LinearLayout root, String label, String extra) {
        LinearLayout row = UiKit.row(context);
        row.addView(UiKit.label(context, label));
        View spacer = new View(context);
        row.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1f));
        if (extra != null) {
            row.addView(UiKit.medium(context, extra, 9.5f,
                    UiKit.color(context, R.color.warn)));
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = UiKit.dp(context, 16);
        params.bottomMargin = UiKit.dp(context, 8);
        root.addView(row, params);
    }

    private void addTop(LinearLayout parent, View child, float topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = UiKit.dp(context, topDp);
        parent.addView(child, params);
    }
}
