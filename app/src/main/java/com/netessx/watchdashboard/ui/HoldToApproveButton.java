package com.netessx.watchdashboard.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.netessx.watchdashboard.R;

/**
 * Approval control for high-risk actions. A single tap does nothing; the user
 * must hold for one second while a progress fill completes. This makes an
 * accidental wrist tap far less likely to run a destructive command.
 */
public class HoldToApproveButton extends View {

    public interface OnConfirmListener {
        void onConfirmed();
    }

    private static final long HOLD_MS = 1000L;

    private final Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final String label;
    private final OnConfirmListener listener;

    private boolean pressing;
    private boolean fired;
    private long pressStartedAt;
    private float progress;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!pressing) {
                return;
            }
            progress = Math.min(1f, (SystemClock.uptimeMillis() - pressStartedAt) / (float) HOLD_MS);
            invalidate();
            if (progress >= 1f) {
                complete();
            } else {
                postOnAnimation(this);
            }
        }
    };

    public HoldToApproveButton(Context context, String label, OnConfirmListener listener) {
        super(context);
        this.label = label;
        this.listener = listener;

        basePaint.setColor(ContextCompat.getColor(context, R.color.accent_dim));
        basePaint.setStyle(Paint.Style.FILL);
        progressPaint.setColor(ContextCompat.getColor(context, R.color.accent));
        progressPaint.setAlpha(70);
        progressPaint.setStyle(Paint.Style.FILL);
        borderPaint.setColor(ContextCompat.getColor(context, R.color.accent));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(UiKit.dp(context, 1.2f));
        textPaint.setColor(ContextCompat.getColor(context, R.color.accent));
        setLabelTextSizeSp(13.5f);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));

        setClickable(true);
        setFocusable(true);
        setContentDescription(context.getString(R.string.content_desc_approve_hold, label));
    }

    public void setLabelTextSizeSp(float sizeSp) {
        textPaint.setTextSize(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp,
                getContext().getResources().getDisplayMetrics()));
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = UiKit.dp(getContext(), 50);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, resolveSize(height, heightMeasureSpec));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pressing = true;
                fired = false;
                progress = 0f;
                pressStartedAt = SystemClock.uptimeMillis();
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                postOnAnimation(tick);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (pressing
                        && (event.getX() < 0 || event.getX() > getWidth()
                        || event.getY() < 0 || event.getY() > getHeight())) {
                    reset();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (pressing) {
                    long held = SystemClock.uptimeMillis() - pressStartedAt;
                    if (held >= HOLD_MS) {
                        complete();
                    } else {
                        reset();
                    }
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                reset();
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        complete();
        return true;
    }

    private void complete() {
        if (fired || !isEnabled()) {
            return;
        }
        fired = true;
        pressing = false;
        progress = 1f;
        removeCallbacks(tick);
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        invalidate();
        if (listener != null) {
            listener.onConfirmed();
        }
    }

    private void reset() {
        pressing = false;
        progress = 0f;
        removeCallbacks(tick);
        invalidate();
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) {
            reset();
        }
        textPaint.setColor(ContextCompat.getColor(getContext(),
                enabled ? R.color.accent : R.color.text_muted));
        borderPaint.setColor(ContextCompat.getColor(getContext(),
                enabled ? R.color.accent : R.color.border));
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        bounds.set(0, 0, getWidth(), getHeight());
        float radius = UiKit.dp(getContext(), 14);
        canvas.drawRoundRect(bounds, radius, radius, basePaint);
        if (progress > 0f) {
            canvas.save();
            canvas.clipRect(0, 0, getWidth() * progress, getHeight());
            canvas.drawRoundRect(bounds, radius, radius, progressPaint);
            canvas.restore();
        }
        canvas.drawRoundRect(bounds, radius, radius, borderPaint);

        float centerY = getHeight() / 2f
                - (textPaint.descent() + textPaint.ascent()) / 2f;
        canvas.drawText(label, getWidth() / 2f, centerY, textPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }
}
