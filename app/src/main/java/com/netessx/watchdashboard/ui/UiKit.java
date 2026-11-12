package com.netessx.watchdashboard.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.netessx.watchdashboard.R;
import com.netessx.watchdashboard.data.AgentRequest;
import com.netessx.watchdashboard.data.AgentStore;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Programmatic watch UI helpers. Tuned for 320-400px square displays. */
public final class UiKit {

    public static final int BG = 0xFF060807;
    public static final int SURFACE = 0xFF101613;
    public static final int SURFACE_ALT = 0xFF16201A;
    public static final int BORDER = 0xFF24332B;

    private UiKit() {
    }

    public static int dp(Context context, float value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics()));
    }

    public static int color(Context context, int resId) {
        return ContextCompat.getColor(context, resId);
    }

    public static int textColor(Context context) {
        return color(context, R.color.text);
    }

    public static int mutedColor(Context context) {
        return color(context, R.color.text_muted);
    }

    public static int accentColor(Context context) {
        return color(context, R.color.accent);
    }

    public static GradientDrawable rounded(Context context, int fill, int stroke,
                                           float strokeDp, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(context, radiusDp));
        if (strokeDp > 0f) {
            drawable.setStroke(dp(context, strokeDp), stroke);
        }
        return drawable;
    }

    public static Drawable ripple(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null);
    }

    public static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    public static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    public static LinearLayout card(Context context, int fill, int stroke) {
        LinearLayout card = column(context);
        card.setPadding(dp(context, 12), dp(context, 11), dp(context, 12), dp(context, 11));
        card.setBackground(rounded(context, fill, stroke, 1f, 16f));
        return card;
    }

    public static LinearLayout clickableCard(Context context, int fill, int stroke) {
        LinearLayout card = card(context, fill, stroke);
        card.setBackground(ripple(rounded(context, fill, stroke, 1f, 16f)));
        card.setClickable(true);
        card.setFocusable(true);
        return card;
    }

    public static TextView text(Context context, CharSequence value, float sizeSp, int textColor) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(textColor);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(0f, 1.15f);
        return view;
    }

    public static TextView medium(Context context, CharSequence value, float sizeSp, int textColor) {
        TextView view = text(context, value, sizeSp, textColor);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    public static TextView label(Context context, CharSequence value) {
        TextView view = medium(context, value, 9.5f, mutedColor(context));
        view.setLetterSpacing(0.12f);
        return view;
    }

    public static TextView sectionLabel(Context context, CharSequence value) {
        TextView view = label(context, value);
        ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        view.setLayoutParams(params);
        return view;
    }

    public static TextView button(Context context, CharSequence label, int fill, int stroke,
                                  int textColor) {
        TextView button = medium(context, label, 13f, textColor);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setEllipsize(android.text.TextUtils.TruncateAt.END);
        button.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        button.setMinHeight(dp(context, 46));
        button.setBackground(ripple(rounded(context, fill, stroke, 1f, 14f)));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    public static TextView pill(Context context, CharSequence label, int fill, int textColor) {
        TextView view = medium(context, label, 9f, textColor);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(context, 7), dp(context, 2), dp(context, 7), dp(context, 2));
        view.setBackground(rounded(context, fill, 0, 0f, 8f));
        return view;
    }

    public static ImageView icon(Context context, int drawableRes, int tintColor, int sizeDp) {
        ImageView image = new ImageView(context);
        image.setImageResource(drawableRes);
        image.setColorFilter(tintColor);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int size = dp(context, sizeDp);
        image.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return image;
    }

    public static ImageView iconButton(Context context, int drawableRes, int tintColor, int sizeDp) {
        ImageView image = icon(context, drawableRes, tintColor, sizeDp);
        int pad = dp(context, 5);
        image.setPadding(pad, pad, pad, pad);
        image.setBackground(ripple(rounded(context, 0x00000000, 0, 0f, 40f)));
        image.setClickable(true);
        image.setFocusable(true);
        int total = dp(context, sizeDp) + pad * 2;
        image.setLayoutParams(new LinearLayout.LayoutParams(total, total));
        return image;
    }

    public static View dot(Context context, int dotColor, float sizeDp) {
        View dot = new View(context);
        int size = dp(context, sizeDp);
        dot.setBackground(rounded(context, dotColor, 0, 0f, sizeDp));
        dot.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return dot;
    }

    public static View space(Context context, float heightDp) {
        View view = new View(context);
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, heightDp)));
        return view;
    }

    public static View divider(Context context) {
        View view = new View(context);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1));
        params.topMargin = dp(context, 8);
        params.bottomMargin = dp(context, 8);
        view.setLayoutParams(params);
        view.setBackgroundColor(color(context, R.color.border));
        return view;
    }

    public static void margins(View view, float leftDp, float topDp, float rightDp, float bottomDp) {
        ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
        if (!(layoutParams instanceof ViewGroup.MarginLayoutParams)) {
            layoutParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) layoutParams;
        Context context = view.getContext();
        margins.leftMargin = dp(context, leftDp);
        margins.topMargin = dp(context, topDp);
        margins.rightMargin = dp(context, rightDp);
        margins.bottomMargin = dp(context, bottomDp);
        view.setLayoutParams(margins);
    }

    public static void matchWidth(View view) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params == null) {
            params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        } else {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        view.setLayoutParams(params);
    }

    public static void weight(View view, float weight) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) params).weight = weight;
        }
    }

    public static String clock(long now) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(now));
    }

    public static String relativeTime(Context context, long timestamp) {
        if (timestamp <= 0) {
            return "";
        }
        long diff = System.currentTimeMillis() - timestamp;
        if (diff < 0 || diff < 60_000L) {
            return context.getString(R.string.ago_just_now);
        }
        if (diff < 60 * 60_000L) {
            return context.getString(R.string.ago_minutes, diff / 60_000L);
        }
        if (diff < 24 * 60 * 60_000L) {
            return context.getString(R.string.ago_hours, diff / (60 * 60_000L));
        }
        return new SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
                .format(new Date(timestamp));
    }

    public static String expiry(Context context, AgentRequest request, long now) {
        if (request.expiresAt <= 0) {
            return context.getString(R.string.expiry_none);
        }
        long remaining = request.expiresAt - now;
        if (remaining <= 0) {
            return context.getString(R.string.expiry_expired);
        }
        if (remaining <= 60_000L) {
            return context.getString(R.string.expiry_remaining_sec,
                    Math.max(1, remaining / 1000L));
        }
        return context.getString(R.string.expiry_remaining_min,
                Math.max(1, remaining / 60_000L));
    }

    public static int statusColor(Context context, AgentStore.Connection connection) {
        switch (connection) {
            case ONLINE:
                return color(context, R.color.accent);
            case CONNECTING:
            case RECONNECTING:
                return color(context, R.color.warn);
            case AUTH_FAILED:
                return color(context, R.color.danger);
            default:
                return mutedColor(context);
        }
    }

    public static String statusLabel(Context context, AgentStore.Connection connection) {
        switch (connection) {
            case ONLINE:
                return context.getString(R.string.status_online);
            case CONNECTING:
                return context.getString(R.string.status_connecting);
            case RECONNECTING:
                return context.getString(R.string.status_reconnecting);
            case AUTH_FAILED:
                return context.getString(R.string.status_auth_failed);
            case UNCONFIGURED:
                return context.getString(R.string.status_unconfigured);
            default:
                return context.getString(R.string.status_disconnected);
        }
    }

    public static int agentStatusColor(Context context, String status) {
        if ("working".equalsIgnoreCase(status)) {
            return color(context, R.color.accent);
        }
        if ("waiting".equalsIgnoreCase(status)) {
            return color(context, R.color.warn);
        }
        if ("error".equalsIgnoreCase(status)) {
            return color(context, R.color.danger);
        }
        return mutedColor(context);
    }

    public static String agentStatusLabel(Context context, String status) {
        if ("working".equalsIgnoreCase(status)) {
            return context.getString(R.string.agent_status_working);
        }
        if ("waiting".equalsIgnoreCase(status)) {
            return context.getString(R.string.agent_status_waiting);
        }
        if ("idle".equalsIgnoreCase(status)) {
            return context.getString(R.string.agent_status_idle);
        }
        if ("error".equalsIgnoreCase(status)) {
            return context.getString(R.string.agent_status_error);
        }
        if ("offline".equalsIgnoreCase(status)) {
            return context.getString(R.string.agent_status_offline);
        }
        return context.getString(R.string.agent_status_unknown);
    }
}
