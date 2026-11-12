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

/** Endpoint/token configuration. Also reachable through adb provision extras. */
public class SettingsScreen extends ScrollView {

    public interface Listener {
        void onSave(String serverUrl, String token, String deviceName);

        void onDisconnect();

        void onBack();

        void onReconnect();
    }

    private final Context context;
    private final boolean notificationsGranted;
    private final EditText urlField;
    private final EditText tokenField;
    private final EditText nameField;
    private final View statusDot;
    private final TextView statusLabel;
    private final TextView errorLabel;

    public SettingsScreen(Context context, Settings settings, AgentStore.Snapshot snapshot,
                          boolean notificationsGranted, final Listener listener) {
        super(context);
        this.context = context;
        this.notificationsGranted = notificationsGranted;
        setFillViewport(true);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setVerticalScrollBarEnabled(false);

        LinearLayout root = UiKit.column(context);
        root.setPadding(UiKit.dp(context, 13), UiKit.dp(context, 10),
                UiKit.dp(context, 13), UiKit.dp(context, 20));
        addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = UiKit.row(context);
        ImageView back = UiKit.iconButton(context, R.drawable.ic_back,
                UiKit.textColor(context), 18);
        back.setContentDescription(context.getString(R.string.content_desc_back));
        back.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onBack();
            }
        });
        header.addView(back);
        TextView title = UiKit.medium(context, context.getString(R.string.settings_title),
                14f, UiKit.textColor(context));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.leftMargin = UiKit.dp(context, 5);
        header.addView(title, titleParams);
        root.addView(header);

        LinearLayout status = UiKit.card(context,
                UiKit.color(context, R.color.surface),
                UiKit.color(context, R.color.border));
        LinearLayout statusRow = UiKit.row(context);
        statusDot = UiKit.dot(context,
                UiKit.statusColor(context, snapshot.connection), 7f);
        statusRow.addView(statusDot);
        statusLabel = UiKit.medium(context,
                UiKit.statusLabel(context, snapshot.connection), 10.5f,
                UiKit.textColor(context));
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.leftMargin = UiKit.dp(context, 7);
        statusRow.addView(statusLabel, labelParams);
        status.addView(statusRow);
        errorLabel = UiKit.text(context,
                snapshot.lastError.isEmpty() ? "" : snapshot.lastError,
                9f, UiKit.color(context, R.color.danger));
        errorLabel.setMaxLines(2);
        errorLabel.setEllipsize(TextUtils.TruncateAt.END);
        errorLabel.setVisibility(snapshot.lastError.isEmpty() ? GONE : VISIBLE);
        addTop(status, errorLabel, 4);
        addTop(root, status, 12);

        urlField = field(context.getString(R.string.hint_server_url), settings.serverUrl,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addTop(root, wrapField(context.getString(R.string.label_server_url), urlField), 12);

        tokenField = field(context.getString(R.string.hint_token), settings.token,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        addTop(root, wrapField(context.getString(R.string.label_token), tokenField), 10);

        nameField = field(context.getString(R.string.hint_device_name), settings.deviceName,
                InputType.TYPE_CLASS_TEXT);
        addTop(root, wrapField(context.getString(R.string.label_device_name), nameField), 10);

        TextView save = UiKit.button(context,
                context.getString(R.string.action_save),
                UiKit.accentColor(context), 0, UiKit.color(context, R.color.bg));
        save.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                listener.onSave(
                        urlField.getText().toString(),
                        tokenField.getText().toString(),
                        nameField.getText().toString());
            }
        });
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(context, 48));
        saveParams.topMargin = UiKit.dp(context, 14);
        root.addView(save, saveParams);

        if (snapshot.configured) {
            TextView disconnect = UiKit.button(context,
                    context.getString(R.string.action_disconnect),
                    UiKit.color(context, R.color.danger_dim),
                    UiKit.color(context, R.color.danger),
                    UiKit.color(context, R.color.danger));
            disconnect.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    listener.onDisconnect();
                }
            });
            LinearLayout.LayoutParams disconnectParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(context, 46));
            disconnectParams.topMargin = UiKit.dp(context, 8);
            root.addView(disconnect, disconnectParams);
        }

        if (!notificationsGranted) {
            TextView note = UiKit.text(context,
                    context.getString(R.string.settings_notifications_note),
                    9f, UiKit.color(context, R.color.warn));
            addTop(root, note, 10);
        }

        TextView security = UiKit.text(context,
                context.getString(R.string.settings_security_note),
                9f, UiKit.mutedColor(context));
        security.setMaxLines(3);
        addTop(root, security, 12);

        String server = snapshot.serverName.isEmpty()
                ? context.getString(R.string.unknown_server)
                : snapshot.serverName;
        TextView meta = UiKit.text(context,
                context.getString(R.string.label_protocol, Protocol.VERSION, server)
                        + "\n" + context.getString(R.string.label_device_id, settings.deviceId),
                8.5f, UiKit.mutedColor(context));
        meta.setTypeface(Typeface.MONOSPACE);
        meta.setGravity(Gravity.START);
        addTop(root, meta, 14);
    }

    public void refresh(AgentStore.Connection connection, String error) {
        statusDot.setBackground(UiKit.rounded(context,
                UiKit.statusColor(context, connection), 0, 0f, 3.5f));
        statusLabel.setText(UiKit.statusLabel(context, connection));
        boolean hasError = error != null && !error.isEmpty();
        errorLabel.setText(hasError ? error : "");
        errorLabel.setVisibility(hasError ? VISIBLE : GONE);
    }

    private EditText field(String placeholder, String value, int inputType) {
        EditText field = new EditText(context);
        field.setHint(placeholder);
        field.setText(value);
        field.setSingleLine(true);
        field.setGravity(Gravity.CENTER_VERTICAL);
        field.setInputType(inputType);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f);
        field.setTextColor(UiKit.textColor(context));
        field.setHintTextColor(UiKit.mutedColor(context));
        field.setPadding(UiKit.dp(context, 11), 0, UiKit.dp(context, 11), 0);
        field.setMinHeight(UiKit.dp(context, 46));
        field.setBackground(UiKit.rounded(context,
                UiKit.color(context, R.color.surface),
                UiKit.color(context, R.color.border), 1f, 12f));
        return field;
    }

    private View wrapField(String label, EditText field) {
        LinearLayout container = UiKit.column(context);
        container.addView(UiKit.label(context, label));
        LinearLayout.LayoutParams fieldParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fieldParams.topMargin = UiKit.dp(context, 5);
        container.addView(field, fieldParams);
        return container;
    }

    private void addTop(LinearLayout parent, View child, float topDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = UiKit.dp(context, topDp);
        parent.addView(child, params);
    }
}
