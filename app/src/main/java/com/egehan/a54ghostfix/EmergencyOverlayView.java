package com.egehan.a54ghostfix;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
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
import android.os.Build;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class EmergencyOverlayView extends FrameLayout {
    interface Listener {
        void onTrustedTap(float x, float y);
        void onTrustedSwipe(float startX, float startY, float endX, float endY);
        void onTrayExpansionChanged(boolean expanded);
    }

    private static final long GUARD_SETTLE_MS = 70;
    private static final String TAG = "EmergencyOverlayView";

    private final Listener listener;
    private final Paint shieldPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
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
    private float specularSweepProgress = 0.0f;
    private float buttonScale = 1.0f;
    private ValueAnimator buttonScaleAnimator;
    private boolean isRenderEffectApplied = false;

    // View components for Liquid Glass layers
    private final GlassBackgroundView mGlassBackgroundView;
    private final GlassForegroundView mGlassForegroundView;

    private final String refractionShaderCode =
            "uniform shader content;\n" +
            "uniform float2 size;\n" +
            "\n" +
            "half4 main(float2 coord) {\n" +
            "  float2 center = size * 0.5;\n" +
            "  float2 delta = coord - center;\n" +
            "  float dist = length(delta) / length(center);\n" +
            "  float refraction = 1.0 + dist * dist * 0.08;\n" +
            "  float2 refractedCoord = center + delta * refraction;\n" +
            "  refractedCoord = clamp(refractedCoord, float2(0.0, 0.0), size);\n" +
            "  return content.eval(refractedCoord);\n" +
            "}\n";

    EmergencyOverlayView(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        density = getResources().getDisplayMetrics().density;
        trayHeight = dp(8); // Start minimized

        setBackgroundColor(Color.TRANSPARENT);
        setClickable(true);
        setFocusable(false);
        setLayerType(LAYER_TYPE_NONE, null);

        mGlassBackgroundView = new GlassBackgroundView(context);
        mGlassForegroundView = new GlassForegroundView(context);

        addView(mGlassBackgroundView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        addView(mGlassForegroundView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
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

        if (listener != null) {
            listener.onTrayExpansionChanged(expanded);
        }

        if (trayAnimator != null) {
            trayAnimator.cancel();
        }

        float start = trayHeight;
        float end = expanded ? dp(220) : dp(8);

        if (expanded) {
            updateRenderEffectState();
        }

        trayAnimator = ValueAnimator.ofFloat(start, end);
        trayAnimator.setDuration(280);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            trayAnimator.setInterpolator(new android.view.animation.PathInterpolator(0.34f, 1.56f, 0.64f, 1.0f));
        } else {
            trayAnimator.setInterpolator(new DecelerateInterpolator());
        }
        trayAnimator.addUpdateListener(animation -> {
            trayHeight = (float) animation.getAnimatedValue();
            float progress = (trayHeight - dp(8)) / (dp(220) - dp(8));
            specularSweepProgress = Math.max(0.0f, Math.min(progress, 1.0f));
            invalidate();
        });
        trayAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (!expanded) {
                    updateRenderEffectState();
                }
            }
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
        if (this.isTrayPressed == pressed) return;
        this.isTrayPressed = pressed;

        if (buttonScaleAnimator != null) {
            buttonScaleAnimator.cancel();
        }

        float start = buttonScale;
        float end = pressed ? 0.94f : 1.0f;

        buttonScaleAnimator = ValueAnimator.ofFloat(start, end);
        buttonScaleAnimator.setDuration(pressed ? 80 : 180);
        if (pressed) {
            buttonScaleAnimator.setInterpolator(new DecelerateInterpolator());
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                buttonScaleAnimator.setInterpolator(new android.view.animation.PathInterpolator(0.34f, 1.56f, 0.64f, 1.0f));
            } else {
                buttonScaleAnimator.setInterpolator(new DecelerateInterpolator());
            }
        }
        buttonScaleAnimator.addUpdateListener(animation -> {
            buttonScale = (float) animation.getAnimatedValue();
            invalidate();
        });
        buttonScaleAnimator.start();
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
    public void invalidate() {
        super.invalidate();
        if (mGlassBackgroundView != null) {
            mGlassBackgroundView.invalidate();
        }
        if (mGlassForegroundView != null) {
            mGlassForegroundView.invalidate();
        }
    }

    private void updateRenderEffectState() {
        if (mGlassBackgroundView == null) return;
        if (isTrayExpanded && trayHeight > dp(8)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!isRenderEffectApplied) {
                    applyLiquidGlassRenderEffect(mGlassBackgroundView, getWidth(), getHeight());
                    isRenderEffectApplied = true;
                }
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (isRenderEffectApplied) {
                    mGlassBackgroundView.setRenderEffect(null);
                    isRenderEffectApplied = false;
                }
            }
        }
    }

    private void applyLiquidGlassRenderEffect(View view, float width, float height) {
        if (width <= 0 || height <= 0) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                android.graphics.RenderEffect blurEffect = android.graphics.RenderEffect.createBlurEffect(
                        20f, 20f, android.graphics.Shader.TileMode.CLAMP
                );
                android.graphics.RuntimeShader runtimeShader = new android.graphics.RuntimeShader(refractionShaderCode);
                runtimeShader.setFloatUniform("size", width, height);
                android.graphics.RenderEffect shaderEffect = android.graphics.RenderEffect.createRuntimeShaderEffect(runtimeShader, "content");
                android.graphics.RenderEffect chainedEffect = android.graphics.RenderEffect.createChainEffect(blurEffect, shaderEffect);
                view.setRenderEffect(chainedEffect);
            } catch (Exception e) {
                Log.e(TAG, "Failed to apply API 33 AGSL shader effect", e);
                applyApi31BlurFallback(view);
            }
        } else {
            applyApi31BlurFallback(view);
        }
    }

    private void applyApi31BlurFallback(View view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                android.graphics.RenderEffect blurEffect = android.graphics.RenderEffect.createBlurEffect(
                        16f, 16f, android.graphics.Shader.TileMode.CLAMP
                );
                view.setRenderEffect(blurEffect);
            } catch (Exception e) {
                Log.e(TAG, "Failed to apply API 31 fallback blur", e);
            }
        }
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

    // LAYER 1 & 2: Background Glass Panels with low opacity fills
    private class GlassBackgroundView extends View {
        GlassBackgroundView(Context context) {
            super(context);
            setLayerType(LAYER_TYPE_HARDWARE, null);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (w > 0 && h > 0) {
                isRenderEffectApplied = false;
                updateRenderEffectState();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            if (trayHeight < dp(40)) {
                return;
            }

            float topY = getHeight() - trayHeight;
            RectF trayRect = new RectF(0, topY, getWidth(), getHeight());

            // 1. Bottom Tray Fill (10% translucent white)
            Paint trayFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            trayFillPaint.setColor(Color.argb(26, 255, 255, 255));
            trayFillPaint.setStyle(Paint.Style.FILL);

            Path trayPath = new Path();
            float r = dp(20);
            float[] radii = {r, r, r, r, 0, 0, 0, 0};
            trayPath.addRoundRect(trayRect, radii, Path.Direction.CW);
            canvas.drawPath(trayPath, trayFillPaint);

            // 2. LCD Display Fill (5% translucent white)
            float lcdHeight = dp(40);
            float lcdMargin = dp(8);
            RectF lcdRect = new RectF(lcdMargin, topY + lcdMargin, getWidth() - lcdMargin, topY + lcdMargin + lcdHeight);

            Paint lcdFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            lcdFillPaint.setColor(Color.argb(13, 255, 255, 255));
            lcdFillPaint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(lcdRect, dp(4), dp(4), lcdFillPaint);

            float contentTop = lcdRect.bottom + dp(8);
            float contentHeight = getHeight() - contentTop;

            // 3. Nested Items Fill
            if (isTemplateMenuOpen) {
                drawTemplatesFills(canvas, contentTop, contentHeight);
            } else if (isKeyboardMode) {
                drawKeyboardFills(canvas, contentTop, contentHeight);
            } else {
                drawAppsFills(canvas, contentTop, contentHeight);
            }
        }

        private void drawKeyboardFills(Canvas canvas, float top, float height) {
            float spacing = dp(8);
            float btnWidth = (getWidth() - spacing * 6) / 5;
            float btnHeight = dp(50);

            for (int i = 0; i < 5; i++) {
                float left = spacing + i * (btnWidth + spacing);
                RectF rect = new RectF(left, top + dp(10), left + btnWidth, top + dp(10) + btnHeight);

                boolean isFocused = (selectedTrayIndex == i);
                boolean isPressed = isFocused && isTrayPressed;

                int alpha = 20; // 8% normal
                if (isPressed) {
                    alpha = 64; // 25% pressed
                    float insetX = rect.width() * (1.0f - buttonScale) / 2f;
                    float insetY = rect.height() * (1.0f - buttonScale) / 2f;
                    rect.inset(insetX, insetY);
                } else if (isFocused) {
                    alpha = 46; // 18% focused
                }

                Paint btnFill = new Paint(Paint.ANTI_ALIAS_FLAG);
                btnFill.setColor(Color.argb(alpha, 255, 255, 255));
                btnFill.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(rect, dp(6), dp(6), btnFill);
            }
        }

        private void drawAppsFills(Canvas canvas, float top, float height) {
            if (apps.isEmpty()) return;

            int cols = 4;
            int rows = 2;
            int maxApps = cols * rows;
            int activeAppIndex = selectedTrayIndex >= 0 ? selectedTrayIndex : -1;
            int pageStart = activeAppIndex >= 0 ? (activeAppIndex / maxApps) * maxApps : 0;

            float spacingX = dp(12);
            float spacingY = dp(10);
            float itemWidth = (getWidth() - spacingX * (cols + 1)) / cols;
            float itemHeight = dp(65);

            for (int i = 0; i < maxApps; i++) {
                int appIndex = pageStart + i;
                if (appIndex >= apps.size()) break;

                int row = i / cols;
                int col = i % cols;
                float x = spacingX + col * (itemWidth + spacingX);
                float y = top + spacingY + row * (itemHeight + spacingY);
                RectF rect = new RectF(x, y, x + itemWidth, y + itemHeight);

                boolean isFocused = (appIndex == activeAppIndex);
                boolean isPressed = isFocused && isTrayPressed;

                int alpha = 31; // 12% normal
                if (isPressed) {
                    alpha = 64; // 25% pressed
                    float insetX = rect.width() * (1.0f - buttonScale) / 2f;
                    float insetY = rect.height() * (1.0f - buttonScale) / 2f;
                    rect.inset(insetX, insetY);
                } else if (isFocused) {
                    alpha = 46; // 18% focused
                }

                Paint appFill = new Paint(Paint.ANTI_ALIAS_FLAG);
                appFill.setColor(Color.argb(alpha, 255, 255, 255));
                appFill.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(rect, dp(6), dp(6), appFill);
            }
        }

        private void drawTemplatesFills(Canvas canvas, float top, float height) {
            float itemHeight = dp(26);
            float spacing = dp(4);
            int maxItems = 4;
            int pageStart = (selectedTemplateIndex / maxItems) * maxItems;

            for (int i = 0; i < maxItems; i++) {
                int index = pageStart + i;
                if (index >= templates.length) break;

                float y = top + dp(6) + i * (itemHeight + spacing);
                RectF rect = new RectF(dp(16), y, getWidth() - dp(16), y + itemHeight);

                boolean isFocused = (index == selectedTemplateIndex);
                boolean isPressed = isFocused && isTrayPressed;

                int alpha = 26; // 10% normal
                if (isPressed) {
                    alpha = 64; // 25% pressed
                    float insetX = rect.width() * (1.0f - buttonScale) / 2f;
                    float insetY = rect.height() * (1.0f - buttonScale) / 2f;
                    rect.inset(insetX, insetY);
                } else if (isFocused) {
                    alpha = 46; // 18% focused
                }

                Paint tmplFill = new Paint(Paint.ANTI_ALIAS_FLAG);
                tmplFill.setColor(Color.argb(alpha, 255, 255, 255));
                tmplFill.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(rect, dp(6), dp(6), tmplFill);
            }
        }
    }

    // LAYER 3 & 4: Sharp elements (Borders, specular lines, drop shadows, texts, icons)
    private class GlassForegroundView extends View {
        GlassForegroundView(Context context) {
            super(context);
            setLayerType(LAYER_TYPE_HARDWARE, null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            // Screen Dim (Shield overlay)
            if (isTrayExpanded) {
                shieldPaint.setColor(0x80000000);
            } else {
                shieldPaint.setColor(0x10000000);
            }
            canvas.drawRect(0, 0, getWidth(), getHeight(), shieldPaint);

            // Focus Ring (Layer 3: rgba(255,255,255,0.7) clean white glow indicator)
            if (!guardedMode && !focusBounds.isEmpty()) {
                RectF highlight = new RectF(focusBounds);
                highlight.inset(-dp(4), -dp(4));

                Paint focusStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
                focusStroke.setStyle(Paint.Style.STROKE);
                focusStroke.setStrokeWidth(dp(2f));
                focusStroke.setColor(Color.argb(179, 255, 255, 255));
                canvas.drawRoundRect(highlight, dp(8), dp(8), focusStroke);
            }

            drawBottomTrayForeground(canvas);
        }

        private void drawBottomTrayForeground(Canvas canvas) {
            float topY = getHeight() - trayHeight;

            if (trayHeight < dp(40)) {
                // Minimized tray display - thin white glass line
                Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                linePaint.setColor(Color.argb(179, 255, 255, 255));
                linePaint.setStrokeWidth(dp(2));
                canvas.drawLine(0, topY + dp(1), getWidth(), topY + dp(1), linePaint);
                return;
            }

            RectF trayRect = new RectF(0, topY, getWidth(), getHeight());

            // 1. Shadow (Layer 4: Soft diffused shadow)
            Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadowPaint.setStyle(Paint.Style.FILL);
            shadowPaint.setColor(Color.TRANSPARENT);
            shadowPaint.setShadowLayer(dp(24), 0, dp(8), Color.argb(89, 0, 0, 0));

            Path trayPath = new Path();
            float r = dp(20);
            float[] radii = {r, r, r, r, 0, 0, 0, 0};
            trayPath.addRoundRect(trayRect, radii, Path.Direction.CW);
            canvas.drawPath(trayPath, shadowPaint);

            // 2. Specular Line & Sweep Animation (Layer 3: Top specular light play)
            Paint specularPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            specularPaint.setStrokeWidth(dp(1.2f));
            specularPaint.setStyle(Paint.Style.STROKE);

            float sweepCenterX = getWidth() * specularSweepProgress;
            float sweepWidth = dp(140);
            LinearGradient specularShader = new LinearGradient(
                    sweepCenterX - sweepWidth, topY + 1, sweepCenterX + sweepWidth, topY + 1,
                    new int[]{
                            Color.TRANSPARENT,
                            Color.argb(153, 255, 255, 255), // 60% white reflection
                            Color.TRANSPARENT
                    },
                    new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP
            );
            specularPaint.setShader(specularShader);
            canvas.drawLine(r, topY + 1, getWidth() - r, topY + 1, specularPaint);

            // 3. Border (Layer 3: Glass border gradient)
            Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(dp(1.5f));
            LinearGradient borderShader = new LinearGradient(
                    0, topY, getWidth(), getHeight(),
                    Color.argb(64, 255, 255, 255), // 25% upper-left
                    Color.argb(13, 255, 255, 255), // 5% lower-right
                    Shader.TileMode.CLAMP
            );
            borderPaint.setShader(borderShader);
            canvas.drawPath(trayPath, borderPaint);

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

        private void drawLCDDisplay(Canvas canvas, RectF rect) {
            // LCD Border
            Paint borderP = new Paint(Paint.ANTI_ALIAS_FLAG);
            borderP.setStyle(Paint.Style.STROKE);
            borderP.setStrokeWidth(dp(1));
            borderP.setColor(Color.argb(51, 255, 255, 255)); // 20% border
            canvas.drawRoundRect(rect, dp(4), dp(4), borderP);

            // Specular screen reflection line
            Paint lcdSpec = new Paint(Paint.ANTI_ALIAS_FLAG);
            lcdSpec.setStrokeWidth(dp(1.2f));
            lcdSpec.setColor(Color.argb(128, 255, 255, 255)); // 50% specular
            canvas.drawLine(rect.left + dp(4), rect.top + 1, rect.right - dp(4), rect.top + 1, lcdSpec);

            Paint textP = new Paint(Paint.ANTI_ALIAS_FLAG);
            textP.setColor(0xFFE0F0E0); // #E0F0E0 LCD text
            textP.setTextSize(dp(11));
            textP.setTypeface(Typeface.MONOSPACE);
            textP.setTextAlign(Paint.Align.LEFT);

            float textY = rect.centerY() - ((textP.descent() + textP.ascent()) / 2);
            String label = isTemplateMenuOpen ? "SABLON SECIM MENUSU" : (isKeyboardMode ? "METIN GIRISI: AKTIF" : "UYGULAMA BASLATICI");
            canvas.drawText("> " + label, rect.left + dp(8), textY, textP);

            if (!selectionText.isEmpty() && !isKeyboardMode && !isTemplateMenuOpen) {
                textP.setTextAlign(Paint.Align.RIGHT);
                String displayVal = selectionText;
                if (displayVal.length() > 20) {
                    displayVal = displayVal.substring(0, 18) + "...";
                }
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

                if (isPressed) {
                    float insetX = rect.width() * (1.0f - buttonScale) / 2f;
                    float insetY = rect.height() * (1.0f - buttonScale) / 2f;
                    rect.inset(insetX, insetY);
                }

                // 1. Shadow (Layer 4)
                Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                shadowPaint.setStyle(Paint.Style.FILL);
                shadowPaint.setColor(Color.TRANSPARENT);
                shadowPaint.setShadowLayer(dp(6), 0, dp(3), Color.argb(64, 0, 0, 0));
                canvas.drawRoundRect(rect, dp(6), dp(6), shadowPaint);

                // 2. Border (Layer 3)
                Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                borderPaint.setStyle(Paint.Style.STROKE);
                if (isFocused) {
                    borderPaint.setStrokeWidth(dp(2));
                    borderPaint.setColor(Color.argb(179, 255, 255, 255)); // 70% focus border
                } else {
                    borderPaint.setStrokeWidth(dp(1.2f));
                    borderPaint.setColor(Color.argb(38, 255, 255, 255)); // 15% normal border
                }
                canvas.drawRoundRect(rect, dp(6), dp(6), borderPaint);

                // 3. Specular top-left corner reflection
                if (!isPressed) {
                    Paint specPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    specPaint.setStyle(Paint.Style.STROKE);
                    specPaint.setStrokeWidth(dp(1.2f));
                    specPaint.setColor(Color.argb(isFocused ? 204 : 102, 255, 255, 255)); // 80% vs 40%

                    Path specPath = new Path();
                    float cornerR = dp(6);
                    specPath.moveTo(rect.left, rect.bottom - cornerR);
                    specPath.lineTo(rect.left, rect.top + cornerR);
                    specPath.quadTo(rect.left, rect.top, rect.left + cornerR, rect.top);
                    specPath.lineTo(rect.right - cornerR, rect.top);
                    canvas.drawPath(specPath, specPaint);
                }

                // 4. Label Text
                Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                labelPaint.setColor(0xFFFFFFFF);
                labelPaint.setTextSize(dp(11));
                labelPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
                labelPaint.setTextAlign(Paint.Align.CENTER);

                float textY = rect.centerY() - ((labelPaint.descent() + labelPaint.ascent()) / 2);
                if (isPressed) {
                    textY += dp(1);
                }
                canvas.drawText(buttons[i].toUpperCase(Locale.ROOT), rect.centerX(), textY, labelPaint);
            }
        }

        private void drawAppsGrid(Canvas canvas, float top, float height) {
            if (apps.isEmpty()) {
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setColor(Color.argb(153, 255, 255, 255));
                paint.setTextSize(dp(14));
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("Uygulama Bulunamadı", getWidth() / 2f, top + dp(40), paint);
                return;
            }

            int cols = 4;
            int rows = 2;
            int maxApps = cols * rows;
            int activeAppIndex = selectedTrayIndex >= 0 ? selectedTrayIndex : -1;
            int pageStart = activeAppIndex >= 0 ? (activeAppIndex / maxApps) * maxApps : 0;

            float spacingX = dp(12);
            float spacingY = dp(10);
            float itemWidth = (getWidth() - spacingX * (cols + 1)) / cols;
            float itemHeight = dp(65);

            Paint textP = new Paint(Paint.ANTI_ALIAS_FLAG);
            textP.setColor(0xFFFFFFFF);
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
                    float insetX = rect.width() * (1.0f - buttonScale) / 2f;
                    float insetY = rect.height() * (1.0f - buttonScale) / 2f;
                    rect.inset(insetX, insetY);
                }

                // 1. Shadow (Layer 4)
                Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                shadowPaint.setStyle(Paint.Style.FILL);
                shadowPaint.setColor(Color.TRANSPARENT);
                shadowPaint.setShadowLayer(dp(6), 0, dp(3), Color.argb(64, 0, 0, 0));
                canvas.drawRoundRect(rect, dp(6), dp(6), shadowPaint);

                // 2. Border (Layer 3)
                Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                borderPaint.setStyle(Paint.Style.STROKE);
                if (isFocused) {
                    borderPaint.setStrokeWidth(dp(2));
                    borderPaint.setColor(Color.argb(179, 255, 255, 255)); // 70% focus border
                } else {
                    borderPaint.setStrokeWidth(dp(1.2f));
                    borderPaint.setColor(Color.argb(38, 255, 255, 255)); // 15% normal border
                }
                canvas.drawRoundRect(rect, dp(6), dp(6), borderPaint);

                // 3. Specular top-left corner reflection
                if (!isPressed) {
                    Paint specPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    specPaint.setStyle(Paint.Style.STROKE);
                    specPaint.setStrokeWidth(dp(1.2f));
                    specPaint.setColor(Color.argb(isFocused ? 204 : 102, 255, 255, 255)); // 80% vs 40%

                    Path specPath = new Path();
                    float cornerR = dp(6);
                    specPath.moveTo(rect.left, rect.bottom - cornerR);
                    specPath.lineTo(rect.left, rect.top + cornerR);
                    specPath.quadTo(rect.left, rect.top, rect.left + cornerR, rect.top);
                    specPath.lineTo(rect.right - cornerR, rect.top);
                    canvas.drawPath(specPath, specPaint);
                }

                // Draw App Icon
                if (app.icon != null) {
                    int iconSize = (int) dp(32);
                    int iconLeft = (int) (rect.centerX() - iconSize / 2f);
                    int iconTop = (int) (rect.top + dp(6));
                    if (isPressed) {
                        float factor = buttonScale;
                        int newSize = (int) (iconSize * factor);
                        int offset = (iconSize - newSize) / 2;
                        app.icon.setBounds(iconLeft + offset, iconTop + offset,
                                iconLeft + offset + newSize, iconTop + offset + newSize);
                    } else {
                        app.icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize);
                    }
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
                    float insetX = rect.width() * (1.0f - buttonScale) / 2f;
                    float insetY = rect.height() * (1.0f - buttonScale) / 2f;
                    rect.inset(insetX, insetY);
                }

                // 1. Border
                Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                borderPaint.setStyle(Paint.Style.STROKE);
                if (isFocused) {
                    borderPaint.setStrokeWidth(dp(1.8f));
                    borderPaint.setColor(Color.argb(179, 255, 255, 255)); // 70% white
                    textP.setColor(0xFFFFFFFF);
                } else {
                    borderPaint.setStrokeWidth(dp(1.0f));
                    borderPaint.setColor(Color.argb(38, 255, 255, 255)); // 15% white
                    textP.setColor(0xFFF5F5F5);
                }
                canvas.drawRoundRect(rect, dp(6), dp(6), borderPaint);

                // 2. Specular top-left reflection
                if (!isPressed) {
                    Paint specPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    specPaint.setStyle(Paint.Style.STROKE);
                    specPaint.setStrokeWidth(dp(1.2f));
                    specPaint.setColor(Color.argb(isFocused ? 204 : 102, 255, 255, 255));

                    Path specPath = new Path();
                    float cornerR = dp(6);
                    specPath.moveTo(rect.left, rect.bottom - cornerR);
                    specPath.lineTo(rect.left, rect.top + cornerR);
                    specPath.quadTo(rect.left, rect.top, rect.left + cornerR, rect.top);
                    specPath.lineTo(rect.right - cornerR, rect.top);
                    canvas.drawPath(specPath, specPaint);
                }

                float textY = rect.centerY() - ((textP.descent() + textP.ascent()) / 2f);
                canvas.drawText(templates[index], rect.centerX(), textY, textP);
            }
        }
    }
}