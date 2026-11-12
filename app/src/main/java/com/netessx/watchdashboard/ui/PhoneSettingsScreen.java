package com.netessx.watchdashboard.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.netessx.watchdashboard.R;
import com.netessx.watchdashboard.data.AgentStore;
import com.netessx.watchdashboard.data.Settings;
import com.netessx.watchdashboard.net.Protocol;

/** Phone settings screen with the same configuration semantics as the watch. */
public class PhoneSettingsScreen extends ScrollView {

    private final Context context;
    private final SettingsScreen.Listener listener;
    private final EditText urlField;
    private final EditText tokenField;
    private final EditText nameField;
    private final View statusDot;
    private final TextView statusLabel;
    private final TextView statusServer;
    private final TextView errorLabel;
    private final TextView reconnectAction;

    public PhoneSettingsScreen(Context context, Settings settings,
                               AgentStore.Snapshot snapshot, boolean notificationsGranted,
                               SettingsScreen.Listener listener) {
        super(context);
        this.context = context;
        this.listener = listener;
        setFillViewport(true);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setVerticalScrollBarEnabled(false);

        LinearLayout root = UiKit.column(context);
        root.setPadding(UiKit.dp(context, 20), UiKit.dp(context, 18),
                UiKit.dp(context, 20), UiKit.dp(context, 36));
        addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = UiKit.row(context);
        ImageView back = new ImageView(context);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(UiKit.textColor(context));
        back.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int backPad = UiKit.dp(context, 10);
        back.setPadding(backPad, backPad, backPad, backPad);
        back.setBackground(UiKit.ripple(UiKit.rounded(context,
                UiKit.color(context, R.color.surface), UiKit.color(context, R.color.border),
                1f, 14f)));
        back.setContentDescription(context.getString(R.string.content_desc_back));
        back.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onBack();
            }
        });
        header.addView(back, new LinearLayout.LayoutParams(
                UiKit.dp(context, 42), UiKit.dp(context, 42)));
        TextView title = UiKit.medium(context,
                context.getString(R.string.phone_settings_title), 20f,
                UiKit.textColor(context));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.leftMargin = UiKit.dp(context, 12);
        header.addView(title, titleParams);
        root.addView(header);

        addSectionLabel(root, context.getString(R.string.phone_settings_connection));
        LinearLayout status = phoneCard();
        LinearLayout statusRow = UiKit.row(context);
        statusDot = UiKit.dot(context, UiKit.statusColor(context, snapshot.connection), 8f);
        statusRow.addView(statusDot);
        statusLabel = UiKit.medium(context,
                UiKit.statusLabel(context, snapshot.connection), 14f,
                UiKit.textColor(context));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        statusParams.leftMargin = UiKit.dp(context, 8);
        statusRow.addView(statusLabel, statusParams);
        reconnectAction = UiKit.pill(context,
                context.getString(R.string.phone_status_reconnect),
                UiKit.color(context, R.color.warn),
                UiKit.color(context, R.color.bg));
        reconnectAction.setPadding(UiKit.dp(context, 12), UiKit.dp(context, 7),
                UiKit.dp(context, 12), UiKit.dp(context, 7));
        reconnectAction.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onReconnect();
            }
        });
        statusRow.addView(reconnectAction);
        status.addView(statusRow);

        statusServer = UiKit.text(context,
                snapshot.serverName.isEmpty()
                        ? context.getString(R.string.unknown_server)
                        : snapshot.serverName,
                11.5f, UiKit.mutedColor(context));
        addTop(status, statusServer, 6);
        errorLabel = UiKit.text(context,
                snapshot.lastError.isEmpty() ? "" : snapshot.lastError,
                11.5f, UiKit.color(context, R.color.danger));
        errorLabel.setMaxLines(2);
        errorLabel.setEllipsize(TextUtils.TruncateAt.END);
        errorLabel.setVisibility(snapshot.lastError.isEmpty() ? GONE : VISIBLE);
        addTop(status, errorLabel, 5);
        addTop(root, status, 10);

        addSectionLabel(root, context.getString(R.string.phone_settings_form));
        LinearLayout form = phoneCard();
        urlField = field(context.getString(R.string.hint_server_url), settings.serverUrl,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addTop(form, fieldLabel(context.getString(R.string.label_server_url)), 0);
        addTop(form, urlField, 6);
        tokenField = field(context.getString(R.string.hint_token), settings.token,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        addTop(form, fieldLabel(context.getString(R.string.label_token)), 14);
        addTop(form, tokenField, 6);
        nameField = field(context.getString(R.string.hint_device_name), settings.deviceName,
                InputType.TYPE_CLASS_TEXT);
        addTop(form, fieldLabel(context.getString(R.string.label_device_name)), 14);
        addTop(form, nameField, 6);

        TextView save = UiKit.button(context,
                context.getString(R.string.phone_action_save),
                UiKit.accentColor(context), 0, UiKit.color(context, R.color.bg));
        save.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14.5f);
        save.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onSave(
                        urlField.getText().toString(),
                        tokenField.getText().toString(),
                        nameField.getText().toString());
            }
        });
        addTop(form, save, 18);
        addTop(root, form, 10);

        addSectionLabel(root, context.getString(R.string.phone_settings_device));
        LinearLayout device = phoneCard();
        addInfoRow(device, context.getString(R.string.phone_label_server_name),
                snapshot.serverName.isEmpty()
                        ? context.getString(R.string.unknown_server)
                        : snapshot.serverName, false);
        addInfoRow(device, context.getString(R.string.phone_label_protocol),
                "v" + Protocol.VERSION, true);
        addInfoRow(device, context.getString(R.string.label_device_id),
                settings.deviceId, true);
        addInfoRow(device, context.getString(R.string.phone_label_notify_state),
                context.getString(notificationsGranted
                        ? R.string.phone_settings_notifications_on
                        : R.string.phone_settings_notifications_off), false);
        addTop(root, device, 10);

        TextView securityLabel = UiKit.medium(context,
                context.getString(R.string.phone_settings_security), 13f,
                UiKit.mutedColor(context));
        addTop(root, securityLabel, 24);
        TextView security = UiKit.text(context,
                context.getString(R.string.phone_settings_security_body), 12f,
                UiKit.mutedColor(context));
        addTop(root, security, 6);

        if (!notificationsGranted) {
            TextView note = UiKit.text(context,
                    context.getString(R.string.settings_notifications_note), 11.5f,
                    UiKit.color(context, R.color.warn));
            addTop(root, note, 10);
        }

        if (snapshot.configured) {
            TextView disconnect = UiKit.button(context,
                    context.getString(R.string.phone_action_disconnect),
                    UiKit.color(context, R.color.danger_dim),
                    UiKit.color(context, R.color.danger),
                    UiKit.color(context, R.color.danger));
            disconnect.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
            disconnect.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    listener.onDisconnect();
                }
            });
            addTop(root, disconnect, 20);
        }

        refresh(snapshot.connection, snapshot.lastError, snapshot.serverName);
    }

    public void refresh(AgentStore.Connection connection, String error) {
        refresh(connection, error, "");
    }

    public void refresh(AgentStore.Connection connection, String error, String serverName) {
        statusDot.setBackground(UiKit.rounded(context,
                UiKit.statusColor(context, connection), 0, 0f, 4f));
        statusLabel.setText(UiKit.statusLabel(context, connection));
        statusLabel.setTextColor(UiKit.statusColor(context, connection));
        boolean hasError = error != null && !error.isEmpty();
        errorLabel.setText(hasError ? error : "");
        errorLabel.setVisibility(hasError ? VISIBLE : GONE);
        boolean showReconnect = connection != AgentStore.Connection.ONLINE
                && connection != AgentStore.Connection.UNCONFIGURED;
        reconnectAction.setVisibility(showReconnect ? VISIBLE : GONE);
        if (serverName != null && !serverName.isEmpty()) {
            statusServer.setText(serverName);
        }
    }

    private EditText field(String placeholder, String value, int inputType) {
        EditText field = new EditText(context);
        field.setHint(placeholder);
        field.setText(value);
        field.setSingleLine(true);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setInputType(inputType);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
        field.setTextColor(UiKit.textColor(context));
        field.setHintTextColor(UiKit.mutedColor(context));
        field.setPadding(UiKit.dp(context, 14), 0, UiKit.dp(context, 14), 0);
        field.setMinHeight(UiKit.dp(context, 52));
        field.setBackground(UiKit.rounded(context,
                UiKit.color(context, R.color.surface_alt),
                UiKit.color(context, R.color.border), 1f, 12f));
        field.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(context, 52)));
        return field;
    }

    private TextView fieldLabel(String label) {
        return UiKit.medium(context, label, 12f, UiKit.mutedColor(context));
    }

    private LinearLayout phoneCard() {
        LinearLayout card = UiKit.card(context,
                UiKit.color(context, R.color.surface), UiKit.color(context, R.color.border));
        card.setPadding(UiKit.dp(context, 16), UiKit.dp(context, 16),
                UiKit.dp(context, 16), UiKit.dp(context, 16));
        return card;
    }

    private void addInfoRow(LinearLayout parent, String label, String value, boolean mono) {
        if (parent.getChildCount() > 0) {
            parent.addView(UiKit.divider(context));
        }
        LinearLayout row = UiKit.row(context);
        row.addView(UiKit.text(context, label, 12f, UiKit.mutedColor(context)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView valueView = UiKit.text(context, value, 12f, UiKit.textColor(context));
        valueView.setMaxLines(1);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        if (mono) {
            valueView.setTypeface(Typeface.MONOSPACE);
        }
        row.addView(valueView);
        parent.addView(row);
    }

    private void addSectionLabel(LinearLayout root, String label) {
        TextView view = UiKit.medium(context, label, 12f, UiKit.mutedColor(context));
        view.setLetterSpacing(0.08f);
        addTop(root, view, 24);
    }

    private void addTop(LinearLayout parent, View child, float topDp) {
        LinearLayout.LayoutParams params;
        if (child.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            params = (LinearLayout.LayoutParams) child.getLayoutParams();
        } else {
            params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        params.topMargin = UiKit.dp(context, topDp);
        if (params.width == 0 && params.height == 0) {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        }
        parent.addView(child, params);
    }
}
