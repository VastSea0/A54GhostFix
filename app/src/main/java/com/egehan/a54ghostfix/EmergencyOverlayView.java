package com.egehan.a54ghostfix;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;

final class EmergencyOverlayView extends View {
    interface Listener {
        void onTrustedTap(float x, float y);

        void onTrustedSwipe(float startX, float startY, float endX, float endY);

        void onVirtualDockTap(int index);
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

    // Virtual dock navigation state
    private boolean inVirtualMenu = false;
    private int virtualSelectedIndex = 0;

    EmergencyOverlayView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        density = getResources().getDisplayMetrics().density;

        setBackgroundColor(Color.TRANSPARENT);
        setClickable(true);
        setFocusable(false);

        // Enable hardware acceleration compatibility for custom shadow layers
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        // Tinted backdrop for enhanced glassmorphism contrast
        shieldPaint.setColor(0x2005100E);
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

    void setVirtualSelection(boolean inVirtualMenu, int index) {
        this.inVirtualMenu = inVirtualMenu;
        this.virtualSelectedIndex = index;
        invalidate();
    }

    private void drawGlassPanel(Canvas canvas, RectF rect, boolean isFocused) {
        // 1. Draw soft glass drop shadow
        Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadowPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setColor(0x30000000);
        if (isFocused) {
            shadowPaint.setShadowLayer(dp(12), 0, dp(1), 0xFF6FFFCB);
        } else {
            shadowPaint.setShadowLayer(dp(8), 0, dp(3), 0x22000000);
        }
        canvas.drawRoundRect(rect, dp(14), dp(14), shadowPaint);

        // 2. Draw frosted glass background gradient
        Paint glassPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        glassPaint.setStyle(Paint.Style.FILL);
        LinearGradient gradient = new LinearGradient(
                rect.left, rect.top,
                rect.left, rect.bottom,
                new int[]{0xF21C2B27, 0xF20F1614},
                null,
                Shader.TileMode.CLAMP
        );
        glassPaint.setShader(gradient);
        canvas.drawRoundRect(rect, dp(14), dp(14), glassPaint);

        // 3. Draw glass stroke/border (specular reflections on edges)
        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1.2f));
        LinearGradient borderGradient = new LinearGradient(
                rect.left, rect.top,
                rect.right, rect.bottom,
                new int[]{0x4DFFFFFF, 0x1AFFFFFF, 0x1A6FFFCB, 0x3DFFFFFF},
                new float[]{0.0f, 0.4f, 0.7f, 1.0f},
                Shader.TileMode.CLAMP
        );
        borderPaint.setShader(borderGradient);
        canvas.drawRoundRect(rect, dp(14), dp(14), borderPaint);

        // 4. Subtle inner reflection highlight (top 40% of the card)
        Paint shinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shinePaint.setStyle(Paint.Style.FILL);
        shinePaint.setColor(0x08FFFFFF);
        Path shinePath = new Path();
        shinePath.addRoundRect(rect, dp(14), dp(14), Path.Direction.CW);
        canvas.save();
        canvas.clipPath(shinePath);
        canvas.drawRect(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.4f, shinePaint);
        canvas.restore();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), getHeight(), shieldPaint);

        // 1. Draw Glass Status Bar
        RectF statusRect = new RectF(dp(12), dp(10), getWidth() - dp(12), dp(46));
        drawGlassPanel(canvas, statusRect, false);

        textPaint.setTextSize(dp(12.5f));
        textPaint.setFakeBoldText(true);
        textPaint.setColor(Color.WHITE);
        float statusY = dp(10) + dp(18) - ((textPaint.descent() + textPaint.ascent()) / 2);
        canvas.drawText(statusText, dp(28), statusY, textPaint);

        // 2. Draw Glass Dock Panel
        RectF dockRect = new RectF(dp(12), dp(52), getWidth() - dp(12), dp(108));
        drawGlassPanel(canvas, dockRect, inVirtualMenu);

        // 3. Draw horizontal navigation keys inside the dock
        float columnWidth = (getWidth() - dp(24)) / 5;
        String[] labels = {
                "◀ Geri",
                "● Ana E.",
                "■ Sonlar",
                "▼ Bild.",
                "✕ Kapat"
        };

        Paint btnTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        btnTextPaint.setTextSize(dp(11));
        btnTextPaint.setFakeBoldText(true);
        btnTextPaint.setTextAlign(Paint.Align.CENTER);

        for (int i = 0; i < 5; i++) {
            float btnLeft = dp(12) + i * columnWidth + dp(4);
            float btnRight = dp(12) + (i + 1) * columnWidth - dp(4);
            float btnTop = dp(52) + dp(6);
            float btnBottom = dp(108) - dp(6);
            RectF btnRect = new RectF(btnLeft, btnTop, btnRight, btnBottom);

            boolean isSelected = inVirtualMenu && (virtualSelectedIndex == i);
            if (isSelected) {
                // Glowy neon key styling
                Paint selPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                selPaint.setStyle(Paint.Style.FILL);
                selPaint.setShadowLayer(dp(8), 0, 0, 0xFF6FFFCB);
                LinearGradient selGrad = new LinearGradient(
                        btnRect.left, btnRect.top,
                        btnRect.left, btnRect.bottom,
                        new int[]{0xE600F5A0, 0xE600D9F5},
                        null,
                        Shader.TileMode.CLAMP
                );
                selPaint.setShader(selGrad);
                canvas.drawRoundRect(btnRect, dp(10), dp(10), selPaint);

                Paint selBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
                selBorder.setStyle(Paint.Style.STROKE);
                selBorder.setStrokeWidth(dp(1.8f));
                selBorder.setColor(0xFF6FFFCB);
                canvas.drawRoundRect(btnRect, dp(10), dp(10), selBorder);

                btnTextPaint.setColor(0xFF0C1613); // High contrast dark text on neon background
            } else {
                // Semi-translucent glass key styling
                Paint normalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                normalPaint.setStyle(Paint.Style.FILL);
                normalPaint.setColor(0x12FFFFFF);
                canvas.drawRoundRect(btnRect, dp(10), dp(10), normalPaint);

                Paint normalBorder = new Paint(Paint.ANTI_ALIAS_FLAG);
                normalBorder.setStyle(Paint.Style.STROKE);
                normalBorder.setStrokeWidth(dp(1.0f));
                normalBorder.setColor(0x26FFFFFF);
                canvas.drawRoundRect(btnRect, dp(10), dp(10), normalBorder);

                btnTextPaint.setColor(0xCCFFFFFF); // 80% opacity white text
            }

            float textY = btnRect.centerY() - ((btnTextPaint.descent() + btnTextPaint.ascent()) / 2);
            canvas.drawText(labels[i], btnRect.centerX(), textY, btnTextPaint);
        }

        // 4. Focus Ring (Liquid Neon glow theme)
        if (!guardedMode && !focusBounds.isEmpty()) {
            RectF highlight = new RectF(focusBounds);
            highlight.inset(-dp(5), -dp(5));

            Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            highlightPaint.setStyle(Paint.Style.STROKE);
            highlightPaint.setStrokeWidth(dp(3.5f));
            highlightPaint.setColor(0xFF6FFFCB);
            highlightPaint.setShadowLayer(dp(10), 0, 0, 0xFF6FFFCB);

            canvas.drawRoundRect(highlight, dp(12), dp(12), highlightPaint);
        }

        // 5. Selection Text (Liquid Glass styling)
        if (!selectionText.isEmpty()) {
            textPaint.setTextSize(dp(13));
            textPaint.setFakeBoldText(false);
            float width = Math.min(
                    textPaint.measureText(selectionText) + dp(28),
                    getWidth() - dp(24)
            );
            RectF bottomRect = new RectF(dp(12), getHeight() - dp(54), dp(12) + width, getHeight() - dp(12));
            drawGlassPanel(canvas, bottomRect, false);

            String visible = ellipsize(selectionText, getWidth() - dp(52));
            textPaint.setColor(0xE6FFFFFF);
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

        float x = event.getRawX();
        float y = event.getRawY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (event.getEventTime() - guardStartedAt < GUARD_SETTLE_MS) {
                    return true;
                }
                trackingTrustedTouch = true;
                downX = x;
                downY = y;
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

                // Check if touch starts and ends inside the Glass Dock panel (y between 52dp and 108dp)
                float yMin = dp(52);
                float yMax = dp(108);
                if (downY >= yMin && downY <= yMax && endY >= yMin && endY <= yMax
                        && downX >= dp(12) && downX <= getWidth() - dp(12)) {
                    float columnWidth = (getWidth() - dp(24)) / 5;
                    int index = (int) ((downX - dp(12)) / columnWidth);
                    if (index >= 0 && index < 5) {
                        listener.onVirtualDockTap(index);
                    }
                    return true;
                }

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
