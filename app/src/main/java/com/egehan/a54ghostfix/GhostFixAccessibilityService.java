package com.egehan.a54ghostfix;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Vibrator;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class GhostFixAccessibilityService extends AccessibilityService
        implements SensorEventListener, EmergencyOverlayView.Listener {
    private static final String TAG = "A54EmergencyControl";
    private static final long TRIGGER_DEBOUNCE_MS = 3500;
    private static final long ACTION_MENU_TRIGGER_MS = 800;
    private static final float SHAKE_THRESHOLD = 19.5f;

    public static final int ACTION_BACK = 0;
    public static final int ACTION_HOME = 1;
    public static final int ACTION_RECENTS = 2;
    public static final int ACTION_NOTIFICATIONS = 3;
    public static final int ACTION_QUICK_SETTINGS = 4;
    public static final int ACTION_DISABLE = 5;
    public static final int ACTION_COUNT = 6;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<AccessibilityNodeInfo> keypadNodes = new ArrayList<>();

    private SensorManager sensorManager;
    private Sensor accelerometer;
    private SharedPreferences preferences;
    private WindowManager windowManager;
    private EmergencyOverlayView overlayView;
    private long lastTriggerAt;
    private boolean listeningForShake;
    private boolean volumeUpHeld;
    private boolean volumeDownHeld;
    private boolean isActionMenuOpen;
    private int actionMenuSelectedIndex;
    private int selectedIndex;

    private static final long CHORD_WINDOW_MS = 80;
    private boolean chordStarted;
    private boolean actionMenuOpenedThisChord;
    private Runnable latestNavRunnable;

    private final Runnable refreshNodesRunnable = this::refreshKeypadNodes;
    private final Runnable actionMenuTriggerRunnable = () -> {
        if (!volumeUpHeld || !volumeDownHeld || !isEmergencyEnabled()) {
            return;
        }
        isActionMenuOpen = true;
        actionMenuSelectedIndex = 0;
        actionMenuOpenedThisChord = true;
        vibrate(80);
        if (overlayView != null) {
            overlayView.setActionMenuState(true, actionMenuSelectedIndex);
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener =
            (sharedPreferences, key) -> handler.post(() -> {
                if (GhostFixPreferences.KEY_SHAKE.equals(key)) {
                    refreshSensors();
                }
                if (GhostFixPreferences.KEY_EMERGENCY_ENABLED.equals(key)
                        || GhostFixPreferences.KEY_EMERGENCY_MODE.equals(key)) {
                    updateEmergencyState();
                }
            });

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        AccessibilityServiceInfo info = getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);

        preferences = GhostFixPreferences.prefs(this);
        preferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        refreshSensors();
        updateEmergencyState();
    }

    @Override
    public void onDestroy() {
        if (preferences != null) {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
        }
        unregisterShake();
        hideEmergencyOverlay();
        recycleKeypadNodes();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isEmergencyEnabled()
                || currentMode() != GhostFixPreferences.MODE_KEYPAD
                || event == null) {
            return;
        }

        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            scheduleNodeRefresh(180);
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        boolean volumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN;
        if (!volumeKey) {
            return false;
        }

        if (!isEmergencyEnabled()) {
            if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) {
                return false;
            }
            boolean shouldRun =
                    (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                            && GhostFixPreferences.volumeUpEnabled(this))
                            || (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                            && GhostFixPreferences.volumeDownEnabled(this));
            if (shouldRun) {
                triggerFix();
            }
            return shouldRun;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                handleEmergencyKeyDown(keyCode);
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_UP) {
            handleEmergencyKeyUp(keyCode);
            return true;
        }
        return true;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!GhostFixPreferences.shakeEnabled(this)
                || event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) {
            return;
        }

        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        double force = Math.sqrt(x * x + y * y + z * z);
        if (force >= SHAKE_THRESHOLD) {
            triggerFix();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onTrustedTap(float x, float y) {
        List<AccessibilityNodeInfo> nodes = collectCurrentNodes(false);
        AccessibilityNodeInfo target = null;
        long smallestArea = Long.MAX_VALUE;
        Rect bounds = new Rect();
        for (AccessibilityNodeInfo node : nodes) {
            node.getBoundsInScreen(bounds);
            long area = (long) bounds.width() * bounds.height();
            if (bounds.contains((int) x, (int) y)
                    && isTapTarget(node)
                    && area > 0
                    && area < smallestArea) {
                target = node;
                smallestArea = area;
            }
        }

        boolean clicked = target != null && performClick(target);
        recycleNodes(nodes);
        vibrate(clicked ? 45 : 20);
    }

    @Override
    public void onTrustedSwipe(float startX, float startY, float endX, float endY) {
        List<AccessibilityNodeInfo> nodes = collectCurrentNodes(true);
        AccessibilityNodeInfo target = null;
        long smallestArea = Long.MAX_VALUE;
        Rect bounds = new Rect();
        for (AccessibilityNodeInfo node : nodes) {
            node.getBoundsInScreen(bounds);
            long area = (long) bounds.width() * bounds.height();
            if (node.isScrollable()
                    && bounds.contains((int) startX, (int) startY)
                    && area > 0
                    && area < smallestArea) {
                target = node;
                smallestArea = area;
            }
        }

        float deltaX = endX - startX;
        float deltaY = endY - startY;
        boolean forward = Math.abs(deltaY) >= Math.abs(deltaX)
                ? deltaY < 0
                : deltaX < 0;
        boolean scrolled = target != null && target.performAction(forward
                ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        recycleNodes(nodes);
        vibrate(scrolled ? 45 : 20);
    }

    private void handleEmergencyKeyDown(int keyCode) {
        Log.d(TAG, "key down: " + KeyEvent.keyCodeToString(keyCode));

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            volumeUpHeld = true;
        } else {
            volumeDownHeld = true;
        }

        if (volumeUpHeld && volumeDownHeld) {
            chordStarted = true;
            if (latestNavRunnable != null) {
                handler.removeCallbacks(latestNavRunnable);
                latestNavRunnable = null;
            }
            
            if (!isActionMenuOpen) {
                handler.postDelayed(actionMenuTriggerRunnable, ACTION_MENU_TRIGGER_MS);
            }
            return;
        }

        chordStarted = false;
        actionMenuOpenedThisChord = false;
        handler.removeCallbacks(actionMenuTriggerRunnable);

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            latestNavRunnable = () -> {
                if (isActionMenuOpen) {
                    navigateActionMenu(-1);
                } else if (currentMode() == GhostFixPreferences.MODE_GUARDED_TOUCH) {
                    updateGuardState(true);
                } else {
                    navigateSelection(-1);
                }
            };
            handler.postDelayed(latestNavRunnable, CHORD_WINDOW_MS);
        } else {
            latestNavRunnable = () -> {
                if (isActionMenuOpen) {
                    navigateActionMenu(1);
                } else {
                    navigateSelection(1);
                }
            };
            handler.postDelayed(latestNavRunnable, CHORD_WINDOW_MS);
        }
    }

    private void handleEmergencyKeyUp(int keyCode) {
        Log.d(TAG, "key up: " + KeyEvent.keyCodeToString(keyCode));
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            volumeUpHeld = false;
            if (currentMode() == GhostFixPreferences.MODE_GUARDED_TOUCH) {
                updateGuardState(false);
            }
        } else {
            volumeDownHeld = false;
        }

        handler.removeCallbacks(actionMenuTriggerRunnable);

        if (chordStarted) {
            if (!volumeUpHeld || !volumeDownHeld) {
                if (!actionMenuOpenedThisChord) {
                    if (isActionMenuOpen) {
                        executeActionMenuSelection();
                        closeActionMenu();
                    } else {
                        activateSelectedNode();
                    }
                }
                chordStarted = false;
            }
        }
    }

    private void navigateActionMenu(int direction) {
        actionMenuSelectedIndex += direction;
        if (actionMenuSelectedIndex < 0) {
            actionMenuSelectedIndex = ACTION_COUNT - 1;
        } else if (actionMenuSelectedIndex >= ACTION_COUNT) {
            actionMenuSelectedIndex = 0;
        }
        vibrate(25);
        if (overlayView != null) {
            overlayView.setActionMenuState(true, actionMenuSelectedIndex);
        }
    }

    private void executeActionMenuSelection() {
        switch (actionMenuSelectedIndex) {
            case ACTION_BACK:
                performGlobalAction(GLOBAL_ACTION_BACK);
                break;
            case ACTION_HOME:
                performGlobalAction(GLOBAL_ACTION_HOME);
                break;
            case ACTION_RECENTS:
                performGlobalAction(GLOBAL_ACTION_RECENTS);
                break;
            case ACTION_NOTIFICATIONS:
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
                break;
            case ACTION_QUICK_SETTINGS:
                performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
                break;
            case ACTION_DISABLE:
                GhostFixPreferences.setEmergencyEnabled(this, false);
                break;
        }
        vibrate(50);
    }

    private void closeActionMenu() {
        isActionMenuOpen = false;
        if (overlayView != null) {
            overlayView.setActionMenuState(false, 0);
        }
    }

    private void updateEmergencyState() {
        if (!isEmergencyEnabled()) {
            hideEmergencyOverlay();
            recycleKeypadNodes();
            return;
        }

        showEmergencyOverlay();
        int mode = currentMode();
        if (mode == GhostFixPreferences.MODE_GUARDED_TOUCH) {
            overlayView.setMode(mode, getString(R.string.overlay_touch_locked));
            overlayView.clearSelection();
        } else {
            overlayView.setMode(mode, getString(R.string.overlay_keypad_mode));
            scheduleNodeRefresh(80);
        }
    }

    private void showEmergencyOverlay() {
        if (overlayView != null || windowManager == null) {
            return;
        }

        overlayView = new EmergencyOverlayView(this, this);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.setFitInsetsTypes(0);
            params.setFitInsetsSides(0);
            params.setFitInsetsIgnoringVisibility(true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        params.setTitle("A54 Ghost Fix Emergency Shield");
        windowManager.addView(overlayView, params);
    }

    private void hideEmergencyOverlay() {
        if (overlayView == null || windowManager == null) {
            return;
        }
        windowManager.removeView(overlayView);
        overlayView = null;
        resetKeyState();
    }

    private void updateGuardState(boolean armed) {
        if (overlayView == null
                || currentMode() != GhostFixPreferences.MODE_GUARDED_TOUCH) {
            return;
        }
        overlayView.setGuardHeld(
                armed,
                getString(armed ? R.string.overlay_touch_armed : R.string.overlay_touch_locked)
        );
    }

    private void navigateSelection(int direction) {
        if (keypadNodes.isEmpty()) {
            refreshKeypadNodes();
            if (keypadNodes.isEmpty()) {
                return;
            }
        }

        int candidate = selectedIndex + direction;
        if (candidate < 0 || candidate >= keypadNodes.size()) {
            if (scrollScreen(direction > 0)) {
                selectedIndex = direction > 0 ? 0 : Math.max(0, keypadNodes.size() - 1);
                scheduleNodeRefresh(280);
                vibrate(25);
                return;
            }
            candidate = direction > 0 ? 0 : keypadNodes.size() - 1;
        }
        selectedIndex = candidate;
        updateSelectedNode();
        vibrate(25);
    }

    private void activateSelectedNode() {
        if (keypadNodes.isEmpty() || selectedIndex >= keypadNodes.size()) {
            refreshKeypadNodes();
            return;
        }
        boolean clicked = performClick(keypadNodes.get(selectedIndex));
        vibrate(clicked ? 55 : 20);
        if (clicked) {
            scheduleNodeRefresh(250);
        }
    }

    private void refreshKeypadNodes() {
        if (!isEmergencyEnabled()
                || currentMode() != GhostFixPreferences.MODE_KEYPAD
                || overlayView == null) {
            return;
        }

        int previousIndex = selectedIndex;
        recycleKeypadNodes();
        keypadNodes.addAll(collectCurrentNodes(false));
        keypadNodes.removeIf(node -> !isKeypadTarget(node));
        keypadNodes.sort(Comparator.comparingInt((AccessibilityNodeInfo node) -> {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            return bounds.top;
        }).thenComparingInt(node -> {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            return bounds.left;
        }));

        if (keypadNodes.isEmpty()) {
            selectedIndex = 0;
            overlayView.clearSelection();
            return;
        }
        selectedIndex = Math.max(0, Math.min(previousIndex, keypadNodes.size() - 1));
        updateSelectedNode();
    }

    private void updateSelectedNode() {
        if (overlayView == null) {
            return;
        }

        if (keypadNodes.isEmpty() || selectedIndex >= keypadNodes.size()) {
            return;
        }
        AccessibilityNodeInfo node = keypadNodes.get(selectedIndex);
        node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        overlayView.setSelection(bounds, nodeLabel(node));
    }

    private List<AccessibilityNodeInfo> collectCurrentNodes(boolean scrollablesOnly) {
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return result;
        }
        collectNodes(root, result, scrollablesOnly);
        root.recycle();
        return result;
    }

    private void collectNodes(
            AccessibilityNodeInfo node,
            List<AccessibilityNodeInfo> result,
            boolean scrollablesOnly
    ) {
        if (node.isVisibleToUser()
                && (!scrollablesOnly || node.isScrollable())) {
            result.add(AccessibilityNodeInfo.obtain(node));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) {
                continue;
            }
            collectNodes(child, result, scrollablesOnly);
            child.recycle();
        }
    }

    private boolean scrollScreen(boolean forward) {
        List<AccessibilityNodeInfo> nodes = collectCurrentNodes(true);
        AccessibilityNodeInfo target = null;
        long largestArea = -1;
        Rect bounds = new Rect();
        for (AccessibilityNodeInfo node : nodes) {
            node.getBoundsInScreen(bounds);
            long area = (long) bounds.width() * bounds.height();
            if (node.isScrollable() && area > largestArea) {
                target = node;
                largestArea = area;
            }
        }
        boolean scrolled = target != null && target.performAction(forward
                ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        recycleNodes(nodes);
        return scrolled;
    }

    private boolean performClick(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
        while (current != null) {
            if (current.isEditable()) {
                current.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            }
            if (current.isClickable()
                    && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                current.recycle();
                return true;
            }
            AccessibilityNodeInfo parent = current.getParent();
            current.recycle();
            current = parent;
        }
        return false;
    }

    private boolean isTapTarget(AccessibilityNodeInfo node) {
        return node.isClickable() || node.isEditable();
    }

    private boolean isKeypadTarget(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return node.isVisibleToUser()
                && !bounds.isEmpty()
                && (node.isClickable() || node.isEditable() || node.isScrollable());
    }

    private String nodeLabel(AccessibilityNodeInfo node) {
        CharSequence text = node.getText();
        if (text == null || text.length() == 0) {
            text = node.getContentDescription();
        }
        if (text == null || text.length() == 0) {
            text = node.getViewIdResourceName();
        }
        return text == null ? getString(R.string.overlay_selected_item) : text.toString();
    }

    private void scheduleNodeRefresh(long delayMs) {
        handler.removeCallbacks(refreshNodesRunnable);
        handler.postDelayed(refreshNodesRunnable, delayMs);
    }

    private void triggerFix() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastTriggerAt < TRIGGER_DEBOUNCE_MS) {
            return;
        }
        lastTriggerAt = now;
        vibrate(60);
        GhostFixer.run(this, null);
    }

    private void refreshSensors() {
        if (GhostFixPreferences.shakeEnabled(this)) {
            registerShake();
        } else {
            unregisterShake();
        }
    }

    private void registerShake() {
        if (listeningForShake || sensorManager == null || accelerometer == null) {
            return;
        }
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        listeningForShake = true;
    }

    private void unregisterShake() {
        if (!listeningForShake || sensorManager == null) {
            return;
        }
        sensorManager.unregisterListener(this);
        listeningForShake = false;
    }

    private int currentMode() {
        return GhostFixPreferences.emergencyMode(this);
    }

    private boolean isEmergencyEnabled() {
        return GhostFixPreferences.emergencyEnabled(this);
    }

    private void resetKeyState() {
        volumeUpHeld = false;
        volumeDownHeld = false;
        isActionMenuOpen = false;
        handler.removeCallbacks(actionMenuTriggerRunnable);
    }

    private void recycleKeypadNodes() {
        recycleNodes(keypadNodes);
        keypadNodes.clear();
    }

    private void recycleNodes(List<AccessibilityNodeInfo> nodes) {
        for (AccessibilityNodeInfo node : nodes) {
            node.recycle();
        }
    }

    @SuppressWarnings("deprecation")
    private void vibrate(long durationMs) {
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(durationMs);
        }
    }
}
