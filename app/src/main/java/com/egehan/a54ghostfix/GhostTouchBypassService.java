package com.egehan.a54ghostfix;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Vibrator;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class GhostTouchBypassService extends AccessibilityService
        implements EmergencyOverlayView.Listener, SensorEventListener {
    private static final String TAG = "GhostTouchBypass";
    private static final long FIX_TRIGGER_DEBOUNCE_MS = 3500;
    private static final float SHAKE_THRESHOLD = 19.5f;
    private static GhostTouchBypassService activeInstance;

    static boolean openEmergencyLauncherIfRunning() {
        GhostTouchBypassService service = activeInstance;
        if (service == null) {
            return false;
        }
        service.handler.postDelayed(service::openLauncherTray, 350);
        return true;
    }

    enum AppAction {
        LAUNCH,
        SWIPE_UP,
        SWIPE_DOWN,
        SWIPE_LEFT,
        SWIPE_RIGHT
    }

    enum SwipeDirection {
        UP,
        DOWN,
        LEFT,
        RIGHT
    }

    public static class AppInfo {
        public String name;
        public String packageName;
        public ComponentName componentName;
        public Drawable icon;
        public String symbol;
        public AppAction action;

        private AppInfo(
                String name,
                String packageName,
                ComponentName componentName,
                Drawable icon,
                String symbol,
                AppAction action
        ) {
            this.name = name;
            this.packageName = packageName;
            this.componentName = componentName;
            this.icon = icon;
            this.symbol = symbol;
            this.action = action;
        }

        static AppInfo app(String name, ComponentName componentName, Drawable icon) {
            return new AppInfo(
                    name,
                    componentName.getPackageName(),
                    componentName,
                    icon,
                    "",
                    AppAction.LAUNCH
            );
        }

        static AppInfo gesture(String name, String symbol, AppAction action) {
            return new AppInfo(name, "", null, null, symbol, action);
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<AccessibilityNodeInfo> keypadNodes = new ArrayList<>();
    private final List<AppInfo> installedApps = new ArrayList<>();
    private final List<AccessibilityNodeInfo> bottomNavNodes = new ArrayList<>();
    private int selectedIndex = 0;

    private SharedPreferences preferences;
    private WindowManager windowManager;
    private EmergencyOverlayView overlayView;
    private SensorManager sensorManager;
    private Sensor accelerometer;
    private long lastFixTriggerAt;
    private boolean listeningForShake;

    // State flags
    private boolean isEditing = false;
    private boolean isTemplateMenuOpen = false;
    private boolean isTrayFocused = false;
    private int selectedTrayIndex = -1;
    private String activePackageName = "";
    private boolean isBottomNavFocused = false;
    private boolean useVirtualInstagramNav;
    private long keepLauncherFocusedUntil;

    private String[] templates = new String[0];

    // Key states
    private boolean isVolumeUpHeld = false;
    private boolean isVolumeDownHeld = false;
    private long volumeUpPressedTime = 0;
    private long volumeDownPressedTime = 0;
    private boolean chordActive = false;
    private boolean chordLongPressed = false;
    private long lastVolumeUpUpTime = 0;
    private long lastVolumeDownUpTime = 0;
    private boolean overlayTemporarilyPassthrough;

    // Timeouts
    private static final long LONG_PRESS_TIMEOUT_MS = 1000; // 1s for Back/Home
    private static final long CHORD_LONG_PRESS_TIMEOUT_MS = 2000; // 2s to open Launcher
    private static final long EXIT_LONG_PRESS_TIMEOUT_MS = 3000; // 3s to disable service
    private static final long DOUBLE_CLICK_WINDOW_MS = 300;
    private static final int INSTAGRAM_NAV_COUNT = 5;

    // Runnables
    private final Runnable volumeUpLongPressRunnable = () -> {
        if (currentMode() == GhostFixPreferences.MODE_GUARDED_TOUCH) {
            return;
        }
        Log.d(TAG, "Volume Up Long Press -> HOME");
        vibrate(100);
        performGlobalAction(GLOBAL_ACTION_HOME);
    };

    private final Runnable volumeDownLongPressRunnable = () -> {
        if (isTemplateMenuOpen) {
            isTemplateMenuOpen = false;
            if (overlayView != null) {
                overlayView.setTemplatesOpen(false, 0);
            }
            refreshKeypadNodes();
            vibrate(80);
            return;
        }
        boolean guarded = currentMode() == GhostFixPreferences.MODE_GUARDED_TOUCH;
        Log.d(TAG, guarded
                ? "Volume Down Long Press -> HOME"
                : "Volume Down Long Press -> BACK");
        vibrate(100);
        performGlobalAction(guarded ? GLOBAL_ACTION_HOME : GLOBAL_ACTION_BACK);
    };

    private final Runnable chordLongPressRunnable = () -> {
        Log.d(TAG, "Volume Up + Down Long Press -> OPEN LAUNCHER");
        vibrate(150);
        chordLongPressed = true;

        if (currentMode() != GhostFixPreferences.MODE_KEYPAD) {
            GhostFixPreferences.setEmergencyMode(this, GhostFixPreferences.MODE_KEYPAD);
        }
        openLauncherTray();
    };

    private final Runnable chordExitRunnable = () -> {
        Log.d(TAG, "Volume Up + Down held for 3s -> EXIT EMERGENCY CONTROL");
        vibrate(300);
        chordLongPressed = true;
        GhostFixPreferences.setEmergencyEnabled(GhostTouchBypassService.this, false);
    };

    private final Runnable repeatingBackspaceRunnable = new Runnable() {
        @Override
        public void run() {
            performBackspace();
            vibrate(15);
            handler.postDelayed(this, 100);
        }
    };

    private final Runnable pendingVolumeUpSingleRunnable = () -> navigateSelection(-1);
    private final Runnable pendingVolumeDownSingleRunnable = () -> navigateSelection(1);
    private final Runnable refreshNodesRunnable = this::refreshKeypadNodes;

    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener =
            (sharedPreferences, key) -> handler.post(() -> {
                if (GhostFixPreferences.KEY_EMERGENCY_ENABLED.equals(key)
                        || GhostFixPreferences.KEY_EMERGENCY_MODE.equals(key)) {
                    updateEmergencyState();
                }
                if (GhostFixPreferences.KEY_LAUNCHER_REQUEST.equals(key)) {
                    handler.postDelayed(this::openPendingLauncherRequest, 500);
                }
                if (GhostFixPreferences.KEY_SHAKE.equals(key)) {
                    refreshShakeListener();
                }
            });

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "onServiceConnected: Service connected");
        activeInstance = this;

        AccessibilityServiceInfo info = getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);

        preferences = GhostFixPreferences.prefs(this);
        preferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener);

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        templates = getResources().getStringArray(R.array.overlay_quick_message_values);

        loadInstalledApps();
        refreshShakeListener();
        updateEmergencyState();
        handler.postDelayed(this::openPendingLauncherRequest, 650);
    }

    private void loadInstalledApps() {
        installedApps.clear();
        PackageManager pm = getPackageManager();

        String[] gestureNames = getResources().getStringArray(R.array.overlay_gesture_names);
        installedApps.add(AppInfo.gesture(gestureNames[0], "↑", AppAction.SWIPE_UP));
        installedApps.add(AppInfo.gesture(gestureNames[1], "↓", AppAction.SWIPE_DOWN));
        installedApps.add(AppInfo.gesture(gestureNames[2], "←", AppAction.SWIPE_LEFT));
        installedApps.add(AppInfo.gesture(gestureNames[3], "→", AppAction.SWIPE_RIGHT));

        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> activities = pm.queryIntentActivities(
                launcherIntent,
                0
        );

        Map<String, AppInfo> appsByPackage = new LinkedHashMap<>();
        for (ResolveInfo activity : activities) {
            if (activity.activityInfo == null
                    || getPackageName().equals(activity.activityInfo.packageName)
                    || !activity.activityInfo.enabled
                    || !activity.activityInfo.applicationInfo.enabled
                    || !activity.activityInfo.exported) {
                continue;
            }
            ComponentName component = new ComponentName(
                    activity.activityInfo.packageName,
                    activity.activityInfo.name
            );
            String name = String.valueOf(activity.loadLabel(pm));
            Drawable icon = activity.loadIcon(pm);
            appsByPackage.putIfAbsent(
                    component.getPackageName(),
                    AppInfo.app(name, component, icon)
            );
        }

        List<AppInfo> apps = new ArrayList<>(appsByPackage.values());
        apps.sort(Comparator
                .comparingInt((AppInfo app) -> appPriority(app.packageName))
                .thenComparing(app -> app.name.toLowerCase(Locale.ROOT)));
        installedApps.addAll(apps);
        Log.d(TAG, "Loaded " + apps.size() + " launchable apps.");
    }

    private int appPriority(String packageName) {
        String[] preferred = {
                "com.samsung.android.dialer",
                "com.whatsapp",
                "com.google.android.apps.messaging",
                "com.sec.android.app.camera",
                "com.android.settings",
                "com.android.chrome",
                "com.instagram.android",
                "com.google.android.apps.maps"
        };
        for (int i = 0; i < preferred.length; i++) {
            if (preferred[i].equals(packageName)) {
                return i;
            }
        }
        return preferred.length;
    }

    @Override
    public void onDestroy() {
        if (activeInstance == this) {
            activeInstance = null;
        }
        if (preferences != null) {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
        }
        unregisterShakeListener();
        handler.removeCallbacks(repeatingBackspaceRunnable);
        hideEmergencyOverlay();
        recycleKeypadNodes();
        recycleNodes(bottomNavNodes);
        bottomNavNodes.clear();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isEmergencyEnabled() || currentMode() != GhostFixPreferences.MODE_KEYPAD || event == null) {
            return;
        }

        if (event.getPackageName() != null) {
            String eventPackage = event.getPackageName().toString();
            if (getPackageName().equals(eventPackage)) {
                return;
            }
            activePackageName = eventPackage;
        }

        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (SystemClock.uptimeMillis() < keepLauncherFocusedUntil && isTrayFocused) {
                scheduleNodeRefresh(180);
                return;
            }
            isTrayFocused = false;
            selectedTrayIndex = -1;
            selectedIndex = 0;
            isTemplateMenuOpen = false;
            isBottomNavFocused = false;
            useVirtualInstagramNav = false;
            recycleNodes(bottomNavNodes);
            bottomNavNodes.clear();
            if (overlayView != null) {
                overlayView.setTemplatesOpen(false, 0);
            }
            scheduleNodeRefresh(180);
        } else if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || type == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            scheduleNodeRefresh(180);
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "onInterrupt called");
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
                    && (node.isClickable() || node.isEditable())
                    && area > 0
                    && area < smallestArea) {
                target = node;
                smallestArea = area;
            }
        }

        boolean clicked = target != null && performClick(target);
        recycleNodes(nodes);
        if (clicked) {
            vibrate(45);
        } else {
            dispatchTap(x, y);
        }
    }

    @Override
    public void onTrustedSwipe(float startX, float startY, float endX, float endY) {
        dispatchGesturePath(startX, startY, endX, endY, 280);
    }

    @Override
    public void onTrayExpansionChanged(boolean expanded) {
        // The panel is intentionally opaque/translucent. No fake blur is applied.
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (!isEmergencyEnabled()) {
            return handleFixTriggerKey(event);
        }

        int keyCode = event.getKeyCode();
        int action = event.getAction();
        boolean isVolumeKey = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN;

        if (!isVolumeKey) {
            return false;
        }

        if (action == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() > 0) {
                return true;
            }
            handleKeyDown(keyCode);
        } else if (action == KeyEvent.ACTION_UP) {
            handleKeyUp(keyCode);
        }

        return true;
    }

    private boolean handleFixTriggerKey(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) {
            return false;
        }

        int keyCode = event.getKeyCode();
        boolean shouldRun =
                (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                        && GhostFixPreferences.volumeUpEnabled(this))
                        || (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                        && GhostFixPreferences.volumeDownEnabled(this));
        if (shouldRun) {
            triggerTouchReset();
        }
        return shouldRun;
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
            triggerTouchReset();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void triggerTouchReset() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastFixTriggerAt < FIX_TRIGGER_DEBOUNCE_MS) {
            return;
        }
        lastFixTriggerAt = now;
        vibrate(60);
        GhostFixer.run(this, null);
    }

    private void refreshShakeListener() {
        if (GhostFixPreferences.shakeEnabled(this)) {
            registerShakeListener();
        } else {
            unregisterShakeListener();
        }
    }

    private void registerShakeListener() {
        if (listeningForShake || sensorManager == null || accelerometer == null) {
            return;
        }
        sensorManager.registerListener(
                this,
                accelerometer,
                SensorManager.SENSOR_DELAY_NORMAL
        );
        listeningForShake = true;
    }

    private void unregisterShakeListener() {
        if (!listeningForShake || sensorManager == null) {
            return;
        }
        sensorManager.unregisterListener(this);
        listeningForShake = false;
    }

    private void handleKeyDown(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            isVolumeUpHeld = true;
            volumeUpPressedTime = SystemClock.elapsedRealtime();
            if (isVolumeDownHeld) {
                handler.removeCallbacks(pendingVolumeUpSingleRunnable);
                handler.removeCallbacks(pendingVolumeDownSingleRunnable);
                handler.removeCallbacks(volumeDownLongPressRunnable);
                chordActive = true;
                chordLongPressed = false;
                if (overlayView != null) {
                    overlayView.setTrayPressed(true);
                }

                // If SIL button is selected, start repeating backspace
                if (isEditing && !isTemplateMenuOpen && isTrayFocused && selectedTrayIndex == 0) {
                    handler.post(repeatingBackspaceRunnable);
                    chordLongPressed = true;
                } else {
                    handler.postDelayed(chordLongPressRunnable, CHORD_LONG_PRESS_TIMEOUT_MS);
                    handler.postDelayed(chordExitRunnable, EXIT_LONG_PRESS_TIMEOUT_MS);
                }
            } else {
                if (currentMode() == GhostFixPreferences.MODE_GUARDED_TOUCH
                        && overlayView != null) {
                    overlayView.setGuardHeld(true, getString(R.string.overlay_touch_armed));
                }
                handler.postDelayed(volumeUpLongPressRunnable, LONG_PRESS_TIMEOUT_MS);
            }
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            isVolumeDownHeld = true;
            volumeDownPressedTime = SystemClock.elapsedRealtime();
            if (isVolumeUpHeld) {
                handler.removeCallbacks(pendingVolumeUpSingleRunnable);
                handler.removeCallbacks(pendingVolumeDownSingleRunnable);
                handler.removeCallbacks(volumeUpLongPressRunnable);
                chordActive = true;
                chordLongPressed = false;
                if (overlayView != null) {
                    overlayView.setGuardHeld(false, getString(R.string.overlay_touch_locked));
                }
                if (overlayView != null) {
                    overlayView.setTrayPressed(true);
                }

                // If SIL button is selected, start repeating backspace
                if (isEditing && !isTemplateMenuOpen && isTrayFocused && selectedTrayIndex == 0) {
                    handler.post(repeatingBackspaceRunnable);
                    chordLongPressed = true;
                } else {
                    handler.postDelayed(chordLongPressRunnable, CHORD_LONG_PRESS_TIMEOUT_MS);
                    handler.postDelayed(chordExitRunnable, EXIT_LONG_PRESS_TIMEOUT_MS);
                }
            } else {
                handler.postDelayed(volumeDownLongPressRunnable, LONG_PRESS_TIMEOUT_MS);
            }
        }
    }

    private void handleKeyUp(int keyCode) {
        long now = SystemClock.elapsedRealtime();
        
        handler.removeCallbacks(repeatingBackspaceRunnable);
        if (overlayView != null) {
            overlayView.setTrayPressed(false);
        }

        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (!isVolumeUpHeld) return;
            isVolumeUpHeld = false;
            if (overlayView != null) {
                overlayView.setGuardHeld(false, getString(R.string.overlay_touch_locked));
            }
            handler.removeCallbacks(volumeUpLongPressRunnable);
            handler.removeCallbacks(chordLongPressRunnable);
            handler.removeCallbacks(chordExitRunnable);

            if (chordActive) {
                if (!isVolumeUpHeld && !isVolumeDownHeld) {
                    if (!chordLongPressed) {
                        if (currentMode() == GhostFixPreferences.MODE_GUARDED_TOUCH) {
                            GhostFixPreferences.setEmergencyMode(
                                    this,
                                    GhostFixPreferences.MODE_KEYPAD
                            );
                        } else {
                            performClickAction();
                        }
                    }
                    chordActive = false;
                    chordLongPressed = false;
                }
            } else {
                long duration = now - volumeUpPressedTime;
                if (duration < LONG_PRESS_TIMEOUT_MS
                        && currentMode() == GhostFixPreferences.MODE_KEYPAD) {
                    handleVolumeUpShortPress(now);
                }
            }
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (!isVolumeDownHeld) return;
            isVolumeDownHeld = false;
            handler.removeCallbacks(volumeDownLongPressRunnable);
            handler.removeCallbacks(chordLongPressRunnable);
            handler.removeCallbacks(chordExitRunnable);

            if (chordActive) {
                if (!isVolumeUpHeld && !isVolumeDownHeld) {
                    if (!chordLongPressed) {
                        if (currentMode() == GhostFixPreferences.MODE_GUARDED_TOUCH) {
                            GhostFixPreferences.setEmergencyMode(
                                    this,
                                    GhostFixPreferences.MODE_KEYPAD
                            );
                        } else {
                            performClickAction();
                        }
                    }
                    chordActive = false;
                    chordLongPressed = false;
                }
            } else {
                long duration = now - volumeDownPressedTime;
                if (duration < LONG_PRESS_TIMEOUT_MS) {
                    if (currentMode() == GhostFixPreferences.MODE_GUARDED_TOUCH) {
                        performGlobalAction(GLOBAL_ACTION_BACK);
                        vibrate(60);
                    } else {
                        handleVolumeDownShortPress(now);
                    }
                }
            }
        }
    }

    private void handleVolumeUpShortPress(long now) {
        if (isTrayFocused || isEditing || isTemplateMenuOpen || isBottomNavFocused) {
            lastVolumeUpUpTime = 0;
            navigateSelection(-1);
            return;
        }
        if (now - lastVolumeUpUpTime < DOUBLE_CLICK_WINDOW_MS) {
            handler.removeCallbacks(pendingVolumeUpSingleRunnable);
            lastVolumeUpUpTime = 0;
            dispatchSwipe(SwipeDirection.RIGHT);
            vibrate(70);
            return;
        }
        lastVolumeUpUpTime = now;
        handler.postDelayed(pendingVolumeUpSingleRunnable, DOUBLE_CLICK_WINDOW_MS);
    }

    private void handleVolumeDownShortPress(long now) {
        if (isTrayFocused || isEditing || isTemplateMenuOpen || isBottomNavFocused) {
            lastVolumeDownUpTime = 0;
            navigateSelection(1);
            return;
        }
        if (now - lastVolumeDownUpTime < DOUBLE_CLICK_WINDOW_MS) {
            handler.removeCallbacks(pendingVolumeDownSingleRunnable);
            lastVolumeDownUpTime = 0;
            handleVolumeDownDoubleClick();
            return;
        }
        lastVolumeDownUpTime = now;
        handler.postDelayed(pendingVolumeDownSingleRunnable, DOUBLE_CLICK_WINDOW_MS);
    }

    private void handleVolumeDownDoubleClick() {
        if ("com.instagram.android".equals(activePackageName)) {
            vibrate(80);
            if (isBottomNavFocused) {
                isBottomNavFocused = false;
                useVirtualInstagramNav = false;
                recycleNodes(bottomNavNodes);
                bottomNavNodes.clear();
                refreshKeypadNodes();
            } else {
                List<AccessibilityNodeInfo> nav = collectBottomNavNodes();
                isBottomNavFocused = true;
                recycleNodes(bottomNavNodes);
                bottomNavNodes.clear();
                useVirtualInstagramNav = nav.size() < 3 || nav.size() > 7;
                if (useVirtualInstagramNav) {
                    recycleNodes(nav);
                } else {
                    bottomNavNodes.addAll(nav);
                }

                isTrayFocused = false;
                selectedTrayIndex = -1;
                selectedIndex = 0;
                updateSelectedNode();
            }
        } else {
            dispatchSwipe(SwipeDirection.LEFT);
            vibrate(70);
        }
    }

    private List<AccessibilityNodeInfo> collectBottomNavNodes() {
        List<AccessibilityNodeInfo> all = collectCurrentNodes(false);
        List<AccessibilityNodeInfo> navNodes = new ArrayList<>();
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int minBarY = (int) (screenHeight * 0.84f);
        
        Rect bounds = new Rect();
        for (AccessibilityNodeInfo node : all) {
            node.getBoundsInScreen(bounds);
            String label = nodeLabel(node);
            boolean usableLabel = label != null
                    && !label.isBlank()
                    && !getString(R.string.overlay_selected_item).equals(label);
            boolean likelyNavigationItem = node.isClickable()
                    && usableLabel
                    && bounds.centerY() >= minBarY
                    && bounds.bottom >= screenHeight - dp(72)
                    && bounds.width() > dp(36)
                    && bounds.width() < screenWidth * 0.5f
                    && bounds.height() < dp(100);
            if (likelyNavigationItem) {
                navNodes.add(node);
            } else {
                node.recycle();
            }
        }
        
        navNodes.sort(Comparator.comparingInt(node -> {
            Rect b = new Rect();
            node.getBoundsInScreen(b);
            return b.left;
        }));
        return navNodes;
    }

    private void dispatchTap(float x, float y) {
        dispatchGesturePath(x, y, x, y, 70);
    }

    private void performVirtualInstagramNavClick() {
        Rect bounds = virtualInstagramNavBounds(selectedIndex);
        dispatchTap(bounds.exactCenterX(), bounds.exactCenterY());
        vibrate(55);
        scheduleNodeRefresh(450);
    }

    private Rect virtualInstagramNavBounds(int index) {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        int safeIndex = Math.max(0, Math.min(index, INSTAGRAM_NAV_COUNT - 1));
        float segmentWidth = width / (float) INSTAGRAM_NAV_COUNT;
        int left = Math.round(safeIndex * segmentWidth + dp(8));
        int right = Math.round((safeIndex + 1) * segmentWidth - dp(8));
        int top = height - dp(80);
        int bottom = height - dp(20);
        return new Rect(left, top, right, bottom);
    }

    private void dispatchSwipe(SwipeDirection direction) {
        int width = getResources().getDisplayMetrics().widthPixels;
        int height = getResources().getDisplayMetrics().heightPixels;
        float startX = width * 0.5f;
        float startY = height * 0.55f;
        float endX = startX;
        float endY = startY;

        switch (direction) {
            case UP:
                startY = height * 0.78f;
                endY = height * 0.25f;
                break;
            case DOWN:
                startY = height * 0.25f;
                endY = height * 0.78f;
                break;
            case LEFT:
                startX = width * 0.84f;
                endX = width * 0.16f;
                break;
            case RIGHT:
                startX = width * 0.16f;
                endX = width * 0.84f;
                break;
        }
        dispatchGesturePath(startX, startY, endX, endY, 280);
    }

    private void dispatchGesturePath(
            float startX,
            float startY,
            float endX,
            float endY,
            long durationMs
    ) {
        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs))
                .build();

        setOverlayPassthrough(true);
        Runnable restore = () -> setOverlayPassthrough(false);
        handler.postDelayed(() -> {
            boolean accepted = dispatchGesture(
                    gesture,
                    new GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription gestureDescription) {
                            handler.removeCallbacks(restore);
                            setOverlayPassthrough(false);
                            scheduleNodeRefresh(180);
                            vibrate(35);
                        }

                        @Override
                        public void onCancelled(GestureDescription gestureDescription) {
                            handler.removeCallbacks(restore);
                            setOverlayPassthrough(false);
                            vibrate(20);
                        }
                    },
                    null
            );
            if (!accepted) {
                handler.removeCallbacks(restore);
                setOverlayPassthrough(false);
            }
        }, 35);
        handler.postDelayed(restore, durationMs + 650);
    }

    private void setOverlayPassthrough(boolean passthrough) {
        if (overlayView == null
                || windowManager == null
                || overlayTemporarilyPassthrough == passthrough) {
            return;
        }
        try {
            WindowManager.LayoutParams params =
                    (WindowManager.LayoutParams) overlayView.getLayoutParams();
            if (params == null) {
                return;
            }
            if (passthrough) {
                params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            } else {
                params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            windowManager.updateViewLayout(overlayView, params);
            overlayTemporarilyPassthrough = passthrough;
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to update emergency overlay touchability", exception);
            overlayTemporarilyPassthrough = false;
        }
    }

    private int getTrayItemsCount() {
        return isTemplateMenuOpen ? templates.length : (isEditing ? 5 : installedApps.size());
    }

    private void navigateSelection(int direction) {
        if ("com.instagram.android".equals(activePackageName) && !isBottomNavFocused && !isTrayFocused && !isEditing) {
            dispatchSwipe(direction > 0 ? SwipeDirection.UP : SwipeDirection.DOWN);
            vibrate(40);
            return;
        }

        if (isBottomNavFocused) {
            int count = useVirtualInstagramNav ? INSTAGRAM_NAV_COUNT : keypadNodes.size();
            if (count > 0) {
                selectedIndex = (selectedIndex + direction + count) % count;
                updateSelectedNode();
                vibrate(25);
            }
            return;
        }

        int totalNodes = keypadNodes.size();

        if (isTrayFocused) {
            int trayCount = getTrayItemsCount();
            int candidate = selectedTrayIndex + direction;

            if (candidate < 0) {
                isTrayFocused = false;
                selectedTrayIndex = -1;
                if (overlayView != null && !isEditing) {
                    overlayView.setTrayExpanded(false);
                }
                selectedIndex = totalNodes > 0 ? totalNodes - 1 : 0;
            } else if (candidate >= trayCount) {
                isTrayFocused = false;
                selectedTrayIndex = -1;
                if (overlayView != null && !isEditing) {
                    overlayView.setTrayExpanded(false);
                }
                selectedIndex = 0;
            } else {
                selectedTrayIndex = candidate;
            }
            updateSelectedNode();
            vibrate(25);
            return;
        }

        if (totalNodes == 0) {
            isTrayFocused = true;
            selectedTrayIndex = 0;
            selectedIndex = 0;
            updateSelectedNode();
            vibrate(25);
            return;
        }

        int candidate = selectedIndex + direction;
        if (candidate < 0) {
            if (scrollScreen(false)) {
                selectedIndex = 0;
                scheduleNodeRefresh(280);
                vibrate(25);
                return;
            }
            isTrayFocused = true;
            int trayCount = getTrayItemsCount();
            selectedTrayIndex = trayCount - 1;
            selectedIndex = totalNodes;
        } else if (candidate >= totalNodes) {
            if (scrollScreen(true)) {
                selectedIndex = totalNodes - 1;
                scheduleNodeRefresh(280);
                vibrate(25);
                return;
            }
            isTrayFocused = true;
            selectedTrayIndex = 0;
            selectedIndex = totalNodes;
        } else {
            selectedIndex = candidate;
        }

        updateSelectedNode();
        vibrate(25);
    }

    private void performClickAction() {
        if (isTrayFocused) {
            executeTrayAction(selectedTrayIndex);
        } else if (isBottomNavFocused && useVirtualInstagramNav) {
            performVirtualInstagramNavClick();
        } else {
            int totalNodes = keypadNodes.size();
            if (selectedIndex < totalNodes) {
                AccessibilityNodeInfo node = keypadNodes.get(selectedIndex);
                boolean clicked = performClick(node);
                vibrate(clicked ? 55 : 20);
                if (clicked) {
                    scheduleNodeRefresh(250);
                }
            }
        }
    }

    private void executeTrayAction(int trayIndex) {
        vibrate(45);
        if (isTemplateMenuOpen) {
            if (trayIndex >= 0 && trayIndex < templates.length) {
                pasteText(templates[trayIndex]);
                isTemplateMenuOpen = false;
                if (overlayView != null) {
                    overlayView.setTemplatesOpen(false, 0);
                }
                isTrayFocused = false;
                selectedTrayIndex = -1;
                refreshKeypadNodes();
            }
        } else if (isEditing) {
            switch (trayIndex) {
                case 0: // SIL
                    performBackspace();
                    break;
                case 1: // BOSLUK
                    performSpace();
                    break;
                case 2: // ENTER
                    performEnter();
                    break;
                case 3: // SABLON
                    isTemplateMenuOpen = true;
                    selectedTrayIndex = 0; // Focus first template
                    updateSelectedNode();
                    break;
                case 4: // KAPAT
                    clearActiveInputFocus();
                    isTrayFocused = false;
                    selectedTrayIndex = -1;
                    if (overlayView != null) {
                        overlayView.setTrayExpanded(false);
                    }
                    break;
            }
        } else {
            if (trayIndex >= 0 && trayIndex < installedApps.size()) {
                executeAppItem(installedApps.get(trayIndex));
            }
        }
    }

    private void executeAppItem(AppInfo item) {
        switch (item.action) {
            case SWIPE_UP:
                dispatchSwipe(SwipeDirection.UP);
                break;
            case SWIPE_DOWN:
                dispatchSwipe(SwipeDirection.DOWN);
                break;
            case SWIPE_LEFT:
                dispatchSwipe(SwipeDirection.LEFT);
                break;
            case SWIPE_RIGHT:
                dispatchSwipe(SwipeDirection.RIGHT);
                break;
            case LAUNCH:
                launchApp(item);
                break;
        }
    }

    private void performBackspace() {
        AccessibilityNodeInfo root = getControllableRoot();
        if (root == null) return;
        AccessibilityNodeInfo focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focusedNode != null) {
            if (focusedNode.isEditable()) {
                CharSequence text = focusedNode.getText();
                if (text != null && text.length() > 0) {
                    CharSequence newText = text.subSequence(0, text.length() - 1);
                    Bundle arguments = new Bundle();
                    arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText);
                    focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);

                    Bundle selArgs = new Bundle();
                    selArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newText.length());
                    selArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newText.length());
                    focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs);
                }
            }
            focusedNode.recycle();
        }
        root.recycle();
    }

    private void performSpace() {
        AccessibilityNodeInfo root = getControllableRoot();
        if (root == null) return;
        AccessibilityNodeInfo focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focusedNode != null) {
            if (focusedNode.isEditable()) {
                CharSequence text = focusedNode.getText();
                String newText = (text == null ? "" : text.toString()) + " ";
                Bundle arguments = new Bundle();
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText);
                focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);

                Bundle selArgs = new Bundle();
                selArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newText.length());
                selArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newText.length());
                focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs);
            }
            focusedNode.recycle();
        }
        root.recycle();
    }

    private void performEnter() {
        AccessibilityNodeInfo root = getControllableRoot();
        if (root == null) return;
        AccessibilityNodeInfo focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focusedNode != null) {
            try {
                Class<?> actionClass = Class.forName("android.view.accessibility.AccessibilityNodeInfo$AccessibilityAction");
                java.lang.reflect.Field field = actionClass.getField("ACTION_IME_ACTION");
                Object action = field.get(null);
                java.lang.reflect.Method getIdMethod = actionClass.getMethod("getId");
                int id = (Integer) getIdMethod.invoke(action);
                focusedNode.performAction(id);
            } catch (Exception e) {
                Log.e(TAG, "Failed to perform IME action via reflection", e);
            }
            focusedNode.recycle();
        }
        root.recycle();
    }

    private void pasteText(String phrase) {
        AccessibilityNodeInfo root = getControllableRoot();
        if (root == null) return;
        AccessibilityNodeInfo focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focusedNode != null) {
            if (focusedNode.isEditable()) {
                CharSequence text = focusedNode.getText();
                String prefix = (text == null || text.length() == 0) ? "" : text.toString();
                if (prefix.length() > 0 && !prefix.endsWith(" ")) {
                    prefix += " ";
                }
                String newText = prefix + phrase;
                Bundle arguments = new Bundle();
                arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText);
                focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);

                Bundle selArgs = new Bundle();
                selArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newText.length());
                selArgs.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newText.length());
                focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs);
            }
            focusedNode.recycle();
        }
        root.recycle();
    }

    private void clearActiveInputFocus() {
        AccessibilityNodeInfo root = getControllableRoot();
        if (root != null) {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused != null) {
                focused.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS);
                focused.recycle();
            }
            root.recycle();
        }
        refreshKeypadNodes();
    }

    private void launchApp(AppInfo app) {
        Log.d(TAG, "Launching app: " + app.packageName + " / " + app.componentName);
        if (app.componentName == null) {
            return;
        }

        Intent launchIntent = Intent.makeMainActivity(app.componentName);
        launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
        );
        try {
            startActivity(launchIntent);
            activePackageName = app.packageName;
            isTrayFocused = false;
            selectedTrayIndex = -1;
            selectedIndex = 0;
            if (overlayView != null) {
                overlayView.setTrayExpanded(false);
            }
            scheduleNodeRefresh(350);
        } catch (RuntimeException exception) {
            Log.e(TAG, "Unable to launch " + app.componentName, exception);
            vibrate(180);
        }
    }

    private boolean performClick(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
        while (current != null) {
            if (current.isClickable() && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                current.recycle();
                return true;
            }
            AccessibilityNodeInfo parent = current.getParent();
            current.recycle();
            current = parent;
        }
        return false;
    }

    private boolean scrollScreen(boolean forward) {
        List<AccessibilityNodeInfo> scrollables = collectCurrentNodes(true);
        AccessibilityNodeInfo target = null;
        long largestArea = -1;
        Rect bounds = new Rect();
        for (AccessibilityNodeInfo node : scrollables) {
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
        recycleNodes(scrollables);
        if (!scrolled) {
            dispatchSwipe(forward ? SwipeDirection.UP : SwipeDirection.DOWN);
            return true;
        }
        return scrolled;
    }

    private void refreshKeypadNodes() {
        if (!isEmergencyEnabled() || currentMode() != GhostFixPreferences.MODE_KEYPAD || overlayView == null) {
            return;
        }

        int previousIndex = selectedIndex;
        int previousTrayIndex = selectedTrayIndex;
        recycleKeypadNodes();

        AccessibilityNodeInfo root = getControllableRoot();
        if (root != null) {
            CharSequence packageName = root.getPackageName();
            if (packageName != null) {
                activePackageName = packageName.toString();
            }
            root.recycle();
        }
        detectInputFocusState();

        if (isBottomNavFocused && "com.instagram.android".equals(activePackageName)) {
            List<AccessibilityNodeInfo> nav = collectBottomNavNodes();
            useVirtualInstagramNav = nav.size() < 3 || nav.size() > 7;
            if (useVirtualInstagramNav) {
                recycleNodes(nav);
            } else {
                keypadNodes.addAll(nav);
            }
        } else if (isBottomNavFocused) {
            isBottomNavFocused = false;
            useVirtualInstagramNav = false;
        }

        if (!isBottomNavFocused) {
            List<AccessibilityNodeInfo> allNodes = collectCurrentNodes(false);
            for (AccessibilityNodeInfo node : allNodes) {
                if (isKeypadTarget(node)) {
                    keypadNodes.add(node);
                } else {
                    node.recycle();
                }
            }

            keypadNodes.sort(Comparator.comparingInt((AccessibilityNodeInfo node) -> {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                return bounds.top;
            }).thenComparingInt(node -> {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                return bounds.left;
            }));
        }

        overlayView.setKeyboardMode(isEditing);
        overlayView.setApps(installedApps);

        int totalNodes = isBottomNavFocused && useVirtualInstagramNav
                ? INSTAGRAM_NAV_COUNT
                : keypadNodes.size();

        if (isTrayFocused) {
            int trayCount = getTrayItemsCount();
            selectedTrayIndex = Math.max(0, Math.min(previousTrayIndex, trayCount - 1));
            selectedIndex = totalNodes;
        } else {
            if (totalNodes == 0) {
                isTrayFocused = true;
                selectedTrayIndex = 0;
                selectedIndex = 0;
            } else {
                selectedIndex = Math.max(0, Math.min(previousIndex, totalNodes - 1));
            }
        }
        
        updateSelectedNode();
    }

    private void detectInputFocusState() {
        AccessibilityNodeInfo root = getControllableRoot();
        isEditing = false;
        if (root != null) {
            AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focused != null) {
                if (focused.isEditable()) {
                    isEditing = true;
                }
                focused.recycle();
            }
            root.recycle();
        }
    }

    private void updateSelectedNode() {
        if (overlayView == null) {
            return;
        }

        int totalNodes = isBottomNavFocused && useVirtualInstagramNav
                ? INSTAGRAM_NAV_COUNT
                : keypadNodes.size();

        if (isTrayFocused) {
            overlayView.clearSelection();
            overlayView.setTrayExpanded(true);
            overlayView.setSelectedTrayIndex(selectedTrayIndex);
            if (isTemplateMenuOpen) {
                overlayView.setTemplatesOpen(true, selectedTrayIndex);
            } else {
                overlayView.setTemplatesOpen(false, 0);
            }
        } else if (isBottomNavFocused && useVirtualInstagramNav) {
            overlayView.setSelection(
                    virtualInstagramNavBounds(selectedIndex),
                    getString(R.string.overlay_instagram_tab, selectedIndex + 1)
            );
            overlayView.setSelectedTrayIndex(-1);
            overlayView.setTemplatesOpen(false, 0);
            overlayView.setTrayExpanded(false);
        } else {
            if (selectedIndex < totalNodes) {
                AccessibilityNodeInfo node = keypadNodes.get(selectedIndex);
                node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                overlayView.setSelection(bounds, nodeLabel(node));
            } else {
                overlayView.clearSelection();
            }
            overlayView.setSelectedTrayIndex(-1);
            overlayView.setTemplatesOpen(isTemplateMenuOpen, 0);
            overlayView.setTrayExpanded(isEditing);
        }
    }

    private boolean isKeypadTarget(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return node.isVisibleToUser()
                && !bounds.isEmpty()
                && (node.isClickable() || node.isEditable() || node.isScrollable());
    }

    private List<AccessibilityNodeInfo> collectCurrentNodes(boolean scrollablesOnly) {
        List<AccessibilityNodeInfo> result = new ArrayList<>();
        AccessibilityNodeInfo root = getControllableRoot();
        if (root == null) {
            return result;
        }
        collectNodes(root, result, scrollablesOnly);
        root.recycle();
        return result;
    }

    private AccessibilityNodeInfo getControllableRoot() {
        KeyguardManager keyguardManager =
                (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (keyguardManager != null && keyguardManager.isDeviceLocked()) {
            return getRootInActiveWindow();
        }

        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                    continue;
                }
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) {
                    continue;
                }
                CharSequence packageName = root.getPackageName();
                if (packageName == null || !getPackageName().contentEquals(packageName)) {
                    return root;
                }
                root.recycle();
            }
        }
        return getRootInActiveWindow();
    }

    private void collectNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> result, boolean scrollablesOnly) {
        if (node.isVisibleToUser() && (!scrollablesOnly || node.isScrollable())) {
            result.add(AccessibilityNodeInfo.obtain(node));
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                collectNodes(child, result, scrollablesOnly);
                child.recycle();
            }
        }
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

    private void openLauncherTray() {
        if (!isEmergencyEnabled()) {
            return;
        }
        keepLauncherFocusedUntil = SystemClock.uptimeMillis() + 1200;
        showEmergencyOverlay();
        loadInstalledApps();
        isTemplateMenuOpen = false;
        isBottomNavFocused = false;
        useVirtualInstagramNav = false;
        recycleNodes(bottomNavNodes);
        bottomNavNodes.clear();
        isTrayFocused = true;
        selectedTrayIndex = 0;
        if (overlayView != null) {
            overlayView.setMode(
                    GhostFixPreferences.MODE_KEYPAD,
                    getString(R.string.overlay_keypad_mode)
            );
            overlayView.setKeyboardMode(false);
            overlayView.setApps(installedApps);
            overlayView.setTemplatesOpen(false, 0);
            overlayView.setTrayExpanded(true);
            overlayView.setSelectedTrayIndex(0);
        }
        GhostFixPreferences.clearLauncherRequest(this);
    }

    private void openPendingLauncherRequest() {
        if (GhostFixPreferences.launcherRequestPending(this)) {
            openLauncherTray();
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
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            params.setBlurBehindRadius(0);
        }
        params.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.setFitInsetsTypes(0);
            params.setFitInsetsSides(0);
            params.setFitInsetsIgnoringVisibility(true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
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
        isVolumeUpHeld = false;
        isVolumeDownHeld = false;
        chordActive = false;
        chordLongPressed = false;
    }

    private int currentMode() {
        return GhostFixPreferences.emergencyMode(this);
    }

    private boolean isEmergencyEnabled() {
        return GhostFixPreferences.emergencyEnabled(this);
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

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    @SuppressWarnings("deprecation")
    private void vibrate(long durationMs) {
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(durationMs);
        }
    }
}
