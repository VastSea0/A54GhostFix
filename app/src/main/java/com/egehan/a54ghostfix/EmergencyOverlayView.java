package com.egehan.a54ghostfix;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    private boolean guardedMode = false;
    private boolean guardHeld = false;
    private boolean trackingTrustedTouch = false;
    private long guardStartedAt = 0;
    private float downX = 0;
    private float downY = 0;
    private String statusText = "";
    private String selectionText = "";

    // Tray states
    private float trayHeight;
    private boolean isTrayExpanded = false;
    private boolean isKeyboardMode = false;
    private boolean isTrayPressed = false;
    private int selectedTrayIndex = -1;
    private List<GhostTouchBypassService.AppInfo> apps = new ArrayList<>();

    // Template menu states
    private boolean isTemplateMenuOpen = false;
    private int selectedTemplateIndex = 0;
    private String[] templates = {
            "Merhaba!",
            "Tamam",
            "Yoldayım, geliyorum.",
            "Seni sonra arayacağım.",
            "Neredesin?",
            "Evet",
            "Hayır"
    };

    private ValueAnimator trayAnimator;

    EmergencyOverlayView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        density = getResources().getDisplayMetrics().density;
        trayHeight = dp(8); // Extremely compact start

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

    void setTrayExpanded(boolean expanded) {
        if (this.isTrayExpanded == expanded) return;
        this.isTrayExpanded = expanded;

        if (trayAnimator != null) {
            trayAnimator.cancel();
        }

        float start = trayHeight;
        float end = expanded ? dp(220) : dp(8); // Compact expanded height

        trayAnimator = ValueAnimator.ofFloat(start, end);
        trayAnimator.setDuration(180);
        trayAnimator.setInterpolator(new DecelerateInterpolator());
        trayAnimator.addUpdateListener(animation -> {
            trayHeight = (float) animation.getAnimatedValue();
            invalidate();
        });
        trayAnimator.start();
    }

    void setKeyboardMode(boolean keyboardMode) {
        this.isKeyboardMode = keyboardMode;
        invalidate();
    }

    void setSelectedTrayIndex(int index) {
        this.selectedTrayIndex = index;
        invalidate();
    }

    void setTrayPressed(boolean pressed) {
        this.isTrayPressed = pressed;
        invalidate();
    }

    void setApps(List<GhostTouchBypassService.AppInfo> appsList) {
        this.apps = appsList;
        invalidate();
    }

    void setTemplatesOpen(boolean open, int selectedIndex) {
        this.isTemplateMenuOpen = open;
        this.selectedTemplateIndex = selectedIndex;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (isTrayExpanded) {
            shieldPaint.setColor(0x80000000);
        } else {
            shieldPaint.setColor(0x10000000);
        }
        canvas.drawRect(0, 0, getWidth(), getHeight(), shieldPaint);

        // 1. Focus Ring
        if (!guardedMode && !focusBounds.isEmpty()) {
            RectF highlight = new RectF(focusBounds);
            highlight.inset(-dp(4), -dp(4));

            Paint focusStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
            focusStroke.setStyle(Paint.Style.STROKE);
            focusStroke.setStrokeWidth(dp(2f));
            focusStroke.setColor(0xFFE5C97A); // Gold accent
            canvas.drawRoundRect(highlight, dp(8), dp(8), focusStroke);
        }

        // 2. Bottom Tray
        drawBottomTray(canvas);
    }

    private void drawBottomTray(Canvas canvas) {
        float topY = getHeight() - trayHeight;
        RectF trayRect = new RectF(0, topY, getWidth(), getHeight());

        // Background (#2C2C2E surface)
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(0xFF2C2C2E);
        
        Path path = new Path();
        float[] radii = {dp(12), dp(12), dp(12), dp(12), 0, 0, 0, 0};
        path.addRoundRect(trayRect, radii, Path.Direction.CW);
        canvas.drawPath(path, bgPaint);

        // Reflection Line (#5A5A5C)
        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1));
        borderPaint.setColor(0xFF5A5A5C);
        canvas.drawLine(0, topY, getWidth(), topY, borderPaint);

        if (trayHeight < dp(40)) {
            drawMinimizedDisplay(canvas, trayRect);
            return;
        }

        // LCD status
        float lcdHeight = dp(40);
        float lcdMargin = dp(8);
        RectF lcdRect = new RectF(lcdMargin, topY + lcdMargin, getWidth() - lcdMargin, topY + lcdMargin + lcdHeight);
        drawLCDDisplay(canvas, lcdRect);

        float contentTop = lcdRect.bottom + dp(8);
        float contentHeight = getHeight() - contentTop;

        if (isTemplateMenuOpen) {
            drawTemplatesList(canvas, contentTop, contentHeight);
        } else if (isKeyboardMode) {
            drawKeyboardButtons(canvas, contentTop, contentHeight);
        } else {
            drawAppsGrid(canvas, contentTop, contentHeight);
        }
    }

    private void drawMinimizedDisplay(Canvas canvas, RectF rect) {
        // Draw thin premium gold line at the top of the tray when minimized - Extremely thin & non-blocking
        Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(0xFFE5C97A);
        linePaint.setStrokeWidth(dp(2));
        canvas.drawLine(rect.left, rect.top + dp(1), rect.right, rect.top + dp(1), linePaint);
    }

    private void drawLCDDisplay(Canvas canvas, RectF rect) {
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(0xFF1A1F1A);
        canvas.drawRoundRect(rect, dp(4), dp(4), bgPaint);

        Paint textP = new Paint(Paint.ANTI_ALIAS_FLAG);
        textP.setColor(0xFFC8D8A8);
        textP.setTextSize(dp(11));
        textP.setTypeface(Typeface.MONOSPACE);
        textP.setTextAlign(Paint.Align.LEFT);

        float textY = rect.centerY() - ((textP.descent() + textP.ascent()) / 2);
        String label = isTemplateMenuOpen ? "SABLON SECIM MENUSU" : (isKeyboardMode ? "METIN GIRISI: AKTIF" : "UYGULAMA BASLATICI");
        canvas.drawText("> " + label, rect.left + dp(8), textY, textP);

        if (!selectionText.isEmpty() && !isKeyboardMode && !isTemplateMenuOpen) {
            textP.setTextAlign(Paint.Align.RIGHT);
            String displayVal = selectionText;
            if (displayVal.length() > 20) displayVal = displayVal.substring(0, 18) + "...";
            canvas.drawText(displayVal.toUpperCase(Locale.ROOT), rect.right - dp(8), textY, textP);
        }
    }

    private void drawKeyboardButtons(Canvas canvas, float top, float height) {
        String[] buttons = {"SIL", "BOSLUK", "ENTER", "SABLON", "KAPAT"};
        float spacing = dp(8);
        float btnWidth = (getWidth() - spacing * 6) / 5;
        float btnHeight = dp(50);

        for (int i = 0; i < buttons.length; i++) {
            float left = spacing + i * (btnWidth + spacing);
            RectF rect = new RectF(left, top + dp(10), left + btnWidth, top + dp(10) + btnHeight);
            
            boolean isFocused = (selectedTrayIndex == i);
            boolean isPressed = isFocused && isTrayPressed;
            drawSkeuomorphicButton(canvas, rect, buttons[i], isFocused, isPressed);
        }
    }

    private void drawAppsGrid(Canvas canvas, float top, float height) {
        if (apps.isEmpty()) {
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(0xFF8E8E93);
            paint.setTextSize(dp(14));
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Uygulama Bulunamadı", getWidth() / 2f, top + dp(40), paint);
            return;
        }

        int cols = 4;
        int rows = 2;
        int maxApps = cols * rows;

        int activeAppIndex = -1;
        if (selectedTrayIndex >= 0) {
            activeAppIndex = selectedTrayIndex;
        }

        int pageStart = 0;
        if (activeAppIndex >= 0) {
            pageStart = (activeAppIndex / maxApps) * maxApps;
        }

        float spacingX = dp(12);
        float spacingY = dp(10);
        float itemWidth = (getWidth() - spacingX * (cols + 1)) / cols;
        float itemHeight = dp(65);

        Paint textP = new Paint(Paint.ANTI_ALIAS_FLAG);
        textP.setColor(0xFFF5F5F5);
        textP.setTextSize(dp(9));
        textP.setTextAlign(Paint.Align.CENTER);

        for (int i = 0; i < maxApps; i++) {
            int appIndex = pageStart + i;
            if (appIndex >= apps.size()) break;

            GhostTouchBypassService.AppInfo app = apps.get(appIndex);
            int row = i / cols;
            int col = i % cols;

            float x = spacingX + col * (itemWidth + spacingX);
            float y = top + spacingY + row * (itemHeight + spacingY);

            RectF rect = new RectF(x, y, x + itemWidth, y + itemHeight);

            boolean isFocused = (appIndex == activeAppIndex);
            boolean isPressed = isFocused && isTrayPressed;

            if (isPressed) {
                rect.inset(rect.width() * 0.015f, rect.height() * 0.015f);
            }

            if (isFocused) {
                Paint borderP = new Paint(Paint.ANTI_ALIAS_FLAG);
                borderP.setStyle(Paint.Style.STROKE);
                borderP.setStrokeWidth(dp(2));
                borderP.setColor(0xFFE5C97A);
                canvas.drawRoundRect(rect, dp(6), dp(6), borderP);
            } else {
                Paint bgP = new Paint(Paint.ANTI_ALIAS_FLAG);
                bgP.setColor(0xFF3A3A3C);
                canvas.drawRoundRect(rect, dp(6), dp(6), bgP);
            }

            if (app.icon != null) {
                int iconSize = (int) dp(32);
                int iconLeft = (int) (rect.centerX() - iconSize / 2f);
                int iconTop = (int) (rect.top + dp(6));
                app.icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
                app.icon.draw(canvas);
            }

            String name = app.name;
            if (name.length() > 9) {
                name = name.substring(0, 7) + "..";
            }
            canvas.drawText(name.toUpperCase(Locale.ROOT), rect.centerX(), rect.bottom - dp(6), textP);
        }
    }

    private void drawTemplatesList(Canvas canvas, float top, float height) {
        float itemHeight = dp(26);
        float spacing = dp(4);
        int maxItems = 4;

        int pageStart = (selectedTemplateIndex / maxItems) * maxItems;

        Paint textP = new Paint(Paint.ANTI_ALIAS_FLAG);
        textP.setTextSize(dp(12));
        textP.setTextAlign(Paint.Align.CENTER);

        for (int i = 0; i < maxItems; i++) {
            int index = pageStart + i;
            if (index >= templates.length) break;

            float y = top + dp(6) + i * (itemHeight + spacing);
            RectF rect = new RectF(dp(16), y, getWidth() - dp(16), y + itemHeight);

            boolean isFocused = (index == selectedTemplateIndex);
            boolean isPressed = isFocused && isTrayPressed;

            if (isPressed) {
                rect.inset(rect.width() * 0.015f, rect.height() * 0.015f);
            }

            Paint bgP = new Paint(Paint.ANTI_ALIAS_FLAG);
            bgP.setStyle(Paint.Style.FILL);
            bgP.setColor(isFocused ? 0xFF3A3A3C : 0xFF2C2C2E);
            canvas.drawRoundRect(rect, dp(6), dp(6), bgP);

            if (isFocused) {
                Paint borderP = new Paint(Paint.ANTI_ALIAS_FLAG);
                borderP.setStyle(Paint.Style.STROKE);
                borderP.setStrokeWidth(dp(1.5f));
                borderP.setColor(0xFFE5C97A);
                canvas.drawRoundRect(rect, dp(6), dp(6), borderP);
                textP.setColor(0xFFE5C97A);
            } else {
                textP.setColor(0xFFF5F5F5);
            }

            float textY = rect.centerY() - ((textP.descent() + textP.ascent()) / 2f);
            canvas.drawText(templates[index], rect.centerX(), textY, textP);
        }
    }

    private void drawSkeuomorphicButton(Canvas canvas, RectF rect, String text, boolean isFocused, boolean isPressed) {
        if (isPressed) {
            rect.inset(rect.width() * 0.015f, rect.height() * 0.015f);
        }

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        LinearGradient shader;
        if (isPressed) {
            shader = new LinearGradient(rect.left, rect.top, rect.left, rect.bottom, 0xFF3A3A3C, 0xFF4A4A4C, Shader.TileMode.CLAMP);
        } else {
            shader = new LinearGradient(rect.left, rect.top, rect.left, rect.bottom, 0xFF48484A, 0xFF3A3A3C, Shader.TileMode.CLAMP);
        }
        paint.setShader(shader);
        canvas.drawRoundRect(rect, dp(6), dp(6), paint);

        Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        borderPaint.setStyle(Paint.Style.STROKE);

        if (isFocused) {
            borderPaint.setStrokeWidth(dp(2));
            borderPaint.setColor(0xFFE5C97A);
            canvas.drawRoundRect(rect, dp(6), dp(6), borderPaint);
        } else {
            borderPaint.setStrokeWidth(dp(1));
            
            borderPaint.setColor(0xFF1C1C1E);
            Path darkPath = new Path();
            darkPath.moveTo(rect.left + dp(6), rect.bottom);
            darkPath.lineTo(rect.right - dp(6), rect.bottom);
            darkPath.quadTo(rect.right, rect.bottom, rect.right, rect.bottom - dp(6));
            darkPath.lineTo(rect.right, rect.top + dp(6));
            canvas.drawPath(darkPath, borderPaint);

            borderPaint.setColor(0xFF5A5A5C);
            Path lightPath = new Path();
            lightPath.moveTo(rect.right - dp(6), rect.top);
            lightPath.lineTo(rect.left + dp(6), rect.top);
            lightPath.quadTo(rect.left, rect.top, rect.left, rect.top + dp(6));
            lightPath.lineTo(rect.left, rect.bottom - dp(6));
            canvas.drawPath(lightPath, borderPaint);
        }

        Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(0xFFF5F5F5);
        labelPaint.setTextSize(dp(11));
        labelPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        labelPaint.setTextAlign(Paint.Align.CENTER);

        float textY = rect.centerY() - ((labelPaint.descent() + labelPaint.ascent()) / 2);
        if (isPressed) {
            textY += dp(1);
        }
        canvas.drawText(text.toUpperCase(Locale.ROOT), rect.centerX(), textY, labelPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!guardedMode || !guardHeld || isTrayExpanded) {
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

    private float dp(float value) {
        return value * density;
    }
}