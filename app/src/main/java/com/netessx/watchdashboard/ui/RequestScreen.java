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
import com.netessx.watchdashboard.data.AgentOption;
import com.netessx.watchdashboard.data.AgentRequest;
import com.netessx.watchdashboard.data.AgentStore;

import java.util.ArrayList;
import java.util.List;

/** Detail screen for a permission request or agent question. */
public class RequestScreen extends ScrollView {

    public interface Listener {
        void onBack();

        void onRespond(AgentRequest request, AgentOption option);
    }

    private final Context context;
    private final AgentRequest request;
    private final boolean phone;
    private final Listener listener;
    private final List<View> actionViews = new ArrayList<>();
    private final TextView expiryView;
    private final TextView footer;
    private final TextView offlineBadge;
    private boolean online;
    private boolean expired;
    private boolean submitting;

    public RequestScreen(Context context, AgentRequest request,
                         AgentStore.Connection connection, Listener listener,
                         boolean phone) {
        super(context);
        this.context = context;
        this.request = request;
        this.listener = listener;
        this.phone = phone;
        this.online = connection == AgentStore.Connection.ONLINE;
        this.expired = request.isExpired(System.currentTimeMillis());

        setFillViewport(true);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setVerticalScrollBarEnabled(false);

        LinearLayout root = UiKit.column(context);
        if (phone) {
            root.setPadding(UiKit.dp(context, 20), UiKit.dp(context, 18),
                    UiKit.dp(context, 20), UiKit.dp(context, 32));
        } else {
            root.setPadding(UiKit.dp(context, 13), UiKit.dp(context, 10),
                    UiKit.dp(context, 13), UiKit.dp(context, 20));
        }
        addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = UiKit.row(context);
        ImageView back = UiKit.iconButton(context, R.drawable.ic_back,
                UiKit.textColor(context), phone ? 20 : 18);
        back.setContentDescription(context.getString(R.string.content_desc_back));
        back.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onBack();
            }
        });
        header.addView(back);
        TextView type = UiKit.label(context,
                context.getString(request.isPermission()
                        ? R.string.type_permission
                        : R.string.type_question));
        if (phone) {
            type.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f);
        }
        LinearLayout.LayoutParams typeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        typeParams.leftMargin = UiKit.dp(context, 4);
        header.addView(type, typeParams);
        View spacer = new View(context);
        header.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1f));
        offlineBadge = UiKit.pill(context,
                UiKit.statusLabel(context, connection),
                UiKit.color(context, R.color.warn_dim),
                UiKit.color(context, R.color.warn));
        offlineBadge.setVisibility(online ? GONE : VISIBLE);
        header.addView(offlineBadge);
        root.addView(header);

        TextView agent = UiKit.text(context,
                request.agentName.isEmpty()
                        ? context.getString(R.string.unknown_agent)
                        : request.agentName,
                phone ? 11.5f : 9.5f, UiKit.mutedColor(context));
        addTop(root, agent, phone ? 18 : 12);

        TextView title = UiKit.medium(context, request.displayTitle(),
                phone ? 24f : 18f, UiKit.textColor(context));
        title.setMaxLines(5);
        title.setEllipsize(TextUtils.TruncateAt.END);
        addTop(root, title, 4);

        LinearLayout meta = UiKit.row(context);
        if (request.isPermission() && !request.risk.isEmpty()) {
            int labelRes;
            int fill;
            int textColor;
            if (request.isHighRisk()) {
                labelRes = R.string.risk_high;
                fill = UiKit.color(context, R.color.danger_dim);
                textColor = UiKit.color(context, R.color.danger);
            } else if ("medium".equalsIgnoreCase(request.risk)) {
                labelRes = R.string.risk_medium;
                fill = UiKit.color(context, R.color.warn_dim);
                textColor = UiKit.color(context, R.color.warn);
            } else {
                labelRes = R.string.risk_low;
                fill = UiKit.color(context, R.color.surface_alt);
                textColor = UiKit.mutedColor(context);
            }
            meta.addView(UiKit.pill(context, context.getString(labelRes), fill, textColor));
        }
        View expirySpacer = new View(context);
        meta.addView(expirySpacer, new LinearLayout.LayoutParams(0, 0, 1f));
        expiryView = UiKit.medium(context,
                UiKit.expiry(context, request, System.currentTimeMillis()),
                phone ? 11.5f : 9.5f, UiKit.mutedColor(context));
        expiryView.setTypeface(Typeface.MONOSPACE);
        meta.addView(expiryView);
        addTop(root, meta, phone ? 12 : 9);

        String body = request.displayBody();
        if (!body.isEmpty()) {
            int maxChars = phone ? 8000 : 4000;
            if (body.length() > maxChars) {
                body = body.substring(0, maxChars) + "…";
            }
            LinearLayout detail = UiKit.card(context,
                    UiKit.color(context, R.color.surface_alt),
                    UiKit.color(context, R.color.border));
            int detailPad = phone ? 15 : 11;
            detail.setPadding(UiKit.dp(context, detailPad), UiKit.dp(context, detailPad),
                    UiKit.dp(context, detailPad), UiKit.dp(context, detailPad));
            TextView detailText = UiKit.text(context, body, phone ? 12.5f : 10.5f,
                    UiKit.textColor(context));
            if (request.isPermission()) {
                detailText.setTypeface(Typeface.MONOSPACE);
                detailText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                        phone ? 12f : 10f);
            }
            detailText.setTextIsSelectable(false);
            detail.addView(detailText);
            addTop(root, detail, phone ? 16 : 11);
        }

        List<AgentOption> options = request.effectiveOptions();
        if (options.isEmpty()) {
            TextView none = UiKit.text(context,
                    context.getString(R.string.question_no_options),
                    10.5f, UiKit.mutedColor(context));
            addTop(root, none, phone ? 20 : 14);
            TextView close = UiKit.button(context,
                    context.getString(R.string.action_close),
                    UiKit.color(context, R.color.surface_alt),
                    UiKit.color(context, R.color.border),
                    UiKit.textColor(context));
            tuneTextButton(close);
            close.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    listener.onBack();
                }
            });
            addButton(root, close, 10);
        } else {
            for (AgentOption option : options) {
                View action;
                if (request.isPermission() && request.isHighRisk() && option.isApprove()) {
                    HoldToApproveButton hold = new HoldToApproveButton(context,
                            option.label, new HoldToApproveButton.OnConfirmListener() {
                        @Override
                        public void onConfirmed() {
                            submit(option);
                        }
                    });
                    if (phone) {
                        hold.setLabelTextSizeSp(15f);
                    }
                    action = hold;
                    addButton(root, hold, phone ? 12 : 8);
                    TextView holdHint = UiKit.text(context,
                            context.getString(R.string.hold_progress_hint),
                            phone ? 11f : 9f, UiKit.mutedColor(context));
                    holdHint.setGravity(Gravity.CENTER);
                    addTop(root, holdHint, phone ? 6 : 4);
                } else {
                    int fill;
                    int stroke;
                    int textColor;
                    if (option.isApprove()) {
                        fill = UiKit.accentColor(context);
                        stroke = 0;
                        textColor = UiKit.color(context, R.color.bg);
                    } else if (option.isDeny()) {
                        fill = UiKit.color(context, R.color.danger_dim);
                        stroke = UiKit.color(context, R.color.danger);
                        textColor = UiKit.color(context, R.color.danger);
                    } else {
                        fill = UiKit.color(context, R.color.surface_alt);
                        stroke = UiKit.color(context, R.color.border);
                        textColor = UiKit.textColor(context);
                    }
                    TextView button = UiKit.button(context, option.label,
                            fill, stroke, textColor);
                    tuneTextButton(button);
                    final AgentOption selected = option;
                    button.setOnClickListener(new OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            submit(selected);
                        }
                    });
                    action = button;
                    addButton(root, button, phone ? 12 : 8);
                }
                actionViews.add(action);
            }
        }

        footer = UiKit.text(context, footerText(), phone ? 11.5f : 9.5f,
                UiKit.mutedColor(context));
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, UiKit.dp(context, phone ? 18 : 12), 0, 0);
        root.addView(footer);
        applyEnabledState();
    }

    public void tick(long now) {
        if (request.expiresAt > 0) {
            expired = request.isExpired(now);
            long remaining = request.expiresAt - now;
            expiryView.setText(UiKit.expiry(context, request, now));
            int color = expired || remaining <= 60_000L
                    ? UiKit.color(context, R.color.danger)
                    : UiKit.mutedColor(context);
            expiryView.setTextColor(color);
            applyEnabledState();
        }
    }

    public void refresh(AgentStore.Connection connection) {
        online = connection == AgentStore.Connection.ONLINE;
        expired = request.isExpired(System.currentTimeMillis());
        offlineBadge.setText(UiKit.statusLabel(context, connection));
        offlineBadge.setVisibility(online ? GONE : VISIBLE);
        footer.setText(footerText());
        applyEnabledState();
    }

    private void submit(AgentOption option) {
        if (submitting || !isActionAllowed()) {
            return;
        }
        submitting = true;
        applyEnabledState();
        listener.onRespond(request, option);
    }

    private boolean isActionAllowed() {
        return online && !expired && !submitting;
    }

    private void applyEnabledState() {
        boolean enabled = isActionAllowed();
        for (View action : actionViews) {
            action.setEnabled(enabled);
            action.setAlpha(enabled ? 1f : 0.45f);
        }
    }

    private String footerText() {
        if (expired) {
            return context.getString(R.string.request_footer_expired);
        }
        if (!online) {
            return context.getString(R.string.request_footer_offline);
        }
        if (request.isPermission() && request.isHighRisk()) {
            return context.getString(R.string.request_footer_high);
        }
        return context.getString(R.string.request_footer);
    }

    private void addTop(LinearLayout parent, View child, float topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = UiKit.dp(context, topDp);
        parent.addView(child, params);
    }

    private void addButton(LinearLayout parent, View button, float topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                UiKit.dp(context, phone ? 54 : 48));
        params.topMargin = UiKit.dp(context, topDp);
        parent.addView(button, params);
    }

    private void tuneTextButton(TextView button) {
        if (phone) {
            button.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f);
        }
    }
}
