package com.egehan.a54ghostfix;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class GhostTouchBypassService extends AccessibilityService implements EmergencyOverlayView.Listener {
    private static final String TAG = "GhostTouchBypass";

    public static class AppInfo {
        public String name;
        public String packageName;
        public Drawable icon;

        public AppInfo(String name, String packageName, Drawable icon) {
            this.name = name;
            this.packageName = packageName;
            this.icon = icon;
        }
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<AccessibilityNodeInfo> keypadNodes = new ArrayList<>();
    private final List<AppInfo> installedApps = new ArrayList<>();
    private int selectedIndex = 0;

    private SharedPreferences preferences;
    private WindowManager windowManager;
    private EmergencyOverlayView overlayView;

    // State flags
    private boolean isEditing = false;
    private boolean isTemplateMenuOpen = false;
    private boolean isTrayFocused = false;
    private int selectedTrayIndex = -1;

    private final String[] templates = {
            "Merhaba!",
            "Tamam",
            "Yoldayım, geliyorum.",
            "Seni sonra arayacağım.",
            "Neredesin?",
            "Evet",
            "Hayır"
    };

    // Key states
    private boolean isVolumeUpHeld = false;
    private boolean isVolumeDownHeld = false;
    private long volumeUpPressedTime = 0;
    private long volumeDownPressedTime = 0;
    private boolean chordActive = false;
    private boolean chordLongPressed = false;

    // Long press delays
    private static final long LONG_PRESS_TIMEOUT_MS = 2000;

    // Runnables
    private final Runnable volumeUpLongPressRunnable = () -> {
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
        Log.d(TAG, "Volume Down Long Press -> BACK");
        vibrate(100);
        performGlobalAction(GLOBAL_ACTION_BACK);
    };

    private final Runnable chordLongPressRunnable = () -> {
        Log.d(TAG, "Volume Up + Down Long Press -> RECENTS");
        vibrate(150);
        chordLongPressed = true;
        performGlobalAction(GLOBAL_ACTION_RECENTS);
    };

    private final Runnable repeatingBackspaceRunnable = new Runnable() {
        @Override
        public void run() {
            performBackspace();
            vibrate(15);
            handler.postDelayed(this, 100);
        }
    };

    private final Runnable refreshNodesRunnable = this::refreshKeypadNodes;

    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceChangeListener =
            (sharedPreferences, key) -> handler.post(() -> {
                if (GhostFixPreferences.KEY_EMERGENCY_ENABLED.equals(key)
                        || GhostFixPreferences.KEY_EMERGENCY_MODE.equals(key)) {
                    updateEmergencyState();
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

        AccessibilityServiceInfo info = getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);

        preferences = GhostFixPreferences.prefs(this);
        preferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener);

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        
        loadInstalledApps();
        updateEmergencyState();
    }

    private void loadInstalledApps() {
        installedApps.clear();
        PackageManager pm = getPackageManager();
        
        String[] targetPackages = {
            "com.whatsapp",
            "com.zhiliaoapp.musically", // TikTok
            "com.instagram.android",
            "com.android.chrome",
            "com.google.android.youtube",
            "com.sec.android.app.camera" // Camera
        };

        for (String pkg : targetPackages) {
            try {
                android.content.pm.ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
                String name = pm.getApplicationLabel(appInfo).toString();
                Drawable icon = pm.getApplicationIcon(appInfo);
                installedApps.add(new AppInfo(name, pkg, icon));
            } catch (PackageManager.NameNotFoundException e) {
                // If not installed, load default fallback icon so user can download it from Play Store
                String name;
                if (pkg.contains("whatsapp")) name = "WhatsApp";
                else if (pkg.contains("musically")) name = "TikTok";
                else if (pkg.contains("instagram")) name = "Instagram";
                else if (pkg.contains("chrome")) name = "Chrome";
                else if (pkg.contains("youtube")) name = "YouTube";
                else name = "Camera";
                
                Drawable icon = pm.getDefaultActivityIcon();
                installedApps.add(new AppInfo(name, pkg, icon));
            }
        }
        Log.d(TAG, "Loaded " + installedApps.size() + " target apps.");
    }

    @Override
    public void onDestroy() {
        if (preferences != null) {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
        }
        handler.removeCallbacks(repeatingBackspaceRunnable);
        hideEmergencyOverlay();
        recycleKeypadNodes();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isEmergencyEnabled() || currentMode() != GhostFixPreferences.MODE_KEYPAD || event == null) {
            return;
        }

        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Reset focus states on window change to avoid locking selection
            isTrayFocused = false;
            selectedTrayIndex = -1;
            selectedIndex = 0;
            isTemplateMenuOpen = false;
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
    }

    @Override
    public void onTrustedSwipe(float startX, float startY, float endX, float endY) {
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (!isEmergencyEnabled()) {
            return false;
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

    private void handleKeyDown(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            isVolumeUpHeld = true;
            volumeUpPressedTime = SystemClock.elapsedRealtime();
            if (isVolumeDownHeld) {
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
                    handler.postDelayed(chordLongPressRunnable, LONG_PRESS_TIMEOUT_MS);
                }
            } else {
                handler.postDelayed(volumeUpLongPressRunnable, LONG_PRESS_TIMEOUT_MS);
            }
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            isVolumeDownHeld = true;
            volumeDownPressedTime = SystemClock.elapsedRealtime();
            if (isVolumeUpHeld) {
                handler.removeCallbacks(volumeUpLongPressRunnable);
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
                    handler.postDelayed(chordLongPressRunnable, LONG_PRESS_TIMEOUT_MS);
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
            handler.removeCallbacks(volumeUpLongPressRunnable);
            handler.removeCallbacks(chordLongPressRunnable);

            if (chordActive) {
                if (!isVolumeUpHeld && !isVolumeDownHeld) {
                    if (!chordLongPressed) {
                        performClickAction();
                    }
                    chordActive = false;
                    chordLongPressed = false;
                }
            } else {
                long duration = now - volumeUpPressedTime;
                if (duration < LONG_PRESS_TIMEOUT_MS) {
                    navigateSelection(-1);
                }
            }
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (!isVolumeDownHeld) return;
            isVolumeDownHeld = false;
            handler.removeCallbacks(volumeDownLongPressRunnable);
            handler.removeCallbacks(chordLongPressRunnable);

            if (chordActive) {
                if (!isVolumeUpHeld && !isVolumeDownHeld) {
                    if (!chordLongPressed) {
                        performClickAction();
                    }
                    chordActive = false;
                    chordLongPressed = false;
                }
            } else {
                long duration = now - volumeDownPressedTime;
                if (duration < LONG_PRESS_TIMEOUT_MS) {
                    navigateSelection(1);
                }
            }
        }
    }

    private int getTrayItemsCount() {
        return isTemplateMenuOpen ? templates.length : (isEditing ? 5 : installedApps.size());
    }

    private void navigateSelection(int direction) {
        int totalNodes = keypadNodes.size();

        if (isTrayFocused) {
            // Navigate inside the tray grid/items
            int trayCount = isTemplateMenuOpen ? templates.length : (isEditing ? 5 : installedApps.size());
            int candidate = selectedTrayIndex + direction;

            if (candidate < 0) {
                // Exit tray, focus last node on screen
                isTrayFocused = false;
                selectedTrayIndex = -1;
                if (overlayView != null && !isEditing) {
                    overlayView.setTrayExpanded(false);
                }
                selectedIndex = totalNodes > 0 ? totalNodes - 1 : 0;
            } else if (candidate >= trayCount) {
                // Exit tray, focus first node on screen
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

        // Navigate screen nodes
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
            // Focus launcher tray from bottom
            isTrayFocused = true;
            int trayCount = isTemplateMenuOpen ? templates.length : (isEditing ? 5 : installedApps.size());
            selectedTrayIndex = trayCount - 1;
            selectedIndex = totalNodes;
        } else if (candidate >= totalNodes) {
            if (scrollScreen(true)) {
                selectedIndex = totalNodes - 1;
                scheduleNodeRefresh(280);
                vibrate(25);
                return;
            }
            // Focus launcher tray from top
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
                AppInfo app = installedApps.get(trayIndex);
                launchApp(app.packageName);
            }
        }
    }

    private void performBackspace() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
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
        AccessibilityNodeInfo root = getRootInActiveWindow();
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
        AccessibilityNodeInfo root = getRootInActiveWindow();
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
        AccessibilityNodeInfo root = getRootInActiveWindow();
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
        AccessibilityNodeInfo root = getRootInActiveWindow();
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

    private void launchApp(String packageName) {
        Log.d(TAG, "Launching app: " + packageName);
        Intent launchIntent;
        if ("com.instagram.android".equals(packageName)) {
            launchIntent = new Intent(Intent.ACTION_MAIN);
            launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            launchIntent.setPackage("com.instagram.android");
        } else {
            launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
        }

        if (launchIntent != null) {
            try {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launchIntent);
                
                // Reset state and minimize tray on success
                isTrayFocused = false;
                selectedTrayIndex = -1;
                selectedIndex = 0;
                if (overlayView != null) {
                    overlayView.setTrayExpanded(false);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to start intent, falling back to Play Store", e);
                launchPlayStoreFallback(packageName);
            }
        } else {
            launchPlayStoreFallback(packageName);
        }
    }

    private void launchPlayStoreFallback(String packageName) {
        try {
            Intent playStoreIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + packageName));
            playStoreIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(playStoreIntent);
        } catch (ActivityNotFoundException e) {
            Intent playStoreWebIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + packageName));
            playStoreWebIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(playStoreWebIntent);
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
        return scrolled;
    }

    private void refreshKeypadNodes() {
        if (!isEmergencyEnabled() || currentMode() != GhostFixPreferences.MODE_KEYPAD || overlayView == null) {
            return;
        }

        int previousIndex = selectedIndex;
        int previousTrayIndex = selectedTrayIndex;
        recycleKeypadNodes();

        detectInputFocusState();

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

        overlayView.setKeyboardMode(isEditing);
        overlayView.setApps(installedApps);

        int totalNodes = keypadNodes.size();

        if (isTrayFocused) {
            int trayCount = getTrayItemsCount();
            selectedTrayIndex = Math.max(0, Math.min(previousTrayIndex, trayCount - 1));
            selectedIndex = totalNodes; // set selection point on tray item
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
        AccessibilityNodeInfo root = getRootInActiveWindow();
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

        int totalNodes = keypadNodes.size();

        if (isTrayFocused) {
            overlayView.clearSelection();
            overlayView.setTrayExpanded(true);
            overlayView.setSelectedTrayIndex(selectedTrayIndex);
            if (isTemplateMenuOpen) {
                overlayView.setTemplatesOpen(true, selectedTrayIndex);
            } else {
                overlayView.setTemplatesOpen(false, 0);
            }
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
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return result;
        }
        collectNodes(root, result, scrollablesOnly);
        root.recycle();
        return result;
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

    @SuppressWarnings("deprecation")
    private void vibrate(long durationMs) {
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(durationMs);
        }
    }
}
