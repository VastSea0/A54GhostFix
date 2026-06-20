package com.egehan.a54ghostfix;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

final class EmergencyOverlayView extends View {
    interface Listener {
        void onTrustedTap(float x, float y);

        void onTrustedSwipe(float startX, float startY, float endX, float endY);
    }

    private static final long GUARD_SETTLE_MS = 70;

    private final Listener listener;
    private final Paint shieldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect focusBounds = new Rect();
    private final float density;

    private boolean guardedMode = true;
    private boolean guardHeld;
    private boolean trackingTrustedTouch;
    private long guardStartedAt;
    private float downX;
    private float downY;
    private String statusText = "";
    private String selectionText = "";

    EmergencyOverlayView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        density = getResources().getDisplayMetrics().density;

        setBackgroundColor(Color.TRANSPARENT);
        setClickable(true);
        setFocusable(false);

        shieldPaint.setColor(0x08000000);
        panelPaint.setColor(0xE6121D1A);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(15));
        textPaint.setFakeBoldText(true);
        focusPaint.setStyle(Paint.Style.STROKE);
        focusPaint.setStrokeWidth(dp(4));
        focusPaint.setColor(0xFF6FFFCB);
    }

    void setMode(int mode, String status) {
        guardedMode = mode == GhostFixPreferences.MODE_GUARDED_TOUCH;
        statusText = status;
        trackingTrustedTouch = false;
        invalidate();
    }

    void setGuardHeld(boolean held, String status) {
        guardHeld = held;
        if (held) {
            guardStartedAt = android.os.SystemClock.uptimeMillis();
        } else {
            trackingTrustedTouch = false;
        }
        statusText = status;
        invalidate();
    }

    void setSelection(Rect bounds, String label) {
        focusBounds.set(bounds);
        selectionText = label == null ? "" : label;
        invalidate();
    }

    void clearSelection() {
        focusBounds.setEmpty();
        selectionText = "";
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), shieldPaint);

        float panelHeight = dp(58);
        canvas.drawRoundRect(
                new RectF(dp(12), dp(10), getWidth() - dp(12), panelHeight),
                dp(12),
                dp(12),
                panelPaint
        );
        canvas.drawText(statusText, dp(28), dp(40), textPaint);

        if (!guardedMode && !focusBounds.isEmpty()) {
            RectF highlight = new RectF(focusBounds);
            highlight.inset(-dp(5), -dp(5));
            canvas.drawRoundRect(highlight, dp(10), dp(10), focusPaint);
        }

        if (!selectionText.isEmpty()) {
            textPaint.setTextSize(dp(13));
            textPaint.setFakeBoldText(false);
            float width = Math.min(
                    textPaint.measureText(selectionText) + dp(28),
                    getWidth() - dp(24)
            );
            canvas.drawRoundRect(
                    new RectF(dp(12), getHeight() - dp(54), dp(12) + width, getHeight() - dp(12)),
                    dp(10),
                    dp(10),
                    panelPaint
            );
            String visible = ellipsize(selectionText, getWidth() - dp(52));
            canvas.drawText(visible, dp(26), getHeight() - dp(27), textPaint);
            textPaint.setTextSize(dp(15));
            textPaint.setFakeBoldText(true);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!guardedMode || !guardHeld) {
            trackingTrustedTouch = false;
            return true;
        }

        if (event.getPointerCount() != 1) {
            trackingTrustedTouch = false;
            return true;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (event.getEventTime() - guardStartedAt < GUARD_SETTLE_MS) {
                    return true;
                }
                trackingTrustedTouch = true;
                downX = event.getRawX();
                downY = event.getRawY();
                return true;
            case MotionEvent.ACTION_UP:
                if (!trackingTrustedTouch || !guardHeld) {
                    trackingTrustedTouch = false;
                    return true;
                }
                trackingTrustedTouch = false;
                float endX = event.getRawX();
                float endY = event.getRawY();
                float distance = (float) Math.hypot(endX - downX, endY - downY);
                if (distance >= dp(48)) {
                    listener.onTrustedSwipe(downX, downY, endX, endY);
                } else {
                    listener.onTrustedTap(endX, endY);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                trackingTrustedTouch = false;
                return true;
            default:
                return true;
        }
    }

    private String ellipsize(String text, float maxWidth) {
        if (textPaint.measureText(text) <= maxWidth) {
            return text;
        }
        String suffix = "...";
        int end = text.length();
        while (end > 0 && textPaint.measureText(text.substring(0, end) + suffix) > maxWidth) {
            end--;
        }
        return text.substring(0, end) + suffix;
    }

    private float dp(float value) {
        return value * density;
    }
}
