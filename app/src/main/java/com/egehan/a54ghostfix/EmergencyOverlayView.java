package com.egehan.a54ghostfix;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

@SuppressLint("ViewConstructor")
final class EmergencyOverlayView extends View {
    interface Listener {
        void onTrustedTap(float x, float y);

        void onTrustedSwipe(float startX, float startY, float endX, float endY);

        void onTrayExpansionChanged(boolean expanded);
    }

    private static final long GUARD_SETTLE_MS = 70;
    private static final int APP_COLUMNS = 4;
    private static final int APP_ROWS = 2;
    private static final int APPS_PER_PAGE = APP_COLUMNS * APP_ROWS;

    private final Listener listener;
    private final float density;
    private final Rect focusBounds = new Rect();
    private final RectF highlightBounds = new RectF();
    private final Paint shieldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subtextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint focusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final String[] templates;
    private final String[] keyboardActions;

    private boolean guardedMode;
    private boolean guardHeld;
    private boolean trackingTrustedTouch;
    private long guardStartedAt;
    private float downX;
    private float downY;
    private String statusText = "";

    private boolean trayExpanded;
    private boolean keyboardMode;
    private boolean trayPressed;
    private boolean templateMenuOpen;
    private int selectedTrayIndex = -1;
    private int selectedTemplateIndex;
    private float trayHeight;
    private List<GhostTouchBypassService.AppInfo> apps = new ArrayList<>();

    EmergencyOverlayView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        density = getResources().getDisplayMetrics().density;
        templates = getResources().getStringArray(R.array.overlay_quick_message_values);
        keyboardActions = getResources().getStringArray(R.array.overlay_keyboard_actions);
        trayHeight = dp(8);

        setBackgroundColor(Color.TRANSPARENT);
        setClickable(true);
        setFocusable(false);

        panelPaint.setColor(0xF21A1E22);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1));
        borderPaint.setColor(0xFF4E5963);

        textPaint.setColor(Color.WHITE);
        textPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        textPaint.setTextSize(dp(14));

        subtextPaint.setColor(0xFFC8D0D6);
        subtextPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
        subtextPaint.setTextSize(dp(11));

        focusPaint.setStyle(Paint.Style.STROKE);
        focusPaint.setStrokeWidth(dp(4));
        focusPaint.setColor(0xFF69F0C2);

        selectedPaint.setColor(0xFF2E755F);
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
            guardStartedAt = SystemClock.uptimeMillis();
        } else {
            trackingTrustedTouch = false;
        }
        statusText = status;
        invalidate();
    }

    void setSelection(Rect bounds, String label) {
        focusBounds.set(bounds);
        invalidate();
    }

    void clearSelection() {
        focusBounds.setEmpty();
        invalidate();
    }

    void setTrayExpanded(boolean expanded) {
        trayExpanded = expanded;
        listener.onTrayExpansionChanged(expanded);
        trayHeight = expanded ? dp(250) : dp(8);
        invalidate();
    }

    void setKeyboardMode(boolean keyboardMode) {
        this.keyboardMode = keyboardMode;
        invalidate();
    }

    void setSelectedTrayIndex(int index) {
        selectedTrayIndex = index;
        invalidate();
    }

    void setTrayPressed(boolean pressed) {
        trayPressed = pressed;
        invalidate();
    }

    void setApps(List<GhostTouchBypassService.AppInfo> appsList) {
        apps = new ArrayList<>(appsList);
        invalidate();
    }

    void setTemplatesOpen(boolean open, int selectedIndex) {
        templateMenuOpen = open;
        selectedTemplateIndex = selectedIndex;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        shieldPaint.setColor(trayExpanded ? 0x5C000000 : 0x12000000);
        canvas.drawRect(0, 0, getWidth(), getHeight(), shieldPaint);

        drawStatus(canvas);
        if (!guardedMode && !focusBounds.isEmpty()) {
            highlightBounds.set(focusBounds);
            highlightBounds.inset(-dp(5), -dp(5));
            canvas.drawRoundRect(highlightBounds, dp(10), dp(10), focusPaint);
        }

        drawTray(canvas);
    }

    private void drawStatus(Canvas canvas) {
        RectF status = new RectF(dp(12), dp(12), getWidth() - dp(12), dp(64));
        canvas.drawRoundRect(status, dp(12), dp(12), panelPaint);
        canvas.drawRoundRect(status, dp(12), dp(12), borderPaint);
        canvas.drawText(statusText, dp(28), dp(44), textPaint);
    }

    private void drawTray(Canvas canvas) {
        float top = getHeight() - trayHeight;
        if (trayHeight < dp(24)) {
            Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
            handle.setColor(0xFFE5E9EC);
            canvas.drawRoundRect(
                    new RectF(getWidth() / 2f - dp(28), getHeight() - dp(7),
                            getWidth() / 2f + dp(28), getHeight() - dp(3)),
                    dp(2),
                    dp(2),
                    handle
            );
            return;
        }

        RectF tray = new RectF(0, top, getWidth(), getHeight());
        canvas.drawRoundRect(tray, dp(20), dp(20), panelPaint);
        canvas.drawRoundRect(tray, dp(20), dp(20), borderPaint);

        String title = templateMenuOpen
                ? getContext().getString(R.string.overlay_quick_messages)
                : (keyboardMode
                ? getContext().getString(R.string.overlay_text_controls)
                : getContext().getString(R.string.overlay_emergency_launcher));
        canvas.drawText(title, dp(18), top + dp(30), textPaint);

        if (templateMenuOpen) {
            drawTemplates(canvas, top + dp(42));
        } else if (keyboardMode) {
            drawKeyboardActions(canvas, top + dp(54));
        } else {
            drawApps(canvas, top + dp(44));
        }
    }

    private void drawApps(Canvas canvas, float top) {
        if (apps.isEmpty()) {
            canvas.drawText(
                    getContext().getString(R.string.overlay_no_apps),
                    dp(18),
                    top + dp(34),
                    subtextPaint
            );
            return;
        }

        int selected = Math.max(0, selectedTrayIndex);
        int pageStart = (selected / APPS_PER_PAGE) * APPS_PER_PAGE;
        float gap = dp(8);
        float itemWidth = (getWidth() - gap * (APP_COLUMNS + 1)) / APP_COLUMNS;
        float itemHeight = dp(78);

        for (int slot = 0; slot < APPS_PER_PAGE; slot++) {
            int appIndex = pageStart + slot;
            if (appIndex >= apps.size()) {
                break;
            }
            int row = slot / APP_COLUMNS;
            int column = slot % APP_COLUMNS;
            float left = gap + column * (itemWidth + gap);
            float itemTop = top + row * (itemHeight + gap);
            RectF item = new RectF(left, itemTop, left + itemWidth, itemTop + itemHeight);
            boolean focused = appIndex == selectedTrayIndex;
            drawItemBackground(canvas, item, focused);

            GhostTouchBypassService.AppInfo app = apps.get(appIndex);
            if (app.icon != null) {
                int iconSize = (int) dp(34);
                int iconLeft = (int) (item.centerX() - iconSize / 2f);
                int iconTop = (int) (item.top + dp(8));
                app.icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
                app.icon.draw(canvas);
            } else {
                Paint arrowPaint = new Paint(textPaint);
                arrowPaint.setTextAlign(Paint.Align.CENTER);
                arrowPaint.setTextSize(dp(28));
                canvas.drawText(app.symbol, item.centerX(), item.top + dp(39), arrowPaint);
            }

            Paint label = new Paint(subtextPaint);
            label.setTextAlign(Paint.Align.CENTER);
            label.setColor(focused ? Color.WHITE : 0xFFC8D0D6);
            canvas.drawText(ellipsize(app.name, label, itemWidth - dp(8)),
                    item.centerX(), item.bottom - dp(9), label);
        }

        int pageCount = (apps.size() + APPS_PER_PAGE - 1) / APPS_PER_PAGE;
        int page = pageStart / APPS_PER_PAGE + 1;
        Paint pagePaint = new Paint(subtextPaint);
        pagePaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(page + "/" + pageCount, getWidth() - dp(18), getHeight() - dp(12), pagePaint);
    }

    private void drawKeyboardActions(Canvas canvas, float top) {
        float gap = dp(7);
        float width = (getWidth() - gap * (keyboardActions.length + 1))
                / keyboardActions.length;
        for (int i = 0; i < keyboardActions.length; i++) {
            float left = gap + i * (width + gap);
            RectF item = new RectF(left, top, left + width, top + dp(58));
            boolean focused = selectedTrayIndex == i;
            drawItemBackground(canvas, item, focused);
            Paint label = new Paint(subtextPaint);
            label.setTextAlign(Paint.Align.CENTER);
            label.setColor(focused ? Color.WHITE : 0xFFC8D0D6);
            canvas.drawText(
                    ellipsize(keyboardActions[i], label, item.width() - dp(6)),
                    item.centerX(),
                    item.centerY() + dp(4),
                    label
            );
        }
    }

    private void drawTemplates(Canvas canvas, float top) {
        int selected = Math.max(0, selectedTemplateIndex);
        int pageStart = (selected / 5) * 5;
        float itemHeight = dp(32);
        for (int slot = 0; slot < 5; slot++) {
            int index = pageStart + slot;
            if (index >= templates.length) {
                break;
            }
            float y = top + slot * (itemHeight + dp(5));
            RectF item = new RectF(dp(14), y, getWidth() - dp(14), y + itemHeight);
            boolean focused = index == selectedTemplateIndex;
            drawItemBackground(canvas, item, focused);
            Paint label = new Paint(subtextPaint);
            label.setColor(focused ? Color.WHITE : 0xFFC8D0D6);
            canvas.drawText(templates[index], item.left + dp(12), item.centerY() + dp(4), label);
        }
    }

    private void drawItemBackground(Canvas canvas, RectF item, boolean focused) {
        Paint fill = focused ? selectedPaint : panelPaint;
        if (focused && trayPressed) {
            item.inset(dp(2), dp(2));
        }
        canvas.drawRoundRect(item, dp(9), dp(9), fill);
        canvas.drawRoundRect(item, dp(9), dp(9), borderPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!guardedMode || !guardHeld || trayExpanded) {
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
                    performClick();
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

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }

    private String ellipsize(String value, Paint paint, float maxWidth) {
        if (value == null) {
            return "";
        }
        if (paint.measureText(value) <= maxWidth) {
            return value;
        }
        String suffix = "...";
        int end = value.length();
        while (end > 0 && paint.measureText(value.substring(0, end) + suffix) > maxWidth) {
            end--;
        }
        return value.substring(0, end) + suffix;
    }

    private float dp(float value) {
        return value * density;
    }
}
