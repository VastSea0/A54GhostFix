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
    private static final long SINGLE_KEY_LONG_PRESS_MS = 850;
    private static final long EXIT_CHORD_MS = 1800;
    private static final float SHAKE_THRESHOLD = 19.5f;

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
    private boolean chordActive;
    private boolean chordHandled;
    private boolean upLongHandled;
    private boolean downLongHandled;
    private int selectedIndex;
    private boolean isInVirtualMenu = false;
    private int virtualSelectedIndex = 0;

    private final Runnable refreshNodesRunnable = this::refreshKeypadNodes;
    private final Runnable upLongRunnable = () -> {
        if (!volumeUpHeld || volumeDownHeld || !isEmergencyEnabled()) {
            return;
        }
        if (currentMode() == GhostFixPreferences.MODE_KEYPAD) {
            upLongHandled = true;
            performGlobalAction(GLOBAL_ACTION_BACK);
            vibrate(45);
        }
    };
    private final Runnable downLongRunnable = () -> {
        if (!volumeDownHeld || volumeUpHeld || !isEmergencyEnabled()) {
            return;
        }
        downLongHandled = true;
        performGlobalAction(GLOBAL_ACTION_HOME);
        vibrate(60);
    };
    private final Runnable chordLongRunnable = () -> {
        if (!volumeUpHeld || !volumeDownHeld || !isEmergencyEnabled()) {
            return;
        }
        chordHandled = true;
        GhostFixPreferences.setEmergencyEnabled(this, false);
        vibrate(120);
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

    @Override
    public void onVirtualDockTap(int index) {
        executeVirtualAction(index);
    }

    private void executeVirtualAction(int index) {
        switch (index) {
            case 0: // Geri
                performGlobalAction(GLOBAL_ACTION_BACK);
                vibrate(45);
                break;
            case 1: // Ana Ekran
                performGlobalAction(GLOBAL_ACTION_HOME);
                vibrate(60);
                break;
            case 2: // Sonlar (Recents)
                performGlobalAction(GLOBAL_ACTION_RECENTS);
                vibrate(50);
                break;
            case 3: // Bildirimler
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
                vibrate(45);
                break;
            case 4: // Kapat
                GhostFixPreferences.setEmergencyEnabled(this, false);
                vibrate(120);
                break;
        }
    }

    private void handleEmergencyKeyDown(int keyCode) {
        Log.d(TAG, "key down: " + KeyEvent.keyCodeToString(keyCode));
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            volumeUpHeld = true;
            upLongHandled = false;
        } else {
            volumeDownHeld = true;
            downLongHandled = false;
        }

        if (volumeUpHeld && volumeDownHeld) {
            chordActive = true;
            chordHandled = false;
            handler.removeCallbacks(upLongRunnable);
            handler.removeCallbacks(downLongRunnable);
            handler.postDelayed(chordLongRunnable, EXIT_CHORD_MS);
            updateGuardState(false);
            return;
        }

        if (currentMode() == GhostFixPreferences.MODE_GUARDED_TOUCH) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                updateGuardState(true);
            } else {
                handler.postDelayed(downLongRunnable, SINGLE_KEY_LONG_PRESS_MS);
            }
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            handler.postDelayed(upLongRunnable, SINGLE_KEY_LONG_PRESS_MS);
        } else {
            handler.postDelayed(downLongRunnable, SINGLE_KEY_LONG_PRESS_MS);
        }
    }

    private void handleEmergencyKeyUp(int keyCode) {
        Log.d(TAG, "key up: " + KeyEvent.keyCodeToString(keyCode));
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            volumeUpHeld = false;
            handler.removeCallbacks(upLongRunnable);
            updateGuardState(false);
        } else {
            volumeDownHeld = false;
            handler.removeCallbacks(downLongRunnable);
        }

        if (chordActive) {
            if (!volumeUpHeld && !volumeDownHeld) {
                handler.removeCallbacks(chordLongRunnable);
                if (!chordHandled) {
                    if (currentMode() == GhostFixPreferences.MODE_KEYPAD) {
                        activateSelectedNode();
                    } else {
                        GhostFixPreferences.setEmergencyMode(
                                this,
                                GhostFixPreferences.MODE_KEYPAD
                        );
                    }
                }
                chordActive = false;
                chordHandled = false;
                upLongHandled = false;
                downLongHandled = false;
            }
            return;
        }

        int mode = currentMode();
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (mode == GhostFixPreferences.MODE_KEYPAD && !upLongHandled) {
                navigateSelection(-1);
            }
            upLongHandled = false;
        } else {
            if (mode == GhostFixPreferences.MODE_KEYPAD && !downLongHandled) {
                navigateSelection(1);
            } else if (mode == GhostFixPreferences.MODE_GUARDED_TOUCH && !downLongHandled) {
                performGlobalAction(GLOBAL_ACTION_BACK);
                vibrate(45);
            }
            downLongHandled = false;
        }
    }

    private void updateEmergencyState() {
        isInVirtualMenu = false;
        virtualSelectedIndex = 0;
        if (!isEmergencyEnabled()) {
            hideEmergencyOverlay();
            recycleKeypadNodes();
            return;
        }

        showEmergencyOverlay();
        overlayView.setVirtualSelection(isInVirtualMenu, virtualSelectedIndex);
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
        if (isInVirtualMenu) {
            int candidate = virtualSelectedIndex + direction;
            if (candidate < 0) {
                // Exit virtual menu to the bottom of the node list
                if (keypadNodes.isEmpty()) {
                    virtualSelectedIndex = 4;
                } else {
                    isInVirtualMenu = false;
                    selectedIndex = keypadNodes.size() - 1;
                    updateSelectedNode();
                }
            } else if (candidate >= 5) {
                // Exit virtual menu to the top of the node list
                if (keypadNodes.isEmpty()) {
                    virtualSelectedIndex = 0;
                } else {
                    isInVirtualMenu = false;
                    selectedIndex = 0;
                    updateSelectedNode();
                }
            } else {
                virtualSelectedIndex = candidate;
                updateSelectedNode();
            }
            vibrate(25);
            return;
        }

        // Screen node navigation
        if (keypadNodes.isEmpty()) {
            refreshKeypadNodes();
            if (keypadNodes.isEmpty()) {
                // Enter virtual menu since there are no screen nodes
                isInVirtualMenu = true;
                virtualSelectedIndex = 0;
                updateSelectedNode();
                vibrate(25);
                return;
            }
        }

        int candidate = selectedIndex + direction;
        if (candidate < 0) {
            // Enter virtual menu at the last option (Exit, index 4)
            isInVirtualMenu = true;
            virtualSelectedIndex = 4;
            updateSelectedNode();
            vibrate(25);
            return;
        } else if (candidate >= keypadNodes.size()) {
            if (scrollScreen(direction > 0)) {
                selectedIndex = direction > 0 ? 0 : Math.max(0, keypadNodes.size() - 1);
                scheduleNodeRefresh(280);
                vibrate(25);
                return;
            }
            // If cannot scroll, enter virtual menu at first option (Back, index 0)
            isInVirtualMenu = true;
            virtualSelectedIndex = 0;
            updateSelectedNode();
            vibrate(25);
            return;
        }

        selectedIndex = candidate;
        updateSelectedNode();
        vibrate(25);
    }

    private void activateSelectedNode() {
        if (isInVirtualMenu) {
            executeVirtualAction(virtualSelectedIndex);
            return;
        }
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
            if (!isInVirtualMenu) {
                overlayView.clearSelection();
            }
            return;
        }
        selectedIndex = Math.max(0, Math.min(previousIndex, keypadNodes.size() - 1));
        updateSelectedNode();
    }

    private void updateSelectedNode() {
        if (overlayView == null) {
            return;
        }

        overlayView.setVirtualSelection(isInVirtualMenu, virtualSelectedIndex);

        if (isInVirtualMenu) {
            overlayView.clearSelection();
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
        chordActive = false;
        chordHandled = false;
        upLongHandled = false;
        downLongHandled = false;
        handler.removeCallbacks(upLongRunnable);
        handler.removeCallbacks(downLongRunnable);
        handler.removeCallbacks(chordLongRunnable);
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
