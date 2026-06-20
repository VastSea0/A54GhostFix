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
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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

    private boolean isActionMenuOpen;
    private int actionMenuSelectedIndex;
    private boolean isLauncherOpen;
    private int launcherSelectedIndex;

    private final String[] ACTION_MENU_ITEMS = {
            "Geri", "Ana Ekran", "Son Uygulamalar", "Bildirimler", "Hızlı Ayarlar", "Devre Dışı Bırak", "Başlatıcı"
    };

    EmergencyOverlayView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        density = getResources().getDisplayMetrics().density;

        setBackgroundColor(Color.TRANSPARENT);
        setClickable(true);
        setFocusable(false);
        setLayerType(LAYER_TYPE_SOFTWARE, null);

        shieldPaint.setColor(0x00000000);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(15));
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

    void setLauncherState(boolean open, int selectedIndex) {
        isLauncherOpen = open;
        launcherSelectedIndex = selectedIndex;
        invalidate();
    }

    void setActionMenuState(boolean open, int selectedIndex) {
        isActionMenuOpen = open;
        actionMenuSelectedIndex = selectedIndex;
        invalidate();
    }

    private void drawMinimalGlassPanel(Canvas canvas, RectF rect, boolean isFocused) {
        // Shadow
        Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadowPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setColor(0x00000000);
        shadowPaint.setShadowLayer(dp(8), 0, dp(4), 0x40000000);
        canvas.drawRoundRect(rect, dp(12), dp(12), shadowPaint);

        // Background
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(isFocused ? 0xE61A1A1A : 0xCC121212);
        canvas.drawRoundRect(rect, dp(12), dp(12), bgPaint);

        // Border
        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1f));
        borderPaint.setColor(isFocused ? 0x4DFFFFFF : 0x1AFFFFFF);
        canvas.drawRoundRect(rect, dp(12), dp(12), borderPaint);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        if (isLauncherOpen || isActionMenuOpen) {
            shieldPaint.setColor(isLauncherOpen ? 0xFA121415 : 0xB3000000);
        } else {
            shieldPaint.setColor(0x10000000);
        }
        canvas.drawRect(0, 0, getWidth(), getHeight(), shieldPaint);

        if (isLauncherOpen) {
            drawSkeuomorphicLauncher(canvas);
            return;
        }

        if (isActionMenuOpen) {
            drawActionMenu(canvas);
            return;
        }

        // 1. Status Pill
        float pillWidth = dp(160);
        float pillHeight = dp(32);
        float pillLeft = (getWidth() - pillWidth) / 2;
        RectF statusRect = new RectF(pillLeft, dp(16), pillLeft + pillWidth, dp(16) + pillHeight);
        drawMinimalGlassPanel(canvas, statusRect, false);

        Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(guardedMode ? 0xFFE57373 : 0xFF81C784); // Muted red/green
        canvas.drawCircle(statusRect.left + dp(16), statusRect.centerY(), dp(4), dotPaint);

        textPaint.setTextSize(dp(12));
        textPaint.setFakeBoldText(true);
        textPaint.setColor(0xE6FFFFFF);
        textPaint.setTextAlign(Paint.Align.LEFT);
        float statusY = statusRect.centerY() - ((textPaint.descent() + textPaint.ascent()) / 2);
        String displayStatus = guardedMode ? "KORUMALI DOKUNMA" : "TUŞ TAKIMI KONTROLÜ";
        canvas.drawText(displayStatus, statusRect.left + dp(28), statusY, textPaint);

        // 2. Focus Ring
        if (!guardedMode && !focusBounds.isEmpty()) {
            RectF highlight = new RectF(focusBounds);
            highlight.inset(-dp(4), -dp(4));

            Paint focusFill = new Paint(Paint.ANTI_ALIAS_FLAG);
            focusFill.setStyle(Paint.Style.FILL);
            focusFill.setColor(0x1AFFFFFF);
            canvas.drawRoundRect(highlight, dp(8), dp(8), focusFill);

            Paint focusStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            focusStroke.setStyle(Paint.Style.STROKE);
            focusStroke.setStrokeWidth(dp(1.5f));
            focusStroke.setColor(0xB3FFFFFF);
            focusStroke.setShadowLayer(dp(2), 0, dp(1), 0x40000000);
            canvas.drawRoundRect(highlight, dp(8), dp(8), focusStroke);
        }

        // 3. Selection Text
        if (!selectionText.isEmpty()) {
            textPaint.setTextSize(dp(14));
            textPaint.setFakeBoldText(false);
            float width = Math.min(textPaint.measureText(selectionText) + dp(32), getWidth() - dp(32));
            float left = (getWidth() - width) / 2;
            RectF bottomRect = new RectF(left, getHeight() - dp(56), left + width, getHeight() - dp(16));
            drawMinimalGlassPanel(canvas, bottomRect, false);

            String visible = ellipsize(selectionText, getWidth() - dp(64));
            textPaint.setColor(0xE6FFFFFF);
            textPaint.setTextAlign(Paint.Align.CENTER);
            float textY = bottomRect.centerY() - ((textPaint.descent() + textPaint.ascent()) / 2);
            canvas.drawText(visible, bottomRect.centerX(), textY, textPaint);
        }
    }

    
    private void drawSkeuomorphicLauncher(Canvas canvas) {
        int cols = 2;
        int rows = 3;
        float spacing = dp(24);
        float itemWidth = (getWidth() - (spacing * 3)) / 2;
        float itemHeight = dp(110);
        
        float totalWidth = (itemWidth * cols) + spacing;
        float totalHeight = (itemHeight * rows) + (spacing * 2);
        
        float startX = (getWidth() - totalWidth) / 2 + (spacing / 2);
        float startY = (getHeight() - totalHeight) / 2;
        
        // Draw physical grid base plate
        RectF basePlate = new RectF(startX - dp(16), startY - dp(16), startX + totalWidth + dp(16), startY + totalHeight + dp(16));
        Paint basePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        basePaint.setStyle(Paint.Style.FILL);
        basePaint.setColor(0xFF1E2122);
        basePaint.setShadowLayer(dp(20), 0, dp(10), 0x99000000);
        canvas.drawRoundRect(basePlate, dp(24), dp(24), basePaint);

        // Inner rim of base plate
        Paint baseRim = new Paint(Paint.ANTI_ALIAS_FLAG);
        baseRim.setStyle(Paint.Style.STROKE);
        baseRim.setStrokeWidth(dp(1f));
        baseRim.setColor(0x20FFFFFF);
        canvas.drawRoundRect(basePlate, dp(24), dp(24), baseRim);

        for (int i = 0; i < GhostFixAccessibilityService.LAUNCHER_PACKAGES.length; i++) {
            if (i >= cols * rows) break;
            
            int row = i / cols;
            int col = i % cols;
            
            float left = startX + (col * (itemWidth + spacing));
            float top = startY + (row * (itemHeight + spacing));
            RectF rect = new RectF(left, top, left + itemWidth, top + itemHeight);
            
            boolean isFocused = (i == launcherSelectedIndex);
            
            // 1. Drop shadow (stronger when not pressed, softer when pressed)
            Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadowPaint.setStyle(Paint.Style.FILL);
            shadowPaint.setColor(0x00000000);
            if (isFocused) {
                shadowPaint.setShadowLayer(dp(2), 0, dp(1), 0x66000000); // Pressed in
            } else {
                shadowPaint.setShadowLayer(dp(8), 0, dp(6), 0x99000000); // Raised
            }
            canvas.drawRoundRect(rect, dp(16), dp(16), shadowPaint);
            
            // 2. Button Body (Gradient for 3D convex/concave)
            Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            bodyPaint.setStyle(Paint.Style.FILL);
            android.graphics.LinearGradient bodyGrad;
            if (isFocused) {
                // Concave (pressed): dark top, light bottom
                bodyGrad = new android.graphics.LinearGradient(
                        0, rect.top, 0, rect.bottom,
                        0xFF121415, 0xFF2A2D2E, android.graphics.Shader.TileMode.CLAMP);
            } else {
                // Convex (raised): light top, dark bottom
                bodyGrad = new android.graphics.LinearGradient(
                        0, rect.top, 0, rect.bottom,
                        0xFF3A3E3F, 0xFF1C1E1F, android.graphics.Shader.TileMode.CLAMP);
            }
            bodyPaint.setShader(bodyGrad);
            canvas.drawRoundRect(rect, dp(16), dp(16), bodyPaint);
            
            // 3. Top Bevel / Specular Highlight (Rim Light)
            if (!isFocused) {
                Paint bevelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                bevelPaint.setStyle(Paint.Style.STROKE);
                bevelPaint.setStrokeWidth(dp(1.5f));
                android.graphics.LinearGradient bevelGrad = new android.graphics.LinearGradient(
                        0, rect.top, 0, rect.bottom,
                        0x66FFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP);
                bevelPaint.setShader(bevelGrad);
                canvas.drawRoundRect(rect, dp(16), dp(16), bevelPaint);
            } else {
                // Inner dark shadow at top when pressed
                Paint innerDark = new Paint(Paint.ANTI_ALIAS_FLAG);
                innerDark.setStyle(Paint.Style.STROKE);
                innerDark.setStrokeWidth(dp(2f));
                android.graphics.LinearGradient innerGrad = new android.graphics.LinearGradient(
                        0, rect.top, 0, rect.top + dp(10),
                        0x80000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP);
                innerDark.setShader(innerGrad);
                canvas.drawRoundRect(rect, dp(16), dp(16), innerDark);
                
                // Active indicator LED
                Paint ledPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                ledPaint.setStyle(Paint.Style.FILL);
                ledPaint.setColor(0xFF4DB6AC); // Teal LED
                ledPaint.setShadowLayer(dp(6), 0, 0, 0xFF4DB6AC);
                canvas.drawCircle(rect.centerX(), rect.top + dp(16), dp(3), ledPaint);
            }
            
            // 4. Engraved Text
            String name = GhostFixAccessibilityService.LAUNCHER_NAMES[i];
            textPaint.setTextSize(dp(16));
            textPaint.setFakeBoldText(true);
            textPaint.setTextAlign(Paint.Align.CENTER);
            
            float textY = rect.centerY() + dp(12);
            
            if (isFocused) {
                // Active text (glowing slightly or bright)
                textPaint.setColor(0xFFFFFFFF);
                textPaint.setShadowLayer(dp(4), 0, 0, 0x66FFFFFF);
            } else {
                // Engraved text (dark fill, white bottom shadow)
                textPaint.setColor(0xFF0A0C0C);
                textPaint.setShadowLayer(dp(1), 0, dp(1), 0x4DFFFFFF);
            }
            canvas.drawText(name, rect.centerX(), textY, textPaint);
            
            // Reset shadow layer for next draw
            textPaint.clearShadowLayer();
        }
    }

    private void drawActionMenu(Canvas canvas) {
        float itemHeight = dp(48);
        float menuWidth = dp(240);
        float menuHeight = ACTION_MENU_ITEMS.length * itemHeight + dp(16);
        float left = (getWidth() - menuWidth) / 2;
        float top = (getHeight() - menuHeight) / 2;

        RectF menuRect = new RectF(left, top, left + menuWidth, top + menuHeight);
        drawMinimalGlassPanel(canvas, menuRect, false);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);

        for (int i = 0; i < ACTION_MENU_ITEMS.length; i++) {
            float itemTop = top + dp(8) + (i * itemHeight);
            RectF itemRect = new RectF(left + dp(8), itemTop, left + menuWidth - dp(8), itemTop + itemHeight);

            if (i == actionMenuSelectedIndex) {
                Paint highlightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                highlightPaint.setStyle(Paint.Style.FILL);
                highlightPaint.setColor(0x26FFFFFF);
                canvas.drawRoundRect(itemRect, dp(8), dp(8), highlightPaint);

                Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                borderPaint.setStyle(Paint.Style.STROKE);
                borderPaint.setStrokeWidth(dp(1f));
                borderPaint.setColor(0x4DFFFFFF);
                canvas.drawRoundRect(itemRect, dp(8), dp(8), borderPaint);
                
                textPaint.setColor(0xFFFFFFFF);
                textPaint.setTextSize(dp(16));
            } else {
                textPaint.setColor(0xB3FFFFFF);
                textPaint.setTextSize(dp(14));
                textPaint.setFakeBoldText(false);
            }

            float textY = itemRect.centerY() - ((textPaint.descent() + textPaint.ascent()) / 2);
            canvas.drawText(ACTION_MENU_ITEMS[i], itemRect.centerX(), textY, textPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!guardedMode || !guardHeld || isActionMenuOpen || isLauncherOpen) {
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