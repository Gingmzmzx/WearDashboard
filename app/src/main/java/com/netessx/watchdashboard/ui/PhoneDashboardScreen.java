package com.netessx.watchdashboard.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.netessx.watchdashboard.R;
import com.netessx.watchdashboard.data.AgentInfo;
import com.netessx.watchdashboard.data.AgentOption;
import com.netessx.watchdashboard.data.AgentRequest;
import com.netessx.watchdashboard.data.AgentStore;
import com.netessx.watchdashboard.data.TaskResult;
import com.netessx.watchdashboard.net.Protocol;

import java.util.ArrayList;
import java.util.List;

/** Phone layout. Shares storage, service and protocol with the watch UI. */
public class PhoneDashboardScreen extends ScrollView {

    public interface Listener extends DashboardScreen.Listener {
        void onQuickRespond(AgentRequest request, AgentOption option);
    }

    private static final class ExpiryRef {
        final AgentRequest request;
        final TextView view;

        ExpiryRef(AgentRequest request, TextView view) {
            this.request = request;
            this.view = view;
        }
    }

    private final Context context;
    private final AgentStore.Snapshot snapshot;
    private final Listener listener;
    private final List<ExpiryRef> expiryRefs = new ArrayList<>();

    public PhoneDashboardScreen(Context context, AgentStore.Snapshot snapshot, Listener listener) {
        super(context);
        this.context = context;
        this.snapshot = snapshot;
        this.listener = listener;
        setFillViewport(true);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setVerticalScrollBarEnabled(false);

        LinearLayout root = UiKit.column(context);
        root.setPadding(UiKit.dp(context, 20), UiKit.dp(context, 18),
                UiKit.dp(context, 20), UiKit.dp(context, 36));
        addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(buildHeader());
        addTop(root, buildStatusLine(), 10);
        addTop(root, buildHero(), 18);

        if (snapshot.outboxCount > 0) {
            TextView note = UiKit.text(context,
                    context.getString(R.string.outbox_pending, snapshot.outboxCount),
                    12f, UiKit.color(context, R.color.warn));
            note.setPadding(UiKit.dp(context, 12), UiKit.dp(context, 8),
                    UiKit.dp(context, 12), UiKit.dp(context, 8));
            note.setBackground(UiKit.rounded(context,
                    UiKit.color(context, R.color.warn_dim), 0, 0f, 12f));
            addTop(root, note, 12);
        }

        if (!snapshot.pending.isEmpty()) {
            addSection(root, context.getString(R.string.label_waiting),
                    snapshot.pending.size() + "");
            for (AgentRequest request : snapshot.pending) {
                addTop(root, buildRequestCard(request), 10);
            }
        }

        addSection(root, context.getString(R.string.phone_section_agents), null);
        addTop(root, buildAgentsCard(), 10);

        addSection(root, context.getString(R.string.phone_section_recent), null);
        addTop(root, buildRecentCard(), 10);

        String server = snapshot.serverName.isEmpty()
                ? context.getString(R.string.unknown_server)
                : snapshot.serverName;
        TextView footer = UiKit.text(context,
                context.getString(R.string.phone_server_line, server, Protocol.VERSION),
                10f, UiKit.mutedColor(context));
        footer.setGravity(Gravity.CENTER);
        addTop(root, footer, 26);

    }

    public void tick(long now) {
        for (ExpiryRef ref : expiryRefs) {
            bindExpiry(ref.view, ref.request, now);
        }
    }

    private View buildHeader() {
        LinearLayout column = UiKit.column(context);

        LinearLayout top = UiKit.row(context);
        TextView eyebrow = UiKit.label(context, context.getString(R.string.phone_eyebrow));
        eyebrow.setTextColor(UiKit.accentColor(context));
        top.addView(eyebrow);
        View spacer = new View(context);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1f));

        ImageView settings = new ImageView(context);
        settings.setImageResource(R.drawable.ic_settings);
        settings.setColorFilter(UiKit.textColor(context));
        settings.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int padding = UiKit.dp(context, 10);
        settings.setPadding(padding, padding, padding, padding);
        settings.setBackground(UiKit.ripple(UiKit.rounded(context,
                UiKit.color(context, R.color.surface), UiKit.color(context, R.color.border),
                1f, 14f)));
        settings.setContentDescription(context.getString(R.string.phone_content_desc_settings));
        settings.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onOpenSettings();
            }
        });
        top.addView(settings, new LinearLayout.LayoutParams(
                UiKit.dp(context, 42), UiKit.dp(context, 42)));
        column.addView(top);

        TextView title = UiKit.medium(context, context.getString(R.string.phone_title),
                27f, UiKit.textColor(context));
        addTop(column, title, 8);

        TextView subtitle = UiKit.text(context, context.getString(R.string.phone_subtitle),
                12.5f, UiKit.mutedColor(context));
        addTop(column, subtitle, 2);
        return column;
    }

    private View buildStatusLine() {
        LinearLayout row = UiKit.row(context);
        row.addView(UiKit.dot(context, UiKit.statusColor(context, snapshot.connection), 8f));
        TextView status = UiKit.medium(context,
                UiKit.statusLabel(context, snapshot.connection), 12f,
                UiKit.statusColor(context, snapshot.connection));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.leftMargin = UiKit.dp(context, 7);
        row.addView(status, statusParams);

        if (!snapshot.serverName.isEmpty()) {
            TextView server = UiKit.text(context,
                    context.getString(R.string.separator_dot) + snapshot.serverName,
                    11f, UiKit.mutedColor(context));
            row.addView(server);
        }
        return row;
    }

    private View buildHero() {
        if (!snapshot.configured) {
            LinearLayout card = phoneCard(UiKit.color(context, R.color.surface),
                    UiKit.color(context, R.color.border));
            card.addView(UiKit.label(context, context.getString(R.string.setup_title)));
            TextView title = UiKit.medium(context,
                    context.getString(R.string.phone_hero_unconfigured), 21f,
                    UiKit.textColor(context));
            addTop(card, title, 8);
            TextView body = UiKit.text(context,
                    context.getString(R.string.phone_hero_unconfigured_body), 13f,
                    UiKit.mutedColor(context));
            addTop(card, body, 6);
            TextView button = phonePrimaryButton(context.getString(R.string.action_configure));
            button.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    listener.onConfigure();
                }
            });
            addTop(card, button, 16);
            return card;
        }

        if (!snapshot.pending.isEmpty()) {
            final AgentRequest first = snapshot.pending.get(0);
            int fill = UiKit.color(context, R.color.warn_dim);
            int stroke = 0xFF4B3A18;
            LinearLayout card = UiKit.clickableCard(context, fill, stroke);
            card.setPadding(UiKit.dp(context, 18), UiKit.dp(context, 17),
                    UiKit.dp(context, 18), UiKit.dp(context, 17));
            card.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    listener.onOpenRequest(first);
                }
            });

            LinearLayout row = UiKit.row(context);
            TextView count = UiKit.medium(context, String.valueOf(snapshot.pending.size()),
                    42f, UiKit.color(context, R.color.warn));
            row.addView(count);
            LinearLayout labels = UiKit.column(context);
            TextView unit = UiKit.medium(context,
                    context.getString(R.string.phone_hero_pending_unit), 14f,
                    UiKit.color(context, R.color.warn));
            labels.addView(unit);
            TextView label = UiKit.text(context,
                    context.getString(R.string.phone_hero_pending), 11.5f,
                    UiKit.mutedColor(context));
            addTop(labels, label, 1);
            LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            labelsParams.leftMargin = UiKit.dp(context, 12);
            row.addView(labels, labelsParams);
            ImageView chevron = UiKit.icon(context, R.drawable.ic_chevron_right,
                    UiKit.color(context, R.color.warn), 22);
            row.addView(chevron);
            card.addView(row);

            TextView earliest = UiKit.text(context,
                    first.displayTitle(), 12f, UiKit.mutedColor(context));
            earliest.setMaxLines(1);
            earliest.setEllipsize(TextUtils.TruncateAt.END);
            addTop(card, earliest, 12);
            return card;
        }

        boolean online = snapshot.connection == AgentStore.Connection.ONLINE;
        LinearLayout card;
        if (online) {
            card = UiKit.card(context, UiKit.color(context, R.color.accent_dim), 0xFF1E4A34);
            card.setPadding(UiKit.dp(context, 18), UiKit.dp(context, 17),
                    UiKit.dp(context, 18), UiKit.dp(context, 17));
            LinearLayout row = UiKit.row(context);
            row.addView(UiKit.icon(context, R.drawable.ic_check,
                    UiKit.accentColor(context), 30));
            LinearLayout labels = UiKit.column(context);
            TextView title = UiKit.medium(context,
                    context.getString(R.string.phone_hero_idle), 20f,
                    UiKit.accentColor(context));
            labels.addView(title);
            TextView body = UiKit.text(context,
                    context.getString(R.string.phone_hero_idle_body), 12.5f,
                    UiKit.mutedColor(context));
            addTop(labels, body, 4);
            LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            labelsParams.leftMargin = UiKit.dp(context, 12);
            row.addView(labels, labelsParams);
            card.addView(row);
        } else {
            boolean auth = snapshot.connection == AgentStore.Connection.AUTH_FAILED;
            card = UiKit.card(context,
                    UiKit.color(context, R.color.surface), UiKit.color(context, R.color.border));
            card.setPadding(UiKit.dp(context, 18), UiKit.dp(context, 17),
                    UiKit.dp(context, 18), UiKit.dp(context, 17));
            LinearLayout row = UiKit.row(context);
            row.addView(UiKit.icon(context, R.drawable.ic_warning,
                    auth ? UiKit.color(context, R.color.danger)
                            : UiKit.color(context, R.color.warn), 28));
            LinearLayout labels = UiKit.column(context);
            TextView title = UiKit.medium(context,
                    context.getString(R.string.phone_hero_offline), 18f,
                    UiKit.textColor(context));
            labels.addView(title);
            String detail = snapshot.lastError.isEmpty()
                    ? context.getString(R.string.phone_hero_offline_body)
                    : snapshot.lastError;
            TextView body = UiKit.text(context, detail, 12f, UiKit.mutedColor(context));
            body.setMaxLines(2);
            body.setEllipsize(TextUtils.TruncateAt.END);
            addTop(labels, body, 4);
            LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            labelsParams.leftMargin = UiKit.dp(context, 12);
            row.addView(labels, labelsParams);
            TextView action = UiKit.pill(context,
                    context.getString(auth
                            ? R.string.phone_status_settings
                            : R.string.phone_status_reconnect),
                    UiKit.color(context, R.color.warn),
                    UiKit.color(context, R.color.bg));
            action.setPadding(UiKit.dp(context, 12), UiKit.dp(context, 8),
                    UiKit.dp(context, 12), UiKit.dp(context, 8));
            action.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (snapshot.connection == AgentStore.Connection.AUTH_FAILED) {
                        listener.onOpenSettings();
                    } else {
                        listener.onReconnect();
                    }
                }
            });
            row.addView(action);
            card.addView(row);
        }
        return card;
    }

    private View buildRequestCard(final AgentRequest request) {
        int stroke = request.isHighRisk()
                ? 0xFF4E2626
                : UiKit.color(context, R.color.border);
        LinearLayout card = UiKit.clickableCard(context,
                UiKit.color(context, R.color.surface), stroke);
        card.setPadding(UiKit.dp(context, 16), UiKit.dp(context, 15),
                UiKit.dp(context, 16), UiKit.dp(context, 15));
        card.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onOpenRequest(request);
            }
        });

        LinearLayout top = UiKit.row(context);
        int typeLabel = request.isPermission()
                ? R.string.type_permission : R.string.type_question;
        int typeFill = request.isPermission()
                ? (request.isHighRisk()
                        ? UiKit.color(context, R.color.danger_dim)
                        : UiKit.color(context, R.color.warn_dim))
                : UiKit.color(context, R.color.info_dim);
        int typeColor = request.isPermission()
                ? (request.isHighRisk()
                        ? UiKit.color(context, R.color.danger)
                        : UiKit.color(context, R.color.warn))
                : UiKit.color(context, R.color.info);
        top.addView(UiKit.pill(context, context.getString(typeLabel), typeFill, typeColor));
        if (request.isPermission() && !request.risk.isEmpty()) {
            TextView risk = riskChip(request);
            LinearLayout.LayoutParams riskParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            riskParams.leftMargin = UiKit.dp(context, 6);
            top.addView(risk, riskParams);
        }
        View spacer = new View(context);
        top.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1f));
        TextView expiry = UiKit.medium(context, "", 11f, UiKit.mutedColor(context));
        expiry.setTypeface(Typeface.MONOSPACE);
        top.addView(expiry);
        expiryRefs.add(new ExpiryRef(request, expiry));
        bindExpiry(expiry, request, System.currentTimeMillis());
        card.addView(top);

        String titleText = request.displayTitle();
        if (titleText.isEmpty()) {
            titleText = context.getString(typeLabel);
        }
        TextView title = UiKit.medium(context, titleText, 17f, UiKit.textColor(context));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        addTop(card, title, 10);

        String body = request.displayBody();
        if (!body.isEmpty()) {
            TextView bodyView = UiKit.text(context, body,
                    request.isPermission() ? 12f : 12.5f, UiKit.mutedColor(context));
            if (request.isPermission()) {
                bodyView.setTypeface(Typeface.MONOSPACE);
            }
            bodyView.setMaxLines(2);
            bodyView.setEllipsize(TextUtils.TruncateAt.END);
            addTop(card, bodyView, 5);
        }

        LinearLayout meta = UiKit.row(context);
        meta.addView(UiKit.text(context,
                request.agentName.isEmpty()
                        ? context.getString(R.string.unknown_agent)
                        : request.agentName,
                11f, UiKit.mutedColor(context)));
        meta.addView(UiKit.text(context,
                context.getString(R.string.separator_dot)
                        + UiKit.relativeTime(context, request.createdAt),
                11f, UiKit.mutedColor(context)));
        View metaSpacer = new View(context);
        meta.addView(metaSpacer, new LinearLayout.LayoutParams(0, 0, 1f));
        if (snapshot.connection != AgentStore.Connection.ONLINE) {
            meta.addView(UiKit.pill(context, context.getString(R.string.phone_offline_pill),
                    UiKit.color(context, R.color.surface_alt),
                    UiKit.mutedColor(context)));
        }
        addTop(card, meta, 10);

        addTop(card, buildQuickActions(request), 12);
        List<AgentOption> allOptions = request.effectiveOptions();
        if (allOptions.size() > 2) {
            TextView hint = UiKit.text(context,
                    context.getString(R.string.phone_quick_all_options, allOptions.size()),
                    10.5f, UiKit.mutedColor(context));
            addTop(card, hint, 7);
        }
        return card;
    }

    private View buildQuickActions(final AgentRequest request) {
        LinearLayout row = UiKit.row(context);
        boolean online = snapshot.connection == AgentStore.Connection.ONLINE;
        List<AgentOption> options = request.effectiveOptions();

        if (request.isPermission()) {
            AgentOption approve = firstStyle(options, true);
            AgentOption deny = firstStyle(options, false);
            if (request.isHighRisk()) {
                if (deny != null) {
                    addQuick(row, request, deny, online, false, true);
                }
                addQuickOpen(row, request, online);
            } else {
                if (approve != null) {
                    addQuick(row, request, approve, online, true, false);
                } else {
                    addQuickOpen(row, request, online);
                }
                if (deny != null) {
                    addQuick(row, request, deny, online, false, true);
                }
            }
        } else {
            if (options.isEmpty()) {
                addQuickOpen(row, request, online);
            } else {
                int limit = Math.min(2, options.size());
                for (int i = 0; i < limit; i++) {
                    AgentOption option = options.get(i);
                    addQuick(row, request, option, online, option.isApprove(), option.isDeny());
                }
            }
        }
        return row;
    }

    private void addQuick(LinearLayout row, final AgentRequest request,
                          final AgentOption option, boolean enabled,
                          boolean primary, boolean danger) {
        int fill;
        int stroke;
        int textColor;
        if (primary) {
            fill = UiKit.accentColor(context);
            stroke = 0;
            textColor = UiKit.color(context, R.color.bg);
        } else if (danger) {
            fill = UiKit.color(context, R.color.danger_dim);
            stroke = UiKit.color(context, R.color.danger);
            textColor = UiKit.color(context, R.color.danger);
        } else {
            fill = UiKit.color(context, R.color.surface_alt);
            stroke = UiKit.color(context, R.color.border);
            textColor = UiKit.textColor(context);
        }
        TextView button = UiKit.button(context, option.label, fill, stroke, textColor);
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f);
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.42f);
        button.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onQuickRespond(request, option);
            }
        });
        row.addView(button, weightedButtonParams(row.getChildCount() > 0));
    }

    private void addQuickOpen(LinearLayout row, final AgentRequest request, boolean enabled) {
        TextView button = UiKit.button(context,
                context.getString(R.string.phone_quick_open),
                UiKit.color(context, R.color.surface_alt),
                UiKit.color(context, R.color.border), UiKit.textColor(context));
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13.5f);
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.42f);
        button.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!snapshot.pending.isEmpty()) {
                    listener.onOpenRequest(snapshot.pending.get(0));
                }
            }
        });
        row.addView(button, weightedButtonParams(row.getChildCount() > 0));
    }

    private LinearLayout.LayoutParams weightedButtonParams(boolean withLeftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, UiKit.dp(context, 46), 1f);
        if (withLeftMargin) {
            params.leftMargin = UiKit.dp(context, 8);
        }
        return params;
    }

    private AgentOption firstStyle(List<AgentOption> options, boolean approve) {
        for (AgentOption option : options) {
            if (approve ? option.isApprove() : option.isDeny()) {
                return option;
            }
        }
        return null;
    }

    private TextView riskChip(AgentRequest request) {
        int label;
        int fill;
        int color;
        if (request.isHighRisk()) {
            label = R.string.risk_high;
            fill = UiKit.color(context, R.color.danger_dim);
            color = UiKit.color(context, R.color.danger);
        } else if ("medium".equalsIgnoreCase(request.risk)) {
            label = R.string.risk_medium;
            fill = UiKit.color(context, R.color.warn_dim);
            color = UiKit.color(context, R.color.warn);
        } else {
            label = R.string.risk_low;
            fill = UiKit.color(context, R.color.surface_alt);
            color = UiKit.mutedColor(context);
        }
        return UiKit.pill(context, context.getString(label), fill, color);
    }

    private View buildAgentsCard() {
        LinearLayout card = phoneCard(UiKit.color(context, R.color.surface),
                UiKit.color(context, R.color.border));
        if (snapshot.agents.isEmpty()) {
            TextView empty = UiKit.text(context, context.getString(R.string.phone_empty_agents),
                    12.5f, UiKit.mutedColor(context));
            card.addView(empty);
            return card;
        }
        int limit = Math.min(snapshot.agents.size(), 8);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                card.addView(UiKit.divider(context));
            }
            AgentInfo agent = snapshot.agents.get(i);
            LinearLayout row = UiKit.row(context);
            row.addView(UiKit.dot(context,
                    UiKit.agentStatusColor(context, agent.status), 7f));
            LinearLayout labels = UiKit.column(context);
            TextView name = UiKit.medium(context, agent.name, 14f, UiKit.textColor(context));
            labels.addView(name);
            String detail = !agent.task.isEmpty() ? agent.task : agent.detail;
            if (!detail.isEmpty()) {
                TextView sub = UiKit.text(context, detail, 11.5f, UiKit.mutedColor(context));
                sub.setMaxLines(1);
                sub.setEllipsize(TextUtils.TruncateAt.END);
                addTop(labels, sub, 2);
            }
            LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            labelsParams.leftMargin = UiKit.dp(context, 9);
            row.addView(labels, labelsParams);
            row.addView(UiKit.medium(context,
                    UiKit.agentStatusLabel(context, agent.status), 11f,
                    UiKit.agentStatusColor(context, agent.status)));
            card.addView(row);
        }
        return card;
    }

    private View buildRecentCard() {
        LinearLayout card = phoneCard(UiKit.color(context, R.color.surface),
                UiKit.color(context, R.color.border));
        if (snapshot.recent.isEmpty()) {
            card.addView(UiKit.text(context, context.getString(R.string.phone_empty_recent),
                    12.5f, UiKit.mutedColor(context)));
            return card;
        }
        int limit = Math.min(snapshot.recent.size(), 10);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                card.addView(UiKit.divider(context));
            }
            TaskResult task = snapshot.recent.get(i);
            int tint = task.isSuccess()
                    ? UiKit.accentColor(context)
                    : UiKit.color(context, R.color.warn);
            LinearLayout row = UiKit.row(context);
            FrameLayout iconWrap = new FrameLayout(context);
            iconWrap.setBackground(UiKit.rounded(context,
                    task.isSuccess()
                            ? UiKit.color(context, R.color.accent_dim)
                            : UiKit.color(context, R.color.warn_dim),
                    0, 0f, 10f));
            ImageView icon = UiKit.icon(context,
                    task.isSuccess() ? R.drawable.ic_check : R.drawable.ic_warning,
                    tint, 16);
            FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(
                    UiKit.dp(context, 16), UiKit.dp(context, 16));
            iconParams.gravity = Gravity.CENTER;
            iconWrap.addView(icon, iconParams);
            row.addView(iconWrap, new LinearLayout.LayoutParams(
                    UiKit.dp(context, 32), UiKit.dp(context, 32)));
            LinearLayout labels = UiKit.column(context);
            TextView title = UiKit.medium(context,
                    task.title.isEmpty()
                            ? context.getString(R.string.task_summary_fallback)
                            : task.title, 14f, UiKit.textColor(context));
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(title);
            if (!task.summary.isEmpty()) {
                TextView summary = UiKit.text(context, task.summary, 11.5f,
                        UiKit.mutedColor(context));
                summary.setMaxLines(1);
                summary.setEllipsize(TextUtils.TruncateAt.END);
                addTop(labels, summary, 2);
            }
            LinearLayout.LayoutParams labelsParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            labelsParams.leftMargin = UiKit.dp(context, 10);
            row.addView(labels, labelsParams);
            row.addView(UiKit.text(context,
                    UiKit.relativeTime(context, task.completedAt), 10.5f,
                    UiKit.mutedColor(context)));
            card.addView(row);
        }
        return card;
    }

    private LinearLayout phoneCard(int fill, int stroke) {
        LinearLayout card = UiKit.card(context, fill, stroke);
        card.setPadding(UiKit.dp(context, 16), UiKit.dp(context, 15),
                UiKit.dp(context, 16), UiKit.dp(context, 15));
        return card;
    }

    private TextView phonePrimaryButton(String label) {
        TextView button = UiKit.button(context, label,
                UiKit.accentColor(context), 0, UiKit.color(context, R.color.bg));
        button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
        button.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(context, 50)));
        return button;
    }

    private void bindExpiry(TextView view, AgentRequest request, long now) {
        if (request.expiresAt <= 0) {
            view.setVisibility(GONE);
            return;
        }
        view.setVisibility(VISIBLE);
        long remaining = request.expiresAt - now;
        view.setText(UiKit.expiry(context, request, now));
        int color;
        if (remaining <= 0) {
            color = UiKit.color(context, R.color.danger);
        } else if (remaining <= 60_000L) {
            color = UiKit.color(context, R.color.danger);
        } else if (request.isHighRisk()) {
            color = UiKit.color(context, R.color.warn);
        } else {
            color = UiKit.mutedColor(context);
        }
        view.setTextColor(color);
    }

    private void addSection(LinearLayout root, String title, String trailing) {
        LinearLayout row = UiKit.row(context);
        TextView label = UiKit.medium(context, title, 13f, UiKit.mutedColor(context));
        label.setLetterSpacing(0.04f);
        row.addView(label);
        View spacer = new View(context);
        row.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1f));
        if (trailing != null) {
            row.addView(UiKit.pill(context, trailing,
                    UiKit.color(context, R.color.warn),
                    UiKit.color(context, R.color.bg)));
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = UiKit.dp(context, 24);
        params.bottomMargin = UiKit.dp(context, 10);
        root.addView(row, params);
    }

    private void addTop(LinearLayout parent, View child, float topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = UiKit.dp(context, topDp);
        parent.addView(child, params);
    }

}
